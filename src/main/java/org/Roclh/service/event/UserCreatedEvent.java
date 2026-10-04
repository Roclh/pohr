package org.Roclh.service.event;

import java.util.UUID;

public record UserCreatedEvent(UUID userId, String username) {}
