package org.Roclh.service.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EnrollmentToken;
import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.Roclh.repository.EnrollmentTokenRepository;
import org.Roclh.repository.EuNodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
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
public class EuNodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EuNodeRepository nodeRepository;
    private final EnrollmentTokenRepository tokenRepository;

    @Value("${pohr.eu-nodes.enroll-token-ttl:1h}")
    private Duration enrollTokenTtl;

    // --- Чтение ---------------------------------------------------------------

    public List<EuNode> findAll() {
        return nodeRepository.findAll(Sort.by(Sort.Direction.DESC, "enrolledAt"));
    }

    public Optional<EuNode> findById(UUID id) {
        return nodeRepository.findById(id);
    }

    public Optional<EuNode> findByName(String name) {
        return nodeRepository.findByName(name);
    }

    public boolean nameTaken(String name) {
        return nodeRepository.existsByName(name);
    }

    /**
     * Все ноды — кандидаты для EU-outbound. Даже UNREACHABLE берём в конфиг:
     * иначе получаем замкнутый круг «Xray упал → нет outbound → не поднялся».
     * Healthcheck потом переведёт в HEALTHY, если туннель заработает.
     */
    public List<EuNode> findCandidates() {
        return nodeRepository.findAll(Sort.by(Sort.Direction.DESC, "enrolledAt"));
    }

    public Optional<EuNode> findByNodeSecret(UUID id, String secret) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return nodeRepository.findById(id)
                .filter(n -> secret.equals(n.getNodeSecret()));
    }

    // --- Enrollment -----------------------------------------------------------

    public boolean isTokenUsable(String tokenValue) {
        return tokenRepository.findById(tokenValue)
                .map(EnrollmentToken::isUsable)
                .orElse(false);
    }

    @Transactional
    public void rotateTunnel(UUID id) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        node.setTunnelUuid(UUID.randomUUID());
        nodeRepository.save(node);
        log.info("Rotated tunnel UUID for node '{}'", node.getName());
    }

    @Transactional
    public String regenerateSecret(UUID id) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        String newSecret = generateSecret();
        node.setNodeSecret(newSecret);
        nodeRepository.save(node);
        log.info("Rotated nodeSecret for node '{}'", node.getName());
        return newSecret;
    }

    public Optional<EnrollmentToken> findToken(String token) {
        return tokenRepository.findById(token);
    }

    @Transactional
    public EnrollmentToken createEnrollmentToken(String nodeName, UUID createdBy, Duration ttlOverride) {
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("Node name is required");
        }
        Duration ttl = ttlOverride != null ? ttlOverride : enrollTokenTtl;
        EnrollmentToken token = EnrollmentToken.builder()
                .token(generateToken())
                .nodeName(nodeName)
                .createdBy(createdBy)
                .expiresAt(Instant.now().plus(ttl))
                .build();
        EnrollmentToken saved = tokenRepository.save(token);
        log.info("Created enrollment token for node '{}' (expires {})", nodeName, saved.getExpiresAt());
        return saved;
    }

    /** Проверяет токен и помечает как использованный. */
    @Transactional
    public EnrollmentToken consumeToken(String tokenValue) {
        EnrollmentToken et = tokenRepository.findById(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment token not found"));
        if (et.getUsedAt() != null) {
            throw new IllegalArgumentException("Enrollment token already used");
        }
        if (et.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Enrollment token expired");
        }
        et.setUsedAt(Instant.now());
        return tokenRepository.save(et);
    }

    /**
     * Регистрация или перерегистрация ноды.
     * Если нода с таким именем уже существует — обновляем host/port/keys,
     * но tunnelUuid и nodeSecret сохраняем, чтобы не рвать существующий туннель.
     */
    @Transactional
    public EuNode registerOrUpdate(EnrollmentToken token,
                                   String host,
                                   int port,
                                   String publicKey,
                                   String shortId,
                                   String xrayVersion,
                                   String agentVersion) {
        EuNode node = nodeRepository.findByName(token.getNodeName())
                .orElseGet(() -> {
                    log.info("Registering new EU node '{}'", token.getNodeName());
                    return EuNode.builder()
                            .name(token.getNodeName())
                            .tunnelUuid(UUID.randomUUID())
                            .nodeSecret(generateSecret())
                            .enrolledBy(token.getCreatedBy())
                            .build();
                });

        node.setHost(host);
        node.setPort(port);
        node.setRealityPublicKey(publicKey);
        node.setRealityShortId(shortId);
        node.setXrayVersion(xrayVersion);
        node.setAgentVersion(agentVersion);
        node.setStatus(NodeStatus.PENDING);

        EuNode saved = nodeRepository.save(node);
        log.info("EU node '{}' registered (id={}, host={}:{})",
                saved.getName(), saved.getId(), saved.getHost(), saved.getPort());
        return saved;
    }

    // --- Обновления состояния -------------------------------------------------

    @Transactional
    public void updateHealth(UUID id, NodeStatus status, String message,
                             String xrayVersion, String configHash) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        node.setStatus(status);
        node.setLastHealthAt(Instant.now());
        node.setLastHealthMsg(message);
        if (xrayVersion != null) node.setXrayVersion(xrayVersion);
        if (configHash != null) node.setConfigHash(configHash);
        nodeRepository.save(node);
    }

    @Transactional
    public void updateTunnelCheck(UUID id, NodeStatus status, String observedIp) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        node.setStatus(status);
        node.setLastTunnelCheckAt(Instant.now());
        node.setLastTunnelIp(observedIp);
        nodeRepository.save(node);
    }

    @Transactional
    public void updateConfigHash(UUID id, String configHash) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        node.setConfigHash(configHash);
        nodeRepository.save(node);
    }

    // --- Удаление -------------------------------------------------------------

    @Transactional
    public void delete(UUID id) {
        EuNode node = nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + id));
        tokenRepository.deleteAll(tokenRepository.findByNodeNameOrderByCreatedAtDesc(node.getName()));
        nodeRepository.delete(node);
        log.info("Deleted EU node '{}'", node.getName());
    }

    // --- Периодическая очистка ------------------------------------------------

    @Scheduled(fixedDelayString = "${pohr.eu-nodes.cleanup-interval:600000}")
    @Transactional
    public void cleanupExpiredTokens() {
        List<EnrollmentToken> expired = tokenRepository
                .findByExpiresAtBeforeAndUsedAtIsNull(Instant.now());
        if (!expired.isEmpty()) {
            tokenRepository.deleteAll(expired);
            log.info("Cleaned up {} expired enrollment tokens", expired.size());
        }
    }

    // --- Helpers --------------------------------------------------------------

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}