ALTER TABLE analysis_run_dispatches
  ADD COLUMN attempt_history_version VARCHAR(32) NOT NULL DEFAULT 'none';

ALTER TABLE analysis_run_dispatches
  ALTER COLUMN attempt_history_version DROP DEFAULT,
  ADD CONSTRAINT ck_analysis_run_dispatches_attempt_history_version
    CHECK (attempt_history_version IN ('none', 'gateway-attempt-v1')),
  ADD CONSTRAINT uq_analysis_run_dispatches_run_owner_history
    UNIQUE (analysis_run_id, owner_id, attempt_history_version);

CREATE TABLE analysis_run_dispatch_attempts (
  analysis_run_id UUID NOT NULL,
  owner_id UUID NOT NULL,
  attempt_history_version VARCHAR(32) NOT NULL,
  fence_token BIGINT NOT NULL,
  effective_timeout_ms INTEGER NOT NULL,
  attempt_state VARCHAR(16) NOT NULL,
  execution_state VARCHAR(16) NOT NULL,
  local_termination VARCHAR(32),
  result_state VARCHAR(16) NOT NULL,
  gateway_outcome VARCHAR(32),
  disposition VARCHAR(32) NOT NULL,
  duration_status VARCHAR(16) NOT NULL,
  duration_ms BIGINT,
  model_token_status VARCHAR(16) NOT NULL,
  model_input_tokens BIGINT,
  model_output_tokens BIGINT,
  model_total_tokens BIGINT,
  cost_status VARCHAR(16) NOT NULL,
  cost_amount NUMERIC(20, 8),
  cost_currency VARCHAR(3),
  claimed_at TIMESTAMPTZ NOT NULL,
  lease_expires_at TIMESTAMPTZ NOT NULL,
  observed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_analysis_run_dispatch_attempts
    PRIMARY KEY (analysis_run_id, fence_token),
  CONSTRAINT fk_analysis_run_dispatch_attempt_owner_history
    FOREIGN KEY (analysis_run_id, owner_id, attempt_history_version)
    REFERENCES analysis_run_dispatches(
      analysis_run_id,
      owner_id,
      attempt_history_version
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_history_version
    CHECK (attempt_history_version = 'gateway-attempt-v1'),
  CONSTRAINT ck_analysis_run_dispatch_attempt_fence
    CHECK (fence_token > 0),
  CONSTRAINT ck_analysis_run_dispatch_attempt_timeout
    CHECK (effective_timeout_ms BETWEEN 1 AND 60000),
  CONSTRAINT ck_analysis_run_dispatch_attempt_lease
    CHECK (lease_expires_at > claimed_at),
  CONSTRAINT ck_analysis_run_dispatch_attempt_state
    CHECK (attempt_state IN ('IN_FLIGHT', 'OBSERVED', 'SUPERSEDED')),
  CONSTRAINT ck_analysis_run_dispatch_attempt_execution
    CHECK (execution_state IN ('PENDING', 'NOT_STARTED', 'STARTED', 'UNKNOWN')),
  CONSTRAINT ck_analysis_run_dispatch_attempt_termination
    CHECK (
      local_termination IS NULL
      OR local_termination IN (
        'RESULT',
        'EXECUTOR_REJECTED',
        'TIMEOUT',
        'CALLER_INTERRUPTED',
        'UNEXPECTED_EXCEPTION',
        'PROCESS_LOST'
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_result_state
    CHECK (result_state IN ('PENDING', 'OBSERVED', 'UNKNOWN')),
  CONSTRAINT ck_analysis_run_dispatch_attempt_gateway_outcome
    CHECK (
      gateway_outcome IS NULL
      OR gateway_outcome IN (
        'SUCCESS',
        'UNAVAILABLE',
        'TIMEOUT',
        'RETRY_EXHAUSTED',
        'PROVIDER_ERROR',
        'UNEXPECTED_FAILURE'
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_disposition
    CHECK (
      disposition IN (
        'PENDING',
        'APPLIED_TO_RUN',
        'STALE_FINALIZE',
        'FENCED_OUT',
        'RECOVERY_PENDING',
        'SUPERSEDED'
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_duration
    CHECK (
      (
        duration_status = 'UNKNOWN'
        AND duration_ms IS NULL
      )
      OR
      (
        duration_status = 'MEASURED'
        AND duration_ms IS NOT NULL
        AND duration_ms >= 0
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_model_tokens
    CHECK (
      (
        model_token_status IN ('PENDING', 'UNKNOWN', 'NOT_APPLICABLE', 'NOT_REPORTED')
        AND model_input_tokens IS NULL
        AND model_output_tokens IS NULL
        AND model_total_tokens IS NULL
      )
      OR
      (
        model_token_status = 'REPORTED'
        AND model_input_tokens IS NOT NULL
        AND model_input_tokens BETWEEN 0 AND 1000000000
        AND model_output_tokens IS NOT NULL
        AND model_output_tokens BETWEEN 0 AND 1000000000
        AND model_total_tokens IS NOT NULL
        AND model_total_tokens BETWEEN 0 AND 1000000000
        AND model_total_tokens >= model_input_tokens
        AND model_total_tokens >= model_output_tokens
        AND model_total_tokens::NUMERIC
            >= model_input_tokens::NUMERIC + model_output_tokens::NUMERIC
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_cost
    CHECK (
      (
        cost_status IN ('PENDING', 'UNKNOWN', 'NOT_APPLICABLE', 'NOT_REPORTED')
        AND cost_amount IS NULL
        AND cost_currency IS NULL
      )
      OR
      (
        cost_status = 'REPORTED'
        AND cost_amount IS NOT NULL
        AND cost_amount >= 0
        AND cost_amount NOT IN (
          'NaN'::NUMERIC,
          'Infinity'::NUMERIC,
          '-Infinity'::NUMERIC
        )
        AND cost_currency IS NOT NULL
        AND cost_currency ~ '^[A-Z]{3}$'
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_result
    CHECK (
      coalesce(
        (
          local_termination IS NULL
          AND result_state = 'PENDING'
          AND gateway_outcome IS NULL
        )
        OR
        (
          local_termination = 'RESULT'
          AND result_state = 'OBSERVED'
          AND gateway_outcome IS NOT NULL
        )
        OR
        (
          local_termination IS NOT NULL
          AND local_termination <> 'RESULT'
          AND result_state = 'UNKNOWN'
          AND gateway_outcome IS NULL
        ),
        FALSE
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_lifecycle
    CHECK (
      coalesce(
        (
          attempt_state = 'IN_FLIGHT'
          AND disposition = 'PENDING'
          AND execution_state = 'PENDING'
          AND local_termination IS NULL
          AND observed_at IS NULL
        )
        OR
        (
          attempt_state = 'IN_FLIGHT'
          AND disposition = 'RECOVERY_PENDING'
          AND execution_state IN ('NOT_STARTED', 'STARTED', 'UNKNOWN')
          AND local_termination = 'CALLER_INTERRUPTED'
          AND observed_at IS NOT NULL
        )
        OR
        (
          attempt_state = 'OBSERVED'
          AND disposition IN ('APPLIED_TO_RUN', 'STALE_FINALIZE')
          AND execution_state IN ('NOT_STARTED', 'STARTED', 'UNKNOWN')
          AND local_termination IN (
            'RESULT',
            'EXECUTOR_REJECTED',
            'TIMEOUT',
            'UNEXPECTED_EXCEPTION'
          )
          AND observed_at IS NOT NULL
        )
        OR
        (
          attempt_state = 'SUPERSEDED'
          AND disposition = 'SUPERSEDED'
          AND execution_state IN ('NOT_STARTED', 'STARTED', 'UNKNOWN')
          AND local_termination IN ('CALLER_INTERRUPTED', 'PROCESS_LOST')
          AND (
            (
              local_termination = 'CALLER_INTERRUPTED'
              AND observed_at IS NOT NULL
            )
            OR
            (
              local_termination = 'PROCESS_LOST'
              AND observed_at IS NULL
            )
          )
        )
        OR
        (
          attempt_state = 'SUPERSEDED'
          AND disposition = 'FENCED_OUT'
          AND execution_state IN ('NOT_STARTED', 'STARTED', 'UNKNOWN')
          AND local_termination IN (
            'RESULT',
            'EXECUTOR_REJECTED',
            'TIMEOUT',
            'UNEXPECTED_EXCEPTION'
          )
          AND observed_at IS NOT NULL
        ),
        FALSE
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_execution_truth
    CHECK (
      coalesce(
        (
          execution_state = 'PENDING'
          AND local_termination IS NULL
        )
        OR
        (
          local_termination = 'RESULT'
          AND execution_state = 'STARTED'
        )
        OR
        (
          local_termination = 'EXECUTOR_REJECTED'
          AND execution_state = 'NOT_STARTED'
        )
        OR
        (
          local_termination IN ('TIMEOUT', 'CALLER_INTERRUPTED', 'UNEXPECTED_EXCEPTION')
          AND execution_state IN ('NOT_STARTED', 'STARTED', 'UNKNOWN')
        )
        OR
        (
          local_termination = 'PROCESS_LOST'
          AND execution_state = 'UNKNOWN'
        ),
        FALSE
      )
    ),
  CONSTRAINT ck_analysis_run_dispatch_attempt_evidence
    CHECK (
      coalesce(
        (
          local_termination IS NULL
          AND duration_status = 'UNKNOWN'
          AND model_token_status = 'PENDING'
          AND cost_status = 'PENDING'
        )
        OR
        (
          local_termination = 'PROCESS_LOST'
          AND duration_status = 'UNKNOWN'
          AND model_token_status = 'UNKNOWN'
          AND cost_status = 'UNKNOWN'
        )
        OR
        (
          local_termination = 'EXECUTOR_REJECTED'
          AND duration_status = 'MEASURED'
          AND model_token_status = 'NOT_APPLICABLE'
          AND cost_status = 'NOT_APPLICABLE'
        )
        OR
        (
          local_termination IN ('TIMEOUT', 'CALLER_INTERRUPTED', 'UNEXPECTED_EXCEPTION')
          AND duration_status = 'MEASURED'
          AND model_token_status IN ('UNKNOWN', 'NOT_APPLICABLE')
          AND cost_status IN ('UNKNOWN', 'NOT_APPLICABLE')
        )
        OR
        (
          local_termination = 'RESULT'
          AND duration_status = 'MEASURED'
          AND model_token_status IN (
            'UNKNOWN',
            'NOT_APPLICABLE',
            'NOT_REPORTED',
            'REPORTED'
          )
          AND cost_status IN (
            'UNKNOWN',
            'NOT_APPLICABLE',
            'NOT_REPORTED',
            'REPORTED'
          )
        ),
        FALSE
      )
    )
);

CREATE UNIQUE INDEX uq_analysis_run_dispatch_attempts_in_flight
  ON analysis_run_dispatch_attempts(analysis_run_id)
  WHERE attempt_state = 'IN_FLIGHT';
