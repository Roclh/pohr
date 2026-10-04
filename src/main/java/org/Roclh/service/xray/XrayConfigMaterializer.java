package org.Roclh.service.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.Subscription;
import org.Roclh.model.XrayConfig;
import org.Roclh.repository.SubscriptionRepository;
import org.Roclh.repository.telegram.TelegramProxyConfigRepository;
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
    private final TelegramProxyConfigRepository telegramProxyConfigRepository;
    private final EuNodeService euNodeService;
    private final EuConfigService euConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${xray.home}")
    private String xrayHome;

    @Value("${pohr.telegram.socks-port:10809}")
    private int telemtSocksPort;

    public Path configPath() {
        return Path.of(xrayHome, "config", "config.json");
    }

    /** Выгружает активный конфиг из БД в файл, подставив клиентов, EU-outbound и telemt-socks. */
    public synchronized void materialize() {
        XrayConfig cfg = configService.getActiveOrThrow();
        Path path = configPath();
        try {
            JsonNode root = objectMapper.readTree(cfg.getContent());
            injectEuOutbound(root);
            injectTelemtSocksInbound(root);
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

    /** Если TG-прокси включён — добавляет SOCKS-inbound, через который ходит telemt. */
    private void injectTelemtSocksInbound(JsonNode root) {
        if (!(root instanceof ObjectNode rootObj)) return;

        boolean enabled = telegramProxyConfigRepository.findById("default")
                .map(c -> c.isEnabled())
                .orElse(false);
        if (!enabled) {
            // Если ранее был добавлен — убираем
            ArrayNode inbounds = ensureArray(rootObj, "inbounds");
            removeByTag(inbounds, "telemt-socks");
            return;
        }

        // SOCKS-inbound имеет смысл только если есть EU-нода
        if (euNodeService.findCandidates().isEmpty()) {
            log.warn("Telegram proxy enabled, but no EU node — telemt-socks inbound skipped");
            return;
        }

        ArrayNode inbounds = ensureArray(rootObj, "inbounds");
        removeByTag(inbounds, "telemt-socks");
        inbounds.add(euConfigService.buildTelemtSocksInbound(telemtSocksPort));
        log.info("Injected telemt-socks inbound on 127.0.0.1:{}", telemtSocksPort);
    }

    private void injectClients(JsonNode root) {
        List<Subscription> subs = subscriptionRepository.findAllByEnabledTrue();

        for (JsonNode inbound : root.path("inbounds")) {
            if (!(inbound instanceof ObjectNode inboundObj)) continue;
            if (!"vless".equals(inboundObj.path("protocol").asText())) continue;

            JsonNode settingsNode = inboundObj.get("settings");
            if (!(settingsNode instanceof ObjectNode settings)) continue;

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
            if (item.path("tag").asText("").equals(tag)) {
                arr.remove(i);
            }
        }
    }
}