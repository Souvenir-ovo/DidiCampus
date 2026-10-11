package com.didicampus.application.usecase;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrandActionResolverTest {

    private final ErrandActionResolver resolver = new ErrandActionResolver(9001L);

    @Test
    void ordinary_runner_can_grab_published_errand() {
        ErrandActionResolver.Resolved resolved =
                resolver.resolve(detail("PUBLISHED", 1001L, null), 2001L);

        assertEquals("VIEWER", resolved.role());
        assertEquals(List.of("GRAB"), resolved.actions());
    }

    @Test
    void publisher_cannot_grab_own_errand_but_can_settle_after_delivery() {
        ErrandActionResolver.Resolved published =
                resolver.resolve(detail("PUBLISHED", 1001L, null), 1001L);
        ErrandActionResolver.Resolved delivered =
                resolver.resolve(detail("DELIVERED", 1001L, 2001L), 1001L);

        assertEquals(List.of(), published.actions());
        assertEquals("PUBLISHER", delivered.role());
        assertEquals(List.of("SETTLE"), delivered.actions());
    }

    @Test
    void current_runner_gets_only_the_action_for_the_current_state() {
        ErrandActionResolver.Resolved locked =
                resolver.resolve(detail("LOCKED", 1001L, 2001L), 2001L);
        ErrandActionResolver.Resolved accepted =
                resolver.resolve(detail("ACCEPTED", 1001L, 2001L), 2001L);
        ErrandActionResolver.Resolved pickedUp =
                resolver.resolve(detail("PICKED_UP", 1001L, 2001L), 2001L);

        assertEquals(List.of("CONFIRM"), locked.actions());
        assertEquals(List.of("PICKUP"), accepted.actions());
        assertEquals(List.of("DELIVER"), pickedUp.actions());
    }

    @Test
    void configured_arbitrator_has_highest_role_priority() {
        ErrandActionResolver.Resolved resolved =
                resolver.resolve(detail("DISPUTED", 1001L, 2001L), 9001L);

        assertEquals("ARBITRATOR", resolved.role());
        assertEquals(List.of(), resolved.actions());
    }

    private static ErrandDetail detail(String status, long publisherId, Long runnerId) {
        return new ErrandDetail(
                77L,
                12L,
                publisherId,
                runnerId,
                "DELIVERY",
                "送到宿舍",
                800L,
                1,
                runnerId == null ? 0 : 1,
                status,
                0,
                3L,
                Instant.now(),
                null);
    }
}
