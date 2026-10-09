package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.ports.WalletQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWalletQueryAdapter implements WalletQueryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWalletQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BalanceView> findBalance(long ownerId) {
        List<BalanceView> rows = jdbcTemplate.query("""
                SELECT available, frozen
                FROM wallet_account
                WHERE owner_id = ? AND owner_type = ?
                """, (rs, rowNum) -> new BalanceView(
                rs.getLong("available"),
                rs.getLong("frozen")), ownerId, AccountType.USER.name());
        return rows.stream().findFirst();
    }

    @Override
    public List<LedgerView> ledger(long ownerId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT created_at, direction, amount, ref_type, ref_id, biz_no
                FROM wallet_ledger
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new LedgerView(
                JdbcTime.instant(rs.getTimestamp("created_at")),
                rs.getString("direction"),
                rs.getLong("amount"),
                rs.getString("ref_type"),
                rs.getLong("ref_id"),
                rs.getString("biz_no")), ownerId, size, Math.max(page, 0) * size);
    }
}
