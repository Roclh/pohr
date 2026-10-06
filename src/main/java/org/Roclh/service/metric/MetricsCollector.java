package org.Roclh.service.metric;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.MetricSample;
import org.Roclh.repository.MetricSampleRepository;
import org.Roclh.service.telegram.TelemtProcessManager;
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
    private final TelemtProcessManager telemtProcessManager;

    private final OperatingSystemMXBean osBean =
            ManagementFactory.getOperatingSystemMXBean();

    private Long prevCgroupCpuNanos;
    private Long prevXrayCpuNanos;
    private Long prevTelemtCpuNanos;
    private Long prevXrayPid;
    private Long prevTelemtPid;
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

        Long xrayCpuNs = null, xrayRss = null;
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

        Long telemtCpuNs = null, telemtRss = null;
        long telemtRx = 0, telemtTx = 0;
        boolean telemtRunning = telemtProcessManager.isRunning();
        Long telemtPid = telemtProcessManager.pid();
        if (telemtRunning && telemtPid != null) {
            telemtCpuNs = containerMetrics.readProcessCpuNanos(telemtPid, 100L);
            telemtRss = containerMetrics.readProcessRssBytes(telemtPid);
            long[] io = containerMetrics.readProcessIoBytes(telemtPid);
            telemtRx = io[0];
            telemtTx = io[1];
        }

        long dtNs = prevSampleAt == null ? 0 : Duration.between(prevSampleAt, now).toNanos();

        Double podCpuPct = null;
        if (cgroupCpuNs != null && prevCgroupCpuNanos != null && dtNs > 0) {
            double d = (cgroupCpuNs - prevCgroupCpuNanos) * 100.0 / dtNs;
            if (d >= 0) podCpuPct = d;
        }

        Double xrayCpuPct = null;
        if (xrayCpuNs != null && prevXrayCpuNanos != null && dtNs > 0
                && java.util.Objects.equals(prevXrayPid, xrayPid)) {
            double d = (xrayCpuNs - prevXrayCpuNanos) * 100.0 / dtNs;
            if (d >= 0) xrayCpuPct = d;
        }

        Double telemtCpuPct = null;
        if (telemtCpuNs != null && prevTelemtCpuNanos != null && dtNs > 0
                && java.util.Objects.equals(prevTelemtPid, telemtPid)) {
            double d = (telemtCpuNs - prevTelemtCpuNanos) * 100.0 / dtNs;
            if (d >= 0) telemtCpuPct = d;
        }

        prevCgroupCpuNanos = cgroupCpuNs;
        prevXrayCpuNanos = xrayCpuNs;
        prevTelemtCpuNanos = telemtCpuNs;
        prevXrayPid = xrayPid;
        prevTelemtPid = telemtPid;
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
                .telemtRunning(telemtRunning)
                .telemtCpuPct(telemtCpuPct)
                .telemtMemBytes(telemtRss)
                .telemtRxBytes(telemtRx)
                .telemtTxBytes(telemtTx)
                .build();
    }

    private void pruneOld(Instant now) {
        String cutoff = now.minus(Duration.ofDays(retentionDays)).toString();
        int removed = repository.deleteOlderThan(cutoff);
        if (removed > 0) log.debug("Pruned {} old metric samples", removed);
    }
}
