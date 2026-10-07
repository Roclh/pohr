package org.Roclh.model.dto.edge;

import jakarta.validation.constraints.*;

public record EdgeSiteForm(

        @NotBlank @Size(max = 253)
        @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
                message = "{validation.edge.site.domain.pattern}")
        String domain,

        @NotBlank
        @Pattern(regexp = "^(proxy|redirect)$",
                message = "{validation.edge.site.type.pattern}")
        String siteType,

        @Size(max = 255)
        String upstreamHost,

        @Min(1) @Max(65535)
        Integer upstreamPort,

        @Size(max = 64)
        String bindAddress,

        @Size(max = 512)
        String redirectTarget,

        @Pattern(regexp = "^$|^(permanent|temporary|html)$",
                message = "{validation.edge.site.redirectCode.pattern}")
        String redirectCode,

        @NotBlank
        @Pattern(regexp = "^(acme|internal|off)$",
                message = "{validation.edge.site.tls.pattern}")
        String tlsMode,

        @Email @Size(max = 255)
        String acmeEmail,

        @Size(max = 8192)
        String extraDirectives,

        boolean enabled
) {
}