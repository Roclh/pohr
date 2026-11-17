package org.Roclh.service.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.TelegramProxyConfig;
import org.Roclh.repository.telegram.TelegramProxyConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class EuConfigService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelegramProxyConfigRepository telegramProxyConfigRepository;

    /**
     * SNI EU-inbound по умолчанию, если у узла нет профиля (Т06: свой домен
     * вместо чужого). Профиль узла всегда важнее этих значений.
     */
    @Value("${pohr.eu-nodes.reality.sni:eu.pohr-roclh.xyz}")
    private String defaultSniValue;

    /** decoy-таргет EU-inbound по умолчанию (Т06: локальный Caddy, а не чужой сайт). */
    @Value("${pohr.eu-nodes.reality.target:127.0.0.1:443}")
    private String defaultTargetValue;

    @Value("${pohr.eu-nodes.tunnel-socks-port:10808}")
    private int healthcheckSocksPort;

    /** SNI для RU→EU Reality handshake (первый из global defaults). */
    public String defaultSni() {
        return defaultSniValue.split("\\s*,\\s*")[0].trim();
    }

    /** Глобальный dest для EU-inbound (фолбэк, если у узла нет профиля). */
    public String defaultTarget() {
        return defaultTargetValue;
    }

    /** Глобальный fingerprint для RU-outbound (фолбэк). */
    public String defaultFingerprint() {
        return "firefox";
    }

    /**
     * Эффективные значения профиля узла: значение узла, иначе глобальный
     * дефолт. {@code null}/{@code blank} в узле — это «не задано», а не пустая
     * строка, поэтому старые узлы ведут себя ровно как до Т03.
     */
    public EffectiveProfile effectiveProfile(EuNode node) {
        String sni = firstNonBlank(node == null ? null : node.getSni(), defaultSni());
        String target = firstNonBlank(node == null ? null : node.getTarget(), defaultTarget());
        String fingerprint = firstNonBlank(node == null ? null : node.getFingerprint(), defaultFingerprint());
        return new EffectiveProfile(
                sni,
                target,
                fingerprint,
                node != null && !isBlank(node.getSni()),
                node != null && !isBlank(node.getTarget()),
                node != null && !isBlank(node.getFingerprint()));
    }

    /** EU-конфиг с плейсхолдером __USE_LOCAL__ — EU подставит свой privateKey. */
    public String buildEuConfig(EuNode node) {
        EffectiveProfile profile = effectiveProfile(node);
        return """
                {
                  "log": { "loglevel": "warning" },
                  "inbounds": [{
                    "listen": "0.0.0.0",
                    "port": %d,
                    "protocol": "vless",
                    "settings": {
                      "clients": [{ "id": "%s", "email": "ru-bridge" }],
                      "decryption": "none"
                    },
                    "streamSettings": {
                      "network": "tcp",
                      "security": "reality",
                      "realitySettings": {
                        "dest": "%s",
                        "serverNames": ["%s"],
                        "privateKey": "__USE_LOCAL__",
                        "shortIds": ["%s"]
                      }
                    }
                  }],
                  "outbounds": [{ "protocol": "freedom", "tag": "direct" }]
                }
                """.formatted(node.getPort(), node.getTunnelUuid(),
                profile.target(), profile.sni(), node.getRealityShortId());
    }

    /** SOCKS-inbound для healthcheck туннеля (RU-side проверка EU). */
    public ObjectNode buildHealthcheckInbound() {
        ObjectNode ib = objectMapper.createObjectNode();
        ib.put("listen", "127.0.0.1");
        ib.put("port", healthcheckSocksPort);
        ib.put("protocol", "socks");
        ib.put("tag", "healthcheck-socks");
        ObjectNode settings = ib.putObject("settings");
        settings.put("auth", "noauth");
        settings.put("udp", false);
        return ib;
    }

    /** SOCKS-inbound, через который telemt отправляет трафик в eu-outbound. */
    public ObjectNode buildTelemtSocksInbound(int port) {
        ObjectNode ib = objectMapper.createObjectNode();
        ib.put("listen", "127.0.0.1");
        ib.put("port", port);
        ib.put("protocol", "socks");
        ib.put("tag", "telemt-socks");
        ObjectNode settings = ib.putObject("settings");
        settings.put("auth", "noauth");
        settings.put("udp", true);
        return ib;
    }

    /** SHA-256 от компактной JSON-сериализации. */
    public String computeHash(String json) {
        try {
            var parsed = objectMapper.readTree(json);
            String normalized = objectMapper.writeValueAsString(parsed);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute config hash", e);
        }
    }

    /** VLESS outbound RU → EU. */
    public ObjectNode buildEuOutbound(EuNode node) {
        ObjectNode ob = objectMapper.createObjectNode();
        ob.put("protocol", "vless");
        ob.put("tag", "eu");

        ObjectNode settings = ob.putObject("settings");
        ObjectNode v = settings.putArray("vnext").addObject();
        v.put("address", node.getHost());
        v.put("port", node.getPort());
        ObjectNode user = v.putArray("users").addObject();
        user.put("id", node.getTunnelUuid().toString());
        user.put("encryption", "none");

        ObjectNode stream = ob.putObject("streamSettings");
        stream.put("network", "tcp");
        stream.put("security", "reality");
        ObjectNode reality = stream.putObject("realitySettings");
        EffectiveProfile profile = effectiveProfile(node);
        reality.put("serverName", profile.sni());
        reality.put("publicKey", node.getRealityPublicKey());
        reality.put("shortId", node.getRealityShortId());
        reality.put("fingerprint", profile.fingerprint());

        return ob;
    }

    /** Правила роутинга: healthcheck/telemt → eu, geoip:ru/private → direct, остальное → eu. */
    public ObjectNode buildRouting() {
        ObjectNode routing = objectMapper.createObjectNode();
        routing.put("domainStrategy", "IPIfNonMatch");
        ArrayNode rules = routing.putArray("rules");

        ObjectNode r1 = rules.addObject();
        r1.put("type", "field");
        r1.put("outboundTag", "eu");
        r1.putArray("inboundTag").add("healthcheck-socks");
        String coverDomain = telegramProxyConfigRepository.findById("default")
                .map(TelegramProxyConfig::getCoverDomain)
                .filter(d -> !d.isBlank())
                .orElse(null);

        if (coverDomain != null) {
            ObjectNode rCover = rules.addObject();
            rCover.put("type", "field");
            rCover.put("outboundTag", "direct");
            rCover.putArray("inboundTag").add("telemt-socks");
            rCover.putArray("domain").add(coverDomain);
        }

        ObjectNode rTelemt = rules.addObject();
        rTelemt.put("type", "field");
        rTelemt.put("outboundTag", "eu");
        rTelemt.putArray("inboundTag").add("telemt-socks");

        ObjectNode r2 = rules.addObject();
        r2.put("type", "field");
        r2.put("outboundTag", "direct");
        ArrayNode domains = r2.putArray("domain");
        domains.add("geosite:category-ru");
        domains.add("geosite:private");

        ObjectNode r3 = rules.addObject();
        r3.put("type", "field");
        r3.put("outboundTag", "direct");
        ArrayNode ips = r3.putArray("ip");
        ips.add("geoip:ru");
        ips.add("geoip:private");

        ObjectNode r4 = rules.addObject();
        r4.put("type", "field");
        r4.put("outboundTag", "eu");
        r4.put("network", "tcp,udp");

        return routing;
    }

    // --- Профиль (Т03) ---------------------------------------------------------

    /**
     * Реальные значения, которые уходят в конфиги. Источник каждого поля
     * важен для UI: админ должен видеть, что узел наследует глобальный дефолт.
     */
    public record EffectiveProfile(
            String sni,
            String target,
            String fingerprint,
            boolean sniFromNode,
            boolean targetFromNode,
            boolean fingerprintFromNode
    ) {
    }

    private static String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
