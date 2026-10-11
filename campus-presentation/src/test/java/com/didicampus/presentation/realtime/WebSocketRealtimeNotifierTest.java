package com.didicampus.presentation.realtime;

import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketRealtimeNotifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pushesStatusChangeToPublisherAndRunner() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        WebSocketSession publisherSession = openSession("publisher");
        WebSocketSession runnerSession = openSession("runner");
        registry.register(1001L, publisherSession);
        registry.register(2001L, runnerSession);

        RealtimeNotifier notifier = new WebSocketRealtimeNotifier(registry, objectMapper);
        notifier.errandStatusChanged(77L, 1001L, 2001L, "ACCEPTED", 2);

        JsonNode publisherMessage = sentMessage(publisherSession);
        JsonNode runnerMessage = sentMessage(runnerSession);
        assertEquals("errand.status", publisherMessage.get("type").asText());
        assertEquals(77L, publisherMessage.get("payload").get("errandId").asLong());
        assertEquals("ACCEPTED", runnerMessage.get("payload").get("status").asText());
    }

    @Test
    void doesNotSendDuplicateStatusMessageWhenPublisherIsRunner() throws Exception {
        WebSocketSession session = openSession("same-user");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        registry.register(1001L, session);

        new WebSocketRealtimeNotifier(registry, objectMapper)
                .errandStatusChanged(88L, 1001L, 1001L, "SETTLED", 0);

        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void serializesNotificationContentAsJson() throws Exception {
        WebSocketSession session = openSession("notification");
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        registry.register(3001L, session);

        new WebSocketRealtimeNotifier(registry, objectMapper)
                .notificationArrived(3001L, 99L, "GRAB", "内容含有\"引号\"");

        JsonNode message = sentMessage(session);
        assertEquals("notification.new", message.get("type").asText());
        assertEquals("内容含有\"引号\"", message.get("payload").get("content").asText());
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private JsonNode sentMessage(WebSocketSession session) throws IOException {
        var captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        try {
            return objectMapper.readTree(captor.getValue().getPayload());
        } catch (Exception ex) {
            throw new AssertionError("invalid websocket JSON", ex);
        }
    }
}
