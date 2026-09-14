-- MariaDB: 보호자-피보호자 연결 요청 상태
-- 기존 행은 이미 승인된 연결이므로 DEFAULT 'APPROVED'로 보정된다.
ALTER TABLE tb_guardian
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'APPROVED'
    COMMENT '연결 상태(PENDING: 승인 대기, APPROVED: 연결됨)';

UPDATE tb_guardian
   SET status = 'APPROVED'
 WHERE status IS NULL OR status = '';
