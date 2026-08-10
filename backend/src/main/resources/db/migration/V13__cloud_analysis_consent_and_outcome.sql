ALTER TABLE user_settings
  ADD COLUMN cloud_analysis_consent_policy_version VARCHAR(64),
  ADD COLUMN cloud_analysis_consent_granted_at TIMESTAMPTZ;

-- A legacy boolean did not identify the provider policy that the user accepted. Revoke it rather
-- than treating it as authorization for an unknown future external transfer policy.
UPDATE user_settings
   SET cloud_analysis_consent = FALSE,
       settings_version = settings_version + 1
 WHERE cloud_analysis_consent = TRUE;

ALTER TABLE user_settings
  ADD CONSTRAINT ck_user_settings_cloud_analysis_consent_pin
    CHECK (
      (
        cloud_analysis_consent = FALSE
        AND cloud_analysis_consent_policy_version IS NULL
        AND cloud_analysis_consent_granted_at IS NULL
      )
      OR
      (
        cloud_analysis_consent = TRUE
        AND cloud_analysis_consent_policy_version IS NOT NULL
        AND length(btrim(cloud_analysis_consent_policy_version)) BETWEEN 1 AND 64
        AND cloud_analysis_consent_granted_at IS NOT NULL
      )
    );

ALTER TABLE analysis_runs
  ADD COLUMN cloud_transfer_mode VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
  ADD COLUMN cloud_gateway_version VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN cloud_provider_id VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN cloud_model_version VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN cloud_consent_policy_version VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN cloud_outcome VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED';

-- Older HYBRID/CLOUD rows predate the descriptor and outcome contract. Do not mislabel them as a
-- path that required no cloud enrichment.
UPDATE analysis_runs
   SET cloud_transfer_mode = 'LEGACY_UNKNOWN',
       cloud_gateway_version = 'legacy-unknown',
       cloud_provider_id = 'legacy-unknown',
       cloud_model_version = 'legacy-unknown',
       cloud_consent_policy_version = 'legacy-unknown',
       cloud_outcome = 'LEGACY_UNKNOWN'
 WHERE route IN ('HYBRID', 'CLOUD');

ALTER TABLE analysis_runs
  ADD CONSTRAINT ck_analysis_runs_cloud_transfer_mode
    CHECK (
      cloud_transfer_mode IN (
        'NOT_REQUIRED',
        'LEGACY_UNKNOWN',
        'DESCRIPTOR_UNAVAILABLE',
        'NO_NETWORK',
        'EXTERNAL_MEMO_CONTENT'
      )
    ),
  ADD CONSTRAINT ck_analysis_runs_cloud_gateway_version
    CHECK (length(btrim(cloud_gateway_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_cloud_provider_id
    CHECK (length(btrim(cloud_provider_id)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_cloud_model_version
    CHECK (length(btrim(cloud_model_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_cloud_consent_policy_version
    CHECK (length(btrim(cloud_consent_policy_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_cloud_outcome
    CHECK (
      cloud_outcome IN (
        'NOT_REQUIRED',
        'LEGACY_UNKNOWN',
        'SUCCESS',
        'CONSENT_REQUIRED',
        'UNAVAILABLE',
        'TIMEOUT',
        'RETRY_EXHAUSTED',
        'PROVIDER_ERROR',
        'INVALID_RESPONSE',
        'UNEXPECTED_FAILURE'
      )
    ),
  ADD CONSTRAINT ck_analysis_runs_cloud_evidence_coherence
    CHECK (
      (
        cloud_transfer_mode = 'NOT_REQUIRED'
        AND route IN ('MOCK', 'LOCAL')
        AND cloud_outcome = 'NOT_REQUIRED'
        AND cloud_gateway_version = 'none'
        AND cloud_provider_id = 'none'
        AND cloud_model_version = 'none'
        AND cloud_consent_policy_version = 'none'
      )
      OR
      (
        cloud_transfer_mode = 'LEGACY_UNKNOWN'
        AND route IN ('CLOUD', 'HYBRID')
        AND cloud_outcome = 'LEGACY_UNKNOWN'
        AND cloud_gateway_version = 'legacy-unknown'
        AND cloud_provider_id = 'legacy-unknown'
        AND cloud_model_version = 'legacy-unknown'
        AND cloud_consent_policy_version = 'legacy-unknown'
      )
      OR
      (
        cloud_transfer_mode = 'DESCRIPTOR_UNAVAILABLE'
        AND route IN ('CLOUD', 'HYBRID')
        AND cloud_outcome = 'UNEXPECTED_FAILURE'
        AND cloud_gateway_version = 'unavailable'
        AND cloud_provider_id = 'unavailable'
        AND cloud_model_version = 'unavailable'
        AND cloud_consent_policy_version = 'unavailable'
      )
      OR
      (
        cloud_transfer_mode = 'NO_NETWORK'
        AND route IN ('CLOUD', 'HYBRID')
        AND cloud_outcome IN (
          'SUCCESS',
          'UNAVAILABLE',
          'TIMEOUT',
          'RETRY_EXHAUSTED',
          'PROVIDER_ERROR',
          'INVALID_RESPONSE',
          'UNEXPECTED_FAILURE'
        )
        AND cloud_gateway_version NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_provider_id NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_model_version NOT IN ('legacy-unknown', 'unavailable')
        AND cloud_consent_policy_version NOT IN ('none', 'legacy-unknown', 'unavailable')
      )
      OR
      (
        cloud_transfer_mode = 'EXTERNAL_MEMO_CONTENT'
        AND route IN ('CLOUD', 'HYBRID')
        AND cloud_outcome IN (
          'SUCCESS',
          'CONSENT_REQUIRED',
          'UNAVAILABLE',
          'TIMEOUT',
          'RETRY_EXHAUSTED',
          'PROVIDER_ERROR',
          'INVALID_RESPONSE',
          'UNEXPECTED_FAILURE'
        )
        AND cloud_gateway_version NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_provider_id NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_model_version NOT IN ('legacy-unknown', 'unavailable')
        AND cloud_consent_policy_version NOT IN ('none', 'legacy-unknown', 'unavailable')
      )
    );
