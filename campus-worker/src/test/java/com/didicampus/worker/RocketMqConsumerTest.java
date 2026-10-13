package com.didicampus.worker;

import com.didicampus.application.usecase.CacheEvictSupport;
import com.didicampus.application.usecase.SettleErrandCommitService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.application.usecase.TimeoutTransferCommitService;
import com.didicampus.application.usecase.TimeoutTransferService;
import com.didicampus.domain.credit.ports.CreditRankingPort;
import com.didicampus.domain.credit.ports.CreditRepository;
import com.didicampus.domain.errand.ports.DelayMessagePort;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.ports.CandidateQueuePort;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.notify.ports.NotificationRepository;
import com.didicampus.domain.notify.ports.RealtimeNotifier;
import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocketMqConsumerTest {

    @Test
    void timeout_consumer_acknowledges_business_outcomes() {
        RecordingTimeoutService service = new RecordingTimeoutService();
        TimeoutTransferConsumer consumer = new TimeoutTransferConsumer(
                null, null, service, "errand-confirm-timeout", "group-a");

        ConsumeResult result = consumer.handleBody("{\"errandId\":10,\"round\":2}");

        assertEquals(ConsumeResult.SUCCESS, result);
        assertEquals(List.of("10:2"), service.calls);
    }

    @Test
    void auto_settle_consumer_uses_system_operator() {
        RecordingSettleService service = new RecordingSettleService(false);
        AutoSettleConsumer consumer = new AutoSettleConsumer(
                null, null, service, "errand-auto-settle", "group-b");

        ConsumeResult result = consumer.handleBody("{\"errandId\":20}");

        assertEquals(ConsumeResult.SUCCESS, result);
        assertEquals(List.of("20:-1"), service.calls);
    }

    @Test
    void cache_evict_consumer_acks_bad_messages_and_evicts_good_ones() {
        RecordingCache cache = new RecordingCache();
        CacheEvictConsumer consumer = new CacheEvictConsumer(null, null, cache, "group-c");

        assertEquals(ConsumeResult.SUCCESS, consumer.handleBody("{\"errandId\":30}"));
        assertEquals(ConsumeResult.SUCCESS, consumer.handleBody("{\"broken\":true}"));
        assertEquals(List.of(30L), cache.evicted);
    }

    @Test
    void fund_event_consumer_creates_idempotent_notifications() {
        RecordingNotificationRepository notifications = new RecordingNotificationRepository();
        FundEventConsumer consumer = new FundEventConsumer(
                null, null, notifications, new SnowflakeIdGenerator(3),
                "errand-fund-event", "group-d");

        ConsumeResult result = consumer.handleEvent("""
                {"bizNo":"settle:40","type":"SETTLED","errandId":40,"publisherId":1001,"runnerId":2001,"amountCents":950,"commissionCents":50}\
                """, "settle:40", "SETTLED");

        assertEquals(ConsumeResult.SUCCESS, result);
        assertEquals(List.of("settle:40:publisher:1001:SETTLED", "settle:40:runner:2001:SETTLED"),
                notifications.records);
    }

    @Test
    void consumer_returns_failure_for_system_exceptions() {
        RecordingSettleService service = new RecordingSettleService(true);
        AutoSettleConsumer consumer = new AutoSettleConsumer(
                null, null, service, "errand-auto-settle", "group-b");

        assertEquals(ConsumeResult.FAILURE, consumer.handleBody("{\"errandId\":20}"));
    }

    @Test
    void message_json_accepts_spacing_and_quoted_numbers() {
        assertEquals(42L, MessageJson.longValue("{\"errandId\": \"42\"}", "errandId"));
        assertEquals(-1L, MessageJson.longValue("{\"operator\": -1}", "operator"));
    }

    private static class RecordingTimeoutService extends TimeoutTransferService {
        private final List<String> calls = new ArrayList<>();

        RecordingTimeoutService() {
            super((ErrandRepository) null, (CandidateQueuePort) null, (GrabSlotPort) null,
                    (DelayMessagePort) null, (LocalMessageRepository) null,
                    (TimeoutTransferCommitService) null, (CacheEvictSupport) null,
                    (CreditRepository) null, new SnowflakeIdGenerator(1),
                    (RealtimeNotifier) null, 300L, 5);
        }

        @Override
        public Outcome handleTimeout(long errandId, int expectedRound) {
            calls.add(errandId + ":" + expectedRound);
            return Outcome.TRANSFERRED;
        }
    }

    private static class RecordingSettleService extends SettleErrandService {
        private final boolean fail;
        private final List<String> calls = new ArrayList<>();

        RecordingSettleService(boolean fail) {
            super((ErrandRepository) null, (WalletRepository) null, (FundEventPort) null,
                    (FundAuditPort) null, (SettleErrandCommitService) null,
                    (CacheEvictSupport) null, (CreditRepository) null,
                    (CreditRankingPort) null, (RealtimeNotifier) null, 0.05D);
            this.fail = fail;
        }

        @Override
        public Result settle(long errandId, long operatorId) {
            if (fail) {
                throw new IllegalStateException("database down");
            }
            calls.add(errandId + ":" + operatorId);
            return Result.SETTLED;
        }
    }

    private static final class RecordingCache implements ErrandCachePort {
        private final List<Long> evicted = new ArrayList<>();

        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) { evicted.add(errandId); }
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return false; }
        @Override public void registerExisting(long errandId) {}
    }

    private static final class RecordingNotificationRepository implements NotificationRepository {
        private final List<String> records = new ArrayList<>();

        @Override
        public boolean insertIfAbsent(long id, String messageKey, long userId,
                                      long errandId, String type, String content) {
            records.add(messageKey + ":" + userId + ":" + type);
            return true;
        }

        @Override
        public int markAllRead(long userId) {
            return 0;
        }
    }
}
