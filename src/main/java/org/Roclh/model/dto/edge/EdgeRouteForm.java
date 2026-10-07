package org.Roclh.model.dto.edge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EdgeRouteForm(

        @NotBlank
        @Size(max = 253)
        @Pattern(regexp = "^\\*?[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
                message = "{validation.edge.route.sni.pattern}")
        String sni,

        @NotBlank
        @Size(max = 255)
        String targetHost,

        @NotNull @Min(1) @Max(65535)
        Integer targetPort,

        @NotBlank
        @Pattern(regexp = "^(tcp|udp)$", message = "{validation.edge.route.protocol.pattern}")
        String protocol,

        @Size(max = 255)
        String description,

        boolean enabled,

        @NotNull @Min(0) @Max(9999)
        Integer sortOrder
) {
}