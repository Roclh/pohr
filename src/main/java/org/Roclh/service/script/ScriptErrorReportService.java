package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.Script;
import org.Roclh.model.script.ScriptErrorReport;
import org.Roclh.model.script.ScriptErrorStatus;
import org.Roclh.repository.script.ScriptErrorReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Приём и обработка отчётов об ошибках клиентских скриптов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptErrorReportService {

    private static final int MAX_REPORT_SIZE = 100 * 1024; // 100 KB

    private final ScriptErrorReportRepository repo;
    private final ScriptCatalogService catalog;

    public record ErrorRequest(
            String rootScript,
            String rootVersion,
            String stage,
            String reportText,
            String clientIp,
            String userAgent
    ) {}

    @Transactional
    public UUID accept(UUID userId, ErrorRequest req) {
        UUID rootScriptId = null;
        if (req.rootScript() != null) {
            rootScriptId = catalog.findByName(req.rootScript())
                    .map(Script::getId)
                    .orElse(null);
        }

        String text = req.reportText();
        if (text == null || text.isBlank()) {
            text = "(no report body)";
        }
        if (text.length() > MAX_REPORT_SIZE) {
            text = text.substring(0, MAX_REPORT_SIZE) + "\n\n[truncated at " + MAX_REPORT_SIZE + " bytes]";
        }

        ScriptErrorReport report = ScriptErrorReport.builder()
                .userId(userId)
                .rootScriptId(rootScriptId)
                .rootScriptVersion(truncate(req.rootVersion(), 32))
                .stage(truncate(req.stage(), 64))
                .status(ScriptErrorStatus.NEW)
                .reportText(text)
                .clientIp(req.clientIp())
                .userAgent(truncate(req.userAgent(), 512))
                .receivedAt(Instant.now())
                .build();

        ScriptErrorReport saved = repo.save(report);
        log.warn("Error report received: user={} script={} v={} stage={} id={}",
                userId, req.rootScript(), req.rootVersion(), req.stage(), saved.getId());
        return saved.getId();
    }

    public List<ScriptErrorReport> listAll() {
        return repo.findAllByOrderByReceivedAtDesc();
    }

    public List<ScriptErrorReport> listByStatus(ScriptErrorStatus status) {
        return repo.findByStatusOrderByReceivedAtDesc(status);
    }

    public Optional<ScriptErrorReport> findById(UUID id) {
        return repo.findById(id);
    }

    public long countNew() {
        return repo.countByStatus(ScriptErrorStatus.NEW);
    }

    public long countLast24h() {
        return repo.countByReceivedAtAfter(Instant.now().minusSeconds(86400));
    }

    @Transactional
    public void markInProgress(UUID id) {
        repo.findById(id).ifPresent(r -> {
            r.setStatus(ScriptErrorStatus.IN_PROGRESS);
            repo.save(r);
        });
    }

    /**
     * Удаление после разбора. Агрегат уходит в script_daily_stats
     * (реализация агрегации — отдельно, здесь только удаление).
     */
    @Transactional
    public void resolve(UUID id, UUID resolvedBy) {
        repo.findById(id).ifPresent(r -> {
            r.setStatus(ScriptErrorStatus.RESOLVED);
            r.setResolvedAt(Instant.now());
            r.setResolvedBy(resolvedBy);
            repo.save(r);
        });
    }

    @Transactional
    public void deleteResolved(UUID id) {
        repo.findById(id).ifPresent(r -> {
            if (r.getStatus() == ScriptErrorStatus.RESOLVED) {
                repo.delete(r);
            }
        });
    }

    @Transactional
    public int purgeStale(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        return repo.deleteStaleUnresolved(cutoff);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}