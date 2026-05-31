-- Khmer Bank Gateway — initial schema (Oracle 19c+ / 21c / 23ai)
-- Uses VARCHAR2, NUMBER, TIMESTAMP WITH TIME ZONE, RAW(16) for UUIDs.

CREATE TABLE users (
    id                          RAW(16) PRIMARY KEY,
    email                       VARCHAR2(255) NOT NULL UNIQUE,
    password_hash               VARCHAR2(255) NOT NULL,
    full_name                   VARCHAR2(100) NOT NULL,
    phone                       VARCHAR2(20),
    company                     VARCHAR2(100),
    role                        VARCHAR2(20)  DEFAULT 'USER' NOT NULL,
    email_verified              NUMBER(1)     DEFAULT 0 NOT NULL,
    enabled                     NUMBER(1)     DEFAULT 1 NOT NULL,
    email_verification_token    VARCHAR2(255),
    last_login_at               TIMESTAMP WITH TIME ZONE,
    created_at                  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
-- email already has a unique index from the UNIQUE constraint; no extra index needed.

CREATE TABLE subscriptions (
    id                      RAW(16) PRIMARY KEY,
    user_id                 RAW(16) NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    plan                    VARCHAR2(16) DEFAULT 'FREE' NOT NULL,
    active                  NUMBER(1)    DEFAULT 1 NOT NULL,
    monthly_quota           NUMBER(19)   DEFAULT 100 NOT NULL,
    usage_this_month        NUMBER(19)   DEFAULT 0 NOT NULL,
    price                   NUMBER(19,2) DEFAULT 0 NOT NULL,
    current_period_start    TIMESTAMP WITH TIME ZONE,
    current_period_end      TIMESTAMP WITH TIME ZONE,
    payment_transaction_id  VARCHAR2(64),
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE api_keys (
    id              RAW(16) PRIMARY KEY,
    user_id         RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR2(100) NOT NULL,
    prefix          VARCHAR2(16)  NOT NULL,
    last4           VARCHAR2(4)   NOT NULL,
    key_hash        VARCHAR2(128) NOT NULL UNIQUE,
    active          NUMBER(1)     DEFAULT 1 NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    usage_count     NUMBER(19)    DEFAULT 0 NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_apikey_user ON api_keys(user_id);

CREATE TABLE merchants (
    id                  RAW(16) PRIMARY KEY,
    user_id             RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_type           VARCHAR2(16)  NOT NULL,
    merchant_name       VARCHAR2(100) NOT NULL,
    merchant_city       VARCHAR2(50),
    merchant_id         VARCHAR2(100) NOT NULL,
    merchant_link       VARCHAR2(500),
    account_number      VARCHAR2(50),
    encrypted_secret    VARCHAR2(1024),
    verified            NUMBER(1) DEFAULT 0 NOT NULL,
    active              NUMBER(1) DEFAULT 1 NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_merchant_user ON merchants(user_id);
CREATE INDEX idx_merchant_bank ON merchants(bank_type);

CREATE TABLE qr_codes (
    id              RAW(16) PRIMARY KEY,
    transaction_id  VARCHAR2(64)   NOT NULL UNIQUE,
    user_id         RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_id     RAW(16) NOT NULL REFERENCES merchants(id),
    bank_type       VARCHAR2(16)   NOT NULL,
    amount          NUMBER(19,4)   NOT NULL,
    currency        VARCHAR2(4)    NOT NULL,
    qr_payload      VARCHAR2(4000) NOT NULL,
    qr_image_base64 CLOB,
    description     VARCHAR2(512),
    reference       VARCHAR2(256),
    status          VARCHAR2(16)   DEFAULT 'PENDING' NOT NULL,
    bank_reference  VARCHAR2(255),
    paid_at         TIMESTAMP WITH TIME ZONE,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_qr_user   ON qr_codes(user_id);
CREATE INDEX idx_qr_status ON qr_codes(status);

CREATE TABLE transactions (
    id              RAW(16) PRIMARY KEY,
    qr_id           RAW(16) REFERENCES qr_codes(id),
    user_id         RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bank_type       VARCHAR2(16)   NOT NULL,
    amount          NUMBER(19,4)   NOT NULL,
    currency        VARCHAR2(4)    NOT NULL,
    status          VARCHAR2(16)   NOT NULL,
    bank_reference  VARCHAR2(255),
    payer_name      VARCHAR2(255),
    payer_account   VARCHAR2(50),
    raw_callback    VARCHAR2(4000),
    paid_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_txn_qr      ON transactions(qr_id);
CREATE INDEX idx_txn_bankref ON transactions(bank_reference);

CREATE TABLE webhooks (
    id                  RAW(16) PRIMARY KEY,
    user_id             RAW(16) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callback_url        VARCHAR2(1024) NOT NULL,
    secret              VARCHAR2(256)  NOT NULL,
    active              NUMBER(1)      DEFAULT 1 NOT NULL,
    delivery_count      NUMBER(19)     DEFAULT 0 NOT NULL,
    failure_count       NUMBER(19)     DEFAULT 0 NOT NULL,
    last_delivered_at   TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX idx_webhook_user ON webhooks(user_id);
