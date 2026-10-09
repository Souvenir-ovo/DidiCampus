SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS errand (
  id BIGINT NOT NULL,
  campus_id BIGINT NOT NULL,
  publisher_id BIGINT NOT NULL,
  grabber_id BIGINT NULL,
  type VARCHAR(16) NOT NULL,
  title VARCHAR(64) NOT NULL,
  reward_amount BIGINT NOT NULL,
  slot_total INT NOT NULL DEFAULT 1,
  slot_taken INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  round INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  locked_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_errand_campus_status (campus_id, status, created_at),
  KEY idx_errand_timeout (status, locked_at),
  KEY idx_errand_autosettle (status, delivered_at),
  KEY idx_errand_publisher (publisher_id, created_at),
  CONSTRAINT ck_errand_slot CHECK (slot_taken >= 0 AND slot_taken <= slot_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跑腿任务';

CREATE TABLE IF NOT EXISTS errand_status_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  round INT NOT NULL DEFAULT 0,
  operator_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_status_log_campus_errand (campus_id, errand_id, id),
  KEY idx_status_log_errand (errand_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态变更日志';

CREATE TABLE IF NOT EXISTS grab_record (
  id BIGINT NOT NULL,
  campus_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  runner_id BIGINT NOT NULL,
  seq INT NOT NULL,
  round INT NOT NULL DEFAULT 0,
  result VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_grab_round_seq (errand_id, round, seq),
  UNIQUE KEY uk_grab_round_runner (errand_id, round, runner_id),
  KEY idx_grab_campus_errand (campus_id, errand_id),
  KEY idx_grab_runner_time (runner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单记录';

CREATE TABLE IF NOT EXISTS wallet_account (
  id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  owner_type VARCHAR(16) NOT NULL,
  available BIGINT NOT NULL DEFAULT 0,
  frozen BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_wallet_owner (owner_id, owner_type),
  CONSTRAINT ck_wallet_balance CHECK (available >= 0 AND frozen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包余额快照';

CREATE TABLE IF NOT EXISTS wallet_ledger (
  id BIGINT NOT NULL,
  biz_no VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  direction VARCHAR(8) NOT NULL,
  amount BIGINT NOT NULL,
  balance_after BIGINT NOT NULL,
  ref_type VARCHAR(24) NOT NULL,
  ref_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ledger_biz_account_direction (biz_no, account_id, direction),
  KEY idx_ledger_account_time (account_id, created_at),
  KEY idx_ledger_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包资金流水';

CREATE TABLE IF NOT EXISTS escrow_order (
  id BIGINT NOT NULL,
  campus_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  publisher_id BIGINT NOT NULL,
  amount BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_escrow_errand (errand_id),
  KEY idx_escrow_campus_errand (campus_id, errand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务资金托管单';

INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1, -1, 'ESCROW', 0, 0, 0),
  (2, -2, 'COMMISSION', 0, 0, 0),
  (1001, 1001, 'USER', 100000, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE IF NOT EXISTS local_message (
  id BIGINT NOT NULL,
  msg_key VARCHAR(96) NOT NULL,
  topic VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  deliver_at DATETIME(3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_local_message_key (msg_key),
  KEY idx_local_message_scan (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地事务消息';

CREATE TABLE IF NOT EXISTS bench_run (
  run_id VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  scenario VARCHAR(24) NOT NULL,
  started_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  status VARCHAR(16) NOT NULL,
  concurrency INT NULL,
  summary JSON NULL,
  env_note VARCHAR(255) NULL,
  PRIMARY KEY (run_id),
  KEY idx_bench_scenario_time (scenario, started_at),
  KEY idx_bench_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='压测轮次';

CREATE TABLE IF NOT EXISTS bench_run_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id VARCHAR(32) NOT NULL,
  entity_type VARCHAR(16) NOT NULL,
  entity_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_bench_item_run (run_id, entity_type),
  KEY idx_bench_item_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='压测数据关联';

CREATE TABLE IF NOT EXISTS fund_audit_log (
  id BIGINT NOT NULL,
  biz_no VARCHAR(64) NOT NULL,
  action VARCHAR(24) NOT NULL,
  errand_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  detail JSON NULL,
  result VARCHAR(16) NOT NULL,
  message VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fund_audit_biz (biz_no),
  KEY idx_fund_audit_errand (errand_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金审计日志';

CREATE TABLE IF NOT EXISTS notification (
  id BIGINT NOT NULL,
  msg_key VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,
  content VARCHAR(255) NOT NULL,
  read_flag TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_msg_user (msg_key, user_id),
  KEY idx_notification_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知';

CREATE TABLE IF NOT EXISTS recon_diff (
  id BIGINT NOT NULL AUTO_INCREMENT,
  check_date DATE NOT NULL,
  check_type VARCHAR(24) NOT NULL,
  subject VARCHAR(64) NULL,
  expected BIGINT NULL,
  actual BIGINT NULL,
  detail VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_recon_date_type_subject (check_date, check_type, subject),
  KEY idx_recon_date (check_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异';

CREATE TABLE IF NOT EXISTS sync_diff (
  id BIGINT NOT NULL AUTO_INCREMENT,
  check_time DATETIME(3) NOT NULL,
  errand_id BIGINT NOT NULL,
  field VARCHAR(32) NOT NULL,
  db_value VARCHAR(64) NULL,
  cache_value VARCHAR(64) NULL,
  fixed TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_sync_diff_time (check_time),
  KEY idx_sync_diff_errand (errand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缓存一致性差异';

CREATE TABLE IF NOT EXISTS credit_score (
  user_id BIGINT NOT NULL,
  score INT NOT NULL DEFAULT 60,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分快照';

CREATE TABLE IF NOT EXISTS credit_event (
  id BIGINT NOT NULL,
  biz_no VARCHAR(96) NOT NULL,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  delta INT NOT NULL,
  ref_type VARCHAR(16) NOT NULL,
  ref_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_event_biz (biz_no),
  KEY idx_credit_event_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用事件流水';
