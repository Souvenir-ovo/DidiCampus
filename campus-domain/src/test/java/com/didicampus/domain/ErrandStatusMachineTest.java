package com.didicampus.domain;

import com.didicampus.domain.errand.model.ErrandStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrandStatusMachineTest {

    @Test
    void onlyPublishedTasksCanBeGrabbed() {
        assertTrue(ErrandStatus.PUBLISHED.grabbable());
        for (ErrandStatus status : ErrandStatus.values()) {
            if (status != ErrandStatus.PUBLISHED) {
                assertFalse(status.grabbable());
            }
        }
    }

    @Test
    void timeoutTransferUsesLockedSelfLoop() {
        assertTrue(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.LOCKED));
        assertTrue(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.PUBLISHED));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertTrue(ErrandStatus.CLOSED.allowedTargets().isEmpty());
        assertTrue(ErrandStatus.CANCELLED.allowedTargets().isEmpty());
        assertFalse(ErrandStatus.PUBLISHED.canTransitTo(ErrandStatus.SETTLED));
    }
}
