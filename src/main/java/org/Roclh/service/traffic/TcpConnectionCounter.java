package org.Roclh.service.traffic;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Приблизительный счётчик активных TCP-соединений на порту Xray.
 *
 * <p>Xray не отдаёт число живых соединений через StatsService, поэтому считаем
 * established-сокеты из /proc/net/tcp{,6}. Важно: xhttp мультиплексирует
 * логические потоки внутри одного TCP-соединения устройства, так что это
 * «соединения», а не «потоки» — для оценки загрузки ноды этого достаточно,
 * а точные per-flow метрики мы не выдумываем.</p>
 */
@Slf4j
public final class TcpConnectionCounter {

    private static final List<String> PROC_TABLES = List.of("/proc/net/tcp", "/proc/net/tcp6");
    private static final String ESTABLISHED = "01";

    private TcpConnectionCounter() {
    }

    /**
     * @return число established-соединений на порту или {@code null}, если
     *         /proc недоступен (не Linux / Windows dev-машина).
     */
    public static Integer countEstablished(int port) {
        String portHex = String.format(Locale.ROOT, ":%04X", port);
        boolean anyTable = false;
        int count = 0;
        for (String table : PROC_TABLES) {
            Path path = Path.of(table);
            if (!Files.isReadable(path)) {
                continue;
            }
            anyTable = true;
            try (Stream<String> lines = Files.lines(path)) {
                count += lines.skip(1)
                        .map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .filter(line -> {
                            String[] parts = line.split("\\s+");
                            return parts.length >= 4
                                    && parts[1].endsWith(portHex)
                                    && ESTABLISHED.equals(parts[3]);
                        })
                        .count();
            } catch (Exception e) {
                log.debug("Cannot read {}: {}", table, e.getMessage());
            }
        }
        return anyTable ? count : null;
    }
}
