package org.Roclh.service.xray;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.XrayLogWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class XrayProcessManager {
    private static final int LOG_BUFFER_SIZE = 500;

    private final XrayLogWebSocketHandler logHandler;
    private final XrayConfigMaterializer materializer;
    private final Path xrayBinary;
    private final Path configPath;
    private final Path pidFile;

    private Process process;
    private final Deque<String> logBuffer = new ArrayDeque<>();
    @Getter
    private Instant startedAt;
    @Getter
    private Instant stoppedAt;
    @Getter
    private Integer lastExitCode;

    public XrayProcessManager(@Value("${xray.home}") String home,
                              XrayLogWebSocketHandler logHandler,
                              XrayConfigMaterializer materializer) {
        Path homePath = Path.of(home);
        String binaryName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "xray.exe" : "xray";
        this.xrayBinary = homePath.resolve("bin").resolve(binaryName);
        this.configPath = homePath.resolve("config").resolve("config.json");
        this.pidFile = homePath.resolve("xray.pid");
        this.logHandler = logHandler;
        this.materializer = materializer;
    }

    /**
     * При старте приложения — если остался PID-файл от предыдущего инстанса,
     * проверяем: жив ли процесс. Живой — убиваем (мы единственный владелец),
     * мёртвый — просто чистим файл.
     */
    @PostConstruct
    public synchronized void recoverStaleProcess() {
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid).ifPresentOrElse(h -> {
                if (h.isAlive()) {
                    log.warn("Found stale Xray process (pid={}) from previous run, killing it", pid);
                    h.destroy();
                    try {
                        h.onExit().get(5, TimeUnit.SECONDS);
                        log.info("Stale Xray (pid={}) terminated gracefully", pid);
                    } catch (Exception e) {
                        log.warn("Stale Xray (pid={}) did not exit in 5s, forcing", pid);
                        h.destroyForcibly();
                        try {
                            h.onExit().get(3, TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                            log.error("Stale Xray (pid={}) refused to die; port conflict expected", pid);
                        }
                    }
                } else {
                    log.info("Stale PID file found (pid={}), process is dead, cleaning up", pid);
                }
            }, () -> log.info("Stale PID file found (pid={}), process no longer exists", pid));
            Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            log.warn("Failed to recover stale Xray process: {}", e.getMessage());
        }
    }

    public synchronized boolean start() {
        if (isRunning()) {
            log.warn("Xray is already running (pid={})", process.pid());
            return false;
        }
        if (!Files.exists(xrayBinary)) {
            throw new IllegalStateException("Xray binary not found: " + xrayBinary);
        }
        materializer.materialize();

        if (!Files.exists(configPath)) {
            throw new IllegalStateException("Xray config not found after materialization: " + configPath);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    xrayBinary.toString(), "run", "-c", configPath.toString());
            pb.redirectErrorStream(true);
            pb.environment().put("XRAY_LOCATION_ASSET", configPath.getParent().toString());
            process = pb.start();
            startedAt = Instant.now();
            lastExitCode = null;
            logBuffer.clear();

            try {
                Files.writeString(pidFile, String.valueOf(process.pid()));
            } catch (IOException e) {
                log.warn("Failed to write PID file {}: {}", pidFile, e.getMessage());
            }

            Thread logReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendLog(line);
                        log.debug("[xray] {}", line);
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
        if (!isRunning()) {
            try {
                Files.deleteIfExists(pidFile);
                return false;
            } catch (IOException e) {
                log.warn("Failed to delete PID file {}: {}", pidFile, e.getMessage());
            }
        }
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
        try {
            Files.deleteIfExists(pidFile);
        } catch (IOException e) {
            log.warn("Failed to delete PID file {}: {}", pidFile, e.getMessage());
        }
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

    public synchronized List<String> recentLogs(int limit) {
        return new ArrayList<>(logBuffer.stream()
                .skip(Math.max(0, logBuffer.size() - limit))
                .toList());
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
        log.info("Application shutting down, stopping Xray");
        stop();
    }
}