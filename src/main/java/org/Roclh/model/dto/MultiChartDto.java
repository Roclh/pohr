package org.Roclh.model.dto;

import java.util.List;

/** Составной график: несколько серий + подписи оси Y сверху-вниз. */
public record MultiChartDto(
        List<ChartSeries> series,
        double min,
        double max,
        List<String> yTicks,
        boolean hasData
) {
}