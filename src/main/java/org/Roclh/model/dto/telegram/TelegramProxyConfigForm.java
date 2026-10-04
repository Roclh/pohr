package org.Roclh.model.dto.telegram;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TelegramProxyConfigForm(
        @NotNull @Min(1) @Max(65535)
        Integer listenPort,

        @Size(max = 255)
        String coverDomain,

        boolean webEnabled
) {}