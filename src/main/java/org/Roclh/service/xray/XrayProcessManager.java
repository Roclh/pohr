package org.Roclh.service.xray;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.XrayLogWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class XrayProcessManager {
    private static final int LOG_BUFFER_SIZE = 500;
    private XrayLogWebSocketHandler logHandler;

    private final Path xrayBinary;
    private final Path configPath;

    private Process process;
    private final Deque<String> logBuffer = new ArrayDeque<>();
    @Getter private Instant startedAt;
    @Getter private Instant stoppedAt;
    @Getter private Integer lastExitCode;

    public XrayProcessManager(@Value("${xray.home}") String home, XrayLogWebSocketHandler logHandler) {
        Path homePath = Path.of(home);
        String binaryName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "xray.exe" : "xray";
        this.xrayBinary = homePath.resolve("bin").resolve(binaryName);
        this.configPath = homePath.resolve("config").resolve("config.json");
        this.logHandler = logHandler;
    }

    public synchronized boolean start() {
        if (isRunning()) {
            log.warn("Xray is already running (pid={})", process.pid());
            return false;
        }
        if (!Files.exists(xrayBinary)) {
            throw new IllegalStateException("Xray binary not found: " + xrayBinary);
        }
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("Xray config not found: " + configPath);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    xrayBinary.toString(), "run", "-c", configPath.toString());
            pb.redirectErrorStream(true);
            // Маскировка: переименовываем argv[0]
            pb.environment().put("XRAY_LOCATION_ASSET", configPath.getParent().toString());
            process = pb.start();
            startedAt = Instant.now();
            lastExitCode = null;
            logBuffer.clear();

            // Асинхронное чтение логов
            Thread logReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendLog(line);
                        log.info("[xray] {}", line);
                    }
                } catch (IOException e) {
                    log.debug("Xray log stream closed", e);
                }
            }, "xray-log-reader");
            logReader.setDaemon(true);
            logReader.start();

            log.info("Xray started (pid={})", process.pid());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Xray", e);
        }
    }

    public synchronized boolean stop() {
        if (!isRunning()) return false;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        lastExitCode = process.exitValue();
        stoppedAt = Instant.now();
        process = null;
        log.info("Xray stopped (exit={})", lastExitCode);
        return true;
    }

    public synchronized boolean restart() {
        stop();
        return start();
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public Long pid() {
        return isRunning() ? process.pid() : null;
    }

    public synchronized java.util.List<String> recentLogs(int limit) {
        return logBuffer.stream().skip(Math.max(0, logBuffer.size() - limit)).toList();
    }
    private synchronized void appendLog(String line) {
        if (logBuffer.size() >= LOG_BUFFER_SIZE) {
            logBuffer.pollFirst();
        }
        logBuffer.addLast(line);
        logHandler.broadcast(line);
    }

    @PreDestroy
    public synchronized void onShutdown() {
        stop();
    }
}