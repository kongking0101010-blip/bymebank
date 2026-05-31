CREATE TABLE user_api_keys (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key       VARCHAR(64) NOT NULL UNIQUE,
    merchant_name VARCHAR(64) NOT NULL,
    banks_json    TEXT,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked       BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_user_api_keys_user_id ON user_api_keys(user_id);
CREATE INDEX idx_user_api_keys_active  ON user_api_keys(user_id, expires_at) WHERE revoked = FALSE;
