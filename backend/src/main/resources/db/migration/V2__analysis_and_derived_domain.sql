CREATE TABLE analysis_runs (
  id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), memo_id UUID NOT NULL REFERENCES memos(id),
  memo_revision INTEGER NOT NULL, route VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL,
  schema_version VARCHAR(16) NOT NULL, analyzer_version VARCHAR(64) NOT NULL,
  ambiguity_reasons JSONB NOT NULL DEFAULT '[]', created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ
);
CREATE TABLE analysis_proposals (
  id UUID PRIMARY KEY, analysis_run_id UUID NOT NULL UNIQUE REFERENCES analysis_runs(id), proposal_json JSONB NOT NULL,
  proposal_hash VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE analysis_applications (
  id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), proposal_id UUID NOT NULL REFERENCES analysis_proposals(id),
  memo_id UUID NOT NULL REFERENCES memos(id), memo_revision INTEGER NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK(status IN ('APPLIED','UNDONE')), selection_json JSONB NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL, undone_at TIMESTAMPTZ, UNIQUE(owner_id,idempotency_key)
);
CREATE TABLE tags (
  id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), canonical_name VARCHAR(100) NOT NULL,
  normalized_name VARCHAR(100) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0, UNIQUE(owner_id,normalized_name)
);
CREATE TABLE tag_aliases (
  id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), tag_id UUID NOT NULL REFERENCES tags(id),
  alias VARCHAR(100) NOT NULL, normalized_alias VARCHAR(100) NOT NULL, source VARCHAR(16) NOT NULL, created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(owner_id,normalized_alias)
);
CREATE TABLE memo_items (
  id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), memo_id UUID NOT NULL REFERENCES memos(id),
  memo_revision INTEGER NOT NULL, application_id UUID NOT NULL REFERENCES analysis_applications(id), kind VARCHAR(24) NOT NULL,
  title VARCHAR(200) NOT NULL, created_at TIMESTAMPTZ NOT NULL, archived_at TIMESTAMPTZ
);
CREATE TABLE task_details (
  memo_item_id UUID PRIMARY KEY REFERENCES memo_items(id), status VARCHAR(16) NOT NULL CHECK(status IN ('TODO','DONE','CANCELLED')),
  due_at_utc TIMESTAMPTZ, date_surface_text VARCHAR(100), date_precision VARCHAR(24), source_time_zone VARCHAR(64),
  time_was_explicit BOOLEAN NOT NULL DEFAULT FALSE, completed_at TIMESTAMPTZ
);
CREATE TABLE item_tags (
  memo_item_id UUID NOT NULL REFERENCES memo_items(id), tag_id UUID NOT NULL REFERENCES tags(id),
  application_id UUID NOT NULL REFERENCES analysis_applications(id), source VARCHAR(16) NOT NULL, score DOUBLE PRECISION,
  confirmed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(memo_item_id,tag_id)
);
CREATE INDEX idx_analysis_memo_revision ON analysis_runs(memo_id,memo_revision,status);
CREATE INDEX idx_items_owner_created ON memo_items(owner_id,created_at DESC);
CREATE INDEX idx_tasks_status_due ON task_details(status,due_at_utc);

INSERT INTO tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at)
VALUES ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','운영체제','운영체제','ACTIVE',NOW(),NOW()),
       ('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','과제','과제','ACTIVE',NOW(),NOW());
INSERT INTO tag_aliases(id,owner_id,tag_id,alias,normalized_alias,source,created_at)
VALUES ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','OS','os','USER',NOW());

