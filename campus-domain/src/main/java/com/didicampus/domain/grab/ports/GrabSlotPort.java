package com.didicampus.domain.grab.ports;

import com.didicampus.domain.grab.model.SlotOutcome;

public interface GrabSlotPort {

    SlotOutcome tryAcquire(long errandId, long runnerId, String requestId);

    void rollback(long errandId, long runnerId, String requestId);

    void initSlot(long errandId, int slotTotal, long ttlSeconds);

    long remainingSlot(long errandId);
}
