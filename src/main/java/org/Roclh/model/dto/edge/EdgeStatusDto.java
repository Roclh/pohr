package org.Roclh.model.dto.edge;

import java.time.Instant;

public record EdgeStatusDto(
        boolean nginxRunning,
        Long nginxPid,
        Instant nginxStartedAt,
        boolean caddyRunning,
        Long caddyPid,
        Instant caddyStartedAt,
        boolean nginxInstalled,
        boolean caddyInstalled,
        int streamPort,
        int httpPort,
        int httpsPort,
        int routeCount,
        int siteCount
) {
}