package com.didicampus.domain;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrandTransferTest {

    private Errand locked(long runnerId) {
        Errand errand = Errand.draft(
                1, 10, 1001, ErrandType.DELIVERY,
                "送餐", Money.fromCents(800), 1
        );
        errand.publish(0);
        errand.lockBy(runnerId, errand.version(), Instant.parse("2026-08-18T10:00:00Z"));
        return errand;
    }

    @Test
    void transferChangesRunnerButNotTakenSlots() {
        Errand errand = locked(2001);
        errand.transferToNextRunner(2002, errand.version(),
                Instant.parse("2026-08-18T10:06:00Z"));

        assertEquals(ErrandStatus.LOCKED, errand.status());
        assertEquals(2002L, errand.grabberId());
        assertEquals(1, errand.slotTaken());
        assertEquals(1, errand.round());
    }

    @Test
    void emptyCandidateQueueReopensTheTask() {
        Errand errand = locked(2001);
        errand.revertToPublished(errand.version());

        assertEquals(ErrandStatus.PUBLISHED, errand.status());
        assertEquals(0, errand.slotTaken());
        assertNull(errand.grabberId());
        assertTrue(errand.slotAvailable());
    }

    @Test
    void onlyCurrentRunnerCanAccept() {
        Errand errand = locked(2001);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> errand.acceptByRunner(9999, errand.version())
        );
        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, error.code());

        errand.acceptByRunner(2001, errand.version());
        assertEquals(ErrandStatus.ACCEPTED, errand.status());
    }

    @Test
    void acceptedTaskCannotBeTransferred() {
        Errand errand = locked(2001);
        errand.acceptByRunner(2001, errand.version());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> errand.transferToNextRunner(2002, errand.version(), Instant.now())
        );
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, error.code());
    }

    @Test
    void timeoutDependsOnStateAndTime() {
        Instant lockedAt = Instant.parse("2026-08-18T10:00:00Z");
        Errand errand = Errand.rehydrate(
                1, 10, 1001, ErrandType.DELIVERY, "任务",
                Money.fromCents(800), 1, 2001L, ErrandStatus.LOCKED,
                1, 0, 2, lockedAt
        );

        assertFalse(errand.confirmTimeout(lockedAt.plusSeconds(299), 300));
        assertTrue(errand.confirmTimeout(lockedAt.plusSeconds(301), 300));
    }
}
