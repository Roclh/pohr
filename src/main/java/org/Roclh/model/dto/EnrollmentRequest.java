package org.Roclh.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EnrollmentRequest(
        @NotBlank String token,
        @NotBlank String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank String publicKey,
        @NotBlank String shortId,
        String xrayVersion,
        String agentVersion
) {
}