package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.model.GrabRecord;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPersistenceAdapterTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();
    }

    @Test
    void errandRepositoryStoresAggregateAndUsesCasWhenStateChanges() {
        JdbcErrandRepository repository = new JdbcErrandRepository(jdbcTemplate);
        Errand draft = Errand.draft(
                10L,
                1L,
                1001L,
                ErrandType.DELIVERY,
                "取快递",
                Money.fromCents(800),
                1);

        repository.insert(draft);

        assertEquals(1, repository.casPublish(10L, 0L));
        Errand published = repository.findById(10L).orElseThrow();
        assertEquals(ErrandStatus.PUBLISHED, published.status());
        assertEquals(1L, published.version());

        assertEquals(1, repository.casLockForRunner(10L, 2001L, 1L));
        assertEquals(0, repository.casLockForRunner(10L, 2002L, 1L));
        Errand locked = repository.findById(10L).orElseThrow();
        assertEquals(ErrandStatus.LOCKED, locked.status());
        assertEquals(2001L, locked.grabberId());
        assertEquals(1, locked.slotTaken());
        assertEquals(2L, locked.version());
        assertNotNull(locked.lockedAt());

        repository.appendStatusLog(
                10L,
                ErrandStatus.PUBLISHED,
                ErrandStatus.LOCKED,
                locked.round(),
                2001L);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM errand_status_log WHERE errand_id = ?",
                Integer.class,
                10L);
        assertEquals(1, logCount);
    }

    @Test
    void walletRepositoryUpdatesBalancesAndPersistsEscrowFacts() {
        JdbcWalletRepository repository = new JdbcWalletRepository(jdbcTemplate);
        jdbcTemplate.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (1001, 1001, 'USER', 10000, 0, 0),
                       (1, -1, 'ESCROW', 0, 0, 0)
                """);

        WalletAccount publisher = repository.findByOwner(1001L, AccountType.USER).orElseThrow();
        assertEquals(Money.fromCents(10000), publisher.available());

        assertEquals(1, repository.casDebit(publisher.id(), Money.fromCents(3500)));
        assertEquals(0, repository.casDebit(publisher.id(), Money.fromCents(8000)));
        assertEquals(Money.fromCents(6500), repository.findById(1001L).orElseThrow().available());

        assertEquals(1, repository.casCredit(1L, Money.fromCents(3500)));
        assertEquals(Money.fromCents(3500), repository.findById(1L).orElseThrow().available());

        repository.insertLedger(new LedgerEntry(
                201L,
                LedgerEntry.escrowBizNo(10L),
                1001L,
                1001L,
                LedgerEntry.Direction.DEBIT,
                Money.fromCents(3500),
                Money.fromCents(6500),
                LedgerEntry.RefType.ESCROW,
                10L));
        assertTrue(repository.ledgerExists(LedgerEntry.escrowBizNo(10L)));

        repository.insertEscrow(EscrowOrder.held(301L, 1L, 10L, 1001L, Money.fromCents(3500)));
        assertTrue(repository.escrowExists(1L, 10L));
        assertEquals(EscrowOrder.EscrowStatus.HELD,
                repository.findEscrowByErrandId(1L, 10L).orElseThrow().status());
        assertEquals(1, repository.casEscrowStatus(
                1L,
                10L,
                EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.RELEASED));
        assertEquals(0, repository.casEscrowStatus(
                1L,
                10L,
                EscrowOrder.EscrowStatus.HELD,
                EscrowOrder.EscrowStatus.REFUNDED));
    }

    @Test
    void grabRecordAndLocalMessageRepositoriesKeepIdempotentFacts() {
        JdbcGrabRecordRepository grabRepository = new JdbcGrabRecordRepository(jdbcTemplate);
        JdbcLocalMessageRepository messageRepository = new JdbcLocalMessageRepository(jdbcTemplate);

        grabRepository.insert(new GrabRecord(
                501L,
                1L,
                10L,
                2001L,
                1,
                0,
                GrabRecord.GrabResultType.GRABBED,
                Instant.now()));
        assertEquals(1, grabRepository.countGrabbed(1L, 10L));

        Instant deliverAt = Instant.now().minusSeconds(1);
        assertTrue(messageRepository.enqueue(601L, "timeout:10:0", "ERRAND_TIMEOUT", "{}", deliverAt));
        assertFalse(messageRepository.enqueue(602L, "timeout:10:0", "ERRAND_TIMEOUT", "{}", deliverAt));

        assertEquals(1, messageRepository.findPending(10).size());
        messageRepository.markRetry("timeout:10:0", 1);
        assertTrue(messageRepository.findPending(10).isEmpty());

        LocalMessageRepository.PendingMessage saved = jdbcTemplate.queryForObject("""
                SELECT id, msg_key, topic, payload, deliver_at, retry_count
                FROM local_message
                WHERE msg_key = ?
                """,
                (rs, rowNum) -> new LocalMessageRepository.PendingMessage(
                        rs.getLong("id"),
                        rs.getString("msg_key"),
                        rs.getString("topic"),
                        rs.getString("payload"),
                        JdbcTime.instant(rs.getTimestamp("deliver_at")),
                        rs.getInt("retry_count")),
                "timeout:10:0");
        assertNotNull(saved);
        assertEquals(1, saved.retryCount());
    }

    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE errand (
                  id BIGINT PRIMARY KEY,
                  campus_id BIGINT NOT NULL,
                  publisher_id BIGINT NOT NULL,
                  grabber_id BIGINT NULL,
                  type VARCHAR(16) NOT NULL,
                  title VARCHAR(64) NOT NULL,
                  reward_amount BIGINT NOT NULL,
                  slot_total INT NOT NULL,
                  slot_taken INT NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  round INT NOT NULL,
                  version BIGINT NOT NULL,
                  locked_at TIMESTAMP NULL,
                  delivered_at TIMESTAMP NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE errand_status_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  campus_id BIGINT NOT NULL,
                  errand_id BIGINT NOT NULL,
                  from_status VARCHAR(16) NOT NULL,
                  to_status VARCHAR(16) NOT NULL,
                  round INT NOT NULL,
                  operator_id BIGINT NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE grab_record (
                  id BIGINT PRIMARY KEY,
                  campus_id BIGINT NOT NULL,
                  errand_id BIGINT NOT NULL,
                  runner_id BIGINT NOT NULL,
                  seq INT NOT NULL,
                  round INT NOT NULL,
                  result VARCHAR(16) NOT NULL,
                  created_at TIMESTAMP NOT NULL,
                  UNIQUE (errand_id, round, seq),
                  UNIQUE (errand_id, round, runner_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wallet_account (
                  id BIGINT PRIMARY KEY,
                  owner_id BIGINT NOT NULL,
                  owner_type VARCHAR(16) NOT NULL,
                  available BIGINT NOT NULL,
                  frozen BIGINT NOT NULL,
                  version BIGINT NOT NULL,
                  UNIQUE (owner_id, owner_type)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE wallet_ledger (
                  id BIGINT PRIMARY KEY,
                  biz_no VARCHAR(64) NOT NULL,
                  account_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  direction VARCHAR(8) NOT NULL,
                  amount BIGINT NOT NULL,
                  balance_after BIGINT NOT NULL,
                  ref_type VARCHAR(24) NOT NULL,
                  ref_id BIGINT NOT NULL,
                  UNIQUE (biz_no, account_id, direction)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE escrow_order (
                  id BIGINT PRIMARY KEY,
                  campus_id BIGINT NOT NULL,
                  errand_id BIGINT NOT NULL,
                  publisher_id BIGINT NOT NULL,
                  amount BIGINT NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  UNIQUE (errand_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE local_message (
                  id BIGINT PRIMARY KEY,
                  msg_key VARCHAR(96) NOT NULL UNIQUE,
                  topic VARCHAR(64) NOT NULL,
                  payload VARCHAR(1000) NOT NULL,
                  deliver_at TIMESTAMP NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  retry_count INT NOT NULL,
                  next_retry_at TIMESTAMP NOT NULL
                )
                """);
    }
}
