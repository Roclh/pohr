package org.Roclh.controller.admin;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.model.dto.XrayClientOption;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.Roclh.service.xray.XrayVersionResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/xray")
@RequiredArgsConstructor
public class AdminXrayController {

    private final XrayProcessManager processManager;
    private final XrayVersionResolver versionResolver;
    private final XrayInstaller installer;
    private final SubscriptionService subscriptionService;   // NEW
    private final UserService userService;                   // NEW

    @GetMapping
    public String page(Model model) {
        model.addAttribute("running", processManager.isRunning());
        model.addAttribute("pid", processManager.pid());
        model.addAttribute("startedAt", processManager.getStartedAt());
        model.addAttribute("version", installer.installedVersion());
        model.addAttribute("installed", installer.isInstalled());
        model.addAttribute("recentLogs", processManager.recentLogs(50));
        List<XrayClientOption> clients = new ArrayList<>();
        for (Subscription s : subscriptionService.findAllEnabled()) {
            if (s.getXrayUuid() == null || s.getXrayUuid().isBlank()) continue;
            String username = userService.findById(s.getUserId())
                    .map(User::getUsername)
                    .orElse("?");
            clients.add(new XrayClientOption(s.getXrayUuid(), username));
        }
        model.addAttribute("clients", clients);
        return "admin/xray";
    }

    @PostMapping("/start")
    public String start(RedirectAttributes flash) {
        try {
            processManager.start();
            flash.addFlashAttribute("successMessage", "flash.xray.started");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray";
    }

    @PostMapping("/stop")
    public String stop(RedirectAttributes flash) {
        try {
            processManager.stop();
            flash.addFlashAttribute("successMessage", "flash.xray.stopped");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray";
    }

    @PostMapping("/restart")
    public String restart(RedirectAttributes flash) {
        try {
            processManager.restart();
            flash.addFlashAttribute("successMessage", "flash.xray.restarted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray";
    }

    @PostMapping("/install")
    public String install(@RequestParam String version, RedirectAttributes flash) {
        try {
            String target = version;
            if ("latest".equalsIgnoreCase(version)) {
                target = versionResolver.resolveLatest();
                if (target == null) {
                    throw new IllegalStateException("Could not resolve latest Xray version");
                }
            }
            installer.install(target);
            flash.addFlashAttribute("successMessage", "flash.xray.installed");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray";
    }
}