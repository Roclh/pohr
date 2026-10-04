package org.Roclh.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InviteAcceptForm(
        @NotBlank(message = "{validation.username.required}")
        @Size(min = 3, max = 64, message = "{validation.username.size}")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "{validation.username.pattern}")
        String username,

        @NotBlank(message = "{password.required}")
        @Size(min = 8, max = 100, message = "{validation.password.minLength}")
        String password
) {
}