package org.Roclh.repository;

import org.Roclh.model.InviteToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface InviteTokenRepository extends JpaRepository<InviteToken, String> {
    List<InviteToken> findAllByOrderByCreatedAtDesc();
    List<InviteToken> findByExpiresAtBeforeAndUsedAtIsNull(Instant threshold);
}