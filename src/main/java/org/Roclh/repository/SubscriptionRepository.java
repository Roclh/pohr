package org.Roclh.repository;

import org.Roclh.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByToken(String token);
    Optional<Subscription> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
    List<Subscription> findAllByEnabledTrue();
}