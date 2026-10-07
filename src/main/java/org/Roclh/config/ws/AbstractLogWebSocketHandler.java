package org.Roclh.config.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
public abstract class AbstractLogWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    /** Имя сервиса для логов: "xray", "telemt", "nginx", "caddy". */
    protected abstract String serviceName();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("{} WS connected: {}", serviceName(), session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.debug("{} WS closed: {}", serviceName(), session.getId());
    }

    public void broadcast(String line) {
        if (sessions.isEmpty()) return;
        TextMessage message = new TextMessage(line);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                log.debug("Dropping {} WS session {}: {}", serviceName(), session.getId(), e.getMessage());
                sessions.remove(session);
            }
        }
    }
}