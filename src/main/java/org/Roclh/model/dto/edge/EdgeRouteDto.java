package org.Roclh.model.dto.edge;

import org.Roclh.model.edge.EdgeRoute;

import java.time.Instant;
import java.util.UUID;

public record EdgeRouteDto(
        UUID id,
        String sni,
        String targetHost,
        int targetPort,
        String protocol,
        String description,
        boolean enabled,
        int sortOrder,
        Instant updatedAt
) {
    public static EdgeRouteDto from(EdgeRoute r) {
        return new EdgeRouteDto(
                r.getId(), r.getSni(), r.getTargetHost(), r.getTargetPort(),
                r.getProtocol(), r.getDescription(), r.isEnabled(),
                r.getSortOrder(), r.getUpdatedAt());
    }
}