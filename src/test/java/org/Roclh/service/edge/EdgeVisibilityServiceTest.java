package org.Roclh.service.edge;

import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeMapDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeSniRowDto;
import org.Roclh.model.dto.edge.EdgeWarningDto;
import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeRoute;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeVisibilityServiceTest {

    private static final String SERVER_IP = "45.131.43.6";

    private EdgeConfigService cfgService;
    private EdgeRouteRepository routeRepo;
    private EdgeSiteRepository siteRepo;
    private EdgeDnsResolver dns;
    private EdgeVisibilityService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        cfgService = mock(EdgeConfigService.class);
        routeRepo = mock(EdgeRouteRepository.class);
        siteRepo = mock(EdgeSiteRepository.class);
        dns = mock(EdgeDnsResolver.class);
        service = new EdgeVisibilityService(cfgService, routeRepo, siteRepo, dns);

        ReflectionTestUtils.setField(service, "publicUrl", "https://pohr-roclh.xyz");
        ReflectionTestUtils.setField(service, "serverIpOverride", "");
        ReflectionTestUtils.setField(service, "telemtSocksPort", 10809);
        ReflectionTestUtils.setField(service, "serverPort", 8080);

        when(cfgService.getConfig()).thenReturn(config("127.0.0.1:8080"));
        when(dns.lookup(anyString())).thenAnswer(invocation -> lookup(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("Карта SNI: типы, DNS-проверка и предупреждения")
    void buildsEffectiveMapWithWarnings() {
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(
                route("htmlacademy.ru", "127.0.0.1", 8443, "tcp"),
                route("dodopizza.ru", "127.0.0.1", 10809, "tcp"),
                route("www.pohr-roclh.xyz", "127.0.0.1", 8444, "tcp")));
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("pohr-roclh.xyz"),
                site("www.pohr-roclh.xyz"),
                site("edge.pohr-roclh.xyz")));

        EdgeMapDto map = service.buildMap();

        assertThat(map.serverIp()).isEqualTo(SERVER_IP);
        assertThat(map.baseDomain()).isEqualTo("pohr-roclh.xyz");
        assertThat(map.routes()).hasSize(5);

        EdgeSniRowDto reality = row(map, "htmlacademy.ru");
        assertThat(reality.type()).isEqualTo(EdgeVisibilityService.TYPE_REALITY);
        assertThat(reality.resolves()).isFalse();
        assertThat(codes(reality.warnings()))
                .contains(EdgeVisibilityService.WARN_DNS_MISMATCH);

        EdgeSniRowDto telemt = row(map, "dodopizza.ru");
        assertThat(telemt.type()).isEqualTo(EdgeVisibilityService.TYPE_TELEMT);
        assertThat(codes(telemt.warnings()))
                .contains(EdgeVisibilityService.WARN_DNS_UNRESOLVED);

        EdgeSniRowDto caddy = row(map, "www.pohr-roclh.xyz");
        assertThat(caddy.type()).isEqualTo(EdgeVisibilityService.TYPE_CADDY);
        assertThat(caddy.resolves()).isTrue();
        assertThat(codes(caddy.warnings())).isEmpty();

        // Сайт без явного маршрута виден строкой карты и идёт через fallback.
        EdgeSniRowDto siteRow = row(map, "pohr-roclh.xyz");
        assertThat(siteRow.type()).isEqualTo(EdgeVisibilityService.TYPE_CADDY);
        assertThat(siteRow.target()).isEqualTo("127.0.0.1:8080");
        assertThat(codes(siteRow.warnings())).contains(EdgeVisibilityService.WARN_SITE_WITHOUT_ROUTE);

        assertThat(map.fallback().type()).isEqualTo(EdgeVisibilityService.TYPE_FALLBACK);
        assertThat(codes(map.fallback().warnings()))
                .contains(EdgeVisibilityService.WARN_FALLBACK_PANEL);

        List<String> global = codes(map.warnings());
        assertThat(global).contains(EdgeVisibilityService.WARN_EXPECTED_SNI_MISSING);
        assertThat(map.warnings())
                .anyMatch(w -> w.subject().equals("eu.pohr-roclh.xyz"));
        assertThat(map.warningCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Дубли SNI помечаются, даже если БД их не допускает")
    void marksDuplicateSni() {
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(
                route("htmlacademy.ru", "127.0.0.1", 8443, "tcp"),
                route("htmlacademy.ru", "127.0.0.1", 8444, "tcp")));
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of());

        EdgeMapDto map = service.buildMap();

        assertThat(map.routes()).hasSize(2);
        assertThat(map.routes()).allSatisfy(row -> assertThat(codes(row.warnings()))
                .contains(EdgeVisibilityService.WARN_DUPLICATE_SNI));
    }

    @Test
    @DisplayName("Fallback на Caddy не считается панелью")
    void fallbackToCaddyHasNoPanelWarning() {
        when(cfgService.getConfig()).thenReturn(config("127.0.0.1:8444"));
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of());
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(site("pohr-roclh.xyz")));

        EdgeMapDto map = service.buildMap();

        assertThat(codes(map.fallback().warnings()))
                .doesNotContain(EdgeVisibilityService.WARN_FALLBACK_PANEL);
    }

    @Test
    @DisplayName("configFiles и preview: same, diff и отсутствие записи на диск")
    void previewComputesDiffWithoutTouchingFiles() throws Exception {
        Path nginx = tempDir.resolve("nginx.conf");
        Path caddy = tempDir.resolve("Caddyfile");
        Files.writeString(nginx, "stream { listen 443; }\n", StandardCharsets.UTF_8);
        Files.writeString(caddy, "pohr-roclh.xyz {\n}\n", StandardCharsets.UTF_8);

        when(cfgService.nginxConfigPath()).thenReturn(nginx);
        when(cfgService.caddyConfigPath()).thenReturn(caddy);
        when(cfgService.buildNginxConfig()).thenReturn("stream { listen 443; }\n");
        when(cfgService.buildCaddyfile()).thenReturn("pohr-roclh.xyz {\n}\n");

        EdgeConfigFilesDto files = service.configFiles();
        assertThat(files.same()).isTrue();
        assertThat(files.nginx().generatedHash()).isEqualTo(EdgeVisibilityService.sha256(files.nginx().generatedText()));
        assertThat(files.nginx().diskHash()).isEqualTo(files.nginx().generatedHash());

        var nginxMtime = Files.getLastModifiedTime(nginx);
        var caddyMtime = Files.getLastModifiedTime(caddy);
        EdgePreviewDto same = service.preview();
        assertThat(same.same()).isTrue();
        assertThat(same.nginxDiff()).isEmpty();
        assertThat(Files.getLastModifiedTime(nginx)).isEqualTo(nginxMtime);
        assertThat(Files.getLastModifiedTime(caddy)).isEqualTo(caddyMtime);

        when(cfgService.buildNginxConfig()).thenReturn("stream { listen 443;\n  proxy_timeout 300s;\n}\n");
        EdgePreviewDto changed = service.preview();

        assertThat(changed.same()).isFalse();
        assertThat(changed.nginxDiff())
                .contains("--- nginx.conf (disk)")
                .contains("+++ nginx.conf (generated)")
                .contains("+  proxy_timeout 300s;");
        assertThat(Files.getLastModifiedTime(nginx)).isEqualTo(nginxMtime);
        assertThat(Files.readString(nginx, StandardCharsets.UTF_8)).isEqualTo("stream { listen 443; }\n");
    }

    @Test
    @DisplayName("Preview при отсутствующем файле показывает все строки как добавленные")
    void previewForMissingFileIsAllAdditions() {
        Path nginx = tempDir.resolve("missing-nginx.conf");
        Path caddy = tempDir.resolve("missing-Caddyfile");
        when(cfgService.nginxConfigPath()).thenReturn(nginx);
        when(cfgService.caddyConfigPath()).thenReturn(caddy);
        when(cfgService.buildNginxConfig()).thenReturn("worker_processes auto;\n");
        when(cfgService.buildCaddyfile()).thenReturn("");

        EdgePreviewDto preview = service.preview();

        assertThat(preview.nginx().existsOnDisk()).isFalse();
        assertThat(preview.nginxDiff())
                .contains("missing on disk")
                .contains("+worker_processes auto;");
        assertThat(Files.exists(nginx)).isFalse();
    }

    private static EdgeConfig config(String fallback) {
        return EdgeConfig.builder()
                .name("default")
                .streamPort(443)
                .httpPort(80)
                .httpsPort(8444)
                .fallbackTarget(fallback)
                .workerProcesses("auto")
                .workerConnections(768)
                .streamProxyTimeout("300s")
                .streamConnectTimeout("5s")
                .autoHttps("disable_redirects")
                .build();
    }

    private static EdgeRoute route(String sni, String host, int port, String protocol) {
        return EdgeRoute.builder()
                .sni(sni)
                .targetHost(host)
                .targetPort(port)
                .protocol(protocol)
                .enabled(true)
                .sortOrder(0)
                .build();
    }

    private static EdgeSite site(String domain) {
        return EdgeSite.builder()
                .domain(domain)
                .siteType("static")
                .rootPath("/app/xray/site")
                .upstreamHost("127.0.0.1")
                .upstreamPort(8080)
                .tlsMode("acme")
                .enabled(true)
                .build();
    }

    private static EdgeDnsResolver.Lookup lookup(String host) {
        return switch (host) {
            case "pohr-roclh.xyz", "www.pohr-roclh.xyz", "edge.pohr-roclh.xyz" ->
                    EdgeDnsResolver.Lookup.ok(List.of(SERVER_IP));
            case "htmlacademy.ru" -> EdgeDnsResolver.Lookup.ok(List.of("104.21.32.1"));
            default -> EdgeDnsResolver.Lookup.failed("unknown host");
        };
    }

    private static EdgeSniRowDto row(EdgeMapDto map, String sni) {
        return map.routes().stream()
                .filter(row -> row.sni().equals(sni))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> codes(List<EdgeWarningDto> warnings) {
        return warnings.stream().map(EdgeWarningDto::code).toList();
    }
}
