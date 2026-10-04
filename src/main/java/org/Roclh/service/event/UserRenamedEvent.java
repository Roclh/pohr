package org.Roclh.service.event;

import java.util.UUID;

public record UserRenamedEvent(UUID userId, String newUsername) {}
