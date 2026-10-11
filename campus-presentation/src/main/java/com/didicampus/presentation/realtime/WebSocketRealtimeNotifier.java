package com.didicampus.presentation.realtime;

import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "didicampus.ws.enabled", havingValue = "true")
public class WebSocketRealtimeNotifier implements RealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRealtimeNotifier.class);

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public WebSocketRealtimeNotifier(WebSocketSessionRegistry sessionRegistry,
                                     ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void errandStatusChanged(long errandId, long publisherId,
                                    Long grabberId, String status, int round) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errandId", errandId);
        payload.put("status", status);
        payload.put("round", round);
        String message = message("errand.status", payload);
        pushTo(publisherId, message);
        if (grabberId != null && grabberId != publisherId) {
            pushTo(grabberId, message);
        }
    }

    @Override
    public void notificationArrived(long userId, long errandId,
                                    String type, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errandId", errandId);
        payload.put("noticeType", type);
        payload.put("content", content == null ? "" : content);
        pushTo(userId, message("notification.new", payload));
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("score", newScore);
        payload.put("delta", delta);
        payload.put("reason", reason == null ? "" : reason);
        pushTo(userId, message("credit.changed", payload));
    }

    private String message(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize websocket message", ex);
        }
    }

    private void pushTo(long userId, String json) {
        for (WebSocketSession session : sessionRegistry.sessionsOf(userId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            } catch (IOException ex) {
                log.debug("websocket push failed, userId={}, sessionId={}",
                        userId, session.getId(), ex);
            }
        }
    }
}
