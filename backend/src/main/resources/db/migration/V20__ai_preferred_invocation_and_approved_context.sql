-- V20 keeps the semantic ambiguity decision separate from the policy that actually invokes a
-- model. Historical dispatches are not reclassified: they retain an explicit legacy invocation
-- tuple, while every new dispatch writes the versioned decision that was made before preparation.
ALTER TABLE analysis_run_dispatches
  ADD COLUMN invocation_policy_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0',
  ADD COLUMN invocation_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
  ADD COLUMN invocation_reason_code VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
  ADD COLUMN approved_correction_context TEXT,
  ADD COLUMN approved_correction_context_hash VARCHAR(64),
  ADD COLUMN approved_correction_context_version VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN approved_correction_context_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE analysis_run_dispatches
  ADD CONSTRAINT ck_analysis_run_dispatches_invocation_policy_version
    CHECK (invocation_policy_version IN ('legacy-v0', 'model-invocation-v1')),
  ADD CONSTRAINT ck_analysis_run_dispatches_invocation_mode
    CHECK (invocation_mode IN ('LEGACY_UNKNOWN', 'UNCERTAINTY_ONLY', 'AI_PREFERRED')),
  ADD CONSTRAINT ck_analysis_run_dispatches_invocation_reason
    CHECK (
      invocation_reason_code IN (
        'LEGACY_UNKNOWN', 'SEMANTIC_UNCERTAINTY', 'AI_PREFERRED_POLICY'
      )
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_invocation_evidence_coherence
    CHECK (
      (
        invocation_policy_version = 'legacy-v0'
        AND invocation_mode = 'LEGACY_UNKNOWN'
        AND invocation_reason_code = 'LEGACY_UNKNOWN'
      )
      OR
      (
        invocation_policy_version = 'model-invocation-v1'
        AND (
          (
            invocation_mode = 'UNCERTAINTY_ONLY'
            AND invocation_reason_code = 'SEMANTIC_UNCERTAINTY'
          )
          OR
          (
            invocation_mode = 'AI_PREFERRED'
            AND invocation_reason_code IN (
              'SEMANTIC_UNCERTAINTY', 'AI_PREFERRED_POLICY'
            )
          )
        )
      )
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_approved_context_version
    CHECK (
      approved_correction_context_version IN ('none', 'approved-type-anchor-k3-v1')
    ),
  ADD CONSTRAINT ck_analysis_run_dispatches_approved_context_coherence
    CHECK (
      (
        approved_correction_context_version = 'none'
        AND approved_correction_context_count = 0
        AND approved_correction_context IS NULL
        AND approved_correction_context_hash IS NULL
      )
      OR
      (
        approved_correction_context_version = 'approved-type-anchor-k3-v1'
        AND invocation_policy_version = 'model-invocation-v1'
        AND invocation_mode = 'AI_PREFERRED'
        AND approved_correction_context_count BETWEEN 0 AND 3
        AND approved_correction_context_hash IS NOT NULL
        AND approved_correction_context_hash ~ '^[0-9a-f]{64}$'
        AND (
          (
            state IN ('PREPARED', 'RUNNING')
            AND approved_correction_context IS NOT NULL
            AND octet_length(approved_correction_context) BETWEEN 40 AND 2048
            AND jsonb_typeof(approved_correction_context::jsonb) = 'object'
            AND coalesce(
              (approved_correction_context::jsonb ->> 'version')
                = approved_correction_context_version,
              FALSE
            )
            AND CASE
              WHEN jsonb_typeof(approved_correction_context::jsonb -> 'signals') = 'array'
                THEN jsonb_array_length(approved_correction_context::jsonb -> 'signals')
                     = approved_correction_context_count
              ELSE FALSE
            END
          )
          OR
          (
            state = 'FINALIZED'
            AND approved_correction_context IS NULL
          )
        )
      )
    );

-- PostgreSQL CHECK accepts UNKNOWN, so the V19 shape check alone did not reject a JSON null
-- version. Keep an explicit FALSE-on-null guard even though the current coherence check below also
-- compares the same value.
ALTER TABLE analysis_run_dispatches
  ADD CONSTRAINT ck_analysis_dispatch_local_decision_version_value
    CHECK (
      local_decision_evidence IS NULL
      OR coalesce(
        local_decision_evidence ->> 'version' = local_decision_evidence_version,
        FALSE
      )
    );

-- V19 required a semantic fallback reason on every current dispatch. AI_PREFERRED also dispatches
-- fully confident proposals, for which an empty semantic-reason set is the truthful value. The
-- new invocation tuple is therefore the evidence that authorizes an empty set; uncertainty-only
-- and rolling V19 writers still require at least one semantic fallback reason.
ALTER TABLE analysis_run_dispatches
  DROP CONSTRAINT ck_analysis_run_dispatches_fallback_evidence_coherence;

ALTER TABLE analysis_run_dispatches
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
        AND coalesce(
          local_decision_evidence ->> 'version' = local_decision_evidence_version,
          FALSE
        )
        AND fallback_policy_version = 'model-fallback-v1'
        AND (
          (
            invocation_policy_version = 'model-invocation-v1'
            AND invocation_mode = 'AI_PREFERRED'
            AND invocation_reason_code = 'AI_PREFERRED_POLICY'
            AND jsonb_array_length(fallback_reason_codes) BETWEEN 0 AND 11
          )
          OR
          (
            NOT (
              invocation_policy_version = 'model-invocation-v1'
              AND invocation_mode = 'AI_PREFERRED'
              AND invocation_reason_code = 'AI_PREFERRED_POLICY'
            )
            AND jsonb_array_length(fallback_reason_codes) BETWEEN 1 AND 11
          )
        )
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
