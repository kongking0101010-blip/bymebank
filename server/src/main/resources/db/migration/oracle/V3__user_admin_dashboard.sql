-- V3: User + admin dashboard plumbing.
--
-- NOTE: The project's existing tables use RAW(16) UUIDs as primary keys (the
-- spec mentioned NUMBER IDENTITY for new tables). To keep the Hibernate
-- @GeneratedValue(strategy=UUID) consistent across all entities we keep
-- RAW(16) PKs for the new tables too. All other columns match the spec.

-- ── USERS — extra columns for OAuth + status + lockout ────────────────────
ALTER TABLE users ADD (
    google_sub      VARCHAR2(64),
    avatar_url      VARCHAR2(500),
    status          VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    locked_until    TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX idx_users_google_sub ON users(google_sub);
-- Google-only users have no password; relax the NOT NULL.
ALTER TABLE users MODIFY (password_hash VARCHAR2(255) NULL);

-- ── EMAIL_OTPS ───────────────────────────────────────────────────────────
CREATE TABLE email_otps (
    id              RAW(16) PRIMARY KEY,
    email           VARCHAR2(255) NOT NULL,
    code_hash       VARCHAR2(128) NOT NULL,
    purpose         VARCHAR2(20)  NOT NULL,    -- LOGIN | VERIFY_EMAIL
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP WITH TIME ZONE,
    attempt_count   NUMBER(5)     DEFAULT 0 NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_email_otps_email_exp ON email_otps(email, expires_at);

-- ── USER_API_KEYS — additional columns to match v3 spec ──────────────────
ALTER TABLE user_api_keys ADD (
    api_key_hash    VARCHAR2(128),
    plan_id         VARCHAR2(20),
    revoked_at      TIMESTAMP WITH TIME ZONE,
    revoke_reason   VARCHAR2(120),
    last_used_at    TIMESTAMP WITH TIME ZONE
);

-- backfill api_key_hash for any pre-existing rows
UPDATE user_api_keys SET api_key_hash = api_key WHERE api_key_hash IS NULL;
UPDATE user_api_keys SET plan_id      = '1month' WHERE plan_id      IS NULL;
ALTER TABLE user_api_keys MODIFY (api_key_hash VARCHAR2(128) NOT NULL);
ALTER TABLE user_api_keys MODIFY (plan_id      VARCHAR2(20)  NOT NULL);

CREATE UNIQUE INDEX idx_user_api_keys_hash ON user_api_keys(api_key_hash);
CREATE INDEX idx_user_api_keys_user_active
    ON user_api_keys(user_id, revoked, expires_at);

-- ── BRIDGE_TRANSACTIONS — sk_-flow tx history (separate from existing
--    transactions table which is bound to qr_codes/legacy flow) ────────────
CREATE TABLE bridge_transactions (
    id              RAW(16) PRIMARY KEY,
    user_id         RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key_id      RAW(16) REFERENCES user_api_keys(id) ON DELETE SET NULL,
    md5             VARCHAR2(32) NOT NULL UNIQUE,
    bank            VARCHAR2(20) NOT NULL,
    amount          NUMBER(12,2) NOT NULL,
    currency        VARCHAR2(3)  NOT NULL,
    status          VARCHAR2(20) NOT NULL,        -- PENDING|PAID|UNPAID|EXPIRED
    paid_at         TIMESTAMP WITH TIME ZONE,
    paid_from       VARCHAR2(120),
    qr_string       CLOB,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_bridge_tx_user_created
    ON bridge_transactions(user_id, created_at DESC);

-- ── AUDIT_LOG ────────────────────────────────────────────────────────────
CREATE TABLE audit_log (
    id              RAW(16) PRIMARY KEY,
    user_id         RAW(16) REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR2(60) NOT NULL,
    target_type     VARCHAR2(40),
    target_id       VARCHAR2(64),
    metadata        CLOB,
    ip              VARCHAR2(45),
    user_agent      VARCHAR2(500),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_audit_user_created ON audit_log(user_id, created_at DESC);
