package org.Roclh.model.dto;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionDto(
        UUID id,
        UUID userId,
        String username,
        String token,
        String url,
        boolean enabled,
        Instant createdAt
) {
}