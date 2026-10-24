package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.XrayConfig;
import org.Roclh.model.dto.XrayConfigPreview;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayConfigValidator;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * MCP-инструменты для Xray-конфигов.
 *
 * <p>Модель безопасности как в админке: правка сохраняется в БД, но не
 * применяется к работающему процессу. Для применения нужен отдельный
 * {@code xray_config_apply} с {@code confirm=true} — он перезапускает Xray
 * и на несколько секунд рвёт клиентские соединения.</p>
 *
 * <p>Каждое сохранение прогоняется через {@code xray run -test}; конфликт
 * правок ловится по {@code expectedHash}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpXrayConfigTools implements McpToolModule {

    private final XrayConfigService configs;
    private final XrayConfigMaterializer materializer;
    private final XrayConfigValidator validator;
    private final XrayProcessManager xrayProcessManager;
    private final XrayInstaller installer;

    @Override
    public ArrayNode definitions() {
        ArrayNode tools = McpToolSupport.JSON.createArrayNode();
        tools.add(McpToolSupport.tool("xray_configs_list",
                "Список Xray-конфигов: id, имя, описание, активность, hash, размер, публичный Reality-ключ и даты.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_read",
                "Прочитать Xray-конфиг по id/имени или активный. Возвращает сохранённый content, hash, updatedAt, а для активного — ещё материализованный вид с сгенерированными clients/EU/API.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"UUID конфига"},
                  "name":{"type":"string","description":"Имя конфига"},
                  "includeMaterialized":{"type":"boolean","description":"Для активного конфига вернуть материализованный вид. По умолчанию true."}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_validate",
                "Проверить JSON и прогнать xray run -test. Можно передать content либо id/имя конфига. Ничего не сохраняет.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string"},
                  "name":{"type":"string"},
                  "content":{"type":"string","description":"JSON конфига для проверки (приоритетнее id/name)"}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_create",
                "Создать новый Xray-конфиг (неактивный). Конфиг валидируется через xray -test; сгенерированные секции вырезаются.",
                """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Уникальное имя, до 128 символов"},
                  "content":{"type":"string","description":"JSON конфига"},
                  "description":{"type":"string"},
                  "realityPublicKey":{"type":"string","description":"Если не задан — выводится из privateKey"},
                  "force":{"type":"boolean","description":"Сохранить даже при провале xray -test (по умолчанию false)"}
                },"required":["name","content"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_update",
                "Обновить существующий конфиг. Передавай expectedHash из xray_config_read, чтобы не перезаписать чужую правку. Применение к процессу — отдельным xray_config_apply.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string"},
                  "name":{"type":"string"},
                  "content":{"type":"string","description":"Новый полный JSON (сохранённая форма)"},
                  "expectedHash":{"type":"string","description":"sha256 из xray_config_read; при несовпадении правка отклоняется"},
                  "description":{"type":"string"},
                  "realityPublicKey":{"type":"string","description":"Если не задан — пересчитывается из privateKey"},
                  "force":{"type":"boolean","description":"Сохранить даже при провале xray -test (по умолчанию false)"}
                },"required":["content"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_activate",
                "Сделать конфиг активным в БД и материализовать файл. Работающий Xray не перезапускается — для этого нужен xray_config_apply.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string"},
                  "name":{"type":"string"}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_config_apply",
                "Материализовать активный конфиг, проверить его через xray -test и перезапустить Xray. Рвёт клиентские соединения — требует confirm=true.",
                """
                {"type":"object","properties":{
                  "confirm":{"type":"boolean","description":"Обязательно true"}
                },"required":["confirm"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("xray_status",
                "Состояние Xray: запущен ли, pid, установленная версия, активный конфиг и последние строки лога.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && toolName.startsWith("xray_");
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "xray_configs_list" -> McpToolSupport.ok(list());
                case "xray_config_read" -> McpToolSupport.ok(read(args));
                case "xray_config_validate" -> McpToolSupport.ok(validate(args));
                case "xray_config_create" -> McpToolSupport.ok(create(args, actor));
                case "xray_config_update" -> McpToolSupport.ok(update(args, actor));
                case "xray_config_activate" -> McpToolSupport.ok(activate(args, actor));
                case "xray_config_apply" -> McpToolSupport.ok(apply(args, actor));
                case "xray_status" -> McpToolSupport.ok(status());
                default -> McpToolSupport.fail("Неизвестный Xray-инструмент: " + toolName);
            };
        } catch (Exception e) {
            log.warn("MCP xray tool '{}' failed for {}: {}", toolName, actor, e.getMessage(), e);
            return McpToolSupport.fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // Чтение
    // =====================================================================

    private ObjectNode list() {
        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        ArrayNode arr = out.putArray("configs");
        for (XrayConfig cfg : configs.findAll()) {
            arr.add(toSummary(cfg));
        }
        out.put("count", arr.size());
        return out;
    }

    private ObjectNode read(ObjectNode args) throws Exception {
        XrayConfig cfg = resolve(args);
        ObjectNode out = toSummary(cfg);
        out.put("content", cfg.getContent());
        out.put("createdAt", cfg.getCreatedAt() == null ? null : cfg.getCreatedAt().toString());

        boolean includeMaterialized = McpToolSupport.optBool(args, "includeMaterialized", true);
        if (cfg.isActive() && includeMaterialized) {
            try {
                XrayConfigPreview preview = materializer.preview();
                out.put("materialized", preview.materialized());
                ArrayNode lines = out.putArray("generatedLines");
                preview.generatedLines().forEach(lines::add);
            } catch (Exception e) {
                out.put("materializedError", e.getMessage());
            }
        }
        ArrayNode hints = out.putArray("hints");
        hints.add("content — сохранённая форма: clients[], api, stats, routing и EU-outbound генерируются при материализации");
        hints.add("для правки передавай в xray_config_update именно content, а не materialized");
        return out;
    }

    private ObjectNode validate(ObjectNode args) throws Exception {
        String content = McpToolSupport.optText(args, "content");
        String source;
        String toTest;
        boolean materializedView = false;
        if (content != null) {
            source = "content";
            toTest = content;
        } else {
            XrayConfig cfg = resolve(args);
            source = "config:" + cfg.getName();
            if (cfg.isActive()) {
                toTest = materializer.preview().materialized();
                source = source + ":materialized";
                materializedView = true;
            } else {
                toTest = cfg.getContent();
            }
        }

        ArrayNode warnings = McpToolSupport.JSON.createArrayNode();
        JsonNode parsed;
        try {
            parsed = McpToolSupport.JSON.readTree(toTest);
        } catch (Exception e) {
            return validationResult(false, -1, "Некорректный JSON: " + e.getMessage(), source, warnings);
        }
        if (!parsed.path("inbounds").isArray() || parsed.path("inbounds").isEmpty()) {
            warnings.add("В конфиге нет inbounds — Xray запустится, но клиентов обслуживать не будет");
        }
        boolean hasVless = false;
        for (JsonNode inbound : parsed.path("inbounds")) {
            if ("vless".equals(inbound.path("protocol").asText())) {
                hasVless = true;
                if (!materializedView && !inbound.path("settings").path("clients").isEmpty()) {
                    warnings.add("В content есть clients[] — при сохранении они будут вырезаны и заменены подписками");
                }
            }
        }
        if (!hasVless) {
            warnings.add("Не найден vless inbound");
        }
        if (!materializedView && (parsed.has("api") || parsed.has("stats"))) {
            warnings.add("В content есть api/stats — это генерируемые секции, при сохранении они будут удалены");
        }

        XrayConfigValidator.Result result = validator.validate(toTest);
        return validationResult(result.ok(), result.exitCode(), result.output(), source, warnings);
    }

    private ObjectNode validationResult(boolean ok, int exitCode, String output,
                                        String source, ArrayNode warnings) {
        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("ok", ok);
        out.put("source", source);
        out.put("exitCode", exitCode);
        out.put("output", output);
        out.set("warnings", warnings);
        return out;
    }

    // =====================================================================
    // Запись
    // =====================================================================

    private ObjectNode create(ObjectNode args, String actor) throws Exception {
        String name = McpToolSupport.reqText(args, "name");
        String content = McpToolSupport.reqText(args, "content");
        boolean force = McpToolSupport.optBool(args, "force", false);

        String clean = materializer.dematerialize(content);
        requireValid(clean, force);

        XrayConfig created = configs.create(
                name,
                McpToolSupport.optText(args, "description"),
                clean,
                McpToolSupport.optText(args, "realityPublicKey"));

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", "created");
        out.put("changed", true);
        out.setAll(toSummary(created));
        out.put("createdBy", actor);
        out.put("note", "Конфиг создан неактивным. Активация — xray_config_activate, применение — xray_config_apply.");
        return out;
    }

    private ObjectNode update(ObjectNode args, String actor) throws Exception {
        XrayConfig cfg = resolve(args);
        String content = McpToolSupport.reqText(args, "content");
        String expectedHash = McpToolSupport.optText(args, "expectedHash");
        String previousHash = sha256(cfg.getContent());

        if (expectedHash != null && !expectedHash.equalsIgnoreCase(previousHash)) {
            throw new IllegalStateException("Конфиг изменился: expectedHash=" + expectedHash
                    + ", текущий=" + previousHash + ". Перечитай xray_config_read и повтори.");
        }

        String clean = materializer.dematerialize(content);
        boolean force = McpToolSupport.optBool(args, "force", false);
        requireValid(clean, force);

        String previousClean = materializer.dematerialize(cfg.getContent());
        boolean semanticChanged = !jsonEquivalent(previousClean, clean);

        String description = args.has("description")
                ? McpToolSupport.optText(args, "description")
                : cfg.getDescription();
        String publicKey = McpToolSupport.optText(args, "realityPublicKey");
        if (publicKey == null) {
            publicKey = cfg.getRealityPublicKey();
        }

        XrayConfig updated = configs.update(cfg.getId(), cfg.getName(), description, clean, publicKey);
        if (!args.has("realityPublicKey")) {
            updated = configs.refreshRealityPublicKey(updated.getId());
        }

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", "updated");
        out.put("changed", semanticChanged);
        out.put("previousHash", previousHash);
        out.setAll(toSummary(updated));
        out.put("updatedBy", actor);
        if (updated.isActive()) {
            out.put("note", "Конфиг активен, но Xray ещё работает со старым файлом. Применить: xray_config_apply.");
        }
        return out;
    }

    private ObjectNode activate(ObjectNode args, String actor) {
        XrayConfig cfg = resolve(args);
        XrayConfig activated = configs.activate(cfg.getId());
        materializer.materialize();

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", "activated");
        out.put("changed", true);
        out.setAll(toSummary(activated));
        out.put("activatedBy", actor);
        out.put("note", "Файл конфига материализован, но Xray не перезапущен. Применить: xray_config_apply с confirm=true.");
        return out;
    }

    private ObjectNode apply(ObjectNode args, String actor) {
        if (!McpToolSupport.optBool(args, "confirm", false)) {
            throw new IllegalArgumentException("Применение конфига требует confirm=true: Xray будет перезапущен");
        }
        if (!xrayProcessManager.isInstalled()) {
            throw new IllegalStateException("Xray binary не установлен");
        }

        XrayConfig active = configs.getActiveOrThrow();
        String materialized = materializer.preview().materialized();
        XrayConfigValidator.Result validation = validator.validate(materialized);
        if (!validation.ok()) {
            return McpToolSupport.fail("Конфиг не прошёл xray -test, Xray не тронут:\n" + validation.output());
        }

        materializer.materialize();
        boolean restarted;
        try {
            restarted = xrayProcessManager.restart();
        } catch (Exception e) {
            ObjectNode failed = McpToolSupport.JSON.createObjectNode();
            failed.put("applied", false);
            failed.put("error", e.getMessage());
            failed.set("logs", McpToolSupport.JSON.valueToTree(xrayProcessManager.recentLogs(60)));
            return McpToolSupport.fail(McpToolSupport.JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(failed));
        }

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("applied", true);
        out.put("restarted", restarted);
        out.put("running", xrayProcessManager.isRunning());
        out.put("pid", xrayProcessManager.pid());
        out.put("config", active.getName());
        out.put("configHash", sha256(active.getContent()));
        out.put("appliedBy", actor);
        out.set("logs", McpToolSupport.JSON.valueToTree(xrayProcessManager.recentLogs(40)));
        out.put("note", "Клиентские соединения были кратковременно прерваны перезапуском Xray.");
        return out;
    }

    private ObjectNode status() throws Exception {
        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("running", xrayProcessManager.isRunning());
        out.put("pid", xrayProcessManager.pid());
        out.put("installed", xrayProcessManager.isInstalled());
        McpToolSupport.putNullable(out, "version", installer.installedVersion());

        XrayConfig active = configs.getActiveOrThrow();
        ObjectNode cfg = out.putObject("activeConfig");
        cfg.put("id", active.getId().toString());
        cfg.put("name", active.getName());
        cfg.put("hash", sha256(active.getContent()));
        cfg.put("updatedAt", active.getUpdatedAt() == null ? null : active.getUpdatedAt().toString());

        out.set("logs", McpToolSupport.JSON.valueToTree(xrayProcessManager.recentLogs(20)));
        return out;
    }

    // =====================================================================
    // Помощники
    // =====================================================================

    private XrayConfig resolve(ObjectNode args) {
        UUID id = McpToolSupport.optUuid(args, "id");
        if (id != null) {
            return configs.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Конфиг не найден: " + id));
        }
        String name = McpToolSupport.optText(args, "name");
        if (name != null) {
            return configs.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("Конфиг не найден: " + name));
        }
        return configs.getActiveOrThrow();
    }

    private void requireValid(String content, boolean force) {
        XrayConfigValidator.Result result = validator.validate(content);
        if (!result.ok() && !force) {
            throw new IllegalArgumentException("xray -test не прошёл, конфиг не сохранён:\n"
                    + result.output() + "\nПередай force=true, только если понимаешь последствия.");
        }
    }

    private ObjectNode toSummary(XrayConfig cfg) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("id", cfg.getId().toString());
        node.put("name", cfg.getName());
        McpToolSupport.putNullable(node, "description", cfg.getDescription());
        node.put("active", cfg.isActive());
        McpToolSupport.putNullable(node, "realityPublicKey", cfg.getRealityPublicKey());
        node.put("size", cfg.getContent() == null ? 0 : cfg.getContent().length());
        node.put("hash", sha256(cfg.getContent()));
        node.put("createdAt", cfg.getCreatedAt() == null ? null : cfg.getCreatedAt().toString());
        node.put("updatedAt", cfg.getUpdatedAt() == null ? null : cfg.getUpdatedAt().toString());
        return node;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось посчитать hash", e);
        }
    }

    /** Сравнение JSON без учёта форматирования и порядка ключей. */
    private static boolean jsonEquivalent(String left, String right) {
        try {
            return McpToolSupport.JSON.readTree(left).equals(McpToolSupport.JSON.readTree(right));
        } catch (Exception e) {
            return java.util.Objects.equals(left, right);
        }
    }
}
