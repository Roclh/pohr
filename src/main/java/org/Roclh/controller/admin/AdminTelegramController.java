package org.Roclh.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.TelegramProxyConfig;
import org.Roclh.model.TelegramProxyUser;
import org.Roclh.model.dto.LogFilterOption;
import org.Roclh.model.dto.telegram.TelegramProxyConfigForm;
import org.Roclh.service.telegram.TelegramProxyService;
import org.Roclh.service.telegram.TelemtProcessManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/telegram")
@RequiredArgsConstructor
public class AdminTelegramController {

    private final TelegramProxyService telegramProxyService;
    private final TelemtProcessManager telemtProcessManager;

    @GetMapping
    public String page(Model model) {
        TelegramProxyConfig c = telegramProxyService.getConfig();
        model.addAttribute("configForm", new TelegramProxyConfigForm(
                c.getListenPort(), c.getCoverDomain(), c.isWebEnabled()));
        model.addAttribute("configDto", telegramProxyService.toConfigDto(c));
        model.addAttribute("users", telegramProxyService.findAll().stream()
                .map(telegramProxyService::toUserDto)
                .toList());

        model.addAttribute("recentLogs", telemtProcessManager.recentLogs(200));

        List<LogFilterOption> filterOptions = new ArrayList<>();
        for (TelegramProxyUser u : telegramProxyService.findAll()) {
            if (u.getLabel() == null || u.getLabel().isBlank()) continue;
            filterOptions.add(new LogFilterOption(u.getLabel(), u.getLabel()));
        }
        model.addAttribute("filterOptions", filterOptions);
        return "admin/telegram";
    }

    @PostMapping("/config")
    public String saveConfig(@Valid @ModelAttribute("configForm") TelegramProxyConfigForm form,
                             BindingResult binding,
                             RedirectAttributes flash) {
        if (binding.hasErrors()) return "admin/telegram";
        try {
            telegramProxyService.updateGlobalConfig(
                    form.listenPort(), form.coverDomain(), form.webEnabled());
            flash.addFlashAttribute("successMessage", "flash.telegram.configSaved");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/telegram";
    }

    @PostMapping("/start")
    public String start(RedirectAttributes flash) {
        try {
            telegramProxyService.start();
            flash.addFlashAttribute("successMessage", "flash.telegram.started");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/telegram";
    }

    @PostMapping("/stop")
    public String stop(RedirectAttributes flash) {
        try {
            telegramProxyService.stop();
            flash.addFlashAttribute("successMessage", "flash.telegram.stopped");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/telegram";
    }

    @PostMapping("/restart")
    public String restart(RedirectAttributes flash) {
        try {
            telegramProxyService.restart();
            flash.addFlashAttribute("successMessage", "flash.telegram.restarted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/telegram";
    }

    @PostMapping("/install")
    public String install(@RequestParam String version, RedirectAttributes flash) {
        try {
            telegramProxyService.install(version);
            flash.addFlashAttribute("successMessage", "flash.telegram.installed");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/telegram";
    }
}