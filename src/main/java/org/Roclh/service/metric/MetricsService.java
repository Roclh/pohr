package org.Roclh.service.metric;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.*;
import org.Roclh.repository.MetricSampleRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MetricSampleRepository repository;

    public List<MetricsSnapshot> last24h() {
        String from = Instant.now().minus(Duration.ofHours(24)).toString();
        return repository.findByTsGreaterThanEqualOrderByTsAsc(from).stream()
                .map(MetricsSnapshot::from)
                .toList();
    }

    public MetricsSnapshot latest() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .findFirst().map(MetricsSnapshot::from).orElse(null);
    }

    public static MultiChartDto buildMultiChart(List<NamedSeries> seriesList, String unit, int w, int h) {
        double gMin = Double.POSITIVE_INFINITY, gMax = Double.NEGATIVE_INFINITY;
        for (NamedSeries s : seriesList) {
            for (Double v : s.values()) {
                if (v == null) continue;
                if (v < gMin) gMin = v;
                if (v > gMax) gMax = v;
            }
        }
        if (!Double.isFinite(gMin)) {
            return new MultiChartDto(List.of(), 0, 0, List.of(), false);
        }
        if (gMax - gMin < 1e-9) gMax = gMin + 1;
        double pad = (gMax - gMin) * 0.1;
        double yMin = gMin - pad;
        double yMax = gMax + pad;

        List<ChartSeries> out = new ArrayList<>();
        for (NamedSeries s : seriesList) {
            String path = buildPath(s.values(), yMin, yMax, w, h);
            String cls = s.cssClass() + (s.dashed() ? " chart-line-dashed" : " chart-line-bold");
            out.add(new ChartSeries(s.label(), cls, path));
        }

        double mid = (gMin + gMax) / 2.0;
        List<String> ticks = List.of(
                formatY(gMax, unit),
                formatY(mid,  unit),
                formatY(gMin, unit));
        return new MultiChartDto(out, gMin, gMax, ticks, true);
    }

    private static String buildPath(List<Double> values, double yMin, double yMax, int w, int h) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null) { started = false; continue; }
            double x = values.size() == 1 ? 0 : (double) i / (values.size() - 1) * w;
            double y = h - ((v - yMin) / (yMax - yMin)) * h;
            sb.append(started ? " L " : " M ").append(fmt(x)).append(',').append(fmt(y));
            started = true;
        }
        return sb.toString().trim();
    }

    private static String formatY(double v, String unit) {
        if ("%".equals(unit))  return String.format(Locale.ROOT, "%.1f%%", v);
        if ("MB".equals(unit)) return String.format(Locale.ROOT, "%.1f MB", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
