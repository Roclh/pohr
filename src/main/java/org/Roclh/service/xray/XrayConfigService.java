package org.Roclh.service.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class XrayConfigService {

    @Value("${xray.home}")
    private String xrayHome;

    @Value("${xray.server.host:127.0.0.1}")
    private String publicHost;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<String> buildVlessLinks() {
        try {
            Path config = Path.of(xrayHome, "config", "config.json");
            if (!Files.exists(config)) {
                log.warn("Xray config not found at {}", config);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(Files.readString(config));
            JsonNode inbounds = root.path("inbounds");
            List<String> result = new ArrayList<>();
            for (JsonNode inbound : inbounds) {
                if (!"vless".equals(inbound.path("protocol").asText())) continue;
                int port = inbound.path("port").asInt();
                for (JsonNode client : inbound.path("settings").path("clients")) {
                    String uuid = client.path("id").asText();
                    if (uuid.isBlank()) continue;
                    String link = "vless://" + uuid + "@" + publicHost + ":" + port
                            + "?type=tcp&security=none&encryption=none#Pohr";
                    result.add(link);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to parse Xray config", e);
            return List.of();
        }
    }
}
