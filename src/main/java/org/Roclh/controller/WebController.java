package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.model.dto.telegram.TelegramProxyUserDto;
import org.Roclh.repository.telegram.TelegramProxyUserRepository;
import org.Roclh.service.DeviceDetector;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.Roclh.service.script.ScriptUpdateService;
import org.Roclh.service.telegram.TelegramProxyService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final PublicUrlResolver publicUrlResolver;
    private final TelegramProxyService telegramProxyService;
    private final TelegramProxyUserRepository telegramProxyUserRepository;
    private final ScriptUpdateService scriptUpdateService;

    @GetMapping("/")
    public String root() {
        return "redirect:/home";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/home")
    public String home(Authentication authentication,
                       Model model,
                       HttpServletRequest request,
                       @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        model.addAttribute("username", authentication.getName());

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("isMobile", DeviceDetector.isMobile(userAgent));

        User user = userService.findByUsername(authentication.getName()).orElse(null);
        if (user != null) {
            Subscription sub = subscriptionService.getOrCreate(user.getId());
            String base = publicUrlResolver.resolve(request);
            String subUrl = base + "/sub/" + sub.getToken();

            model.addAttribute("subscriptionUrl", subUrl);
            model.addAttribute("subscriptionEnabled", sub.isEnabled());
            model.addAttribute("subscriptionToken", sub.getToken());

            String encoded = URLEncoder.encode(subUrl, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            model.addAttribute("happLink", "happ://add/" + encoded);

            String encodedNg = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(subUrl.getBytes(StandardCharsets.UTF_8));
            model.addAttribute("v2rayNgLink",
                    "v2rayng://install-sub?url=" + encodedNg + "&name=Pohr");

            model.addAttribute("streisandLink", "streisand://import/" + encoded);
            model.addAttribute("karingLink", "karing://install-config?url=" + encoded);

            String b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(subUrl.getBytes(StandardCharsets.UTF_8));
            model.addAttribute("shadowrocketLink", "sub://" + b64);

            telegramProxyUserRepository.findById(user.getId())
                    .filter(tg -> tg.isEnabled() && tg.getSecret() != null)
                    .ifPresent(tg -> {
                        TelegramProxyUserDto dto = telegramProxyService.toUserDto(tg);
                        model.addAttribute("tgProxyUrl", dto.proxyUrl());
                        model.addAttribute("tgWebProxyUrl", dto.webProxyUrl());
                    });
            String scriptPlatform = DeviceDetector.isMobile(userAgent) ? "any" : "windows";
            Map<String, ScriptUpdateService.EntrypointUpdates> updates = new HashMap<>();
            for (var u : scriptUpdateService.checkForPlatform(user.getId(), scriptPlatform, isAdmin)) {
                updates.put(u.entrypointName(), u);
            }
            model.addAttribute("updates", updates);
        }
        return "home";
    }

    @GetMapping("/admin")
    public String admin(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        return "admin";
    }
}