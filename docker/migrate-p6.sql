SET NAMES utf8mb4;

SET @add_status_log_campus = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE errand_status_log ADD COLUMN campus_id BIGINT NULL AFTER id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'errand_status_log'
    AND column_name = 'campus_id'
);
PREPARE add_status_log_campus FROM @add_status_log_campus;
EXECUTE add_status_log_campus;
DEALLOCATE PREPARE add_status_log_campus;

SET @add_grab_campus = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE grab_record ADD COLUMN campus_id BIGINT NULL AFTER id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'grab_record'
    AND column_name = 'campus_id'
);
PREPARE add_grab_campus FROM @add_grab_campus;
EXECUTE add_grab_campus;
DEALLOCATE PREPARE add_grab_campus;

SET @add_escrow_campus = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE escrow_order ADD COLUMN campus_id BIGINT NULL AFTER id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'escrow_order'
    AND column_name = 'campus_id'
);
PREPARE add_escrow_campus FROM @add_escrow_campus;
EXECUTE add_escrow_campus;
DEALLOCATE PREPARE add_escrow_campus;

UPDATE grab_record gr
JOIN errand e ON e.id = gr.errand_id
SET gr.campus_id = e.campus_id
WHERE gr.campus_id IS NULL;

UPDATE escrow_order eo
JOIN errand e ON e.id = eo.errand_id
SET eo.campus_id = e.campus_id
WHERE eo.campus_id IS NULL;

UPDATE errand_status_log log_item
JOIN errand e ON e.id = log_item.errand_id
SET log_item.campus_id = e.campus_id
WHERE log_item.campus_id IS NULL;

UPDATE grab_record SET campus_id = 1 WHERE campus_id IS NULL;
UPDATE escrow_order SET campus_id = 1 WHERE campus_id IS NULL;
UPDATE errand_status_log SET campus_id = 1 WHERE campus_id IS NULL;

ALTER TABLE grab_record MODIFY COLUMN campus_id BIGINT NOT NULL;
ALTER TABLE escrow_order MODIFY COLUMN campus_id BIGINT NOT NULL;
ALTER TABLE errand_status_log MODIFY COLUMN campus_id BIGINT NOT NULL;
