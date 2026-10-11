package com.didicampus.presentation.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@ConditionalOnProperty(name = "didicampus.ws.enabled", havingValue = "true")
public class UserWebSocketHandler extends TextWebSocketHandler {

    static final String USER_ID_ATTRIBUTE = "didicampus.ws.user-id";

    private final WebSocketSessionRegistry sessionRegistry;

    public UserWebSocketHandler(WebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (!(userId instanceof Long id)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessionRegistry.register(id, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equalsIgnoreCase(message.getPayload().trim())) {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("pong"));
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (userId instanceof Long id) {
            sessionRegistry.unregister(id, session);
        }
    }
}
