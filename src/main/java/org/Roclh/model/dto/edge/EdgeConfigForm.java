package org.Roclh.model.dto.edge;

import jakarta.validation.constraints.*;

public record EdgeConfigForm(

        @NotNull @Min(1) @Max(65535)
        Integer streamPort,

        @NotNull @Min(1) @Max(65535)
        Integer httpPort,

        @NotNull @Min(1) @Max(65535)
        Integer httpsPort,

        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[A-Za-z0-9._-]+:\\d{1,5}$",
                message = "{validation.edge.fallback.pattern}")
        String fallbackTarget,

        @NotBlank @Size(max = 16)
        @Pattern(regexp = "^(auto|\\d{1,4})$",
                message = "{validation.edge.workers.pattern}")
        String workerProcesses,

        @NotNull @Min(64) @Max(65535)
        Integer workerConnections,

        @NotBlank @Size(max = 16)
        @Pattern(regexp = "^\\d+[smh]?$",
                message = "{validation.edge.timeout.pattern}")
        String streamProxyTimeout,

        @NotBlank @Size(max = 16)
        @Pattern(regexp = "^\\d+[smh]?$",
                message = "{validation.edge.timeout.pattern}")
        String streamConnectTimeout,

        @NotBlank
        @Pattern(regexp = "^(on|off|disable_redirects|disable_certs)$",
                message = "{validation.edge.autoHttps.pattern}")
        String autoHttps,

        @Size(max = 8192)
        String extraStreamDirectives,

        @Size(max = 8192)
        String extraHttpDirectives,

        @Size(max = 8192)
        String extraGlobalDirectives
) {
}