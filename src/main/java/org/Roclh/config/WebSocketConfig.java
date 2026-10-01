package org.Roclh.config;

import lombok.RequiredArgsConstructor;
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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(xrayLogHandler, "/ws/xray-logs")
                .setAllowedOriginPatterns("*");
    }
}