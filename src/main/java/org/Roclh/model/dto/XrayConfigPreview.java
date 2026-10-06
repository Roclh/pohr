package org.Roclh.model.dto;

import java.util.List;

/**
 * Два представления активного Xray-конфига: как он лежит в БД и каким
 * станет после материализации (клиенты, EU-outbound, telemt-socks, routing).
 */
public record XrayConfigPreview(
        String original,
        String materialized,
        List<Integer> generatedLines
) {
}