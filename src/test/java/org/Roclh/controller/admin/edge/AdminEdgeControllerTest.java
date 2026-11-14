package org.Roclh.controller.admin.edge;

import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeMapDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeSniRowDto;
import org.Roclh.model.dto.edge.EdgeStatusDto;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.EdgeConfigService;
import org.Roclh.service.edge.EdgeService;
import org.Roclh.service.edge.EdgeVisibilityService;
import org.Roclh.service.edge.NginxProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class AdminEdgeControllerTest {

    private EdgeService edgeService;
    private EdgeVisibilityService visibility;
    private NginxProcessManager nginx;
    private CaddyProcessManager caddy;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        edgeService = mock(EdgeService.class);
        visibility = mock(EdgeVisibilityService.class);
        nginx = mock(NginxProcessManager.class);
        caddy = mock(CaddyProcessManager.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminEdgeController(
                        edgeService,
                        mock(EdgeConfigService.class),
                        visibility,
                        nginx,
                        caddy))
                .build();

        when(edgeService.status()).thenReturn(edgeStatus());
        when(nginx.recentLogs(200)).thenReturn(List.of("nginx line"));
        when(caddy.recentLogs(200)).thenReturn(List.of("caddy line"));
        when(visibility.buildMap()).thenReturn(map());
        when(visibility.configFiles()).thenReturn(files("nginx.conf", "stream {}\n"));
    }

    @Test
    @DisplayName("GET /admin/edge отдаёт карту SNI и список конфигов")
    void pageRendersMapAndConfigs() throws Exception {
        mockMvc.perform(get("/admin/edge"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/edge/edge"))
                .andExpect(model().attribute("status", edgeStatus()))
                .andExpect(model().attribute("edgeMap", map()))
                .andExpect(model().attribute("configList", hasSize(2)));
    }

    @Test
    @DisplayName("GET /admin/edge/config/nginx отдаёт text/plain и hash")
    void nginxConfigIsPlainText() throws Exception {
        mockMvc.perform(get("/admin/edge/config/nginx"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("stream {}\n"))
                .andExpect(header().string("X-Content-Sha256",
                        EdgeVisibilityService.sha256("stream {}\n")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")));
    }

    @Test
    @DisplayName("GET /admin/edge/config/caddy?download=1 отдаёт вложение")
    void caddyConfigCanBeDownloaded() throws Exception {
        when(visibility.configFiles()).thenReturn(new EdgeConfigFilesDto(
                file("nginx.conf", "stream {}\n", "stream {}\n"),
                file("Caddyfile", "pohr-roclh.xyz {}\n", "pohr-roclh.xyz {}\n")));

        mockMvc.perform(get("/admin/edge/config/caddy").param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string("pohr-roclh.xyz {}\n"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    @DisplayName("GET /admin/edge/preview показывает diff, ничего не применяя")
    void previewRendersDiff() throws Exception {
        EdgeConfigFileDto nginxFile = file("nginx.conf", "stream {}\n", "stream {}\n");
        EdgeConfigFileDto caddyFile = file("Caddyfile", "old\n", "new\n");
        when(visibility.preview()).thenReturn(new EdgePreviewDto(
                nginxFile, caddyFile, "", "--- Caddyfile (disk)\n+++ Caddyfile (generated)\n+new\n"));

        mockMvc.perform(get("/admin/edge/preview"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/edge/edge-preview"))
                .andExpect(model().attribute("preview", org.hamcrest.Matchers.notNullValue()));
    }

    private static EdgeStatusDto edgeStatus() {
        return new EdgeStatusDto(true, 10L, null, true, 20L, null,
                true, true, 443, 80, 8444, 3, 3);
    }

    private static EdgeMapDto map() {
        EdgeSniRowDto row = new EdgeSniRowDto("htmlacademy.ru", "127.0.0.1:8443",
                EdgeVisibilityService.TYPE_REALITY, true, List.of("45.131.43.6"), List.of());
        EdgeSniRowDto fallback = new EdgeSniRowDto("default", "127.0.0.1:8444",
                EdgeVisibilityService.TYPE_FALLBACK, false, List.of(), List.of());
        return new EdgeMapDto("45.131.43.6", "pohr-roclh.xyz", "pohr-roclh.xyz",
                List.of(row), fallback, List.of());
    }

    private static EdgeConfigFilesDto files(String name, String generated) {
        return new EdgeConfigFilesDto(
                file(name, generated, generated),
                file("Caddyfile", "caddy\n", "caddy\n"));
    }

    private static EdgeConfigFileDto file(String name, String generated, String disk) {
        boolean same = generated.equals(disk);
        return new EdgeConfigFileDto(name, "/app/xray/edge/" + name, true, generated,
                EdgeVisibilityService.sha256(generated), disk,
                EdgeVisibilityService.sha256(disk), same);
    }
}
