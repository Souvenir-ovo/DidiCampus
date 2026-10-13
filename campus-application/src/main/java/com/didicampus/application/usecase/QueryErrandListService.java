package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 任务读模型编排。
 *
 * <p>列表查询不经过聚合根，也不参与状态流转；它只负责校验查询参数、
 * 限制分页规模，以及把底层查询结果组织成接口需要的分页形态。</p>
 */
@Service
public class QueryErrandListService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ErrandQueryPort queryPort;
    private final ErrandRepository errandRepository;

    public QueryErrandListService(ErrandQueryPort queryPort,
                                  ErrandRepository errandRepository) {
        this.queryPort = queryPort;
        this.errandRepository = errandRepository;
    }

    public List<Errand> list(long campusId, String status, int page, int size) {
        return queryPort.list(
                campusId,
                normalizeStatus(status),
                normalizePage(page),
                normalizeSize(size));
    }

    public CursorPage<Errand> listByCursor(long campusId,
                                           String status,
                                           String cursor,
                                           int size) {
        int pageSize = normalizeSize(size);
        Cursor position = decodeCursor(cursor);
        List<ErrandQueryPort.CursorItem> rows = queryPort.listByCursor(
                campusId,
                normalizeStatus(status),
                position.createdAt(),
                position.errandId(),
                pageSize + 1);

        boolean more = rows.size() > pageSize;
        List<ErrandQueryPort.CursorItem> visible =
                more ? rows.subList(0, pageSize) : rows;
        String next = null;
        if (more && !visible.isEmpty()) {
            ErrandQueryPort.CursorItem tail = visible.getLast();
            next = encodeCursor(tail.createdAt(), tail.errand().id());
        }
        return new CursorPage<>(
                visible.stream().map(ErrandQueryPort.CursorItem::errand).toList(),
                next);
    }

    public List<Errand> publishedBy(long publisherId, int page, int size) {
        return queryPort.listByPublisher(
                publisherId, normalizePage(page), normalizeSize(size));
    }

    public List<Errand> grabbedBy(long runnerId, int page, int size) {
        return queryPort.listByRunner(
                runnerId, normalizePage(page), normalizeSize(size));
    }

    public List<ErrandQueryPort.StatusChange> statusLog(long errandId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ERRAND_NOT_FOUND,
                        "errandId=" + errandId));
        return queryPort.statusLog(errand.campusId(), errandId);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return ErrandStatus.PUBLISHED.name();
        }
        try {
            return ErrandStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "unsupported status=" + status);
        }
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizeSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            String[] pieces = raw.split(":", 2);
            if (pieces.length != 2) {
                throw new IllegalArgumentException("cursor must contain timestamp and id");
            }
            return new Cursor(
                    Instant.ofEpochMilli(Long.parseLong(pieces[0])),
                    Long.parseLong(pieces[1]));
        } catch (RuntimeException ex) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "invalid cursor");
        }
    }

    private String encodeCursor(Instant createdAt, long errandId) {
        String raw = createdAt.toEpochMilli() + ":" + errandId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant createdAt, Long errandId) {
    }

    public record CursorPage<T>(List<T> items, String nextCursor) {
    }
}
