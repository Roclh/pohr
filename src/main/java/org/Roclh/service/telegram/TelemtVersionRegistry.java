package org.Roclh.service.telegram;

import org.Roclh.service.version.AbstractVersionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class TelemtVersionRegistry extends AbstractVersionRegistry {
    @Value("${xray.home}")
    private String home;

    @Override
    protected Path versionFile() {
        return Path.of(home, "TELEMT_VERSION");
    }
}