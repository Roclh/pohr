package org.Roclh.service.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.TelegramProxyUser;
import org.Roclh.repository.telegram.TelegramProxyUserRepository;
import org.Roclh.service.event.UserCreatedEvent;
import org.Roclh.service.event.UserDeletedEvent;
import org.Roclh.service.event.UserRenamedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramProxyUserInitializer {

    private final TelegramProxyUserRepository repo;
    private final TelegramProxyService telegramProxyService;

    // --- Создание ----------------------------------------------------------

    @EventListener
    @Transactional
    public void onUserCreated(UserCreatedEvent e) {
        if (repo.existsById(e.userId())) return;

        String secret = telegramProxyService.generateSecret();

        repo.save(TelegramProxyUser.builder()
                .userId(e.userId())
                .label(e.username())
                .secret(secret)
                .enabled(true)
                .build());
        log.info("Created TG proxy record for user '{}' (secret generated)", e.username());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUserCreated(UserCreatedEvent e) {
        reloadQuietly("created", e.username());
    }

    // --- Переименование ----------------------------------------------------

    @EventListener
    @Transactional
    public void onUserRenamed(UserRenamedEvent e) {
        repo.findById(e.userId()).ifPresent(u -> {
            u.setLabel(e.newUsername());
            repo.save(u);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUserRenamed(UserRenamedEvent e) {
        reloadQuietly("renamed", e.newUsername());
    }

    // --- Удаление ----------------------------------------------------------

    @EventListener
    @Transactional
    public void onUserDeleted(UserDeletedEvent e) {
        repo.deleteById(e.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUserDeleted(UserDeletedEvent e) {
        reloadQuietly("deleted", e.userId().toString());
    }

    // --- Helper ------------------------------------------------------------

    private void reloadQuietly(String action, String who) {
        try {
            telegramProxyService.reloadUsers();
            log.info("telemt reloaded after user {} ({})", action, who);
        } catch (Exception ex) {
            log.warn("Failed to reload telemt after user {} ({}): {}", action, who, ex.getMessage());
        }
    }
}