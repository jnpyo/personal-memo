-- Preserve every V15 dispatch exactly as it was sent. Historical rows have no retrieval context;
-- a synthetic empty payload would change the request shape for the same provider token.
ALTER TABLE analysis_run_dispatches
  ADD COLUMN retrieval_context TEXT,
  ADD COLUMN retrieval_context_hash VARCHAR(64),
  ADD COLUMN retrieval_context_version VARCHAR(64) NOT NULL DEFAULT 'none',
  ADD COLUMN retrieval_context_candidate_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE analysis_run_dispatches
  ALTER COLUMN retrieval_context_version DROP DEFAULT,
  ALTER COLUMN retrieval_context_candidate_count DROP DEFAULT,
  ADD CONSTRAINT ck_analysis_run_dispatches_retrieval_context_version
    CHECK (retrieval_context_version IN ('none', 'tag-alias-exact-k8-v1')),
  ADD CONSTRAINT ck_analysis_run_dispatches_retrieval_context_coherence
    CHECK (
      (
        retrieval_context_version = 'none'
        AND retrieval_context_candidate_count = 0
        AND retrieval_context IS NULL
        AND retrieval_context_hash IS NULL
      )
      OR
      (
        retrieval_context_version = 'tag-alias-exact-k8-v1'
        AND retrieval_context_candidate_count BETWEEN 0 AND 8
        AND retrieval_context_hash IS NOT NULL
        AND retrieval_context_hash ~ '^[0-9a-f]{64}$'
        AND (
          (
            state IN ('PREPARED', 'RUNNING')
            AND retrieval_context IS NOT NULL
            AND octet_length(retrieval_context) BETWEEN 45 AND 16384
            AND jsonb_typeof(retrieval_context::jsonb) = 'object'
            AND coalesce(
              (retrieval_context::jsonb ->> 'version') = retrieval_context_version,
              FALSE
            )
            AND CASE
              WHEN jsonb_typeof(retrieval_context::jsonb -> 'candidates') = 'array'
                THEN jsonb_array_length(retrieval_context::jsonb -> 'candidates')
                     = retrieval_context_candidate_count
              ELSE FALSE
            END
          )
          OR
          (
            state = 'FINALIZED'
            AND retrieval_context IS NULL
          )
        )
      )
    );
