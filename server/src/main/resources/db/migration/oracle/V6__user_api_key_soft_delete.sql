-- V6: Soft-delete + upstream-bank-list cache for user_api_keys.
--
-- We move from a hard-delete model (where the dashboard's "Remove" button
-- deletes the row from Oracle) to a soft-delete model where:
--   • status='ACTIVE'  → key is shown in the dashboard
--   • status='HIDDEN'  → user removed it from their dashboard view, but the
--                        sk_ key is still valid upstream on the bot. Stays
--                        in Oracle for 30 days so the user can restore it.
--   • status='PURGED'  → the 30-day window expired, sk_ scrubbed from
--                        Oracle (still valid upstream — Oracle just forgets).
--
-- We also cache the upstream /key_info bank-list per key so the
-- dashboard's bank picker stays correct even when the upstream cold-starts.

ALTER TABLE user_api_keys ADD (
    -- ACTIVE | HIDDEN | PURGED
    status              VARCHAR2(16)   DEFAULT 'ACTIVE' NOT NULL,
    -- When the user clicked "Remove from dashboard"
    deleted_at          TIMESTAMP WITH TIME ZONE,
    -- Last time we refreshed `cached_banks_json` from upstream /key_info
    banks_cached_at     TIMESTAMP WITH TIME ZONE,
    -- JSON array of {"bank":"aba","accountName":"…","hasQr":true}
    -- coming straight from the bot. Authoritative once cached.
    cached_banks_json   CLOB
);

-- Backfill: any pre-existing revoked rows become HIDDEN with deleted_at=revoked_at
-- so the new soft-delete UI shows them in the "Deleted" tab.
UPDATE user_api_keys
   SET status     = 'HIDDEN',
       deleted_at = revoked_at
 WHERE revoked = 1
   AND deleted_at IS NULL;

CREATE INDEX idx_user_api_keys_status
    ON user_api_keys(user_id, status);
