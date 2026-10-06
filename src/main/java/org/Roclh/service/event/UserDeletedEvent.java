package org.Roclh.service.event;

import java.util.UUID;

public record UserDeletedEvent(UUID userId) {
}