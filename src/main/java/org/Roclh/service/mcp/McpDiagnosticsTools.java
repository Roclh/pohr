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
import org.Roclh.service.metric.ContainerMetrics;
import org.Roclh.service.metric.MetricsService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.process.OrphanProcessReaper;
import org.Roclh.service.process.ProcessTable;
import org.Roclh.service.telegram.TelemtProcessManager;
import org.Roclh.service.traffic.ClientTrafficService;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Диагностика для агента: состояние процессов, хвосты логов, трафик клиентов,
 * EU-ноды, память. Всё read-only — эти инструменты ничего не меняют.
 *
 * <p>Метрики отдаются уже посчитанными: SQLite делает min/avg/max и свёртку по
 * часам, наружу уходит несколько килобайт вместо тысяч точек. Иначе один такой
 * вызов съедал бы весь контекст агента.</p>
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
    private final ContainerMetrics containerMetrics;
    private final OrphanProcessReaper processReaper;

    @Value("${pohr.db-path:./data/pohr.db}")
    private String dbPath;

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
        tools.add(McpToolSupport.tool("memory_report",
                "Память сейчас, без ssh: расклад cgroup (anon/file/slab), счётчики memory.events (упирались ли в лимит, были ли OOM-kill), топ процессов по RSS/anon/THP, мусорные процессы и статистика reaper'а, размер БД. Отвечает на вопрос «кто занимает память и есть ли реальный дефицит».",
                """
                {"type":"object","properties":{
                  "top":{"type":"integer","description":"Сколько процессов показать, 1..30. По умолчанию 8"}
                },"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("metrics_stats",
                "Статистика метрик за окно в уже посчитанном виде: min/avg/max по anon, занятой памяти, Xray, telemt и heap, доля точек у лимита, тренд и свёртка по часам/суткам. Заменяет выгрузку поминутных точек.",
                """
                {"type":"object","properties":{
                  "hours":{"type":"integer","description":"Окно в часах, 1..168. По умолчанию 24"}
                },"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && (toolName.equals("server_status")
                || toolName.equals("logs_tail")
                || toolName.equals("traffic_overview")
                || toolName.equals("nodes_list")
                || toolName.equals("memory_report")
                || toolName.equals("metrics_stats"));
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "server_status" -> McpToolSupport.ok(serverStatus());
                case "logs_tail" -> McpToolSupport.ok(logsTail(args));
                case "traffic_overview" -> McpToolSupport.ok(trafficOverview(args));
                case "nodes_list" -> McpToolSupport.ok(nodesList());
                case "memory_report" -> McpToolSupport.ok(memoryReport(args));
                case "metrics_stats" -> McpToolSupport.ok(metricsStats(args));
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
            metrics.put("podAnonBytes", latest.podAnonBytes());
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

    // =====================================================================
    // memory_report
    // =====================================================================

    /**
     * Снимок памяти без ssh: cgroup-расклад, memory.events, процессы, reaper, БД.
     *
     * <p>Главное отличие от {@code server_status}: здесь отдельно показан
     * {@code anon} — то, что нельзя вытеснить. {@code memory.current} включает
     * page cache, поэтому «480 из 480» само по себе ещё не дефицит.</p>
     */
    private ObjectNode memoryReport(ObjectNode args) {
        int top = McpToolSupport.optInt(args, "top", 8, 1, 30);
        ObjectNode out = McpToolSupport.JSON.createObjectNode();

        long current = nz(containerMetrics.readCgroupMemoryBytes());
        long limit = nz(containerMetrics.readCgroupMemoryLimitBytes());
        Map<String, Long> stat = containerMetrics.readCgroupMemoryStat();
        Map<String, Long> events = containerMetrics.readCgroupMemoryEvents();
        long anon = nz(stat.get("anon"));
        double usedPct = limit > 0 ? current * 100.0 / limit : 0;
        double anonPct = limit > 0 ? anon * 100.0 / limit : 0;

        ObjectNode cgroup = out.putObject("cgroup");
        cgroup.put("currentMB", mb(current));
        cgroup.put("anonMB", mb(anon));
        cgroup.put("fileMB", mb(nz(stat.get("file"))));
        cgroup.put("slabMB", mb(nz(stat.get("slab"))));
        cgroup.put("limitMB", mb(limit));
        cgroup.put("usedPctOfLimit", round(usedPct));
        cgroup.put("anonPctOfLimit", round(anonPct));

        ObjectNode ev = out.putObject("events");
        ev.put("hitLimit", nz(events.get("max")));
        ev.put("oom", nz(events.get("oom")));
        ev.put("oomKill", nz(events.get("oom_kill")));

        List<ProcessTable.Proc> processes = ProcessTable.snapshot(true);
        ArrayNode arr = out.putArray("processes");
        processes.stream()
                .sorted(Comparator.comparingLong(ProcessTable.Proc::rssBytes).reversed())
                .limit(top)
                .forEach(p -> {
                    ObjectNode node = arr.addObject();
                    node.put("pid", p.pid());
                    node.put("name", p.name());
                    node.put("rssMB", mb(p.rssBytes()));
                    node.put("anonMB", mb(p.anonBytes()));
                    node.put("thpMB", mb(p.thpBytes()));
                    node.put("threads", p.threads());
                    node.put("ageMin", p.ageSeconds() < 0 ? -1 : p.ageSeconds() / 60);
                });
        out.put("processCount", processes.size());
        out.put("strayManaged", strayManagedProcesses(processes));

        OrphanProcessReaper.Stats reaper = processReaper.stats();
        ObjectNode reaperNode = out.putObject("reaper");
        reaperNode.put("enabled", reaper.enabled());
        reaperNode.put("minAgeSeconds", reaper.minAgeSeconds());
        reaperNode.put("killedTotal", reaper.killedTotal());
        reaperNode.put("killedLastSweep", reaper.killedLastSweep());
        McpToolSupport.putNullable(reaperNode, "lastSweepAt",
                reaper.lastSweepAt() == null ? null : reaper.lastSweepAt().toString());

        ObjectNode db = out.putObject("db");
        db.put("mainMB", mb(fileSize(Path.of(dbPath))));
        db.put("walMB", mb(fileSize(Path.of(dbPath + "-wal"))));

        out.put("verdict", memoryVerdict(usedPct, anonPct, nz(events.get("oom_kill"))));
        out.put("note", "memory.current включает page cache; реальное давление — anon. "
                + "Видны только процессы своего PID-namespace.");
        return out;
    }

    /** Сколько xray/telemt живёт не под управлением менеджеров — их заберёт reaper. */
    private long strayManagedProcesses(List<ProcessTable.Proc> processes) {
        Long xrayPid = xray.pid();
        Long telemtPid = telemt.pid();
        return processes.stream()
                .filter(p -> "xray".equals(p.name()) || "telemt".equals(p.name()))
                .filter(p -> xrayPid == null || p.pid() != xrayPid)
                .filter(p -> telemtPid == null || p.pid() != telemtPid)
                .count();
    }

    private static String memoryVerdict(double usedPct, double anonPct, long oomKill) {
        if (oomKill > 0) {
            return "OOM-kill был " + oomKill + " раз: контейнер убивал процессы, "
                    + "нужно снижать anon (Xray) или поднимать лимит.";
        }
        if (anonPct >= 85) {
            return "anon " + round(anonPct) + " % от лимита — реальное давление близко к пределу.";
        }
        if (usedPct >= 90) {
            return "Лимит упирается за счёт page cache (anon " + round(anonPct)
                    + " %) — это reclaim, а не дефицит памяти.";
        }
        return "Запас по anon " + round(100 - anonPct) + " % — давление умеренное.";
    }

    // =====================================================================
    // metrics_stats
    // =====================================================================

    private ObjectNode metricsStats(ObjectNode args) {
        int hours = McpToolSupport.optInt(args, "hours", 24, 1, 168);
        MetricsService.Stats stats = metricsService.stats(hours);

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("windowHours", stats.hours());
        out.put("samples", stats.samples());
        out.put("limitMB", stats.limitMb());

        ObjectNode now = out.putObject("now");
        now.put("anonMB", stats.nowAnon());
        now.put("usedMB", stats.nowUsed());

        ObjectNode ranges = out.putObject("ranges");
        ranges.set("anonMB", rangeNode(stats.anon()));
        ranges.set("usedMB", rangeNode(stats.used()));
        ranges.set("xrayMB", rangeNode(stats.xray()));
        ranges.set("telemtMB", rangeNode(stats.telemt()));
        ranges.set("heapMB", rangeNode(stats.heap()));
        ranges.set("cpuPct", rangeNode(stats.cpuPct()));

        ObjectNode over = out.putObject("overLimit");
        over.put("usedAt95PctSamples", stats.samplesOverLimit());
        over.put("anonAt95PctSamples", stats.samplesAnonOverLimit());
        over.put("usedAt95PctSharePct", stats.samples() == 0 ? 0
                : round(stats.samplesOverLimit() * 100.0 / stats.samples()));

        ObjectNode trend = out.putObject("trend");
        trend.put("anonDeltaMB", stats.anonTrend());
        trend.put("how", "avg anon первой половины окна → второй");

        ArrayNode buckets = out.putArray("buckets");
        for (MetricsService.MetricBucket bucket : stats.buckets()) {
            ObjectNode node = buckets.addObject();
            node.put("t", bucket.key());
            node.put("n", bucket.samples());
            node.put("anonAvgMB", bucket.anonAvg());
            node.put("anonMaxMB", bucket.anonMax());
            node.put("usedAvgMB", bucket.usedAvg());
            node.put("usedMaxMB", bucket.usedMax());
            node.put("xrayAvgMB", bucket.xrayAvg());
            node.put("xrayMaxMB", bucket.xrayMax());
            node.put("heapAvgMB", bucket.heapAvg());
        }

        out.put("verdict", trendVerdict(stats));
        out.put("note", "buckets: час при hours<=48, иначе сутки. anon не включает page cache.");
        return out;
    }

    private static ObjectNode rangeNode(MetricsService.MetricRange range) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("min", range.min());
        node.put("avg", range.avg());
        node.put("max", range.max());
        return node;
    }

    private static String trendVerdict(MetricsService.Stats stats) {
        if (stats.samplesAnonOverLimit() > 0) {
            return "Были точки, где anon ≥ 95 % лимита — дефицит реальный.";
        }
        if (stats.anonTrend() > 10) {
            return "anon растёт: +" + stats.anonTrend() + " МБ за половину окна — похоже на утечку.";
        }
        if (stats.anonTrend() < -10) {
            return "anon снижается: " + stats.anonTrend() + " МБ за половину окна.";
        }
        return "anon стабилен (тренд " + stats.anonTrend() + " МБ за половину окна).";
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static double mb(long bytes) {
        return round(bytes / 1048576.0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
