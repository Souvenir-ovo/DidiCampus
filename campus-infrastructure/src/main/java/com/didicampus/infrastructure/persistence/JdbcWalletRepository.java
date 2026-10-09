package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<WalletAccount> findByOwner(long ownerId, AccountType type) {
        List<WalletAccount> rows = jdbcTemplate.query("""
                SELECT id, owner_id, owner_type, available, frozen, version
                FROM wallet_account
                WHERE owner_id = ? AND owner_type = ?
                """, this::mapAccount, ownerId, type.name());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<WalletAccount> findById(long accountId) {
        List<WalletAccount> rows = jdbcTemplate.query("""
                SELECT id, owner_id, owner_type, available, frozen, version
                FROM wallet_account
                WHERE id = ?
                """, this::mapAccount, accountId);
        return rows.stream().findFirst();
    }

    @Override
    public int casDebit(long accountId, Money amount) {
        return jdbcTemplate.update("""
                UPDATE wallet_account
                SET available = available - ?, version = version + 1
                WHERE id = ? AND available >= ?
                """, amount.cents(), accountId, amount.cents());
    }

    @Override
    public int casCredit(long accountId, Money amount) {
        return jdbcTemplate.update("""
                UPDATE wallet_account
                SET available = available + ?, version = version + 1
                WHERE id = ?
                """, amount.cents(), accountId);
    }

    @Override
    public void insertLedger(LedgerEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO wallet_ledger
                (id, biz_no, account_id, user_id, direction, amount, balance_after, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(),
                entry.bizNo(),
                entry.accountId(),
                entry.userId(),
                entry.direction().name(),
                entry.amount().cents(),
                entry.balanceAfter().cents(),
                entry.refType().name(),
                entry.refId());
    }

    @Override
    public void insertEscrow(EscrowOrder order) {
        jdbcTemplate.update("""
                INSERT INTO escrow_order
                (id, campus_id, errand_id, publisher_id, amount, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                order.id(),
                order.campusId(),
                order.errandId(),
                order.publisherId(),
                order.amount().cents(),
                order.status().name());
    }

    @Override
    public boolean escrowExists(long campusId, long errandId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM escrow_order
                WHERE campus_id = ? AND errand_id = ?
                """, Integer.class, campusId, errandId);
        return count != null && count > 0;
    }

    @Override
    public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) {
        List<EscrowOrder> rows = jdbcTemplate.query("""
                SELECT id, campus_id, errand_id, publisher_id, amount, status
                FROM escrow_order
                WHERE campus_id = ? AND errand_id = ?
                """, this::mapEscrow, campusId, errandId);
        return rows.stream().findFirst();
    }

    @Override
    public int casEscrowStatus(long campusId, long errandId,
                               EscrowOrder.EscrowStatus from,
                               EscrowOrder.EscrowStatus to) {
        return jdbcTemplate.update("""
                UPDATE escrow_order
                SET status = ?
                WHERE campus_id = ? AND errand_id = ? AND status = ?
                """, to.name(), campusId, errandId, from.name());
    }

    @Override
    public boolean ledgerExists(String bizNo) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM wallet_ledger
                WHERE biz_no = ?
                """, Integer.class, bizNo);
        return count != null && count > 0;
    }

    private WalletAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new WalletAccount(
                rs.getLong("id"),
                rs.getLong("owner_id"),
                AccountType.valueOf(rs.getString("owner_type")),
                Money.fromCents(rs.getLong("available")),
                Money.fromCents(rs.getLong("frozen")),
                rs.getLong("version"));
    }

    private EscrowOrder mapEscrow(ResultSet rs, int rowNum) throws SQLException {
        return new EscrowOrder(
                rs.getLong("id"),
                rs.getLong("campus_id"),
                rs.getLong("errand_id"),
                rs.getLong("publisher_id"),
                Money.fromCents(rs.getLong("amount")),
                EscrowOrder.EscrowStatus.valueOf(rs.getString("status")));
    }
}
