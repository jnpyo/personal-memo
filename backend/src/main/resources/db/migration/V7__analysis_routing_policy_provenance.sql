ALTER TABLE analysis_runs
  ADD COLUMN routing_policy_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0',
  ADD COLUMN prompt_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0',
  ADD COLUMN local_model_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0',
  ADD COLUMN embedding_model_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v0';

UPDATE analysis_runs
   SET analyzer_version = 'legacy-v0'
 WHERE length(btrim(analyzer_version)) = 0;

ALTER TABLE analysis_runs
  ADD CONSTRAINT ck_analysis_runs_analyzer_version
    CHECK (length(btrim(analyzer_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_prompt_version
    CHECK (length(btrim(prompt_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_local_model_version
    CHECK (length(btrim(local_model_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_embedding_model_version
    CHECK (length(btrim(embedding_model_version)) BETWEEN 1 AND 64),
  ADD CONSTRAINT ck_analysis_runs_routing_policy_version
    CHECK (length(btrim(routing_policy_version)) BETWEEN 1 AND 64);
