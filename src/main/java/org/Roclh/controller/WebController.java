package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final UserService userService;
    private final SubscriptionService subscriptionService;

    @GetMapping("/")
    public String root() {
        return "redirect:/home";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/home")
    public String home(Authentication authentication, Model model, HttpServletRequest request) {
        model.addAttribute("username", authentication.getName());

        User user = userService.findByUsername(authentication.getName()).orElse(null);
        if (user != null) {
            Subscription sub = subscriptionService.getOrCreate(user.getId());
            String host = request.getServerName();
            if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) {
                host = "127.0.0.1";
            }
            int port = request.getServerPort();
            String url = "http://" + host + ":" + port + "/sub/" + sub.getToken();
            model.addAttribute("subscriptionUrl", url);
            model.addAttribute("subscriptionEnabled", sub.isEnabled());
        }
        return "home";
    }

    @GetMapping("/admin")
    public String admin(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "admin";
    }
}