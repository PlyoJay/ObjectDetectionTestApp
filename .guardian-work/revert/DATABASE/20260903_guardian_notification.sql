-- Read-only diagnostics. Earlier schema-change proposal is withdrawn.
-- Do not ALTER/DROP columns or add independent battery flags for this feature.
SHOW CREATE TABLE tb_guardian_notification_setting;
SELECT guardian_id, COUNT(*) AS setting_count
FROM tb_guardian_notification_setting
GROUP BY guardian_id HAVING COUNT(*) > 1;
SELECT s.* FROM tb_guardian_notification_setting s
JOIN (SELECT guardian_id FROM tb_guardian_notification_setting
      GROUP BY guardian_id HAVING COUNT(*) > 1) d ON d.guardian_id = s.guardian_id
ORDER BY s.guardian_id, s.setting_id;
-- No automatic merge/delete. Resolve conflicting legacy preferences separately.
