package org.Roclh.controller;

import lombok.RequiredArgsConstructor;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.xray.XrayConfigService;
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

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> subscription(@PathVariable String token) {
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
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Profile-Title", "Pohr")
                .header("Profile-Update-Interval", "6")
                .header("Subscription-Userinfo", "upload=0; download=0; total=0; expire=0")
                .body(encoded);
    }
}