-- V4: add the legacy upstream-api-key columns the User entity has been
-- carrying since the original VPS integration. They mirror the active sk_
-- key onto the user row for fast lookup.

ALTER TABLE users ADD (
    upstream_api_key             VARCHAR2(128),
    upstream_api_key_issued_at   TIMESTAMP WITH TIME ZONE,
    upstream_api_key_expires_at  TIMESTAMP WITH TIME ZONE
);
