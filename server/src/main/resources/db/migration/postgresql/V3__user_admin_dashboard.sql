-- V3: User + admin dashboard plumbing (Postgres mirror of Oracle V3).

-- USERS extra columns
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS google_sub   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS avatar_url   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_google_sub ON users(google_sub);
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- EMAIL_OTPS
CREATE TABLE IF NOT EXISTS email_otps (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    code_hash       VARCHAR(128) NOT NULL,
    purpose         VARCHAR(20)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ,
    attempt_count   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_email_otps_email_exp ON email_otps(email, expires_at);

-- USER_API_KEYS extra columns
ALTER TABLE user_api_keys
    ADD COLUMN IF NOT EXISTS api_key_hash  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS plan_id       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS revoked_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoke_reason VARCHAR(120),
    ADD COLUMN IF NOT EXISTS last_used_at  TIMESTAMPTZ;

UPDATE user_api_keys SET api_key_hash = api_key WHERE api_key_hash IS NULL;
UPDATE user_api_keys SET plan_id      = '1month' WHERE plan_id      IS NULL;
ALTER TABLE user_api_keys ALTER COLUMN api_key_hash SET NOT NULL;
ALTER TABLE user_api_keys ALTER COLUMN plan_id      SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_api_keys_hash ON user_api_keys(api_key_hash);
CREATE INDEX IF NOT EXISTS idx_user_api_keys_user_active
    ON user_api_keys(user_id, revoked, expires_at);

-- BRIDGE_TRANSACTIONS
CREATE TABLE IF NOT EXISTS bridge_transactions (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key_id      UUID REFERENCES user_api_keys(id) ON DELETE SET NULL,
    md5             VARCHAR(32) NOT NULL UNIQUE,
    bank            VARCHAR(20) NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    status          VARCHAR(20) NOT NULL,
    paid_at         TIMESTAMPTZ,
    paid_from       VARCHAR(120),
    qr_string       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_bridge_tx_user_created
    ON bridge_transactions(user_id, created_at DESC);

-- AUDIT_LOG
CREATE TABLE IF NOT EXISTS audit_log (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(60) NOT NULL,
    target_type     VARCHAR(40),
    target_id       VARCHAR(64),
    metadata        TEXT,
    ip              VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user_created ON audit_log(user_id, created_at DESC);
