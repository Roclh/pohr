package org.Roclh.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserDto(

        @NotBlank(message = "{validation.username.required}")
        @Size(min = 3, max = 64, message = "{validation.username.size}")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "{validation.username.pattern}")
        String username,

        @Size(max = 100, message = "{validation.password.size}")
        String password,

        @NotBlank(message = "{validation.role.required}")
        @Pattern(regexp = "^(ADMIN|USER)$", message = "{validation.role.pattern}")
        String role,

        boolean enabled
) {
}