package org.Roclh.service.metric;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class ContainerMetrics {

    public boolean isInsideContainer() {
        return Files.exists(Path.of("/.dockerenv"))
                || Files.exists(Path.of("/run/.containerenv"));
    }

    /** RSS текущего Java-процесса (fallback для локального запуска). */
    public Long readCurrentProcessRssBytes() {
        return readProcessRssBytes(ProcessHandle.current().pid());
    }

    /** cgroup v2: /sys/fs/cgroup/memory.current. v1: .../memory/memory.usage_in_bytes. */
    public Long readCgroupMemoryBytes() {
        return readLong("/sys/fs/cgroup/memory.current",
                "/sys/fs/cgroup/memory/memory.usage_in_bytes");
    }

    /**
     * Реальное давление на память: поле {@code anon} из memory.stat.
     *
     * <p>{@code memory.current} включает page cache (файловый кэш БД, страницы
     * бинарников), который ядро вытесняет под давлением. Поэтому «занято 480 из
     * 480» на графике не значит, что процессам не хватает памяти: anon — это
     * единственная часть, которую нельзя освободить без свопа. Если anon
     * далёк от лимита, а current — нет, это не утечка, а кэш.</p>
     */
    public Long readCgroupAnonBytes() {
        return readCgroupMemoryStat().get("anon");
    }

    /** memory.stat как map (anon, file, slab, shmem, ...). Пустая map, если файла нет. */
    public Map<String, Long> readCgroupMemoryStat() {
        return readKeyValueFile("/sys/fs/cgroup/memory.stat",
                "/sys/fs/cgroup/memory/memory.stat");
    }

    /**
     * memory.events: сколько раз cgroup упиралась в лимит и были ли реальные
     * OOM-kill. {@code max > 0} при {@code oomKill = 0} — это reclaim, а не
     * падение процессов.
     */
    public Map<String, Long> readCgroupMemoryEvents() {
        return readKeyValueFile("/sys/fs/cgroup/memory.events",
                "/sys/fs/cgroup/memory/memory.oom_control");
    }

    private Map<String, Long> readKeyValueFile(String... paths) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String p : paths) {
            try {
                String content = Files.readString(Path.of(p));
                for (String line : content.split("\\R")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length != 2) continue;
                    try {
                        result.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException ignored) {
                        // v1 oom_control содержит "oom_kill_disable 0" и текст — пропускаем
                    }
                }
                if (!result.isEmpty()) return result;
            } catch (Exception ignored) {
                // файла нет (не cgroup v2) — пробуем следующий путь
            }
        }
        return result;
    }

    public Long readCgroupMemoryLimitBytes() {
        return readLong("/sys/fs/cgroup/memory.max",
                "/sys/fs/cgroup/memory/memory.limit_in_bytes");
    }

    /** cgroup v2 cpu.stat: "usage_usec 12345"; v1 cpuacct/cpuacct.usage: nanoseconds. */
    public Long readCgroupCpuUsageNanos() {
        // v2
        try {
            String stat = Files.readString(Path.of("/sys/fs/cgroup/cpu.stat"));
            for (String line : stat.split("\\R")) {
                if (line.startsWith("usage_usec")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1000L;
                }
            }
        } catch (Exception ignored) {}
        // v1
        Long ns = readLong("/sys/fs/cgroup/cpuacct/cpuacct.usage");
        return ns;
    }

    /** /proc/<pid>/status: VmRSS:  12345 kB → bytes. */
    public Long readProcessRssBytes(long pid) {
        try {
            String status = Files.readString(Path.of("/proc/" + pid + "/status"));
            for (String line : status.split("\\R")) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** /proc/<pid>/stat, поле 14 + 15 (utime + stime) в тиках → наносекунды. */
    public Long readProcessCpuNanos(long pid, long clockTicksPerSec) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            // формат: pid (comm) state ppid ... ; comm может содержать пробелы и скобки
            int close = stat.lastIndexOf(')');
            String rest = stat.substring(close + 2);
            String[] f = rest.split("\\s+");
            long utime = Long.parseLong(f[11]);  // поле 14
            long stime = Long.parseLong(f[12]);  // поле 15
            return (utime + stime) * 1_000_000_000L / clockTicksPerSec;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** /proc/<pid>/io: rchar + wchar — «объём прочитанных/записанных байт через syscalls». */
    public long[] readProcessIoBytes(long pid) {
        try {
            String io = Files.readString(Path.of("/proc/" + pid + "/io"));
            long rchar = 0, wchar = 0;
            for (String line : io.split("\\R")) {
                if (line.startsWith("rchar:")) rchar = parseLongAfterColon(line);
                if (line.startsWith("wchar:")) wchar = parseLongAfterColon(line);
            }
            return new long[]{rchar, wchar};
        } catch (Exception ignored) {
            return new long[]{0, 0};
        }
    }

    private static long parseLongAfterColon(String line) {
        return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
    }

    private Long readLong(String... paths) {
        for (String p : paths) {
            try {
                String s = Files.readString(Path.of(p)).trim();
                if (s.equals("max")) return null;
                return Long.parseLong(s);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
