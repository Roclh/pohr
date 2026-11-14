package org.Roclh.controller.admin.edge;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeConfigForm;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.EdgeConfigService;
import org.Roclh.service.edge.EdgeService;
import org.Roclh.service.edge.EdgeVisibilityService;
import org.Roclh.service.edge.NginxProcessManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/admin/edge")
@RequiredArgsConstructor
public class AdminEdgeController {

    private final EdgeService edgeService;
    private final EdgeConfigService cfgService;
    private final EdgeVisibilityService visibility;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("status", edgeService.status());
        model.addAttribute("nginxLogs", nginx.recentLogs(200));
        model.addAttribute("caddyLogs", caddy.recentLogs(200));
        model.addAttribute("edgeMap", visibility.buildMap());
        EdgeConfigFilesDto configs = visibility.configFiles();
        model.addAttribute("configs", configs);
        model.addAttribute("configList", java.util.List.of(configs.nginx(), configs.caddy()));
        model.addAttribute("emptyFilterOptions", java.util.List.of());
        return "admin/edge/edge";
    }

    /** Сгенерированные конфиги только на чтение: download=1 отдаёт вложением. */
    @GetMapping("/config/nginx")
    public ResponseEntity<String> nginxConfig(@RequestParam(defaultValue = "false") boolean download) {
        return configResponse(visibility.configFiles().nginx(), "nginx.conf", download);
    }

    @GetMapping("/config/caddy")
    public ResponseEntity<String> caddyConfig(@RequestParam(defaultValue = "false") boolean download) {
        return configResponse(visibility.configFiles().caddy(), "Caddyfile", download);
    }

    @GetMapping("/preview")
    public String preview(Model model) {
        var preview = visibility.preview();
        model.addAttribute("preview", preview);
        model.addAttribute("previewList", java.util.List.of(preview.nginx(), preview.caddy()));
        return "admin/edge/edge-preview";
    }

    private static ResponseEntity<String> configResponse(EdgeConfigFileDto file,
                                                         String filename,
                                                         boolean download) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                (download ? "attachment" : "inline") + "; filename=\"" + filename + "\"");
        headers.set("X-Content-Sha256", file.generatedHash());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(file.generatedText());
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
