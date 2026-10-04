package org.Roclh.service.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.TelegramProxyUser;
import org.Roclh.repository.telegram.TelegramProxyUserRepository;
import org.Roclh.service.event.UserCreatedEvent;
import org.Roclh.service.event.UserRenamedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramProxyUserInitializer {

    private final TelegramProxyUserRepository repo;

    @EventListener
    @Transactional
    public void onUserCreated(UserCreatedEvent e) {
        if (repo.existsById(e.userId())) return;
        repo.save(TelegramProxyUser.builder()
                .userId(e.userId())
                .label(e.username())
                .enabled(true)
                .build());
        log.info("Created TG proxy record for user '{}'", e.username());
    }

    @EventListener
    @Transactional
    public void onUserRenamed(UserRenamedEvent e) {
        repo.findById(e.userId()).ifPresent(u -> {
            u.setLabel(e.newUsername());
            repo.save(u);
        });
    }
}