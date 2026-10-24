package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.Script;
import org.Roclh.model.script.ScriptAccessLevel;
import org.Roclh.model.script.ScriptVersion;
import org.Roclh.model.script.ScriptVersionSource;
import org.Roclh.repository.script.ScriptVersionRepository;
import org.Roclh.service.script.ScriptCatalogService;
import org.Roclh.service.script.ScriptVersionService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Реализация MCP-инструментов Pohr.
 *
 * <p>Здесь нет доступа к HTTP: сервис вызывает те же ScriptCatalogService и
 * ScriptVersionService, что и админка. Поэтому версии, авторство, снапшоты и
 * проверки имени работают одинаково для UI и для агента.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVER_NAME = "pohr";
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{[A-Za-z0-9_]+}}");
    private static final Set<String> PLATFORMS = Set.of("any", "windows", "linux", "macos");

    private final ScriptCatalogService catalog;
    private final ScriptVersionService versions;
    private final ScriptVersionRepository versionRepository;
    private final McpInstructionProvider instructions;
    private final List<McpToolModule> modules;

    // =====================================================================
    // Описание инструментов
    // =====================================================================

    private record ToolSpec(String name, String description, String schema) {}

    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("pohr_rules",
                    "Вернуть контракт клиентских скриптов Pohr и полные правила работы агента: скрипты, Xray-конфиги, отчёты об ошибках, диагностика. Вызывай первым в сессии.",
                    """
                    {"type":"object","properties":{},"additionalProperties":false}
                    """),
            new ToolSpec("scripts_list",
                    "Список всех скриптов Pohr: имя, активная версия, hash, автор и время последнего изменения, зависимости, enabled/entrypoint/accessLevel.",
                    """
                    {"type":"object","properties":{},"additionalProperties":false}
                    """),
            new ToolSpec("scripts_read",
                    "Прочитать скрипт: содержимое активной или указанной версии + метаданные (version, hash, changedBy, createdAt). Hash нужен для expectedHash при scripts_update.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла, например v2rayn-setup.ps1"},
                      "version":{"type":"string","description":"Версия вида 1.4. По умолчанию активная."},
                      "versionId":{"type":"string","description":"UUID версии из scripts_history (альтернатива version)."}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_history",
                    "История версий скрипта: versionId, версия, источник, автор, дата, комментарий, доступен ли snapshot для отката.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_create",
                    "Создать новый скрипт: записать файл в volume, зарегистрировать в каталоге и создать версию 1.0. Имя по шаблону {platform}-{purpose}.{ext}.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла: только буквы, цифры, точка, дефис, подчёркивание; расширения bat/cmd/ps1/sh/txt/json"},
                      "content":{"type":"string","description":"Полное содержимое файла"},
                      "displayName":{"type":"string"},
                      "description":{"type":"string"},
                      "platform":{"type":"string","description":"windows | linux | macos | any"},
                      "accessLevel":{"type":"string","enum":["PUBLIC","ADMIN"]},
                      "entrypoint":{"type":"boolean","description":"Показывать в списке установки на /home"},
                      "sortOrder":{"type":"integer"},
                      "enabled":{"type":"boolean"}
                    },"required":["name","content"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_update",
                    "Заменить содержимое скрипта и создать новую минорную версию. Передавай expectedHash из scripts_read, чтобы не перезаписать чужую правку. Старое содержимое автоматически попадает в .history/.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "content":{"type":"string","description":"Новое полное содержимое"},
                      "expectedHash":{"type":"string","description":"Hash активной версии из scripts_read; при несовпадении правка отклоняется"},
                      "note":{"type":"string","description":"Короткий комментарий к версии"}
                    },"required":["name","content"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_update_meta",
                    "Изменить только метаданные каталога, не содержимое: displayName, description, platform, accessLevel, entrypoint, sortOrder, enabled.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "displayName":{"type":"string"},
                      "description":{"type":"string","description":"Пустая строка очищает описание"},
                      "platform":{"type":"string","enum":["any","windows","linux","macos"]},
                      "accessLevel":{"type":"string","enum":["PUBLIC","ADMIN"]},
                      "entrypoint":{"type":"boolean"},
                      "sortOrder":{"type":"integer"},
                      "enabled":{"type":"boolean"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_bump_major",
                    "Поднять мажорную версию скрипта (файл не меняется, минорная сбрасывается в 0).",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "note":{"type":"string","description":"Причина мажорного изменения"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_rollback",
                    "Откатить скрипт на версию из истории. Содержимое берётся из snapshot, создаётся новая минорная версия с source=ROLLBACK.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "version":{"type":"string","description":"Версия вида 1.3"},
                      "versionId":{"type":"string","description":"UUID версии из scripts_history (альтернатива version)"},
                      "note":{"type":"string"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_backup",
                    "Сделать ручной бэкап текущего содержимого скрипта в .history/ и вернуть имя файла.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "note":{"type":"string","description":"Зачем делается бэкап"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_backups",
                    "Список бэкапов скрипта в .history/: имя файла, версия, дата, размер.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_restore_backup",
                    "Восстановить содержимое скрипта из файла бэкапа .history/. Создаёт новую минорную версию.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "file":{"type":"string","description":"Имя файла из scripts_backups (без пути)"},
                      "note":{"type":"string"}
                    },"required":["name","file"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_validate",
                    "Проверить содержимое скрипта по чек-листу scripts.md: каркас, плейсхолдеры, sync/error-report, логирование, line endings. Если content не передан — проверяется активная версия.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла (расширение важно для проверок)"},
                      "content":{"type":"string","description":"Проверяемый текст; если не задан — читается активная версия"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_reseed",
                    "Вернуть встроенную (bundled) версию скрипта из classpath. Создаёт новую минорную версию.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "note":{"type":"string"}
                    },"required":["name"],"additionalProperties":false}
                    """),
            new ToolSpec("scripts_delete",
                    "Удалить скрипт из каталога и volume вместе со снапшотами. Требует confirm=true и явного согласия пользователя.",
                    """
                    {"type":"object","properties":{
                      "name":{"type":"string","description":"Имя файла скрипта"},
                      "confirm":{"type":"boolean","description":"Обязательно true — подтверждение удаления"}
                    },"required":["name","confirm"],"additionalProperties":false}
                    """)
    );

    /** tools/list для MCP. */
    public ArrayNode toolDefinitions() {
        ArrayNode result = JSON.createArrayNode();
        for (ToolSpec spec : TOOLS) {
            ObjectNode tool = result.addObject();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.set("inputSchema", JSON.readTree(spec.schema()));
        }
        for (McpToolModule module : modules) {
            for (JsonNode definition : module.definitions()) {
                result.add(definition);
            }
        }
        return result;
    }

    public String serverName() {
        return SERVER_NAME;
    }

    // =====================================================================
    // tools/call
    // =====================================================================

    /**
     * @param name  raw-имя инструмента (scripts_list, scripts_update, ...)
     * @param args  arguments из tools/call
     * @param actor username владельца MCP-токена
     */
    public ObjectNode call(String name, ObjectNode args, String actor) {
        if (args == null) {
            args = JSON.createObjectNode();
        }
        try {
            for (McpToolModule module : modules) {
                if (module.supports(name)) {
                    return module.call(name, args, actor);
                }
            }
            return switch (name) {
                case "pohr_rules" -> ok(instructions.rulesAndContract());
                case "scripts_list" -> okJson(listScripts());
                case "scripts_read" -> okJson(readScript(args));
                case "scripts_history" -> okJson(history(args));
                case "scripts_create" -> okJson(createScript(args, actor));
                case "scripts_update" -> okJson(updateScript(args, actor));
                case "scripts_update_meta" -> okJson(updateMeta(args, actor));
                case "scripts_bump_major" -> okJson(bumpMajor(args, actor));
                case "scripts_rollback" -> okJson(rollback(args, actor));
                case "scripts_backup" -> okJson(backup(args, actor));
                case "scripts_backups" -> okJson(backups(args));
                case "scripts_restore_backup" -> okJson(restoreBackup(args, actor));
                case "scripts_validate" -> okJson(validate(args));
                case "scripts_reseed" -> okJson(reseed(args, actor));
                case "scripts_delete" -> okJson(deleteScript(args, actor));
                default -> fail("Неизвестный инструмент: " + name);
            };
        } catch (Exception e) {
            log.warn("MCP tool '{}' failed for {}: {}", name, actor, e.getMessage(), e);
            return fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // Чтение
    // =====================================================================

    private ObjectNode listScripts() {
        ObjectNode out = JSON.createObjectNode();
        out.put("server", SERVER_NAME);
        ArrayNode arr = out.putArray("scripts");
        for (Script s : catalog.listAll()) {
            ObjectNode n = arr.addObject();
            n.put("id", s.getId().toString());
            n.put("name", s.getName());
            n.put("displayName", s.getDisplayName());
            n.put("description", s.getDescription());
            n.put("platform", s.getPlatform());
            putText(n, "accessLevel", s.getAccessLevel() == null ? null : s.getAccessLevel().name());
            n.put("entrypoint", s.isEntrypoint());
            n.put("enabled", s.isEnabled());
            n.put("seeded", s.isSeeded());
            n.put("updatedBy", s.getUpdatedBy() == null ? "system" : s.getUpdatedBy());
            putInstant(n, "updatedAt", s.getUpdatedAt());

            ScriptVersion active = versions.getActive(s.getId()).orElse(null);
            if (active == null) {
                n.putNull("version");
                n.putNull("hash");
                n.putNull("lastModifiedBy");
                n.putNull("lastModifiedAt");
            } else {
                n.put("version", active.getVersion());
                n.put("hash", active.getHash());
                n.put("size", active.getSize());
                n.put("lastModifiedBy", actorOf(active));
                putInstant(n, "lastModifiedAt", active.getCreatedAt());
                putText(n, "source", active.getSource() == null ? null : active.getSource().name());
                putText(n, "note", active.getChangeNote());
            }

            n.set("dependencies", stringArray(catalog.getDirectDependencies(s.getName())));
            n.set("reverseDependencies", stringArray(catalog.getReverseDependencies(s.getName())));
        }
        out.put("count", arr.size());
        return out;
    }

    private ObjectNode readScript(ObjectNode args) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        ScriptVersion version = resolveVersion(s, args);
        byte[] bytes = readVersion(s, version);

        ObjectNode out = JSON.createObjectNode();
        out.put("id", s.getId().toString());
        out.put("name", s.getName());
        out.put("version", version.getVersion());
        out.put("versionId", version.getId().toString());
        out.put("hash", version.getHash());
        out.put("size", version.getSize());
        out.put("active", version.isActive());
        out.put("snapshotAvailable", version.isActive() || version.getSnapshotPath() != null);
        putText(out, "source", version.getSource() == null ? null : version.getSource().name());
        out.put("changedBy", actorOf(version));
        putInstant(out, "createdAt", version.getCreatedAt());
        putText(out, "note", version.getChangeNote());
        out.put("content", new String(bytes, StandardCharsets.UTF_8));
        return out;
    }

    private ObjectNode history(ObjectNode args) {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        ObjectNode out = JSON.createObjectNode();
        out.put("id", s.getId().toString());
        out.put("name", s.getName());
        ArrayNode arr = out.putArray("versions");
        for (ScriptVersion v : versions.getHistory(s.getId())) {
            ObjectNode n = arr.addObject();
            n.put("versionId", v.getId().toString());
            n.put("version", v.getVersion());
            putText(n, "source", v.getSource() == null ? null : v.getSource().name());
            n.put("changedBy", actorOf(v));
            putInstant(n, "createdAt", v.getCreatedAt());
            n.put("active", v.isActive());
            n.put("size", v.getSize());
            n.put("hash", v.getHash());
            n.put("snapshotAvailable", v.isActive() || v.getSnapshotPath() != null);
            if (v.getRestoreOf() == null) {
                n.putNull("restoreOf");
            } else {
                n.put("restoreOf", v.getRestoreOf().toString());
            }
            putText(n, "note", v.getChangeNote());
        }
        out.put("count", arr.size());
        putText(out, "currentVersion",
                versions.getActive(s.getId()).map(ScriptVersion::getVersion).orElse(null));
        return out;
    }

    // =====================================================================
    // Запись
    // =====================================================================

    private ObjectNode createScript(ObjectNode args, String actor) throws IOException {
        String name = reqText(args, "name");
        String content = textOrEmpty(args.get("content"));

        Script s = catalog.createScript(
                name,
                content,
                optText(args, "displayName"),
                optText(args, "description"),
                normalizePlatform(optText(args, "platform"), null),
                parseAccessLevel(optText(args, "accessLevel"), ScriptAccessLevel.ADMIN),
                optBool(args, "entrypoint", false),
                validateSortOrder(optInt(args, "sortOrder", 1000)),
                optBool(args, "enabled", true),
                actor,
                ScriptVersionSource.MCP_CREATE);

        ObjectNode out = scriptState("created", s);
        out.put("changed", true);
        return out;
    }

    private ObjectNode updateScript(ObjectNode args, String actor) throws IOException {
        String name = reqText(args, "name");
        if (!args.has("content") || args.get("content").isNull()) {
            throw new IllegalArgumentException("Параметр 'content' обязателен: пустая строка допустима, отсутствие — нет");
        }
        String content = textOrEmpty(args.get("content"));
        String expectedHash = optText(args, "expectedHash");
        String note = optText(args, "note");

        Script s = catalog.getByNameOrThrow(name);
        String before = versions.getActive(s.getId()).map(ScriptVersion::getHash).orElse(null);

        catalog.saveContent(s.getId(), content, actor, note, expectedHash,
                ScriptVersionSource.MCP_EDIT);

        String after = versions.getActive(s.getId()).map(ScriptVersion::getHash).orElse(null);
        ObjectNode out = scriptState("updated", s);
        out.put("changed", !Objects.equals(before, after));
        out.put("previousHash", before);
        out.put("hash", after);
        return out;
    }

    private ObjectNode updateMeta(ObjectNode args, String actor) {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        boolean any = false;

        String displayName = optText(args, "displayName");
        if (displayName != null) any = true;
        else displayName = s.getDisplayName();

        String description = s.getDescription();
        if (args.has("description")) {
            description = textOrNull(args.get("description"));
            any = true;
        }

        String requestedPlatform = optText(args, "platform");
        String platform = requestedPlatform != null
                ? normalizePlatform(requestedPlatform, null)
                : s.getPlatform();
        if (requestedPlatform != null) any = true;

        ScriptAccessLevel accessLevel = parseAccessLevel(optText(args, "accessLevel"), s.getAccessLevel());
        if (optText(args, "accessLevel") != null) any = true;

        boolean entrypoint = optBool(args, "entrypoint", s.isEntrypoint());
        if (args.has("entrypoint")) any = true;

        int sortOrder = validateSortOrder(optInt(args, "sortOrder", s.getSortOrder()));
        if (args.has("sortOrder")) any = true;

        boolean enabled = optBool(args, "enabled", s.isEnabled());
        if (args.has("enabled")) any = true;

        if (!any) {
            throw new IllegalArgumentException("Не передано ни одного поля метаданных");
        }

        catalog.updateMetadata(s.getId(), displayName, description, platform,
                accessLevel, entrypoint, sortOrder, enabled, actor);

        ObjectNode out = scriptState("metadata_updated", catalog.getByIdOrThrow(s.getId()));
        out.put("changed", true);
        return out;
    }

    private ObjectNode bumpMajor(ObjectNode args, String actor) {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        catalog.bumpMajor(s.getId(), actor, optText(args, "note"), ScriptVersionSource.MCP_EDIT);
        ObjectNode out = scriptState("major_bumped", s);
        out.put("changed", true);
        return out;
    }

    private ObjectNode rollback(ObjectNode args, String actor) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        ScriptVersion target = resolveVersion(s, args);
        if (target.isActive()) {
            throw new IllegalArgumentException("Версия " + target.getVersion() + " уже активна");
        }
        if (target.getSnapshotPath() == null) {
            throw new IllegalArgumentException("У версии " + target.getVersion()
                    + " нет snapshot — откат невозможен");
        }
        catalog.rollback(s.getId(), target.getId(), actor, optText(args, "note"));
        ObjectNode out = scriptState("rolled_back", s);
        out.put("rolledBackTo", target.getVersion());
        out.put("changed", true);
        return out;
    }

    private ObjectNode backup(ObjectNode args, String actor) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        ScriptVersion active = versions.getActiveOrThrow(s.getId());
        byte[] content = Files.readAllBytes(catalog.resolvePath(s));
        Path path = versions.createManualBackup(s.getId(), active.getVersion(), content);

        ObjectNode out = JSON.createObjectNode();
        out.put("name", s.getName());
        out.put("version", active.getVersion());
        out.put("fileName", path.getFileName().toString());
        out.put("path", path.toString());
        out.put("size", content.length);
        out.put("createdBy", actor);
        putInstant(out, "createdAt", Instant.now());
        putText(out, "note", optText(args, "note"));
        return out;
    }

    private ObjectNode backups(ObjectNode args) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        ObjectNode out = JSON.createObjectNode();
        out.put("name", s.getName());
        out.put("id", s.getId().toString());
        ArrayNode arr = out.putArray("backups");
        for (ScriptVersionService.BackupInfo b : versions.listBackups(s.getId())) {
            ObjectNode n = arr.addObject();
            n.put("fileName", b.fileName());
            n.put("version", b.version());
            putInstant(n, "createdAt", b.createdAt());
            n.put("size", b.size());
        }
        out.put("count", arr.size());
        return out;
    }

    private ObjectNode restoreBackup(ObjectNode args, String actor) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        String file = reqText(args, "file");
        byte[] bytes = versions.readBackup(s.getId(), file);
        String before = versions.getActive(s.getId()).map(ScriptVersion::getHash).orElse(null);

        catalog.saveContent(s.getId(), new String(bytes, StandardCharsets.UTF_8),
                actor, "restore backup " + file, null, ScriptVersionSource.MCP_EDIT);

        String after = versions.getActive(s.getId()).map(ScriptVersion::getHash).orElse(null);
        ObjectNode out = scriptState("backup_restored", s);
        out.put("restoredFrom", file);
        out.put("changed", !Objects.equals(before, after));
        out.put("hash", after);
        return out;
    }

    private ObjectNode validate(ObjectNode args) throws IOException {
        String name = reqText(args, "name");
        String content = args.has("content") ? textOrEmpty(args.get("content")) : null;
        if (content == null) {
            Script s = catalog.getByNameOrThrow(name);
            content = Files.readString(catalog.resolvePath(s), StandardCharsets.UTF_8);
        }
        return validateContent(name, content);
    }

    private ObjectNode reseed(ObjectNode args, String actor) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        catalog.reseedFromBundled(s.getId(), actor, ScriptVersionSource.MCP_EDIT);
        ObjectNode out = scriptState("reseeded", s);
        out.put("changed", true);
        return out;
    }

    private ObjectNode deleteScript(ObjectNode args, String actor) throws IOException {
        Script s = catalog.getByNameOrThrow(reqText(args, "name"));
        if (!optBool(args, "confirm", false)) {
            throw new IllegalArgumentException("Удаление требует confirm=true");
        }
        catalog.delete(s.getId(), actor);
        ObjectNode out = JSON.createObjectNode();
        out.put("deleted", true);
        out.put("name", s.getName());
        out.put("id", s.getId().toString());
        out.put("deletedBy", actor);
        return out;
    }

    // =====================================================================
    // Валидация по чек-листу scripts.md
    // =====================================================================

    private ObjectNode validateContent(String name, String content) {
        ArrayNode errors = JSON.createArrayNode();
        ArrayNode warnings = JSON.createArrayNode();
        ArrayNode notes = JSON.createArrayNode();

        if (content == null || content.isBlank()) {
            errors.add("Содержимое пустое");
        }
        if (!catalog.isAllowedFileName(name)) {
            errors.add("Недопустимое расширение. Разрешены: bat, cmd, ps1, sh, txt, json");
        }

        String lower = name == null ? "" : name.toLowerCase();
        boolean ps1 = lower.endsWith(".ps1");
        boolean bat = lower.endsWith(".bat") || lower.endsWith(".cmd");
        boolean sh = lower.endsWith(".sh");

        if (content != null) {
            if (content.contains("\r\n") && content.replace("\r\n", "").contains("\n")) {
                warnings.add("Смешанные line endings. Сервер нормализует их сам, но лучше привести к одному стилю");
            }
            if (contains(content, "{{__VERSION__}}")) {
                notes.add("Плейсхолдер {{__VERSION__}} найден");
            } else if (ps1 || bat || sh) {
                warnings.add("Нет {{__VERSION__}} — версия скрипта будет unknown");
            }
            List<String> placeholders = placeholders(content);
            if (!placeholders.isEmpty()) {
                notes.add("Плейсхолдеры: " + String.join(", ", placeholders));
            }
            if (contains(content, "Write-Host")) {
                warnings.add("Найден Write-Host. По контракту логи — только через Write-Pohr*");
            }
            if (contains(content, "http://") || contains(content, "https://")) {
                notes.add("В тексте есть абсолютные URL. Убедитесь, что это не хардкод сервера Pohr");
            }
        }

        if (ps1 && content != null) {
            requireToken(content, "param(", errors, "Нет param() с -RootName/-RootVersion/-Ps1Version");
            requireToken(content, "$ErrorActionPreference", errors, "Нет $ErrorActionPreference = \"Stop\"");
            requireToken(content, "Send-PohrSync", errors, "Нет Send-PohrSync в конце");
            requireToken(content, "Send-PohrErrorReport", warnings, "Нет Send-PohrErrorReport в catch");
            requireToken(content, "{{SCRIPTS_BASE_URL}}", warnings, "Нет загрузки pohr-utils.ps1 через {{SCRIPTS_BASE_URL}}");
        }
        if (bat && content != null) {
            requireToken(content, "powershell", errors, "Нет вызова powershell — .bat должен быть загрузчиком");
            requireToken(content, "-RootName", warnings, "Нет передачи -RootName");
            requireToken(content, "-RootVersion", warnings, "Нет передачи -RootVersion");
            requireToken(content, "-Ps1Version", warnings, "Нет передачи -Ps1Version");
        }
        if (sh && content != null) {
            requireToken(content, "set -euo pipefail", errors, "Нет set -euo pipefail");
            requireToken(content, "trap", warnings, "Нет trap ... ERR для error-report");
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("name", name);
        out.put("ok", errors.isEmpty());
        out.set("errors", errors);
        out.set("warnings", warnings);
        out.set("notes", notes);
        if (content != null) {
            String normalized = catalog.normalizeForName(name, content);
            out.put("normalizedSize", normalized.getBytes(StandardCharsets.UTF_8).length);
            out.put("normalizedHash", ScriptVersionService.sha256Hex(
                    normalized.getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static void requireToken(String content, String token,
                                     ArrayNode problems, String message) {
        if (!content.contains(token)) {
            problems.add(message);
        }
    }

    private static List<String> placeholders(String content) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return new ArrayList<>(found);
    }

    // =====================================================================
    // Общие помощники
    // =====================================================================

    private ObjectNode scriptState(String status, Script s) {
        ObjectNode out = JSON.createObjectNode();
        out.put("status", status);
        out.put("id", s.getId().toString());
        out.put("name", s.getName());
        out.put("displayName", s.getDisplayName());
        out.put("platform", s.getPlatform());
        putText(out, "accessLevel", s.getAccessLevel() == null ? null : s.getAccessLevel().name());
        out.put("entrypoint", s.isEntrypoint());
        out.put("enabled", s.isEnabled());
        out.put("updatedBy", s.getUpdatedBy() == null ? "system" : s.getUpdatedBy());

        ScriptVersion active = versions.getActive(s.getId()).orElse(null);
        if (active == null) {
            out.putNull("version");
            out.putNull("hash");
        } else {
            out.put("version", active.getVersion());
            out.put("versionId", active.getId().toString());
            out.put("hash", active.getHash());
            out.put("changedBy", actorOf(active));
            putInstant(out, "createdAt", active.getCreatedAt());
            putText(out, "note", active.getChangeNote());
        }
        return out;
    }

    private ScriptVersion resolveVersion(Script s, ObjectNode args) {
        String versionId = optText(args, "versionId");
        if (versionId != null) {
            UUID id;
            try {
                id = UUID.fromString(versionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Некорректный versionId: " + versionId);
            }
            ScriptVersion v = versionRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Версия не найдена: " + versionId));
            if (!v.getScriptId().equals(s.getId())) {
                throw new IllegalArgumentException("Версия " + versionId + " принадлежит другому скрипту");
            }
            return v;
        }

        String version = optText(args, "version");
        if (version != null) {
            return versions.getHistory(s.getId()).stream()
                    .filter(v -> v.getVersion().equals(version))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Версия " + version + " не найдена у скрипта " + s.getName()));
        }

        return versions.getActiveOrThrow(s.getId());
    }

    private byte[] readVersion(Script s, ScriptVersion version) throws IOException {
        if (version.isActive()) {
            Path path = catalog.resolvePath(s);
            if (!Files.exists(path)) {
                throw new IOException("Файл скрипта отсутствует в volume: " + path);
            }
            return Files.readAllBytes(path);
        }
        if (version.getSnapshotPath() == null) {
            throw new IOException("У версии " + version.getVersion()
                    + " нет snapshot (история почищена ротацией)");
        }
        return versions.readSnapshot(version.getSnapshotPath());
    }

    private static String actorOf(ScriptVersion v) {
        return v.getChangedBy() == null || v.getChangedBy().isBlank()
                ? "system"
                : v.getChangedBy();
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static ArrayNode stringArray(Collection<String> values) {
        ArrayNode arr = JSON.createArrayNode();
        if (values != null) {
            for (String value : values) {
                arr.add(value);
            }
        }
        return arr;
    }

    private ObjectNode okJson(JsonNode payload) {
        return ok(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private ObjectNode ok(String text) {
        return toolResult(text, false);
    }

    private ObjectNode fail(String text) {
        return toolResult(text, true);
    }

    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode content = out.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text == null ? "" : text);
        out.put("isError", isError);
        return out;
    }

    private static String reqText(ObjectNode args, String key) {
        String value = optText(args, key);
        if (value == null) {
            throw new IllegalArgumentException("Параметр '" + key + "' обязателен");
        }
        return value;
    }

    private static String optText(ObjectNode args, String key) {
        if (args == null || !args.has(key)) return null;
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String value = node.asText();
        return value == null ? "" : value;
    }

    private static boolean optBool(ObjectNode args, String key, boolean defaultValue) {
        if (args == null || !args.has(key)) return defaultValue;
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return defaultValue;
        if (node.isBoolean()) return node.asBoolean();
        String value = node.asText();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static int optInt(ObjectNode args, String key, int defaultValue) {
        if (args == null || !args.has(key)) return defaultValue;
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return defaultValue;
        if (node.isNumber()) return node.asInt();
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Параметр '" + key + "' должен быть числом");
        }
    }

    private static String normalizePlatform(String value, String defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("platform должен быть any, windows, linux или macos");
        }
        return normalized;
    }

    private static int validateSortOrder(int value) {
        if (value < 0 || value > 9999) {
            throw new IllegalArgumentException("sortOrder должен быть в диапазоне 0..9999");
        }
        return value;
    }

    private static ScriptAccessLevel parseAccessLevel(String value, ScriptAccessLevel defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return ScriptAccessLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("accessLevel должен быть PUBLIC или ADMIN");
        }
    }

    private static boolean contains(String content, String token) {
        return content != null && content.contains(token);
    }
}
