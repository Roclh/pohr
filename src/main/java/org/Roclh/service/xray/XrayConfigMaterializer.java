package org.Roclh.service.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.Subscription;
import org.Roclh.model.XrayConfig;
import org.Roclh.repository.SubscriptionRepository;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class XrayConfigMaterializer {

    private final XrayConfigService configService;
    private final SubscriptionRepository subscriptionRepository;
    private final EuNodeService euNodeService;
    private final EuConfigService euConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${xray.home}")
    private String xrayHome;

    public Path configPath() {
        return Path.of(xrayHome, "config", "config.json");
    }

    /** Выгружает активный конфиг из БД в файл, подставив клиентов из подписок. */
    public synchronized void materialize() {
        XrayConfig cfg = configService.getActiveOrThrow();
        Path path = configPath();
        try {
            JsonNode root = objectMapper.readTree(cfg.getContent());
            injectEuOutbound(root);
            injectClients(root);
            String finalContent = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root);
            Files.createDirectories(path.getParent());
            Files.writeString(path, finalContent);
            log.info("Materialized active Xray config '{}' → {}", cfg.getName(), path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize Xray config to " + path, e);
        }
    }

    /** Если есть активная EU-нода — добавляет eu outbound, healthcheck inbound и routing. */
    private void injectEuOutbound(JsonNode root) {
        if (!(root instanceof ObjectNode rootObj)) return;

        List<EuNode> activeNodes = euNodeService.findCandidates();
        if (activeNodes.isEmpty()) {
            log.debug("No active EU node, running config as-is (freedom only)");
            return;
        }
        EuNode eu = activeNodes.getFirst();

        ArrayNode outbounds = ensureArray(rootObj, "outbounds");
        removeByTag(outbounds, "eu");
        outbounds.add(euConfigService.buildEuOutbound(eu));

        ArrayNode inbounds = ensureArray(rootObj, "inbounds");
        removeByTag(inbounds, "healthcheck-socks");
        inbounds.add(euConfigService.buildHealthcheckInbound());

        rootObj.set("routing", euConfigService.buildRouting());

        log.info("Injected EU outbound for node '{}' ({}:{})",
                eu.getName(), eu.getHost(), eu.getPort());
    }

    private static ArrayNode ensureArray(ObjectNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node instanceof ArrayNode arr) return arr;
        ArrayNode arr = obj.arrayNode();
        obj.set(field, arr);
        return arr;
    }

    private static void removeByTag(ArrayNode arr, String tag) {
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonNode item = arr.get(i);
            if (item.path("tag").asString("").equals(tag)) {
                arr.remove(i);
            }
        }
    }

    private void injectClients(JsonNode root) {
        List<Subscription> subs = subscriptionRepository.findAllByEnabledTrue();

        for (JsonNode inbound : root.path("inbounds")) {
            if (!(inbound instanceof ObjectNode inboundObj)) continue;
            if (!"vless".equals(inboundObj.path("protocol").asString())) continue;

            JsonNode settingsNode = inboundObj.get("settings");
            if (!(settingsNode instanceof ObjectNode settings)) continue;

            JsonNode stream = inboundObj.path("streamSettings");
            String network = stream.path("network").asString("tcp");
            String security = stream.path("security").asString("none");
            boolean tcpReality = "tcp".equals(network) && "reality".equals(security);

            ArrayNode clients = settings.putArray("clients");
            for (Subscription sub : subs) {
                if (sub.getXrayUuid() == null || sub.getXrayUuid().isBlank()) continue;
                ObjectNode client = objectMapper.createObjectNode();
                client.put("id", sub.getXrayUuid());
                client.put("email", sub.getId().toString());
                clients.add(client);
            }
            log.info("Injected {} client(s) into inbound on port {}",
                    clients.size(), inboundObj.path("port").asInt());
        }
    }
}