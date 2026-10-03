package org.Roclh.model.dto;

import java.time.Instant;

public record ScriptInfo(
        String name,
        long size,
        Instant modifiedAt,
        boolean bundled,
        boolean matchesBundled
) {
}