package org.Roclh.service.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.Subscription;
import org.Roclh.model.XrayConfig;
import org.Roclh.repository.XrayConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class XrayConfigService {

    private final XrayConfigRepository repository;
    private final XrayRealityService realityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${xray.server.host:127.0.0.1}")
    private String publicHost;

    @Value("${xray.reality.dest:www.microsoft.com:443}")
    private String defaultDest;

    @Value("${xray.reality.server-names:www.microsoft.com}")
    private String defaultServerNamesCsv;

    // --- Чтение ---------------------------------------------------------------

    public Optional<XrayConfig> findActive() {
        return repository.findByActiveTrue();
    }

    public XrayConfig getActiveOrThrow() {
        return repository.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active Xray config"));
    }

    public List<XrayConfig> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<XrayConfig> findById(UUID id) {
        return repository.findById(id);
    }

    // --- Инициализация --------------------------------------------------------

    /** Если активного конфига нет — генерирует дефолтный Reality-конфиг. Требует установленный xray. */
    @Transactional
    public XrayConfig ensureDefault() {
        return repository.findByActiveTrue().orElseGet(() -> {
            try {
                XrayRealityService.KeyPair kp = realityService.generateKeyPair();
                String shortId = realityService.generateShortId();
                List<String> snis = List.of(defaultServerNamesCsv.split("\\s*,\\s*"));
                String content = buildDefaultContent(kp.privateKey(), shortId, defaultDest, snis);
                XrayConfig cfg = XrayConfig.builder()
                        .name("default")
                        .description("Auto-generated Reality config")
                        .content(content)
                        .realityPublicKey(kp.publicKey())
                        .active(true)
                        .build();
                log.info("Generated default Xray config (sni={}, dest={})", snis, defaultDest);
                return repository.save(cfg);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to generate default Xray config", e);
            }
        });
    }

    // --- CRUD -----------------------------------------------------------------

    @Transactional
    public XrayConfig create(String name, String description, String content, String realityPublicKey) {
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("Config with name '" + name + "' already exists");
        }
        return repository.save(XrayConfig.builder()
                .name(name)
                .description(description)
                .content(content)
                .realityPublicKey(blankToNull(realityPublicKey))
                .active(false)
                .build());
    }

    @Transactional
    public XrayConfig update(UUID id, String name, String description, String content, String realityPublicKey) {
        XrayConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        if (!cfg.getName().equals(name) && repository.existsByName(name)) {
            throw new IllegalArgumentException("Config with name '" + name + "' already exists");
        }
        cfg.setName(name);
        cfg.setDescription(description);
        cfg.setContent(content);
        cfg.setRealityPublicKey(blankToNull(realityPublicKey));
        return repository.save(cfg);
    }

    @Transactional
    public void delete(UUID id) {
        XrayConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        if (cfg.isActive()) {
            throw new IllegalStateException("Cannot delete the active config");
        }
        repository.delete(cfg);
    }

    @Transactional
    public XrayConfig activate(UUID id) {
        XrayConfig target = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        repository.findByActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(target.getId())) {
                current.setActive(false);
                repository.save(current);
            }
        });
        target.setActive(true);
        XrayConfig saved = repository.save(target);
        log.info("Activated Xray config '{}'", saved.getName());
        return saved;
    }

    /** Пробует вывести publicKey из privateKey внутри JSON, если поле reality_public_key пустое. */
    @Transactional
    public XrayConfig refreshRealityPublicKey(UUID id) throws IOException {
        XrayConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        JsonNode root = objectMapper.readTree(cfg.getContent());
        String privateKey = extractRealityPrivateKey(root);
        if (privateKey == null || privateKey.isBlank()) {
            cfg.setRealityPublicKey(null);
            return repository.save(cfg);
        }
        XrayRealityService.KeyPair kp = realityService.deriveKeyPair(privateKey);
        cfg.setRealityPublicKey(kp.publicKey());
        return repository.save(cfg);
    }

    // --- Генерация vless-ссылок ----------------------------------------------

    public List<String> buildVlessLinks(Subscription subscription) {
        if (subscription == null
                || subscription.getXrayUuid() == null
                || subscription.getXrayUuid().isBlank()) {
            log.warn("Subscription has no xray UUID, cannot build links");
            return List.of();
        }
        try {
            XrayConfig cfg = repository.findByActiveTrue().orElse(null);
            if (cfg == null) {
                log.warn("No active Xray config, cannot build vless links");
                return List.of();
            }
            JsonNode root = objectMapper.readTree(cfg.getContent());
            return parseLinks(root, subscription.getXrayUuid(), cfg.getRealityPublicKey());
        } catch (Exception e) {
            log.error("Failed to build vless links", e);
            return List.of();
        }
    }

    private List<String> parseLinks(JsonNode root, String uuid, String realityPublicKey) {
        List<String> result = new ArrayList<>();
        for (JsonNode inbound : root.path("inbounds")) {
            if (!"vless".equals(inbound.path("protocol").asString())) continue;

            int port = inbound.path("port").asInt();
            JsonNode stream = inbound.path("streamSettings");
            String network = stream.path("network").asString("tcp");
            String security = stream.path("security").asString("none");
            JsonNode reality = stream.path("realitySettings");
            boolean isReality = "reality".equals(security);
            boolean isXhttp = "xhttp".equals(network);

            String sni = reality.path("serverNames").path(0).asString("");
            String shortId = reality.path("shortIds").path(0).asString("");
            String pubKey = reality.path("publicKey").asString("");
            if (isReality && pubKey.isBlank() && realityPublicKey != null) {
                pubKey = realityPublicKey;
            }

            JsonNode xhttp = stream.path("xhttpSettings");
            String xhttpPath = xhttp.path("path").asString("");
            String xhttpHost = xhttp.path("host").asString("");
            String xhttpMode = xhttp.path("mode").asString("");

            StringBuilder link = new StringBuilder("vless://")
                    .append(uuid).append("@")
                    .append(publicHost).append(":").append(port)
                    .append("?type=").append(network)
                    .append("&encryption=none");

            if (isXhttp) {
                if (!xhttpPath.isBlank()) link.append("&path=").append(encode(xhttpPath));
                if (!xhttpHost.isBlank()) link.append("&host=").append(encode(xhttpHost));
                if (!xhttpMode.isBlank()) link.append("&mode=").append(xhttpMode);
            }

            if (isReality) {
                String fp = reality.path("fingerprint").asString("chrome");
                String spx = reality.path("spiderX").asString("");
                String alpn = reality.path("alpn").asString("");

                link.append("&security=reality")
                        .append("&pbk=").append(encode(pubKey))
                        .append("&fp=").append(fp)
                        .append("&sni=").append(encode(sni))
                        .append("&sid=").append(shortId);
                if (!spx.isBlank()) link.append("&spx=").append(encode(spx));
                if (!alpn.isBlank()) link.append("&alpn=").append(encode(alpn));
                if ("tcp".equals(network)) {
                    link.append("&flow=xtls-rprx-vision");
                }
            } else {
                link.append("&security=none");
            }

            link.append("#Pohr");
            result.add(link.toString());
        }
        return result;
    }

    private static String extractRealityPrivateKey(JsonNode root) {
        for (JsonNode inbound : root.path("inbounds")) {
            JsonNode reality = inbound.path("streamSettings").path("realitySettings");
            String pk = reality.path("privateKey").asString("");
            if (!pk.isBlank()) return pk;
        }
        return null;
    }

    private String buildDefaultContent(String privateKey, String shortId, String dest, List<String> snis) {
        String sniList = snis.stream()
                .map(n -> "\"" + n + "\"")
                .collect(Collectors.joining(", "));
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [
                    {
                      "listen": "0.0.0.0",
                      "port": 8443,
                      "protocol": "vless",
                      "settings": {
                        "clients": [],
                        "decryption": "none"
                      },
                      "streamSettings": {
                        "network": "tcp",
                        "security": "reality",
                        "realitySettings": {
                          "dest": "%s",
                          "serverNames": [%s],
                          "privateKey": "%s",
                          "shortIds": ["%s"]
                        }
                      }
                    }
                  ],
                  "outbounds": [
                    { "protocol": "freedom", "tag": "direct" }
                  ]
                }
                """.formatted(dest, sniList, privateKey, shortId);
    }

    @Transactional
    public XrayConfig clone(UUID id) {
        XrayConfig src = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        String base = src.getName() + "-copy";
        String name = base;
        int n = 2;
        while (repository.existsByName(name)) {
            name = base + "-" + n++;
        }
        return repository.save(XrayConfig.builder()
                .name(name)
                .description(src.getDescription())
                .content(src.getContent())
                .realityPublicKey(src.getRealityPublicKey())
                .active(false)
                .build());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}