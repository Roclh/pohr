package org.Roclh.controller.admin;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.EuNodeDto;
import org.Roclh.model.dto.MetricsSnapshot;
import org.Roclh.model.dto.NamedSeries;
import org.Roclh.service.metric.MetricsService;
import org.Roclh.service.node.EuNodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private static final DateTimeFormatter HHMM =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private final MetricsService metricsService;
    private final EuNodeService euNodeService;

    @GetMapping
    public String page(Model model) {
        List<MetricsSnapshot> snapshots = metricsService.last24h();

        model.addAttribute("latest", metricsService.latest());
        model.addAttribute("sampleCount", snapshots.size());

        // CPU: pod (bold) + xray (dashed) + telemt (dashed)
        model.addAttribute("cpuChart", MetricsService.buildMultiChart(List.of(
                new NamedSeries("Pod",    "chart-line-pod",    false,
                        snapshots.stream().map(MetricsSnapshot::podCpuPct).toList()),
                new NamedSeries("Xray",   "chart-line-xray",   true,
                        snapshots.stream().map(MetricsSnapshot::xrayCpuPct).toList()),
                new NamedSeries("telemt", "chart-line-telemt", true,
                        snapshots.stream().map(MetricsSnapshot::telemtCpuPct).toList())
        ), "%", 600, 120));

        // RAM: pod (bold) + xray (dashed) + telemt (dashed)
        model.addAttribute("memChart", MetricsService.buildMultiChart(List.of(
                new NamedSeries("Pod",    "chart-line-pod",    false,
                        snapshots.stream().map(s -> toMb(s.podMemBytes())).toList()),
                new NamedSeries("Xray",   "chart-line-xray",   true,
                        snapshots.stream().map(s -> toMb(s.xrayMemBytes())).toList()),
                new NamedSeries("telemt", "chart-line-telemt", true,
                        snapshots.stream().map(s -> toMb(s.telemtMemBytes())).toList())
        ), "MB", 600, 120));

        // JVM heap: одна линия
        model.addAttribute("jvmChart", MetricsService.buildMultiChart(List.of(
                new NamedSeries("JVM heap", "chart-line-jvm", false,
                        snapshots.stream().map(s -> toMb(s.jvmHeapBytes())).toList())
        ), "MB", 600, 120));

        if (!snapshots.isEmpty()) {
            model.addAttribute("chartXStart", HHMM.format(Instant.parse(snapshots.get(0).ts())));
            model.addAttribute("chartXEnd",   HHMM.format(Instant.parse(snapshots.get(snapshots.size() - 1).ts())));
        }

        List<EuNodeDto> nodes = new ArrayList<>(
                euNodeService.findAll().stream().map(EuNodeDto::from).toList());
        model.addAttribute("nodes", nodes);

        return "admin/monitoring";
    }

    private static Double toMb(Long bytes) {
        return bytes == null ? null : bytes / 1024.0 / 1024.0;
    }
}
