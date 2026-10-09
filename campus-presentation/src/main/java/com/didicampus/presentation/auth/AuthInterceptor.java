package com.didicampus.presentation.auth;

import com.didicampus.domain.auth.ports.AuthPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthPort authPort;
    private final boolean allowHeaderIdentity;

    public AuthInterceptor(AuthPort authPort,
                           @Value("${didicampus.auth.allow-header-identity:false}") boolean allowHeaderIdentity) {
        this.authPort = authPort;
        this.allowHeaderIdentity = allowHeaderIdentity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = readUserIdFromHeader(request);
        if (userId == null) {
            userId = readUserIdFromToken(request);
        }
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        request.setAttribute(CurrentUser.ATTR, userId);
        return true;
    }

    private Long readUserIdFromHeader(HttpServletRequest request) {
        if (!allowHeaderIdentity) {
            return null;
        }
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long readUserIdFromToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authPort.resolve(authorization.substring(7).trim()).orElse(null);
    }
}
