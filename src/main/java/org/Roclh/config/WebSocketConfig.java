package org.Roclh.config;

import lombok.RequiredArgsConstructor;
import org.Roclh.config.ws.CaddyLogWebSocketHandler;
import org.Roclh.config.ws.NginxLogWebSocketHandler;
import org.Roclh.config.ws.TelemtLogWebSocketHandler;
import org.Roclh.config.ws.XrayLogWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final XrayLogWebSocketHandler xrayLogHandler;
    private final TelemtLogWebSocketHandler telemtLogHandler;
    private final NginxLogWebSocketHandler nginxLogHandler;
    private final CaddyLogWebSocketHandler caddyLogHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(xrayLogHandler, "/ws/xray-logs")
                .setAllowedOriginPatterns("*");
        registry.addHandler(telemtLogHandler, "/ws/telemt-logs")
                .setAllowedOriginPatterns("*");
        registry.addHandler(nginxLogHandler, "/ws/nginx-logs")
                .setAllowedOriginPatterns("*");
        registry.addHandler(caddyLogHandler, "/ws/caddy-logs")
                .setAllowedOriginPatterns("*");
    }
}