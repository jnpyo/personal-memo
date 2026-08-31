-- Preserve V14 evidence as historical truth while adding a durable, provider-call-only
-- preparation contract. Existing rows are deliberately not assigned a dispatch: none of them
-- was committed before its gateway call.
ALTER TABLE analysis_runs
  DROP CONSTRAINT ck_analysis_runs_cloud_outcome,
  DROP CONSTRAINT ck_analysis_runs_cloud_evidence_coherence,
  DROP CONSTRAINT ck_analysis_runs_cloud_execution_contract_version,
  DROP CONSTRAINT ck_analysis_runs_cloud_execution_snapshot_coherence;

ALTER TABLE analysis_runs
  ADD CONSTRAINT ck_analysis_runs_status
    CHECK (
      status IN (
        'QUEUED',
        'RUNNING',
        'REVIEW_REQUIRED',
        'POSTPONED',
        'FAILED',
        'STALE',
        'APPLIED',
        'REJECTED'
      )
    ),
  ADD CONSTRAINT ck_analysis_runs_cloud_outcome
    CHECK (
      cloud_outcome IN (
        'NOT_REQUIRED',
        'LEGACY_UNKNOWN',
        'PENDING',
        'SUCCESS',
        'CONSENT_REQUIRED',
        'UNAVAILABLE',
        'TIMEOUT',
        'RETRY_EXHAUSTED',
        'PROVIDER_ERROR',
        'INVALID_RESPONSE',
        'UNEXPECTED_FAILURE',
        'CANCELLED_STALE'
      )
    ),
  ADD CONSTRAINT ck_analysis_runs_cloud_execution_contract_version
    CHECK (cloud_execution_contract_version IN ('legacy-v0', 'snapshot-v1', 'durable-v1')),
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
          'PENDING',
          'SUCCESS',
          'UNAVAILABLE',
          'TIMEOUT',
          'RETRY_EXHAUSTED',
          'PROVIDER_ERROR',
          'INVALID_RESPONSE',
          'UNEXPECTED_FAILURE',
          'CANCELLED_STALE'
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
          'PENDING',
          'SUCCESS',
          'CONSENT_REQUIRED',
          'UNAVAILABLE',
          'TIMEOUT',
          'RETRY_EXHAUSTED',
          'PROVIDER_ERROR',
          'INVALID_RESPONSE',
          'UNEXPECTED_FAILURE',
          'CANCELLED_STALE'
        )
        AND cloud_gateway_version NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_provider_id NOT IN ('none', 'legacy-unknown', 'unavailable')
        AND cloud_model_version NOT IN ('legacy-unknown', 'unavailable')
        AND cloud_consent_policy_version NOT IN ('none', 'legacy-unknown', 'unavailable')
      )
    ),
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
            AND cloud_outcome IN (
              'SUCCESS',
              'UNAVAILABLE',
              'TIMEOUT',
              'RETRY_EXHAUSTED',
              'PROVIDER_ERROR',
              'INVALID_RESPONSE',
              'UNEXPECTED_FAILURE'
            )
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
      OR
      (
        cloud_execution_contract_version = 'durable-v1'
        AND (
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
              'PENDING',
              'SUCCESS',
              'UNAVAILABLE',
              'TIMEOUT',
              'RETRY_EXHAUSTED',
              'PROVIDER_ERROR',
              'INVALID_RESPONSE',
              'UNEXPECTED_FAILURE',
              'CANCELLED_STALE'
            )
            AND cloud_authorization_checked_at IS NOT NULL
            AND cloud_accepted_consent_granted_at IS NOT NULL
            AND cloud_accepted_consent_granted_at <= cloud_authorization_checked_at
            AND cloud_provider_request_token IS NOT NULL
          )
        )
      )
    ),
  ADD CONSTRAINT ck_analysis_runs_durable_outcome_version
    CHECK (
      cloud_outcome NOT IN ('PENDING', 'CANCELLED_STALE')
      OR cloud_execution_contract_version = 'durable-v1'
    ),
  ADD CONSTRAINT ck_analysis_runs_cancelled_stale_status
    CHECK (cloud_outcome <> 'CANCELLED_STALE' OR status = 'STALE'),
  ADD CONSTRAINT ck_analysis_runs_durable_lifecycle
    CHECK (
      cloud_execution_contract_version <> 'durable-v1'
      OR (
        (
          cloud_outcome = 'PENDING'
          AND status IN ('QUEUED', 'RUNNING', 'STALE')
          AND completed_at IS NULL
        )
        OR
        (
          cloud_outcome <> 'PENDING'
          AND status NOT IN ('QUEUED', 'RUNNING')
          AND completed_at IS NOT NULL
          AND completed_at >= created_at
        )
      )
    );

