package org.Roclh.model.dto.edge;

/**
 * Предупреждение карты SNI. Сообщение локализуется в UI по {@code code},
 * а {@code subject}/{@code detail} добавляют технический контекст.
 *
 * @param code    машинный код, например {@code dns-mismatch}
 * @param severity WARN | INFO
 * @param subject SNI, домен или адрес, к которому относится предупреждение
 * @param detail  необязательная деталь (например, реальные A-записи)
 */
public record EdgeWarningDto(
        String code,
        String severity,
        String subject,
        String detail
) {
}
