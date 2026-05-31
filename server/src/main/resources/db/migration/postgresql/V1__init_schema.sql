-- Khmer Bank Gateway - initial schema
-- PostgreSQL 16

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                       VARCHAR(255) NOT NULL UNIQUE,
    password_hash               VARCHAR(255) NOT NULL,
    full_name                   VARCHAR(100) NOT NULL,
    phone                       VARCHAR(20),
    company                     VARCHAR(100),
    role                        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    email_verified              BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled                     BOOLEAN      NOT NULL DEFAULT TRUE,
    email_verification_token    VARCHAR(255),
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_email ON users(email);

CREATE TABLE subscriptions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    plan                        VARCHAR(16) NOT NULL DEFAULT 'FREE',
    active                      BOOLEAN     NOT NULL DEFAULT TRUE,
    monthly_quota               BIGINT      NOT NULL DEFAULT 100,
    usage_this_month            BIGINT      NOT NULL DEFAULT 0,
    price                       NUMERIC(19,2) NOT NULL DEFAULT 0,
    current_period_start        TIMESTAMPTZ,
    current_period_end          TIMESTAMPTZ,
    payment_transaction_id      VARCHAR(64),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    prefix          VARCHAR(16)  NOT NULL,
    last4           VARCHAR(4)   NOT NULL,
    key_hash        VARCHAR(128) NOT NULL UNIQUE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    usage_count     BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_apikey_user ON api_keys(user_id);

CREATE TABLE merchants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_type           VARCHAR(16)  NOT NULL,
    merchant_name       VARCHAR(100) NOT NULL,
    merchant_city       VARCHAR(50),
    merchant_id         VARCHAR(100) NOT NULL,
    merchant_link       VARCHAR(500),
    account_number      VARCHAR(50),
    encrypted_secret    VARCHAR(1024),
    verified            BOOLEAN NOT NULL DEFAULT FALSE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_merchant_user ON merchants(user_id);
CREATE INDEX idx_merchant_bank ON merchants(bank_type);

CREATE TABLE qr_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  VARCHAR(64)  NOT NULL UNIQUE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_id     UUID NOT NULL REFERENCES merchants(id),
    bank_type       VARCHAR(16)  NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(4)   NOT NULL,
    qr_payload      VARCHAR(4096) NOT NULL,
    qr_image_base64 TEXT,
    description     VARCHAR(512),
    reference       VARCHAR(256),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    bank_reference  VARCHAR(255),
    paid_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_qr_user   ON qr_codes(user_id);
CREATE INDEX idx_qr_status ON qr_codes(status);

CREATE TABLE transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    qr_id           UUID REFERENCES qr_codes(id),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_type       VARCHAR(16) NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(4)  NOT NULL,
    status          VARCHAR(16) NOT NULL,
    bank_reference  VARCHAR(255),
    payer_name      VARCHAR(255),
    payer_account   VARCHAR(50),
    raw_callback    VARCHAR(4096),
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_txn_qr      ON transactions(qr_id);
CREATE INDEX idx_txn_bankref ON transactions(bank_reference);

CREATE TABLE webhooks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callback_url        VARCHAR(1024) NOT NULL,
    secret              VARCHAR(256)  NOT NULL,
    active              BOOLEAN  NOT NULL DEFAULT TRUE,
    delivery_count      BIGINT   NOT NULL DEFAULT 0,
    failure_count       BIGINT   NOT NULL DEFAULT 0,
    last_delivered_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhook_user ON webhooks(user_id);
