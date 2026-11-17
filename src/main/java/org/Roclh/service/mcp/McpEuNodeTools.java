package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.EuNodeProfileHistory;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeProfileService;
import org.Roclh.service.node.EuNodeProfileValidator;
import org.Roclh.service.node.EuNodeService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * MCP-инструменты профиля REALITY EU-узла (Т03).
 *
 * <p>{@code eu_node_config_update} — осознанное применение: он валидирует
 * пару sni/target, сохраняет профиль, материализует RU-конфиг и перезапускает
 * Xray (если тот запущен), поэтому клиентские сессии кратковременно рвутся.
 * Конфликт параллельных правок ловится по {@code expectedHash}, который
 * возвращает {@code eu_node_config_read}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpEuNodeTools implements McpToolModule {

    private static final List<String> TOOL_NAMES = List.of(
            "eu_node_config_read", "eu_node_config_update", "eu_node_sync");

    private final EuNodeService nodeService;
    private final EuNodeProfileService profileService;
    private final EuConfigService euConfigService;

    @Override
    public ArrayNode definitions() {
        ArrayNode tools = McpToolSupport.JSON.createArrayNode();
        tools.add(McpToolSupport.tool("eu_node_config_read",
                "Прочитать профиль REALITY EU-узла: сохранённые sni/target/fingerprint, эффективные значения с учётом глобального фолбэка, preview EU-конфига, hash профиля (для expectedHash) и hash конфига. Только чтение.",
                """
                {"type":"object","properties":{
                  "nodeId":{"type":"string","description":"UUID узла"}
                },"required":["nodeId"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("eu_node_config_update",
                "Проверить и применить профиль REALITY EU-узла: сохранить sni/target/fingerprint, материализовать RU-конфиг и перезапустить Xray (рвёт клиентские сессии на несколько секунд). Передавай expectedHash из eu_node_config_read, чтобы не перезаписать чужую правку.",
                """
                {"type":"object","properties":{
                  "nodeId":{"type":"string","description":"UUID узла"},
                  "sni":{"type":"string","description":"Домен SNI; пустая строка — наследовать глобальный"},
                  "target":{"type":"string","description":"host:port сайта-прикрытия; пустая строка — наследовать глобальный"},
                  "fingerprint":{"type":"string","description":"firefox | edge | chrome | safari; пустая строка — наследовать (firefox)"},
                  "expectedHash":{"type":"string","description":"hash из eu_node_config_read; при несовпадении правка отклоняется"}
                },"required":["nodeId"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("eu_node_sync",
                "Пометить EU-узел к синхронизации: сбросить сохранённый hash конфига и вернуть текущие hash профиля и конфига. Xray не перезапускается, EU подхватит конфиг в течение 5 минут.",
                """
                {"type":"object","properties":{
                  "nodeId":{"type":"string","description":"UUID узла"}
                },"required":["nodeId"],"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && TOOL_NAMES.contains(toolName);
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "eu_node_config_read" -> McpToolSupport.ok(read(args));
                case "eu_node_config_update" -> update(args, actor);
                case "eu_node_sync" -> McpToolSupport.ok(sync(args));
                default -> McpToolSupport.fail("Неизвестный инструмент EU-узла: " + toolName);
            };
        } catch (Exception e) {
            log.warn("MCP eu node tool '{}' failed for {}: {}", toolName, actor, e.getMessage(), e);
            return McpToolSupport.fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // eu_node_config_read
    // =====================================================================

    private ObjectNode read(ObjectNode args) {
        EuNode node = resolve(args);
        EuConfigService.EffectiveProfile effective = profileService.effectiveProfile(node);

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        ObjectNode nodeNode = out.putObject("node");
        nodeNode.put("id", node.getId().toString());
        nodeNode.put("name", node.getName());
        nodeNode.put("host", node.getHost());
        nodeNode.put("port", node.getPort());
        nodeNode.put("status", node.getStatus() == null ? null : node.getStatus().name());
        McpToolSupport.putNullable(nodeNode, "configHash", node.getConfigHash());
        McpToolSupport.putNullable(nodeNode, "profileUpdatedAt",
                node.getProfileUpdatedAt() == null ? null : node.getProfileUpdatedAt().toString());

        ObjectNode profile = out.putObject("profile");
        McpToolSupport.putNullable(profile, "sni", node.getSni());
        McpToolSupport.putNullable(profile, "target", node.getTarget());
        McpToolSupport.putNullable(profile, "fingerprint", node.getFingerprint());

        ObjectNode effectiveNode = out.putObject("effective");
        effectiveNode.put("sni", effective.sni());
        effectiveNode.put("target", effective.target());
        effectiveNode.put("fingerprint", effective.fingerprint());
        effectiveNode.put("sniFromNode", effective.sniFromNode());
        effectiveNode.put("targetFromNode", effective.targetFromNode());
        effectiveNode.put("fingerprintFromNode", effective.fingerprintFromNode());

        ObjectNode preview = out.putObject("preview");
        String euConfig = euConfigService.buildEuConfig(node);
        preview.put("euConfig", euConfig);
        preview.set("ruOutbound", McpToolSupport.JSON.valueToTree(euConfigService.buildEuOutbound(node)));

        out.put("hash", profileService.profileHash(node));
        out.put("configHash", profileService.configHash(node));
        out.set("history", historyNode(node.getId()));

        out.put("note", "hash — это expectedHash для eu_node_config_update; "
                + "configHash — ровно тот, что увидит EU-агент при опросе конфига.");
        return out;
    }

    private ArrayNode historyNode(UUID nodeId) {
        ArrayNode history = McpToolSupport.JSON.createArrayNode();
        for (EuNodeProfileHistory entry : profileService.history(nodeId)) {
            ObjectNode item = history.addObject();
            item.put("id", entry.getId() == null ? null : entry.getId().toString());
            McpToolSupport.putNullable(item, "sni", entry.getSni());
            McpToolSupport.putNullable(item, "target", entry.getTarget());
            McpToolSupport.putNullable(item, "fingerprint", entry.getFingerprint());
            McpToolSupport.putNullable(item, "createdAt",
                    entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString());
            McpToolSupport.putNullable(item, "createdBy", entry.getCreatedBy());
        }
        return history;
    }

    // =====================================================================
    // eu_node_config_update
    // =====================================================================

    private ObjectNode update(ObjectNode args, String actor) {
        EuNode node = resolve(args);
        String previousHash = profileService.profileHash(node);
        String expectedHash = McpToolSupport.optText(args, "expectedHash");
        if (expectedHash != null && !expectedHash.equalsIgnoreCase(previousHash)) {
            throw new IllegalStateException("Профиль узла изменился: expectedHash=" + expectedHash
                    + ", текущий=" + previousHash
                    + ". Перечитай eu_node_config_read и повтори.");
        }

        EuNodeProfileForm form = new EuNodeProfileForm(
                McpToolSupport.optText(args, "sni"),
                McpToolSupport.optText(args, "target"),
                McpToolSupport.optText(args, "fingerprint"));

        EuNodeProfileService.ApplyResult result = profileService.apply(node.getId(), form, actor);
        if (!result.applied()) {
            ObjectNode failed = McpToolSupport.JSON.createObjectNode();
            failed.put("status", "rejected");
            failed.put("applied", false);
            failed.set("validation", validationNode(result.validation()));
            return McpToolSupport.fail(McpToolSupport.JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(failed));
        }

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", "applied");
        out.put("applied", true);
        out.put("changed", result.changed());
        out.put("restarted", result.restarted());
        out.put("previousHash", previousHash);
        out.put("hash", result.profileHash());
        out.put("configHash", result.configHash());
        out.set("validation", validationNode(result.validation()));
        out.put("updatedBy", actor);
        out.put("note", result.restarted()
                ? "Профиль применён; Xray перезапущен, клиентские соединения были кратковременно прерваны. EU-агент подхватит новый конфиг в течение 5 минут."
                : "Профиль применён, RU-конфиг материализован. Xray не был запущен — новый конфиг вступит в силу при следующем старте; EU-агент подхватит свой конфиг в течение 5 минут.");
        return McpToolSupport.ok(out);
    }

    private ObjectNode validationNode(EuNodeProfileValidator.ValidationResult validation) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("ok", validation.ok());
        node.set("errors", McpToolSupport.JSON.valueToTree(validation.errors()));
        node.set("warnings", McpToolSupport.JSON.valueToTree(validation.warnings()));
        return node;
    }

    // =====================================================================
    // eu_node_sync
    // =====================================================================

    private ObjectNode sync(ObjectNode args) {
        EuNode node = resolve(args);
        EuNodeProfileService.SyncResult result = profileService.sync(node.getId());

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", "sync-requested");
        out.put("nodeId", result.node().getId().toString());
        out.put("name", result.node().getName());
        out.put("hash", result.profileHash());
        out.put("configHash", result.configHash());
        out.putNull("storedConfigHash");
        out.put("note", "Сохранённый hash конфига сброшен, RU-конфиг материализован. "
                + "EU-агент опрашивает /api/nodes/{id}/config каждые 5 минут и сам применит новый конфиг.");
        return out;
    }

    // =====================================================================
    // Помощники
    // =====================================================================

    private EuNode resolve(ObjectNode args) {
        UUID id = McpToolSupport.optUuid(args, "nodeId");
        if (id == null) {
            throw new IllegalArgumentException("Параметр 'nodeId' обязателен (UUID узла)");
        }
        return nodeService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Узел не найден: " + id));
    }
}
