package org.Roclh.service.edge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.dto.edge.EdgeConfigForm;
import org.Roclh.model.dto.edge.EdgeStatusDto;
import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.repository.edge.EdgeConfigRepository;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeService {

    private final EdgeConfigService cfgService;
    private final EdgeConfigRepository cfgRepo;
    private final EdgeRouteRepository routeRepo;
    private final EdgeSiteRepository siteRepo;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;

    public EdgeStatusDto status() {
        EdgeConfig c = cfgService.getConfig();
        return new EdgeStatusDto(
                nginx.isRunning(), nginx.pid(), nginx.getStartedAt(),
                caddy.isRunning(), caddy.pid(), caddy.getStartedAt(),
                nginx.isInstalled(), caddy.isInstalled(),
                c.getStreamPort(), c.getHttpPort(), c.getHttpsPort(),
                routeRepo.findAll().size(),
                siteRepo.findAll().size());
    }

    @Transactional
    public void updateConfig(EdgeConfigForm form) {
        EdgeConfig c = cfgService.getConfig();
        c.setStreamPort(form.streamPort());
        c.setHttpPort(form.httpPort());
        c.setHttpsPort(form.httpsPort());
        c.setFallbackTarget(form.fallbackTarget());
        c.setWorkerProcesses(form.workerProcesses());
        c.setWorkerConnections(form.workerConnections());
        c.setStreamProxyTimeout(form.streamProxyTimeout());
        c.setStreamConnectTimeout(form.streamConnectTimeout());
        c.setAutoHttps(form.autoHttps());
        c.setExtraStreamDirectives(blankToNull(form.extraStreamDirectives()));
        c.setExtraHttpDirectives(blankToNull(form.extraHttpDirectives()));
        c.setExtraGlobalDirectives(blankToNull(form.extraGlobalDirectives()));
        cfgRepo.save(c);
    }

    /**
     * Полный цикл: материализовать → validate → reload/start.
     * При ошибке валидации — ничего не применяется, состояние процессов не меняется.
     */
    public synchronized void apply() {
        cfgService.materialize();

        // 1. Проверяем конфиги
        try {
            nginx.validate();
        } catch (Exception e) {
            throw new IllegalStateException("nginx config invalid: " + e.getMessage(), e);
        }
        try {
            caddy.validate();
        } catch (Exception e) {
            throw new IllegalStateException("Caddyfile invalid: " + e.getMessage(), e);
        }

        // 2. Применяем nginx
        try {
            if (nginx.isRunning()) nginx.reload(); else nginx.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply nginx: " + e.getMessage(), e);
        }

        // 3. Применяем caddy
        try {
            if (caddy.isRunning()) caddy.reload(); else caddy.start();
        } catch (Exception e) {
            log.error("nginx applied but caddy failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to apply caddy: " + e.getMessage(), e);
        }

        log.info("Edge applied: nginx pid={}, caddy pid={}", nginx.pid(), caddy.pid());
    }

    public void startNginx()   { nginx.start(); }
    public void stopNginx()    { nginx.stop(); }
    public void restartNginx() { nginx.restart(); }
    public void startCaddy()   { caddy.start(); }
    public void stopCaddy()    { caddy.stop(); }
    public void restartCaddy() { caddy.restart(); }

    /** При переезде: почистить состояние, если конфиги есть, а бинарников нет. */
    @Transactional
    public void resetRuntimeState() {
        try {
            Path edgeDir = Path.of(cfgService.nginxConfigPath().getParent().toString());
            if (Files.exists(edgeDir)) {
                try (var s = Files.walk(edgeDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".pid"))
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception e) {
            log.warn("resetRuntimeState: {}", e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}