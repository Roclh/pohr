package org.Roclh.service.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Активная проверка RU→EU туннеля: раз в 5 минут делаем HTTP-запрос через SOCKS-inbound,
 * который материализатор Xray настроил на outbound "eu".
 *
 * Логика:
 *  - ответ == node.host  → HEALTHY
 *  - ответ != node.host  → DEGRADED (routing идёт не туда)
 *  - 3 подряд таймаута   → UNREACHABLE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TunnelHealthCheckService {

    private final EuNodeService nodeService;

    @Value("${pohr.eu-nodes.tunnel-socks-port:10808}")
    private int socksPort;

    @Value("${pohr.eu-nodes.tunnel-check-url:https://api.ipify.org}")
    private String checkUrl;

    @Value("${pohr.eu-nodes.tunnel-check-timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${pohr.eu-nodes.tunnel-check-failures-threshold:3}")
    private int failuresThreshold;

    private final Map<UUID, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private volatile String cachedRuIp;
    private volatile Instant ruIpFetchedAt;

    @Scheduled(
            fixedDelayString  = "${pohr.eu-nodes.tunnel-check-interval:300000}",
            initialDelayString = "${pohr.eu-nodes.tunnel-check-initial-delay:30000}")
    public void checkTunnel() {
        List<EuNode> candidates = nodeService.findCandidates();
        if (candidates.isEmpty()) {
            log.debug("No active EU nodes to health-check");
            return;
        }
        // Materializer добавляет только один eu outbound — для первой активной ноды.
        // Через SOCKS :10808 трафик идёт именно к ней, другие проверять бессмысленно.
        EuNode node = candidates.getFirst();

        String observedIp = probeThroughSocks();
        if (observedIp == null) {
            handleFailure(node, "no response via tunnel");
            return;
        }
        if (observedIp.equals(node.getHost())) {
            handleSuccess(node, observedIp);
            return;
        }
        String ruIp = getRuIp();
        String msg = "probe returned " + observedIp
                + (observedIp.equals(ruIp) ? " (RU bridge IP — routing broken)" : "");
        handleWrongIp(node, msg, observedIp);
    }

    // --- Probe ----------------------------------------------------------------

    private String probeThroughSocks() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", socksPort)))
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(checkUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Tunnel probe: HTTP {} from {} via SOCKS :{}",
                        resp.statusCode(), checkUrl, socksPort);
                return null;
            }
            return resp.body().trim();
        } catch (Exception e) {
            log.warn("Tunnel probe failed via SOCKS :{}: {}", socksPort, e.getMessage());
            return null;
        }
    }

    // --- Обработчики исходов --------------------------------------------------

    private void handleSuccess(EuNode node, String observedIp) {
        Integer previousFailures = consecutiveFailures.remove(node.getId());
        NodeStatus prev = node.getStatus();
        nodeService.updateTunnelCheck(node.getId(), NodeStatus.HEALTHY, observedIp);
        if (prev != NodeStatus.HEALTHY) {
            log.info("EU node '{}' tunnel OK (observed {}), status {} → HEALTHY",
                    node.getName(), observedIp, prev);
        } else if (previousFailures != null && previousFailures > 0) {
            log.info("EU node '{}' tunnel recovered after {} failures", node.getName(), previousFailures);
        } else {
            log.debug("EU node '{}' tunnel OK ({})", node.getName(), observedIp);
        }
    }

    private void handleWrongIp(EuNode node, String message, String observedIp) {
        consecutiveFailures.remove(node.getId());
        NodeStatus prev = node.getStatus();
        nodeService.updateTunnelCheck(node.getId(), NodeStatus.DEGRADED, observedIp);
        log.warn("EU node '{}' tunnel DEGRADED: {} (prev {})", node.getName(), message, prev);
    }

    private void handleFailure(EuNode node, String message) {
        int failures = consecutiveFailures.merge(node.getId(), 1, Integer::sum);
        if (failures >= failuresThreshold) {
            NodeStatus prev = node.getStatus();
            nodeService.updateTunnelCheck(node.getId(), NodeStatus.UNREACHABLE, null);
            log.error("EU node '{}' tunnel UNREACHABLE after {} failures ({})",
                    node.getName(), failures, message);
            if (prev == NodeStatus.HEALTHY || prev == NodeStatus.DEGRADED) {
                log.error("EU node '{}' dropped to UNREACHABLE — VPN clients may lose access. "
                                + "Проверь `systemctl status xray` и `/etc/xray/config.json` на EU ({})",
                        node.getName(), node.getHost());
            }
        } else {
            log.warn("EU node '{}' tunnel probe failure {}/{} ({})",
                    node.getName(), failures, failuresThreshold, message);
        }
    }

    // --- Диагностика: IP RU-моста (только для логов) --------------------------

    private String getRuIp() {
        Instant now = Instant.now();
        if (cachedRuIp != null && ruIpFetchedAt != null
                && Duration.between(ruIpFetchedAt, now).toHours() < 1) {
            return cachedRuIp;
        }
        try {
            HttpClient direct = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(checkUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = direct.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                cachedRuIp = resp.body().trim();
                ruIpFetchedAt = now;
                log.info("RU bridge public IP (diagnostics): {}", cachedRuIp);
            }
        } catch (Exception e) {
            log.debug("Cannot determine RU IP: {}", e.getMessage());
        }
        return cachedRuIp;
    }
}