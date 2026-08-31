-- Existing and newly created feeds remain local-only unless the owner explicitly enables external
-- HTTPS publication against the currently configured consent policy. No existing row is promoted.
ALTER TABLE calendar_feeds
  ADD COLUMN publication_scope VARCHAR(16) NOT NULL DEFAULT 'LOCAL_ONLY',
  ADD COLUMN public_consent_policy_version VARCHAR(64),
  ADD COLUMN public_consent_granted_at TIMESTAMPTZ;

ALTER TABLE calendar_feeds
  ADD CONSTRAINT ck_calendar_feeds_publication_scope
    CHECK (publication_scope IN ('LOCAL_ONLY', 'PUBLIC_HTTPS')),
  ADD CONSTRAINT ck_calendar_feeds_public_consent_pin CHECK (
    (
      publication_scope = 'LOCAL_ONLY'
      AND public_consent_policy_version IS NULL
      AND public_consent_granted_at IS NULL
    )
    OR
    (
      publication_scope = 'PUBLIC_HTTPS'
      AND status = 'ACTIVE'
      AND revoked_at IS NULL
      AND public_consent_policy_version IS NOT NULL
      AND public_consent_policy_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
      AND public_consent_granted_at IS NOT NULL
      AND public_consent_granted_at >= created_at
      AND public_consent_granted_at <= updated_at
    )
  );
