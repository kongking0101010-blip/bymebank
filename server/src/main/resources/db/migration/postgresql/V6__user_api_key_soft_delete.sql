-- V6: Soft-delete + upstream-bank-list cache for user_api_keys.
-- Postgres port of oracle/V6__user_api_key_soft_delete.sql.
-- (V7 will hard-revoke and drop these columns again — this file kept
--  for historical parity so the postgres timeline matches oracle's.)

ALTER TABLE user_api_keys
    ADD COLUMN IF NOT EXISTS status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS deleted_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS banks_cached_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cached_banks_json TEXT;

UPDATE user_api_keys
   SET status     = 'HIDDEN',
       deleted_at = NOW()
 WHERE revoked = TRUE
   AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_api_keys_status
    ON user_api_keys(user_id, status);
