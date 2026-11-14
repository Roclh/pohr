package org.Roclh.service.edge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeMapDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeSniRowDto;
import org.Roclh.model.dto.edge.EdgeWarningDto;
import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeRoute;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Эффективная картина edge: SNI → backend, предупреждения, сгенерированные
 * конфиги и их расхождение с файлами на диске.
 *
 * <p>Сервис только читает: он не вызывает {@code materialize()}, не пишет файлы
 * и не трогает процессы. DNS-резолв кэшируется в {@link CachedEdgeDnsResolver}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeVisibilityService {

    public static final String TYPE_REALITY = "reality";
    public static final String TYPE_TELEMT = "telemt";
    public static final String TYPE_CADDY = "caddy";
    public static final String TYPE_FALLBACK = "fallback";

    public static final String WARN_DNS_UNRESOLVED = "dns-unresolved";
    public static final String WARN_DNS_MISMATCH = "dns-mismatch";
    public static final String WARN_DUPLICATE_SNI = "duplicate-sni";
    public static final String WARN_FALLBACK_PANEL = "fallback-panel";
    public static final String WARN_SITE_WITHOUT_ROUTE = "site-without-route";
    public static final String WARN_ROUTE_WITHOUT_SITE = "route-without-site";
    public static final String WARN_EXPECTED_SNI_MISSING = "expected-sni-missing";
    public static final String WARN_SERVER_IP_UNKNOWN = "server-ip-unknown";

    private final EdgeConfigService cfgService;
    private final EdgeRouteRepository routeRepo;
    private final EdgeSiteRepository siteRepo;
    private final EdgeDnsResolver dnsResolver;

    @Value("${pohr.public-url:}")
    private String publicUrl;

    @Value("${pohr.edge.server-ip:}")
    private String serverIpOverride;

    @Value("${pohr.telegram.socks-port:10809}")
    private int telemtSocksPort;

    @Value("${server.port:8080}")
    private int serverPort;

    // =====================================================================
    // Карта SNI
    // =====================================================================

    public EdgeMapDto buildMap() {
        EdgeConfig config = cfgService.getConfig();
        List<EdgeRoute> routes = routeRepo.findByEnabledTrueOrderBySortOrderAsc();
        List<EdgeSite> sites = siteRepo.findByEnabledTrueOrderByDomainAsc();

        String publicHost = hostFromUrl(publicUrl);
        String serverIp = resolveServerIp(publicHost);
        String baseDomain = resolveBaseDomain(sites, publicHost);

        Set<String> routeSnis = routes.stream()
                .map(route -> normalize(route.getSni()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> siteDomains = sites.stream()
                .map(site -> normalize(site.getDomain()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Long> duplicates = routes.stream()
                .collect(Collectors.groupingBy(route -> normalize(route.getSni()), Collectors.counting()));

        List<EdgeWarningDto> globalWarnings = new ArrayList<>();
        if (serverIp == null) {
            globalWarnings.add(new EdgeWarningDto(WARN_SERVER_IP_UNKNOWN, "INFO",
                    publicHost == null ? "pohr.public-url" : publicHost,
                    "Set POHR_PUBLIC_URL or POHR_EDGE_SERVER_IP to compare DNS records"));
        }

        List<EdgeSniRowDto> rows = new ArrayList<>();
        for (EdgeRoute route : routes) {
            List<EdgeWarningDto> rowWarnings = new ArrayList<>();
            String sni = route.getSni();
            if (duplicates.getOrDefault(normalize(sni), 0L) > 1) {
                rowWarnings.add(new EdgeWarningDto(WARN_DUPLICATE_SNI, "WARN", sni,
                        "first match wins in nginx map"));
            }

            EdgeDnsResolver.Lookup lookup = dnsResolver.lookup(sni);
            rowWarnings.addAll(dnsWarnings(sni, serverIp, lookup));

            String type = classify(route, config);
            if (TYPE_CADDY.equals(type) && !siteDomains.contains(normalize(sni))) {
                rowWarnings.add(new EdgeWarningDto(WARN_ROUTE_WITHOUT_SITE, "WARN", sni,
                        "Caddy route has no matching edge_sites row"));
            }

            rows.add(new EdgeSniRowDto(sni, target(route.getTargetHost(), route.getTargetPort()),
                    type, resolvesToServer(serverIp, lookup), lookup.addresses(), rowWarnings));
        }

        // Сайты без явного маршрута всё равно обслуживаются Caddy через fallback.
        // Показываем их строками карты, иначе «сайт есть, маршрута нет» не видно.
        for (EdgeSite site : sites) {
            String domain = site.getDomain();
            if (routeSnis.contains(normalize(domain))) {
                continue;
            }
            EdgeDnsResolver.Lookup lookup = dnsResolver.lookup(domain);
            List<EdgeWarningDto> rowWarnings = new ArrayList<>();
            rowWarnings.add(new EdgeWarningDto(WARN_SITE_WITHOUT_ROUTE, "WARN", domain,
                    "unmatched SNI falls back to " + config.getFallbackTarget()));
            rowWarnings.addAll(dnsWarnings(domain, serverIp, lookup));
            rows.add(new EdgeSniRowDto(domain, config.getFallbackTarget(), TYPE_CADDY,
                    resolvesToServer(serverIp, lookup), lookup.addresses(), rowWarnings));
        }

        List<EdgeWarningDto> fallbackWarnings = new ArrayList<>();
        if (pointsToPanel(config.getFallbackTarget(), serverIp)) {
            fallbackWarnings.add(new EdgeWarningDto(WARN_FALLBACK_PANEL, "WARN",
                    config.getFallbackTarget(), "fallback points to the panel port"));
        }
        EdgeSniRowDto fallback = new EdgeSniRowDto("default", config.getFallbackTarget(),
                TYPE_FALLBACK, false, List.of(), fallbackWarnings);

        addExpectedSniWarnings(baseDomain, routeSnis, siteDomains, globalWarnings);

        return new EdgeMapDto(serverIp, publicHost, baseDomain, rows, fallback, globalWarnings);
    }

    private static boolean resolvesToServer(String serverIp, EdgeDnsResolver.Lookup lookup) {
        return serverIp != null && lookup.resolved() && lookup.addresses().contains(serverIp);
    }

    private static List<EdgeWarningDto> dnsWarnings(String host, String serverIp,
                                                    EdgeDnsResolver.Lookup lookup) {
        List<EdgeWarningDto> warnings = new ArrayList<>();
        if (serverIp == null) {
            return warnings;
        }
        if (!lookup.resolved()) {
            warnings.add(new EdgeWarningDto(WARN_DNS_UNRESOLVED, "WARN", host, lookup.error()));
        } else if (!lookup.addresses().contains(serverIp)) {
            warnings.add(new EdgeWarningDto(WARN_DNS_MISMATCH, "WARN", host,
                    String.join(", ", lookup.addresses())));
        }
        return warnings;
    }

    private void addExpectedSniWarnings(String baseDomain, Set<String> routeSnis,
                                        Set<String> siteDomains, List<EdgeWarningDto> warnings) {
        if (baseDomain == null || baseDomain.isBlank() || isIpLiteral(baseDomain)) {
            return;
        }
        for (String label : List.of("www", "edge", "eu")) {
            String host = label + "." + baseDomain;
            if (!routeSnis.contains(host) && !siteDomains.contains(host)) {
                warnings.add(new EdgeWarningDto(WARN_EXPECTED_SNI_MISSING, "WARN", host,
                        "expected by T01 DNS plan, not described in edge map"));
            }
        }
    }

    /** Тип маршрута для UI/MCP: reality | telemt | caddy. */
    public String routeType(EdgeRoute route) {
        return classify(route, cfgService.getConfig());
    }

    String classify(EdgeRoute route, EdgeConfig config) {
        String protocol = route.getProtocol() == null ? "" : route.getProtocol().trim().toLowerCase(Locale.ROOT);
        if (TYPE_REALITY.equals(protocol) || TYPE_TELEMT.equals(protocol) || TYPE_CADDY.equals(protocol)) {
            return protocol;
        }
        int port = route.getTargetPort();
        if (port == config.getHttpsPort() || port == config.getHttpPort()) {
            return TYPE_CADDY;
        }
        if (port == telemtSocksPort) {
            return TYPE_TELEMT;
        }
        return TYPE_REALITY;
    }

    private boolean pointsToPanel(String target, String serverIp) {
        if (target == null) {
            return false;
        }
        String value = target.trim().toLowerCase(Locale.ROOT);
        List<String> loopbackHosts = List.of("127.0.0.1", "localhost", "0.0.0.0", "::1", "[::1]");
        for (String host : loopbackHosts) {
            if (value.equals(host + ":" + serverPort)) {
                return true;
            }
        }
        return serverIp != null && value.equals(serverIp.toLowerCase(Locale.ROOT) + ":" + serverPort);
    }

    private String resolveServerIp(String publicHost) {
        if (serverIpOverride != null && !serverIpOverride.isBlank()) {
            return serverIpOverride.trim();
        }
        if (publicHost == null) {
            return null;
        }
        if (isIpLiteral(publicHost)) {
            return publicHost;
        }
        EdgeDnsResolver.Lookup lookup = dnsResolver.lookup(publicHost);
        return lookup.resolved() ? lookup.addresses().getFirst() : null;
    }

    private String resolveBaseDomain(List<EdgeSite> sites, String publicHost) {
        if (publicHost != null && !isIpLiteral(publicHost)) {
            return stripT01Prefix(publicHost);
        }
        List<String> domains = sites.stream()
                .map(EdgeSite::getDomain)
                .filter(Objects::nonNull)
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isBlank())
                .toList();
        return domains.stream()
                .filter(domain -> !domain.startsWith("www.") && !domain.startsWith("edge.") && !domain.startsWith("eu."))
                .findFirst()
                .or(() -> domains.stream().findFirst().map(this::stripT01Prefix))
                .orElse(null);
    }

    private String stripT01Prefix(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("www.", "edge.", "eu.")) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    static String hostFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String candidate = url.contains("://") ? url.trim() : "https://" + url.trim();
            String host = URI.create(candidate).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            log.debug("Cannot parse pohr.public-url '{}': {}", url, e.getMessage());
            return null;
        }
    }

    private static boolean isIpLiteral(String value) {
        return value != null && (value.contains(":") || value.matches("\\d{1,3}(\\.\\d{1,3}){3}"));
    }

    private static String target(String host, int port) {
        return host + ":" + port;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // =====================================================================
    // Конфиги и preview
    // =====================================================================

    public EdgeConfigFilesDto configFiles() {
        String nginx = cfgService.buildNginxConfig();
        String caddy = cfgService.buildCaddyfile();
        return new EdgeConfigFilesDto(
                snapshot("nginx.conf", cfgService.nginxConfigPath(), nginx),
                snapshot("Caddyfile", cfgService.caddyConfigPath(), caddy));
    }

    public EdgePreviewDto preview() {
        EdgeConfigFilesDto files = configFiles();
        String nginxDiff = diff(files.nginx(), "nginx.conf");
        String caddyDiff = diff(files.caddy(), "Caddyfile");
        return new EdgePreviewDto(files.nginx(), files.caddy(), nginxDiff, caddyDiff);
    }

    private static String diff(EdgeConfigFileDto file, String name) {
        if (file.same()) {
            return "";
        }
        String oldText = file.diskText() == null ? "" : file.diskText();
        String oldLabel = name + (file.existsOnDisk() ? " (disk)" : " (missing on disk)");
        return UnifiedDiff.diff(oldText, file.generatedText(), oldLabel, name + " (generated)");
    }

    private static EdgeConfigFileDto snapshot(String name, Path path, String generated) {
        String diskText = null;
        try {
            if (Files.exists(path)) {
                diskText = Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Cannot read edge config {}: {}", path, e.getMessage());
        }
        String generatedHash = sha256(generated);
        String diskHash = diskText == null ? null : sha256(diskText);
        boolean same = diskText != null && diskText.equals(generated);
        return new EdgeConfigFileDto(name, path.toString(), diskText != null, generated,
                generatedHash, diskText, diskHash, same);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
