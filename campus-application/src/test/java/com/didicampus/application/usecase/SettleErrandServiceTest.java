package com.didicampus.application.usecase;

import com.didicampus.domain.credit.model.CreditEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettleErrandServiceTest {

    @Test
    void settle_releases_escrow_to_runner_and_commission_accounts() {
        SettleFixture fixture = new SettleFixture(deliveredErrand());

        SettleErrandService.Result result = fixture.service().settle(66L, 500L);

        assertEquals(SettleErrandService.Result.SETTLED, result);
        assertEquals(1, fixture.wallet.casEscrowCalls);
        assertEquals(1, fixture.repository.casSettleCalls);
        assertEquals(3, fixture.wallet.ledger.size());
        assertEquals(1_000L, fixture.wallet.ledger.get(0).amount().cents());
        assertEquals(900L, fixture.wallet.ledger.get(1).amount().cents());
        assertEquals(100L, fixture.wallet.ledger.get(2).amount().cents());
        assertEquals(1, fixture.credit.applyCalls);
        assertEquals(1, fixture.eventPort.publishCalls);
        assertEquals(true, fixture.audit.success);
        assertEquals(66L, fixture.cache.evictedId);
        assertEquals(1, fixture.ranking.updateCalls);
        assertEquals(ErrandStatus.SETTLED.name(), fixture.notifier.lastStatus);
    }

    @Test
    void already_settled_returns_idempotent_result_without_side_effects() {
        SettleFixture fixture = new SettleFixture(settledErrand());

        SettleErrandService.Result result = fixture.service().settle(66L, 500L);

        assertEquals(SettleErrandService.Result.ALREADY_SETTLED, result);
        assertEquals(0, fixture.eventPort.publishCalls);
        assertEquals(0, fixture.wallet.ledger.size());
    }

    @Test
    void escrow_status_conflict_returns_conflict_without_ledger() {
        SettleFixture fixture = new SettleFixture(deliveredErrand());
        fixture.wallet.casEscrowResult = 0;

        SettleErrandService.Result result = fixture.service().settle(66L, 500L);

        assertEquals(SettleErrandService.Result.CONFLICT, result);
        assertEquals(0, fixture.wallet.ledger.size());
        assertEquals(false, fixture.audit.success);
    }

    @Test
    void errand_cas_conflict_returns_conflict_before_writing_ledger() {
        SettleFixture fixture = new SettleFixture(deliveredErrand());
        fixture.repository.casSettleResult = 0;

        SettleErrandService.Result result = fixture.service().settle(66L, 500L);

        assertEquals(SettleErrandService.Result.CONFLICT, result);
        assertEquals(1, fixture.wallet.casEscrowCalls);
        assertEquals(0, fixture.wallet.ledger.size());
        assertEquals(false, fixture.audit.success);
    }

    private static Errand deliveredErrand() {
        return Errand.rehydrate(
                66L,
                10L,
                500L,
                ErrandType.DELIVERY,
                "送资料",
                Money.fromCents(1_000),
                1,
                900L,
                ErrandStatus.DELIVERED,
                1,
                0,
                7,
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(60));
    }

    private static Errand settledErrand() {
        return Errand.rehydrate(
                66L,
                10L,
                500L,
                ErrandType.DELIVERY,
                "送资料",
                Money.fromCents(1_000),
                1,
                900L,
                ErrandStatus.SETTLED,
                1,
                0,
                8,
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(60));
    }

    private static final class SettleFixture {
        private final SettleRepository repository;
        private final SettleWallet wallet = new SettleWallet();
        private final RecordingCredit credit = new RecordingCredit();
        private final RecordingFundEvent eventPort = new RecordingFundEvent();
        private final RecordingAudit audit = new RecordingAudit();
        private final RecordingCache cache = new RecordingCache();
        private final RecordingRanking ranking = new RecordingRanking();
        private final RecordingNotifier notifier = new RecordingNotifier();

        private SettleFixture(Errand errand) {
            this.repository = new SettleRepository(errand);
        }

        private SettleErrandService service() {
            SettleErrandCommitService commitService = new SettleErrandCommitService(
                    repository,
                    wallet,
                    credit,
                    new SnowflakeIdGenerator(10));
            return new SettleErrandService(
                    repository,
                    wallet,
                    eventPort,
                    audit,
                    commitService,
                    new CacheEvictSupport(cache, new NoopDelayedEviction(), false, 500),
                    credit,
                    ranking,
                    notifier,
                    0.10);
        }
    }

    private static final class SettleRepository implements ErrandRepository {
        private final Errand errand;
        private int casSettleResult = 1;
        private int casSettleCalls;

        private SettleRepository(Errand errand) {
            this.errand = errand;
        }

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
        @Override public int casSettle(long errandId, long expectedVersion) {
            casSettleCalls++;
            return casSettleResult;
        }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class SettleWallet implements WalletRepository {
        private final WalletAccount escrowAccount = new WalletAccount(
                101L, -1L, AccountType.ESCROW, Money.fromCents(1_000), Money.ZERO, 0);
        private final WalletAccount runnerAccount = new WalletAccount(
                102L, 900L, AccountType.USER, Money.fromCents(200), Money.ZERO, 0);
        private final WalletAccount commissionAccount = new WalletAccount(
                103L, -2L, AccountType.COMMISSION, Money.ZERO, Money.ZERO, 0);
        private final List<LedgerEntry> ledger = new ArrayList<>();
        private int casEscrowResult = 1;
        private int casEscrowCalls;

        @Override
        public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
            if (ownerId == -1L && type == AccountType.ESCROW) {
                return Optional.of(escrowAccount);
            }
            if (ownerId == 900L && type == AccountType.USER) {
                return Optional.of(runnerAccount);
            }
            if (ownerId == -2L && type == AccountType.COMMISSION) {
                return Optional.of(commissionAccount);
            }
            return Optional.empty();
        }

        @Override public Optional<WalletAccount> findById(long accountId) { return Optional.empty(); }
        @Override public int casDebit(long accountId, Money amount) { return 1; }
        @Override public int casCredit(long accountId, Money amount) { return 1; }
        @Override public void insertLedger(LedgerEntry entry) { ledger.add(entry); }
        @Override public void insertEscrow(EscrowOrder order) {}
        @Override public boolean escrowExists(long campusId, long errandId) { return true; }
        @Override public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
            return Optional.of(EscrowOrder.held(201L, campusId, errandId, 500L, Money.fromCents(1_000)));
        }
        @Override public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) {
            casEscrowCalls++;
            return casEscrowResult;
        }
        @Override public boolean ledgerExists(String bizNo) { return false; }
    }

    private static final class RecordingCredit implements CreditRepository {
        private int applyCalls;
        @Override public int scoreOf(long userId) { return 62; }
        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { applyCalls++; return true; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class RecordingFundEvent implements FundEventPort {
        private int publishCalls;
        @Override public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
            publishCalls++;
            return localWork.execute();
        }
    }

    private static final class RecordingAudit implements FundAuditPort {
        private boolean success;
        @Override public void record(String bizNo, String action, long errandId, long operatorId, String detailJson, boolean success, String message) {
            this.success = success;
        }
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

    private static final class RecordingRanking implements CreditRankingPort {
        private int updateCalls;
        @Override public void update(long campusId, long userId, int score) { updateCalls++; }
        @Override public List<Entry> top(long campusId, int limit) { return List.of(); }
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
