package org.Roclh.service;

public final class DeviceDetector {

    private DeviceDetector() {
    }

    public static boolean isMobile(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return false;
        String ua = userAgent.toLowerCase();
        return ua.contains("android")
                || ua.contains("iphone")
                || ua.contains("ipad")
                || ua.contains("ipod")
                || ua.contains("windows phone")
                || ua.contains("mobile");
    }
}