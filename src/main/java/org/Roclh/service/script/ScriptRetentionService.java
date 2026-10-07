package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.repository.script.ScriptDownloadRepository;
import org.Roclh.repository.script.ScriptVersionReportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Ротация служебных логов скриптов:
 * script_downloads и script_version_reports — 1000 записей на юзера, 2 недели.
 * script_error_reports — 30 дней для неразобранных.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptRetentionService {

    private static final int REPORTS_RETENTION_DAYS = 14;
    private static final int ERROR_RETENTION_DAYS = 30;

    private final ScriptDownloadRepository downloads;
    private final ScriptVersionReportRepository versionReports;
    private final ScriptErrorReportService errorReports;

    @Value("${pohr.scripts.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${pohr.scripts.retention.cron:0 0 3 * * *}")
    @Transactional
    public void rotate() {
        if (!enabled) return;

        Instant cutoff = Instant.now().minusSeconds(REPORTS_RETENTION_DAYS * 86400L);

        int d = downloads.deleteOlderThan(cutoff);
        int r = versionReports.deleteOlderThan(cutoff);
        int e = errorReports.purgeStale(ERROR_RETENTION_DAYS);

        if (d + r + e > 0) {
            log.info("Script retention: downloads={} versionReports={} staleErrors={}",
                    d, r, e);
        }
    }
}