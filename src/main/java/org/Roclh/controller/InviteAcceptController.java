package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.InviteToken;
import org.Roclh.model.User;
import org.Roclh.model.dto.InviteAcceptForm;
import org.Roclh.service.InviteService;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/invite")
@RequiredArgsConstructor
public class InviteAcceptController {

    private final InviteService inviteService;
    private final UserService userService;
    private final SubscriptionService subscriptionService;

    @GetMapping("/{token}")
    public String show(@PathVariable String token,
                       Model model,
                       RedirectAttributes flash) {
        if (!inviteService.isUsable(token)) {
            flash.addFlashAttribute("errorMessage", "error.invite.unusable");
            return "redirect:/login";
        }
        model.addAttribute("token", token);
        model.addAttribute("form", new InviteAcceptForm("", ""));
        return "invite-accept";
    }

    @PostMapping("/{token}")
    public String accept(@PathVariable String token,
                         @Valid @ModelAttribute("form") InviteAcceptForm form,
                         BindingResult binding,
                         HttpServletRequest request,
                         Model model,
                         RedirectAttributes flash) {
        if (!inviteService.isUsable(token)) {
            flash.addFlashAttribute("errorMessage", "error.invite.unusable");
            return "redirect:/login";
        }
        if (userService.usernameTaken(form.username(), null)) {
            binding.rejectValue("username", "validation.username.taken");
        }
        if (binding.hasErrors()) {
            model.addAttribute("token", token);
            return "invite-accept";
        }

        InviteToken inv = inviteService.findByToken(token).orElseThrow();
        User newUser = userService.create(form.username(), form.password(), inv.getRole(), true);
        subscriptionService.getOrCreate(newUser.getId());
        inviteService.consume(token, newUser.getId());

        var auth = UsernamePasswordAuthenticationToken.authenticated(
                newUser.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + newUser.getRole())));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

        return "redirect:/home";
    }
}