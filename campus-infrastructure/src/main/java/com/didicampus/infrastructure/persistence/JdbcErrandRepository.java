package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcErrandRepository implements ErrandRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcErrandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Errand errand) {
        jdbcTemplate.update("""
                INSERT INTO errand
                (id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                 slot_total, slot_taken, status, round, version, locked_at, delivered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                errand.id(),
                errand.campusId(),
                errand.publisherId(),
                errand.grabberId(),
                errand.type().name(),
                errand.title(),
                errand.reward().cents(),
                errand.slotTotal(),
                errand.slotTaken(),
                errand.status().name(),
                errand.round(),
                errand.version(),
                JdbcTime.timestamp(errand.lockedAt()),
                JdbcTime.timestamp(errand.deliveredAt()));
    }

    @Override
    public Optional<Errand> findById(long errandId) {
        List<Errand> rows = jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at
                FROM errand
                WHERE id = ?
                """, this::mapErrand, errandId);
        return rows.stream().findFirst();
    }

    @Override
    public int casLockForRunner(long errandId, long runnerId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET grabber_id = ?, status = ?, slot_taken = slot_taken + 1,
                    version = version + 1, locked_at = ?
                WHERE id = ? AND status = ? AND version = ? AND slot_taken < slot_total
                """,
                runnerId,
                ErrandStatus.LOCKED.name(),
                JdbcTime.timestamp(Instant.now()),
                errandId,
                ErrandStatus.PUBLISHED.name(),
                expectedVersion);
    }

    @Override
    public int casPublish(long errandId, long expectedVersion) {
        return changeStatus(errandId, ErrandStatus.DRAFT, ErrandStatus.PUBLISHED, expectedVersion);
    }

    @Override
    public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to,
                                int round, long operatorId) {
        jdbcTemplate.update("""
                INSERT INTO errand_status_log
                (campus_id, errand_id, from_status, to_status, round, operator_id)
                SELECT campus_id, id, ?, ?, ?, ?
                FROM errand
                WHERE id = ?
                """,
                from.name(),
                to.name(),
                round,
                operatorId,
                errandId);
    }

    @Override
    public int casAccept(long errandId, long runnerId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, version = version + 1
                WHERE id = ? AND grabber_id = ? AND status = ? AND version = ?
                """,
                ErrandStatus.ACCEPTED.name(),
                errandId,
                runnerId,
                ErrandStatus.LOCKED.name(),
                expectedVersion);
    }

    @Override
    public int casTransferToNext(long errandId, long nextRunnerId,
                                 long expectedVersion, int expectedRound) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET grabber_id = ?, round = round + 1, version = version + 1, locked_at = ?
                WHERE id = ? AND status = ? AND version = ? AND round = ?
                """,
                nextRunnerId,
                JdbcTime.timestamp(Instant.now()),
                errandId,
                ErrandStatus.LOCKED.name(),
                expectedVersion,
                expectedRound);
    }

    @Override
    public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET grabber_id = NULL, status = ?, slot_taken = CASE WHEN slot_taken > 0 THEN slot_taken - 1 ELSE 0 END,
                    round = round + 1, version = version + 1, locked_at = NULL
                WHERE id = ? AND status = ? AND version = ? AND round = ?
                """,
                ErrandStatus.PUBLISHED.name(),
                errandId,
                ErrandStatus.LOCKED.name(),
                expectedVersion,
                expectedRound);
    }

    @Override
    public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        return jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at
                FROM errand
                WHERE status = ? AND locked_at <= ?
                ORDER BY locked_at ASC
                LIMIT ?
                """, this::mapErrand, ErrandStatus.LOCKED.name(), JdbcTime.timestamp(cutoff), limit);
    }

    @Override
    public int casPickUp(long errandId, long runnerId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, version = version + 1
                WHERE id = ? AND grabber_id = ? AND status = ? AND version = ?
                """,
                ErrandStatus.PICKED_UP.name(),
                errandId,
                runnerId,
                ErrandStatus.ACCEPTED.name(),
                expectedVersion);
    }

    @Override
    public int casDeliver(long errandId, long runnerId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, version = version + 1, delivered_at = ?
                WHERE id = ? AND grabber_id = ? AND status = ? AND version = ?
                """,
                ErrandStatus.DELIVERED.name(),
                JdbcTime.timestamp(Instant.now()),
                errandId,
                runnerId,
                ErrandStatus.PICKED_UP.name(),
                expectedVersion);
    }

    @Override
    public int casSettle(long errandId, long expectedVersion) {
        return changeStatus(errandId, ErrandStatus.DELIVERED, ErrandStatus.SETTLED, expectedVersion);
    }

    @Override
    public int casRefundFromDispute(long errandId, long expectedVersion) {
        return changeStatus(errandId, ErrandStatus.DISPUTED, ErrandStatus.REFUNDED, expectedVersion);
    }

    @Override
    public int casCancel(long errandId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, grabber_id = NULL, slot_taken = 0, version = version + 1
                WHERE id = ? AND status IN (?, ?) AND version = ?
                """,
                ErrandStatus.CANCELLED.name(),
                errandId,
                ErrandStatus.DRAFT.name(),
                ErrandStatus.PUBLISHED.name(),
                expectedVersion);
    }

    @Override
    public int casDispute(long errandId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, version = version + 1
                WHERE id = ? AND status IN (?, ?, ?) AND version = ?
                """,
                ErrandStatus.DISPUTED.name(),
                errandId,
                ErrandStatus.ACCEPTED.name(),
                ErrandStatus.PICKED_UP.name(),
                ErrandStatus.DELIVERED.name(),
                expectedVersion);
    }

    @Override
    public int casSettleFromDispute(long errandId, long expectedVersion) {
        return changeStatus(errandId, ErrandStatus.DISPUTED, ErrandStatus.SETTLED, expectedVersion);
    }

    @Override
    public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) {
        Instant cutoff = Instant.now().minusSeconds(autoSettleSeconds);
        return jdbcTemplate.query("""
                SELECT id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                       slot_total, slot_taken, status, round, version, locked_at, delivered_at
                FROM errand
                WHERE status = ? AND delivered_at <= ?
                ORDER BY delivered_at ASC
                LIMIT ?
                """, this::mapErrand, ErrandStatus.DELIVERED.name(), JdbcTime.timestamp(cutoff), limit);
    }

    private int changeStatus(long errandId, ErrandStatus from, ErrandStatus to, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE errand
                SET status = ?, version = version + 1
                WHERE id = ? AND status = ? AND version = ?
                """,
                to.name(),
                errandId,
                from.name(),
                expectedVersion);
    }

    private Errand mapErrand(ResultSet rs, int rowNum) throws SQLException {
        Long runnerId = rs.getObject("grabber_id", Long.class);
        Timestamp lockedAt = rs.getTimestamp("locked_at");
        Timestamp deliveredAt = rs.getTimestamp("delivered_at");
        return Errand.rehydrate(
                rs.getLong("id"),
                rs.getLong("campus_id"),
                rs.getLong("publisher_id"),
                ErrandType.valueOf(rs.getString("type")),
                rs.getString("title"),
                Money.fromCents(rs.getLong("reward_amount")),
                rs.getInt("slot_total"),
                runnerId,
                ErrandStatus.valueOf(rs.getString("status")),
                rs.getInt("slot_taken"),
                rs.getInt("round"),
                rs.getLong("version"),
                JdbcTime.instant(lockedAt),
                JdbcTime.instant(deliveredAt));
    }
}
