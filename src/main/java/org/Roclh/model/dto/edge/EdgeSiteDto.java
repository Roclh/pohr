package org.Roclh.model.dto.edge;

import org.Roclh.model.edge.EdgeSite;

import java.time.Instant;
import java.util.UUID;

public record EdgeSiteDto(
        UUID id,
        String domain,
        String siteType,
        String upstreamHost,
        Integer upstreamPort,
        String bindAddress,
        String redirectTarget,
        String redirectCode,
        String tlsMode,
        String acmeEmail,
        String extraDirectives,
        boolean enabled,
        Instant updatedAt
) {
    public static EdgeSiteDto from(EdgeSite s) {
        return new EdgeSiteDto(
                s.getId(), s.getDomain(), s.getSiteType(),
                s.getUpstreamHost(), s.getUpstreamPort(), s.getBindAddress(),
                s.getRedirectTarget(), s.getRedirectCode(),
                s.getTlsMode(), s.getAcmeEmail(), s.getExtraDirectives(),
                s.isEnabled(), s.getUpdatedAt());
    }
}