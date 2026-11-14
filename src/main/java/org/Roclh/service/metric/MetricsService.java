package org.Roclh.service.metric;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.*;
import org.Roclh.repository.MetricSampleRepository;
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

    /** Последняя точка; раньше здесь был findAll + сортировка всей таблицы. */
    public MetricsSnapshot latest() {
        return repository.findTopByOrderByIdDesc().map(MetricsSnapshot::from).orElse(null);
    }

    // =====================================================================
    // Сводка для диагностики (MCP): агрегаты считает SQLite, наружу уходит
    // уже посчитанное, без тысяч точек.
    // =====================================================================

    /** Диапазон значений метрики в окне, в мегабайтах (кроме CPU — проценты). */
    public record MetricRange(double min, double avg, double max) {
        public static MetricRange of(double min, double avg, double max) {
            return new MetricRange(round(min), round(avg), round(max));
        }
    }

    /** Свёрнутая точка ряда: час или сутки. */
    public record MetricBucket(String key, int samples,
                               double anonAvg, double anonMax,
                               double usedAvg, double usedMax,
                               double xrayAvg, double xrayMax,
                               double heapAvg) {
    }

    /**
     * Компактная сводка по метрикам за окно.
     *
     * @param samplesOverLimit     сколько точек видели «занято ≥ 95 % лимита»
     *                             (включая page cache)
     * @param samplesAnonOverLimit сколько точек видели настоящий дефицит:
     *                             anon ≥ 95 % лимита
     * @param anonTrend            прирост anon между первой и второй половиной
     *                             окна, МБ; положительный — растёт
     */
    public record Stats(int hours, int samples, double limitMb,
                        MetricRange anon, MetricRange used, MetricRange xray,
                        MetricRange telemt, MetricRange heap, MetricRange cpuPct,
                        double nowAnon, double nowUsed,
                        long samplesOverLimit, long samplesAnonOverLimit,
                        double anonTrend,
                        List<MetricBucket> buckets) {
    }

    public Stats stats(int hours) {
        String from = Instant.now().minus(Duration.ofHours(hours)).toString();
        List<Object[]> rows = repository.aggregateSince(from);
        Object[] row = rows.isEmpty() ? new Object[22] : rows.getFirst();
        for (int i = 0; i < row.length; i++) {
            if (row[i] == null) row[i] = 0;
        }
        // Порядок колонок зафиксирован в MetricSampleRepository#aggregateSince.
        int samples = (int) num(row[0]);
        MetricRange used = MetricRange.of(num(row[1]), num(row[2]), num(row[3]));
        MetricRange anon = MetricRange.of(num(row[4]), num(row[5]), num(row[6]));
        MetricRange xray = MetricRange.of(num(row[7]), num(row[8]), num(row[9]));
        MetricRange telemt = MetricRange.of(num(row[10]), num(row[11]), num(row[12]));
        MetricRange heap = MetricRange.of(num(row[13]), num(row[14]), num(row[15]));
        MetricRange cpu = MetricRange.of(num(row[16]), num(row[17]), num(row[18]));
        double limitMb = num(row[19]);
        long overLimit = (long) num(row[20]);
        long anonOverLimit = (long) num(row[21]);

        // До 48 точек — по часам, иначе по суткам: ответ агента не должен
        // разрастаться на неделю поминутных данных.
        int len = hours > 48 ? 10 : 13;
        List<MetricBucket> buckets = new ArrayList<>();
        for (Object[] b : repository.bucketsSince(from, len)) {
            buckets.add(new MetricBucket(
                    (String) b[0],
                    (int) num(b[1]),
                    round(num(b[2])), round(num(b[3])),
                    round(num(b[4])), round(num(b[5])),
                    round(num(b[6])), round(num(b[7])),
                    round(num(b[8]))));
        }

        MetricsSnapshot latest = latest();
        return new Stats(hours, samples, round(limitMb),
                anon, used, xray, telemt, heap, cpu,
                latest == null ? 0 : round(toMb(latest.podAnonBytes())),
                latest == null ? 0 : round(toMb(latest.podMemBytes())),
                overLimit, anonOverLimit,
                round(trend(buckets)),
                buckets);
    }

    /** Прирост anon между половинами окна (взвешенно по числу точек). */
    private static double trend(List<MetricBucket> buckets) {
        if (buckets.size() < 4) return 0;
        int mid = buckets.size() / 2;
        double first = weightedAnon(buckets.subList(0, mid));
        double second = weightedAnon(buckets.subList(mid, buckets.size()));
        return second - first;
    }

    private static double weightedAnon(List<MetricBucket> part) {
        double sum = 0;
        int n = 0;
        for (MetricBucket b : part) {
            sum += b.anonAvg() * b.samples();
            n += b.samples();
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double toMb(Long bytes) {
        return bytes == null ? 0 : bytes / 1048576.0;
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
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
                formatY(gMin,  unit));
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
