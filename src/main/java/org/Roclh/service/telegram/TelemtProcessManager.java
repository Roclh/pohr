package org.Roclh.service.telegram;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.TelemtLogWebSocketHandler;
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
@RequiredArgsConstructor
public class TelemtProcessManager {

    private static final int LOG_BUFFER_SIZE = 500;
    private final TelemtInstaller installer;
    private final TelemtLogWebSocketHandler logHandler;
    private static final java.util.regex.Pattern ANSI_PATTERN =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    @Value("${xray.home}")
    private String xrayHome;

    @Getter
    private Process process;

    @Getter
    private Instant startedAt;
    private final Deque<String> logBuffer = new ArrayDeque<>();

    private Path pidFile() {
        return Path.of(xrayHome, "telemt.pid");
    }

    /** На старте приложения — вычищаем любой висящий telemt (PID-файл + по имени). */
    @PostConstruct
    public synchronized void recoverStaleProcess() {
        Path pf = pidFile();
        if (Files.exists(pf)) {
            try {
                long pid = Long.parseLong(Files.readString(pf).trim());
                killByPid(pid);
                Files.deleteIfExists(pf);
            } catch (Exception e) {
                log.warn("Failed to clean up stale telemt PID file: {}", e.getMessage());
            }
        }
        killAllByName("telemt");
    }

    public synchronized void start(String configPath, int listenPort) {
        if (isRunning()) {
            log.warn("telemt already running (pid={})", process.pid());
            return;
        }
        // Чистим сирот перед запуском — иначе на :3128 будет конфликт портов.
        killAllByName("telemt");

        if (!installer.isInstalled()) {
            throw new IllegalStateException("telemt binary not installed");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    installer.binary().toString(), configPath);
            pb.redirectErrorStream(true);
            pb.environment().put("RUST_LOG", "info");
            process = pb.start();
            startedAt = Instant.now();
            logBuffer.clear();

            try {
                Files.writeString(pidFile(), String.valueOf(process.pid()));
            } catch (IOException e) {
                log.warn("Failed to write telemt PID file: {}", e.getMessage());
            }

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendLog(line);
                        log.info("[telemt] {}", line);
                    }
                } catch (IOException ignored) {}
            }, "telemt-log-reader");
            reader.setDaemon(true);
            reader.start();

            log.info("telemt started (pid={}, port={})", process.pid(), listenPort);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start telemt", e);
        }
    }

    /**
     * Гарантированная остановка. Гасит текущий handle (SIGTERM → SIGKILL),
     * затем подчищает все процессы с именем бинарника telemt в системе.
     * Даже если handle потерян (краш приложения, падение транзакции),
     * процесс будет убит по имени.
     */
    public synchronized void stop() {
        boolean wasRunning = isRunning();
        Long pid = wasRunning ? process.pid() : null;

        if (wasRunning) {
            process.destroy();
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    log.warn("telemt (pid={}) did not stop in 8s, forcing", pid);
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }

        // Финальная зачистка: убиваем любые процессы telemt, даже если наш handle потерян.
        killAllByName("telemt");

        try {
            Files.deleteIfExists(pidFile());
        } catch (IOException e) {
            log.warn("Failed to delete telemt PID file: {}", e.getMessage());
        }

        log.info("telemt stopped (was running: {})", wasRunning);
    }

    public synchronized boolean restart(String configPath, int listenPort) {
        stop();
        start(configPath, listenPort);
        return isRunning();
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

    private void appendLog(String raw) {
        String stripped = ANSI_PATTERN.matcher(raw).replaceAll("");
        for (String piece : stripped.split("\\\\[nr]")) {
            String line = piece.strip();
            if (line.isEmpty()) continue;
            synchronized (this) {
                if (logBuffer.size() >= LOG_BUFFER_SIZE) logBuffer.pollFirst();
                logBuffer.addLast(line);
            }
            logHandler.broadcast(line);
        }
    }

    // --- Helpers --------------------------------------------------------------

    private void killByPid(long pid) {
        ProcessHandle.of(pid).ifPresentOrElse(h -> {
            if (h.isAlive()) {
                log.warn("Killing stale telemt process (pid={})", pid);
                h.destroy();
                try {
                    h.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    h.destroyForcibly();
                    try { h.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            }
        }, () -> log.debug("Stale telemt PID {} no longer exists", pid));
    }

    /** Убивает все процессы с basename, равным {@code name}. */
    private void killAllByName(String name) {
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(ph -> ph.pid() != self)
                .filter(ph -> ph.info().command()
                        .map(cmd -> {
                            try {
                                return Path.of(cmd).getFileName().toString().equals(name);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .orElse(false))
                .forEach(ph -> {
                    log.warn("Killing orphan telemt process (pid={}, cmd={})",
                            ph.pid(), ph.info().command().orElse("?"));
                    ph.destroy();
                    try {
                        ph.onExit().get(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        ph.destroyForcibly();
                    }
                });
    }

    @PreDestroy
    public void onShutdown() {
        stop();
    }
}