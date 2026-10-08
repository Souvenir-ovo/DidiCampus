package com.didicampus.domain;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrandAggregateTest {

    private Errand published(int slots) {
        Errand errand = Errand.draft(
                1, 10, 1001, ErrandType.DELIVERY,
                "取快递", Money.fromCents(800), slots
        );
        errand.publish(0);
        return errand;
    }

    @Test
    void staleVersionIsRejected() {
        Errand errand = published(1);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> errand.lockBy(2001, 99, Instant.now())
        );

        assertEquals(ErrorCode.STALE_VERSION, error.code());
    }

    @Test
    void lockingUpdatesRunnerAndHistory() {
        Errand errand = published(1);
        errand.lockBy(2001, errand.version(), Instant.now());

        assertEquals(ErrandStatus.LOCKED, errand.status());
        assertEquals(2001L, errand.grabberId());
        assertEquals(1, errand.slotTaken());
        assertNotNull(errand.lockedAt());
        assertEquals(2, errand.changes().size());
    }

    @Test
    void invalidDraftIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Errand.draft(1, 10, 1001, ErrandType.BUY,
                        "代买", Money.ZERO, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Errand.draft(1, 10, 1001, ErrandType.BUY,
                        "代买", Money.fromCents(100), 0)
        );
    }

    @Test
    void escrowCarriesCampusForFutureSharding() {
        EscrowOrder order = EscrowOrder.held(
                1, 10, 100, 1001, Money.fromCents(800)
        );
        assertEquals(10, order.campusId());
    }
}
