package org.Roclh.controller;

import org.Roclh.config.SecurityConfig;
import org.Roclh.controller.api.EuNodeApiController;
import org.Roclh.controller.mcp.McpController;
import org.Roclh.service.ClientConfigService;
import org.Roclh.service.ClientScriptService;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.mcp.McpInstructionProvider;
import org.Roclh.service.mcp.McpTokenService;
import org.Roclh.service.mcp.McpToolService;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayInstaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний периметр: что видит анонимный сканер.
 *
 * <p>Правила проверяются через настоящий {@link SecurityConfig} и настоящую
 * цепочку фильтров Spring Security, а не через повторение матчеров в тесте.
 * Ожидания — из «критериев приёмки» задачи про VPN-отпечаток.</p>
 */
@WebMvcTest(controllers = {
        HealthController.class,
        EuNodeApiController.class,
        SubscriptionController.class,
        McpController.class
})
@Import(SecurityConfig.class)
class SecurityPerimeterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EuNodeService nodeService;
    @MockitoBean
    private EuConfigService euConfigService;
    @MockitoBean
    private ClientScriptService clientScriptService;
    @MockitoBean
    private XrayInstaller xrayInstaller;
    @MockitoBean
    private SubscriptionService subscriptionService;
    @MockitoBean
    private XrayConfigService xrayConfigService;
    @MockitoBean
    private ClientConfigService clientConfigService;
    @MockitoBean
    private PublicUrlResolver publicUrlResolver;
    @MockitoBean
    private McpToolService mcpToolService;
    @MockitoBean
    private McpInstructionProvider mcpInstructionProvider;
    @MockitoBean
    private McpTokenService mcpTokenService;

    @Test
    @DisplayName("Анонимный /api/health — 401 без бренда в теле")
    void anonymousHealthIsClosedAndNeutral() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("pohr"))))
                .andExpect(content().string(not(containsString("SNAPSHOT"))));
    }

    @Test
    @DisplayName("Анонимный /api/nodes/agent.sh — 401 и никакой версии Xray")
    void anonymousAgentScriptIsClosed() throws Exception {
        mockMvc.perform(get("/api/nodes/agent.sh"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Pohr-Xray-Version"))
                .andExpect(content().string(not(containsString("pohr-agent"))));
    }

    @Test
    @DisplayName("Анонимный конфиг узла — 401 без заголовка версии Xray")
    void anonymousNodeConfigDoesNotLeakVersion() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(nodeService.findByNodeSecret(id, null)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/nodes/{id}/config", id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Pohr-Xray-Version"));
    }

    @Test
    @DisplayName("Неизвестный /api/nodes/** больше не публичный")
    void unknownNodeEndpointIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/nodes/secret-list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Enroll остаётся доступен анонимно: токен проверяется в теле запроса")
    void registerStaysPublic() throws Exception {
        mockMvc.perform(post("/api/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Анонимный POST /mcp — 401/403, а не HTML-логин")
    void anonymousMcpIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Браузер на /admin/** уводится на логин, панель не отдаётся")
    void adminAreaRequiresLogin() throws Exception {
        mockMvc.perform(get("/admin/edge").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
    }

    @Test
    @DisplayName("/admin/** без Accept тоже уходит на логин, а не отдаёт HTML")
    void adminAreaRedirectsWithoutAccept() throws Exception {
        mockMvc.perform(get("/admin/edge"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
    }

    @Test
    @DisplayName("/sub/** остаётся публичным (подписка по токену)")
    void subscriptionStaysPublic() throws Exception {
        when(subscriptionService.findByToken("token")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/sub/token"))
                .andExpect(status().isNotFound());
    }
}
