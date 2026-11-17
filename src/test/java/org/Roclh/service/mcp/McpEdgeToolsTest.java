package org.Roclh.service.mcp;

import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeMapDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeSniRowDto;
import org.Roclh.model.dto.edge.EdgeStatusDto;
import org.Roclh.model.dto.edge.EdgeWarningDto;
import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeRoute;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.EdgeConfigService;
import org.Roclh.service.edge.EdgeService;
import org.Roclh.service.edge.EdgeVisibilityService;
import org.Roclh.service.edge.NginxProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpEdgeToolsTest {

    private EdgeConfigService cfgService;
    private EdgeService edgeService;
    private EdgeVisibilityService visibility;
    private NginxProcessManager nginx;
    private CaddyProcessManager caddy;
    private EdgeRouteRepository routeRepo;
    private EdgeSiteRepository siteRepo;
    private McpEdgeTools tools;

    @BeforeEach
    void setUp() {
        cfgService = mock(EdgeConfigService.class);
        edgeService = mock(EdgeService.class);
        visibility = mock(EdgeVisibilityService.class);
        nginx = mock(NginxProcessManager.class);
        caddy = mock(CaddyProcessManager.class);
        routeRepo = mock(EdgeRouteRepository.class);
        siteRepo = mock(EdgeSiteRepository.class);
        tools = new McpEdgeTools(cfgService, edgeService, visibility, nginx, caddy, routeRepo, siteRepo);

        when(cfgService.getConfig()).thenReturn(config());
        when(edgeService.status()).thenReturn(new EdgeStatusDto(
                true, 11L, null, true, 22L, null,
                true, true, 443, 80, 8444, 2, 2));
        when(nginx.isRunning()).thenReturn(true);
        when(nginx.pid()).thenReturn(11L);
        when(nginx.isInstalled()).thenReturn(true);
        when(caddy.isRunning()).thenReturn(true);
        when(caddy.pid()).thenReturn(22L);
        when(caddy.isInstalled()).thenReturn(true);
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of(route()));
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(site()));

        EdgeConfigFilesDto files = files();
        when(visibility.configFiles()).thenReturn(files);
        when(visibility.buildMap()).thenReturn(map());
        when(visibility.panelUrl()).thenReturn("https://panel.pohr-roclh.xyz");
        when(visibility.panelHost()).thenReturn("panel.pohr-roclh.xyz");
        when(routeRepo.findAllByOrderBySortOrderAscSniAsc()).thenReturn(List.of(route()));
        when(siteRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(site()));
        when(visibility.routeType(org.mockito.ArgumentMatchers.any(EdgeRoute.class)))
                .thenReturn(EdgeVisibilityService.TYPE_REALITY);
    }

    @Test
    @DisplayName("definitions содержит три read-only edge-инструмента")
    void definitionsContainThreeTools() {
        var definitions = tools.definitions();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            names.add(definitions.get(i).get("name").asText());
        }

        assertThat(names).containsExactly("edge_status", "edge_config_read", "edge_preview");
        assertThat(tools.supports("edge_status")).isTrue();
        assertThat(tools.supports("edge_apply")).isFalse();
    }

    @Test
    @DisplayName("edge_status отдаёт порты, процессы, счётчики и hash конфигов")
    void edgeStatusReturnsSummary() throws Exception {
        ObjectNode result = tools.call("edge_status", McpToolSupport.JSON.createObjectNode(), "tester");
        assertThat(result.get("isError").asBoolean()).isFalse();

        JsonNode payload = McpToolSupport.JSON.readTree(result.get("content").get(0).get("text").asText());
        assertThat(payload.get("ports").get("stream").asInt()).isEqualTo(443);
        assertThat(payload.get("ports").get("http").asInt()).isEqualTo(80);
        assertThat(payload.get("ports").get("https").asInt()).isEqualTo(8444);
        assertThat(payload.get("fallback").asText()).isEqualTo("127.0.0.1:8444");
        assertThat(payload.get("processes").get("nginx").get("running").asBoolean()).isTrue();
        assertThat(payload.get("processes").get("caddy").get("pid").asLong()).isEqualTo(22L);
        assertThat(payload.get("counts").get("routes").asInt()).isEqualTo(2);
        assertThat(payload.get("counts").get("sites").asInt()).isEqualTo(2);
        assertThat(payload.get("configs").get("nginx").get("generatedSha256").asText())
                .isEqualTo(EdgeVisibilityService.sha256("stream {}\n"));
        assertThat(payload.get("configs").get("same").asBoolean()).isTrue();
        assertThat(payload.get("warnings")).hasSize(2);
        assertThat(payload.get("warnings").get(0).get("code").asText())
                .isEqualTo(EdgeVisibilityService.WARN_SITE_PROXIES_PANEL);
        assertThat(payload.get("warnings").get(1).get("code").asText())
                .isEqualTo(EdgeVisibilityService.WARN_FALLBACK_PANEL);
        assertThat(payload.get("panel").get("url").asText())
                .isEqualTo("https://panel.pohr-roclh.xyz");
        assertThat(payload.get("panel").get("host").asText()).isEqualTo("panel.pohr-roclh.xyz");
        assertThat(payload.get("panel").get("published").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("edge_config_read отдаёт edge_config, routes, sites и тексты конфигов")
    void edgeConfigReadReturnsEverything() throws Exception {
        ObjectNode result = tools.call("edge_config_read", McpToolSupport.JSON.createObjectNode(), "tester");
        assertThat(result.get("isError").asBoolean()).isFalse();

        JsonNode payload = McpToolSupport.JSON.readTree(result.get("content").get(0).get("text").asText());
        assertThat(payload.get("edgeConfig").get("streamPort").asInt()).isEqualTo(443);
        assertThat(payload.get("edgeConfig").get("fallbackTarget").asText()).isEqualTo("127.0.0.1:8444");
        assertThat(payload.get("routes")).hasSize(1);
        assertThat(payload.get("routes").get(0).get("sni").asText()).isEqualTo("htmlacademy.ru");
        assertThat(payload.get("routes").get(0).get("type").asText()).isEqualTo("reality");
        assertThat(payload.get("sites")).hasSize(1);
        assertThat(payload.get("sites").get(0).get("domain").asText()).isEqualTo("pohr-roclh.xyz");
        assertThat(payload.get("configs").get("nginx").get("text").asText()).isEqualTo("stream {}\n");
        assertThat(payload.get("configs").get("caddy").get("text").asText()).isEqualTo("site {}\n");
        assertThat(payload.get("configs").get("nginx").get("generatedSha256").asText())
                .hasSize(64);
    }

    @Test
    @DisplayName("edge_preview при отсутствии изменений возвращает same")
    void edgePreviewReturnsSame() throws Exception {
        EdgeConfigFilesDto files = files();
        when(visibility.preview()).thenReturn(new EdgePreviewDto(
                files.nginx(), files.caddy(), "", ""));

        ObjectNode result = tools.call("edge_preview", McpToolSupport.JSON.createObjectNode(), "tester");
        JsonNode payload = McpToolSupport.JSON.readTree(result.get("content").get(0).get("text").asText());

        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(payload.get("same").asBoolean()).isTrue();
        assertThat(payload.get("status").asText()).isEqualTo("same");
        assertThat(payload.get("nginx").get("diff").asText()).isEmpty();
    }

    @Test
    @DisplayName("edge_preview при изменениях возвращает unified diff")
    void edgePreviewReturnsDiff() throws Exception {
        EdgeConfigFileDto changed = new EdgeConfigFileDto("nginx.conf",
                "/app/xray/edge/nginx.conf", true, "stream {\n  new;\n}\n",
                EdgeVisibilityService.sha256("stream {\n  new;\n}\n"), "stream {}\n",
                EdgeVisibilityService.sha256("stream {}\n"), false);
        EdgeConfigFileDto same = files().caddy();
        when(visibility.preview()).thenReturn(new EdgePreviewDto(
                changed, same, "--- nginx.conf (disk)\n+++ nginx.conf (generated)\n+  new;\n", ""));

        ObjectNode result = tools.call("edge_preview", McpToolSupport.JSON.createObjectNode(), "tester");
        JsonNode payload = McpToolSupport.JSON.readTree(result.get("content").get(0).get("text").asText());

        assertThat(payload.get("same").asBoolean()).isFalse();
        assertThat(payload.get("status").asText()).isEqualTo("diff");
        assertThat(payload.get("nginx").get("diff").asText()).contains("+  new;");
    }

    @Test
    @DisplayName("неизвестный инструмент возвращает isError")
    void unknownToolFails() {
        ObjectNode result = tools.call("edge_apply", McpToolSupport.JSON.createObjectNode(), "tester");
        assertThat(result.get("isError").asBoolean()).isTrue();
    }

    private static EdgeMapDto map() {
        EdgeSniRowDto row = new EdgeSniRowDto("htmlacademy.ru", "127.0.0.1:8443",
                EdgeVisibilityService.TYPE_REALITY, true, List.of("45.131.43.6"),
                List.of(new EdgeWarningDto(EdgeVisibilityService.WARN_SITE_PROXIES_PANEL,
                        "WARN", "htmlacademy.ru", "site proxies the panel port")));
        EdgeSniRowDto fallback = new EdgeSniRowDto("default", "127.0.0.1:8444",
                EdgeVisibilityService.TYPE_FALLBACK, false, List.of(),
                List.of(new EdgeWarningDto(EdgeVisibilityService.WARN_FALLBACK_PANEL,
                        "WARN", "127.0.0.1:8444", "fallback points to the panel port")));
        return new EdgeMapDto("45.131.43.6", "pohr-roclh.xyz", "pohr-roclh.xyz",
                List.of(row), fallback, List.of());
    }

    private static EdgeConfig config() {
        return EdgeConfig.builder()
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
                .build();
    }

    private static EdgeRoute route() {
        return EdgeRoute.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .sni("htmlacademy.ru")
                .targetHost("127.0.0.1")
                .targetPort(8443)
                .protocol("tcp")
                .enabled(true)
                .sortOrder(0)
                .build();
    }

    private static EdgeSite site() {
        return EdgeSite.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .domain("pohr-roclh.xyz")
                .siteType("proxy")
                .upstreamHost("127.0.0.1")
                .upstreamPort(8080)
                .tlsMode("acme")
                .enabled(true)
                .build();
    }

    private static EdgeConfigFilesDto files() {
        return new EdgeConfigFilesDto(
                file("nginx.conf", "stream {}\n"),
                file("Caddyfile", "site {}\n"));
    }

    private static EdgeConfigFileDto file(String name, String text) {
        return new EdgeConfigFileDto(name, "/app/xray/edge/" + name, true, text,
                EdgeVisibilityService.sha256(text), text, EdgeVisibilityService.sha256(text), true);
    }
}
