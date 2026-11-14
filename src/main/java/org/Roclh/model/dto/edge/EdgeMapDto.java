package org.Roclh.model.dto.edge;

import java.util.List;

/**
 * Эффективная карта SNI + предупреждения, которые нельзя привязать к одной строке.
 *
 * @param serverIp   IP сервера, с которым сравниваются A-записи (может быть null)
 * @param publicHost хост из {@code pohr.public-url} (может быть null)
 * @param baseDomain базовый домен для проверки {@code www}/{@code edge}/{@code eu}
 * @param routes     строки карты: SNI-маршруты и сайты, идущие через fallback
 * @param fallback   строка fallback для несовпавших SNI
 * @param warnings   глобальные предупреждения
 */
public record EdgeMapDto(
        String serverIp,
        String publicHost,
        String baseDomain,
        List<EdgeSniRowDto> routes,
        EdgeSniRowDto fallback,
        List<EdgeWarningDto> warnings
) {
    public long warningCount() {
        long rowWarnings = routes.stream().mapToLong(row -> row.warnings().size()).sum();
        return rowWarnings + warnings.size() + fallback.warnings().size();
    }
}
