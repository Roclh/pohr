package org.Roclh.service.telegram;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemtProcessManager {

    private final TelemtInstaller installer;

    @Value("${xray.home}")
    private String xrayHome;

    @Getter
    private Process process;

    @Getter
    private Instant startedAt;

    public synchronized void start(String configPath, int listenPort) {
        if (isRunning()) {
            log.warn("telemt already running (pid={})", process.pid());
            return;
        }
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

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
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

    public synchronized void stop() {
        if (!isRunning()) return;
        process.destroy();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
        log.info("telemt stopped");
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

    @PreDestroy
    public void onShutdown() { stop(); }
}