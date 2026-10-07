package org.Roclh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.Subscription;
import org.Roclh.repository.SubscriptionRepository;
import org.Roclh.service.event.SubscriptionsChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository repository;
    private final ApplicationEventPublisher events;

    public Subscription getOrCreate(UUID userId) {
        Optional<Subscription> existing = repository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Subscription s = Subscription.builder()
                .userId(userId)
                .token(generateToken())
                .xrayUuid(UUID.randomUUID().toString())
                .enabled(true)
                .build();
        Subscription saved = repository.save(s);
        events.publishEvent(new SubscriptionsChangedEvent("subscription created for user " + userId));
        log.info("Created subscription {} for user {}", saved.getId(), userId);
        return saved;
    }

    public Subscription create(UUID userId, boolean enabled) {
        Subscription saved = repository.save(Subscription.builder()
                .userId(userId)
                .token(generateToken())
                .xrayUuid(UUID.randomUUID().toString())
                .enabled(enabled)
                .build());
        if (enabled) {
            events.publishEvent(new SubscriptionsChangedEvent("subscription created (enabled) for user " + userId));
        }
        log.info("Created subscription {} for user {} (enabled={})", saved.getId(), userId, enabled);
        return saved;
    }

    public List<Subscription> findAllEnabled() {
        return repository.findAllByEnabledTrue();
    }

    public Optional<Subscription> findByToken(String token) {
        return repository.findByToken(token).filter(Subscription::isEnabled);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public List<Subscription> findAll() {
        return repository.findAll();
    }

    @Transactional
    public void toggle(UUID id) {
        Subscription s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        s.setEnabled(!s.isEnabled());
        repository.save(s);
        events.publishEvent(new SubscriptionsChangedEvent(
                "subscription " + id + " toggled to " + s.isEnabled()));
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
        events.publishEvent(new SubscriptionsChangedEvent("subscription " + id + " deleted"));
    }

    public boolean existsByUserId(UUID userId) {
        return repository.existsByUserId(userId);
    }
}
