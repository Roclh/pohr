package org.Roclh.config.xray;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayInstaller;
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

    @Value("${xray.install.auto:true}")
    private boolean autoInstall;

    @Value("${xray.install.version:latest}")
    private String version;

    @Bean
    public ApplicationRunner xrayInstallRunner() {
        return args -> {
            if (!autoInstall) {
                log.info("Xray auto-install disabled");
                return;
            }
            String target = version;
            if (target == null || target.isBlank() || "latest".equals(target)) {
                String latest = versionResolver.resolveLatest();
                if (latest == null) {
                    log.warn("Could not resolve latest Xray version, skipping auto-install");
                    return;
                }
                target = latest;
            }
            try {
                boolean installed = installer.install(target);
                log.info(installed ? "Xray {} installed" : "Xray {} already up to date", target);
                configService.ensureDefault();
                materializer.materialize();
            } catch (Exception e) {
                log.error("Failed to auto-install Xray {}: {}", target, e.getMessage(), e);
            }
        };
    }
}