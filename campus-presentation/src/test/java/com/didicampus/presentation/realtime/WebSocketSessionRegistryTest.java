package com.didicampus.presentation.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WebSocketSessionRegistryTest {

    @Test
    void supportsMultipleConnectionsForOneUser() {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);

        registry.register(1001L, first);
        registry.register(1001L, second);

        assertEquals(1, registry.onlineUserCount());
        assertEquals(2, registry.sessionsOf(1001L).size());

        registry.unregister(1001L, first);
        assertEquals(1, registry.sessionsOf(1001L).size());

        registry.unregister(1001L, second);
        assertEquals(0, registry.onlineUserCount());
    }
}
