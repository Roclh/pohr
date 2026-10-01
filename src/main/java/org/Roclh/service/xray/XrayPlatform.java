package org.Roclh.service.xray;

import java.util.Locale;

public enum XrayPlatform {
    LINUX_AMD64("linux-64"),
    LINUX_ARM64("linux-arm64-v8a"),
    WINDOWS_AMD64("windows-64"),
    WINDOWS_ARM64("windows-arm64-v8a"),
    MACOS_AMD64("macos-64"),
    MACOS_ARM64("macos-arm64-v8a");

    private final String suffix;

    XrayPlatform(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }

    public static XrayPlatform current() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            return arm ? WINDOWS_ARM64 : WINDOWS_AMD64;
        }
        if (os.contains("mac")) {
            return arm ? MACOS_ARM64 : MACOS_AMD64;
        }
        return arm ? LINUX_ARM64 : LINUX_AMD64;
    }
}