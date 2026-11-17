package org.Roclh.service.mcp;

import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeProfileService;
import org.Roclh.service.node.EuNodeProfileValidator;
import org.Roclh.service.node.EuNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpEuNodeToolsTest {

    private static final UUID NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EuNodeService nodeService;
    private EuNodeProfileService profileService;
    private EuConfigService euConfigService;
    private McpEuNodeTools tools;
    private EuNode node;

    @BeforeEach
    void setUp() {
        nodeService = mock(EuNodeService.class);
        profileService = mock(EuNodeProfileService.class);
        euConfigService = mock(EuConfigService.class);
        tools = new McpEuNodeTools(nodeService, profileService, euConfigService);

        node = node();
        when(nodeService.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(profileService.effectiveProfile(node)).thenReturn(new EuConfigService.EffectiveProfile(
                "eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox", true, true, true));
        when(profileService.profileHash(node)).thenReturn("profile-hash");
        when(profileService.configHash(node)).thenReturn("config-hash");
        when(profileService.history(NODE_ID)).thenReturn(List.of());
        when(euConfigService.buildEuConfig(node)).thenReturn("{\"inbounds\":[]}");
        when(euConfigService.buildEuOutbound(node)).thenReturn(McpToolSupport.JSON.createObjectNode());
    }

    @Test
    @DisplayName("definitions содержит три инструмента EU-узла")
    void definitionsContainThreeTools() {
        var definitions = tools.definitions();
        assertThat(definitions).hasSize(3);
        assertThat(tools.supports("eu_node_config_update")).isTrue();
        assertThat(tools.supports("eu_node_config_delete")).isFalse();
    }

    @Test
    @DisplayName("eu_node_config_read отдаёт профиль, эффективные значения и hashes")
    void readReturnsProfileAndPreview() throws Exception {
        ObjectNode result = tools.call("eu_node_config_read",
                args("nodeId", NODE_ID.toString()), "tester");

        assertThat(result.get("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.get("profile").get("sni").asText()).isEqualTo("eu.pohr-roclh.xyz");
        assertThat(payload.get("effective").get("fingerprint").asText()).isEqualTo("firefox");
        assertThat(payload.get("effective").get("sniFromNode").asBoolean()).isTrue();
        assertThat(payload.get("preview").get("euConfig").asText()).isEqualTo("{\"inbounds\":[]}");
        assertThat(payload.get("hash").asText()).isEqualTo("profile-hash");
        assertThat(payload.get("configHash").asText()).isEqualTo("config-hash");
    }

    @Test
    @DisplayName("eu_node_config_update отказывает при несовпадении expectedHash")
    void updateRejectsStaleHash() throws Exception {
        ObjectNode args = args("nodeId", NODE_ID.toString());
        args.put("sni", "new.pohr-roclh.xyz");
        args.put("target", "new.pohr-roclh.xyz:443");
        args.put("fingerprint", "firefox");
        args.put("expectedHash", "stale-hash");

        ObjectNode result = tools.call("eu_node_config_update", args, "tester");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText())
                .contains("expectedHash", "profile-hash");
        verify(profileService, org.mockito.Mockito.never()).apply(any(), any(), any());
    }

    @Test
    @DisplayName("eu_node_config_update применяет профиль при верном expectedHash")
    void updateAppliesWithMatchingHash() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                true, List.of(), List.of("TLS-проверка пройдена"), null);
        when(profileService.apply(eq(NODE_ID), any(), eq("tester")))
                .thenReturn(new EuNodeProfileService.ApplyResult(
                        true, validation, node, "new-profile-hash", "new-config-hash", true, true));

        ObjectNode args = args("nodeId", NODE_ID.toString());
        args.put("sni", "eu.pohr-roclh.xyz");
        args.put("target", "eu.pohr-roclh.xyz:443");
        args.put("fingerprint", "firefox");
        args.put("expectedHash", "profile-hash");

        ObjectNode result = tools.call("eu_node_config_update", args, "tester");

        assertThat(result.get("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.get("applied").asBoolean()).isTrue();
        assertThat(payload.get("restarted").asBoolean()).isTrue();
        assertThat(payload.get("hash").asText()).isEqualTo("new-profile-hash");
        assertThat(payload.get("previousHash").asText()).isEqualTo("profile-hash");
    }

    @Test
    @DisplayName("eu_node_config_update возвращает ошибку валидации, не сохраняя профиль")
    void updateReturnsValidationErrors() throws Exception {
        var validation = new EuNodeProfileValidator.ValidationResult(
                false, List.of("target должен быть в формате host:port"), List.of(), null);
        when(profileService.apply(eq(NODE_ID), any(), eq("tester")))
                .thenReturn(new EuNodeProfileService.ApplyResult(
                        false, validation, node, "profile-hash", "config-hash", false, false));

        ObjectNode args = args("nodeId", NODE_ID.toString());
        args.put("sni", "eu.pohr-roclh.xyz");
        args.put("target", "eu.pohr-roclh.xyz");
        args.put("expectedHash", "profile-hash");

        ObjectNode result = tools.call("eu_node_config_update", args, "tester");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText())
                .contains("host:port", "\"applied\" : false");
    }

    @Test
    @DisplayName("eu_node_sync сбрасывает сохранённый hash и возвращает актуальные")
    void syncMarksNode() throws Exception {
        when(profileService.sync(NODE_ID)).thenReturn(
                new EuNodeProfileService.SyncResult(node, "profile-hash", "config-hash"));

        ObjectNode result = tools.call("eu_node_sync",
                args("nodeId", NODE_ID.toString()), "tester");

        assertThat(result.get("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.get("status").asText()).isEqualTo("sync-requested");
        assertThat(payload.get("configHash").asText()).isEqualTo("config-hash");
        assertThat(payload.get("storedConfigHash").isNull()).isTrue();
    }

    @Test
    @DisplayName("неизвестный узел — ошибка")
    void unknownNodeFails() {
        UUID missing = UUID.randomUUID();
        when(nodeService.findById(missing)).thenReturn(Optional.empty());

        ObjectNode result = tools.call("eu_node_config_read",
                args("nodeId", missing.toString()), "tester");

        assertThat(result.get("isError").asBoolean()).isTrue();
    }

    private static ObjectNode args(String key, String value) {
        ObjectNode args = McpToolSupport.JSON.createObjectNode();
        args.put(key, value);
        return args;
    }

    private static JsonNode payload(ObjectNode result) throws Exception {
        return McpToolSupport.JSON.readTree(result.get("content").get(0).get("text").asText());
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
                .build();
    }
}
