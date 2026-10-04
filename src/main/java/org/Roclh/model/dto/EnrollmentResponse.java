package org.Roclh.model.dto;

import java.util.UUID;

public record EnrollmentResponse(
        UUID nodeId,
        String nodeSecret,
        String config,
        String configHash,
        int pollIntervalSeconds,
        String xrayVersion
) {
}