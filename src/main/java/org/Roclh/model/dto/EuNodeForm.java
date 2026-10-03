package org.Roclh.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EuNodeForm(

        @NotBlank(message = "{validation.node.name.required}")
        @Size(min = 3, max = 128, message = "{validation.node.name.size}")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "{validation.node.name.pattern}")
        String name,

        @NotNull(message = "{validation.node.ttl.required}")
        @Min(value = 5, message = "{validation.node.ttl.range}")
        @Max(value = 1440, message = "{validation.node.ttl.range}")
        Integer ttlMinutes
) {
}