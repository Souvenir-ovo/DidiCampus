package com.didicampus.infrastructure.auth;

import com.didicampus.domain.auth.ports.RefreshTokenPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryJwtAuthAdapterTest {

    private static final String SECRET = "didi-campus-test-secret-key-minimum-32-bytes";

    @Test
    void loginIssuesResolvableAccessToken() {
        InMemoryJwtAuthAdapter adapter = new InMemoryJwtAuthAdapter(SECRET, 10, 60);

        String token = adapter.login(1001L);

        assertEquals(1001L, adapter.resolve(token).orElseThrow());
    }

    @Test
    void logoutRevokesAccessToken() {
        InMemoryJwtAuthAdapter adapter = new InMemoryJwtAuthAdapter(SECRET, 10, 60);
        String token = adapter.login(1002L);

        adapter.logout(token);

        assertTrue(adapter.resolve(token).isEmpty());
    }

    @Test
    void refreshTokenRotatesOnce() {
        InMemoryJwtAuthAdapter adapter = new InMemoryJwtAuthAdapter(SECRET, 10, 60);
        RefreshTokenPort.TokenPair first = adapter.loginWithRefresh(1003L);

        RefreshTokenPort.TokenPair second = adapter.refresh(first.refreshToken()).orElseThrow();

        assertEquals(1003L, adapter.resolve(second.accessToken()).orElseThrow());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertTrue(adapter.refresh(first.refreshToken()).isEmpty());
    }
}
