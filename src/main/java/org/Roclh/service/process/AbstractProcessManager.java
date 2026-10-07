package org.Roclh.service.process;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.AbstractLogWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Базовый класс для управления дочерними процессами (Xray, telemt, nginx, caddy).
 * Наследники переопределяют только:
 *  - startCommand()         — что запускать
 *  - startEnvironment()     — env vars (опционально)
 *  - prepareBeforeStart()   — хук перед стартом (например, materialize)
 *  - cleanupBeforeStart()   — хук до старта (например, убить orphans)
 *  - cleanupAfterStop()     — хук после остановки
 *  - validate()             — проверка конфига (опционально)
 *  - reloadCommand()        — команда graceful reload (опционально)
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractProcessManager<H extends AbstractLogWebSocketHandler> {

    private static final int LOG_BUFFER_SIZE = 500;

    protected final H logHandler;
    protected final Path binary;
    protected final Path configPath;
    protected final Path pidFile;

    protected Process process;
    protected final Deque<String> logBuffer = new ArrayDeque<>();

    @Getter protected Instant startedAt;
    @Getter protected Instant stoppedAt;
    @Getter protected Integer lastExitCode;

    // =====================================================================
    // Что переопределяют наследники
    // =====================================================================

    /** Имя для логов и сообщений. */
    protected abstract String serviceName();

    /** Полная команда запуска, включая argv[0] = binary. */
    protected abstract List<String> startCommand();

    /** Дополнительные переменные окружения для процесса. */
    protected Map<String, String> startEnvironment() {
        return Map.of();
    }

    /** Хук: выполняется до старта. Например, материализация конфига. */
    protected void prepareBeforeStart() throws Exception {}

    /** Хук: очистка до старта (например, kill orphans by name). */
    protected void cleanupBeforeStart() {}

    /** Хук: очистка после stop. Например, повторный kill по имени. */
    protected void cleanupAfterStop() {}

    /** Проверка конфига. По умолчанию — no-op. */
    public void validate() throws Exception {}

    /** Команда graceful reload. null = не поддерживается → fallback в restart(). */
    protected List<String> reloadCommand() {
        return null;
    }

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    @PostConstruct
    public synchronized void recoverStaleProcess() {
        if (!Files.exists(pidFile)) return;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid).ifPresentOrElse(h -> {
                if (h.isAlive()) {
                    log.warn("Found stale {} process (pid={}), killing it", serviceName(), pid);
                    h.destroy();
                    try {
                        h.onExit().get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        h.destroyForcibly();
                        try { h.onExit().get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    }
                }
            }, () -> log.info("Stale {} PID {} no longer exists", serviceName(), pid));
            Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            log.warn("Failed to recover stale {}: {}", serviceName(), e.getMessage());
        }
    }

    public synchronized boolean start() {
        if (isRunning()) {
            log.warn("{} already running (pid={})", serviceName(), process.pid());
            return false;
        }
        if (!Files.exists(binary)) {
            throw new IllegalStateException(serviceName() + " binary not found: " + binary);
        }
        cleanupBeforeStart();
        try {
            prepareBeforeStart();
            validate();
        } catch (Exception e) {
            throw new IllegalStateException("Refusing to start " + serviceName() + ": " + e.getMessage(), e);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(startCommand());
            pb.redirectErrorStream(true);
            pb.environment().putAll(startEnvironment());
            process = pb.start();
            startedAt = Instant.now();
            lastExitCode = null;
            logBuffer.clear();

            try {
                Files.writeString(pidFile, String.valueOf(process.pid()));
            } catch (IOException e) {
                log.warn("Failed to write {} PID file: {}", serviceName(), e.getMessage());
            }

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        appendLog(line);
                    }
                } catch (IOException e) {
                    log.debug("{} log stream closed", serviceName(), e);
                }
            }, serviceName() + "-log-reader");
            reader.setDaemon(true);
            reader.start();

            log.info("{} started (pid={})", serviceName(), process.pid());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start " + serviceName(), e);
        }
    }

    public synchronized boolean stop() {
        if (!isRunning()) {
            try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
            return false;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        lastExitCode = process.exitValue();
        stoppedAt = Instant.now();
        process = null;
        try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
        cleanupAfterStop();
        log.info("{} stopped (exit={})", serviceName(), lastExitCode);
        return true;
    }

    public synchronized boolean restart() {
        stop();
        return start();
    }

    /** Graceful reload через внешнюю команду. Fallback в restart при ошибке. */
    public synchronized boolean reload() {
        if (!isRunning()) return start();
        List<String> cmd = reloadCommand();
        if (cmd == null) return restart();
        try {
            validate();
        } catch (Exception e) {
            throw new IllegalStateException("Refusing to reload " + serviceName() + ": " + e.getMessage(), e);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                log.warn("{} reload failed (exit={}, out={}), falling back to restart",
                        serviceName(), p.exitValue(), out.trim());
                return restart();
            }
            log.info("{} reloaded", serviceName());
            return true;
        } catch (Exception e) {
            log.warn("{} reload errored ({}), falling back to restart", serviceName(), e.getMessage());
            return restart();
        }
    }

    // =====================================================================
    // Доступ и логи
    // =====================================================================

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public Long pid() {
        return isRunning() ? process.pid() : null;
    }

    public boolean isInstalled() {
        return Files.exists(binary);
    }

    public synchronized List<String> recentLogs(int limit) {
        return new ArrayList<>(logBuffer.stream()
                .skip(Math.max(0, logBuffer.size() - limit))
                .toList());
    }

    protected synchronized void appendLog(String line) {
        if (logBuffer.size() >= LOG_BUFFER_SIZE) logBuffer.pollFirst();
        logBuffer.addLast(line);
        logHandler.broadcast(line);
    }

    // =====================================================================
    // Хелперы для наследников
    // =====================================================================

    /** Убивает все процессы с basename, равным {@code name}. */
    protected void killAllByName(String name) {
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(ph -> ph.pid() != self)
                .filter(ph -> ph.info().command()
                        .map(cmd -> {
                            try { return Path.of(cmd).getFileName().toString().equals(name); }
                            catch (Exception e) { return false; }
                        })
                        .orElse(false))
                .forEach(ph -> {
                    log.warn("Killing orphan {} process (pid={})", serviceName(), ph.pid());
                    ph.destroy();
                    try { ph.onExit().get(3, TimeUnit.SECONDS); }
                    catch (Exception e) { ph.destroyForcibly(); }
                });
    }

    @PreDestroy
    public synchronized void onShutdown() {
        log.info("Shutting down, stopping {}", serviceName());
        stop();
    }
}