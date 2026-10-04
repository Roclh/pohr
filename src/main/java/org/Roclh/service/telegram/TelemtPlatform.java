package org.Roclh.service.telegram;

import java.util.Locale;

public enum TelemtPlatform {
    LINUX_AMD64("x86_64-linux-gnu"),
    LINUX_ARM64("aarch64-linux-gnu");

    private final String suffix;

    TelemtPlatform(String suffix) { this.suffix = suffix; }

    public String suffix() { return suffix; }

    public static TelemtPlatform current() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (os.contains("win") || os.contains("mac")) {
            throw new UnsupportedOperationException(
                    "telemt ships only Linux builds. Run Pohr in Docker or on Linux.");
        }
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        return arm ? LINUX_ARM64 : LINUX_AMD64;
    }
}