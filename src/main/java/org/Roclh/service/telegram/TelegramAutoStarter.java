package org.Roclh.service.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.TelegramProxyConfig;
import org.Roclh.repository.telegram.TelegramProxyConfigRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Автозапуск telemt при старте приложения, если config.enabled = true.
 * Срабатывает после всех ApplicationRunner'ов (XrayAutoInstaller уже поднял Xray
 * с telemt-socks inbound). Xray НЕ рестартится.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAutoStarter {

    private final TelegramProxyService telegramProxyService;
    private final TelegramProxyConfigRepository configRepo;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        TelegramProxyConfig c = configRepo.findById(TelegramProxyService.DEFAULT_CONFIG).orElse(null);
        if (c == null || !c.isEnabled()) {
            log.info("Telegram proxy disabled — telemt not started");
            return;
        }
        if (telegramProxyService.isRunning()) {
            log.info("telemt already running");
            return;
        }
        try {
            log.info("Auto-starting telemt (config.enabled=true)");
            telegramProxyService.startInternal();
        } catch (Exception e) {
            log.error("Auto-start of telemt failed: {}", e.getMessage(), e);
        }
    }
}