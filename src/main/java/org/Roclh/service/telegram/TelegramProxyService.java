package org.Roclh.service.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.TelegramProxyConfig;
import org.Roclh.model.TelegramProxyUser;
import org.Roclh.model.dto.telegram.TelegramProxyConfigDto;
import org.Roclh.model.dto.telegram.TelegramProxyUserDto;
import org.Roclh.repository.telegram.TelegramProxyConfigRepository;
import org.Roclh.repository.telegram.TelegramProxyUserRepository;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramProxyService {

    public static final String DEFAULT_CONFIG = "default";

    private final TelegramProxyConfigRepository configRepo;
    private final TelegramProxyUserRepository userRepo;
    private final TelemtInstaller installer;
    private final TelemtProcessManager processManager;
    private final XrayConfigMaterializer materializer;
    private final XrayProcessManager xrayProcessManager;
    private final TelemtVersionResolver versionResolver;

    @Value("${xray.home}")
    private String xrayHome;

    @Value("${pohr.telegram.metrics-port:9400}")
    private int metricsPort;

    @Value("${pohr.telegram.socks-port:10809}")
    private int socksPort;

    @Value("${pohr.telegram.public-port:443}")
    private int publicPort;

    @Value("${pohr.public-url:}")
    private String publicUrlOverride;

    @Value("${pohr.telegram.install.version:latest}")
    private String defaultVersion;

    // --- Config ---------------------------------------------------------------

    public TelegramProxyConfig getConfig() {
        return configRepo.findById(DEFAULT_CONFIG)
                .orElseThrow(() -> new IllegalStateException("telegram_proxy_config row missing"));
    }

    public TelegramProxyConfigDto toConfigDto(TelegramProxyConfig c) {
        return new TelegramProxyConfigDto(
                c.isEnabled(), c.getListenPort(), c.getCoverDomain(), c.isWebEnabled(),
                processManager.isRunning(), installer.installedVersion(),
                (int) userRepo.countByEnabledTrue(),
                processManager.pid(), processManager.getStartedAt());
    }

    @Transactional
    public void updateGlobalConfig(int listenPort, String coverDomain, boolean webEnabled) {
        TelegramProxyConfig c = getConfig();
        c.setListenPort(listenPort);
        c.setCoverDomain(blankToNull(coverDomain));
        c.setWebEnabled(webEnabled);
        configRepo.save(c);
        if (c.isEnabled()) {
            apply();
        }
    }

    @Transactional
    public void start() {
        TelegramProxyConfig c = getConfig();
        if (!c.isEnabled()) {
            c.setEnabled(true);
            configRepo.save(c);
        }
        apply();
    }

    @Transactional
    public void stop() {
        TelegramProxyConfig c = getConfig();
        if (c.isEnabled()) {
            c.setEnabled(false);
            configRepo.save(c);
        }
        apply();
    }

    @Transactional
    public void restart() {
        apply();
    }

    // --- Users ----------------------------------------------------------------

    public List<TelegramProxyUser> findAll() {
        return userRepo.findAll(Sort.by("label"));
    }

    public TelegramProxyUserDto toUserDto(TelegramProxyUser u) {
        TelegramProxyConfig c = getConfig();
        String host = resolvePublicHost();

        String classicUrl = null;
        String webUrl = null;

        if (u.getSecret() != null && !u.getSecret().isBlank()) {
            String cover = (c.getCoverDomain() != null && !c.getCoverDomain().isBlank())
                    ? c.getCoverDomain() : "www.microsoft.com";
            String coverHex = HexFormat.of()
                    .formatHex(cover.getBytes(StandardCharsets.UTF_8));

            String clientSecret = "ee" + u.getSecret() + coverHex;

            classicUrl = "tg://proxy?server=" + host + "&port=" + publicPort
                    + "&secret=" + clientSecret;

            if (c.isWebEnabled()) {
                webUrl = "tg://webproxy?server=" + host + "&secret=" + u.getSecret();
            }
        }

        return new TelegramProxyUserDto(
                u.getUserId(),
                u.getLabel(),
                u.getSecret(),   // в DTO остаётся "сырой" 32-hex — он же идёт в конфиг telemt
                u.isEnabled(),
                classicUrl,
                webUrl);
    }

    @Transactional
    public void toggle(UUID userId) {
        TelegramProxyUser u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("TG user not found: " + userId));
        u.setEnabled(!u.isEnabled());
        userRepo.save(u);
        apply();
    }

    @Transactional
    public void regenerateSecret(UUID userId) {
        TelegramProxyUser u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("TG user not found: " + userId));
        u.setSecret(installer.generateSecretn());
        userRepo.save(u);
        apply();
    }

    /** Публичная обёртка над installer.generateSecretn — для генерации при создании юзера. */
    public String generateSecret() {
        return installer.generateSecretn();
    }

    public boolean isRunning() {
        return processManager.isRunning();
    }

    /** Лёгкий reload telemt: сгенерировать секреты, перезаписать TOML, рестартнуть только telemt.
     *  Xray не трогаем — telemt-socks inbound не меняется при добавлении/удалении юзера. */
    @Transactional
    public void reloadUsers() {
        TelegramProxyConfig c = getConfig();
        try {
            generateMissingSecrets();

            if (!c.isEnabled()) {
                log.debug("telemt disabled globally — secrets generated, process not touched");
                return;
            }

            // 2. Дальше — как раньше
            ensureInstalled();
            Path cfg = writeTelemtConfig(c);
            if (processManager.isRunning()) {
                processManager.restart();
            } else {
                processManager.start();
            }
            log.info("telemt reloaded ({} enabled users)", userRepo.countByEnabledTrue());
        } catch (Exception e) {
            log.warn("Failed to reload telemt: {}", e.getMessage(), e);
        }
    }

    /** Автозапуск при старте приложения. Xray к этому моменту уже поднят XrayAutoInstaller'ом
     *  с telemt-socks inbound — рестартить его не нужно. */
    @Transactional
    public void startInternal() {
        TelegramProxyConfig c = getConfig();
        ensureInstalledQuietly();
        generateMissingSecrets();
        try {
            Path cfg = writeTelemtConfig(c);
            if (!processManager.isRunning()) {
                processManager.start();
            }
            log.info("telemt auto-started on :{} ({} enabled users)",
                    c.getListenPort(), userRepo.countByEnabledTrue());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to auto-start telemt: " + e.getMessage(), e);
        }
    }

    private void generateMissingSecrets() {
        for (TelegramProxyUser u : userRepo.findBySecretIsNull()) {
            try {
                u.setSecret(installer.generateSecretn());
                userRepo.save(u);
            } catch (Exception e) {
                log.warn("Cannot generate secret for user {}: {}", u.getLabel(), e.getMessage());
            }
        }
    }

    private void ensureInstalledQuietly() {
        if (installer.isInstalled()) return;
        try {
            ensureInstalled();
        } catch (Exception e) {
            throw new IllegalStateException("telemt not installed and cannot install: " + e.getMessage(), e);
        }
    }

    // --- Apply ----------------------------------------------------------------

    /** Генерирует конфиг telemt, материализует Xray и (пере)запускает процессы. */
    @Transactional
    public void apply() {
        TelegramProxyConfig c = getConfig();
        try {
            if (c.isEnabled()) {
                ensureInstalled();
                generateMissingSecrets();
                Path cfg = writeTelemtConfig(c);

                materializer.materialize();
                if (xrayProcessManager.isRunning()) {
                    xrayProcessManager.restart();
                }

                if (processManager.isRunning()) {
                    processManager.restart();
                } else {
                    processManager.start();
                }
            } else {
                processManager.stop();
                materializer.materialize();
                if (xrayProcessManager.isRunning()) {
                    xrayProcessManager.restart();
                }
            }
        } catch (Exception e) {
            log.error("Failed to apply telegram proxy: {}", e.getMessage(), e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    // --- Install --------------------------------------------------------------

    @Transactional
    public void install(String version) {
        try {
            String target = "latest".equalsIgnoreCase(version)
                    ? versionResolver.resolveLatest()
                    : version;
            if (target == null) {
                throw new IllegalStateException("Could not resolve latest telemt version");
            }
            installer.install(target);
            TelegramProxyConfig c = getConfig();
            if (c.isEnabled()) {
                apply();
            }
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException(
                    "telemt доступен только на Linux. На Windows запускай Pohr в Docker (WSL2).", e);
        } catch (Exception e) {
            throw new IllegalStateException("Install failed: " + e.getMessage(), e);
        }
    }

    private void ensureInstalled() throws IOException, InterruptedException {
        if (installer.isInstalled()) return;
        String pinned = defaultVersion;
        String target = (pinned != null && !pinned.isBlank() && !"latest".equalsIgnoreCase(pinned))
                ? pinned
                : versionResolver.resolveLatest();
        if (target == null) {
            throw new IllegalStateException("Cannot resolve telemt version for install");
        }
        installer.install(target);
    }

    // --- Config generation ----------------------------------------------------

    private Path writeTelemtConfig(TelegramProxyConfig c) throws IOException {
        Path cfg = Path.of(xrayHome, "config", "telemt.toml");
        Files.createDirectories(cfg.getParent());

        String cover = (c.getCoverDomain() != null && !c.getCoverDomain().isBlank())
                ? c.getCoverDomain() : "www.cloudflare.com";

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated by Pohr. Do not edit manually.\n\n");
        sb.append("show_link = []\n\n");

        // Upstream: весь исходящий трафик telemt → локальный SOCKS Xray → eu outbound
        sb.append("[[upstreams]]\n");
        sb.append("type = \"socks5\"\n");
        sb.append("address = \"127.0.0.1:").append(socksPort).append("\"\n");
        sb.append("enabled = true\n");
        sb.append("weight = 1\n\n");

        sb.append("[general]\n");
        sb.append("fast_mode = true\n");
        sb.append("use_middle_proxy = false\n\n");

        sb.append("[server]\n");
        sb.append("port = ").append(c.getListenPort()).append("\n");
        sb.append("listen_addr_ipv4 = \"0.0.0.0\"\n\n");

        sb.append("[server.api]\n");
        sb.append("enabled = true\n");
        sb.append("listen = \"127.0.0.1:").append(metricsPort).append("\"\n\n");

        sb.append("[censorship]\n");
        sb.append("tls_domain = \"").append(cover).append("\"\n");
        sb.append("mask = true\n");
        sb.append("mask_port = 443\n\n");

        sb.append("[access.users]\n");
        for (TelegramProxyUser u : userRepo.findByEnabledTrue()) {
            if (u.getSecret() == null || u.getSecret().isBlank()) continue;
            sb.append(sanitizeKey(u.getLabel())).append(" = \"")
                    .append(u.getSecret()).append("\"\n");
        }

        if (c.isWebEnabled()) {
            sb.append("\n[web]\n");
            sb.append("enabled = true\n");
            sb.append("carrier = \"https\"\n");
            sb.append("carrier_method = \"post\"\n");
        }

        Files.writeString(cfg, sb.toString(), StandardCharsets.UTF_8);
        log.info("Wrote telemt config to {} ({} users, socks upstream 127.0.0.1:{})",
                cfg, userRepo.countByEnabledTrue(), socksPort);
        return cfg;
    }

    private static String sanitizeKey(String label) {
        return label.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String resolvePublicHost() {
        if (publicUrlOverride != null && !publicUrlOverride.isBlank()) {
            try {
                URI uri = URI.create(publicUrlOverride);
                if (uri.getHost() != null) return uri.getHost();
            } catch (Exception ignored) {}
        }
        log.warn("pohr.public-url not set — TG proxy links will point to 127.0.0.1");
        return "127.0.0.1";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}