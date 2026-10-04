package org.Roclh.config.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.xray.XrayAssetService;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.Roclh.service.xray.XrayVersionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class XrayAutoInstaller {

    private final XrayInstaller installer;
    private final XrayVersionResolver versionResolver;
    private final XrayConfigService configService;
    private final XrayConfigMaterializer materializer;
    private final XrayAssetService assetService;
    private final XrayProcessManager processManager;

    @Value("${xray.install.auto:true}")
    private boolean autoInstall;

    @Value("${xray.install.version:latest}")
    private String version;

    @Value("${xray.start.auto:true}")
    private boolean autoStart;

    @Bean
    public ApplicationRunner xrayInstallRunner() {
        return args -> {
            if (!autoInstall) {
                log.info("Xray auto-install disabled");
                return;
            }
            try {
                boolean explicitVersion = version != null && !version.isBlank()
                        && !"latest".equals(version);

                if (!installer.isInstalled()) {
                    String resolved = explicitVersion ? version : versionResolver.resolveLatest();
                    if (resolved == null) {
                        log.warn("Could not resolve Xray version, skipping install");
                        return;
                    }
                    installer.install(resolved);
                    log.info("Xray {} installed on first boot", resolved);
                } else if (explicitVersion && !version.equals(installer.installedVersion())) {
                    installer.install(version);
                    log.info("Xray pinned to {}", version);
                } else {
                    log.info("Xray {} already installed, keeping (set XRAY_VERSION to pin/upgrade)",
                            installer.installedVersion());
                }

                assetService.ensureAssets();
                configService.ensureDefault();
                materializer.materialize();

                if (autoStart && !processManager.isRunning()) {
                    processManager.start();
                }
            } catch (Exception e) {
                log.error("Xray bootstrap failed: {}", e.getMessage(), e);
            }
        };
    }
}