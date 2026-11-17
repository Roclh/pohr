package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.ClientConfigService;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.xray.XrayConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Slf4j
@RestController
@RequestMapping("/sub")
@RequiredArgsConstructor
public class SubscriptionController {

    /** Нейтральный заголовок: не бренд и не хост панели. */
    static final String PROFILE_TITLE = "Subscription";
    private static final String BRAND_MARKER = "pohr";

    private final SubscriptionService subscriptionService;
    private final XrayConfigService xrayConfigService;
    private final ClientConfigService clientConfigService;
    private final PublicUrlResolver publicUrlResolver;

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> subscription(
            @PathVariable String token,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest request) {

        var subscription = subscriptionService.findByToken(token).orElse(null);
        if (subscription == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> links = xrayConfigService.buildVlessLinks(subscription);
        if (links.isEmpty()) {
            return ResponseEntity.status(503).body("# No servers configured yet");
        }
        String body = String.join("\n", links);
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        headers.set("Profile-Title", PROFILE_TITLE);
        headers.set("Profile-Update-Interval", "6");

        long totalBytes = 1024L * 1024 * 1024 * 1024; // 1 TiB — везде показывается как «не ограничено»
        long expireUnix = Instant.now().plus(Duration.ofDays(3650)).getEpochSecond();
        headers.set("Subscription-Userinfo",
                "upload=0; download=0; total=" + totalBytes + "; expire=" + expireUnix);

        String subUrl = publicUrlResolver.resolve(request) + "/sub/" + token;
        clientConfigService.findByUserAgent(userAgent).ifPresent(cfg -> {
            clientConfigService.parseHeaders(cfg.getHeaders())
                    .forEach((name, value) -> {
                        String rendered = substitute(value, token, subUrl);
                        if (revealsIdentity(rendered, token, subUrl)) {
                            log.debug("Subscription header '{}' dropped: it leaks brand, host or token", name);
                            return;
                        }
                        headers.set(name, rendered);
                    });
        });

        return ResponseEntity.ok().headers(headers).body(encoded);
    }

    /**
     * Заголовок не должен быть отпечатком: ни бренда, ни домена панели, ни
     * токена подписки. Иначе ответ на {@code /sub/**} сам рассказывает, что
     * это за сервис и где он живёт, — ровно то, что снимается снаружи.
     */
    private static boolean revealsIdentity(String value, String token, String subUrl) {
        if (value == null) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains(BRAND_MARKER)) {
            return true;
        }
        if (token != null && !token.isBlank() && value.contains(token)) {
            return true;
        }
        String host = hostOf(subUrl);
        return host != null && lower.contains(host);
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String substitute(String value, String token, String subUrl) {
        return value
                .replace("{{TOKEN}}", token)
                .replace("{{SUB_URL}}", subUrl);
    }
}