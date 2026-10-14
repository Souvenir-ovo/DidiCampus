package com.didicampus.infrastructure.auth;

import com.didicampus.domain.auth.ports.RefreshTokenPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisJwtAuthAdapterTest {

    private static final String SECRET = "didi-campus-test-secret-key-minimum-32-bytes";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisJwtAuthAdapter adapter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        adapter = new RedisJwtAuthAdapter(redis, SECRET, 10, 60);
    }

    @Test
    void accessTokenIsSelfContainedAndResolvable() {
        String token = adapter.login(2001L);

        when(redis.hasKey(anyString())).thenReturn(false);

        assertEquals(2001L, adapter.resolve(token).orElseThrow());
        assertEquals(2, token.chars().filter(character -> character == '.').count());
    }

    @Test
    void revokedJtiIsRejectedByRedisBlacklist() {
        String token = adapter.login(2002L);
        when(redis.hasKey(anyString())).thenReturn(true);

        assertTrue(adapter.resolve(token).isEmpty());
    }

    @Test
    void logoutStoresAccessTokenIdInBlacklist() {
        String token = adapter.login(2002L);

        adapter.logout(token);

        verify(values).set(anyString(), anyString(), any());
    }

    @Test
    void refreshTokenIsStoredAndRotated() {
        AtomicReference<String> storedUser = new AtomicReference<>();
        when(values.get(anyString())).thenAnswer(invocation -> storedUser.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            storedUser.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any());

        RefreshTokenPort.TokenPair first = adapter.loginWithRefresh(2003L);
        when(redis.hasKey(anyString())).thenReturn(false);
        RefreshTokenPort.TokenPair second = adapter.refresh(first.refreshToken()).orElseThrow();

        assertEquals(2003L, adapter.resolve(second.accessToken()).orElseThrow());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        verify(redis).delete(anyString());
    }

    @Test
    void malformedRedisRefreshValueIsRejected() {
        when(values.get(anyString())).thenReturn("not-a-user-id");

        assertTrue(adapter.refresh("refresh-token").isEmpty());
        verify(redis).delete(anyString());
    }

    @Test
    void revokeRefreshDeletesRedisEntry() {
        adapter.revokeRefresh("refresh-token");

        verify(redis).delete("auth:jwt:refresh:refresh-token");
    }
}
