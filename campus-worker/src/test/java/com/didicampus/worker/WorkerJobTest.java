package com.didicampus.worker;

import com.didicampus.application.usecase.CacheEvictSupport;
import com.didicampus.application.usecase.CacheConsistencyCheckService;
import com.didicampus.application.usecase.CreditCalibrationService;
import com.didicampus.application.usecase.SettleErrandCommitService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.application.usecase.TimeoutTransferCommitService;
import com.didicampus.application.usecase.TimeoutTransferService;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.domain.recon.ports.ReconRepository;
import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerJobTest {

    @Test
    void localMessageRetryMarksSentOrRetryIndividually() {
        FakeLocalMessages messages = new FakeLocalMessages(List.of(
                new LocalMessageRepository.PendingMessage(1L, "ok", "topic", "{}", Instant.now(), 0),
                new LocalMessageRepository.PendingMessage(2L, "bad", "topic", "{}", Instant.now(), 0)
        ));
        DelayMessagePort delay = (topic, messageKey, payload, deliverAt) -> {
            if ("bad".equals(messageKey)) {
                throw new IllegalStateException("send failed");
            }
        };

        LocalMessageRetryJob job = new LocalMessageRetryJob(messages, delay, 10, 3);
        job.retryPendingMessages();

        assertEquals(List.of("ok"), messages.sentKeys);
        assertEquals(List.of("bad"), messages.retryKeys);
    }

    @Test
    void timeoutScanDelegatesEachTimedOutErrand() {
        FakeErrands errands = new FakeErrands();
        errands.confirmTimeouts = List.of(lockedErrand(10L, 0), lockedErrand(11L, 1));
        StubTimeoutService timeoutService = new StubTimeoutService();

        TimeoutScanJob job = new TimeoutScanJob(errands, timeoutService, 2L, 20);
        job.scanConfirmTimeouts();

        assertEquals(List.of("10:0", "11:1"), timeoutService.calls);
        assertEquals(302L, errands.lastConfirmTimeoutSeconds);
        assertEquals(20, errands.lastConfirmLimit);
    }

    @Test
    void autoSettleScanUsesSystemOperator() {
        FakeErrands errands = new FakeErrands();
        errands.autoSettleDue = List.of(deliveredErrand(20L), deliveredErrand(21L));
        StubSettleService settleService = new StubSettleService();

        AutoSettleScanJob job = new AutoSettleScanJob(errands, settleService, 3600L, 50);
        job.scanAutoSettle();

        assertEquals(List.of("20:-1", "21:-1"), settleService.calls);
        assertEquals(3600L, errands.lastAutoSettleSeconds);
        assertEquals(50, errands.lastAutoSettleLimit);
    }

    @Test
    void creditCalibrationJobUsesThirtyDayWindow() {
        StubCreditCalibrationService service = new StubCreditCalibrationService();
        CreditCalibrationJob job = new CreditCalibrationJob(service, 500);

        job.calibrate();

        assertEquals(List.of("30:500"), service.calls);
    }

    @Test
    void reconciliationJobRecordsThreeKindsOfDiffs() {
        FakeReconRepository recon = new FakeReconRepository();
        recon.balanceDelta = 9L;
        recon.accountDiffs = List.of(new ReconRepository.AccountDiff(1L, 100L, 300L, 250L));
        recon.escrowDiffs = List.of(new ReconRepository.EscrowDiff(20L, "HELD", "closed_errand"));
        ReconciliationJob job = new ReconciliationJob(recon);

        int diffs = job.run(LocalDate.of(2026, 10, 9));

        assertEquals(3, diffs);
        assertEquals(List.of("DEBIT_CREDIT:GLOBAL", "SNAPSHOT:1", "ESCROW_CLOSURE:20"), recon.recorded);
    }

    @Test
    void cacheConsistencyJobDelegatesToApplicationService() {
        StubCacheConsistencyCheckService service = new StubCacheConsistencyCheckService();
        CacheConsistencyCheckJob job = new CacheConsistencyCheckJob(service);

        job.check();

        assertEquals(1, service.calls);
    }

    private static Errand lockedErrand(long id, int round) {
        return Errand.rehydrate(
                id,
                1L,
                1001L,
                ErrandType.DELIVERY,
                "locked",
                Money.fromCents(100),
                1,
                2001L,
                ErrandStatus.LOCKED,
                1,
                round,
                2L,
                Instant.now().minusSeconds(600));
    }

    private static Errand deliveredErrand(long id) {
        return Errand.rehydrate(
                id,
                1L,
                1001L,
                ErrandType.DELIVERY,
                "delivered",
                Money.fromCents(100),
                1,
                2001L,
                ErrandStatus.DELIVERED,
                1,
                0,
                5L,
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(3600));
    }

    private static final class FakeLocalMessages implements LocalMessageRepository {
        private final List<PendingMessage> pending;
        private final List<String> sentKeys = new ArrayList<>();
        private final List<String> retryKeys = new ArrayList<>();

        private FakeLocalMessages(List<PendingMessage> pending) {
            this.pending = pending;
        }

        @Override
        public boolean enqueue(long id, String messageKey, String topic, String payload, Instant deliverAt) {
            return false;
        }

        @Override
        public void markSent(String messageKey) {
            sentKeys.add(messageKey);
        }

        @Override
        public List<PendingMessage> findPending(int limit) {
            return pending;
        }

        @Override
        public void markRetry(String messageKey, int maxRetry) {
            retryKeys.add(messageKey);
        }
    }

    private static final class FakeErrands implements ErrandRepository {
        private List<Errand> confirmTimeouts = List.of();
        private List<Errand> autoSettleDue = List.of();
        private long lastConfirmTimeoutSeconds;
        private int lastConfirmLimit;
        private long lastAutoSettleSeconds;
        private int lastAutoSettleLimit;

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.empty(); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }

        @Override
        public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) {
            lastConfirmTimeoutSeconds = timeoutSeconds;
            lastConfirmLimit = limit;
            return confirmTimeouts;
        }

        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }

        @Override
        public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) {
            lastAutoSettleSeconds = autoSettleSeconds;
            lastAutoSettleLimit = limit;
            return autoSettleDue;
        }
    }

    private static class StubTimeoutService extends TimeoutTransferService {
        private final List<String> calls = new ArrayList<>();

        StubTimeoutService() {
            super(null, null, null, null, null, null, null,
                    null, new SnowflakeIdGenerator(1), null, 300L, 5);
        }

        @Override
        public long confirmationTimeoutSeconds() {
            return 300L;
        }

        @Override
        public Outcome handleTimeout(long errandId, int expectedRound) {
            calls.add(errandId + ":" + expectedRound);
            return Outcome.TRANSFERRED;
        }
    }

    private static class StubSettleService extends SettleErrandService {
        private final List<String> calls = new ArrayList<>();

        StubSettleService() {
            super((ErrandRepository) null, (WalletRepository) null, (FundEventPort) null,
                    (FundAuditPort) null, (SettleErrandCommitService) null,
                    (CacheEvictSupport) null, (CreditRepository) null,
                    (CreditRankingPort) null, (RealtimeNotifier) null, 0.05D);
        }

        @Override
        public Result settle(long errandId, long operatorId) {
            calls.add(errandId + ":" + operatorId);
            return Result.SETTLED;
        }
    }

    private static final class StubCreditCalibrationService extends CreditCalibrationService {
        private final List<String> calls = new ArrayList<>();

        private StubCreditCalibrationService() {
            super(null);
        }

        @Override
        public int calibrate(int windowDays, int limit) {
            calls.add(windowDays + ":" + limit);
            return 3;
        }
    }

    private static final class FakeReconRepository implements ReconRepository {
        private long balanceDelta;
        private List<AccountDiff> accountDiffs = List.of();
        private List<EscrowDiff> escrowDiffs = List.of();
        private final List<String> recorded = new ArrayList<>();

        @Override public long debitMinusCredit() { return balanceDelta; }
        @Override public List<AccountDiff> findSnapshotDiffs() { return accountDiffs; }
        @Override public List<EscrowDiff> findEscrowClosureDiffs() { return escrowDiffs; }

        @Override
        public void recordDiff(LocalDate date, String checkType, String subject,
                               Long expected, Long actual, String detail) {
            recorded.add(checkType + ":" + subject);
        }

        @Override public int countDiffs(LocalDate date) { return 0; }
    }

    private static final class StubCacheConsistencyCheckService extends CacheConsistencyCheckService {
        private int calls;

        private StubCacheConsistencyCheckService() {
            super(null, null, null, null, 1);
        }

        @Override
        public int checkOnce() {
            calls++;
            return 0;
        }
    }
}
