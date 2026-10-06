package org.Roclh.model.dto;

/**
 * Опция фильтра логов. {@code value} — подстрока для поиска в строке лога
 * (subscription UUID для Xray, label для telemt), {@code label} — отображаемое имя.
 */
public record LogFilterOption(
        String value,
        String label
) {
}