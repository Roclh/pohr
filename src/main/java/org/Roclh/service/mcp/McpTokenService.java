package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.User;
import org.Roclh.model.dto.mcp.McpTokenDto;
import org.Roclh.model.mcp.McpToken;
import org.Roclh.repository.UserRepository;
import org.Roclh.repository.mcp.McpTokenRepository;
import org.Roclh.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Выпуск и проверка MCP-токенов.
 *
 * <p>Токен — 32 случайных байта в base64url с префиксом {@code pohr_}.
 * В БД хранится только SHA-256; сравнение идёт по хэшу, поэтому утечка
 * таблицы не даёт доступа к /mcp.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "pohr_";
    private static final int TOKEN_BYTES = 32;
    private static final Duration LAST_USED_THROTTLE = Duration.ofSeconds(60);

    private final McpTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public record CreatedToken(McpToken token, String rawToken) {}

    public record Principal(UUID tokenId, UUID userId, String username) {}

    /**
     * Создаёт новый токен для администратора. Возвращает plaintext один раз.
     */
    @Transactional
    public CreatedToken create(String username, String name) {
        User user = userRepository.findByUsername(username)
                .filter(u -> u.isEnabled() && UserService.ROLE_ADMIN.equals(u.getRole()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Только активный администратор может создать MCP-токен"));

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String raw = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String normalizedName = (name == null || name.isBlank())
                ? "DeepSeek Harness"
                : name.trim();
        if (normalizedName.length() > 64) {
            normalizedName = normalizedName.substring(0, 64);
        }

        McpToken token = McpToken.builder()
                .userId(user.getId())
                .name(normalizedName)
                .tokenHash(sha256Hex(raw))
                .tokenPrefix(raw.substring(0, Math.min(12, raw.length())))
                .build();
        McpToken saved = tokenRepository.save(token);
        log.info("Issued MCP token '{}' ({}) for user '{}'", normalizedName, saved.getTokenPrefix(), username);
        return new CreatedToken(saved, raw);
    }

    /**
     * Проверяет Bearer-токен. При успехе (не чаще раза в минуту) обновляет last_used_at.
     * Возвращает пустой Optional для неизвестного, отозванного токена, выключенного
     * пользователя или не-админа.
     */
    @Transactional
    public Optional<Principal> authenticate(String rawToken) {
        Optional<McpToken> found = findActive(rawToken);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        McpToken token = found.get();
        User user = userRepository.findById(token.getUserId()).orElse(null);
        if (user == null || !user.isEnabled() || !UserService.ROLE_ADMIN.equals(user.getRole())) {
            log.warn("MCP token {} belongs to a missing/disabled/non-admin user", token.getTokenPrefix());
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (token.getLastUsedAt() == null
                || token.getLastUsedAt().isBefore(now.minus(LAST_USED_THROTTLE))) {
            token.setLastUsedAt(now);
            tokenRepository.save(token);
        }
        return Optional.of(new Principal(token.getId(), user.getId(), user.getUsername()));
    }

    /** Проверка токена без отметки last_used_at — для выдачи установщика. */
    @Transactional(readOnly = true)
    public boolean matchesUser(String rawToken, String username) {
        return findActive(rawToken)
                .flatMap(t -> userRepository.findById(t.getUserId()))
                .map(u -> u.isEnabled()
                        && UserService.ROLE_ADMIN.equals(u.getRole())
                        && u.getUsername().equals(username))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<McpTokenDto> listForUser(UUID userId) {
        return tokenRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new McpTokenDto(
                        t.getId(),
                        t.getName(),
                        t.getTokenPrefix() + "…",
                        t.getCreatedAt() == null ? null : t.getCreatedAt().toString(),
                        t.getLastUsedAt() == null ? null : t.getLastUsedAt().toString(),
                        !t.isActive()))
                .toList();
    }

    @Transactional
    public void revoke(UUID tokenId, UUID userId) {
        McpToken token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("Токен не найден"));
        if (!token.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Токен принадлежит другому пользователю");
        }
        if (token.isActive()) {
            token.setRevokedAt(Instant.now());
            tokenRepository.save(token);
            log.info("Revoked MCP token '{}' ({})", token.getName(), token.getTokenPrefix());
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Optional<McpToken> findActive(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(sha256Hex(rawToken))
                .filter(McpToken::isActive);
    }
}
