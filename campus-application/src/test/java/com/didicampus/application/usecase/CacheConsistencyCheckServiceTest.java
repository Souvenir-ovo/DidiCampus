package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.errand.ports.SyncDiffRepository;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheConsistencyCheckServiceTest {

    @Test
    void recordsDiffsAndEvictsStaleCache() {
        FakeQuery query = new FakeQuery(List.of(100L, 101L, 102L));
        FakeErrands errands = new FakeErrands();
        errands.rows.put(100L, errand(100L, ErrandStatus.PUBLISHED, 3L, 500L));
        errands.rows.put(101L, errand(101L, ErrandStatus.ACCEPTED, 4L, 800L));
        FakeCache cache = new FakeCache();
        cache.rows.put(100L, new ErrandCachePort.CachedErrand(
                "{\"status\":\"PUBLISHED\",\"version\":3,\"rewardCents\":500}",
                System.currentTimeMillis() + 10000,
                false));
        cache.rows.put(101L, new ErrandCachePort.CachedErrand(
                "{\"status\":\"LOCKED\",\"version\":3,\"rewardCents\":700}",
                System.currentTimeMillis() + 10000,
                false));
        cache.rows.put(102L, new ErrandCachePort.CachedErrand(
                "{\"status\":\"PUBLISHED\",\"version\":1,\"rewardCents\":100}",
                System.currentTimeMillis() + 10000,
                false));
        FakeSyncDiffs syncDiffs = new FakeSyncDiffs();
        CacheConsistencyCheckService service =
                new CacheConsistencyCheckService(query, errands, cache, syncDiffs, 10);

        int diffs = service.checkOnce();

        assertEquals(4, diffs);
        assertEquals(List.of(101L), cache.evicted);
        assertTrue(syncDiffs.fields.contains("101:status:ACCEPTED:LOCKED:true"));
        assertTrue(syncDiffs.fields.contains("101:version:4:3:true"));
        assertTrue(syncDiffs.fields.contains("101:reward_amount:800:700:true"));
        assertTrue(syncDiffs.fields.contains("102:existence:MISSING:PRESENT:false"));
    }

    private static Errand errand(long id, ErrandStatus status, long version, long rewardCents) {
        return Errand.rehydrate(
                id,
                1L,
                1001L,
                ErrandType.DELIVERY,
                "sample",
                Money.fromCents(rewardCents),
                1,
                2001L,
                status,
                1,
                0,
                version,
                Instant.now());
    }

    private static final class FakeQuery implements ErrandQueryPort {
        private final List<Long> ids;

        private FakeQuery(List<Long> ids) {
            this.ids = ids;
        }

        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return ids; }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static final class FakeErrands implements ErrandRepository {
        private final Map<Long, Errand> rows = new LinkedHashMap<>();

        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.ofNullable(rows.get(errandId)); }
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

    private static final class FakeCache implements ErrandCachePort {
        private final Map<Long, CachedErrand> rows = new LinkedHashMap<>();
        private final List<Long> evicted = new ArrayList<>();

        @Override public Optional<CachedErrand> get(long errandId) { return Optional.ofNullable(rows.get(errandId)); }
        @Override public void put(long errandId, String payloadJson) {}
        @Override public void putEmpty(long errandId) {}
        @Override public void evict(long errandId) { evicted.add(errandId); }
        @Override public boolean tryAcquireRebuild(long errandId) { return false; }
        @Override public void releaseRebuild(long errandId) {}
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) {}
    }

    private static final class FakeSyncDiffs implements SyncDiffRepository {
        private final List<String> fields = new ArrayList<>();

        @Override
        public void record(Instant checkTime, long errandId, String field,
                           String dbValue, String cacheValue, boolean fixed) {
            fields.add(errandId + ":" + field + ":" + dbValue + ":" + cacheValue + ":" + fixed);
        }

        @Override public long countSince(Instant since) { return 0L; }
    }
}
