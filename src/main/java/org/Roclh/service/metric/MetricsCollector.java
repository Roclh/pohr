package org.Roclh.service.metric;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.MetricSample;
import org.Roclh.repository.MetricSampleRepository;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollector {

    private final MetricSampleRepository repository;
    private final ContainerMetrics containerMetrics;
    private final XrayProcessManager processManager;

    private final OperatingSystemMXBean osBean =
            ManagementFactory.getOperatingSystemMXBean();

    private Long prevCgroupCpuNanos;
    private Long prevXrayCpuNanos;
    private Instant prevSampleAt;

    @Value("${pohr.metrics.retention-days:7}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${pohr.metrics.interval-ms:60000}",
            initialDelayString = "${pohr.metrics.initial-delay-ms:15000}")
    public void collect() {
        try {
            Instant now = Instant.now();
            MetricSample sample = doCollect(now);
            repository.save(sample);
            pruneOld(now);
        } catch (Exception e) {
            log.warn("Metrics collection failed: {}", e.getMessage());
        }
    }

    private MetricSample doCollect(Instant now) {
        Long cgroupCpuNs = containerMetrics.readCgroupCpuUsageNanos();
        Long xrayCpuNs = null;
        Long xrayRss = null;
        long xrayRx = 0, xrayTx = 0;
        boolean xrayRunning = processManager.isRunning();
        Long xrayPid = processManager.pid();

        if (xrayRunning && xrayPid != null) {
            xrayCpuNs = containerMetrics.readProcessCpuNanos(xrayPid, 100L);
            xrayRss = containerMetrics.readProcessRssBytes(xrayPid);
            long[] io = containerMetrics.readProcessIoBytes(xrayPid);
            xrayRx = io[0];
            xrayTx = io[1];
        }

        Double podCpuPct = null;
        if (cgroupCpuNs != null && prevCgroupCpuNanos != null && prevSampleAt != null) {
            long dtNs = Duration.between(prevSampleAt, now).toNanos();
            if (dtNs > 0) {
                podCpuPct = (cgroupCpuNs - prevCgroupCpuNanos) * 100.0 / dtNs;
            }
        }
        Double xrayCpuPct = null;
        if (xrayCpuNs != null && prevXrayCpuNanos != null && prevSampleAt != null) {
            long dtNs = Duration.between(prevSampleAt, now).toNanos();
            if (dtNs > 0) {
                xrayCpuPct = (xrayCpuNs - prevXrayCpuNanos) * 100.0 / dtNs;
            }
        }

        prevCgroupCpuNanos = cgroupCpuNs;
        prevXrayCpuNanos = xrayCpuNs;
        prevSampleAt = now;

        Runtime rt = Runtime.getRuntime();
        long heap = rt.totalMemory() - rt.freeMemory();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();

        Long podMem = containerMetrics.isInsideContainer()
                ? containerMetrics.readCgroupMemoryBytes()
                : containerMetrics.readCurrentProcessRssBytes();

        return MetricSample.builder()
                .ts(now.toString())
                .podCpuPct(podCpuPct)
                .podMemBytes(podMem)
                .podMemLimitBytes(containerMetrics.readCgroupMemoryLimitBytes())
                .jvmHeapBytes(heap)
                .jvmThreads(threads)
                .xrayRunning(xrayRunning)
                .xrayCpuPct(xrayCpuPct)
                .xrayMemBytes(xrayRss)
                .xrayRxBytes(xrayRx)
                .xrayTxBytes(xrayTx)
                .build();
    }

    private void pruneOld(Instant now) {
        String cutoff = now.minus(Duration.ofDays(retentionDays)).toString();
        int removed = repository.deleteOlderThan(cutoff);
        if (removed > 0) log.debug("Pruned {} old metric samples", removed);
    }
}
