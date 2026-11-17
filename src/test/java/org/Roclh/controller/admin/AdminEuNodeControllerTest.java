package org.Roclh.controller.admin;

import org.Roclh.model.EuNode;
import org.Roclh.model.EuNodeProfileHistory;
import org.Roclh.model.NodeStatus;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeProfileService;
import org.Roclh.service.node.EuNodeProfileValidator;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class AdminEuNodeControllerTest {

    private static final UUID NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EuNodeService nodeService;
    private EuNodeProfileService profileService;
    private EuConfigService euConfigService;
    private XrayConfigMaterializer materializer;
    private MockMvc mockMvc;
    private EuNode node;

    @BeforeEach
    void setUp() {
        nodeService = mock(EuNodeService.class);
        profileService = mock(EuNodeProfileService.class);
        euConfigService = mock(EuConfigService.class);
        materializer = mock(XrayConfigMaterializer.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminEuNodeController(
                        nodeService, profileService, euConfigService,
                        mock(UserRepository.class), materializer))
                .build();

        node = node();
        when(nodeService.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(profileService.effectiveProfile(node)).thenReturn(new EuConfigService.EffectiveProfile(
                "eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox", true, true, true));
        when(profileService.history(NODE_ID)).thenReturn(List.of(history()));
    }

    @Test
    @DisplayName("GET /admin/nodes/{id} отдаёт профиль, эффективные значения и историю")
    void detailRendersProfileCard() throws Exception {
        mockMvc.perform(get("/admin/nodes/{id}", NODE_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/eu/eu-node-detail"))
                .andExpect(model().attributeExists("node", "rawNode", "profileForm", "effective", "history"))
                .andExpect(model().attribute("effective", new EuConfigService.EffectiveProfile(
                        "eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox", true, true, true)));
    }

    @Test
    @DisplayName("GET preview отдаёт EU-конфиг текстом и hash в заголовке")
    void previewReturnsPlainText() throws Exception {
        when(euConfigService.buildEuConfig(node)).thenReturn("{\"inbounds\":[]}");
        when(euConfigService.computeHash("{\"inbounds\":[]}")).thenReturn("deadbeef");

        mockMvc.perform(get("/admin/nodes/{id}/profile/preview", NODE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("{\"inbounds\":[]}"))
                .andExpect(header().string("X-Content-Sha256", "deadbeef"));
    }

    @Test
    @DisplayName("POST validate проверяет и не сохраняет профиль")
    void validateDoesNotSave() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                true, List.of(), List.of("TLS-проверка пройдена"), null);
        when(profileService.validate(eq(node), any(EuNodeProfileForm.class))).thenReturn(validation);

        mockMvc.perform(post("/admin/nodes/{id}/profile/validate", NODE_ID)
                        .param("sni", "eu.pohr-roclh.xyz")
                        .param("target", "eu.pohr-roclh.xyz:443")
                        .param("fingerprint", "firefox")
                        .principal(() -> "admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/eu/eu-node-detail"))
                .andExpect(model().attribute("validation", validation));

        verify(profileService, never()).apply(any(), any(), any());
    }

    @Test
    @DisplayName("POST profile применяет профиль и редиректит на карточку узла")
    void applyProfileSavesAndRedirects() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                true, List.of(), List.of("TLS-проверка пройдена"), null);
        when(profileService.apply(eq(NODE_ID), any(EuNodeProfileForm.class), eq("admin")))
                .thenReturn(new EuNodeProfileService.ApplyResult(
                        true, validation, node, "hash", "configHash", true, true));

        mockMvc.perform(post("/admin/nodes/{id}/profile", NODE_ID)
                        .param("sni", "eu.pohr-roclh.xyz")
                        .param("target", "eu.pohr-roclh.xyz:443")
                        .param("fingerprint", "firefox")
                        .principal(() -> "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/nodes/" + NODE_ID));

        verify(profileService).apply(eq(NODE_ID), any(EuNodeProfileForm.class), eq("admin"));
    }

    @Test
    @DisplayName("POST profile при ошибке валидации показывает форму, ничего не применяя")
    void applyProfileWithValidationErrorsRendersForm() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                false, List.of("target должен быть в формате host:port"), List.of(), null);
        when(profileService.apply(eq(NODE_ID), any(EuNodeProfileForm.class), eq("admin")))
                .thenReturn(new EuNodeProfileService.ApplyResult(
                        false, validation, node, "hash", "configHash", false, false));

        mockMvc.perform(post("/admin/nodes/{id}/profile", NODE_ID)
                        .param("sni", "eu.pohr-roclh.xyz")
                        .param("target", "eu.pohr-roclh.xyz")
                        .param("fingerprint", "firefox")
                        .principal(() -> "admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/eu/eu-node-detail"))
                .andExpect(model().attribute("validation", validation));
    }

    @Test
    @DisplayName("POST rollback откатывает профиль и редиректит на карточку")
    void rollbackProfileRedirects() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                true, List.of(), List.of("Профиль откатан"), null);
        when(profileService.rollback(eq(NODE_ID), eq("admin")))
                .thenReturn(new EuNodeProfileService.ApplyResult(
                        true, validation, node, "hash", "configHash", true, true));

        mockMvc.perform(post("/admin/nodes/{id}/profile/rollback", NODE_ID)
                        .principal(() -> "admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/nodes/" + NODE_ID));

        verify(profileService).rollback(NODE_ID, "admin");
    }

    @Test
    @DisplayName("GET preview для несуществующего узла — 404")
    void previewMissingNodeReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(nodeService.findById(missing)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/nodes/{id}/profile/preview", missing))
                .andExpect(status().isNotFound());
    }

    private static EuNode node() {
        return EuNode.builder()
                .id(NODE_ID)
                .name("eu-poland-1")
                .host("78.17.145.160")
                .port(8443)
                .realityPublicKey("public-key")
                .realityShortId("aabbccdd")
                .tunnelUuid(UUID.randomUUID())
                .nodeSecret("secret")
                .status(NodeStatus.HEALTHY)
                .sni("eu.pohr-roclh.xyz")
                .target("eu.pohr-roclh.xyz:443")
                .fingerprint("firefox")
                .profileUpdatedAt(Instant.parse("2026-11-14T20:00:00Z"))
                .enrolledAt(Instant.parse("2026-11-01T10:00:00Z"))
                .enrolledBy(UUID.randomUUID())
                .build();
    }

    private static EuNodeProfileHistory history() {
        return EuNodeProfileHistory.builder()
                .id(UUID.randomUUID())
                .nodeId(NODE_ID)
                .sni("old.pohr-roclh.xyz")
                .target("old.pohr-roclh.xyz:443")
                .fingerprint("firefox")
                .createdAt(Instant.parse("2026-11-13T10:00:00Z"))
                .createdBy("admin")
                .build();
    }
}
