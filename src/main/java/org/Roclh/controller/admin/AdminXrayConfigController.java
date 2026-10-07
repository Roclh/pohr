package org.Roclh.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.XrayConfig;
import org.Roclh.model.dto.XrayConfigForm;
import org.Roclh.model.dto.XrayConfigPreview;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequestMapping("/admin/xray/configs")
@RequiredArgsConstructor
public class AdminXrayConfigController {

    private final XrayConfigService configService;
    private final XrayConfigMaterializer materializer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public String list(Model model) {
        model.addAttribute("configs", configService.findAll());
        return "admin/xray/xray-configs";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new XrayConfigForm("", "", "", ""));
        model.addAttribute("mode", "create");
        return "admin/xray/xray-config-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") XrayConfigForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        validateJson(form.content(), binding);
        validateRealityKey(form.content(), form.realityPublicKey(), binding);
        if (binding.hasErrors()) {
            model.addAttribute("mode", "create");
            return "admin/xray/xray-config-form";
        }
        try {
            configService.create(form.name(), form.description(),
                    materializer.dematerialize(form.content()), form.realityPublicKey());
            flash.addFlashAttribute("successMessage", "flash.xray.config.created");
            return "redirect:/admin/xray/configs";
        } catch (IllegalArgumentException e) {
            binding.rejectValue("name", "error.xray.config.name.taken");
            model.addAttribute("mode", "create");
            return "admin/xray/xray-config-form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        XrayConfig cfg = configService.findById(id).orElse(null);
        if (cfg == null) {
            flash.addFlashAttribute("errorMessage", "error.xray.config.notfound");
            return "redirect:/admin/xray/configs";
        }

        String content = cfg.getContent();
        List<Integer> generatedLines = List.of();

        if (cfg.isActive()) {
            try {
                XrayConfigPreview p = materializer.preview();
                content = p.materialized();
                generatedLines = p.generatedLines();
            } catch (Exception e) {
                log.warn("Failed to build config preview: {}", e.getMessage());
            }
        }

        model.addAttribute("form", new XrayConfigForm(
                cfg.getName(), cfg.getDescription(), content, cfg.getRealityPublicKey()));
        model.addAttribute("mode", "edit");
        model.addAttribute("configId", id);
        model.addAttribute("active", cfg.isActive());
        model.addAttribute("generatedLinesCsv",
                generatedLines.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return "admin/xray/xray-config-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") XrayConfigForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        validateJson(form.content(), binding);
        validateRealityKey(form.content(), form.realityPublicKey(), binding);
        if (binding.hasErrors()) {
            model.addAttribute("mode", "edit");
            model.addAttribute("configId", id);
            return "admin/xray/xray-config-form";
        }
        try {
            configService.update(id, form.name(), form.description(),
                    materializer.dematerialize(form.content()), form.realityPublicKey());
            flash.addFlashAttribute("successMessage", "flash.xray.config.updated");
            return "redirect:/admin/xray/configs";
        } catch (IllegalArgumentException e) {
            binding.rejectValue("name", "error.xray.config.name.taken");
            model.addAttribute("mode", "edit");
            model.addAttribute("configId", id);
            return "admin/xray/xray-config-form";
        }
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            configService.activate(id);
            materializer.materialize();
            flash.addFlashAttribute("successMessage", "flash.xray.config.activated");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray/configs";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            configService.delete(id);
            flash.addFlashAttribute("successMessage", "flash.xray.config.deleted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray/configs";
    }

    @PostMapping("/{id}/clone")
    public String clone(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            XrayConfig copy = configService.clone(id);
            flash.addFlashAttribute("successMessage", "flash.xray.config.cloned");
            return "redirect:/admin/xray/configs/" + copy.getId() + "/edit";
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/xray/configs";
        }
    }

    @PostMapping("/{id}/refresh-key")
    public String refreshKey(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            configService.refreshRealityPublicKey(id);
            flash.addFlashAttribute("successMessage", "flash.xray.config.keyRefreshed");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/xray/configs/" + id + "/edit";
    }

    private void validateJson(String content, BindingResult binding) {
        try {
            objectMapper.readTree(content);
        } catch (Exception e) {
            binding.rejectValue("content", "error.xray.config.invalidJson");
        }
    }

    private void validateRealityKey(String content, String publicKey, BindingResult binding) {
        try {
            JsonNode root = objectMapper.readTree(content);
            boolean hasReality = false;
            for (JsonNode in : root.path("inbounds")) {
                if ("reality".equals(in.path("streamSettings").path("security").asString())) {
                    hasReality = true;
                    break;
                }
            }
            if (hasReality && (publicKey == null || publicKey.isBlank())) {
                binding.rejectValue("realityPublicKey", "error.xray.config.realityPublicKey.required");
            }
        } catch (Exception ignored) {
        }
    }
}