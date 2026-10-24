package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.ScriptVersion;
import org.Roclh.model.script.ScriptVersionSource;
import org.Roclh.repository.script.ScriptVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Управление версиями скриптов: регистрация, bump, rollback, .history/.
 * Работает с БД и файлами снапшотов. Не знает о содержимом скриптов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptVersionService {

    private static final DateTimeFormatter SNAPSHOT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int KEEP_LAST_VERSIONS = 5;
    private static final String SYSTEM_ACTOR = "system";

    private final ScriptVersionRepository versionRepo;

    @Value("${pohr.scripts.home}")
    private String scriptsHome;

    // =====================================================================
    // Чтение
    // =====================================================================

    public Optional<ScriptVersion> getActive(UUID scriptId) {
        return versionRepo.findByScriptIdAndInvalidatedAtIsNull(scriptId);
    }

    public List<ScriptVersion> getHistory(UUID scriptId) {
        return versionRepo.findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId);
    }

    public ScriptVersion getActiveOrThrow(UUID scriptId) {
        return getActive(scriptId).orElseThrow(
                () -> new IllegalStateException("No active version for script " + scriptId));
    }

    // =====================================================================
    // Регистрация
    // =====================================================================

    /**
     * Первая версия скрипта. Не инвалидирует ничего — активной ещё нет.
     * contentForSnapshot не нужен: снапшотить нечего.
     */
    @Transactional
    public ScriptVersion registerInitial(UUID scriptId,
                                         String hash,
                                         long size,
                                         ScriptVersionSource source) {
        return registerInitial(scriptId, hash, size, source, SYSTEM_ACTOR, null);
    }

    @Transactional
    public ScriptVersion registerInitial(UUID scriptId,
                                         String hash,
                                         long size,
                                         ScriptVersionSource source,
                                         String changedBy,
                                         String note) {
        ScriptVersionNumber v = ScriptVersionNumber.initial();
        ScriptVersion sv = ScriptVersion.builder()
                .scriptId(scriptId)
                .version(v.toString())
                .versionMajor(v.major())
                .versionMinor(v.minor())
                .hash(hash)
                .size((int) size)
                .source(source)
                .changedBy(actor(changedBy))
                .changeNote(trimNote(note))
                .build();
        ScriptVersion saved = versionRepo.save(sv);
        log.info("Registered initial version {} for script {} ({})", v, scriptId, source);
        return saved;
    }

    /**
     * Минорный bump. Если contentForSnapshot передан — сохраняет снапшот
     * текущей активной версии в .history/.
     */
    @Transactional
    public ScriptVersion bumpMinor(UUID scriptId,
                                   String hash,
                                   long size,
                                   ScriptVersionSource source,
                                   byte[] contentForSnapshot) {
        return bumpMinor(scriptId, hash, size, source, contentForSnapshot, SYSTEM_ACTOR, null);
    }

    @Transactional
    public ScriptVersion bumpMinor(UUID scriptId,
                                   String hash,
                                   long size,
                                   ScriptVersionSource source,
                                   byte[] contentForSnapshot,
                                   String changedBy,
                                   String note) {
        ScriptVersion active = getActive(scriptId).orElse(null);
        if (active == null) {
            return registerInitial(scriptId, hash, size, source, changedBy, note);
        }

        ScriptVersionNumber current = ScriptVersionNumber.parse(active.getVersion());
        ScriptVersionNumber next = current.nextMinor();

        return registerNewVersion(scriptId, next, hash, size, source, active,
                contentForSnapshot, null, changedBy, note);
    }

    /**
     * Мажорный bump. Файл не меняется — только номер. Снапшот не делаем.
     */
    @Transactional
    public ScriptVersion bumpMajor(UUID scriptId,
                                   String hash,
                                   long size,
                                   ScriptVersionSource source) {
        return bumpMajor(scriptId, hash, size, source, SYSTEM_ACTOR, null);
    }

    @Transactional
    public ScriptVersion bumpMajor(UUID scriptId,
                                   String hash,
                                   long size,
                                   ScriptVersionSource source,
                                   String changedBy,
                                   String note) {
        ScriptVersion active = getActive(scriptId).orElse(null);
        if (active == null) {
            return registerInitial(scriptId, hash, size, source, changedBy, note);
        }

        ScriptVersionNumber current = ScriptVersionNumber.parse(active.getVersion());
        ScriptVersionNumber next = current.nextMajor();

        return registerNewVersion(scriptId, next, hash, size, source, active,
                null, null, changedBy, note);
    }

    /**
     * Откат на конкретную версию. Контент берётся из снапшота.
     * Новая версия = minor-bump от активной, restore_of = targetVersionId.
     */
    @Transactional
    public ScriptVersion rollback(UUID scriptId,
                                  UUID targetVersionId,
                                  String hash,
                                  long size,
                                  byte[] contentForSnapshot) {
        return rollback(scriptId, targetVersionId, hash, size, contentForSnapshot, SYSTEM_ACTOR, null);
    }

    @Transactional
    public ScriptVersion rollback(UUID scriptId,
                                  UUID targetVersionId,
                                  String hash,
                                  long size,
                                  byte[] contentForSnapshot,
                                  String changedBy,
                                  String note) {
        ScriptVersion target = versionRepo.findById(targetVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + targetVersionId));
        if (!target.getScriptId().equals(scriptId)) {
            throw new IllegalArgumentException("Version " + targetVersionId + " does not belong to script " + scriptId);
        }

        ScriptVersion active = getActive(scriptId)
                .orElseThrow(() -> new IllegalStateException("No active version to rollback from"));

        ScriptVersionNumber current = ScriptVersionNumber.parse(active.getVersion());
        ScriptVersionNumber next = current.nextMinor();

        log.info("Rollback script {} from {} to {} (as new version {})",
                scriptId, current, target.getVersion(), next);

        return registerNewVersion(scriptId, next, hash, size, ScriptVersionSource.ROLLBACK,
                active, contentForSnapshot, target.getId(), changedBy, note);
    }

    private ScriptVersion registerNewVersion(UUID scriptId,
                                             ScriptVersionNumber version,
                                             String hash,
                                             long size,
                                             ScriptVersionSource source,
                                             ScriptVersion previousActive,
                                             byte[] contentForSnapshot,
                                             UUID restoreOf,
                                             String changedBy,
                                             String note) {
        // 1. Снапшот предыдущей активной — до инвалидации
        if (contentForSnapshot != null) {
            try {
                Path snapshot = writeSnapshot(scriptId, previousActive.getVersion(), contentForSnapshot);
                previousActive.setSnapshotPath(snapshot.toString());
                versionRepo.save(previousActive);
            } catch (IOException e) {
                log.warn("Failed to write snapshot for {}@{}: {}",
                        scriptId, previousActive.getVersion(), e.getMessage());
            }
        }

        // 2. Инвалидируем активную
        versionRepo.invalidateActive(scriptId, Instant.now());

        // 3. Создаём новую
        ScriptVersion sv = ScriptVersion.builder()
                .scriptId(scriptId)
                .version(version.toString())
                .versionMajor(version.major())
                .versionMinor(version.minor())
                .hash(hash)
                .size((int) size)
                .source(source)
                .restoreOf(restoreOf)
                .changedBy(actor(changedBy))
                .changeNote(trimNote(note))
                .build();
        ScriptVersion saved = versionRepo.save(sv);
        log.info("Registered version {} for script {} ({}, hash={})",
                version, scriptId, source, hash.substring(0, Math.min(8, hash.length())));

        // 4. Ротация .history/
        rotateHistory(scriptId);

        return saved;
    }

    // =====================================================================
    // .history/ — файлы снапшотов
    // =====================================================================

    private Path historyDir() {
        return Path.of(scriptsHome, ".history");
    }

    private Path writeSnapshot(UUID scriptId, String version, byte[] content) throws IOException {
        Path dir = historyDir();
        Files.createDirectories(dir);
        String stamp = SNAPSHOT_STAMP.format(LocalDateTime.now());
        String fileName = scriptId + "." + version + "." + stamp + ".bak";
        Path target = dir.resolve(fileName);
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            // Два бэкапа в одну миллисекунду — добавляем короткий суффикс.
            fileName = scriptId + "." + version + "." + stamp + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".bak";
            target = dir.resolve(fileName);
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        }
        log.debug("Snapshot written: {}", target);
        return target;
    }

    /**
     * Читает содержимое снапшота. Бросает, если файла нет.
     * Используется при rollback.
     */
    public byte[] readSnapshot(String snapshotPath) throws IOException {
        Path p = Path.of(snapshotPath);
        if (!Files.exists(p)) {
            throw new IOException("Snapshot not found: " + snapshotPath);
        }
        return Files.readAllBytes(p);
    }

    // =====================================================================
    // Ручные бэкапы (MCP scripts_backup)
    // =====================================================================

    public record BackupInfo(String fileName, String version, Instant createdAt, long size) {}

    /** Копирует текущее содержимое в .history/ и возвращает путь к файлу. */
    public Path createManualBackup(UUID scriptId, String version, byte[] content) throws IOException {
        return writeSnapshot(scriptId, version, content);
    }

    /**
     * Список снапшотов скрипта (свежие первыми).
     *
     * <p>Сначала берём пути из БД: это надёжно работает даже если старые
     * снапшоты лежат не в текущем {@code scriptsHome/.history} или названы
     * по-старому. Затем добавляем ручные .bak из .history, которых в БД нет.</p>
     */
    public List<BackupInfo> listBackups(UUID scriptId) throws IOException {
        Map<String, BackupInfo> byPath = new LinkedHashMap<>();

        // 1. Снапшоты, на которые ссылается история версий.
        for (ScriptVersion version : versionRepo
                .findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId)) {
            if (version.getSnapshotPath() == null || version.getSnapshotPath().isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(version.getSnapshotPath());
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                BackupInfo info = backupInfo(path, version.getVersion());
                if (info != null) {
                    byPath.putIfAbsent(path.toAbsolutePath().normalize().toString(), info);
                }
            } catch (Exception e) {
                log.warn("Failed to stat snapshot {}: {}", version.getSnapshotPath(), e.getMessage());
            }
        }

        // 2. Ручные бэкапы, созданные scripts_backup (в БД их нет).
        Path dir = historyDir();
        if (Files.exists(dir)) {
            String prefix = scriptId + ".";
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.startsWith(prefix) && n.endsWith(".bak");
                        })
                        .map(this::toBackupInfo)
                        .filter(Objects::nonNull)
                        .forEach(b -> byPath.putIfAbsent(
                                dir.resolve(b.fileName()).toAbsolutePath().normalize().toString(), b));
            }
        }

        return byPath.values().stream()
                .sorted(Comparator.comparing(BackupInfo::createdAt).reversed())
                .toList();
    }

    /**
     * Читает снапшот по имени файла. Разрешены только файлы этого скрипта
     * внутри .history/ — защита от path traversal.
     */
    public byte[] readBackup(UUID scriptId, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Не указан файл бэкапа");
        }
        Path asPath = Path.of(fileName);
        if (!asPath.getFileName().toString().equals(fileName)) {
            throw new IllegalArgumentException("Ожидается имя файла без пути: " + fileName);
        }

        // Сначала ищем среди путей из БД: старые снапшоты могут лежать
        // не в текущем scriptsHome/.history и называться иначе.
        for (ScriptVersion version : versionRepo
                .findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId)) {
            if (version.getSnapshotPath() == null || version.getSnapshotPath().isBlank()) {
                continue;
            }
            Path snapshot = Path.of(version.getSnapshotPath());
            if (snapshot.getFileName().toString().equals(fileName) && Files.isRegularFile(snapshot)) {
                return Files.readAllBytes(snapshot);
            }
        }

        if (!fileName.startsWith(scriptId + ".")) {
            throw new IllegalArgumentException("Бэкап не принадлежит этому скрипту: " + fileName);
        }
        Path dir = historyDir().toAbsolutePath().normalize();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Бэкап не найден: " + fileName);
        }
        return Files.readAllBytes(target);
    }

    private BackupInfo toBackupInfo(Path path) {
        String name = path.getFileName().toString();
        int firstDot = name.indexOf('.');
        if (firstDot <= 0 || !name.endsWith(".bak")) return null;

        // {scriptId}.{version}.{stamp}.bak
        String withoutBak = name.substring(firstDot + 1, name.length() - ".bak".length());
        int lastDot = withoutBak.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String version = withoutBak.substring(0, lastDot);
        if (!version.matches("\\d+\\.\\d+")) return null;
        return backupInfo(path, version);
    }

    private BackupInfo backupInfo(Path path, String version) {
        try {
            return new BackupInfo(
                    path.getFileName().toString(),
                    version,
                    Files.getLastModifiedTime(path).toInstant(),
                    Files.size(path));
        } catch (IOException e) {
            log.warn("Failed to stat backup {}: {}", path, e.getMessage());
            return null;
        }
    }

    public Path getHistoryDir() {
        return historyDir();
    }

    /**
     * Оставляет: последние 5 версий + последняя версия каждого предыдущего мажора.
     * Остальные снапшоты удаляются с диска. Строки в БД не трогаем —
     * snapshot_path остаётся как исторический факт.
     */
    @Transactional
    public void rotateHistory(UUID scriptId) {
        List<ScriptVersion> history = versionRepo
                .findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId);
        if (history.isEmpty()) return;

        int currentMajor = history.get(0).getVersionMajor();
        Set<UUID> keep = new LinkedHashSet<>();

        // Последние KEEP_LAST_VERSIONS
        history.stream().limit(KEEP_LAST_VERSIONS).forEach(v -> keep.add(v.getId()));

        // Последняя версия каждого мажора < currentMajor
        Map<Integer, ScriptVersion> lastPerMajor = new HashMap<>();
        for (ScriptVersion v : history) {
            lastPerMajor.merge(v.getVersionMajor(), v, (a, b) ->
                    a.getVersionMinor() >= b.getVersionMinor() ? a : b);
        }
        lastPerMajor.forEach((major, v) -> {
            if (major < currentMajor) keep.add(v.getId());
        });

        // Удалить файлы тех, кого не держим
        for (ScriptVersion v : history) {
            if (keep.contains(v.getId())) continue;
            if (v.getSnapshotPath() == null) continue;
            try {
                Files.deleteIfExists(Path.of(v.getSnapshotPath()));
            } catch (IOException e) {
                log.warn("Failed to delete snapshot {}: {}", v.getSnapshotPath(), e.getMessage());
            }
        }
    }

    /**
     * Полная очистка всех снапшотов скрипта. Нужно при удалении скрипта.
     */
    @Transactional
    public void purgeSnapshots(UUID scriptId) {
        List<ScriptVersion> history = versionRepo.findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId);
        for (ScriptVersion v : history) {
            if (v.getSnapshotPath() == null) continue;
            try {
                Files.deleteIfExists(Path.of(v.getSnapshotPath()));
            } catch (IOException e) {
                log.warn("Failed to delete snapshot {}: {}", v.getSnapshotPath(), e.getMessage());
            }
        }

        // Ручные .bak из .history не имеют строки в БД — удаляем по префиксу id.
        Path dir = historyDir();
        if (Files.exists(dir)) {
            String prefix = scriptId + ".";
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.startsWith(prefix) && n.endsWith(".bak");
                        })
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("Failed to delete manual backup {}: {}", p, e.getMessage());
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to scan .history for script {}: {}", scriptId, e.getMessage());
            }
        }
    }

    // =====================================================================
    // Хэш
    // =====================================================================

    public static String sha256Hex(byte[] data) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String actor(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM_ACTOR;
        }
        String trimmed = value.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private static String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > 512 ? trimmed.substring(0, 512) : trimmed;
    }
}