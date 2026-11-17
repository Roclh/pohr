package org.Roclh.service.edge;

import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeConfigRepository;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeConfigServiceTest {

    private static final String PANEL_URL = "https://panel.pohr-roclh.xyz";

    private EdgeConfigRepository cfgRepo;
    private EdgeRouteRepository routeRepo;
    private EdgeSiteRepository siteRepo;
    private EdgeConfigService service;

    @BeforeEach
    void setUp() {
        cfgRepo = mock(EdgeConfigRepository.class);
        routeRepo = mock(EdgeRouteRepository.class);
        siteRepo = mock(EdgeSiteRepository.class);
        service = new EdgeConfigService(cfgRepo, routeRepo, siteRepo);
        ReflectionTestUtils.setField(service, "edgeHome", "/app/xray");
        ReflectionTestUtils.setField(service, "panelPort", 8080);
        ReflectionTestUtils.setField(service, "panelAllowlistCsv", "");
        ReflectionTestUtils.setField(service, "panelUrl", PANEL_URL);

        when(cfgRepo.findById("default")).thenReturn(Optional.of(EdgeConfig.builder()
                .name("default")
                .streamPort(443)
                .httpPort(80)
                .httpsPort(8444)
                .fallbackTarget("127.0.0.1:8444")
                .workerProcesses("auto")
                .workerConnections(768)
                .streamProxyTimeout("300s")
                .streamConnectTimeout("5s")
                .autoHttps("disable_redirects")
                .build()));
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of());
    }

    @Test
    @DisplayName("Статический сайт: файлы + узкий проброс клиентских endpoint'ов")
    void staticSiteUsesFileServerAndRootPath() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("www.pohr-roclh.xyz", "static", "/app/xray/site")));

        String caddyfile = service.buildCaddyfile();
        String site = siteBlock(caddyfile, "www.pohr-roclh.xyz");

        assertThat(site)
                .contains("root * /app/xray/site")
                .contains("file_server")
                .contains("encode gzip")
                // подписка, rules.json, скрипты клиента — иначе клиенты не обновятся
                .contains("handle /sub/* {")
                // приглашения живут на хосте панели: сессия должна ставиться там
                .contains("redir /invite/* https://panel.pohr-roclh.xyz{uri} 301")
                .contains("reverse_proxy 127.0.0.1:8080")
                // панельные пути на сайте не существуют
                .contains("@panel_paths path /api/* /admin/* /mcp /mcp/* /ws/*")
                .contains("respond @panel_paths \"Not Found\" 404");
        assertThat(occurrences(site, "reverse_proxy")).isEqualTo(1);
        assertThat(caddyfile)
                .contains("http://www.pohr-roclh.xyz {")
                .contains("redir https://{host}{uri} 301");
    }

    @Test
    void staticSiteWithoutRootPathFallsBackToPlaceholderDir() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("edge.pohr-roclh.xyz", "static", null)));

        assertThat(service.buildCaddyfile()).contains("root * /app/xray/site");
    }

    @Test
    void proxySiteStillUsesReverseProxy() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("pohr-roclh.xyz", "proxy", null)));

        String caddyfile = service.buildCaddyfile();

        assertThat(caddyfile)
                .contains("reverse_proxy 127.0.0.1:8080")
                .contains("http://pohr-roclh.xyz {")
                .doesNotContain("file_server");
    }

    @Test
    @DisplayName("Сайт, проксирующий панель, слушает только loopback и закрыт allowlist'ом")
    void panelSiteBindsLoopbackAndHasAllowlist() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("panel.example.com", "proxy", null)));

        String caddyfile = service.buildCaddyfile();
        String site = siteBlock(caddyfile, "panel.example.com");

        assertThat(site)
                .contains("bind 127.0.0.1")
                .contains("@panel_denied not remote_ip 127.0.0.1 ::1")
                .contains("respond @panel_denied \"Not Found\" 404")
                .contains("reverse_proxy 127.0.0.1:8080");
    }

    @Test
    @DisplayName("Ожидаемый хост панели публикуется без loopback-bind и без allowlist")
    void expectedPanelHostIsPublic() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("panel.pohr-roclh.xyz", "proxy", null)));

        String caddyfile = service.buildCaddyfile();

        assertThat(siteBlock(caddyfile, "panel.pohr-roclh.xyz"))
                .doesNotContain("bind 127.0.0.1")
                .doesNotContain("@panel_denied")
                .contains("reverse_proxy 127.0.0.1:8080");
        // по 80 панель редиректит всё, включая /admin и /api
        assertThat(blockFor(caddyfile, "http://panel.pohr-roclh.xyz {"))
                .contains("redir https://{host}{uri} 301")
                .doesNotContain("@panel_paths");
    }

    @Test
    @DisplayName("Без pohr.panel-url панель нигде не публикуется")
    void withoutPanelUrlNothingIsPublic() {
        ReflectionTestUtils.setField(service, "panelUrl", "");
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("panel.pohr-roclh.xyz", "proxy", null),
                site("pohr-roclh.xyz", "static", "/app/xray/site")));

        String caddyfile = service.buildCaddyfile();

        assertThat(siteBlock(caddyfile, "panel.pohr-roclh.xyz")).contains("bind 127.0.0.1");
        // инвайты некуда редиректить — остаётся прямой проброс в Java
        assertThat(siteBlock(caddyfile, "pohr-roclh.xyz")).contains("handle /invite/* {");
    }

    @Test
    @DisplayName("Allowlist панели расширяется из конфига, loopback остаётся всегда")
    void panelAllowlistIsExtendable() {
        ReflectionTestUtils.setField(service, "panelAllowlistCsv", "10.8.0.0/24, 203.0.113.7");

        assertThat(service.panelAllowlist())
                .isEqualTo("127.0.0.1 ::1 10.8.0.0/24 203.0.113.7");
    }

    @Test
    @DisplayName("Allowlist — второй барьер и для ожидаемого хоста панели")
    void allowlistAppliesToExpectedPanelHostToo() {
        ReflectionTestUtils.setField(service, "panelAllowlistCsv", "203.0.113.7");
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("panel.pohr-roclh.xyz", "proxy", null)));

        String site = siteBlock(service.buildCaddyfile(), "panel.pohr-roclh.xyz");

        assertThat(site)
                .doesNotContain("bind 127.0.0.1")
                .contains("@panel_denied not remote_ip 127.0.0.1 ::1 203.0.113.7")
                .contains("respond @panel_denied \"Not Found\" 404");
    }

    @Test
    @DisplayName("Сайт не на панель не получает loopback-bind и allowlist")
    void otherProxySiteKeepsItsBind() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("api.example.com", "proxy", null, "192.0.2.10", 9000)));

        String caddyfile = service.buildCaddyfile();

        assertThat(caddyfile)
                .contains("reverse_proxy 192.0.2.10:9000")
                .doesNotContain("bind 127.0.0.1")
                .doesNotContain("@panel_denied");
    }

    @Test
    @DisplayName("Статический сайт отдаёт файлы с кэш-заголовками и своей 404")
    void staticSiteServesFilesWithCacheHeadersAnd404() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("pohr-roclh.xyz", "static", "/app/xray/site")));

        String caddyfile = service.buildCaddyfile();

        assertThat(siteBlock(caddyfile, "pohr-roclh.xyz"))
                .contains("root * /app/xray/site")
                .contains("file_server")
                .contains("header /assets/* Cache-Control \"public, max-age=31536000, immutable\"")
                .contains("header *.html Cache-Control \"public, max-age=300\"")
                .contains("X-Content-Type-Options \"nosniff\"")
                .contains("handle_errors")
                .contains("rewrite @not_found /404.html");
    }

    @Test
    @DisplayName("Порт 80 отдаёт только ACME и 301: панельные пути — 404")
    void httpPortServesOnlyAcmeAndRedirect() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("pohr-roclh.xyz", "static", "/app/xray/site"),
                site("www.pohr-roclh.xyz", "static", "/app/xray/site")));

        String caddyfile = service.buildCaddyfile();

        for (String block : httpBlocks(caddyfile)) {
            assertThat(block)
                    .contains("route {")
                    .contains("redir https://{host}{uri} 301")
                    .contains("@panel_paths path /api/* /admin/* /mcp /mcp/*")
                    .contains("respond @panel_paths \"Not Found\" 404")
                    .doesNotContain("reverse_proxy")
                    .doesNotContain("file_server")
                    .doesNotContain("root *");
            // respond должен идти раньше redir: route{} фиксирует порядок,
            // иначе Caddy отсортирует директивы и 404 не сработает.
            assertThat(block.indexOf("respond @panel_paths")).isLessThan(block.indexOf("redir https://"));
        }
        assertThat(httpBlocks(caddyfile)).hasSize(2);
    }

    /** Тела всех {@code http://domain { ... }} блоков (с учётом вложенности). */
    private static List<String> httpBlocks(String caddyfile) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        int depth = 0;
        for (String line : caddyfile.split("\n")) {
            if (current == null) {
                if (!line.startsWith("http://")) {
                    continue;
                }
                current = new StringBuilder();
            }
            current.append(line).append('\n');
            depth += occurrences(line, '{') - occurrences(line, '}');
            if (depth == 0) {
                blocks.add(current.toString());
                current = null;
            }
        }
        return blocks;
    }

    private static int occurrences(String line, char symbol) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == symbol) count++;
        }
        return count;
    }

    /** Тело сайт-блока {@code domain { ... }} вместе с заголовком. */
    private static String siteBlock(String caddyfile, String domain) {
        return blockFor(caddyfile, domain + " {");
    }

    /** Тело блока, первая строка которого равна {@code header}. */
    private static String blockFor(String caddyfile, String header) {
        StringBuilder block = null;
        int depth = 0;
        for (String line : caddyfile.split("\n")) {
            if (block == null) {
                if (!line.equals(header)) {
                    continue;
                }
                block = new StringBuilder();
            }
            block.append(line).append('\n');
            depth += occurrences(line, '{') - occurrences(line, '}');
            if (depth == 0) {
                return block.toString();
            }
        }
        throw new AssertionError("No block '" + header + "' in:\n" + caddyfile);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static EdgeSite site(String domain, String type, String rootPath) {
        return site(domain, type, rootPath, "127.0.0.1", 8080);
    }

    private static EdgeSite site(String domain, String type, String rootPath,
                                 String upstreamHost, int upstreamPort) {
        return EdgeSite.builder()
                .domain(domain)
                .siteType(type)
                .rootPath(rootPath)
                .upstreamHost(upstreamHost)
                .upstreamPort(upstreamPort)
                .bindAddress(null)
                .tlsMode("acme")
                .enabled(true)
                .build();
    }
}
