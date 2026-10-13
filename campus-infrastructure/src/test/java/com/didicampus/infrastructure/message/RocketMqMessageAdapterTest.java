package com.didicampus.infrastructure.message;

import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketMqMessageAdapterTest {

    @Test
    void fund_event_json_keeps_business_fields() {
        FundEventPort.FundEvent event = new FundEventPort.FundEvent(
                "settle:10", "SETTLED", 10L, 1001L, 2001L, 950L, 50L);

        String json = RocketMqFundEventAdapter.toJson(event);

        assertEquals("""
                {"bizNo":"settle:10","type":"SETTLED","errandId":10,"publisherId":1001,"runnerId":2001,"amountCents":950,"commissionCents":50}\
                """, json);
    }

    @Test
    void transaction_check_uses_wallet_ledger_existence() {
        FakeWalletRepository wallet = new FakeWalletRepository();
        RocketMqFundEventAdapter adapter = new RocketMqFundEventAdapter(null, wallet);

        assertEquals(false, adapter.checkLocalTransaction("settle:10"));

        wallet.existingBizNo = "settle:10";

        assertTrue(adapter.checkLocalTransaction("settle:10"));
    }

    @Test
    void cache_evict_delay_delegates_to_delay_message_port() {
        RecordingDelayMessagePort delay = new RecordingDelayMessagePort();
        RocketMqCacheEvictDelayAdapter adapter = new RocketMqCacheEvictDelayAdapter(delay);
        Instant deliverAt = Instant.parse("2026-10-13T12:00:00Z");

        adapter.scheduleEvict(99L, deliverAt);

        assertEquals(List.of("errand-cache-evict|cache-evict:99:1791892800000|{\"errandId\":99}"),
                delay.sent);
        assertEquals(deliverAt, delay.deliverAt);
    }

    private static final class RecordingDelayMessagePort implements DelayMessagePort {
        private final List<String> sent = new ArrayList<>();
        private Instant deliverAt;

        @Override
        public void send(String topic, String messageKey, String payload, Instant deliverAt) {
            sent.add(topic + "|" + messageKey + "|" + payload);
            this.deliverAt = deliverAt;
        }
    }

    private static final class FakeWalletRepository implements WalletRepository {
        private String existingBizNo;

        @Override public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) { return Optional.empty(); }
        @Override public Optional<WalletAccount> findById(long accountId) { return Optional.empty(); }
        @Override public int casDebit(long accountId, Money amount) { return 0; }
        @Override public int casCredit(long accountId, Money amount) { return 0; }
        @Override public void insertLedger(LedgerEntry entry) {}
        @Override public void insertEscrow(EscrowOrder order) {}
        @Override public boolean escrowExists(long campusId, long errandId) { return false; }
        @Override public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) { return Optional.empty(); }
        @Override public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) { return 0; }

        @Override
        public boolean ledgerExists(String bizNo) {
            return bizNo.equals(existingBizNo);
        }
    }
}
