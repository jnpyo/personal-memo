-- V18 dispatch rows predate durable local-decision and model-contribution evidence. Preserve that
-- absence explicitly instead of reconstructing a fallback decision or claiming model influence.
ALTER TABLE analysis_run_dispatches
  ADD COLUMN local_decision_evidence_version VARCHAR(32) NOT NULL DEFAULT 'none',
  ADD COLUMN local_decision_evidence JSONB,
  ADD COLUMN fallback_policy_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0',
  ADD COLUMN fallback_reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN model_contribution_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RECORDED',
  ADD COLUMN model_changed_fields JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Keep the legacy defaults temporarily so the pre-V19 write path remains deployable during a
-- rolling application upgrade. The current write path must supply all six current evidence values.
ALTER TABLE analysis_run_dispatches
  ADD CONSTRAINT ck_analysis_run_dispatches_local_decision_evidence_version
    CHECK (local_decision_evidence_version IN ('none', 'local-decision-v1')),
  ADD CONSTRAINT ck_analysis_run_dispatches_fallback_policy_version
    CHECK (
      length(btrim(fallback_policy_version)) BETWEEN 1 AND 64
      AND fallback_policy_version NOT IN ('none', 'legacy-unknown', 'unavailable')
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_fallback_reason_codes
    CHECK (
      jsonb_typeof(fallback_reason_codes) = 'array'
      AND fallback_reason_codes <@ '[
        "DEFAULT_RECORD_FALLBACK",
        "UNPARSED_TEMPORAL_CUE",
        "UNRECOGNIZED_ACTION_CUE",
        "LOW_TYPE_MARGIN",
        "TAG_UNCERTAINTY",
        "DATE_UNCERTAINTY",
        "UNRESOLVED_REFERENCE",
        "INCOMPLETE_TASK",
        "MULTI_INTENT",
        "CANDIDATE_LIMIT",
        "LOCAL_CONFLICT"
      ]'::jsonb
      AND jsonb_array_length(fallback_reason_codes) =
          (CASE WHEN fallback_reason_codes ? 'DEFAULT_RECORD_FALLBACK' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'UNPARSED_TEMPORAL_CUE' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'UNRECOGNIZED_ACTION_CUE' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'LOW_TYPE_MARGIN' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'TAG_UNCERTAINTY' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'DATE_UNCERTAINTY' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'UNRESOLVED_REFERENCE' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'INCOMPLETE_TASK' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'MULTI_INTENT' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'CANDIDATE_LIMIT' THEN 1 ELSE 0 END)
        + (CASE WHEN fallback_reason_codes ? 'LOCAL_CONFLICT' THEN 1 ELSE 0 END)
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_model_contribution_status
    CHECK (
      model_contribution_status IN (
        'NOT_RECORDED',
        'PENDING',
        'ACCEPTED_CHANGED',
        'ACCEPTED_UNCHANGED',
        'LOCAL_FALLBACK'
      )
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_model_changed_fields
    CHECK (
      jsonb_typeof(model_changed_fields) = 'array'
      AND model_changed_fields <@ '[
        "SUGGESTED_TITLE",
        "TYPE_CANDIDATES",
        "DATE_CANDIDATES",
        "TAG_CANDIDATES",
        "ITEM_CANDIDATES",
        "RELATION_CANDIDATES",
        "AMBIGUITY_REASONS"
      ]'::jsonb
      AND jsonb_array_length(model_changed_fields) =
          (CASE WHEN model_changed_fields ? 'SUGGESTED_TITLE' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'TYPE_CANDIDATES' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'DATE_CANDIDATES' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'TAG_CANDIDATES' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'ITEM_CANDIDATES' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'RELATION_CANDIDATES' THEN 1 ELSE 0 END)
        + (CASE WHEN model_changed_fields ? 'AMBIGUITY_REASONS' THEN 1 ELSE 0 END)
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_local_decision_evidence_shape
    CHECK (
      local_decision_evidence IS NULL
      OR (
        jsonb_typeof(local_decision_evidence) = 'object'
        AND octet_length(local_decision_evidence::text) BETWEEN 2 AND 16384
        AND local_decision_evidence ?& ARRAY[
          'version',
          'typeSummary',
          'temporalSummary',
          'taxonomySummary',
          'itemSummary',
          'relationCandidateCount'
        ]
        AND local_decision_evidence - ARRAY[
          'version',
          'typeSummary',
          'temporalSummary',
          'taxonomySummary',
          'itemSummary',
          'relationCandidateCount'
        ] = '{}'::jsonb
        AND local_decision_evidence ->> 'version' = 'local-decision-v1'
        AND jsonb_typeof(local_decision_evidence -> 'typeSummary') = 'object'
        AND (local_decision_evidence -> 'typeSummary') ?& ARRAY[
          'candidateCount', 'leader', 'leaderScore', 'runnerUpScore', 'margin'
        ]
        AND (local_decision_evidence -> 'typeSummary') - ARRAY[
          'candidateCount', 'leader', 'leaderScore', 'runnerUpScore', 'margin'
        ] = '{}'::jsonb
        AND jsonb_typeof(local_decision_evidence #> '{typeSummary,candidateCount}') = 'number'
        AND (local_decision_evidence #>> '{typeSummary,candidateCount}')::numeric
              BETWEEN 1 AND 5
        AND mod(
              (local_decision_evidence #>> '{typeSummary,candidateCount}')::numeric,
              1
            ) = 0
        AND local_decision_evidence #>> '{typeSummary,leader}' IN (
          'TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD', 'UNKNOWN'
        )
        AND jsonb_typeof(local_decision_evidence #> '{typeSummary,leaderScore}') = 'number'
        AND (local_decision_evidence #>> '{typeSummary,leaderScore}')::numeric BETWEEN 0 AND 1
        AND (
          (
            (local_decision_evidence #>> '{typeSummary,candidateCount}')::integer = 1
            AND jsonb_typeof(local_decision_evidence #> '{typeSummary,runnerUpScore}') = 'null'
            AND jsonb_typeof(local_decision_evidence #> '{typeSummary,margin}') = 'null'
          )
          OR
          (
            (local_decision_evidence #>> '{typeSummary,candidateCount}')::integer > 1
            AND jsonb_typeof(local_decision_evidence #> '{typeSummary,runnerUpScore}') = 'number'
            AND jsonb_typeof(local_decision_evidence #> '{typeSummary,margin}') = 'number'
            AND (local_decision_evidence #>> '{typeSummary,runnerUpScore}')::numeric
                  BETWEEN 0 AND 1
            AND (local_decision_evidence #>> '{typeSummary,leaderScore}')::numeric
                  >= (local_decision_evidence #>> '{typeSummary,runnerUpScore}')::numeric
            AND (local_decision_evidence #>> '{typeSummary,margin}')::numeric =
                  (local_decision_evidence #>> '{typeSummary,leaderScore}')::numeric
                  - (local_decision_evidence #>> '{typeSummary,runnerUpScore}')::numeric
          )
        )
        AND jsonb_typeof(local_decision_evidence -> 'temporalSummary') = 'object'
        AND (local_decision_evidence -> 'temporalSummary') ?& ARRAY[
          'candidateCount', 'preciseCount', 'impreciseCount', 'explicitTimeCount'
        ]
        AND (local_decision_evidence -> 'temporalSummary') - ARRAY[
          'candidateCount', 'preciseCount', 'impreciseCount', 'explicitTimeCount'
        ] = '{}'::jsonb
        AND jsonb_typeof(local_decision_evidence #> '{temporalSummary,candidateCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{temporalSummary,preciseCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{temporalSummary,impreciseCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{temporalSummary,explicitTimeCount}') = 'number'
        AND (local_decision_evidence #>> '{temporalSummary,candidateCount}')::numeric
              BETWEEN 0 AND 5
        AND (local_decision_evidence #>> '{temporalSummary,preciseCount}')::numeric
              BETWEEN 0 AND 5
        AND (local_decision_evidence #>> '{temporalSummary,impreciseCount}')::numeric
              BETWEEN 0 AND 5
        AND (local_decision_evidence #>> '{temporalSummary,explicitTimeCount}')::numeric
              BETWEEN 0 AND 5
        AND mod((local_decision_evidence #>> '{temporalSummary,candidateCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{temporalSummary,preciseCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{temporalSummary,impreciseCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{temporalSummary,explicitTimeCount}')::numeric, 1) = 0
        AND (local_decision_evidence #>> '{temporalSummary,preciseCount}')::integer
              + (local_decision_evidence #>> '{temporalSummary,impreciseCount}')::integer
              = (local_decision_evidence #>> '{temporalSummary,candidateCount}')::integer
        AND (local_decision_evidence #>> '{temporalSummary,explicitTimeCount}')::integer
              <= (local_decision_evidence #>> '{temporalSummary,preciseCount}')::integer
        AND jsonb_typeof(local_decision_evidence -> 'taxonomySummary') = 'object'
        AND (local_decision_evidence -> 'taxonomySummary') ?& ARRAY[
          'candidateCount', 'newProposalCount', 'strongestScore'
        ]
        AND (local_decision_evidence -> 'taxonomySummary') - ARRAY[
          'candidateCount', 'newProposalCount', 'strongestScore'
        ] = '{}'::jsonb
        AND jsonb_typeof(local_decision_evidence #> '{taxonomySummary,candidateCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{taxonomySummary,newProposalCount}') = 'number'
        AND (local_decision_evidence #>> '{taxonomySummary,candidateCount}')::numeric
              BETWEEN 0 AND 10
        AND (local_decision_evidence #>> '{taxonomySummary,newProposalCount}')::numeric
              BETWEEN 0 AND 10
        AND mod((local_decision_evidence #>> '{taxonomySummary,candidateCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{taxonomySummary,newProposalCount}')::numeric, 1) = 0
        AND (local_decision_evidence #>> '{taxonomySummary,newProposalCount}')::integer
              <= (local_decision_evidence #>> '{taxonomySummary,candidateCount}')::integer
        AND (
          (
            (local_decision_evidence #>> '{taxonomySummary,candidateCount}')::integer = 0
            AND jsonb_typeof(local_decision_evidence #> '{taxonomySummary,strongestScore}') = 'null'
          )
          OR
          (
            (local_decision_evidence #>> '{taxonomySummary,candidateCount}')::integer > 0
            AND jsonb_typeof(local_decision_evidence #> '{taxonomySummary,strongestScore}') = 'number'
            AND (local_decision_evidence #>> '{taxonomySummary,strongestScore}')::numeric
                  BETWEEN 0 AND 1
          )
        )
        AND jsonb_typeof(local_decision_evidence -> 'itemSummary') = 'object'
        AND (local_decision_evidence -> 'itemSummary') ?& ARRAY[
          'candidateCount', 'taskCount', 'verbPresentCount', 'referentPresentCount',
          'dueBindingCount'
        ]
        AND (local_decision_evidence -> 'itemSummary') - ARRAY[
          'candidateCount', 'taskCount', 'verbPresentCount', 'referentPresentCount',
          'dueBindingCount'
        ] = '{}'::jsonb
        AND jsonb_typeof(local_decision_evidence #> '{itemSummary,candidateCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{itemSummary,taskCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{itemSummary,verbPresentCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{itemSummary,referentPresentCount}') = 'number'
        AND jsonb_typeof(local_decision_evidence #> '{itemSummary,dueBindingCount}') = 'number'
        AND (local_decision_evidence #>> '{itemSummary,candidateCount}')::numeric BETWEEN 0 AND 3
        AND (local_decision_evidence #>> '{itemSummary,taskCount}')::numeric BETWEEN 0 AND 3
        AND (local_decision_evidence #>> '{itemSummary,verbPresentCount}')::numeric BETWEEN 0 AND 3
        AND (local_decision_evidence #>> '{itemSummary,referentPresentCount}')::numeric BETWEEN 0 AND 3
        AND (local_decision_evidence #>> '{itemSummary,dueBindingCount}')::numeric BETWEEN 0 AND 3
        AND mod((local_decision_evidence #>> '{itemSummary,candidateCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{itemSummary,taskCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{itemSummary,verbPresentCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{itemSummary,referentPresentCount}')::numeric, 1) = 0
        AND mod((local_decision_evidence #>> '{itemSummary,dueBindingCount}')::numeric, 1) = 0
        AND (local_decision_evidence #>> '{itemSummary,taskCount}')::integer
              <= (local_decision_evidence #>> '{itemSummary,candidateCount}')::integer
        AND (local_decision_evidence #>> '{itemSummary,verbPresentCount}')::integer
              <= (local_decision_evidence #>> '{itemSummary,taskCount}')::integer
        AND (local_decision_evidence #>> '{itemSummary,referentPresentCount}')::integer
              <= (local_decision_evidence #>> '{itemSummary,taskCount}')::integer
        AND (local_decision_evidence #>> '{itemSummary,dueBindingCount}')::integer
              <= (local_decision_evidence #>> '{itemSummary,taskCount}')::integer
        AND jsonb_typeof(local_decision_evidence -> 'relationCandidateCount') = 'number'
        AND (local_decision_evidence ->> 'relationCandidateCount')::numeric BETWEEN 0 AND 10
        AND mod((local_decision_evidence ->> 'relationCandidateCount')::numeric, 1) = 0
      )
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_fallback_evidence_coherence
    CHECK (
      (
        local_decision_evidence_version = 'none'
        AND local_decision_evidence IS NULL
        AND fallback_policy_version = 'legacy-v0'
        AND fallback_reason_codes = '[]'::jsonb
        AND model_contribution_status = 'NOT_RECORDED'
        AND model_changed_fields = '[]'::jsonb
      )
      OR
      (
        local_decision_evidence_version = 'local-decision-v1'
        AND local_decision_evidence IS NOT NULL
        AND local_decision_evidence ->> 'version' = local_decision_evidence_version
        AND fallback_policy_version = 'model-fallback-v1'
        AND jsonb_array_length(fallback_reason_codes) BETWEEN 1 AND 11
        AND (
          (
            state IN ('PREPARED', 'RUNNING')
            AND model_contribution_status = 'PENDING'
            AND model_changed_fields = '[]'::jsonb
          )
          OR
          (
            state = 'FINALIZED'
            AND model_contribution_status IN (
              'ACCEPTED_CHANGED', 'ACCEPTED_UNCHANGED', 'LOCAL_FALLBACK'
            )
            AND (
              (
                model_contribution_status = 'ACCEPTED_CHANGED'
                AND jsonb_array_length(model_changed_fields) BETWEEN 1 AND 7
              )
              OR
              (
                model_contribution_status IN ('ACCEPTED_UNCHANGED', 'LOCAL_FALLBACK')
                AND model_changed_fields = '[]'::jsonb
              )
            )
          )
        )
      )
    );

-- A localhost model receives memo content on the same machine. It is not a no-network path, and it
-- is not an external transfer requiring cloud consent.
ALTER TABLE analysis_runs
  DROP CONSTRAINT ck_analysis_runs_cloud_transfer_mode,
  DROP CONSTRAINT ck_analysis_runs_cloud_evidence_coherence,
  DROP CONSTRAINT ck_analysis_runs_cloud_execution_snapshot_coherence;

ALTER TABLE analysis_runs
  ADD CONSTRAINT ck_analysis_runs_cloud_transfer_mode
    CHECK (
      cloud_transfer_mode IN (
        'NOT_REQUIRED',
        'LEGACY_UNKNOWN',
        'DESCRIPTOR_UNAVAILABLE',
        'NO_NETWORK',
        'LOCAL_MACHINE_MEMO_CONTENT',
        'EXTERNAL_MEMO_CONTENT'
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
        cloud_transfer_mode IN ('NO_NETWORK', 'LOCAL_MACHINE_MEMO_CONTENT')
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
            cloud_transfer_mode IN ('NO_NETWORK', 'LOCAL_MACHINE_MEMO_CONTENT')
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
            cloud_transfer_mode IN ('NO_NETWORK', 'LOCAL_MACHINE_MEMO_CONTENT')
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
    );
