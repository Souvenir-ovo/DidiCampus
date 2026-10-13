package com.didicampus.application.usecase;

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
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancelErrandServiceTest {

    @Test
    void publisher_can_cancel_and_receive_full_refund() {
        Fixture fixture = new Fixture(errand(ErrandStatus.PUBLISHED));

        CancelErrandService.Result result = fixture.service().cancel(88L, 500L);

        assertEquals(CancelErrandService.Result.REFUNDED, result);
        assertEquals(1, fixture.repository.casCancelCalls);
        assertEquals(ErrandStatus.PUBLISHED, fixture.repository.loggedFrom);
        assertEquals(1, fixture.wallet.casEscrowCalls);
        assertEquals(2, fixture.wallet.ledger.size());
        assertEquals(1_000L, fixture.wallet.ledger.get(0).amount().cents());
        assertEquals(1_000L, fixture.wallet.ledger.get(1).amount().cents());
        assertEquals(88L, fixture.cache.evictedId);
        assertEquals(ErrandStatus.CANCELLED.name(), fixture.notifier.status);
        assertEquals(true, fixture.audit.success);
    }

    @Test
    void repeated_cancel_is_idempotent() {
        Fixture fixture = new Fixture(errand(ErrandStatus.CANCELLED));

        CancelErrandService.Result result = fixture.service().cancel(88L, 500L);

        assertEquals(CancelErrandService.Result.ALREADY_REFUNDED, result);
        assertEquals(0, fixture.repository.casCancelCalls);
        assertEquals(0, fixture.wallet.ledger.size());
        assertEquals(0, fixture.eventPort.publishCalls);
    }

    @Test
    void non_publisher_cannot_cancel() {
        Fixture fixture = new Fixture(errand(ErrandStatus.PUBLISHED));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service().cancel(88L, 501L));

        assertEquals(ErrorCode.NOT_PUBLISHER, error.code());
        assertEquals(0, fixture.repository.casCancelCalls);
        assertEquals(0, fixture.eventPort.publishCalls);
    }

    @Test
    void escrow_conflict_returns_conflict_without_writing_ledger() {
        Fixture fixture = new Fixture(errand(ErrandStatus.PUBLISHED));
        fixture.wallet.casEscrowResult = 0;

        CancelErrandService.Result result = fixture.service().cancel(88L, 500L);

        assertEquals(CancelErrandService.Result.CONFLICT, result);
        assertEquals(0, fixture.repository.casCancelCalls);
        assertEquals(0, fixture.wallet.ledger.size());
        assertEquals(false, fixture.audit.success);
    }

    @Test
    void draft_status_is_recorded_correctly() {
        Fixture fixture = new Fixture(errand(ErrandStatus.DRAFT));

        CancelErrandService.Result result = fixture.service().cancel(88L, 500L);

        assertEquals(CancelErrandService.Result.REFUNDED, result);
        assertEquals(ErrandStatus.DRAFT, fixture.repository.loggedFrom);
    }

    private static Errand errand(ErrandStatus status) {
        return Errand.rehydrate(
                88L,
                10L,
                500L,
                ErrandType.DELIVERY,
                "取消测试任务",
                Money.fromCents(1_000),
                1,
                null,
                status,
                0,
                0,
                4,
                null,
                null);
    }

    private static final class Fixture {
        private final CancelRepository repository;
        private final CancelWallet wallet = new CancelWallet();
        private final RecordingFundEvent eventPort = new RecordingFundEvent();
        private final RecordingAudit audit = new RecordingAudit();
        private final RecordingCache cache = new RecordingCache();
        private final RecordingNotifier notifier = new RecordingNotifier();

        private Fixture(Errand errand) {
            repository = new CancelRepository(errand);
        }

        private CancelErrandService service() {
            return new CancelErrandService(
                    repository,
                    eventPort,
                    audit,
                    new CancelErrandCommitService(
                            repository,
                            wallet,
                            new SnowflakeIdGenerator(11)),
                    new CacheEvictSupport(
                            cache,
                            new NoopDelayedEviction(),
                            false,
                            500),
                    notifier);
        }
    }

    private static final class CancelRepository implements ErrandRepository {
        private final Errand errand;
        private int casCancelCalls;
        private int casCancelResult = 1;
        private ErrandStatus loggedFrom;

        private CancelRepository(Errand errand) {
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
        @Override
        public int casCancel(long errandId, long expectedVersion) {
            casCancelCalls++;
            return casCancelResult;
        }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class CancelWallet implements WalletRepository {
        private final WalletAccount escrowAccount = new WalletAccount(
                101L,
                -1L,
                AccountType.ESCROW,
                Money.fromCents(1_000),
                Money.ZERO,
                0);
        private final WalletAccount publisherAccount = new WalletAccount(
                102L,
                500L,
                AccountType.USER,
                Money.fromCents(200),
                Money.ZERO,
                0);
        private final List<LedgerEntry> ledger = new ArrayList<>();
        private int casEscrowResult = 1;
        private int casEscrowCalls;

        @Override
        public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
            if (ownerId == -1L && type == AccountType.ESCROW) {
                return Optional.of(escrowAccount);
            }
            if (ownerId == 500L && type == AccountType.USER) {
                return Optional.of(publisherAccount);
            }
            return Optional.empty();
        }

        @Override public Optional<WalletAccount> findById(long accountId) { return Optional.empty(); }
        @Override public int casDebit(long accountId, Money amount) { return 1; }
        @Override public int casCredit(long accountId, Money amount) { return 1; }
        @Override public void insertLedger(LedgerEntry entry) { ledger.add(entry); }
        @Override public void insertEscrow(EscrowOrder order) {}
        @Override public boolean escrowExists(long campusId, long errandId) { return true; }
        @Override
        public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
            return Optional.of(EscrowOrder.held(
                    201L,
                    campusId,
                    errandId,
                    500L,
                    Money.fromCents(1_000)));
        }
        @Override
        public int casEscrowStatus(long campusId, long errandId,
                                   EscrowOrder.EscrowStatus from,
                                   EscrowOrder.EscrowStatus to) {
            casEscrowCalls++;
            return casEscrowResult;
        }
        @Override public boolean ledgerExists(String bizNo) { return false; }
    }

    private static final class RecordingFundEvent implements FundEventPort {
        private int publishCalls;
        @Override
        public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
            publishCalls++;
            return localWork.execute();
        }
    }

    private static final class RecordingAudit implements FundAuditPort {
        private boolean success;
        @Override
        public void record(String bizNo, String action, long errandId, long operatorId,
                           String detailJson, boolean success, String message) {
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
