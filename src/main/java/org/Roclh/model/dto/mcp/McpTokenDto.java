package org.Roclh.model.dto.mcp;

import java.util.UUID;

/**
 * Строка таблицы токенов на /admin/mcp. Строки вместо Instant — чтобы
 * Thymeleaf и GraalVM native не зависели от рефлексии java.time.
 */
public record McpTokenDto(
        UUID id,
        String name,
        String prefix,
        String createdAt,
        String lastUsedAt,
        boolean revoked
) {
}
