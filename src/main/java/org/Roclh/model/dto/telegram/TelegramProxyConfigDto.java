package org.Roclh.model.dto.telegram;

import java.time.Instant;

public record TelegramProxyConfigDto(
        boolean enabled,
        int listenPort,
        String coverDomain,
        boolean webEnabled,
        boolean running,
        String version,
        int userCount,
        Long pid,
        Instant startedAt
) {}