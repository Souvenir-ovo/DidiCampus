package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "didicampus.grab.slot-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryGrabSlotAdapter implements GrabSlotPort {

    private final ConcurrentMap<Long, SlotState> slots = new ConcurrentHashMap<>();

    @Override
    public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
        SlotState state = slots.get(errandId);
        if (state == null) {
            return SlotOutcome.NOT_GRABBABLE;
        }
        synchronized (state) {
            SlotOutcome previous = state.requestResults.get(requestId);
            if (previous != null) {
                return SlotOutcome.DUPLICATE_REQUEST;
            }
            if (state.grabbedRunners.contains(runnerId)) {
                state.requestResults.put(requestId, SlotOutcome.ALREADY_GRABBED);
                return SlotOutcome.ALREADY_GRABBED;
            }
            if (state.remaining <= 0) {
                state.requestResults.put(requestId, SlotOutcome.SLOT_FULL);
                return SlotOutcome.SLOT_FULL;
            }
            state.remaining--;
            state.grabbedRunners.add(runnerId);
            state.acquiredRequests.put(requestId, runnerId);
            state.requestResults.put(requestId, SlotOutcome.ACQUIRED);
            return SlotOutcome.ACQUIRED;
        }
    }

    @Override
    public void rollback(long errandId, long runnerId, String requestId) {
        SlotState state = slots.get(errandId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            Long owner = state.acquiredRequests.remove(requestId);
            if (owner != null && owner == runnerId) {
                state.grabbedRunners.remove(runnerId);
                state.remaining = Math.min(state.total, state.remaining + 1);
            }
            state.requestResults.remove(requestId);
        }
    }

    @Override
    public void initSlot(long errandId, int slotTotal, long ttlSeconds) {
        slots.put(errandId, new SlotState(Math.max(0, slotTotal)));
    }

    @Override
    public long remainingSlot(long errandId) {
        SlotState state = slots.get(errandId);
        if (state == null) {
            return -1L;
        }
        synchronized (state) {
            return state.remaining;
        }
    }

    private static final class SlotState {
        private final int total;
        private final Set<Long> grabbedRunners = new HashSet<>();
        private final Map<String, Long> acquiredRequests = new HashMap<>();
        private final Map<String, SlotOutcome> requestResults = new HashMap<>();
        private int remaining;

        private SlotState(int total) {
            this.total = total;
            this.remaining = total;
        }
    }
}
