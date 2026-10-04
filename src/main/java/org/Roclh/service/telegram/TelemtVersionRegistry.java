package org.Roclh.service.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class TelemtVersionRegistry {

    @Value("${xray.home}")
    private String xrayHome;

    private Path versionFile() {
        return Path.of(xrayHome, "TELEMT_VERSION");
    }

    public String readInstalledVersion() {
        Path file = versionFile();
        if (!Files.exists(file)) return null;
        try { return Files.readString(file).trim(); }
        catch (IOException e) {
            log.warn("Failed to read telemt version file", e);
            return null;
        }
    }

    public void writeInstalledVersion(String version) throws IOException {
        Path file = versionFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, version);
    }
}
