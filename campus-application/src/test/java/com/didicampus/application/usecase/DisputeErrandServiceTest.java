package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisputeErrandServiceTest {

    @Test
    void publisher_can_raise_dispute_and_only_order_state_changes() {
        Fixture fixture = new Fixture(errand(ErrandStatus.DELIVERED, 900L));

        fixture.service().raise(77L, 500L);

        assertEquals(1, fixture.repository.casDisputeCalls);
        assertEquals(ErrandStatus.DELIVERED, fixture.repository.loggedFrom);
        assertEquals(ErrandStatus.DISPUTED.name(), fixture.notifier.status);
        assertEquals(77L, fixture.cache.evictedId);
    }

    @Test
    void current_runner_can_raise_dispute() {
        Fixture fixture = new Fixture(errand(ErrandStatus.ACCEPTED, 900L));

        fixture.service().raise(77L, 900L);

        assertEquals(1, fixture.repository.casDisputeCalls);
        assertEquals(ErrandStatus.ACCEPTED, fixture.repository.loggedFrom);
    }

    @Test
    void unrelated_user_is_rejected_before_database_cas() {
        Fixture fixture = new Fixture(errand(ErrandStatus.PICKED_UP, 900L));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service().raise(77L, 901L));

        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, error.code());
        assertEquals(0, fixture.repository.casDisputeCalls);
        assertEquals(0, fixture.cache.evictCalls);
    }

    @Test
    void database_conflict_becomes_stale_version_error() {
        Fixture fixture = new Fixture(errand(ErrandStatus.DELIVERED, 900L));
        fixture.repository.casDisputeResult = 0;

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service().raise(77L, 500L));

        assertEquals(ErrorCode.STALE_VERSION, error.code());
        assertEquals(0, fixture.cache.evictCalls);
        assertEquals(null, fixture.notifier.status);
    }

    @Test
    void already_disputed_task_cannot_be_raised_again() {
        Fixture fixture = new Fixture(errand(ErrandStatus.DISPUTED, 900L));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service().raise(77L, 500L));

        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, error.code());
        assertEquals(0, fixture.repository.casDisputeCalls);
    }

    private static Errand errand(ErrandStatus status, long runnerId) {
        return Errand.rehydrate(
                77L,
                12L,
                500L,
                ErrandType.DELIVERY,
                "争议测试任务",
                Money.fromCents(800),
                1,
                runnerId,
                status,
                1,
                0,
                3L,
                Instant.now().minusSeconds(30),
                status == ErrandStatus.DELIVERED ? Instant.now().minusSeconds(10) : null);
    }

    private static final class Fixture {
        private final DisputeRepository repository;
        private final RecordingCache cache = new RecordingCache();
        private final RecordingNotifier notifier = new RecordingNotifier();

        private Fixture(Errand errand) {
            repository = new DisputeRepository(errand);
        }

        private DisputeErrandService service() {
            return new DisputeErrandService(
                    repository,
                    new CacheEvictSupport(cache, new NoopDelayedEviction(), false, 500),
                    notifier);
        }
    }

    private static final class DisputeRepository implements ErrandRepository {
        private final Errand errand;
        private int casDisputeResult = 1;
        private int casDisputeCalls;
        private ErrandStatus loggedFrom;

        private DisputeRepository(Errand errand) {
            this.errand = errand;
        }

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.of(errand); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override
        public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to,
                                    int round, long operatorId) {
            loggedFrom = from;
        }
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override
        public int casDispute(long errandId, long expectedVersion) {
            casDisputeCalls++;
            return casDisputeResult;
        }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class RecordingCache implements ErrandCachePort {
        private long evictedId = -1L;
        private int evictCalls;

        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) { evictCalls++; evictedId = errandId; }
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) {}
    }

    private static final class NoopDelayedEviction implements CacheEvictDelayPort {
        @Override public void scheduleEvict(long errandId, Instant deliverAt) {}
    }

    private static final class RecordingNotifier implements RealtimeNotifier {
        private String status;
        @Override
        public void errandStatusChanged(long errandId, long publisherId,
                                        Long grabberId, String status, int round) {
            this.status = status;
        }
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
