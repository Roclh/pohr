package org.Roclh.controller.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EnrollmentToken;
import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.Roclh.model.dto.EnrollmentRequest;
import org.Roclh.model.dto.EnrollmentResponse;
import org.Roclh.model.dto.NodeHealthRequest;
import org.Roclh.service.ClientScriptService;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.xray.XrayInstaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/nodes")
@RequiredArgsConstructor
public class EuNodeApiController {

    private final EuNodeService nodeService;
    private final EuConfigService configService;
    private final ClientScriptService scriptService;
    private final XrayInstaller installer;

    @Value("${pohr.public-url:}")
    private String publicUrlOverride;

    @Value("${pohr.ssh.authorized-keys:}")
    private String sshAuthorizedKeys;

    @Value("${pohr.eu-nodes.xray-version:latest}")
    private String defaultXrayVersion;

    @Value("${pohr.eu-nodes.default-port:8443}")
    private int defaultNodePort;

    // --- Bootstrap скрипта ----------------------------------------------------

    @GetMapping(value = "/agent.sh", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> agentScript() {
        if (!scriptService.exists("pohr-agent.sh")) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Script 'pohr-agent.sh' not found.".getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] bytes = scriptService.renderBytes("pohr-agent.sh", Map.of());
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"pohr-agent.sh\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Render failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping(value = "/bootstrap.sh", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> bootstrap(@RequestParam("token") String token,
                                            HttpServletRequest request) {
        if (!nodeService.isTokenUsable(token)) {
            return ResponseEntity.status(410)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Enrollment token not found, used or expired.".getBytes(StandardCharsets.UTF_8));
        }
        if (!scriptService.exists("eu-node-setup.sh")) {
            return ResponseEntity.status(503)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Script 'eu-node-setup.sh' not found.".getBytes(StandardCharsets.UTF_8));
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("ENROLL_TOKEN", token);
        vars.put("RU_URL", resolveBaseUrl(request));
        vars.put("SSH_KEYS", sshAuthorizedKeys == null ? "" : sshAuthorizedKeys);
        vars.put("XRAY_VERSION",
                installer.installedVersion() != null
                        ? installer.installedVersion()
                        : defaultXrayVersion);
        vars.put("NODE_PORT", String.valueOf(defaultNodePort));
        try {
            byte[] bytes = scriptService.renderBytes("eu-node-setup.sh", vars);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"pohr-eu-setup.sh\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Render failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- Register -------------------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody EnrollmentRequest req) {
        try {
            EnrollmentToken token = nodeService.consumeToken(req.token());
            EuNode node = nodeService.registerOrUpdate(
                    token, req.host(), req.port(), req.publicKey(), req.shortId(),
                    req.xrayVersion(), req.agentVersion());
            String config = configService.buildEuConfig(node);
            String hash = configService.computeHash(config);
            nodeService.updateConfigHash(node.getId(), hash);
            return ResponseEntity.ok(new EnrollmentResponse(
                    node.getId(), node.getNodeSecret(), config, hash, 300,
                    installer.installedVersion()));
        } catch (IllegalArgumentException e) {
            log.warn("Enrollment failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // --- Config polling -------------------------------------------------------

    @GetMapping(value = "/{id}/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> config(@PathVariable UUID id,
                                         @RequestHeader(value = "X-Node-Secret", required = false) String secret,
                                         @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        Optional<EuNode> nodeOpt = nodeService.findByNodeSecret(id, secret);
        if (nodeOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        EuNode node = nodeOpt.get();
        String config = configService.buildEuConfig(node);
        String hash = configService.computeHash(config);
        String ruXrayVersion = installer.installedVersion();
        if (hash.equals(ifNoneMatch)) {
            return ResponseEntity.status(304)
                    .header("ETag", hash)
                    .header("X-Pohr-Xray-Version", ruXrayVersion == null ? "" : ruXrayVersion)
                    .build();
        }
        nodeService.updateConfigHash(id, hash);
        return ResponseEntity.ok()
                .header("ETag", hash)
                .header("X-Pohr-Xray-Version", ruXrayVersion == null ? "" : ruXrayVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(config);
    }

    // --- Health ---------------------------------------------------------------

    @PostMapping("/{id}/health")
    public ResponseEntity<Void> health(@PathVariable UUID id,
                                       @RequestHeader(value = "X-Node-Secret", required = false) String secret,
                                       @RequestBody NodeHealthRequest req) {
        Optional<EuNode> nodeOpt = nodeService.findByNodeSecret(id, secret);
        if (nodeOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        NodeStatus status = parseStatus(req.status());
        nodeService.updateHealth(id, status, null, req.xrayVersion(), req.configHash());
        return ResponseEntity.ok().build();
    }

    // --- Helpers --------------------------------------------------------------

    private NodeStatus parseStatus(String s) {
        try {
            return NodeStatus.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return NodeStatus.UNREACHABLE;
        }
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