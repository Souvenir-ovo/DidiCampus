package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.grab.model.SlotOutcome;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishErrandServiceTest {

    @Test
    void publish_escrows_money_then_opens_grab_slots() {
        MemoryWallet wallet = new MemoryWallet();
        MemoryErrandRepository errands = new MemoryErrandRepository();
        MemorySlot slots = new MemorySlot();
        MemoryCache cache = new MemoryCache();
        PublishErrandService service = new PublishErrandService(
                errands,
                wallet,
                slots,
                cache,
                new SnowflakeIdGenerator(1));

        PublishErrandService.Result result = service.publish(
                new PublishErrandService.Command(
                        10L, 20L, ErrandType.DELIVERY, "送到宿舍楼下", 880, 2));

        assertEquals(ErrandStatus.PUBLISHED, result.status());
        assertEquals(880, result.frozenCents());
        assertEquals(2, wallet.ledger.size());
        assertEquals(1, wallet.escrows.size());
        assertEquals(2, slots.initializedSlots);
        assertEquals(result.errandId(), cache.registeredId);
        assertEquals(ErrandStatus.PUBLISHED, errands.saved.status());
    }

    @Test
    void publish_rejects_when_debit_cas_does_not_succeed() {
        MemoryWallet wallet = new MemoryWallet();
        wallet.debitResult = 0;
        PublishErrandService service = new PublishErrandService(
                new MemoryErrandRepository(),
                wallet,
                new MemorySlot(),
                new MemoryCache(),
                new SnowflakeIdGenerator(2));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.publish(new PublishErrandService.Command(
                        10L, 20L, ErrandType.BUY, "代取快递", 100, 1)));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, error.code());
    }

    private static final class MemoryWallet implements WalletRepository {
        private final WalletAccount publisher = new WalletAccount(
                101, 20, AccountType.USER, Money.fromCents(5000), Money.ZERO, 0);
        private final WalletAccount escrow = new WalletAccount(
                102, -1, AccountType.ESCROW, Money.ZERO, Money.ZERO, 0);
        private final List<LedgerEntry> ledger = new ArrayList<>();
        private final List<EscrowOrder> escrows = new ArrayList<>();
        private int debitResult = 1;

        @Override
        public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
            if (ownerId == publisher.ownerId() && type == publisher.type()) {
                return Optional.of(publisher);
            }
            if (ownerId == escrow.ownerId() && type == escrow.type()) {
                return Optional.of(escrow);
            }
            return Optional.empty();
        }

        @Override public Optional<WalletAccount> findById(long accountId) { return Optional.empty(); }
        @Override public int casDebit(long accountId, Money amount) { return debitResult; }
        @Override public int casCredit(long accountId, Money amount) { return 1; }
        @Override public void insertLedger(LedgerEntry entry) { ledger.add(entry); }
        @Override public void insertEscrow(EscrowOrder order) { escrows.add(order); }
        @Override public boolean escrowExists(long campusId, long errandId) { return false; }
        @Override public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) { return Optional.empty(); }
        @Override public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) { return 0; }
        @Override public boolean ledgerExists(String bizNo) { return false; }
    }

    private static final class MemoryErrandRepository implements ErrandRepository {
        private Errand saved;

        @Override public void insert(Errand errand) { saved = errand; }
        @Override public Optional<Errand> findById(long errandId) { return Optional.ofNullable(saved); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 1; }
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

    private static final class MemorySlot implements GrabSlotPort {
        private int initializedSlots;
        @Override public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) { return SlotOutcome.NOT_GRABBABLE; }
        @Override public void rollback(long errandId, long runnerId, String requestId) {}
        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) { initializedSlots = slotTotal; }
        @Override public long remainingSlot(long errandId) { return initializedSlots; }
    }

    private static final class MemoryCache implements ErrandCachePort {
        private long registeredId;
        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) {}
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return false; }
        @Override public void registerExisting(long errandId) { registeredId = errandId; }
    }
}
