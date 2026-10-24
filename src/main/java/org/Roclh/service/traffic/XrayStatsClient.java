package org.Roclh.service.traffic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Клиент локального Xray API (gRPC через CLI-обёртку {@code xray api}).
 *
 * <p>Почему CLI, а не gRPC-клиент: полноценный grpc-java + protobuf съел бы
 * десятки мегабайт native-образа и потребовал бы reflection-конфиг, а нам нужно
 * два вызова в минуту. Формат {@code statsquery} стабилен и парсится как JSON.</p>
 *
 * <p>Для мультисерверного моста этот класс остаётся локальным: каждая RU-нода
 * опрашивает свой Xray, а менеджер будет принимать уже готовые дельты по node_id.</p>
 */
@Slf4j
@Component
public class XrayStatsClient {

    private static final int TIMEOUT_SECONDS = 10;

    private final String binary;
    private final int apiPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public XrayStatsClient(@Value("${xray.home}") String xrayHome,
                           @Value("${pohr.xray.api-port:10085}") int apiPort) {
        String binName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "xray.exe" : "xray";
        this.binary = Path.of(xrayHome, "bin", binName).toString();
        this.apiPort = apiPort;
    }

    /**
     * Сырые счётчики по всем клиентам.
     *
     * @return map email → счётчики; пустая map, если трафика ещё нет;
     *         {@code null}, если API недоступен (Xray не запущен/старая конфигурация).
     */
    public Map<String, Counters> queryUserTraffic() {
        if (!Files.exists(Path.of(binary))) {
            log.debug("Xray binary not found at {}, stats skipped", binary);
            return null;
        }
        String output = exec(List.of(
                binary, "api", "statsquery",
                "--server=127.0.0.1:" + apiPort,
                "-pattern", "user>>>"));
        if (output == null) {
            return null;
        }
        Map<String, long[]> acc = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(output);
            for (JsonNode stat : root.path("stat")) {
                String name = stat.path("name").asText("");
                String[] parts = name.split(">>>");
                if (parts.length < 4 || !"user".equals(parts[0]) || !"traffic".equals(parts[2])) {
                    continue;
                }
                long value = parseValue(stat.get("value"));
                long[] pair = acc.computeIfAbsent(parts[1], k -> new long[2]);
                if ("uplink".equals(parts[3])) {
                    pair[0] += value;
                } else if ("downlink".equals(parts[3])) {
                    pair[1] += value;
                }
            }
        } catch (Exception e) {
            log.warn("Cannot parse xray statsquery output: {}", e.getMessage());
            return null;
        }
        Map<String, Counters> result = new HashMap<>();
        acc.forEach((email, pair) -> result.put(email, new Counters(pair[0], pair[1])));
        return result;
    }

    private static long parseValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (node.isNumber()) {
            return node.asLong(0L);
        }
        try {
            return Long.parseLong(node.asText("0").trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String exec(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // CLI — короткоживущий Go-процесс внутри того же cgroup, что и Xray.
            // На 480 МБ лимите всплеск heap не должен двигать общий счётчик.
            pb.environment().put("GOMEMLIMIT", "32MiB");
            pb.environment().put("GOGC", "50");
            process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("xray api timed out after {}s", TIMEOUT_SECONDS);
                return null;
            }
            if (process.exitValue() != 0) {
                log.debug("xray api exited with {}: {}", process.exitValue(),
                        output.length() > 300 ? output.substring(0, 300) : output);
                return null;
            }
            return output;
        } catch (Exception e) {
            log.debug("xray api call failed: {}", e.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    public record Counters(long uplink, long downlink) {
    }
}
