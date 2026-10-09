package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FulfillmentServiceTest {

    @Test
    void current_runner_can_mark_errand_picked_up() {
        FulfillmentRepository repository = new FulfillmentRepository(acceptedErrand());
        RecordingCache cache = new RecordingCache();
        RecordingNotifier notifier = new RecordingNotifier();
        PickUpErrandService service = new PickUpErrandService(
                repository,
                new CacheEvictSupport(cache, new NoopDelayedEviction(), false, 500),
                notifier);

        service.pickUp(new PickUpErrandService.Command(66L, 900L));

        assertEquals(1, repository.pickUpCalls);
        assertEquals(ErrandStatus.PICKED_UP.name(), notifier.lastStatus);
        assertEquals(66L, cache.evictedId);
        assertEquals(ErrandStatus.ACCEPTED, repository.lastFrom);
        assertEquals(ErrandStatus.PICKED_UP, repository.lastTo);
    }

    @Test
    void wrong_runner_cannot_pick_up() {
        FulfillmentRepository repository = new FulfillmentRepository(acceptedErrand());
        PickUpErrandService service = new PickUpErrandService(
                repository,
                new CacheEvictSupport(new RecordingCache(), new NoopDelayedEviction(), false, 500),
                new RecordingNotifier());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.pickUp(new PickUpErrandService.Command(66L, 901L)));

        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, error.code());
        assertEquals(0, repository.pickUpCalls);
    }

    @Test
    void deliver_registers_auto_settle_message_in_local_table() {
        FulfillmentRepository repository = new FulfillmentRepository(pickedUpErrand());
        RecordingMessages messages = new RecordingMessages();
        RecordingNotifier notifier = new RecordingNotifier();
        RecordingCache cache = new RecordingCache();
        DeliverErrandService service = new DeliverErrandService(
                repository,
                messages,
                new SnowflakeIdGenerator(8),
                new CacheEvictSupport(cache, new NoopDelayedEviction(), false, 500),
                notifier,
                86_400);

        service.deliver(new DeliverErrandService.Command(66L, 900L));

        assertEquals(1, repository.deliverCalls);
        assertEquals("autosettle:66", messages.lastMessageKey);
        assertEquals(DelayTaskPolicy.AUTO_SETTLE_TOPIC, messages.lastTopic);
        assertEquals("{\"errandId\":66}", messages.lastPayload);
        assertEquals(ErrandStatus.DELIVERED.name(), notifier.lastStatus);
        assertEquals(66L, cache.evictedId);
    }

    @Test
    void deliver_cas_conflict_does_not_enqueue_auto_settle_message() {
        FulfillmentRepository repository = new FulfillmentRepository(pickedUpErrand());
        repository.deliverResult = 0;
        RecordingMessages messages = new RecordingMessages();
        DeliverErrandService service = new DeliverErrandService(
                repository,
                messages,
                new SnowflakeIdGenerator(9),
                new CacheEvictSupport(new RecordingCache(), new NoopDelayedEviction(), false, 500),
                new RecordingNotifier(),
                86_400);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.deliver(new DeliverErrandService.Command(66L, 900L)));

        assertEquals(ErrorCode.STALE_VERSION, error.code());
        assertEquals(0, messages.enqueueCalls);
    }

    private static Errand acceptedErrand() {
        return Errand.rehydrate(
                66L,
                10L,
                500L,
                ErrandType.DELIVERY,
                "帮送资料",
                Money.fromCents(600),
                1,
                900L,
                ErrandStatus.ACCEPTED,
                1,
                0,
                4,
                Instant.now().minusSeconds(60));
    }

    private static Errand pickedUpErrand() {
        return Errand.rehydrate(
                66L,
                10L,
                500L,
                ErrandType.DELIVERY,
                "帮送资料",
                Money.fromCents(600),
                1,
                900L,
                ErrandStatus.PICKED_UP,
                1,
                0,
                5,
                Instant.now().minusSeconds(120));
    }

    private static final class FulfillmentRepository implements ErrandRepository {
        private final Errand errand;
        private int pickUpCalls;
        private int deliverCalls;
        private int deliverResult = 1;
        private ErrandStatus lastFrom;
        private ErrandStatus lastTo;

        private FulfillmentRepository(Errand errand) {
            this.errand = errand;
        }

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.of(errand); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {
            lastFrom = from;
            lastTo = to;
        }
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) {
            pickUpCalls++;
            return 1;
        }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) {
            deliverCalls++;
            return deliverResult;
        }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class RecordingMessages implements LocalMessageRepository {
        private int enqueueCalls;
        private String lastMessageKey;
        private String lastTopic;
        private String lastPayload;

        @Override
        public boolean enqueue(long id, String messageKey, String topic, String payload, Instant deliverAt) {
            enqueueCalls++;
            lastMessageKey = messageKey;
            lastTopic = topic;
            lastPayload = payload;
            return true;
        }

        @Override public void markSent(String messageKey) {}
        @Override public List<PendingMessage> findPending(int limit) { return List.of(); }
        @Override public void markRetry(String messageKey, int maxRetry) {}
    }

    private static final class RecordingCache implements ErrandCachePort {
        private long evictedId = -1L;
        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) { evictedId = errandId; }
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) {}
    }

    private static final class NoopDelayedEviction implements CacheEvictDelayPort {
        @Override public void scheduleEvict(long errandId, Instant deliverAt) {}
    }

    private static final class RecordingNotifier implements RealtimeNotifier {
        private String lastStatus;
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {
            lastStatus = status;
        }
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
