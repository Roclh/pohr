package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.Roclh.service.ClientScriptService;
import org.Roclh.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequestMapping("/sub")
@RequiredArgsConstructor
public class ClientSetupController {

    private final SubscriptionService subscriptionService;
    private final ClientScriptService scriptService;

    @Value("${pohr.public-url:}")
    private String publicUrlOverride;

    @GetMapping(value = "/{token}/setup.bat", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> setupBat(@PathVariable String token,
                                           HttpServletRequest request) throws IOException {
        if (subscriptionService.findByToken(token).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!scriptService.exists("v2rayn-setup.bat")) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Script 'v2rayn-setup.bat' not found.".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = scriptService.renderBytes("v2rayn-setup.bat", buildVars(token, request));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"pohr-v2rayn-setup.bat\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping(value = "/{token}/setup.ps1", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> setupPs1(@PathVariable String token,
                                           HttpServletRequest request) throws IOException {
        if (subscriptionService.findByToken(token).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!scriptService.exists("v2rayn-setup.ps1")) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Script 'v2rayn-setup.ps1' not found.".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = scriptService.renderBytes("v2rayn-setup.ps1", buildVars(token, request));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    private Map<String, String> buildVars(String token, HttpServletRequest request) {
        String base = resolveBaseUrl(request);
        String subUrl = base + "/sub/" + token;
        return Map.of(
                "SUBSCRIPTION_URL", subUrl,
                "RULES_URL", subUrl + "/rules.json",
                "PS1_URL", subUrl + "/setup.ps1",
                "BASE_URL", base,
                "TOKEN", token
        );
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (publicUrlOverride != null && !publicUrlOverride.isBlank()) {
            return publicUrlOverride.replaceAll("/$", "");
        }
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        String host = request.getServerName();
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) host = "127.0.0.1";
        return scheme + "://" + host + ":" + request.getServerPort();
    }
}