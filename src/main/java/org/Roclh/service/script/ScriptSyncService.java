package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.*;
import org.Roclh.repository.script.ScriptVersionReportRepository;
import org.Roclh.repository.script.UserScriptVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Обработка sync-отчётов от клиентских скриптов.
 *
 * <p>Клиент присылает {rootScript, versions:{name:version,...}}.
 * Сервис делает upsert в user_script_versions, append в script_version_reports
 * (только при изменении версии), возвращает latest + updatesAvailable.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptSyncService {

    private final ScriptCatalogService catalog;
    private final ScriptVersionService versionService;
    private final UserScriptVersionRepository userVersions;
    private final ScriptVersionReportRepository versionReports;

    public record SyncRequest(
            String rootScript,
            Map<String, String> versions,
            String reportedAt
    ) {}

    public record SyncResponse(
            Map<String, String> latest,
            List<String> updatesAvailable,
            List<String> unknownScripts
    ) {}

    @Transactional
    public SyncResponse handle(UUID userId,
                               SyncRequest request,
                               String clientIp,
                               String userAgent) {
        if (request.rootScript() == null || request.versions() == null || request.versions().isEmpty()) {
            throw new IllegalArgumentException("rootScript and versions are required");
        }

        Script root = catalog.findByName(request.rootScript()).orElse(null);
        if (root == null) {
            throw new IllegalArgumentException("Unknown root script: " + request.rootScript());
        }
        if (!root.isEntrypoint()) {
            throw new IllegalArgumentException("Script is not an entrypoint: " + request.rootScript());
        }

        Instant now = Instant.now();
        Map<String, String> latest = new LinkedHashMap<>();
        List<String> updates = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<String, String> e : request.versions().entrySet()) {
            String name = e.getKey();
            String reported = e.getValue();

            Script script = catalog.findByName(name).orElse(null);
            if (script == null) {
                unknown.add(name);
                continue;
            }

            Optional<ScriptVersion> active = versionService.getActive(script.getId());
            if (active.isEmpty()) continue;

            String current = active.get().getVersion();
            latest.put(name, current);

            // upsert текущего состояния
            upsertUserVersion(userId, script, current, now, clientIp, userAgent, reported);

            if (!current.equals(reported)) {
                updates.add(name);
            }
        }

        log.info("Sync from user {} root={} reported={} updates={} unknown={}",
                userId, request.rootScript(), request.versions().size(),
                updates.size(), unknown.size());

        return new SyncResponse(latest, updates, unknown);
    }

    private void upsertUserVersion(UUID userId,
                                   Script script,
                                   String currentVersion,
                                   Instant now,
                                   String clientIp,
                                   String userAgent,
                                   String reportedVersion) {
        UserScriptVersionId pk = new UserScriptVersionId(userId, script.getId());
        Optional<UserScriptVersion> existing = userVersions.findById(pk);

        String previousVersion = existing.map(UserScriptVersion::getVersion).orElse(null);
        boolean versionChanged = !Objects.equals(previousVersion, reportedVersion);

        ScriptVersionNumber num = ScriptVersionNumber.parse(reportedVersion);

        UserScriptVersion row = existing.orElseGet(() -> UserScriptVersion.builder()
                .userId(userId)
                .scriptId(script.getId())
                .build());
        row.setVersion(reportedVersion);
        row.setVersionMajor(num.major());
        row.setVersionMinor(num.minor());
        row.setReportedAt(now);
        row.setClientIp(clientIp);
        row.setUserAgent(truncate(userAgent, 512));
        userVersions.save(row);

        // append в историю — только если реально изменилось
        if (versionChanged) {
            versionReports.save(ScriptVersionReport.builder()
                    .userId(userId)
                    .scriptId(script.getId())
                    .version(reportedVersion)
                    .clientIp(clientIp)
                    .userAgent(truncate(userAgent, 512))
                    .reportedAt(now)
                    .build());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}