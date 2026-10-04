package org.Roclh.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PublicUrlResolver {

    @Value("${pohr.public-url:}")
    private String publicUrlOverride;

    public String resolve(HttpServletRequest request) {
        if (publicUrlOverride != null && !publicUrlOverride.isBlank()) {
            return publicUrlOverride.replaceAll("/$", "");
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) {
            host = "127.0.0.1";
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            sb.append(":").append(port);
        }
        return sb.toString();
    }
}