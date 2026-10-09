package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcErrandQueryAdapter implements ErrandQueryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcErrandQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Errand> list(long campusId, String status, int page, int size) {
        return jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at
                FROM errand
                WHERE campus_id = ? AND status = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, this::mapErrand, campusId, status, size, offset(page, size));
    }

    @Override
    public List<CursorItem> listByCursor(long campusId, String status,
                                         Instant beforeCreatedAt, Long beforeId, int size) {
        if (beforeCreatedAt == null || beforeId == null) {
            return jdbcTemplate.query("""
                    SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                           slot_total, slot_taken, status, round, version, locked_at, delivered_at, created_at
                    FROM errand
                    WHERE campus_id = ? AND status = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """, this::mapCursorItem, campusId, status, size);
        }
        return jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at, created_at
                FROM errand
                WHERE campus_id = ? AND status = ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, this::mapCursorItem, campusId, status,
                JdbcTime.timestamp(beforeCreatedAt), JdbcTime.timestamp(beforeCreatedAt), beforeId, size);
    }

    @Override
    public List<Errand> listByPublisher(long publisherId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at
                FROM errand
                WHERE publisher_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, this::mapErrand, publisherId, size, offset(page, size));
    }

    @Override
    public List<Errand> listByRunner(long runnerId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT e.id, e.campus_id, e.publisher_id, e.grabber_id, e.type, e.title,
                       e.reward_amount, e.slot_total, e.slot_taken, e.status, e.round,
                       e.version, e.locked_at, e.delivered_at
                FROM errand e
                JOIN (SELECT DISTINCT errand_id FROM grab_record WHERE runner_id = ?) g
                  ON g.errand_id = e.id
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT ? OFFSET ?
                """, this::mapErrand, runnerId, size, offset(page, size));
    }

    @Override
    public List<StatusChange> statusLog(long campusId, long errandId) {
        return jdbcTemplate.query("""
                SELECT created_at, from_status, to_status, round, operator_id
                FROM errand_status_log
                WHERE campus_id = ? AND errand_id = ?
                ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> new StatusChange(
                JdbcTime.instant(rs.getTimestamp("created_at")),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getInt("round"),
                rs.getLong("operator_id")), campusId, errandId);
    }

    @Override
    public List<Long> sampleIds(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM errand
                ORDER BY RAND()
                LIMIT ?
                """, Long.class, limit);
    }

    @Override
    public int countOngoingByRunner(long runnerId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM errand
                WHERE grabber_id = ? AND status IN (?, ?)
                """, Integer.class, runnerId,
                ErrandStatus.ACCEPTED.name(), ErrandStatus.PICKED_UP.name());
        return count == null ? 0 : count;
    }

    private CursorItem mapCursorItem(ResultSet rs, int rowNum) throws SQLException {
        return new CursorItem(mapErrand(rs, rowNum), JdbcTime.instant(rs.getTimestamp("created_at")));
    }

    private Errand mapErrand(ResultSet rs, int rowNum) throws SQLException {
        return Errand.rehydrate(
                rs.getLong("id"),
                rs.getLong("campus_id"),
                rs.getLong("publisher_id"),
                ErrandType.valueOf(rs.getString("type")),
                rs.getString("title"),
                Money.fromCents(rs.getLong("reward_amount")),
                rs.getInt("slot_total"),
                rs.getObject("grabber_id", Long.class),
                ErrandStatus.valueOf(rs.getString("status")),
                rs.getInt("slot_taken"),
                rs.getInt("round"),
                rs.getLong("version"),
                JdbcTime.instant(rs.getTimestamp("locked_at")),
                JdbcTime.instant(rs.getTimestamp("delivered_at")));
    }

    private int offset(int page, int size) {
        return Math.max(page, 0) * size;
    }
}
