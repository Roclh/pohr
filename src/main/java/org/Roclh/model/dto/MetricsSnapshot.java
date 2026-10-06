package org.Roclh.model.dto;

import org.Roclh.model.MetricSample;

public record MetricsSnapshot(
        String ts,
        Double podCpuPct,
        Long   podMemBytes,
        Long   podMemLimitBytes,
        Long   jvmHeapBytes,
        Integer jvmThreads,
        boolean xrayRunning,
        Double xrayCpuPct,
        Long   xrayMemBytes,
        Long   xrayRxBytes,
        Long   xrayTxBytes,
        boolean telemtRunning,
        Double telemtCpuPct,
        Long   telemtMemBytes,
        Long   telemtRxBytes,
        Long   telemtTxBytes
) {
    public static MetricsSnapshot from(MetricSample s) {
        return new MetricsSnapshot(
                s.getTs(), s.getPodCpuPct(), s.getPodMemBytes(), s.getPodMemLimitBytes(),
                s.getJvmHeapBytes(), s.getJvmThreads(),
                s.isXrayRunning(), s.getXrayCpuPct(), s.getXrayMemBytes(),
                s.getXrayRxBytes(), s.getXrayTxBytes(),
                s.isTelemtRunning(), s.getTelemtCpuPct(), s.getTelemtMemBytes(),
                s.getTelemtRxBytes(), s.getTelemtTxBytes());
    }
}