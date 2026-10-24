package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Проверка произвольного Xray-конфига через {@code xray run -test}.
 *
 * <p>Используется MCP-инструментами перед сохранением/применением: агент
 * получает ошибку конфигурации, не трогая работающий Xray. Временный файл
 * пишется рядом с конфигом, чтобы Xray видел те же geo-ассеты.</p>
 */
@Slf4j
@Service
public class XrayConfigValidator {

    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_OUTPUT = 8000;

    private final String binary;
    private final Path assetDir;

    public XrayConfigValidator(@Value("${xray.home}") String xrayHome) {
        String binName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "xray.exe" : "xray";
        this.binary = Path.of(xrayHome, "bin", binName).toString();
        this.assetDir = Path.of(xrayHome, "config");
    }

    public Result validate(String json) {
        if (json == null || json.isBlank()) {
            return new Result(false, -1, "Конфиг пустой");
        }
        Path tmp = null;
        try {
            Files.createDirectories(assetDir);
            tmp = Files.createTempFile(assetDir, ".mcp-xray-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(binary, "run", "-test", "-c", tmp.toString());
            pb.redirectErrorStream(true);
            pb.environment().put("XRAY_LOCATION_ASSET", assetDir.toString());
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, -1, "xray -test не завершился за " + TIMEOUT_SECONDS + " с");
            }
            return new Result(process.exitValue() == 0, process.exitValue(), truncate(output));
        } catch (Exception e) {
            log.warn("Xray config validation failed: {}", e.getMessage());
            return new Result(false, -1, "Не удалось запустить xray -test: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public String binary() {
        return binary;
    }

    public List<String> suggestedCommands() {
        return List.of(binary, "run", "-test", "-c", assetDir.resolve("config.json").toString());
    }

    private static String truncate(String output) {
        if (output == null) return "";
        return output.length() <= MAX_OUTPUT ? output : output.substring(0, MAX_OUTPUT) + "\n...[truncated]";
    }

    public record Result(boolean ok, int exitCode, String output) {
    }
}
