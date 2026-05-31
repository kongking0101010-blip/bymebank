-- V5: Multiple API keys per user.
-- Allow users to buy more than one sk_ key and label each one.
-- Postgres port of oracle/V5__user_api_key_label.sql.

ALTER TABLE user_api_keys
    ADD COLUMN IF NOT EXISTS label      VARCHAR(80),
    ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: mark the most recent active key per user as primary.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY revoked ASC, issued_at DESC
           ) AS rn
    FROM user_api_keys
)
UPDATE user_api_keys uak
   SET is_primary = TRUE
  FROM ranked
 WHERE uak.id = ranked.id
   AND ranked.rn = 1;

-- Backfill labels: "Key 1", "Key 2", … by issue order, oldest first.
WITH ordered AS (
    SELECT id,
           'Key ' || ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY issued_at ASC
           ) AS calc_label
    FROM user_api_keys
)
UPDATE user_api_keys uak
   SET label = ordered.calc_label
  FROM ordered
 WHERE uak.id = ordered.id
   AND uak.label IS NULL;
