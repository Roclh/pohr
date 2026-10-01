package org.Roclh.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.model.dto.SubscriptionDto;
import org.Roclh.model.dto.SubscriptionForm;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;

    @GetMapping
    public String list(HttpServletRequest request, Model model) {
        List<SubscriptionDto> subs = subscriptionService.findAll().stream()
                .map(s -> toDto(s, request))
                .toList();
        model.addAttribute("subscriptions", subs);
        return "admin/subscriptions";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        List<User> users = userService.findAll().stream()
                .filter(u -> !subscriptionService.existsByUserId(u.getId()))
                .toList();
        model.addAttribute("users", users);
        model.addAttribute("form", new SubscriptionForm(null, true));
        return "admin/subscription-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") SubscriptionForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        if (form.userId() != null && subscriptionService.existsByUserId(form.userId())) {
            binding.rejectValue("userId", "validation.subscription.exists");
        }
        if (binding.hasErrors()) {
            model.addAttribute("users", userService.findAll());
            return "admin/subscription-form";
        }
        subscriptionService.create(form.userId(), form.enabled());
        flash.addFlashAttribute("successMessage", "flash.subscription.created");
        return "redirect:/admin/subscriptions";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            subscriptionService.toggle(id);
            flash.addFlashAttribute("successMessage", "flash.subscription.toggled");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/subscriptions";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            subscriptionService.delete(id);
            flash.addFlashAttribute("successMessage", "flash.subscription.deleted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/subscriptions";
    }

    private SubscriptionDto toDto(Subscription s, HttpServletRequest request) {
        String username = userService.findById(s.getUserId())
                .map(User::getUsername)
                .orElse("?");
        String host = request.getServerName();
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) {
            host = "127.0.0.1";
        }
        int port = request.getServerPort();
        String url = "http://" + host + ":" + port + "/sub/" + s.getToken();
        return new SubscriptionDto(
                s.getId(),
                s.getUserId(),
                username,
                s.getToken(),
                url,
                s.isEnabled(),
                s.getCreatedAt()
        );
    }
}