package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.credit.ports.CreditRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCreditRepository implements CreditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCreditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int scoreOf(long userId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT score
                FROM credit_score
                WHERE user_id = ?
                """, Integer.class, userId);
        return rows.isEmpty() ? CreditEventType.BASE_SCORE : rows.getFirst();
    }

    @Override
    public Optional<CreditScore> find(long userId) {
        List<CreditScore> rows = jdbcTemplate.query("""
                SELECT user_id, score, version
                FROM credit_score
                WHERE user_id = ?
                """, (rs, rowNum) -> new CreditScore(
                rs.getLong("user_id"),
                rs.getInt("score"),
                rs.getLong("version")), userId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean applyEvent(CreditEvent event) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO credit_event
                    (id, biz_no, user_id, type, delta, ref_type, ref_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.id(),
                    event.bizNo(),
                    event.userId(),
                    event.type().name(),
                    event.delta(),
                    event.refType(),
                    event.refId(),
                    JdbcTime.timestamp(event.createdAt()));
        } catch (DuplicateKeyException ignored) {
            return false;
        }

        int changed = updateScore(event.userId(), event.delta());
        if (changed == 0) {
            insertInitialScore(event.userId(), event.delta());
        }
        return true;
    }

    @Override
    public List<CreditEvent> recentEvents(long userId, int days, int limit) {
        Instant since = Instant.now().minusSeconds(days * 24L * 60L * 60L);
        return jdbcTemplate.query("""
                SELECT id, biz_no, user_id, type, delta, ref_type, ref_id, created_at
                FROM credit_event
                WHERE user_id = ? AND created_at >= ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new CreditEvent(
                rs.getLong("id"),
                rs.getString("biz_no"),
                rs.getLong("user_id"),
                CreditEventType.valueOf(rs.getString("type")),
                rs.getInt("delta"),
                rs.getString("ref_type"),
                rs.getLong("ref_id"),
                JdbcTime.instant(rs.getTimestamp("created_at"))), userId, JdbcTime.timestamp(since), limit);
    }

    @Override
    public int windowDelta(long userId, int windowDays) {
        Instant since = Instant.now().minusSeconds(windowDays * 24L * 60L * 60L);
        Integer sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(delta), 0)
                FROM credit_event
                WHERE user_id = ? AND created_at >= ?
                """, Integer.class, userId, JdbcTime.timestamp(since));
        return sum == null ? 0 : sum;
    }

    @Override
    public int calibrateScores(int windowDays, int limit) {
        Instant since = Instant.now().minusSeconds(windowDays * 24L * 60L * 60L);
        List<Calibration> rows = jdbcTemplate.query("""
                SELECT base.user_id,
                       COALESCE(score.score, ?) AS old_score,
                       GREATEST(?, LEAST(?, ? + COALESCE(SUM(event.delta), 0))) AS new_score
                FROM (
                    SELECT user_id FROM credit_score
                    UNION
                    SELECT DISTINCT user_id FROM credit_event WHERE created_at >= ?
                ) base
                LEFT JOIN credit_score score ON score.user_id = base.user_id
                LEFT JOIN credit_event event ON event.user_id = base.user_id AND event.created_at >= ?
                GROUP BY base.user_id, score.score
                HAVING old_score <> new_score
                ORDER BY base.user_id ASC
                LIMIT ?
                """, (rs, rowNum) -> new Calibration(
                rs.getLong("user_id"),
                rs.getInt("new_score")),
                CreditEventType.BASE_SCORE,
                CreditEventType.MIN_SCORE,
                CreditEventType.MAX_SCORE,
                CreditEventType.BASE_SCORE,
                JdbcTime.timestamp(since),
                JdbcTime.timestamp(since),
                limit);

        for (Calibration row : rows) {
            upsertScore(row.userId(), row.score());
        }
        return rows.size();
    }

    private int updateScore(long userId, int delta) {
        return jdbcTemplate.update("""
                UPDATE credit_score
                SET score = GREATEST(?, LEAST(?, score + ?)),
                    version = version + 1
                WHERE user_id = ?
                """, CreditEventType.MIN_SCORE, CreditEventType.MAX_SCORE, delta, userId);
    }

    private void insertInitialScore(long userId, int delta) {
        int score = clamp(CreditEventType.BASE_SCORE + delta);
        try {
            jdbcTemplate.update("""
                    INSERT INTO credit_score (user_id, score, version)
                    VALUES (?, ?, 1)
                    """, userId, score);
        } catch (DuplicateKeyException ignored) {
            updateScore(userId, delta);
        }
    }

    private void upsertScore(long userId, int score) {
        int changed = jdbcTemplate.update("""
                UPDATE credit_score
                SET score = ?, version = version + 1
                WHERE user_id = ?
                """, score, userId);
        if (changed == 0) {
            jdbcTemplate.update("""
                    INSERT INTO credit_score (user_id, score, version)
                    VALUES (?, ?, 1)
                    """, userId, score);
        }
    }

    private int clamp(int value) {
        return Math.max(CreditEventType.MIN_SCORE, Math.min(CreditEventType.MAX_SCORE, value));
    }

    private record Calibration(long userId, int score) {
    }
}