CREATE TABLE analysis_run_dispatches (
  analysis_run_id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  reserved_proposal_id UUID NOT NULL,
  idempotency_key_hash VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  validated_local_proposal TEXT,
  validated_local_proposal_hash VARCHAR(64) NOT NULL,
  executor_binding_id VARCHAR(69) NOT NULL,
  call_timeout_ms INTEGER NOT NULL,
  max_attempts INTEGER NOT NULL,
  deadline_at TIMESTAMPTZ NOT NULL,
  state VARCHAR(16) NOT NULL,
  fence_token BIGINT NOT NULL,
  last_attempt_started_at TIMESTAMPTZ,
  lease_expires_at TIMESTAMPTZ,
  prepared_at TIMESTAMPTZ NOT NULL,
  finalized_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_analysis_run_dispatches_owner_key
    UNIQUE (owner_id, idempotency_key_hash),
  CONSTRAINT uq_analysis_run_dispatches_reserved_proposal
    UNIQUE (reserved_proposal_id),
  CONSTRAINT fk_analysis_run_dispatch_run_owner
    FOREIGN KEY (analysis_run_id, owner_id) REFERENCES analysis_runs(id, owner_id),
  CONSTRAINT ck_analysis_run_dispatches_idempotency_key_hash
    CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_analysis_run_dispatches_request_hash
    CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_analysis_run_dispatches_local_proposal_hash
    CHECK (validated_local_proposal_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_analysis_run_dispatches_local_proposal
    CHECK (
      (
        state IN ('PREPARED', 'RUNNING')
        AND validated_local_proposal IS NOT NULL
        AND octet_length(validated_local_proposal) BETWEEN 2 AND 65536
        AND jsonb_typeof(validated_local_proposal::jsonb) = 'object'
      )
      OR
      (
        state = 'FINALIZED'
        AND validated_local_proposal IS NULL
      )
    ),
  CONSTRAINT ck_analysis_run_dispatches_executor_binding_id
    CHECK (executor_binding_id ~ '^cgb1_[0-9a-f]{64}$'),
  CONSTRAINT ck_analysis_run_dispatches_timeout
    CHECK (call_timeout_ms BETWEEN 1 AND 60000),
  CONSTRAINT ck_analysis_run_dispatches_max_attempts
    CHECK (max_attempts BETWEEN 1 AND 10),
  CONSTRAINT ck_analysis_run_dispatches_deadline
    CHECK (
      deadline_at >= prepared_at + call_timeout_ms * interval '1 millisecond'
    ),
  CONSTRAINT ck_analysis_run_dispatches_state
    CHECK (state IN ('PREPARED', 'RUNNING', 'FINALIZED')),
  CONSTRAINT ck_analysis_run_dispatches_fence
    CHECK (fence_token BETWEEN 0 AND max_attempts),
  CONSTRAINT ck_analysis_run_dispatches_lifecycle
    CHECK (
      (
        state = 'PREPARED'
        AND fence_token = 0
        AND last_attempt_started_at IS NULL
        AND lease_expires_at IS NULL
        AND finalized_at IS NULL
      )
      OR
      (
        state = 'RUNNING'
        AND fence_token > 0
        AND last_attempt_started_at IS NOT NULL
        AND last_attempt_started_at >= prepared_at
        AND last_attempt_started_at < deadline_at
        AND lease_expires_at IS NOT NULL
        AND lease_expires_at > last_attempt_started_at
        AND lease_expires_at <= deadline_at
        AND updated_at >= last_attempt_started_at
        AND finalized_at IS NULL
      )
      OR
      (
        state = 'FINALIZED'
        AND lease_expires_at IS NULL
        AND finalized_at IS NOT NULL
        AND finalized_at >= prepared_at
        AND updated_at >= finalized_at
        AND (
          (
            fence_token = 0
            AND last_attempt_started_at IS NULL
          )
          OR
          (
            fence_token > 0
            AND last_attempt_started_at IS NOT NULL
            AND last_attempt_started_at >= prepared_at
            AND last_attempt_started_at < deadline_at
            AND finalized_at >= last_attempt_started_at
          )
        )
      )
    ),
  CONSTRAINT ck_analysis_run_dispatches_updated_at
    CHECK (updated_at >= prepared_at)
);

CREATE INDEX idx_analysis_run_dispatches_recovery
  ON analysis_run_dispatches(
    state,
    lease_expires_at,
    deadline_at,
    prepared_at,
    analysis_run_id
  )
  WHERE state IN ('PREPARED', 'RUNNING');
