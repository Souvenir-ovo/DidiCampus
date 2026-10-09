package com.didicampus.presentation.auth;

import com.didicampus.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorTest {

    @Test
    void rejectsRequestWithoutIdentity() {
        AuthInterceptor interceptor = new AuthInterceptor(new FakeAuthPort(), false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsBearerToken() {
        AuthInterceptor interceptor = new AuthInterceptor(new FakeAuthPort(), false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(9001L, request.getAttribute(CurrentUser.ATTR));
    }

    @Test
    void optionalHeaderIdentityIsOnlyUsedWhenEnabled() {
        AuthInterceptor interceptor = new AuthInterceptor(new FakeAuthPort(), true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "8001");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(8001L, request.getAttribute(CurrentUser.ATTR));
    }

    private static final class FakeAuthPort implements AuthPort {
        @Override public String login(long userId) { return "good-token"; }

        @Override
        public Optional<Long> resolve(String token) {
            return "good-token".equals(token) ? Optional.of(9001L) : Optional.empty();
        }

        @Override public void logout(String token) {}
    }
}
