package org.Roclh.controller;

import lombok.RequiredArgsConstructor;
import org.Roclh.service.SubscriptionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sub")
@RequiredArgsConstructor
public class RoutingRulesController {

    private final SubscriptionService subscriptionService;

    @GetMapping(value = "/{token}/rules.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rules(@PathVariable String token) {
        if (subscriptionService.findByToken(token).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String body = """
                {
                  "domainStrategy": "IPIfNonMatch",
                  "rules": [
                    {
                      "type": "field",
                      "outboundTag": "direct",
                      "domain": ["geosite:category-ru", "geosite:private"]
                    },
                    {
                      "type": "field",
                      "outboundTag": "direct",
                      "ip": ["geoip:ru", "geoip:private"]
                    }
                  ]
                }
                """;
        return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body);
    }
}