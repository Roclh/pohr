package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeStatusDto;
import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeRoute;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.EdgeConfigService;
import org.Roclh.service.edge.EdgeService;
import org.Roclh.service.edge.EdgeVisibilityService;
import org.Roclh.service.edge.NginxProcessManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * MCP-инструменты edge: только чтение и preview. Ни один вызов не пишет
 * конфиги, не рестартит процессы и не вызывает {@code apply()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpEdgeTools implements McpToolModule {

    private final EdgeConfigService cfgService;
    private final EdgeService edgeService;
    private final EdgeVisibilityService visibility;
    private final NginxProcessManager nginx;
    private final CaddyProcessManager caddy;
    private final EdgeRouteRepository routeRepo;
    private final EdgeSiteRepository siteRepo;

    @Override
    public ArrayNode definitions() {
        ArrayNode tools = McpToolSupport.JSON.createArrayNode();
        tools.add(McpToolSupport.tool("edge_status",
                "Состояние edge: порты stream/http/https, fallback, процессы nginx/caddy, количество маршрутов и сайтов, sha256 сгенерированных конфигов и их совпадение с файлами на диске. Только чтение.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("edge_config_read",
                "Полная конфигурация edge: edge_config, все SNI-маршруты и Caddy-сайты, сгенерированные nginx.conf и Caddyfile текстом, их sha256 и состояние файлов на диске. Только чтение.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        tools.add(McpToolSupport.tool("edge_preview",
                "Preview edge: собрать nginx.conf и Caddyfile в память и вернуть unified diff к текущим файлам. Ничего не записывает и не применяет; при отсутствии изменений возвращает status=same.",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """));
        return tools;
    }

    @Override
    public boolean supports(String toolName) {
        return "edge_status".equals(toolName)
                || "edge_config_read".equals(toolName)
                || "edge_preview".equals(toolName);
    }

    @Override
    public ObjectNode call(String toolName, ObjectNode args, String actor) {
        try {
            return switch (toolName) {
                case "edge_status" -> McpToolSupport.ok(edgeStatus());
                case "edge_config_read" -> McpToolSupport.ok(edgeConfigRead());
                case "edge_preview" -> McpToolSupport.ok(edgePreview());
                default -> McpToolSupport.fail("Неизвестный edge-инструмент: " + toolName);
            };
        } catch (Exception e) {
            log.warn("MCP edge tool '{}' failed for {}: {}", toolName, actor, e.getMessage(), e);
            return McpToolSupport.fail("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // edge_status
    // =====================================================================

    private ObjectNode edgeStatus() {
        EdgeStatusDto status = edgeService.status();
        EdgeConfig config = cfgService.getConfig();
        EdgeConfigFilesDto files = visibility.configFiles();

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        ObjectNode ports = out.putObject("ports");
        ports.put("stream", status.streamPort());
        ports.put("http", status.httpPort());
        ports.put("https", status.httpsPort());
        out.put("fallback", config.getFallbackTarget());

        ObjectNode processes = out.putObject("processes");
        processNode(processes, "nginx", nginx.isRunning(), nginx.pid(),
                nginx.isInstalled(), nginx.getStartedAt());
        processNode(processes, "caddy", caddy.isRunning(), caddy.pid(),
                caddy.isInstalled(), caddy.getStartedAt());

        ObjectNode counts = out.putObject("counts");
        counts.put("routes", status.routeCount());
        counts.put("sites", status.siteCount());
        counts.put("enabledRoutes", routeRepo.findByEnabledTrueOrderBySortOrderAsc().size());
        counts.put("enabledSites", siteRepo.findByEnabledTrueOrderByDomainAsc().size());

        ObjectNode configs = out.putObject("configs");
        configs.set("nginx", configNode(files.nginx(), false));
        configs.set("caddy", configNode(files.caddy(), false));
        configs.put("same", files.same());

        out.put("note", files.same()
                ? "Сгенерированные конфиги совпадают с файлами на диске."
                : "Сгенерированные конфиги отличаются от файлов на диске — нужен apply.");
        return out;
    }

    private static void processNode(ObjectNode parent, String name, boolean running,
                                    Long pid, boolean installed, Instant startedAt) {
        ObjectNode node = parent.putObject(name);
        node.put("running", running);
        if (pid == null) node.putNull("pid"); else node.put("pid", pid);
        node.put("installed", installed);
        McpToolSupport.putNullable(node, "startedAt", startedAt == null ? null : startedAt.toString());
    }

    private static ObjectNode configNode(EdgeConfigFileDto file, boolean includeText) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("name", file.name());
        node.put("path", file.path());
        node.put("existsOnDisk", file.existsOnDisk());
        node.put("sha256", file.generatedHash());
        node.put("generatedSha256", file.generatedHash());
        McpToolSupport.putNullable(node, "diskSha256", file.diskHash());
        node.put("same", file.same());
        if (includeText) {
            node.put("text", file.generatedText());
        }
        return node;
    }

    // =====================================================================
    // edge_config_read
    // =====================================================================

    private ObjectNode edgeConfigRead() {
        EdgeConfig config = cfgService.getConfig();
        List<EdgeRoute> routes = routeRepo.findAllByOrderBySortOrderAscSniAsc();
        List<EdgeSite> sites = siteRepo.findAllByOrderByDomainAsc();
        EdgeConfigFilesDto files = visibility.configFiles();

        ObjectNode out = McpToolSupport.JSON.createObjectNode();

        ObjectNode cfg = out.putObject("edgeConfig");
        cfg.put("name", config.getName());
        cfg.put("streamPort", config.getStreamPort());
        cfg.put("httpPort", config.getHttpPort());
        cfg.put("httpsPort", config.getHttpsPort());
        cfg.put("fallbackTarget", config.getFallbackTarget());
        cfg.put("workerProcesses", config.getWorkerProcesses());
        cfg.put("workerConnections", config.getWorkerConnections());
        cfg.put("streamProxyTimeout", config.getStreamProxyTimeout());
        cfg.put("streamConnectTimeout", config.getStreamConnectTimeout());
        cfg.put("autoHttps", config.getAutoHttps());
        McpToolSupport.putNullable(cfg, "extraStreamDirectives", config.getExtraStreamDirectives());
        McpToolSupport.putNullable(cfg, "extraHttpDirectives", config.getExtraHttpDirectives());
        McpToolSupport.putNullable(cfg, "extraGlobalDirectives", config.getExtraGlobalDirectives());
        McpToolSupport.putNullable(cfg, "updatedAt",
                config.getUpdatedAt() == null ? null : config.getUpdatedAt().toString());

        ArrayNode routeNodes = out.putArray("routes");
        for (EdgeRoute route : routes) {
            ObjectNode node = routeNodes.addObject();
            node.put("sni", route.getSni());
            McpToolSupport.putNullable(node, "id", route.getId() == null ? null : route.getId().toString());
            node.put("target", route.getTargetHost() + ":" + route.getTargetPort());
            node.put("targetHost", route.getTargetHost());
            node.put("targetPort", route.getTargetPort());
            node.put("protocol", route.getProtocol());
            node.put("type", visibility.routeType(route));
            McpToolSupport.putNullable(node, "description", route.getDescription());
            node.put("enabled", route.isEnabled());
            node.put("sortOrder", route.getSortOrder());
            McpToolSupport.putNullable(node, "updatedAt",
                    route.getUpdatedAt() == null ? null : route.getUpdatedAt().toString());
        }

        ArrayNode siteNodes = out.putArray("sites");
        for (EdgeSite site : sites) {
            ObjectNode node = siteNodes.addObject();
            McpToolSupport.putNullable(node, "id", site.getId() == null ? null : site.getId().toString());
            node.put("domain", site.getDomain());
            node.put("siteType", site.getSiteType());
            McpToolSupport.putNullable(node, "rootPath", site.getRootPath());
            McpToolSupport.putNullable(node, "upstreamHost", site.getUpstreamHost());
            if (site.getUpstreamPort() == null) node.putNull("upstreamPort");
            else node.put("upstreamPort", site.getUpstreamPort());
            McpToolSupport.putNullable(node, "bindAddress", site.getBindAddress());
            McpToolSupport.putNullable(node, "redirectTarget", site.getRedirectTarget());
            McpToolSupport.putNullable(node, "redirectCode", site.getRedirectCode());
            node.put("tlsMode", site.getTlsMode());
            McpToolSupport.putNullable(node, "acmeEmail", site.getAcmeEmail());
            node.put("enabled", site.isEnabled());
            McpToolSupport.putNullable(node, "updatedAt",
                    site.getUpdatedAt() == null ? null : site.getUpdatedAt().toString());
        }

        ObjectNode configs = out.putObject("configs");
        configs.set("nginx", configNode(files.nginx(), true));
        configs.set("caddy", configNode(files.caddy(), true));
        configs.put("same", files.same());

        ObjectNode counts = out.putObject("counts");
        counts.put("routes", routeNodes.size());
        counts.put("sites", siteNodes.size());

        out.put("note", "Только чтение: файлы на диске и процессы не меняются.");
        return out;
    }

    // =====================================================================
    // edge_preview
    // =====================================================================

    private ObjectNode edgePreview() {
        EdgePreviewDto preview = visibility.preview();

        ObjectNode out = McpToolSupport.JSON.createObjectNode();
        out.put("same", preview.same());
        out.put("status", preview.same() ? "same" : "diff");

        ObjectNode configs = out.putObject("configs");
        configs.set("nginx", configNode(preview.nginx(), false));
        configs.set("caddy", configNode(preview.caddy(), false));

        out.set("nginx", diffNode(preview.nginx(), preview.nginxDiff()));
        out.set("caddy", diffNode(preview.caddy(), preview.caddyDiff()));

        out.put("note", "Preview ничего не записывает и не применяет; "
                + "mtime файлов и процессы не меняются.");
        return out;
    }

    private static ObjectNode diffNode(EdgeConfigFileDto file, String diff) {
        ObjectNode node = McpToolSupport.JSON.createObjectNode();
        node.put("same", file.same());
        node.put("sha256", file.generatedHash());
        node.put("generatedSha256", file.generatedHash());
        McpToolSupport.putNullable(node, "diskSha256", file.diskHash());
        node.put("existsOnDisk", file.existsOnDisk());
        node.put("diff", diff == null ? "" : diff);
        return node;
    }
}
