package org.Roclh.model.dto;

import jakarta.validation.constraints.NotBlank;

public record NodeHealthRequest(
        @NotBlank String status,
        String xrayVersion,
        String configHash,
        Long uptime
) {
}