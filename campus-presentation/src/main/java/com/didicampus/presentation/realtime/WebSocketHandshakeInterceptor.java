package com.didicampus.presentation.realtime;

import com.didicampus.domain.auth.ports.AuthPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "didicampus.ws.enabled", havingValue = "true")
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthPort authPort;

    public WebSocketHandshakeInterceptor(AuthPort authPort) {
        this.authPort = authPort;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        String token = servletRequest.getServletRequest().getParameter("token");
        return authPort.resolve(token)
                .map(userId -> {
                    attributes.put(UserWebSocketHandler.USER_ID_ATTRIBUTE, userId);
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler handler,
                               Exception exception) {
    }
}
