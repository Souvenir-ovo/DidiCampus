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

class ConfirmErrandServiceTest {

    @Test
    void current_runner_can_confirm_and_evict_detail_cache() {
        ConfirmRepository repository = new ConfirmRepository(lockedErrand());
        RecordingCache cache = new RecordingCache();
        RecordingNotifier notifier = new RecordingNotifier();

        ConfirmErrandService service = new ConfirmErrandService(
                repository,
                new CacheEvictSupport(cache, new NoopDelayedEviction(), false, 500),
                notifier);

        ConfirmErrandService.Outcome outcome = service.confirm(
                new ConfirmErrandService.Command(77L, 900L));

        assertEquals(ConfirmErrandService.Outcome.CONFIRMED, outcome);
        assertEquals(1, repository.casAcceptCalls);
        assertEquals(77L, cache.evictedId);
        assertEquals(ErrandStatus.ACCEPTED.name(), notifier.status);
    }

    @Test
    void wrong_runner_is_rejected_before_database_cas() {
        ConfirmRepository repository = new ConfirmRepository(lockedErrand());
        ConfirmErrandService service = new ConfirmErrandService(
                repository,
                new CacheEvictSupport(new RecordingCache(), new NoopDelayedEviction(), false, 500),
                new RecordingNotifier());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.confirm(new ConfirmErrandService.Command(77L, 901L)));

        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, error.code());
        assertEquals(0, repository.casAcceptCalls);
    }

    private static Errand lockedErrand() {
        return Errand.rehydrate(
                77L,
                12L,
                500L,
                ErrandType.DELIVERY,
                "送到宿舍",
                Money.fromCents(800),
                1,
                900L,
                ErrandStatus.LOCKED,
                1,
                0,
                3,
                Instant.now().minusSeconds(10));
    }

    private static final class ConfirmRepository implements ErrandRepository {
        private final Errand errand;
        private int casAcceptCalls;

        private ConfirmRepository(Errand errand) {
            this.errand = errand;
        }

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.of(errand); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) {
            casAcceptCalls++;
            return 1;
        }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
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
        private String status;
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {
            this.status = status;
        }
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
