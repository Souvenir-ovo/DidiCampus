package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetErrandDetailServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void cache_miss_loads_database_once_and_writes_detail() {
        Errand source = errand(77L, "送到宿舍", ErrandStatus.PUBLISHED);
        RecordingRepository repository = new RecordingRepository(source);
        RecordingCache cache = new RecordingCache();
        GetErrandDetailService service = service(repository, cache);

        Optional<ErrandDetail> result = service.find(77L);

        assertTrue(result.isPresent());
        assertEquals("送到宿舍", result.get().title());
        assertEquals(1, repository.findCalls);
        assertNotNull(cache.payload);
        assertEquals(1, cache.putCalls);
    }

    @Test
    void fresh_cache_hit_does_not_query_database_again() throws Exception {
        Errand source = errand(78L, "代取快递", ErrandStatus.ACCEPTED);
        RecordingRepository repository = new RecordingRepository(source);
        RecordingCache cache = new RecordingCache();
        cache.entry = fresh(objectMapper.writeValueAsString(ErrandDetail.from(source)));
        GetErrandDetailService service = service(repository, cache);

        Optional<ErrandDetail> result = service.find(78L);

        assertTrue(result.isPresent());
        assertEquals("代取快递", result.get().title());
        assertEquals(0, repository.findCalls);
        assertEquals(0, cache.putCalls);
    }

    @Test
    void missing_database_row_is_cached_as_empty_value() {
        RecordingRepository repository = new RecordingRepository(null);
        RecordingCache cache = new RecordingCache();
        GetErrandDetailService service = service(repository, cache);

        assertTrue(service.find(404L).isEmpty());
        assertTrue(service.find(404L).isEmpty());

        assertEquals(1, repository.findCalls);
        assertEquals(1, cache.emptyWrites);
    }

    @Test
    void bloom_filter_can_stop_a_definitely_missing_id_before_database() {
        RecordingRepository repository = new RecordingRepository(null);
        RecordingCache cache = new RecordingCache();
        cache.mightExist = false;
        GetErrandDetailService service = service(repository, cache);

        assertTrue(service.find(405L).isEmpty());

        assertEquals(0, repository.findCalls);
        assertEquals(0, cache.emptyWrites);
    }

    @Test
    void expired_entry_is_rebuilt_by_the_request_that_gets_the_lock() throws Exception {
        Errand oldValue = errand(79L, "旧标题", ErrandStatus.LOCKED);
        Errand currentValue = errand(79L, "新标题", ErrandStatus.ACCEPTED);
        RecordingRepository repository = new RecordingRepository(currentValue);
        RecordingCache cache = new RecordingCache();
        cache.entry = expired(objectMapper.writeValueAsString(ErrandDetail.from(oldValue)));
        cache.rebuildLockAvailable = true;
        GetErrandDetailService service = service(repository, cache);

        Optional<ErrandDetail> result = service.find(79L);

        assertTrue(result.isPresent());
        assertEquals("新标题", result.get().title());
        assertEquals(1, repository.findCalls);
        assertEquals(1, cache.releaseCalls);
    }

    @Test
    void expired_entry_returns_stale_value_when_another_request_rebuilds() throws Exception {
        Errand oldValue = errand(80L, "可读旧值", ErrandStatus.LOCKED);
        Errand currentValue = errand(80L, "数据库新值", ErrandStatus.ACCEPTED);
        RecordingRepository repository = new RecordingRepository(currentValue);
        RecordingCache cache = new RecordingCache();
        cache.entry = expired(objectMapper.writeValueAsString(ErrandDetail.from(oldValue)));
        cache.rebuildLockAvailable = false;
        GetErrandDetailService service = service(repository, cache);

        Optional<ErrandDetail> result = service.find(80L);

        assertTrue(result.isPresent());
        assertEquals("可读旧值", result.get().title());
        assertEquals(0, repository.findCalls);
        assertEquals(1, cache.lockAttempts);
    }

    private GetErrandDetailService service(RecordingRepository repository,
                                           RecordingCache cache) {
        return new GetErrandDetailService(repository, cache, objectMapper);
    }

    private static Errand errand(long id, String title, ErrandStatus status) {
        return Errand.rehydrate(
                id,
                12L,
                500L,
                ErrandType.DELIVERY,
                title,
                Money.fromCents(800),
                2,
                900L,
                status,
                1,
                1,
                4,
                Instant.now().minusSeconds(10),
                status == ErrandStatus.DELIVERED ? Instant.now() : null);
    }

    private static ErrandCachePort.CachedErrand fresh(String payload) {
        return new ErrandCachePort.CachedErrand(
                payload,
                System.currentTimeMillis() + 60_000,
                false);
    }

    private static ErrandCachePort.CachedErrand expired(String payload) {
        return new ErrandCachePort.CachedErrand(
                payload,
                System.currentTimeMillis() - 1,
                false);
    }

    private static final class RecordingRepository implements ErrandRepository {
        private final Errand row;
        private int findCalls;

        private RecordingRepository(Errand row) {
            this.row = row;
        }

        @Override
        public Optional<Errand> findById(long errandId) {
            findCalls++;
            return Optional.ofNullable(row);
        }

        @Override public void insert(Errand errand) {}
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

    private static final class RecordingCache implements ErrandCachePort {
        private ErrandCachePort.CachedErrand entry;
        private String payload;
        private boolean mightExist = true;
        private boolean rebuildLockAvailable;
        private int putCalls;
        private int emptyWrites;
        private int lockAttempts;
        private int releaseCalls;

        @Override
        public Optional<CachedErrand> get(long errandId) {
            return Optional.ofNullable(entry);
        }

        @Override
        public void put(long errandId, String payloadJson) {
            putCalls++;
            payload = payloadJson;
            entry = fresh(payloadJson);
        }

        @Override
        public void putEmpty(long errandId) {
            emptyWrites++;
            entry = new CachedErrand(null, System.currentTimeMillis() + 60_000, true);
        }

        @Override public void evict(long errandId) { entry = null; }

        @Override
        public boolean tryAcquireRebuild(long errandId) {
            lockAttempts++;
            return rebuildLockAvailable;
        }

        @Override public void releaseRebuild(long errandId) { releaseCalls++; }
        @Override public boolean mightExist(long errandId) { return mightExist; }
        @Override public void registerExisting(long errandId) {}
    }
}
