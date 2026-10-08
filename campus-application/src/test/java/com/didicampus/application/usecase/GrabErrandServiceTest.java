package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabRateLimiterPort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrabErrandServiceTest {

    @Test
    void rate_limit_happens_before_credit_and_slot_checks() {
        CountingRateLimiter limiter = new CountingRateLimiter(false);
        CountingCredit credit = new CountingCredit();
        CountingSlot slot = new CountingSlot(SlotOutcome.NOT_GRABBABLE);
        GrabErrandService service = new GrabErrandService(
                slot,
                new MemoryCandidateQueue(),
                new EmptyErrandRepository(),
                new StubCommitService(false),
                credit,
                new EmptyErrandQuery(),
                limiter,
                new SnowflakeIdGenerator(3),
                null,
                40,
                5);

        GrabErrandService.Result result = service.grab(
                new GrabErrandService.Command(1, 2, "limited-request"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(0, credit.scoreCalls);
        assertEquals(0, slot.acquireCalls);
    }

    @Test
    void successful_redis_acquisition_must_also_pass_database_commit() {
        CountingSlot slot = new CountingSlot(SlotOutcome.ACQUIRED);
        StubCommitService commit = new StubCommitService(true);
        GrabErrandService service = new GrabErrandService(
                slot,
                new MemoryCandidateQueue(),
                new PublishedErrandRepository(),
                commit,
                new CountingCredit(),
                new EmptyErrandQuery(),
                new CountingRateLimiter(true),
                new SnowflakeIdGenerator(4),
                new NoopTimeoutTransferService(),
                40,
                5);

        GrabErrandService.Result result = service.grab(
                new GrabErrandService.Command(1, 2, "request-ok"));

        assertTrue(result.grabbed());
        assertEquals(ErrorCode.OK, result.code());
        assertEquals(1, commit.calls);
        assertEquals(0, slot.rollbackCalls);
    }

    @Test
    void database_conflict_returns_the_slot_and_enqueues_candidate() {
        CountingSlot slot = new CountingSlot(SlotOutcome.ACQUIRED);
        MemoryCandidateQueue queue = new MemoryCandidateQueue();
        GrabErrandService service = new GrabErrandService(
                slot,
                queue,
                new PublishedErrandRepository(),
                new StubCommitService(false),
                new CountingCredit(),
                new EmptyErrandQuery(),
                new CountingRateLimiter(true),
                new SnowflakeIdGenerator(5),
                new NoopTimeoutTransferService(),
                40,
                5);

        GrabErrandService.Result result = service.grab(
                new GrabErrandService.Command(1, 2, "request-conflict"));

        assertEquals(ErrorCode.GRAB_CONFLICT, result.code());
        assertEquals(1, slot.rollbackCalls);
        assertEquals(1, queue.offerCalls);
        assertEquals(1L, result.candidateRank());
    }

    @Test
    void timeout_registration_failure_does_not_undo_a_committed_grab() {
        CountingSlot slot = new CountingSlot(SlotOutcome.ACQUIRED);
        GrabErrandService service = new GrabErrandService(
                slot,
                new MemoryCandidateQueue(),
                new PublishedErrandRepository(),
                new StubCommitService(true),
                new CountingCredit(),
                new EmptyErrandQuery(),
                new CountingRateLimiter(true),
                new SnowflakeIdGenerator(7),
                new ThrowingTimeoutTransferService(),
                40,
                5);

        GrabErrandService.Result result = service.grab(
                new GrabErrandService.Command(1, 2, "request-timeout-registration-fails"));

        assertTrue(result.grabbed());
        assertEquals(0, slot.rollbackCalls);
    }

    private static final class StubCommitService extends GrabErrandCommitService {
        private final boolean outcome;
        private int calls;

        private StubCommitService(boolean outcome) {
            super(null, null, null);
            this.outcome = outcome;
        }

        @Override
        public boolean commit(CommitCommand command) {
            calls++;
            return outcome;
        }
    }

    private static final class NoopTimeoutTransferService extends TimeoutTransferService {
        private NoopTimeoutTransferService() {
            super(null, null, null, null, null, null, null, null,
                    null, null, 300, 5);
        }

        @Override
        public void scheduleFirstTimeout(long errandId, int round, long version) {
        }
    }

    private static final class ThrowingTimeoutTransferService extends TimeoutTransferService {
        private ThrowingTimeoutTransferService() {
            super(null, null, null, null, null, null, null, null,
                    null, null, 300, 5);
        }

        @Override
        public void scheduleFirstTimeout(long errandId, int round, long version) {
            throw new IllegalStateException("message table unavailable");
        }
    }

    private static final class CountingRateLimiter implements GrabRateLimiterPort {
        private final boolean allowed;
        private CountingRateLimiter(boolean allowed) { this.allowed = allowed; }
        @Override public boolean tryPass(long errandId, long runnerId) { return allowed; }
    }

    private static final class CountingCredit implements CreditRepository {
        private int scoreCalls;
        @Override public int scoreOf(long userId) { scoreCalls++; return 80; }
        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class CountingSlot implements GrabSlotPort {
        private final SlotOutcome outcome;
        private int acquireCalls;
        private int rollbackCalls;
        private CountingSlot(SlotOutcome outcome) { this.outcome = outcome; }
        @Override public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
            acquireCalls++;
            return outcome;
        }
        @Override public void rollback(long errandId, long runnerId, String requestId) { rollbackCalls++; }
        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) {}
        @Override public long remainingSlot(long errandId) { return 0; }
    }

    private static final class MemoryCandidateQueue implements CandidateQueuePort {
        private int offerCalls;
        @Override public void offer(long errandId, long runnerId, double score) { offerCalls++; }
        @Override public Optional<Long> pollBest(long errandId) { return Optional.empty(); }
        @Override public long size(long errandId) { return offerCalls; }
    }

    private static final class EmptyErrandQuery implements ErrandQueryPort {
        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static class EmptyErrandRepository implements ErrandRepository {
        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.empty(); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
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

    private static final class PublishedErrandRepository extends EmptyErrandRepository {
        private final Errand existing = Errand.rehydrate(
                1, 10, 20, ErrandType.DELIVERY, "test",
                Money.fromCents(100), 1, null,
                ErrandStatus.PUBLISHED, 0, 0, 0, null);

        @Override
        public Optional<Errand> findById(long errandId) {
            return Optional.of(existing);
        }
    }
}
