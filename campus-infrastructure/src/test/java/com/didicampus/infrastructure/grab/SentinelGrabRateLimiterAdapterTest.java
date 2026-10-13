package com.didicampus.infrastructure.grab;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentinelGrabRateLimiterAdapterTest {

    @Test
    void limits_each_errand_as_an_independent_hot_parameter() {
        SentinelGrabRateLimiterAdapter limiter =
                new SentinelGrabRateLimiterAdapter(true, 2, resourceName());

        assertTrue(limiter.tryPass(10001L, 2001L));
        assertTrue(limiter.tryPass(10001L, 2002L));
        assertFalse(limiter.tryPass(10001L, 2003L));
        assertTrue(limiter.tryPass(10002L, 2004L));
    }

    @Test
    void disabled_limiter_allows_every_request() {
        SentinelGrabRateLimiterAdapter limiter =
                new SentinelGrabRateLimiterAdapter(false, 1, resourceName());

        assertTrue(limiter.tryPass(10001L, 2001L));
        assertTrue(limiter.tryPass(10001L, 2002L));
    }

    private static String resourceName() {
        return "didicampus-grab-test-" + UUID.randomUUID();
    }
}
