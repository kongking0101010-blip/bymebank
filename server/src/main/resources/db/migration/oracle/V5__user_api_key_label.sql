-- V5: Multiple API keys per user.
-- Allow users to buy more than one sk_ key and label each one.

ALTER TABLE user_api_keys ADD (
    label       VARCHAR2(80),                                 -- user-facing name (e.g. "Production")
    is_primary  NUMBER(1) DEFAULT 0 NOT NULL                  -- 1 → the default for single-key endpoints
);

-- Backfill: mark the most recent active key per user as primary.
MERGE INTO user_api_keys tgt
USING (
    SELECT id
    FROM (
        SELECT id, user_id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id
                   ORDER BY revoked ASC, issued_at DESC
               ) rn
        FROM user_api_keys
    )
    WHERE rn = 1
) src
ON (tgt.id = src.id)
WHEN MATCHED THEN UPDATE SET tgt.is_primary = 1;

-- Backfill labels: "Key 1", "Key 2", … by issue order, oldest first.
MERGE INTO user_api_keys tgt
USING (
    SELECT id,
           'Key ' || ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY issued_at ASC
           ) AS calc_label
    FROM user_api_keys
) src
ON (tgt.id = src.id)
WHEN MATCHED THEN UPDATE SET tgt.label = src.calc_label
                       WHERE tgt.label IS NULL;
