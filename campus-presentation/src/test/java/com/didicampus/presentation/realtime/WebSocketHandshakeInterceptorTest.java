package com.didicampus.presentation.realtime;

import com.didicampus.domain.auth.ports.AuthPort;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketHandshakeInterceptorTest {

    @Test
    void acceptsValidQueryTokenAndStoresUserId() {
        WebSocketHandshakeInterceptor interceptor = new WebSocketHandshakeInterceptor(new FakeAuthPort());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", "valid");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);

        assertTrue(accepted);
        assertEquals(9001L, attributes.get(UserWebSocketHandler.USER_ID_ATTRIBUTE));
    }

    @Test
    void rejectsMissingOrInvalidToken() {
        WebSocketHandshakeInterceptor interceptor = new WebSocketHandshakeInterceptor(new FakeAuthPort());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertFalse(accepted);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    private static final class FakeAuthPort implements AuthPort {
        @Override public String login(long userId) { return "valid"; }

        @Override
        public Optional<Long> resolve(String token) {
            return "valid".equals(token) ? Optional.of(9001L) : Optional.empty();
        }

        @Override public void logout(String token) {}
    }
}
