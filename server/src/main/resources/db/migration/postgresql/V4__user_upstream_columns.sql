-- V4: legacy upstream-api-key columns mirrored on user row.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS upstream_api_key            VARCHAR(128),
    ADD COLUMN IF NOT EXISTS upstream_api_key_issued_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS upstream_api_key_expires_at TIMESTAMPTZ;
