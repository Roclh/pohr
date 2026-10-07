package org.Roclh.controller.admin.edge;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.dto.edge.EdgeSiteDto;
import org.Roclh.model.dto.edge.EdgeSiteForm;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeSiteRepository;
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
@RequestMapping("/admin/edge/sites")
@RequiredArgsConstructor
public class AdminEdgeSiteController {

    private final EdgeSiteRepository repo;
    private final ApplicationEventPublisher events;

    @GetMapping
    public String list(Model model) {
        var dtos = new ArrayList<>(repo.findAllByOrderByDomainAsc().stream()
                .map(EdgeSiteDto::from).toList());
        model.addAttribute("sites", dtos);
        return "admin/edge/edge-sites";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new EdgeSiteForm(
                "", "proxy", "127.0.0.1", 8080,
                "127.0.0.1", null, null,
                "acme", "", "", true));
        model.addAttribute("mode", "create");
        return "admin/edge/edge-site-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") EdgeSiteForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        if (repo.existsByDomain(form.domain())) {
            binding.rejectValue("domain", "validation.edge.site.domain.taken");
        }
        if ("proxy".equals(form.siteType())) {
            if (form.upstreamHost() == null || form.upstreamHost().isBlank()) {
                binding.rejectValue("upstreamHost", "validation.edge.site.upstream.required");
            }
            if (form.upstreamPort() == null) {
                binding.rejectValue("upstreamPort", "validation.edge.site.upstream.required");
            }
        } else if ("redirect".equals(form.siteType())) {
            if (form.redirectTarget() == null || form.redirectTarget().isBlank()) {
                binding.rejectValue("redirectTarget", "validation.edge.site.redirect.required");
            }
        }
        if (binding.hasErrors()) {
            model.addAttribute("mode", "create");
            return "admin/edge/edge-site-form";
        }
        repo.save(EdgeSite.builder()
                .domain(form.domain())
                .siteType(form.siteType())
                .upstreamHost(blankToNull(form.upstreamHost()))
                .upstreamPort(form.upstreamPort())
                .bindAddress(blankToNull(form.bindAddress()))
                .redirectTarget(blankToNull(form.redirectTarget()))
                .redirectCode(blankToNull(form.redirectCode()))
                .tlsMode(form.tlsMode())
                .acmeEmail(blankToNull(form.acmeEmail()))
                .extraDirectives(blankToNull(form.extraDirectives()))
                .enabled(form.enabled())
                .build());
        events.publishEvent(new EdgeConfigChangedEvent("site added: " + form.domain()));
        flash.addFlashAttribute("successMessage", "flash.edge.site.created");
        return "redirect:/admin/edge/sites";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        EdgeSite s = repo.findById(id).orElse(null);
        if (s == null) {
            flash.addFlashAttribute("errorMessage", "error.edge.site.notfound");
            return "redirect:/admin/edge/sites";
        }
        model.addAttribute("form", new EdgeSiteForm(
                s.getDomain(), s.getSiteType(),
                s.getUpstreamHost(), s.getUpstreamPort(),
                s.getBindAddress(),
                s.getRedirectTarget(), s.getRedirectCode(),
                s.getTlsMode(), s.getAcmeEmail(),
                s.getExtraDirectives(), s.isEnabled()));
        model.addAttribute("mode", "edit");
        model.addAttribute("siteId", id);
        return "admin/edge/edge-site-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") EdgeSiteForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        EdgeSite s = repo.findById(id).orElse(null);
        if (s == null) {
            flash.addFlashAttribute("errorMessage", "error.edge.site.notfound");
            return "redirect:/admin/edge/sites";
        }
        repo.findByDomain(form.domain()).filter(other -> !other.getId().equals(id))
                .ifPresent(other -> binding.rejectValue("domain", "validation.edge.site.domain.taken"));
        if ("proxy".equals(form.siteType())) {
            if (form.upstreamHost() == null || form.upstreamHost().isBlank()) {
                binding.rejectValue("upstreamHost", "validation.edge.site.upstream.required");
            }
            if (form.upstreamPort() == null) {
                binding.rejectValue("upstreamPort", "validation.edge.site.upstream.required");
            }
        } else if ("redirect".equals(form.siteType())) {
            if (form.redirectTarget() == null || form.redirectTarget().isBlank()) {
                binding.rejectValue("redirectTarget", "validation.edge.site.redirect.required");
            }
        }
        if (binding.hasErrors()) {
            model.addAttribute("mode", "edit");
            model.addAttribute("siteId", id);
            return "admin/edge/edge-site-form";
        }
        repo.save(EdgeSite.builder()
                .domain(form.domain())
                .siteType(form.siteType())
                .upstreamHost(blankToNull(form.upstreamHost()))
                .upstreamPort(form.upstreamPort())
                .bindAddress(blankToNull(form.bindAddress()))
                .redirectTarget(blankToNull(form.redirectTarget()))
                .redirectCode(blankToNull(form.redirectCode()))
                .tlsMode(form.tlsMode())
                .acmeEmail(blankToNull(form.acmeEmail()))
                .extraDirectives(blankToNull(form.extraDirectives()))
                .enabled(form.enabled())
                .build());
        events.publishEvent(new EdgeConfigChangedEvent("site updated: " + form.domain()));
        flash.addFlashAttribute("successMessage", "flash.edge.site.updated");
        return "redirect:/admin/edge/sites";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable UUID id, RedirectAttributes flash) {
        repo.findById(id).ifPresent(s -> {
            s.setEnabled(!s.isEnabled());
            repo.save(s);
            events.publishEvent(new EdgeConfigChangedEvent("site toggled: " + s.getDomain()));
        });
        flash.addFlashAttribute("successMessage", "flash.edge.site.toggled");
        return "redirect:/admin/edge/sites";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        repo.findById(id).ifPresent(s -> {
            repo.delete(s);
            events.publishEvent(new EdgeConfigChangedEvent("site deleted: " + s.getDomain()));
        });
        flash.addFlashAttribute("successMessage", "flash.edge.site.deleted");
        return "redirect:/admin/edge/sites";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}