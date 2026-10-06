package org.Roclh.model.dto;

/** Одна линия составного графика. cssClass содержит все нужные классы (color + dashed/bold). */
public record ChartSeries(
        String label,
        String cssClass,
        String path
) {
}