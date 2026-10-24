package org.Roclh.model.dto;

/**
 * Сводка по трафику на странице мониторинга.
 * activeConnections / activeUsers могут быть null, если сэмплов ещё нет.
 */
public record TrafficSummary(
        String upToday,
        String downToday,
        Integer activeConnections,
        Integer activeUsers,
        int clientsToday
) {
}
