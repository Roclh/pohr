package org.Roclh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.InviteToken;
import org.Roclh.repository.InviteTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InviteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteTokenRepository repository;

    public List<InviteToken> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<InviteToken> findByToken(String token) {
        return repository.findById(token);
    }

    public boolean isUsable(String token) {
        return repository.findById(token).map(InviteToken::isUsable).orElse(false);
    }

    @Transactional
    public InviteToken create(UUID createdBy, String role, Duration ttl) {
        InviteToken inv = InviteToken.builder()
                .token(generateToken())
                .role(role)
                .createdBy(createdBy)
                .expiresAt(Instant.now().plus(ttl))
                .build();
        InviteToken saved = repository.save(inv);
        log.info("Created invite token (role={}, expires={})", role, saved.getExpiresAt());
        return saved;
    }

    @Transactional
    public InviteToken consume(String token, UUID userId) {
        InviteToken inv = repository.findById(token)
                .orElseThrow(() -> new IllegalArgumentException("Invite not found"));
        if (inv.getUsedAt() != null) {
            throw new IllegalArgumentException("Invite already used");
        }
        if (inv.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Invite expired");
        }
        inv.setUsedAt(Instant.now());
        inv.setUsedBy(userId);
        return repository.save(inv);
    }

    @Transactional
    public void delete(String token) {
        repository.deleteById(token);
    }

    @Scheduled(fixedDelayString = "${pohr.invites.cleanup-interval:600000}")
    @Transactional
    public void cleanupExpired() {
        List<InviteToken> expired = repository.findByExpiresAtBeforeAndUsedAtIsNull(Instant.now());
        if (!expired.isEmpty()) {
            repository.deleteAll(expired);
            log.info("Cleaned up {} expired invite tokens", expired.size());
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}