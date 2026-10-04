package org.Roclh.service.metric;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.MetricsSnapshot;
import org.Roclh.repository.MetricSampleRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    /**
     * Строит SVG-path в прямоугольнике w×h из списка значений.
     * null-значения игнорируются (пропуски в линии).
     */
    public static String toSvgPath(List<Double> values, int w, int h) {
        if (values == null || values.isEmpty()) return "";
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Double v : values) {
            if (v == null) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (!Double.isFinite(min)) return "";
        if (max - min < 1e-9) { max = min + 1; }
        double pad = (max - min) * 0.1;
        min -= pad;
        max += pad;

        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null) { started = false; continue; }
            double x = values.size() == 1 ? 0 : (double) i / (values.size() - 1) * w;
            double y = h - ((v - min) / (max - min)) * h;
            sb.append(started ? " L " : " M ")
                    .append(fmt(x)).append(',').append(fmt(y));
            started = true;
        }
        return sb.toString().trim();
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
