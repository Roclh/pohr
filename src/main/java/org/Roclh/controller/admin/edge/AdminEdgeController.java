package org.Roclh.controller.admin.edge;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.edge.EdgeConfigForm;
import org.Roclh.service.edge.EdgeConfigService;
import org.Roclh.service.edge.EdgeService;
import org.Roclh.service.edge.NginxProcessManager;
import org.Roclh.service.edge.CaddyProcessManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/edge")
@RequiredArgsConstructor
public class AdminEdgeController {

    private final EdgeService edgeService;
    private final EdgeConfigService cfgService;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("status", edgeService.status());
        model.addAttribute("nginxLogs", nginx.recentLogs(200));
        model.addAttribute("caddyLogs", caddy.recentLogs(200));
        model.addAttribute("emptyFilterOptions", java.util.List.of());
        return "admin/edge/edge";
    }

    @GetMapping("/config")
    public String configForm(Model model) {
        var c = cfgService.getConfig();
        model.addAttribute("form", new EdgeConfigForm(
                c.getStreamPort(), c.getHttpPort(), c.getHttpsPort(), c.getFallbackTarget(), c.getWorkerProcesses(),
                c.getWorkerConnections(), c.getStreamProxyTimeout(), c.getStreamConnectTimeout(), c.getAutoHttps(),
                c.getExtraStreamDirectives(), c.getExtraHttpDirectives(), c.getExtraGlobalDirectives()));
        return "admin/edge/edge-config";
    }

    @PostMapping("/config")
    public String configSave(@Valid @ModelAttribute("form") EdgeConfigForm form,
                             BindingResult binding,
                             RedirectAttributes flash) {
        if (binding.hasErrors()) return "admin/edge/edge-config";
        try {
            edgeService.updateConfig(form);
            edgeService.apply();
            flash.addFlashAttribute("successMessage", "flash.edge.configSaved");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/edge";
    }

    @PostMapping("/apply")
    public String apply(RedirectAttributes flash) {
        try {
            edgeService.apply();
            flash.addFlashAttribute("successMessage", "flash.edge.applied");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/edge";
    }

    @PostMapping("/nginx/{action}")
    public String nginxAction(@PathVariable String action, RedirectAttributes flash) {
        try {
            switch (action) {
                case "start"   -> edgeService.startNginx();
                case "stop"    -> edgeService.stopNginx();
                case "restart" -> edgeService.restartNginx();
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            flash.addFlashAttribute("successMessage", "flash.edge.nginx." + action);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/edge";
    }

    @PostMapping("/caddy/{action}")
    public String caddyAction(@PathVariable String action, RedirectAttributes flash) {
        try {
            switch (action) {
                case "start"   -> edgeService.startCaddy();
                case "stop"    -> edgeService.stopCaddy();
                case "restart" -> edgeService.restartCaddy();
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            flash.addFlashAttribute("successMessage", "flash.edge.caddy." + action);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/edge";
    }
}