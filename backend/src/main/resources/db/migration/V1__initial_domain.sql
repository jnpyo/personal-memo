CREATE TABLE users (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_settings (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  time_zone VARCHAR(64) NOT NULL,
  cloud_analysis_consent BOOLEAN NOT NULL DEFAULT FALSE,
  settings_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE memos (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users(id),
  current_revision INTEGER NOT NULL CHECK (current_revision > 0),
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','TRASHED')),
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE memo_revisions (
  memo_id UUID NOT NULL REFERENCES memos(id),
  revision INTEGER NOT NULL,
  content TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL REFERENCES users(id),
  PRIMARY KEY (memo_id, revision)
);

CREATE TABLE idempotency_records (
  owner_id UUID NOT NULL REFERENCES users(id),
  operation VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  response_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (owner_id, operation, idempotency_key)
);

CREATE INDEX idx_memos_owner_status_updated ON memos(owner_id, status, updated_at DESC);

INSERT INTO users(id, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000001', NOW(), NOW());
INSERT INTO user_settings(user_id, time_zone, cloud_analysis_consent) VALUES ('00000000-0000-0000-0000-000000000001', 'Asia/Seoul', FALSE);

