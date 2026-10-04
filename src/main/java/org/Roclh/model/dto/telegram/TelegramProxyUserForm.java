package org.Roclh.model.dto.telegram;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TelegramProxyUserForm(
        @NotBlank(message = "{validation.tgUser.label.required}")
        @Size(min = 1, max = 64)
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "{validation.tgUser.label.pattern}")
        String label,

        boolean enabled
) {}