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
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP_LAST_VERSIONS = 5;

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
        ScriptVersionNumber v = ScriptVersionNumber.initial();
        ScriptVersion sv = ScriptVersion.builder()
                .scriptId(scriptId)
                .version(v.toString())
                .versionMajor(v.major())
                .versionMinor(v.minor())
                .hash(hash)
                .size((int) size)
                .source(source)
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
        ScriptVersion active = getActive(scriptId).orElse(null);
        if (active == null) {
            return registerInitial(scriptId, hash, size, source);
        }

        ScriptVersionNumber current = ScriptVersionNumber.parse(active.getVersion());
        ScriptVersionNumber next = current.nextMinor();

        return registerNewVersion(scriptId, next, hash, size, source, active,
                contentForSnapshot, null);
    }

    /**
     * Мажорный bump. Файл не меняется — только номер. Снапшот не делаем.
     */
    @Transactional
    public ScriptVersion bumpMajor(UUID scriptId,
                                   String hash,
                                   long size,
                                   ScriptVersionSource source) {
        ScriptVersion active = getActive(scriptId).orElse(null);
        if (active == null) {
            return registerInitial(scriptId, hash, size, source);
        }

        ScriptVersionNumber current = ScriptVersionNumber.parse(active.getVersion());
        ScriptVersionNumber next = current.nextMajor();

        return registerNewVersion(scriptId, next, hash, size, source, active,
                null, null);
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
                active, contentForSnapshot, target.getId());
    }

    private ScriptVersion registerNewVersion(UUID scriptId,
                                             ScriptVersionNumber version,
                                             String hash,
                                             long size,
                                             ScriptVersionSource source,
                                             ScriptVersion previousActive,
                                             byte[] contentForSnapshot,
                                             UUID restoreOf) {
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
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
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
}