package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.Roclh.model.XrayConfig;
import org.Roclh.model.dto.ClientTrafficRow;
import org.Roclh.model.dto.MetricsSnapshot;
import org.Roclh.model.dto.TrafficSummary;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.NginxProcessManager;
import org.Roclh.service.metric.MetricsService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.telegram.TelemtProcessManager;
import org.Roclh.service.traffic.ClientTrafficService;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/**
 * Диагностика для агента: состояние процессов, хвосты логов, трафик клиентов,
 * EU-ноды. Всё read-only — эти инструменты ничего не меняют.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpDiagnosticsTools implements McpToolModule {

    private final XrayProcessManager xray;
    private final TelemtProcessManager telemt;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;
    private final XrayInstaller xrayInstaller;
    private final MetricsService metricsService;
    private final EuNodeService euNodeService;
    private final ClientTrafficService clientTrafficService;
    private final XrayConfigService xrayConfigService;

    @Override
    public ArrayNode definitions() {
        ArrayNode tools = McpToolSupport.JSON.createArrayNode();
        tools.add(McpToolSupport.tool("server_status",
                "Сводка состояния сервера: процессы Xray/telemt/nginx/caddy, последние метрики CPU/RAM, трафик клиентов, EU-ноды, активный Xray-конфиг.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("logs_tail",
                "Последние строки лога процесса. Полезно для диагностики без доступа к контейнеру.",
                """
                {"type":"object","properties":{
                  "service":{"type":"string","enum":["xray","telemt","nginx","caddy"]},
                  "limit":{"type":"integer","description":"1..500, по умолчанию 100"}
                },"required":["service"],"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("traffic_overview",
                "Трафик клиентов: суммы за сегодня, активные соединения/клиенты и топ клиентов за 7 дней.",
                """
                {"type":"object","properties":{
                  "limit":{"type":"integer","description":"Сколько клиентов вернуть, 1..100. По умолчанию 20"}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("nodes_list",
                "EU-ноды: статус, версия Xray, последний health, последняя проверка туннеля.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && (toolName.equals("server_status")
                || toolName.equals("logs_tail")
                || toolName.equals("traffic_overview")
                || toolName.equals("nodes_list"));
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "server_status" -> McpToolSupport.ok(serverStatus());
                case "logs_tail" -> McpToolSupport.ok(logsTail(args));
                case "traffic_overview" -> McpToolSupport.ok(trafficOverview(args));
                case "nodes_list" -> McpToolSupport.ok(nodesList());
                default -> McpToolSupport.fail("Неизвестный диагностический инструмент: " + toolName);
            };
        } catch (Exception e) {
            log.warn("MCP diagnostics tool '{}' failed for {}: {}", toolName, actor, e.getMessage(), e);
            return McpToolSupport.fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // server_status
    // =====================================================================

    private ObjectNode serverStatus() {
        ObjectNode out = McpToolSupport.JSON.createObjectNode();

        ObjectNode processes = out.putObject("processes");
        processStatus(processes, "xray", xray.isRunning(), xray.pid(), xrayInstaller.installedVersion());
        processStatus(processes, "telemt", telemt.isRunning(), telemt.pid(), null);
        processStatus(processes, "nginx", nginx.isRunning(), nginx.pid(), null);
        processStatus(processes, "caddy", caddy.isRunning(), caddy.pid(), null);

        MetricsSnapshot latest = metricsService.latest();
        if (latest != null) {
            ObjectNode metrics = out.putObject("metrics");
            metrics.put("ts", latest.ts());
            metrics.put("podCpuPct", latest.podCpuPct());
            metrics.put("podMemBytes", latest.podMemBytes());
            metrics.put("podMemLimitBytes", latest.podMemLimitBytes());
            metrics.put("xrayMemBytes", latest.xrayMemBytes());
            metrics.put("xrayCpuPct", latest.xrayCpuPct());
            metrics.put("telemtMemBytes", latest.telemtMemBytes());
            metrics.put("jvmHeapBytes", latest.jvmHeapBytes());
            metrics.put("jvmThreads", latest.jvmThreads());
        } else {
            out.putNull("metrics");
        }

        TrafficSummary traffic = clientTrafficService.summary();
        ObjectNode trafficNode = out.putObject("traffic");
        trafficNode.put("upToday", traffic.upToday());
        trafficNode.put("downToday", traffic.downToday());
        trafficNode.put("activeConnections", traffic.activeConnections());
        trafficNode.put("activeUsers", traffic.activeUsers());
        trafficNode.put("clientsToday", traffic.clientsToday());

        List<EuNode> nodes = euNodeService.findAll();
        ObjectNode nodesNode = out.putObject("euNodes");
        nodesNode.put("total", nodes.size());
        nodesNode.put("healthy", nodes.stream().filter(n -> n.getStatus() == NodeStatus.HEALTHY).count());
        nodesNode.put("degraded", nodes.stream().filter(n -> n.getStatus() == NodeStatus.DEGRADED).count());
        nodesNode.put("unreachable", nodes.stream().filter(n -> n.getStatus() == NodeStatus.UNREACHABLE).count());

        XrayConfig active = xrayConfigService.findAll().stream()
                .filter(XrayConfig::isActive).findFirst().orElse(null);
        if (active != null) {
            ObjectNode cfg = out.putObject("activeXrayConfig");
            cfg.put("id", active.getId().toString());
            cfg.put("name", active.getName());
            cfg.put("updatedAt", active.getUpdatedAt() == null ? null : active.getUpdatedAt().toString());
        } else {
            out.putNull("activeXrayConfig");
        }
        return out;
    }

    private static void processStatus(ObjectNode parent, String name,
                                      boolean running, Long pid, String version) {
        ObjectNode node = parent.putObject(name);
        node.put("running", running);
        if (pid == null) node.putNull("pid"); else node.put("pid", pid);
        McpToolSupport.putNullable(node, "version", version);
    }

    // =====================================================================
    // logs_tail
    // =====================================================================

    private ObjectNode logsTail(ObjectNode args) {
        String service = McpToolSupport.reqText(args, "service").toLowerCase(Locale.ROOT);
        int limit = McpToolSupport.optInt(args, "limit", 100, 1, 500);

        List<String> lines = switch (service) {
            case "xray" -> xray.recentLogs(limit);
            case "telemt" -> telemt.recentLogs(limit);
            case "nginx" -> nginx.recentLogs(limit);
            case "caddy" -> caddy.recentLogs(limit);
            default -> throw new IllegalArgumentException(
                    "service должен быть xray, telemt, nginx или caddy");
        };

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("service", service);
        out.put("running", switch (service) {
            case "xray" -> xray.isRunning();
            case "telemt" -> telemt.isRunning();
            case "nginx" -> nginx.isRunning();
            default -> caddy.isRunning();
        });
        ArrayNode arr = out.putArray("lines");
        lines.forEach(arr::add);
        out.put("count", arr.size());
        out.put("note", "Лог хранится в памяти процесса с момента старта; после рестарта история пустая.");
        return out;
    }

    // =====================================================================
    // traffic_overview
    // =====================================================================

    private ObjectNode trafficOverview(ObjectNode args) {
        int limit = McpToolSupport.optInt(args, "limit", 20, 1, 100);
        TrafficSummary summary = clientTrafficService.summary();
        List<ClientTrafficRow> rows = clientTrafficService.overview();

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        ObjectNode totals = out.putObject("today");
        totals.put("up", summary.upToday());
        totals.put("down", summary.downToday());
        totals.put("activeConnections", summary.activeConnections());
        totals.put("activeUsers", summary.activeUsers());
        totals.put("clientsWithTraffic", summary.clientsToday());

        ArrayNode clients = out.putArray("clients");
        rows.stream().limit(limit).forEach(row -> {
            ObjectNode node = clients.addObject();
            node.put("username", row.username());
            node.put("upToday", row.upToday());
            node.put("downToday", row.downToday());
            node.put("up7d", row.upWeek());
            node.put("down7d", row.downWeek());
            node.put("lastSeen", row.lastSeen());
            node.put("active", row.active());
        });
        out.put("returned", clients.size());
        out.put("totalClients", rows.size());
        return out;
    }

    // =====================================================================
    // nodes_list
    // =====================================================================

    private ObjectNode nodesList() {
        List<EuNode> nodes = euNodeService.findAll();
        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        ArrayNode arr = out.putArray("nodes");
        for (EuNode node : nodes) {
            ObjectNode n = arr.addObject();
            n.put("id", node.getId().toString());
            n.put("name", node.getName());
            n.put("host", node.getHost());
            n.put("port", node.getPort());
            n.put("status", node.getStatus() == null ? null : node.getStatus().name());
            McpToolSupport.putNullable(n, "xrayVersion", node.getXrayVersion());
            McpToolSupport.putNullable(n, "agentVersion", node.getAgentVersion());
            McpToolSupport.putNullable(n, "lastHealthAt",
                    node.getLastHealthAt() == null ? null : node.getLastHealthAt().toString());
            McpToolSupport.putNullable(n, "lastHealthMsg", node.getLastHealthMsg());
            McpToolSupport.putNullable(n, "lastTunnelIp", node.getLastTunnelIp());
            McpToolSupport.putNullable(n, "lastTunnelCheckAt",
                    node.getLastTunnelCheckAt() == null ? null : node.getLastTunnelCheckAt().toString());
        }
        out.put("count", arr.size());
        return out;
    }
}
