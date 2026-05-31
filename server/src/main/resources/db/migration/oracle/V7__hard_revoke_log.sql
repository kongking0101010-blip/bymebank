-- V7: Hard-revoke model.
--
-- Replaces the V6 soft-delete (status / deleted_at) with a hard-revoke flow:
--   • DELETE the user_api_keys row outright when the user clicks
--     "Permanently revoke" — no soft-delete, no Deleted tab, no restore.
--   • Audit each kill in revocation_log (sk_ stored as sha256 hash, never
--     cleartext).
--
-- Triggered by the dashboard which first calls upstream
-- /api/owner/revoke_key so the key dies on the bot too. Oracle cleanup
-- only proceeds if upstream returns 2xx OR 404 ("already gone").
--
-- All DDL is wrapped in try/ignore blocks so this migration is idempotent
-- and can be re-applied on a partially-migrated database without manual
-- cleanup.

-- ── revocation_log table ──────────────────────────────────────────
DECLARE table_missing EXCEPTION;
        PRAGMA EXCEPTION_INIT(table_missing, -955);   -- ORA-00955 already exists
BEGIN
    EXECUTE IMMEDIATE q'[
        CREATE TABLE revocation_log (
            id           RAW(16)        DEFAULT SYS_GUID() NOT NULL PRIMARY KEY,
            user_id      RAW(16)        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            sk_key_hash  VARCHAR2(64)   NOT NULL,
            revoked_at   TIMESTAMP WITH TIME ZONE NOT NULL,
            source       VARCHAR2(32)   NOT NULL
        )
    ]';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 THEN RAISE; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE
        'CREATE INDEX idx_revocation_log_user ON revocation_log(user_id, revoked_at)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE
        'CREATE INDEX idx_revocation_log_hash ON revocation_log(sk_key_hash)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 AND SQLCODE != -1408 THEN RAISE; END IF;
END;
/

-- ── Migrate any leftover V6 HIDDEN rows into revocation_log, then nuke ──
-- Only runs if the V6 columns still exist (idempotent).
DECLARE
    v_has_status NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_has_status
    FROM   user_tab_columns
    WHERE  table_name = 'USER_API_KEYS' AND column_name = 'STATUS';

    IF v_has_status > 0 THEN
        EXECUTE IMMEDIATE q'[
            INSERT INTO revocation_log (id, user_id, sk_key_hash, revoked_at, source)
            SELECT SYS_GUID(),
                   user_id,
                   LOWER(STANDARD_HASH(api_key, 'SHA256')),
                   NVL(deleted_at, SYSTIMESTAMP),
                   'v7_migration'
              FROM user_api_keys
             WHERE status = 'HIDDEN'
               AND api_key IS NOT NULL
        ]';

        EXECUTE IMMEDIATE q'[DELETE FROM user_api_keys WHERE status = 'HIDDEN']';

        -- Drop the V6 columns + index in one shot. Wrapped because re-runs.
        BEGIN
            EXECUTE IMMEDIATE
                'ALTER TABLE user_api_keys DROP (status, deleted_at, banks_cached_at, cached_banks_json)';
        EXCEPTION
            WHEN OTHERS THEN
                -- ORA-00904 (column missing) means already-dropped. Safe to ignore.
                IF SQLCODE != -904 THEN RAISE; END IF;
        END;
    END IF;
END;
/

-- Drop the V6 index if it still exists (some paths drop columns without
-- cascading the index).
BEGIN
    EXECUTE IMMEDIATE 'DROP INDEX idx_user_api_keys_status';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1418 THEN RAISE; END IF;   -- ORA-01418 = index missing
END;
/
