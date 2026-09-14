-- Review and run separately before deploying the new application. NOT auto-applied.
-- Observed 2026-09-03 in the configured Tomcat pasDS: 1 row, 1 guardian, 0 duplicate guardians.
SHOW CREATE TABLE tb_guardian_notification_setting;
SELECT guardian_id, COUNT(*) AS setting_count
FROM tb_guardian_notification_setting GROUP BY guardian_id HAVING COUNT(*) > 1;
SELECT s.* FROM tb_guardian_notification_setting s
JOIN (SELECT guardian_id FROM tb_guardian_notification_setting
      GROUP BY guardian_id HAVING COUNT(*) > 1) d ON d.guardian_id = s.guardian_id
ORDER BY s.guardian_id, s.setting_id;

-- Check foreign keys, external consumers and triggers before changing the schema.
SELECT TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
AND (TABLE_NAME = 'tb_guardian_notification_setting'
     OR REFERENCED_TABLE_NAME = 'tb_guardian_notification_setting');
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_STATEMENT
FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE();

-- Stop writes and take a verified backup first. If any guardian has multiple rows,
-- STOP: choose common preferences with the account owner. Do not automatically
-- merge, select the latest row, delete, or add UNIQUE before resolving duplicates.
-- The original pair index and protege_id values are retained for audit/rollback.
-- If UNIQUE creation fails, inspect the schema before proceeding or retrying.
-- No existing rows are deleted or changed by this script.
ALTER TABLE tb_guardian_notification_setting
    MODIFY COLUMN protege_id VARCHAR(50) NULL DEFAULT NULL,
    ADD COLUMN battery_50_enabled TINYINT NULL DEFAULT NULL,
    ADD COLUMN battery_25_enabled TINYINT NULL DEFAULT NULL,
    ADD COLUMN battery_10_enabled TINYINT NULL DEFAULT NULL,
    ADD UNIQUE KEY uk_guardian_notification_account (guardian_id);

-- Nullable battery flags preserve legacy data. Until first save, the application
-- reads the old enabled flag + selected threshold as one enabled threshold only.
-- Confirm this conversion with the service owner if an external sender previously
-- interpreted the old threshold cumulatively. No legacy values are overwritten.
-- No DROP COLUMN is necessary for this change. Old application versions must not
-- continue writing after cutover: their pair-scoped UPSERT/DELETE is incompatible.
