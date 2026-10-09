package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.LocalMessageRepository;
import com.didicampus.domain.grab.model.GrabRecord;
import com.didicampus.domain.credit.model.CreditEvent;
import com.didicampus.domain.credit.model.CreditEventType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.domain.notify.ports.NotificationQueryPort;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.WalletQueryPort;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
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

    @Test
    void queryAdaptersReturnReadModelsWithExpectedOrdering() {
        Instant oldTime = Instant.now().minusSeconds(60);
        Instant newTime = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO errand
                (id, campus_id, publisher_id, grabber_id, type, title, reward_amount,
                 slot_total, slot_taken, status, round, version, locked_at, delivered_at, created_at)
                VALUES
                (10, 1, 1001, 2001, 'DELIVERY', '旧任务', 800, 1, 1, 'ACCEPTED', 0, 2, ?, NULL, ?),
                (11, 1, 1001, NULL, 'BUY', '新任务', 1200, 1, 0, 'PUBLISHED', 0, 1, NULL, NULL, ?),
                (12, 2, 1002, NULL, 'QUEUE', '别的校区', 500, 1, 0, 'PUBLISHED', 0, 1, NULL, NULL, ?)
                """, JdbcTime.timestamp(oldTime), JdbcTime.timestamp(oldTime),
                JdbcTime.timestamp(newTime), JdbcTime.timestamp(newTime));
        jdbcTemplate.update("""
                INSERT INTO grab_record
                (id, campus_id, errand_id, runner_id, seq, round, result, created_at)
                VALUES (501, 1, 10, 2001, 1, 0, 'GRABBED', ?)
                """, JdbcTime.timestamp(oldTime));
        jdbcTemplate.update("""
                INSERT INTO errand_status_log
                (campus_id, errand_id, from_status, to_status, round, operator_id, created_at)
                VALUES (1, 10, 'PUBLISHED', 'LOCKED', 0, 2001, ?)
                """, JdbcTime.timestamp(oldTime));
        jdbcTemplate.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (1001, 1001, 'USER', 9000, 0, 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO wallet_ledger
                (id, biz_no, account_id, user_id, direction, amount, balance_after, ref_type, ref_id, created_at)
                VALUES (701, 'escrow:10', 1001, 1001, 'DEBIT', 800, 9200, 'ESCROW', 10, ?)
                """, JdbcTime.timestamp(oldTime));

        JdbcErrandQueryAdapter errandQuery = new JdbcErrandQueryAdapter(jdbcTemplate);
        JdbcWalletQueryAdapter walletQuery = new JdbcWalletQueryAdapter(jdbcTemplate);

        assertEquals(11L, errandQuery.list(1L, "PUBLISHED", 0, 10).getFirst().id());
        assertEquals(11L, errandQuery.listByCursor(1L, "PUBLISHED", null, null, 10).getFirst().errand().id());
        assertEquals(2, errandQuery.listByPublisher(1001L, 0, 10).size());
        assertEquals(10L, errandQuery.listByRunner(2001L, 0, 10).getFirst().id());
        assertEquals(1, errandQuery.countOngoingByRunner(2001L));

        ErrandQueryPort.StatusChange change = errandQuery.statusLog(1L, 10L).getFirst();
        assertEquals("PUBLISHED", change.from());
        assertEquals("LOCKED", change.to());

        WalletQueryPort.BalanceView balance = walletQuery.findBalance(1001L).orElseThrow();
        assertEquals(9000L, balance.availableCents());
        assertEquals("escrow:10", walletQuery.ledger(1001L, 0, 10).getFirst().bizNo());
    }

    @Test
    void notificationCreditAuditReconAndSyncAdaptersPersistSupportData() {
        JdbcNotificationRepository notificationRepository = new JdbcNotificationRepository(jdbcTemplate);
        JdbcNotificationQueryAdapter notificationQuery = new JdbcNotificationQueryAdapter(jdbcTemplate);
        JdbcCreditRepository creditRepository = new JdbcCreditRepository(jdbcTemplate);
        JdbcSyncDiffRepository syncDiffRepository = new JdbcSyncDiffRepository(jdbcTemplate);
        JdbcReconRepository reconRepository = new JdbcReconRepository(jdbcTemplate);
        JdbcFundAuditAdapter auditAdapter = new JdbcFundAuditAdapter(jdbcTemplate, new SnowflakeIdGenerator(1));

        assertTrue(notificationRepository.insertIfAbsent(801L, "settle:10:2001", 2001L, 10L,
                "SETTLED", "任务已结算"));
        assertFalse(notificationRepository.insertIfAbsent(802L, "settle:10:2001", 2001L, 10L,
                "SETTLED", "重复消息"));
        assertEquals(1, notificationQuery.unreadCount(2001L));
        NotificationQueryPort.NotificationView view = notificationQuery.list(2001L, 0, 10).getFirst();
        assertEquals(10L, view.errandId());
        assertEquals(1, notificationRepository.markAllRead(2001L));
        assertEquals(0, notificationQuery.unreadCount(2001L));

        CreditEvent event = new CreditEvent(901L, "settle:10", 2001L,
                CreditEventType.SETTLE, CreditEventType.SETTLE.delta(),
                "ERRAND", 10L, Instant.now());
        assertTrue(creditRepository.applyEvent(event));
        assertFalse(creditRepository.applyEvent(event));
        assertEquals(62, creditRepository.scoreOf(2001L));
        assertEquals(2, creditRepository.windowDelta(2001L, 30));
        assertEquals(1, creditRepository.recentEvents(2001L, 30, 10).size());
        jdbcTemplate.update("UPDATE credit_score SET score = 40 WHERE user_id = 2001");
        assertEquals(1, creditRepository.calibrateScores(30, 10));
        assertEquals(62, creditRepository.scoreOf(2001L));

        syncDiffRepository.record(Instant.now(), 10L, "status", "PUBLISHED", "LOCKED", true);
        assertEquals(1L, syncDiffRepository.countSince(Instant.now().minusSeconds(10)));

        auditAdapter.record("settle:10", "SETTLE", 10L, -1L, "{}", true, "ok");
        Integer auditCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM fund_audit_log", Integer.class);
        assertEquals(1, auditCount);

        jdbcTemplate.update("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (1, -1, 'ESCROW', 100, 0, 0)
                """);
        assertEquals(1, reconRepository.findSnapshotDiffs().size());

        reconRepository.recordDiff(java.time.LocalDate.now(), "SNAPSHOT", "1", 0L, 100L, "balance mismatch");
        reconRepository.recordDiff(java.time.LocalDate.now(), "SNAPSHOT", "1", 0L, 100L, "balance mismatch");
        assertEquals(1, reconRepository.countDiffs(java.time.LocalDate.now()));
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
                  delivered_at TIMESTAMP NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
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
        jdbcTemplate.execute("""
                CREATE TABLE notification (
                  id BIGINT PRIMARY KEY,
                  msg_key VARCHAR(96) NOT NULL,
                  user_id BIGINT NOT NULL,
                  errand_id BIGINT NOT NULL,
                  type VARCHAR(24) NOT NULL,
                  content VARCHAR(255) NOT NULL,
                  read_flag TINYINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE (msg_key, user_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE credit_score (
                  user_id BIGINT PRIMARY KEY,
                  score INT NOT NULL,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  version BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE credit_event (
                  id BIGINT PRIMARY KEY,
                  biz_no VARCHAR(96) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  type VARCHAR(32) NOT NULL,
                  delta INT NOT NULL,
                  ref_type VARCHAR(16) NOT NULL,
                  ref_id BIGINT NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sync_diff (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  check_time TIMESTAMP NOT NULL,
                  errand_id BIGINT NOT NULL,
                  field VARCHAR(32) NOT NULL,
                  db_value VARCHAR(64),
                  cache_value VARCHAR(64),
                  fixed TINYINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE fund_audit_log (
                  id BIGINT PRIMARY KEY,
                  biz_no VARCHAR(64) NOT NULL,
                  action VARCHAR(24) NOT NULL,
                  errand_id BIGINT NOT NULL,
                  operator_id BIGINT NOT NULL,
                  detail VARCHAR(1000),
                  result VARCHAR(16) NOT NULL,
                  message VARCHAR(255),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE recon_diff (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  check_date DATE NOT NULL,
                  check_type VARCHAR(24) NOT NULL,
                  subject VARCHAR(64),
                  expected BIGINT,
                  actual BIGINT,
                  detail VARCHAR(500),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE (check_date, check_type, subject)
                )
                """);
    }
}
