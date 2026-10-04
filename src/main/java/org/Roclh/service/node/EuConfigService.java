package org.Roclh.service.node;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
public class EuConfigService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${xray.reality.dest:www.microsoft.com:443}")
    private String defaultDest;

    @Value("${xray.reality.server-names:www.microsoft.com}")
    private String defaultServerNamesCsv;

    @Value("${pohr.eu-nodes.tunnel-socks-port:10808}")
    private int healthcheckSocksPort;

    /** SNI для RU→EU Reality handshake (первый из global defaults). */
    public String defaultSni() {
        return defaultServerNamesCsv.split("\\s*,\\s*")[0].trim();
    }

    /** EU-конфиг с плейсхолдером __USE_LOCAL__ — EU подставит свой privateKey. */
    public String buildEuConfig(EuNode node) {
        String sni = defaultSni();
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
                defaultDest, sni, node.getRealityShortId());
    }

    /** SHA-256 от компактной JSON-сериализации. */
    public String computeHash(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            String normalized = objectMapper.writeValueAsString(parsed);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute config hash", e);
        }
    }

    /** VLESS outbound VLESS → EU-нода. */
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
        reality.put("serverName", defaultSni());
        reality.put("publicKey", node.getRealityPublicKey());
        reality.put("shortId", node.getRealityShortId());
        reality.put("fingerprint", "chrome");

        return ob;
    }

    /** SOCKS inbound для health-check'а (RU-side проверка туннеля). */
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

    /** Правила роутинга: healthcheck → eu, geoip:ru/private → direct, всё остальное → eu. */
    public ObjectNode buildRouting() {
        ObjectNode routing = objectMapper.createObjectNode();
        routing.put("domainStrategy", "IPIfNonMatch");
        ArrayNode rules = routing.putArray("rules");

        ObjectNode r1 = rules.addObject();
        r1.put("type", "field");
        r1.put("outboundTag", "eu");
        r1.putArray("inboundTag").add("healthcheck-socks");

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
}