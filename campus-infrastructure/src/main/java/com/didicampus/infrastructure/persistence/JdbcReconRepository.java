package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.recon.ports.ReconRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class JdbcReconRepository implements ReconRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long debitMinusCredit() {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0)
                FROM wallet_ledger
                """, Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public List<AccountDiff> findSnapshotDiffs() {
        return jdbcTemplate.query("""
                SELECT account.id,
                       account.owner_id,
                       account.available + account.frozen AS snapshot_total,
                       COALESCE((
                           SELECT SUM(CASE WHEN ledger.direction = 'CREDIT'
                                           THEN ledger.amount ELSE -ledger.amount END)
                           FROM wallet_ledger ledger
                           WHERE ledger.account_id = account.id
                       ), 0) AS ledger_net
                FROM wallet_account account
                WHERE account.owner_type IN ('ESCROW', 'COMMISSION')
                  AND account.available + account.frozen <> COALESCE((
                           SELECT SUM(CASE WHEN ledger.direction = 'CREDIT'
                                           THEN ledger.amount ELSE -ledger.amount END)
                           FROM wallet_ledger ledger
                           WHERE ledger.account_id = account.id
                       ), 0)
                """, (rs, rowNum) -> new AccountDiff(
                rs.getLong("id"),
                rs.getLong("owner_id"),
                rs.getLong("snapshot_total"),
                rs.getLong("ledger_net")));
    }

    @Override
    public List<EscrowDiff> findEscrowClosureDiffs() {
        return jdbcTemplate.query("""
                SELECT escrow.errand_id, escrow.status, 'released_without_settle_ledger' AS reason
                FROM escrow_order escrow
                WHERE escrow.status = 'RELEASED'
                  AND NOT EXISTS (
                      SELECT 1 FROM wallet_ledger ledger
                      WHERE ledger.biz_no = CONCAT('settle:', escrow.errand_id)
                  )
                UNION ALL
                SELECT escrow.errand_id, escrow.status, 'refunded_without_refund_ledger'
                FROM escrow_order escrow
                WHERE escrow.status = 'REFUNDED'
                  AND NOT EXISTS (
                      SELECT 1 FROM wallet_ledger ledger
                      WHERE ledger.biz_no = CONCAT('refund:', escrow.errand_id)
                  )
                UNION ALL
                SELECT escrow.errand_id, escrow.status, CONCAT('errand_', errand.status, '_but_escrow_held')
                FROM escrow_order escrow
                JOIN errand errand ON errand.id = escrow.errand_id
                WHERE escrow.status = 'HELD'
                  AND errand.status IN ('SETTLED', 'REFUNDED', 'CANCELLED')
                """, (rs, rowNum) -> new EscrowDiff(
                rs.getLong("errand_id"),
                rs.getString("status"),
                rs.getString("reason")));
    }

    @Override
    public void recordDiff(LocalDate date, String checkType, String subject,
                           Long expected, Long actual, String detail) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO recon_diff
                    (check_date, check_type, subject, expected, actual, detail)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, date, checkType, subject, expected, actual, detail);
        } catch (DuplicateKeyException ignored) {
            // 对账任务可重复执行，同一主体同一天的同类差异只保留一条。
        }
    }

    @Override
    public int countDiffs(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM recon_diff
                WHERE check_date = ?
                """, Integer.class, date);
        return count == null ? 0 : count;
    }
}
