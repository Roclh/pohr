package org.Roclh.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientConfigForm(
        boolean enabled,

        @NotBlank(message = "{validation.clientConfig.ua.required}")
        @Size(max = 128, message = "{validation.clientConfig.ua.size}")
        String userAgentPattern,

        @Size(max = 8192, message = "{validation.clientConfig.headers.size}")
        String headers
) {
}