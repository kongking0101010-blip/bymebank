CREATE TABLE user_api_keys (
    id              RAW(16) PRIMARY KEY,
    user_id         RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key         VARCHAR2(64) NOT NULL UNIQUE,
    merchant_name   VARCHAR2(64) NOT NULL,
    banks_json      CLOB,
    issued_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked         NUMBER(1) DEFAULT 0 NOT NULL
);
CREATE INDEX idx_user_api_keys_user_id ON user_api_keys(user_id);
