package org.Roclh.controller.admin;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.EuNodeDto;
import org.Roclh.model.dto.MetricsSnapshot;
import org.Roclh.service.metric.MetricsService;
import org.Roclh.service.node.EuNodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private final MetricsService metricsService;
    private final EuNodeService euNodeService;

    @GetMapping
    public String page(Model model) {
        List<MetricsSnapshot> snapshots = metricsService.last24h();

        model.addAttribute("latest", metricsService.latest());
        model.addAttribute("sampleCount", snapshots.size());

        List<Double> cpuSeries  = snapshots.stream().map(MetricsSnapshot::podCpuPct).toList();
        List<Double> memSeries  = snapshots.stream().map(s -> toMb(s.podMemBytes())).toList();
        List<Double> xraySeries = snapshots.stream().map(s -> toMb(s.xrayMemBytes())).toList();

        model.addAttribute("cpuPath",  MetricsService.toSvgPath(cpuSeries,  600, 120));
        model.addAttribute("memPath",  MetricsService.toSvgPath(memSeries,  600, 120));
        model.addAttribute("xrayPath", MetricsService.toSvgPath(xraySeries, 600, 120));

        List<EuNodeDto> nodes = new ArrayList<>(
                euNodeService.findAll().stream().map(EuNodeDto::from).toList());
        model.addAttribute("nodes", nodes);

        return "admin/monitoring";
    }

    private static Double toMb(Long bytes) {
        return bytes == null ? null : bytes / 1024.0 / 1024.0;
    }
}
