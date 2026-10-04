package org.Roclh.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.ClientConfig;
import org.Roclh.model.dto.ClientConfigForm;
import org.Roclh.service.ClientConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/client-configs")
@RequiredArgsConstructor
public class AdminClientConfigController {

    private final ClientConfigService clientConfigService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("configs", clientConfigService.findAll());
        return "admin/client-configs";
    }

    @GetMapping("/{name}/edit")
    public String editForm(@PathVariable String name, Model model) {
        ClientConfig cfg = clientConfigService.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown client: " + name));
        model.addAttribute("name", name);
        model.addAttribute("form", new ClientConfigForm(
                cfg.isEnabled(), cfg.getUserAgentPattern(), cfg.getHeaders()));
        return "admin/client-config-form";
    }

    @PostMapping("/{name}")
    public String save(@PathVariable String name,
                       @Valid @ModelAttribute("form") ClientConfigForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {
        if (binding.hasErrors()) {
            model.addAttribute("name", name);
            return "admin/client-config-form";
        }
        try {
            clientConfigService.update(name, form.enabled(), form.userAgentPattern(), form.headers());
            flash.addFlashAttribute("successMessage", "flash.clientConfig.saved");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/client-configs/" + name + "/edit";
    }
}