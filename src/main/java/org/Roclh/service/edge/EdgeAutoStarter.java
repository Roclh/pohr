package org.Roclh.service.edge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeAutoStarter {

    private final EdgeService edgeService;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;

    @Value("${pohr.edge.auto-start:true}")
    private boolean autoStart;

    /**
     * Запускается после ApplicationReadyEvent, т.е. после XrayAutoInstaller
     * и TelegramAutoStarter. Nginx и Caddy поднимаются последними, потому что
     * их апстримы (Xray :8443, telemt :3128, Caddy :8444) должны быть уже слушать.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!autoStart) {
            log.info("Edge auto-start disabled");
            return;
        }
        if (!nginx.isInstalled() || !caddy.isInstalled()) {
            log.warn("Edge binaries not installed — skip auto-start. " +
                    "Install via /admin/edge or place binaries at ${pohr.edge.home}/edge/bin/");
            return;
        }
        try {
            edgeService.apply();
            log.info("Edge auto-started: nginx pid={}, caddy pid={}", nginx.pid(), caddy.pid());
        } catch (Exception e) {
            log.error("Edge auto-start failed: {}", e.getMessage(), e);
        }
    }
}