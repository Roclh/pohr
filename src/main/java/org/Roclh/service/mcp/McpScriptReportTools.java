package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.User;
import org.Roclh.model.script.Script;
import org.Roclh.model.script.ScriptErrorReport;
import org.Roclh.model.script.ScriptErrorStatus;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.script.ScriptCatalogService;
import org.Roclh.service.script.ScriptErrorReportService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MCP-доступ к «тикетам» клиентских скриптов — отчётам об ошибках,
 * которые видны на /admin/scripts/reports.
 *
 * <p>Агент может читать отчёты, менять их статус и получать сводку.
 * Удаление намеренно не вынесено в MCP: разбор тикета — действие человека
 * в админке, а агент только помогает диагностировать.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpScriptReportTools implements McpToolModule {

    private static final int EXCERPT = 320;

    private final ScriptErrorReportService reports;
    private final ScriptCatalogService catalog;
    private final UserRepository userRepository;

    @Override
    public ArrayNode definitions() {
        ArrayNode tools = McpToolSupport.JSON.createArrayNode();
        tools.add(McpToolSupport.tool("script_reports_list",
                "Список отчётов об ошибках клиентских скриптов (тикетов): статус, скрипт, версия, стадия, клиент, время, короткий фрагмент. Фильтры: status, script, sinceHours.",
                """
                {"type":"object","properties":{
                  "status":{"type":"string","enum":["NEW","IN_PROGRESS","RESOLVED"],"description":"Фильтр по статусу"},
                  "script":{"type":"string","description":"Имя корневого скрипта, например v2rayn-setup.ps1"},
                  "sinceHours":{"type":"integer","description":"Только отчёты за последние N часов (1..8760)"},
                  "limit":{"type":"integer","description":"Сколько вернуть, 1..200. По умолчанию 50"}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("script_reports_read",
                "Прочитать отчёт об ошибке целиком: текст, стадия, клиент, user-agent, статус, кто и когда разобрал.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"UUID отчёта"}
                },"required":["id"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("script_reports_set_status",
                "Сменить статус отчёта (NEW / IN_PROGRESS / RESOLVED). RESOLVED проставляет resolvedAt/resolvedBy. Отчёт не удаляется.",
                """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"UUID отчёта"},
                  "status":{"type":"string","enum":["NEW","IN_PROGRESS","RESOLVED"]}
                },"required":["id","status"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("script_reports_stats",
                "Сводка по отчётам: всего, по статусам, за 24 часа, топ скриптов по числу ошибок.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && toolName.startsWith("script_reports_");
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "script_reports_list" -> McpToolSupport.ok(list(args));
                case "script_reports_read" -> McpToolSupport.ok(read(args));
                case "script_reports_set_status" -> McpToolSupport.ok(setStatus(args, actor));
                case "script_reports_stats" -> McpToolSupport.ok(stats());
                default -> McpToolSupport.fail("Неизвестный инструмент отчётов: " + toolName);
            };
        } catch (Exception e) {
            log.warn("MCP reports tool '{}' failed for {}: {}", toolName, actor, e.getMessage(), e);
            return McpToolSupport.fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ObjectNode list(ObjectNode args) {
        ScriptErrorStatus status = parseStatus(McpToolSupport.optText(args, "status"));
        int limit = McpToolSupport.optInt(args, "limit", 50, 1, 200);
        Integer sinceHours = args.has("sinceHours")
                ? McpToolSupport.optInt(args, "sinceHours", 24, 1, 8760) : null;

        Map<UUID, String> scriptNames = scriptNames();
        Map<UUID, String> usernames = usernames();

        UUID scriptId = null;
        String scriptName = McpToolSupport.optText(args, "script");
        if (scriptName != null) {
            scriptId = catalog.findByName(scriptName).map(Script::getId).orElse(null);
            if (scriptId == null) {
                throw new IllegalArgumentException("Скрипт не найден: " + scriptName);
            }
        }

        Instant since = sinceHours == null ? null : Instant.now().minus(Duration.ofHours(sinceHours));
        List<ScriptErrorReport> source = status == null ? reports.listAll() : reports.listByStatus(status);
        UUID finalScriptId = scriptId;

        ArrayNode arr = McpToolSupport.JSON.createArrayNode();
        int matched = 0;
        for (ScriptErrorReport report : source) {
            if (since != null && report.getReceivedAt().isBefore(since)) continue;
            if (finalScriptId != null && !finalScriptId.equals(report.getRootScriptId())) continue;
            matched++;
            if (arr.size() >= limit) continue;
            arr.add(toListItem(report, scriptNames, usernames));
        }

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("totalMatched", matched);
        out.put("returned", arr.size());
        out.put("newCount", reports.countNew());
        out.put("last24h", reports.countLast24h());
        out.set("reports", arr);
        return out;
    }

    private ObjectNode read(ObjectNode args) {
        UUID id = McpToolSupport.optUuid(args, "id");
        if (id == null) {
            throw new IllegalArgumentException("Параметр 'id' обязателен");
        }
        ScriptErrorReport report = reports.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Отчёт не найден: " + id));

        ObjectNode out = toListItem(report, scriptNames(), usernames());
        out.put("reportText", report.getReportText());
        McpToolSupport.putNullable(out, "userAgent", report.getUserAgent());
        McpToolSupport.putNullable(out, "resolvedAt",
                report.getResolvedAt() == null ? null : report.getResolvedAt().toString());
        McpToolSupport.putNullable(out, "resolvedBy",
                report.getResolvedBy() == null ? null
                        : usernames().getOrDefault(report.getResolvedBy(), report.getResolvedBy().toString()));
        return out;
    }

    private ObjectNode setStatus(ObjectNode args, String actor) {
        UUID id = McpToolSupport.optUuid(args, "id");
        if (id == null) {
            throw new IllegalArgumentException("Параметр 'id' обязателен");
        }
        ScriptErrorStatus status = parseStatus(McpToolSupport.reqText(args, "status"));
        UUID actorId = userRepository.findByUsername(actor).map(User::getId).orElse(null);
        ScriptErrorStatus previous = reports.findById(id).map(ScriptErrorReport::getStatus).orElse(null);
        ScriptErrorReport updated = reports.setStatus(id, status, actorId);

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("status", updated.getStatus().name());
        out.put("changed", previous != status);
        McpToolSupport.putNullable(out, "previousStatus", previous == null ? null : previous.name());
        out.put("id", updated.getId().toString());
        McpToolSupport.putNullable(out, "resolvedAt",
                updated.getResolvedAt() == null ? null : updated.getResolvedAt().toString());
        out.put("updatedBy", actor);
        return out;
    }

    private ObjectNode stats() {
        List<ScriptErrorReport> all = reports.listAll();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        Map<String, Integer> byScript = new HashMap<>();
        int last24h = 0;
        int news = 0, inProgress = 0, resolved = 0;

        Map<UUID, String> scriptNames = scriptNames();
        for (ScriptErrorReport report : all) {
            if (report.getReceivedAt().isAfter(dayAgo)) last24h++;
            ScriptErrorStatus status = report.getStatus() == null
                    ? ScriptErrorStatus.NEW : report.getStatus();
            switch (status) {
                case NEW -> news++;
                case IN_PROGRESS -> inProgress++;
                case RESOLVED -> resolved++;
            }
            String name = report.getRootScriptId() == null
                    ? "unknown"
                    : scriptNames.getOrDefault(report.getRootScriptId(), "deleted");
            byScript.merge(name, 1, Integer::sum);
        }

        ArrayNode top = McpToolSupport.JSON.createArrayNode();
        byScript.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(e -> {
                    ObjectNode node = top.addObject();
                    node.put("script", e.getKey());
                    node.put("count", e.getValue());
                });

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("total", all.size());
        out.put("new", news);
        out.put("inProgress", inProgress);
        out.put("resolved", resolved);
        out.put("last24h", last24h);
        out.set("topScripts", top);
        return out;
    }

    // =====================================================================
    // Помощники
    // =====================================================================

    private ObjectNode toListItem(ScriptErrorReport report,
                                  Map<UUID, String> scriptNames,
                                  Map<UUID, String> usernames) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("id", report.getId().toString());
        node.put("status", report.getStatus() == null ? "NEW" : report.getStatus().name());
        node.put("user", report.getUserId() == null ? null
                : usernames.getOrDefault(report.getUserId(), report.getUserId().toString()));
        McpToolSupport.putNullable(node, "script", report.getRootScriptId() == null ? null
                : scriptNames.getOrDefault(report.getRootScriptId(), "deleted"));
        McpToolSupport.putNullable(node, "scriptVersion", report.getRootScriptVersion());
        McpToolSupport.putNullable(node, "stage", report.getStage());
        node.put("receivedAt", report.getReceivedAt() == null ? null : report.getReceivedAt().toString());
        McpToolSupport.putNullable(node, "clientIp", report.getClientIp());
        node.put("size", report.getReportText() == null ? 0 : report.getReportText().length());
        node.put("excerpt", excerpt(report.getReportText()));
        return node;
    }

    private Map<UUID, String> scriptNames() {
        Map<UUID, String> result = new HashMap<>();
        for (Script script : catalog.listAll()) {
            result.put(script.getId(), script.getName());
        }
        return result;
    }

    private Map<UUID, String> usernames() {
        Map<UUID, String> result = new HashMap<>();
        for (User user : userRepository.findAll()) {
            result.put(user.getId(), user.getUsername());
        }
        return result;
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT ? flat : flat.substring(0, EXCERPT) + "…";
    }

    private static ScriptErrorStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ScriptErrorStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status должен быть NEW, IN_PROGRESS или RESOLVED");
        }
    }
}
