-- V7: Hard-revoke model — drop V6 soft-delete columns + add audit log.
-- Postgres port of oracle/V7__hard_revoke_log.sql.
--
-- Migration is fully idempotent (IF NOT EXISTS / IF EXISTS everywhere)
-- so re-running on a partially-migrated database is safe.

CREATE TABLE IF NOT EXISTS revocation_log (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sk_key_hash  VARCHAR(64)  NOT NULL,
    revoked_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    source       VARCHAR(32)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revocation_log_user
    ON revocation_log(user_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_revocation_log_hash
    ON revocation_log(sk_key_hash);

-- Migrate leftover V6 HIDDEN rows into revocation_log, then nuke them.
-- Wrapped in DO block so we only touch columns that still exist.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 'user_api_keys'
           AND column_name = 'status'
    ) THEN
        INSERT INTO revocation_log (user_id, sk_key_hash, revoked_at, source)
        SELECT user_id,
               encode(digest(api_key, 'sha256'), 'hex'),
               COALESCE(deleted_at, NOW()),
               'v7_migration'
          FROM user_api_keys
         WHERE status = 'HIDDEN'
           AND api_key IS NOT NULL;

        DELETE FROM user_api_keys WHERE status = 'HIDDEN';

        ALTER TABLE user_api_keys
            DROP COLUMN IF EXISTS status,
            DROP COLUMN IF EXISTS deleted_at,
            DROP COLUMN IF EXISTS banks_cached_at,
            DROP COLUMN IF EXISTS cached_banks_json;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_user_api_keys_status;

-- The pgcrypto extension is required for digest() above. Created once,
-- idempotent. Render's free Postgres ships pgcrypto pre-installed.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
