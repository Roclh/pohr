package org.Roclh.model.dto.edge;

import java.util.List;

/**
 * Строка карты SNI: куда nginx stream отправляет конкретное имя.
 *
 * @param sni        имя из SNI (для fallback — {@code default})
 * @param target     host:port назначения
 * @param type       reality | telemt | caddy | fallback
 * @param resolves   резолвится ли SNI в IP сервера
 * @param addresses  фактические A/AAAA-записи SNI
 * @param warnings   предупреждения именно этой строки
 */
public record EdgeSniRowDto(
        String sni,
        String target,
        String type,
        boolean resolves,
        List<String> addresses,
        List<EdgeWarningDto> warnings
) {
}
