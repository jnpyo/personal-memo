ALTER TABLE analysis_runs
  ADD COLUMN cloud_execution_contract_version VARCHAR(32),
  ADD COLUMN cloud_authorization_checked_at TIMESTAMPTZ,
  ADD COLUMN cloud_accepted_consent_granted_at TIMESTAMPTZ,
  ADD COLUMN cloud_provider_request_token VARCHAR(69);

-- V13 rows predate this execution snapshot. Preserve that fact rather than reconstructing an
-- authorization decision or request token that did not exist at call time.
UPDATE analysis_runs
   SET cloud_execution_contract_version = 'legacy-v0';

ALTER TABLE analysis_runs
  ALTER COLUMN cloud_execution_contract_version SET NOT NULL,
  ADD CONSTRAINT ck_analysis_runs_cloud_execution_contract_version
    CHECK (cloud_execution_contract_version IN ('legacy-v0', 'snapshot-v1')),
  ADD CONSTRAINT ck_analysis_runs_cloud_provider_request_token
    CHECK (
      cloud_provider_request_token IS NULL
      OR cloud_provider_request_token ~ '^pmr1_[0-9a-f]{64}$'
    ),
  ADD CONSTRAINT uq_analysis_runs_cloud_provider_request_token
    UNIQUE (cloud_provider_request_token),
  ADD CONSTRAINT ck_analysis_runs_cloud_execution_snapshot_coherence
    CHECK (
      (
        cloud_execution_contract_version = 'legacy-v0'
        AND cloud_authorization_checked_at IS NULL
        AND cloud_accepted_consent_granted_at IS NULL
        AND cloud_provider_request_token IS NULL
      )
      OR
      (
        cloud_execution_contract_version = 'snapshot-v1'
        AND (
          (
            cloud_transfer_mode IN ('NOT_REQUIRED', 'DESCRIPTOR_UNAVAILABLE')
            AND cloud_authorization_checked_at IS NULL
            AND cloud_accepted_consent_granted_at IS NULL
            AND cloud_provider_request_token IS NULL
          )
          OR
          (
            cloud_transfer_mode = 'NO_NETWORK'
            AND cloud_authorization_checked_at IS NULL
            AND cloud_accepted_consent_granted_at IS NULL
            AND cloud_provider_request_token IS NOT NULL
          )
          OR
          (
            cloud_transfer_mode = 'EXTERNAL_MEMO_CONTENT'
            AND cloud_outcome = 'CONSENT_REQUIRED'
            AND cloud_authorization_checked_at IS NOT NULL
            AND cloud_accepted_consent_granted_at IS NULL
            AND cloud_provider_request_token IS NULL
          )
          OR
          (
            cloud_transfer_mode = 'EXTERNAL_MEMO_CONTENT'
            AND cloud_outcome IN (
              'SUCCESS',
              'UNAVAILABLE',
              'TIMEOUT',
              'RETRY_EXHAUSTED',
              'PROVIDER_ERROR',
              'INVALID_RESPONSE',
              'UNEXPECTED_FAILURE'
            )
            AND cloud_authorization_checked_at IS NOT NULL
            AND cloud_accepted_consent_granted_at IS NOT NULL
            AND cloud_accepted_consent_granted_at <= cloud_authorization_checked_at
            AND cloud_provider_request_token IS NOT NULL
          )
        )
      )
    );
