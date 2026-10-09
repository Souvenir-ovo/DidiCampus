package com.didicampus.infrastructure.support;

import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.infrastructure.cache.InMemoryCreditRankingAdapter;
import com.didicampus.infrastructure.cache.InMemoryErrandCacheAdapter;
import com.didicampus.infrastructure.grab.InMemoryCandidateQueueAdapter;
import com.didicampus.infrastructure.grab.InMemoryGrabSlotAdapter;
import com.didicampus.infrastructure.grab.LocalGrabRateLimiterAdapter;
import com.didicampus.infrastructure.message.DirectFundEventAdapter;
import com.didicampus.infrastructure.message.NoopDelayMessageAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LocalSupportAdapterTest {

    @Test
    void inMemoryGrabSlotPreventsOversellDuplicateAndSameRunnerGrab() {
        InMemoryGrabSlotAdapter adapter = new InMemoryGrabSlotAdapter();
        adapter.initSlot(10L, 1, 60L);

        assertEquals(SlotOutcome.ACQUIRED, adapter.tryAcquire(10L, 2001L, "req-1"));
        assertEquals(SlotOutcome.DUPLICATE_REQUEST, adapter.tryAcquire(10L, 2001L, "req-1"));
        assertEquals(SlotOutcome.ALREADY_GRABBED, adapter.tryAcquire(10L, 2001L, "req-2"));
        assertEquals(SlotOutcome.SLOT_FULL, adapter.tryAcquire(10L, 2002L, "req-3"));
        assertEquals(0L, adapter.remainingSlot(10L));

        adapter.rollback(10L, 2001L, "req-1");
        assertEquals(1L, adapter.remainingSlot(10L));
        assertEquals(SlotOutcome.ACQUIRED, adapter.tryAcquire(10L, 2002L, "req-4"));
    }

    @Test
    void candidateQueuePollsLowestScoreAndReplacesRunnerPriority() {
        InMemoryCandidateQueueAdapter adapter = new InMemoryCandidateQueueAdapter();

        adapter.offer(10L, 2001L, 90D);
        adapter.offer(10L, 2002L, 70D);
        adapter.offer(10L, 2001L, 60D);

        assertEquals(2L, adapter.size(10L));
        assertEquals(2001L, adapter.pollBest(10L).orElseThrow());
        assertEquals(2002L, adapter.pollBest(10L).orElseThrow());
        assertTrue(adapter.pollBest(10L).isEmpty());
    }

    @Test
    void localRateLimiterCountsByErrandAndSecond() {
        LocalGrabRateLimiterAdapter adapter = new LocalGrabRateLimiterAdapter(true, 2);

        assertTrue(adapter.tryPass(10L, 2001L));
        assertTrue(adapter.tryPass(10L, 2002L));
        assertFalse(adapter.tryPass(10L, 2003L));
        assertTrue(adapter.tryPass(11L, 2003L));
    }

    @Test
    void errandCacheSupportsLogicalExpireAndRebuildLock() {
        InMemoryErrandCacheAdapter adapter = new InMemoryErrandCacheAdapter(1000L, 1000L);

        assertTrue(adapter.mightExist(10L));
        adapter.registerExisting(10L);
        assertTrue(adapter.mightExist(10L));
        assertFalse(adapter.mightExist(11L));

        adapter.put(10L, "{\"id\":10}");
        ErrandCachePort.CachedErrand cached = adapter.get(10L).orElseThrow();
        assertEquals("{\"id\":10}", cached.payloadJson());
        assertFalse(cached.isEmpty());

        assertTrue(adapter.tryAcquireRebuild(10L));
        assertFalse(adapter.tryAcquireRebuild(10L));
        adapter.releaseRebuild(10L);
        assertTrue(adapter.tryAcquireRebuild(10L));

        adapter.evict(10L);
        assertTrue(adapter.get(10L).isEmpty());
    }

    @Test
    void rankingAndNoopMessageAdaptersKeepApplicationFlowUsable() {
        InMemoryCreditRankingAdapter ranking = new InMemoryCreditRankingAdapter();
        ranking.update(1L, 2001L, 62);
        ranking.update(1L, 2002L, 88);

        assertEquals(2002L, ranking.top(1L, 10).getFirst().userId());
        assertEquals(88, ranking.top(1L, 10).getFirst().score());

        NoopDelayMessageAdapter delay = new NoopDelayMessageAdapter();
        assertDoesNotThrow(() -> delay.send("topic", "key", "{}", Instant.now()));

        DirectFundEventAdapter fundEvent = new DirectFundEventAdapter();
        AtomicBoolean executed = new AtomicBoolean(false);
        boolean committed = fundEvent.publishInTransaction(
                new com.didicampus.domain.wallet.ports.FundEventPort.FundEvent(
                        "settle:10", "SETTLED", 10L, 1001L, 2001L, 100L, 5L),
                () -> {
                    executed.set(true);
                    return true;
                });
        assertTrue(executed.get());
        assertTrue(committed);
    }
}
