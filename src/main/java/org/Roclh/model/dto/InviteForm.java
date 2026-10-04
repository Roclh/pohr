package org.Roclh.model.dto;

import jakarta.validation.constraints.*;

public record InviteForm(
        @NotBlank(message = "{validation.role.required}")
        @Pattern(regexp = "^(ADMIN|USER)$", message = "{validation.role.pattern}")
        String role,

        @NotNull(message = "{validation.invite.ttl.required}")
        @Min(value = 5, message = "{validation.invite.ttl.range}")
        @Max(value = 43200, message = "{validation.invite.ttl.range}")
        Integer ttlMinutes
) {
}