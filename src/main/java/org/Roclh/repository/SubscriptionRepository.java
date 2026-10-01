package org.Roclh.repository;

import org.Roclh.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByToken(String token);
    Optional<Subscription> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}