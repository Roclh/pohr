package org.Roclh.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record XrayConfigForm(

        @NotBlank(message = "{validation.xray.config.name.required}")
        @Size(max = 128, message = "{validation.xray.config.name.size}")
        String name,

        @Size(max = 512, message = "{validation.xray.config.description.size}")
        String description,

        @NotBlank(message = "{validation.xray.config.content.required}")
        String content,

        @Size(max = 64, message = "{validation.xray.config.realityPublicKey.size}")
        String realityPublicKey
) {
}