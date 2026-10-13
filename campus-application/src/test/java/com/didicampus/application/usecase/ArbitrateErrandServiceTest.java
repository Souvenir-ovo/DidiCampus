package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.CacheEvictDelayPort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArbitrateErrandServiceTest {

    @Test
    void publisher_favor_refunds_escrow_and_marks_runner_credit_loss() {
        Fixture fixture = new Fixture();
        fixture.refundCommit.result = true;

        ArbitrateErrandService.Result result =
                fixture.service().arbitrate(
                        77L,
                        ArbitrateErrandService.Favor.PUBLISHER,
                        9001L);

        assertEquals(ArbitrateErrandService.Result.REFUNDED_TO_PUBLISHER, result);
        assertEquals(1, fixture.refundCommit.calls);
        assertEquals(1, fixture.credit.applyCalls);
        assertEquals(54, fixture.credit.score);
        assertEquals(1, fixture.ranking.updateCalls);
        assertEquals(ErrandStatus.REFUNDED.name(), fixture.notifier.lastStatus);
        assertEquals("refund:77", fixture.audit.bizNo);
        assertEquals(true, fixture.audit.success);
    }

    @Test
    void runner_favor_releases_escrow_and_returns_settled_result() {
        Fixture fixture = new Fixture();
        fixture.settleCommit.result = true;

        ArbitrateErrandService.Result result =
                fixture.service().arbitrate(
                        77L,
                        ArbitrateErrandService.Favor.RUNNER,
                        9001L);

        assertEquals(ArbitrateErrandService.Result.SETTLED_TO_RUNNER, result);
        assertEquals(1, fixture.settleCommit.calls);
        assertEquals(1, fixture.ranking.updateCalls);
        assertEquals(ErrandStatus.SETTLED.name(), fixture.notifier.lastStatus);
        assertEquals("settle:77", fixture.audit.bizNo);
        assertEquals(true, fixture.audit.success);
    }

    @Test
    void already_finalized_errand_is_idempotent() {
        Fixture fixture = new Fixture(ErrandStatus.REFUNDED);

        ArbitrateErrandService.Result result =
                fixture.service().arbitrate(
                        77L,
                        ArbitrateErrandService.Favor.RUNNER,
                        9001L);

        assertEquals(ArbitrateErrandService.Result.ALREADY_DONE, result);
        assertEquals(0, fixture.refundCommit.calls);
        assertEquals(0, fixture.settleCommit.calls);
    }

    @Test
    void non_disputed_errand_is_rejected() {
        Fixture fixture = new Fixture(ErrandStatus.DELIVERED);

        assertThrows(
                com.didicampus.shared.BusinessException.class,
                () -> fixture.service().arbitrate(
                        77L,
                        ArbitrateErrandService.Favor.PUBLISHER,
                        9001L));
        assertEquals(0, fixture.refundCommit.calls);
        assertEquals(0, fixture.settleCommit.calls);
    }

    private static final class Fixture {
        private final RecordingErrandRepository errandRepository;
        private final RecordingWallet wallet = new RecordingWallet();
        private final RecordingFundEvent events = new RecordingFundEvent();
        private final RecordingAudit audit = new RecordingAudit();
        private final RecordingRefundCommit refundCommit;
        private final RecordingSettleCommit settleCommit;
        private final RecordingCredit credit = new RecordingCredit();
        private final RecordingRanking ranking = new RecordingRanking();
        private final RecordingNotifier notifier = new RecordingNotifier();
        private final RecordingCache cache = new RecordingCache();

        private Fixture() {
            this(ErrandStatus.DISPUTED);
        }

        private Fixture(ErrandStatus status) {
            Errand errand = Errand.rehydrate(
                    77L,
                    12L,
                    500L,
                    ErrandType.DELIVERY,
                    "仲裁任务",
                    Money.fromCents(1_000L),
                    1,
                    900L,
                    status,
                    1,
                    2,
                    8L,
                    Instant.now().minusSeconds(60),
                    Instant.now().minusSeconds(30));
            errandRepository = new RecordingErrandRepository(errand);
            refundCommit = new RecordingRefundCommit();
            settleCommit = new RecordingSettleCommit();
        }

        private ArbitrateErrandService service() {
            return new ArbitrateErrandService(
                    errandRepository,
                    wallet,
                    events,
                    audit,
                    refundCommit,
                    settleCommit,
                    new CacheEvictSupport(
                            cache,
                            new NoopDelayedEviction(),
                            false,
                            500L),
                    credit,
                    ranking,
                    new SnowflakeIdGenerator(10),
                    notifier,
                    0.10);
        }
    }

    private static final class RecordingRefundCommit extends ArbitrateRefundCommitService {
        private boolean result;
        private int calls;

        private RecordingRefundCommit() {
            super(null, null, null);
        }

        @Override
        public boolean commit(Command command) {
            calls++;
            return result;
        }
    }

    private static final class RecordingSettleCommit extends ArbitrateSettleCommitService {
        private boolean result;
        private int calls;

        private RecordingSettleCommit() {
            super(null, null, null, null);
        }

        @Override
        public boolean commit(Command command) {
            calls++;
            return result;
        }
    }

    private static final class RecordingErrandRepository implements ErrandRepository {
        private final Errand errand;

        private RecordingErrandRepository(Errand errand) {
            this.errand = errand;
        }

        @Override
        public void insert(Errand errand) {
        }

        @Override
        public Optional<Errand> findById(long errandId) {
            return Optional.of(errand);
        }

        @Override
        public int casLockForRunner(long errandId, long runnerId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casPublish(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public void appendStatusLog(long errandId, ErrandStatus from,
                                    ErrandStatus to, int round, long operatorId) {
        }

        @Override
        public int casAccept(long errandId, long runnerId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casTransferToNext(long errandId, long nextRunnerId,
                                     long expectedVersion, int expectedRound) {
            return 0;
        }

        @Override
        public int casRevertToPublished(long errandId, long expectedVersion,
                                        int expectedRound) {
            return 0;
        }

        @Override
        public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) {
            return List.of();
        }

        @Override
        public int casPickUp(long errandId, long runnerId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casDeliver(long errandId, long runnerId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casSettle(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casRefundFromDispute(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casCancel(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casDispute(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public int casSettleFromDispute(long errandId, long expectedVersion) {
            return 0;
        }

        @Override
        public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) {
            return List.of();
        }
    }

    private static final class RecordingWallet implements WalletRepository {
        private final EscrowOrder escrow = EscrowOrder.held(
                300L,
                12L,
                77L,
                500L,
                Money.fromCents(1_000L));

        @Override
        public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
            return Optional.empty();
        }

        @Override
        public Optional<WalletAccount> findById(long accountId) {
            return Optional.empty();
        }

        @Override
        public int casDebit(long accountId, Money amount) {
            return 1;
        }

        @Override
        public int casCredit(long accountId, Money amount) {
            return 1;
        }

        @Override
        public void insertLedger(LedgerEntry entry) {
        }

        @Override
        public void insertEscrow(EscrowOrder order) {
        }

        @Override
        public boolean escrowExists(long campusId, long errandId) {
            return true;
        }

        @Override
        public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
            return Optional.of(escrow);
        }

        @Override
        public int casEscrowStatus(long campusId, long errandId,
                                   EscrowOrder.EscrowStatus from,
                                   EscrowOrder.EscrowStatus to) {
            return 1;
        }

        @Override
        public boolean ledgerExists(String bizNo) {
            return false;
        }
    }

    private static final class RecordingFundEvent implements FundEventPort {
        @Override
        public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
            return localWork.execute();
        }
    }

    private static final class RecordingAudit implements FundAuditPort {
        private String bizNo;
        private boolean success;

        @Override
        public void record(String bizNo, String action, long errandId,
                           long operatorId, String detailJson,
                           boolean success, String message) {
            this.bizNo = bizNo;
            this.success = success;
        }
    }

    private static final class RecordingCredit implements CreditRepository {
        private int score = 62;
        private int applyCalls;

        @Override
        public int scoreOf(long userId) {
            return score;
        }

        @Override
        public Optional<CreditScore> find(long userId) {
            return Optional.empty();
        }

        @Override
        public boolean applyEvent(CreditEvent event) {
            applyCalls++;
            if (event.type() == CreditEventType.DISPUTE_LOSE) {
                score += event.delta();
            }
            return true;
        }

        @Override
        public List<CreditEvent> recentEvents(long userId, int days, int limit) {
            return List.of();
        }

        @Override
        public int windowDelta(long userId, int windowDays) {
            return 0;
        }

        @Override
        public int calibrateScores(int windowDays, int limit) {
            return 0;
        }
    }

    private static final class RecordingRanking implements CreditRankingPort {
        private int updateCalls;

        @Override
        public void update(long campusId, long userId, int score) {
            updateCalls++;
        }

        @Override
        public List<Entry> top(long campusId, int limit) {
            return List.of();
        }
    }

    private static final class RecordingNotifier implements RealtimeNotifier {
        private String lastStatus;

        @Override
        public void errandStatusChanged(long errandId, long publisherId,
                                        Long grabberId, String status, int round) {
            lastStatus = status;
        }

        @Override
        public void notificationArrived(long userId, long errandId,
                                        String type, String content) {
        }

        @Override
        public void creditChanged(long userId, int newScore, int delta, String reason) {
        }
    }

    private static final class RecordingCache implements ErrandCachePort {
        @Override
        public Optional<CachedErrand> get(long errandId) {
            return Optional.empty();
        }

        @Override
        public void put(long errandId, String payloadJson) {
        }

        @Override
        public void putEmpty(long errandId) {
        }

        @Override
        public void evict(long errandId) {
        }

        @Override
        public boolean tryAcquireRebuild(long errandId) {
            return false;
        }

        @Override
        public void releaseRebuild(long errandId) {
        }

        @Override
        public boolean mightExist(long errandId) {
            return true;
        }

        @Override
        public void registerExisting(long errandId) {
        }
    }

    private static final class NoopDelayedEviction implements CacheEvictDelayPort {
        @Override
        public void scheduleEvict(long errandId, Instant deliverAt) {
        }
    }
}
