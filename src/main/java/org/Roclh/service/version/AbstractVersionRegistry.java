package org.Roclh.service.version;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public abstract class AbstractVersionRegistry {

    protected abstract Path versionFile();

    public String readInstalledVersion() {
        Path f = versionFile();
        if (!Files.exists(f)) return null;
        try {
            return Files.readString(f).trim();
        } catch (IOException e) {
            log.warn("Failed to read version file {}", f, e);
            return null;
        }
    }

    public void writeInstalledVersion(String version) throws IOException {
        Path f = versionFile();
        Files.createDirectories(f.getParent());
        Files.writeString(f, version);
    }
}