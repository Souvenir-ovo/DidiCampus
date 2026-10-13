package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryErrandListServiceTest {

    @Test
    void list_defaults_to_published_and_caps_page_size() {
        RecordingQueryPort query = new RecordingQueryPort();
        QueryErrandListService service =
                new QueryErrandListService(query, new EmptyErrandRepository());

        service.list(12L, null, -3, 500);

        assertEquals("12:PUBLISHED:0:50", query.lastCall);
    }

    @Test
    void cursor_page_returns_next_cursor_only_when_extra_row_exists() {
        RecordingQueryPort query = new RecordingQueryPort();
        Instant first = Instant.parse("2026-10-13T12:00:00Z");
        Instant second = Instant.parse("2026-10-13T11:59:00Z");
        query.cursorRows = List.of(
                new ErrandQueryPort.CursorItem(errand(1L, first), first),
                new ErrandQueryPort.CursorItem(errand(2L, second), second));
        QueryErrandListService service =
                new QueryErrandListService(query, new EmptyErrandRepository());

        QueryErrandListService.CursorPage<Errand> result =
                service.listByCursor(12L, "published", null, 1);

        assertEquals(List.of(1L), result.items().stream().map(Errand::id).toList());
        assertEquals(2, query.requestedSize);
        QueryErrandListService.CursorPage<Errand> lastPage =
                service.listByCursor(12L, "PUBLISHED", result.nextCursor(), 1);
        org.junit.jupiter.api.Assertions.assertTrue(query.lastCursor.startsWith("1:"));
        assertEquals(List.of(2L), lastPage.items().stream().map(Errand::id).toList());
    }

    @Test
    void invalid_cursor_is_rejected_as_bad_request() {
        QueryErrandListService service =
                new QueryErrandListService(new RecordingQueryPort(), new EmptyErrandRepository());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.listByCursor(1L, "PUBLISHED", "broken", 20));

        assertEquals("BAD_REQUEST", exception.code().name());
    }

    @Test
    void missing_errand_prevents_status_log_query() {
        QueryErrandListService service =
                new QueryErrandListService(new RecordingQueryPort(), new EmptyErrandRepository());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.statusLog(404L));

        assertEquals("ERRAND_NOT_FOUND", exception.code().name());
    }

    private static Errand errand(long id, Instant ignoredCreatedAt) {
        return Errand.rehydrate(
                id, 12L, 1001L, ErrandType.DELIVERY, "task",
                Money.fromCents(800), 1, null, ErrandStatus.PUBLISHED,
                0, 0, 1L, null, null);
    }

    private static final class RecordingQueryPort implements ErrandQueryPort {
        private String lastCall;
        private String lastCursor;
        private int requestedSize;
        private List<CursorItem> cursorRows = List.of();

        @Override
        public List<Errand> list(long campusId, String status, int page, int size) {
            lastCall = campusId + ":" + status + ":" + page + ":" + size;
            return List.of();
        }

        @Override
        public List<CursorItem> listByCursor(long campusId, String status,
                                             Instant beforeCreatedAt, Long beforeId, int size) {
            requestedSize = size;
            if (beforeId != null) {
                lastCursor = beforeId + ":" + beforeCreatedAt.toEpochMilli();
                return cursorRows.subList(1, cursorRows.size());
            }
            return cursorRows;
        }

        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static final class EmptyErrandRepository implements ErrandRepository {
        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.empty(); }
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
}
