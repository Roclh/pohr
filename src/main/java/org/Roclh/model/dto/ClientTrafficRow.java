package org.Roclh.model.dto;

/**
 * Строка таблицы трафика клиента. Строки уже отформатированы, чтобы
 * Thymeleaf не вызывал методы форматирования через SpEL (в native это дороже
 * и требует reflection-хинтов).
 */
public record ClientTrafficRow(
        String username,
        String upToday,
        String downToday,
        String upWeek,
        String downWeek,
        String lastSeen,
        boolean active
) {
}
