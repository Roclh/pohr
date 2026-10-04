package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.Roclh.service.ClientConfigService;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.xray.XrayConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/sub")
@RequiredArgsConstructor
public class SubscriptionController {

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
        headers.set("Profile-Title", "Pohr");
        headers.set("Profile-Update-Interval", "6");
        headers.set("Subscription-Userinfo", "upload=0; download=0; total=0; expire=0");

        String subUrl = publicUrlResolver.resolve(request) + "/sub/" + token;
        clientConfigService.findByUserAgent(userAgent).ifPresent(cfg -> {
            clientConfigService.parseHeaders(cfg.getHeaders())
                    .forEach((name, value) ->
                            headers.set(name, substitute(value, token, subUrl)));
        });

        return ResponseEntity.ok().headers(headers).body(encoded);
    }

    private static String substitute(String value, String token, String subUrl) {
        return value
                .replace("{{TOKEN}}", token)
                .replace("{{SUB_URL}}", subUrl);
    }
}