package org.Roclh.model.dto.telegram;

import java.util.UUID;

public record TelegramProxyUserDto(
        UUID userId,
        String label,
        String secret,
        boolean enabled,
        String proxyUrl,
        String webProxyUrl
) {}