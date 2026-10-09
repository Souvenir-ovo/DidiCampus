package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.ports.LocalMessageRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcLocalMessageRepository implements LocalMessageRepository {

    private static final String PENDING = "PENDING";
    private static final String SENT = "SENT";
    private static final String DEAD = "DEAD";

    private final JdbcTemplate jdbcTemplate;

    public JdbcLocalMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean enqueue(long id, String messageKey, String topic,
                           String payload, Instant deliverAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO local_message
                    (id, msg_key, topic, payload, deliver_at, status, retry_count, next_retry_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                    """,
                    id,
                    messageKey,
                    topic,
                    payload,
                    JdbcTime.timestamp(deliverAt),
                    PENDING,
                    JdbcTime.timestamp(deliverAt));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public void markSent(String messageKey) {
        jdbcTemplate.update("""
                UPDATE local_message
                SET status = ?
                WHERE msg_key = ?
                """, SENT, messageKey);
    }

    @Override
    public List<PendingMessage> findPending(int limit) {
        Instant now = Instant.now();
        return jdbcTemplate.query("""
                SELECT id, msg_key, topic, payload, deliver_at, retry_count
                FROM local_message
                WHERE status = ? AND deliver_at <= ? AND next_retry_at <= ?
                ORDER BY deliver_at ASC, id ASC
                LIMIT ?
                """, this::mapPending, PENDING, JdbcTime.timestamp(now), JdbcTime.timestamp(now), limit);
    }

    @Override
    public void markRetry(String messageKey, int maxRetry) {
        Instant nextRetryAt = Instant.now().plusSeconds(30);
        jdbcTemplate.update("""
                UPDATE local_message
                SET retry_count = retry_count + 1,
                    status = CASE WHEN retry_count + 1 >= ? THEN ? ELSE ? END,
                    next_retry_at = ?
                WHERE msg_key = ? AND status = ?
                """, maxRetry, DEAD, PENDING, JdbcTime.timestamp(nextRetryAt), messageKey, PENDING);
    }

    private PendingMessage mapPending(ResultSet rs, int rowNum) throws SQLException {
        return new PendingMessage(
                rs.getLong("id"),
                rs.getString("msg_key"),
                rs.getString("topic"),
                rs.getString("payload"),
                JdbcTime.instant(rs.getTimestamp("deliver_at")),
                rs.getInt("retry_count"));
    }
}
