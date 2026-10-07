package org.Roclh.service.xray;

import org.Roclh.service.version.AbstractVersionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class XrayVersionRegistry extends AbstractVersionRegistry {
    @Value("${xray.home}")
    private String home;

    @Override
    protected Path versionFile() {
        return Path.of(home, "VERSION");
    }
}