package org.Roclh.service;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.Subscription;
import org.Roclh.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository repository;

    public Subscription getOrCreate(UUID userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            Subscription s = Subscription.builder()
                    .userId(userId)
                    .token(generateToken())
                    .enabled(true)
                    .build();
            return repository.save(s);
        });
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

    public Subscription create(UUID userId, boolean enabled) {
        return repository.save(Subscription.builder()
                .userId(userId)
                .token(generateToken())
                .enabled(enabled)
                .build());
    }

    @Transactional
    public void toggle(UUID id) {
        Subscription s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        s.setEnabled(!s.isEnabled());
        repository.save(s);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    public boolean existsByUserId(UUID userId) {
        return repository.existsByUserId(userId);
    }

    public Optional<Subscription> findByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }
}
