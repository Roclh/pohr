package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.*;
import org.Roclh.repository.script.ScriptRepository;
import org.Roclh.service.ClientScriptService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Реестр скриптов: сканирование volume, регистрация, версии, зависимости.
 *
 * <p>Зависимости не хранятся в БД — считаются на лету из содержимого скриптов
 * и кэшируются в памяти. Инвалидация — при любом изменении содержимого.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptCatalogService {

    /** Расширения, которые регистрируем в каталоге. */
    private static final Set<String> ALLOWED_EXT =
            Set.of("bat", "cmd", "ps1", "sh", "txt", "json");
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private final ScriptRepository scriptRepo;
    private final ScriptVersionService versionService;
    private final ClientScriptService fileService;

    @Value("${pohr.scripts.home}")
    private String scriptsHome;

    // =====================================================================
    // Кэш графа зависимостей
    // =====================================================================

    private final Map<String, Set<String>> dependencyCache = new ConcurrentHashMap<>();
    private final AtomicBoolean dependencyCacheValid = new AtomicBoolean(false);

    private void invalidateDependencyCache() {
        dependencyCache.clear();
        dependencyCacheValid.set(false);
    }

    // =====================================================================
    // Сканирование при старте
    // =====================================================================

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void scanAndRegister() {
        Path home = Path.of(scriptsHome);
        if (!Files.exists(home)) {
            log.info("Scripts home {} does not exist, skipping scan", home);
            return;
        }

        Set<String> seen = new HashSet<>();
        try (var stream = Files.list(home)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> isAllowedExtension(p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        seen.add(name);
                        try {
                            registerOrUpdate(p, name);
                        } catch (Exception e) {
                            log.error("Failed to register script {}: {}", name, e.getMessage(), e);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to scan scripts home {}: {}", home, e.getMessage());
        }

        // Помечаем отсутствующие как disabled
        for (Script s : scriptRepo.findAll()) {
            if (!seen.contains(s.getName()) && s.isEnabled()) {
                s.setEnabled(false);
                scriptRepo.save(s);
                log.warn("Script '{}' missing on disk, marked as disabled", s.getName());
            }
        }

        invalidateDependencyCache();
        log.info("Script scan complete: {} files in volume, {} registered",
                seen.size(), scriptRepo.count());
    }

    private void registerOrUpdate(Path file, String name) throws IOException {
        byte[] content = Files.readAllBytes(file);
        String hash = ScriptVersionService.sha256Hex(content);
        long size = content.length;

        Optional<Script> existing = scriptRepo.findByName(name);
        if (existing.isEmpty()) {
            Script script = scriptRepo.save(Script.builder()
                    .name(name)
                    .displayName(name)
                    .platform(guessPlatform(name))
                    .accessLevel(ScriptAccessLevel.ADMIN)
                    .entrypoint(false)
                    .sortOrder(1000)
                    .enabled(true)
                    .seeded(fileService.isBundled(name))
                    .build());
            versionService.registerInitial(script.getId(), hash, size, ScriptVersionSource.SEED,
                    "system", "registered from volume");
            log.info("Registered new script '{}' v1.0 (seeded={})", name, script.isSeeded());
            return;
        }

        Script script = existing.get();
        Optional<ScriptVersion> active = versionService.getActive(script.getId());

        if (active.isEmpty()) {
            versionService.registerInitial(script.getId(), hash, size, ScriptVersionSource.VOLUME_RESCAN,
                    "system", "registered from volume");
            log.info("Registered initial version for existing script '{}'", name);
            return;
        }

        if (active.get().getHash().equals(hash)) {
            // Содержимое не изменилось
            if (!script.isEnabled()) {
                script.setEnabled(true);
                scriptRepo.save(script);
                log.info("Script '{}' re-enabled (file restored)", name);
            }
            return;
        }

        // Содержимое изменилось вне UI — не имеем старого контента для снапшота
        versionService.bumpMinor(script.getId(), hash, size,
                ScriptVersionSource.VOLUME_RESCAN, null, "system", "changed on disk");
        log.info("Script '{}' changed on disk → bumped to next minor (no snapshot available)", name);

        if (!script.isEnabled()) {
            script.setEnabled(true);
            scriptRepo.save(script);
        }
    }

    // =====================================================================
    // Чтение
    // =====================================================================

    public List<Script> listAll() {
        return scriptRepo.findAllByOrderBySortOrderAscNameAsc();
    }

    public List<Script> listEnabled() {
        return scriptRepo.findByEnabledTrueOrderBySortOrderAscNameAsc();
    }

    public List<Script> listEntrypoints() {
        return scriptRepo.findByEntrypointTrueAndEnabledTrueOrderBySortOrderAscNameAsc();
    }

    public List<Script> listEntrypointsForPlatform(String platform) {
        return listEntrypoints().stream()
                .filter(s -> "any".equals(s.getPlatform()) || platform.equals(s.getPlatform()))
                .toList();
    }

    public Optional<Script> findByName(String name) {
        return scriptRepo.findByName(name);
    }

    public Optional<Script> findById(UUID id) {
        return scriptRepo.findById(id);
    }

    public Script getByNameOrThrow(String name) {
        return scriptRepo.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Script not found: " + name));
    }

    public Script getByIdOrThrow(UUID id) {
        return scriptRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Script not found: " + id));
    }

    // =====================================================================
    // Граф зависимостей
    // =====================================================================

    /** Прямые зависимости (один уровень). */
    public Set<String> getDirectDependencies(String scriptName) {
        ensureDependencyCache();
        return dependencyCache.getOrDefault(scriptName, Set.of());
    }

    /** Транзитивные зависимости (BFS, без циклов, без самого скрипта). */
    public Set<String> getTransitiveDependencies(String scriptName) {
        ensureDependencyCache();
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(getDirectDependencies(scriptName));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!result.add(current)) continue;
            queue.addAll(dependencyCache.getOrDefault(current, Set.of()));
        }
        result.remove(scriptName);
        return result;
    }

    /** Обратные зависимости: кто ссылается на этот скрипт. */
    public Set<String> getReverseDependencies(String scriptName) {
        ensureDependencyCache();
        Set<String> result = new LinkedHashSet<>();
        dependencyCache.forEach((from, deps) -> {
            if (deps.contains(scriptName)) result.add(from);
        });
        return result;
    }

    private void ensureDependencyCache() {
        if (dependencyCacheValid.get()) return;
        synchronized (this) {
            if (dependencyCacheValid.get()) return;
            Map<String, Set<String>> fresh = computeDependencyGraph();
            dependencyCache.clear();
            dependencyCache.putAll(fresh);
            dependencyCacheValid.set(true);
        }
    }

    private Map<String, Set<String>> computeDependencyGraph() {
        List<Script> all = listEnabled();
        Set<String> names = all.stream().map(Script::getName).collect(Collectors.toSet());
        Map<String, Set<String>> graph = new LinkedHashMap<>();

        for (Script s : all) {
            Set<String> deps = new LinkedHashSet<>();
            Path p = Path.of(scriptsHome, s.getName());
            if (!Files.exists(p)) {
                graph.put(s.getName(), deps);
                continue;
            }
            try {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                for (String candidate : names) {
                    if (candidate.equals(s.getName())) continue;
                    if (content.contains(candidate)) {
                        deps.add(candidate);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {} for dependency detection: {}", s.getName(), e.getMessage());
            }
            graph.put(s.getName(), deps);
        }

        log.debug("Dependency graph computed: {} scripts", graph.size());
        return graph;
    }

    // =====================================================================
    // Админские операции
    // =====================================================================

    @Transactional
    public void updateMetadata(UUID id,
                               String displayName,
                               String description,
                               String platform,
                               ScriptAccessLevel accessLevel,
                               boolean entrypoint,
                               int sortOrder,
                               boolean enabled) {
        updateMetadata(id, displayName, description, platform, accessLevel,
                entrypoint, sortOrder, enabled, "system");
    }

    @Transactional
    public void updateMetadata(UUID id,
                               String displayName,
                               String description,
                               String platform,
                               ScriptAccessLevel accessLevel,
                               boolean entrypoint,
                               int sortOrder,
                               boolean enabled,
                               String actor) {
        Script s = getByIdOrThrow(id);
        s.setDisplayName(displayName);
        s.setDescription(blankToNull(description));
        s.setPlatform(platform == null || platform.isBlank() ? "any" : platform);
        s.setAccessLevel(accessLevel == null ? ScriptAccessLevel.ADMIN : accessLevel);
        s.setEntrypoint(entrypoint);
        s.setSortOrder(sortOrder);
        s.setEnabled(enabled);
        s.setUpdatedBy(actor(actor));
        scriptRepo.save(s);
        log.info("Metadata updated for script '{}' by {}", s.getName(), actor(actor));
    }

    /**
     * Сохраняет новое содержимое через админский UI.
     * Перед записью снапшотит старый контент.
     */
    @Transactional
    public void saveContent(UUID id, String newContent) throws IOException {
        saveContent(id, newContent, "system", null, null, ScriptVersionSource.UI_EDIT);
    }

    /**
     * Полная версия сохранения: актор, комментарий и optimistic-lock по хэшу.
     *
     * @param expectedHash если задан — активная версия обязана иметь этот хэш,
     *                     иначе сохранение отклоняется (защита от перезаписи
     *                     чужой правки).
     */
    @Transactional
    public void saveContent(UUID id,
                            String newContent,
                            String actor,
                            String note,
                            String expectedHash,
                            ScriptVersionSource source) throws IOException {
        Script s = getByIdOrThrow(id);
        Path path = Path.of(scriptsHome, s.getName());
        Files.createDirectories(path.getParent());

        Optional<ScriptVersion> active = versionService.getActive(id);
        if (expectedHash != null && !expectedHash.isBlank()) {
            String currentHash = active.map(ScriptVersion::getHash).orElse(null);
            if (currentHash == null || !currentHash.equalsIgnoreCase(expectedHash.trim())) {
                throw new IllegalStateException(
                        "Скрипт уже изменён: ожидался hash " + expectedHash
                                + ", сейчас " + (currentHash == null ? "нет активной версии" : currentHash)
                                + ". Перечитайте скрипт и повторите правку.");
            }
        }

        byte[] oldBytes = Files.exists(path) ? Files.readAllBytes(path) : null;

        // Защита от правки файла в обход UI/MCP: active hash в БД может
        // совпадать с expectedHash, а на диске уже лежит другое содержимое.
        if (expectedHash != null && !expectedHash.isBlank() && oldBytes != null) {
            String diskHash = ScriptVersionService.sha256Hex(oldBytes);
            if (!diskHash.equalsIgnoreCase(expectedHash.trim())) {
                throw new IllegalStateException(
                        "Файл скрипта изменён на диске (hash " + diskHash
                                + "), перечитайте scripts_read и повторите правку.");
            }
        }

        String normalized = normalizeLineEndings(s.getName(), newContent == null ? "" : newContent);
        byte[] newBytes = normalized.getBytes(StandardCharsets.UTF_8);
        String newHash = ScriptVersionService.sha256Hex(newBytes);

        if (active.isPresent() && active.get().getHash().equals(newHash)) {
            log.debug("Script '{}' content unchanged, skipping version bump", s.getName());
            if (note != null && !note.isBlank()) {
                log.info("Script '{}' unchanged; note ignored: {}", s.getName(), note);
            }
            return;
        }

        Files.write(path, newBytes);

        versionService.bumpMinor(id, newHash, newBytes.length,
                source == null ? ScriptVersionSource.UI_EDIT : source,
                oldBytes, actor(actor), note);

        s.setUpdatedBy(actor(actor));
        scriptRepo.save(s);
        invalidateDependencyCache();
        log.info("Script '{}' saved by {}, new version registered", s.getName(), actor(actor));
    }

    /**
     * Создаёт новый скрипт через UI.
     * Файл пишется в volume, регистрируется в БД с версией 1.0.
     */
    @Transactional
    public Script createScript(String name,
                               String content,
                               String displayName,
                               String description,
                               String platform,
                               ScriptAccessLevel accessLevel,
                               boolean entrypoint,
                               int sortOrder,
                               boolean enabled) throws IOException {
        return createScript(name, content, displayName, description, platform,
                accessLevel, entrypoint, sortOrder, enabled, "system",
                ScriptVersionSource.UI_CREATE);
    }

    @Transactional
    public Script createScript(String name,
                               String content,
                               String displayName,
                               String description,
                               String platform,
                               ScriptAccessLevel accessLevel,
                               boolean entrypoint,
                               int sortOrder,
                               boolean enabled,
                               String actor,
                               ScriptVersionSource source) throws IOException {
        validateNewScriptName(name);

        if (content == null) content = "";
        String normalized = normalizeLineEndings(name, content);
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);

        Path path = Path.of(scriptsHome, name);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);

        Script script = scriptRepo.save(Script.builder()
                .name(name)
                .displayName(blankToNull(displayName) != null ? displayName : name)
                .description(blankToNull(description))
                .platform(platform == null || platform.isBlank() ? guessPlatform(name) : platform)
                .accessLevel(accessLevel == null ? ScriptAccessLevel.ADMIN : accessLevel)
                .entrypoint(entrypoint)
                .sortOrder(sortOrder)
                .enabled(enabled)
                .seeded(false)
                .updatedBy(actor(actor))
                .build());

        String hash = ScriptVersionService.sha256Hex(bytes);
        versionService.registerInitial(script.getId(), hash, bytes.length,
                source == null ? ScriptVersionSource.UI_CREATE : source,
                actor(actor), "created");

        invalidateDependencyCache();
        log.info("Script '{}' created by {} ({} bytes)", name, actor(actor), bytes.length);
        return script;
    }

    private void validateNewScriptName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя файла обязательно");
        }
        if (!isAllowedExtension(name)) {
            throw new IllegalArgumentException(
                    "Недопустимое расширение. Разрешены: bat, cmd, ps1, sh, txt, json");
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Имя может содержать только буквы, цифры, точку, дефис и подчёркивание (до 64 символов)");
        }
        if (scriptRepo.existsByName(name)) {
            throw new IllegalArgumentException("Скрипт с таким именем уже существует");
        }
        if (fileService.isBundled(name)) {
            throw new IllegalArgumentException(
                    "Скрипт '" + name + "' входит в поставку Pohr и уже seed-ится автоматически");
        }
        if (Files.exists(Path.of(scriptsHome, name))) {
            throw new IllegalArgumentException(
                    "Файл '" + name + "' уже существует в volume. Перезапустите Pohr для регистрации или удалите его вручную.");
        }
    }

    @Transactional
    public void rename(UUID id, String newName) throws IOException {
        Script s = getByIdOrThrow(id);
        if (s.getName().equals(newName)) return;
        if (scriptRepo.existsByName(newName)) {
            throw new IllegalArgumentException("Script with name '" + newName + "' already exists");
        }

        Path oldPath = Path.of(scriptsHome, s.getName());
        Path newPath = Path.of(scriptsHome, newName);

        if (Files.exists(newPath)) {
            throw new IllegalStateException("File already exists in volume: " + newPath);
        }
        if (Files.exists(oldPath)) {
            Files.move(oldPath, newPath);
        }

        String previousName = s.getName();
        s.setName(newName);
        scriptRepo.save(s);
        invalidateDependencyCache();
        log.info("Script '{}' renamed to '{}'", previousName, newName);
    }

    @Transactional
    public void bumpMajor(UUID id) {
        bumpMajor(id, "system", null, ScriptVersionSource.UI_EDIT);
    }

    @Transactional
    public void bumpMajor(UUID id, String actor, String note, ScriptVersionSource source) {
        Script s = getByIdOrThrow(id);
        ScriptVersion active = versionService.getActiveOrThrow(id);
        versionService.bumpMajor(id, active.getHash(), active.getSize(),
                source == null ? ScriptVersionSource.UI_EDIT : source,
                actor(actor), note);
        s.setUpdatedBy(actor(actor));
        scriptRepo.save(s);
        log.info("Script '{}' bumped to next major version by {}", s.getName(), actor(actor));
    }

    @Transactional
    public void rollback(UUID id, UUID targetVersionId) throws IOException {
        rollback(id, targetVersionId, "system", null);
    }

    @Transactional
    public void rollback(UUID id, UUID targetVersionId, String actor, String note) throws IOException {
        Script s = getByIdOrThrow(id);
        ScriptVersion target = versionService.getHistory(id).stream()
                .filter(v -> v.getId().equals(targetVersionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + targetVersionId));

        if (target.getSnapshotPath() == null) {
            throw new IllegalStateException(
                    "Version " + target.getVersion() + " has no snapshot, rollback impossible");
        }

        byte[] targetContent = versionService.readSnapshot(target.getSnapshotPath());
        String targetHash = ScriptVersionService.sha256Hex(targetContent);

        Path path = Path.of(scriptsHome, s.getName());
        byte[] currentContent = Files.exists(path) ? Files.readAllBytes(path) : null;

        Files.write(path, targetContent);

        versionService.rollback(id, targetVersionId, targetHash, targetContent.length,
                currentContent, actor(actor), note);
        s.setUpdatedBy(actor(actor));
        scriptRepo.save(s);
        invalidateDependencyCache();
        log.info("Script '{}' rolled back to v{} by {}", s.getName(), target.getVersion(), actor(actor));
    }

    /**
     * Копирует classpath-версию поверх файла в volume и бампает версию.
     * Если classpath-версии нет — IllegalArgumentException.
     */
    @Transactional
    public void reseedFromBundled(UUID id) throws IOException {
        reseedFromBundled(id, "system", ScriptVersionSource.UI_EDIT);
    }

    @Transactional
    public void reseedFromBundled(UUID id, String actor, ScriptVersionSource source) throws IOException {
        Script s = getByIdOrThrow(id);
        if (!fileService.isBundled(s.getName())) {
            throw new IllegalArgumentException(
                    "Script '" + s.getName() + "' has no bundled version");
        }
        Path path = Path.of(scriptsHome, s.getName());
        byte[] oldBytes = Files.exists(path) ? Files.readAllBytes(path) : null;
        byte[] bundled = fileService.readBundledBytes(s.getName());

        Files.createDirectories(path.getParent());
        Files.write(path, bundled);

        String newHash = ScriptVersionService.sha256Hex(bundled);
        Optional<ScriptVersion> active = versionService.getActive(id);
        if (active.isPresent() && active.get().getHash().equals(newHash)) {
            log.debug("Script '{}' already matches bundled, skipping bump", s.getName());
            return;
        }

        versionService.bumpMinor(id, newHash, bundled.length,
                source == null ? ScriptVersionSource.UI_EDIT : source,
                oldBytes, actor(actor), "reset to bundled");

        s.setUpdatedBy(actor(actor));
        if (!s.isSeeded()) {
            s.setSeeded(true);
        }
        scriptRepo.save(s);
        invalidateDependencyCache();
        log.info("Script '{}' re-seeded from bundled classpath", s.getName());
    }

    @Transactional
    public void delete(UUID id) throws IOException {
        delete(id, "system");
    }

    @Transactional
    public void delete(UUID id, String actor) throws IOException {
        Script s = getByIdOrThrow(id);
        Path path = Path.of(scriptsHome, s.getName());
        Files.deleteIfExists(path);
        versionService.purgeSnapshots(id);
        scriptRepo.delete(s);
        invalidateDependencyCache();
        log.info("Script '{}' deleted by {}", s.getName(), actor(actor));
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Абсолютный путь файла скрипта в volume. */
    public Path resolvePath(Script script) {
        return Path.of(scriptsHome, script.getName());
    }

    public Path scriptsHomePath() {
        return Path.of(scriptsHome);
    }

    /** Разрешено ли расширение (bat/cmd/ps1/sh/txt/json). */
    public boolean isAllowedFileName(String name) {
        return name != null && isAllowedExtension(name);
    }

    /** Нормализует line endings так же, как при сохранении. */
    public String normalizeForName(String name, String content) {
        return normalizeLineEndings(name, content == null ? "" : content);
    }

    private boolean isAllowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        return ALLOWED_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    private String guessPlatform(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")) {
            return "windows";
        }
        if (lower.endsWith(".sh")) return "linux";
        return "any";
    }

    /** .sh — LF; .bat/.cmd/.ps1 — CRLF; остальное — как есть. */
    private String normalizeLineEndings(String name, String content) {
        String lower = name.toLowerCase();
        String lf = content.replace("\r\n", "\n").replace("\r", "\n");
        if (lower.endsWith(".sh")) return lf;
        if (lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")) {
            return lf.replace("\n", "\r\n");
        }
        return content;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String actor(String value) {
        if (value == null || value.isBlank()) {
            return "system";
        }
        String trimmed = value.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}