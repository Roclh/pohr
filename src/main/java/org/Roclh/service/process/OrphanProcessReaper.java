package org.Roclh.service.process;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.telegram.TelemtProcessManager;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Периодически убивает «лишние» xray/telemt, которые не находятся под
 * управлением менеджеров процессов.
 *
 * <p>Зачем: при старте {@code AbstractProcessManager#cleanupBeforeStart} делает
 * разовую зачистку по имени, но если процесс остался жить после неудачного
 * старта, валидации конфига или ручного запуска внутри контейнера, до
 * следующего рестарта он висит и занимает память. На контейнере с жёстким
 * лимитом это прямой путь в reclaim.</p>
 *
 * <p>Кого не трогаем: управляемые pid'ы, сам процесс приложения и короткоживущие
 * вызовы {@code xray api ...} / {@code xray run -test} — у них то же имя
 * бинарника, поэтому есть два предохранителя: минимальный возраст и проверка
 * командной строки.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanProcessReaper {

    /** Имена бинарников, которыми управляет приложение. */
    private static final Set<String> MANAGED_NAMES = Set.of("xray", "telemt");

    /** Признаки короткоживущего вызова того же бинарника. */
    private static final List<String> TRANSIENT_MARKERS = List.of(" api ", " statsquery", " -test", "test -c");

    private final XrayProcessManager xray;
    private final TelemtProcessManager telemt;

    private final AtomicLong killedTotal = new AtomicLong();

    @Value("${pohr.processes.reaper.enabled:true}")
    private boolean enabled;

    @Value("${pohr.processes.reaper.min-age-seconds:300}")
    private long minAgeSeconds;

    private volatile Instant lastSweepAt;
    private volatile int killedLastSweep;
    private volatile int seenLastSweep;

    /** Сводка для MCP: ничего не стоит и не требует ssh. */
    public record Stats(boolean enabled, long minAgeSeconds, Instant lastSweepAt,
                        long killedTotal, int killedLastSweep, int managedSeen) {
    }

    public Stats stats() {
        return new Stats(enabled, minAgeSeconds, lastSweepAt,
                killedTotal.get(), killedLastSweep, seenLastSweep);
    }

    @Scheduled(fixedDelayString = "${pohr.processes.reaper.interval-ms:300000}",
            initialDelayString = "${pohr.processes.reaper.initial-delay-ms:120000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            doSweep();
        } catch (Exception e) {
            log.warn("Orphan process sweep failed: {}", e.getMessage());
        } finally {
            lastSweepAt = Instant.now();
        }
    }

    private void doSweep() {
        Set<Long> managed = new LinkedHashSet<>();
        addPid(managed, xray.pid());
        addPid(managed, telemt.pid());
        long self = ProcessHandle.current().pid();
        managed.add(self);

        // Процессов в контейнере единицы, поэтому полный обход /proc дешевле
        // любой оптимизации с кэшем pid'ов.
        List<ProcessTable.Proc> all = ProcessTable.snapshot(false);
        int seen = 0;
        int killed = 0;
        for (ProcessTable.Proc proc : all) {
            if (!MANAGED_NAMES.contains(proc.name())) continue;
            seen++;
            if (managed.contains(proc.pid())) continue;
            if (proc.ageSeconds() >= 0 && proc.ageSeconds() < minAgeSeconds) continue;
            if (isTransient(proc.cmdLine())) continue;

            log.warn("Killing orphan {} process (pid={}, age={}s, rss={} MB, cmd='{}')",
                    proc.name(), proc.pid(), proc.ageSeconds(),
                    Math.round(proc.rssMb()), proc.cmdLine());
            if (kill(proc.pid())) {
                killed++;
                killedTotal.incrementAndGet();
            }
        }
        seenLastSweep = seen;
        killedLastSweep = killed;
        if (killed > 0) {
            log.warn("Orphan sweep: killed {} process(es), {} managed still running", killed, managed.size() - 1);
        } else {
            log.debug("Orphan sweep: nothing to kill ({} xray/telemt processes, all managed)", seen);
        }
    }

    private static boolean isTransient(String cmdLine) {
        if (cmdLine == null || cmdLine.isEmpty()) return false;
        String normalized = " " + cmdLine.toLowerCase(Locale.ROOT) + " ";
        for (String marker : TRANSIENT_MARKERS) {
            if (normalized.contains(marker)) return true;
        }
        return false;
    }

    private static void addPid(Set<Long> target, Long pid) {
        if (pid != null) target.add(pid);
    }

    private static boolean kill(long pid) {
        return ProcessHandle.of(pid).map(handle -> {
            handle.destroy();
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // не смогли — процесса не будет в следующем снимке только если он умер
                }
            }
            return !handle.isAlive();
        }).orElse(false);
    }
}
