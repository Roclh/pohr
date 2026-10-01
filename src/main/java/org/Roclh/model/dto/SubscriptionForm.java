package org.Roclh.model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubscriptionForm(
        @NotNull(message = "{validation.subscription.user.required}")
        UUID userId,
        boolean enabled
) {
}