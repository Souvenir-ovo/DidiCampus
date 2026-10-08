package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeoutTransferServiceTest {

    @Test
    void stale_round_message_is_ignored_without_polling_candidate_queue() {
        TimeoutRepository repository = new TimeoutRepository(lockedErrand(2));
        CountingQueue queue = new CountingQueue(Optional.of(300L));
        TimeoutTransferService service = service(
                repository,
                queue,
                new CountingSlot(),
                new StubCommitService(),
                new RecordingCredit());

        TimeoutTransferService.Outcome outcome = service.handleTimeout(88L, 1);

        assertEquals(TimeoutTransferService.Outcome.SKIPPED, outcome);
        assertEquals(0, queue.pollCalls);
    }

    @Test
    void candidate_is_transferred_and_next_timeout_message_is_sent() {
        TimeoutRepository repository = new TimeoutRepository(lockedErrand(0));
        CountingQueue queue = new CountingQueue(Optional.of(300L));
        CountingDelayMessage delay = new CountingDelayMessage();
        StubCommitService commit = new StubCommitService();
        commit.transferResult = new TimeoutTransferCommitService.StepResult(
                true,
                new TimeoutTransferCommitService.PendingMessage(
                        DelayTaskPolicy.CONFIRM_TIMEOUT_TOPIC,
                        "timeout:88:1",
                        "{\"errandId\":88,\"round\":1,\"version\":4}",
                        Instant.now().plusSeconds(300)));

        TimeoutTransferService service = service(
                repository,
                queue,
                new CountingSlot(),
                commit,
                new RecordingCredit(),
                delay);

        TimeoutTransferService.Outcome outcome = service.handleTimeout(88L, 0);

        assertEquals(TimeoutTransferService.Outcome.TRANSFERRED, outcome);
        assertEquals(1, queue.pollCalls);
        assertEquals(1, delay.sendCalls);
        assertEquals(1, commit.transferCalls);
    }

    @Test
    void empty_candidate_queue_reopens_task_restores_slots_and_records_credit_penalty() {
        TimeoutRepository repository = new TimeoutRepository(lockedErrand(0));
        CountingQueue queue = new CountingQueue(Optional.empty());
        CountingSlot slot = new CountingSlot();
        RecordingCredit credit = new RecordingCredit();
        StubCommitService commit = new StubCommitService();
        commit.reopenResult = new TimeoutTransferCommitService.StepResult(true, null);

        TimeoutTransferService service = service(
                repository,
                queue,
                slot,
                commit,
                credit);

        TimeoutTransferService.Outcome outcome = service.handleTimeout(88L, 0);

        assertEquals(TimeoutTransferService.Outcome.REOPENED, outcome);
        assertEquals(1, slot.initCalls);
        assertEquals(1, credit.eventCalls);
        assertEquals(1, commit.reopenCalls);
    }

    private static TimeoutTransferService service(TimeoutRepository repository,
                                                  CountingQueue queue,
                                                  CountingSlot slot,
                                                  StubCommitService commit,
                                                  RecordingCredit credit) {
        return service(repository, queue, slot, commit, credit, new CountingDelayMessage());
    }

    private static TimeoutTransferService service(TimeoutRepository repository,
                                                  CountingQueue queue,
                                                  CountingSlot slot,
                                                  StubCommitService commit,
                                                  RecordingCredit credit,
                                                  CountingDelayMessage delay) {
        return new TimeoutTransferService(
                repository,
                queue,
                slot,
                delay,
                new MemoryMessages(),
                commit,
                new CacheEvictSupport(new MemoryCache(), new NoopDelayedEviction(), false, 500),
                credit,
                new SnowflakeIdGenerator(6),
                new NoopNotifier(),
                300,
                5);
    }

    private static Errand lockedErrand(int round) {
        return Errand.rehydrate(
                88L,
                12L,
                500L,
                ErrandType.DELIVERY,
                "送到宿舍",
                Money.fromCents(800),
                2,
                700L,
                ErrandStatus.LOCKED,
                1,
                round,
                3,
                Instant.now().minusSeconds(600));
    }

    private static final class StubCommitService extends TimeoutTransferCommitService {
        private StepResult transferResult = StepResult.skipped();
        private StepResult reopenResult = StepResult.skipped();
        private int transferCalls;
        private int reopenCalls;

        private StubCommitService() {
            super(null, null, null);
        }

        @Override
        public StepResult transfer(Errand errand, long nextRunnerId, long timeoutSeconds) {
            transferCalls++;
            return transferResult;
        }

        @Override
        public StepResult reopen(Errand errand) {
            reopenCalls++;
            return reopenResult;
        }
    }

    private static final class TimeoutRepository implements ErrandRepository {
        private final Errand errand;
        private TimeoutRepository(Errand errand) { this.errand = errand; }
        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.of(errand); }
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

    private static final class CountingQueue implements CandidateQueuePort {
        private final Optional<Long> candidate;
        private int pollCalls;
        private CountingQueue(Optional<Long> candidate) { this.candidate = candidate; }
        @Override public void offer(long errandId, long runnerId, double score) {}
        @Override public Optional<Long> pollBest(long errandId) {
            pollCalls++;
            return candidate;
        }
        @Override public long size(long errandId) { return 0; }
    }

    private static final class CountingSlot implements GrabSlotPort {
        private int initCalls;
        @Override public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) { return SlotOutcome.NOT_GRABBABLE; }
        @Override public void rollback(long errandId, long runnerId, String requestId) {}
        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) { initCalls++; }
        @Override public long remainingSlot(long errandId) { return 0; }
    }

    private static final class CountingDelayMessage implements DelayMessagePort {
        private int sendCalls;
        @Override public void send(String topic, String messageKey, String payload, Instant deliverAt) { sendCalls++; }
    }

    private static final class MemoryMessages implements LocalMessageRepository {
        @Override public boolean enqueue(long id, String messageKey, String topic, String payload, Instant deliverAt) { return true; }
        @Override public void markSent(String messageKey) {}
        @Override public List<PendingMessage> findPending(int limit) { return List.of(); }
        @Override public void markRetry(String messageKey, int maxRetry) {}
    }

    private static final class RecordingCredit implements CreditRepository {
        private int eventCalls;
        @Override public int scoreOf(long userId) { return 60; }
        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { eventCalls++; return true; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class MemoryCache implements ErrandCachePort {
        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) {}
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) {}
    }

    private static final class NoopDelayedEviction implements CacheEvictDelayPort {
        @Override public void scheduleEvict(long errandId, Instant deliverAt) {}
    }

    private static final class NoopNotifier implements RealtimeNotifier {
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {}
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
