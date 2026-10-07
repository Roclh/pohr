package org.Roclh.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.InviteToken;
import org.Roclh.model.User;
import org.Roclh.model.dto.InviteForm;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.InviteService;
import org.Roclh.service.PublicUrlResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/admin/invites")
@RequiredArgsConstructor
public class AdminInviteController {

    private final InviteService inviteService;
    private final UserRepository userRepository;
    private final PublicUrlResolver publicUrlResolver;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("invites", inviteService.findAll());
        return "admin/invite/invites";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new InviteForm("USER", 10080));
        return "admin/invite/invite-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") InviteForm form,
                         BindingResult binding,
                         Authentication auth,
                         RedirectAttributes flash) {
        if (binding.hasErrors()) {
            return "admin/invite/invite-form";
        }
        UUID userId = userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + auth.getName()));
        InviteToken inv = inviteService.create(userId, form.role(), Duration.ofMinutes(form.ttlMinutes()));
        flash.addFlashAttribute("successMessage", "flash.invite.created");
        return "redirect:/admin/invites/" + inv.getToken();
    }

    @GetMapping("/{token}")
    public String detail(@PathVariable String token,
                         HttpServletRequest request,
                         Model model,
                         RedirectAttributes flash) {
        InviteToken inv = inviteService.findByToken(token).orElse(null);
        if (inv == null) {
            flash.addFlashAttribute("errorMessage", "error.invite.notfound");
            return "redirect:/admin/invites";
        }
        String base = publicUrlResolver.resolve(request);
        model.addAttribute("invite", inv);
        model.addAttribute("inviteUrl", base + "/invite/" + inv.getToken());
        return "admin/invite/invite-detail";
    }

    @PostMapping("/{token}/delete")
    public String delete(@PathVariable String token, RedirectAttributes flash) {
        try {
            inviteService.delete(token);
            flash.addFlashAttribute("successMessage", "flash.invite.deleted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/invites";
    }
}