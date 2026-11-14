package org.Roclh.service.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Снимок процессов из {@code /proc} без внешних утилит.
 *
 * <p>Нужен двум потребителям: reaper'у (найти процессы, которых не должно быть)
 * и диагностике MCP (показать, кто именно занимает память, не заходя на сервер
 * по ssh). Всё, что не читается, считается нулём — на не-Linux системах таблица
 * просто пустая.</p>
 *
 * <p>Важно про видимость: приложение в контейнере видит только свой PID-namespace.
 * Процессы, запущенные на хосте мимо контейнера, сюда не попадают — для них
 * нужен отдельный доступ к хосту.</p>
 */
public final class ProcessTable {

    /** Скольких тиков в секунду считает ядро в поле starttime; на Linux это USER_HZ = 100. */
    private static final double CLOCK_TICKS = 100.0;

    private static final Path PROC = Path.of("/proc");

    private ProcessTable() {
    }

    /**
     * @param anonBytes  private_dirty/Anonymous из smaps_rollup — реально занятая память
     * @param thpBytes   AnonHugePages: сколько из anon лежит в huge-страницах
     * @param ageSeconds сколько процесс живёт; отрицательное, если не определить
     */
    public record Proc(long pid, String name, String cmdLine, long rssBytes, long anonBytes,
                       long thpBytes, int threads, long ageSeconds) {

        public double rssMb() {
            return rssBytes / 1048576.0;
        }

        public double anonMb() {
            return anonBytes / 1048576.0;
        }

        public double thpMb() {
            return thpBytes / 1048576.0;
        }
    }

    /** Все процессы namespace'а. При {@code withMemory=false} smaps_rollup не читается. */
    public static List<Proc> snapshot(boolean withMemory) {
        List<Proc> result = new ArrayList<>();
        if (!Files.isDirectory(PROC)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PROC)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (!isNumeric(fileName)) continue;
                long pid;
                try {
                    pid = Long.parseLong(fileName);
                } catch (NumberFormatException e) {
                    continue;
                }
                Proc proc = read(pid, withMemory);
                if (proc != null) result.add(proc);
            }
        } catch (IOException ignored) {
            // /proc недоступен — например, запуск не на Linux
        }
        return result;
    }

    public static Optional<Proc> find(long pid) {
        return Optional.ofNullable(read(pid, true));
    }

    private static Proc read(long pid, boolean withMemory) {
        Path dir = PROC.resolve(Long.toString(pid));
        String name = readString(dir.resolve("comm"));
        if (name == null) {
            // процесс уже завершился или чужая песочница
            return null;
        }
        String cmdLine = readCmdLine(dir.resolve("cmdline"));
        long rss = 0, threads = 0;
        String status = readString(dir.resolve("status"));
        if (status != null) {
            for (String line : status.split("\\R")) {
                if (line.startsWith("VmRSS:")) rss = parseKb(line);
                if (line.startsWith("Threads:")) threads = parseKb(line);
            }
        }
        long anon = 0, thp = 0;
        if (withMemory) {
            String rollup = readString(dir.resolve("smaps_rollup"));
            if (rollup != null) {
                for (String line : rollup.split("\\R")) {
                    if (line.startsWith("Anonymous:")) anon = parseKb(line);
                    if (line.startsWith("AnonHugePages:")) thp = parseKb(line);
                }
            }
        }
        return new Proc(pid, name, cmdLine, rss, anon, thp, (int) threads, ageSeconds(pid, dir));
    }

    private static long ageSeconds(long pid, Path dir) {
        String stat = readString(dir.resolve("stat"));
        String uptime = readString(PROC.resolve("uptime"));
        if (stat == null || uptime == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0) return -1;
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        // после comm идёт state (fields[0]), starttime — 22-е поле stat, то есть fields[19]
        if (fields.length < 20) return -1;
        try {
            long startTicks = Long.parseLong(fields[19]);
            double upSeconds = Double.parseDouble(uptime.trim().split("\\s+")[0]);
            return Math.max(0, Math.round(upSeconds - startTicks / CLOCK_TICKS));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseKb(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return 0;
        try {
            return Long.parseLong(parts[1]) * 1024L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readCmdLine(Path path) {
        try {
            byte[] raw = Files.readAllBytes(path);
            String text = new String(raw, StandardCharsets.UTF_8).replace('\0', ' ').trim();
            return text.length() > 240 ? text.substring(0, 240) : text;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
