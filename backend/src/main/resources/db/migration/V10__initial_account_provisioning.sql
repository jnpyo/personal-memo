CREATE TABLE initial_account_provisioning (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE', 'CONSUMED')),
  provisioned_user_id UUID,
  method VARCHAR(32) CHECK (method IN ('INTERACTIVE_CLI', 'PREEXISTING')),
  consumed_at TIMESTAMPTZ,
  CONSTRAINT ck_initial_account_provisioning_state
    CHECK (
      (status = 'AVAILABLE'
        AND provisioned_user_id IS NULL
        AND method IS NULL
        AND consumed_at IS NULL)
      OR
      (status = 'CONSUMED'
        AND provisioned_user_id IS NOT NULL
        AND method IS NOT NULL
        AND consumed_at IS NOT NULL)
    )
);

-- Existing claimed accounts permanently close the bootstrap gate. The internal UUID is retained
-- as audit metadata without a foreign key so a later account-deletion policy cannot reopen it.
INSERT INTO initial_account_provisioning(
  singleton,
  status,
  provisioned_user_id,
  method,
  consumed_at
)
SELECT
  TRUE,
  CASE WHEN claimed.id IS NULL THEN 'AVAILABLE' ELSE 'CONSUMED' END,
  claimed.id,
  CASE WHEN claimed.id IS NULL THEN NULL ELSE 'PREEXISTING' END,
  claimed.created_at
FROM (SELECT 1) AS seed
LEFT JOIN LATERAL (
  SELECT id, created_at
  FROM users
  WHERE status <> 'LEGACY_UNCLAIMED'
  ORDER BY created_at, id
  LIMIT 1
) AS claimed ON TRUE;
