SET NAMES utf8mb4;

INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
VALUES
  (1, -1, 'ESCROW', 0, 0, 0),
  (2, -2, 'COMMISSION', 0, 0, 0),
  (1001, 1001, 'USER', 100000, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
WITH RECURSIVE runner_seq AS (
  SELECT 2001 AS user_id
  UNION ALL
  SELECT user_id + 1 FROM runner_seq WHERE user_id < 2100
)
SELECT user_id, user_id, 'USER', 0, 0, 0
FROM runner_seq
ON DUPLICATE KEY UPDATE id = id;
