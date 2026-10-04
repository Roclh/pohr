package org.Roclh.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.TelegramProxyConfig;
import org.Roclh.model.dto.telegram.TelegramProxyConfigForm;
import org.Roclh.service.telegram.TelegramProxyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/telegram")
@RequiredArgsConstructor
public class AdminTelegramController {

    private final TelegramProxyService telegramProxyService;

    @GetMapping
    public String page(Model model) {
        TelegramProxyConfig c = telegramProxyService.getConfig();
        model.addAttribute("configForm", new TelegramProxyConfigForm(
                c.getListenPort(), c.getCoverDomain(), c.isWebEnabled()));
        model.addAttribute("configDto", telegramProxyService.toConfigDto(c));
        model.addAttribute("users", telegramProxyService.findAll().stream()
                .map(telegramProxyService::toUserDto)
                .toList());
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