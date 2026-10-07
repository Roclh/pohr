package org.Roclh.controller.admin.edge;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.edge.EdgeRouteDto;
import org.Roclh.model.dto.edge.EdgeRouteForm;
import org.Roclh.model.edge.EdgeRoute;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.service.event.EdgeConfigChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.UUID;

@Controller
@RequestMapping("/admin/edge/routes")
@RequiredArgsConstructor
public class AdminEdgeRouteController {

    private final EdgeRouteRepository repo;
    private final ApplicationEventPublisher events;

    @GetMapping
    public String list(Model model) {
        var dtos = new ArrayList<>(repo.findAllByOrderBySortOrderAscSniAsc().stream()
                .map(EdgeRouteDto::from).toList());
        model.addAttribute("routes", dtos);
        return "admin/edge/edge-routes";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new EdgeRouteForm("", "127.0.0.1", 8443, "tcp", "", true, 100));
        model.addAttribute("mode", "create");
        return "admin/edge/edge-route-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") EdgeRouteForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        if (repo.existsBySni(form.sni())) {
            binding.rejectValue("sni", "validation.edge.route.sni.taken");
        }
        if (binding.hasErrors()) {
            model.addAttribute("mode", "create");
            return "admin/edge/edge-route-form";
        }
        repo.save(EdgeRoute.builder()
                .sni(form.sni())
                .targetHost(form.targetHost())
                .targetPort(form.targetPort())
                .protocol(form.protocol())
                .description(blankToNull(form.description()))
                .enabled(form.enabled())
                .sortOrder(form.sortOrder())
                .build());
        events.publishEvent(new EdgeConfigChangedEvent("route added: " + form.sni()));
        flash.addFlashAttribute("successMessage", "flash.edge.route.created");
        return "redirect:/admin/edge/routes";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        EdgeRoute r = repo.findById(id).orElse(null);
        if (r == null) {
            flash.addFlashAttribute("errorMessage", "error.edge.route.notfound");
            return "redirect:/admin/edge/routes";
        }
        model.addAttribute("form", new EdgeRouteForm(
                r.getSni(), r.getTargetHost(), r.getTargetPort(),
                r.getProtocol(), r.getDescription(), r.isEnabled(), r.getSortOrder()));
        model.addAttribute("mode", "edit");
        model.addAttribute("routeId", id);
        return "admin/edge/edge-route-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") EdgeRouteForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        EdgeRoute r = repo.findById(id).orElse(null);
        if (r == null) {
            flash.addFlashAttribute("errorMessage", "error.edge.route.notfound");
            return "redirect:/admin/edge/routes";
        }
        repo.findBySni(form.sni()).filter(other -> !other.getId().equals(id))
                .ifPresent(other -> binding.rejectValue("sni", "validation.edge.route.sni.taken"));
        if (binding.hasErrors()) {
            model.addAttribute("mode", "edit");
            model.addAttribute("routeId", id);
            return "admin/edge/edge-route-form";
        }
        r.setSni(form.sni());
        r.setTargetHost(form.targetHost());
        r.setTargetPort(form.targetPort());
        r.setProtocol(form.protocol());
        r.setDescription(blankToNull(form.description()));
        r.setEnabled(form.enabled());
        r.setSortOrder(form.sortOrder());
        repo.save(r);
        events.publishEvent(new EdgeConfigChangedEvent("route updated: " + form.sni()));
        flash.addFlashAttribute("successMessage", "flash.edge.route.updated");
        return "redirect:/admin/edge/routes";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable UUID id, RedirectAttributes flash) {
        repo.findById(id).ifPresent(r -> {
            r.setEnabled(!r.isEnabled());
            repo.save(r);
            events.publishEvent(new EdgeConfigChangedEvent("route toggled: " + r.getSni()));
        });
        flash.addFlashAttribute("successMessage", "flash.edge.route.toggled");
        return "redirect:/admin/edge/routes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        repo.findById(id).ifPresent(r -> {
            repo.delete(r);
            events.publishEvent(new EdgeConfigChangedEvent("route deleted: " + r.getSni()));
        });
        flash.addFlashAttribute("successMessage", "flash.edge.route.deleted");
        return "redirect:/admin/edge/routes";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}