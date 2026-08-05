# Data model

This is a logical model for the first implementation. Exact SQL belongs in Flyway migrations after the open decisions are resolved.

## Core principles

- Raw memo identity and revision history are preserved.
- Analysis is attached to one exact revision.
- Proposed values and confirmed values are separate.
- Derived records retain provenance and can be undone as an application unit.
- Graph DTOs are projected from these records rather than stored as the only truth.

## Core entities

### users

```text
id UUID PK
created_at
updated_at
```

### user_settings

```text
user_id UUID PK/FK
time_zone VARCHAR
default_due_time TIME NULL
default_reminder_policy JSONB NULL
cloud_analysis_consent BOOLEAN
settings_version BIGINT
```

### memos

Stable identity and lifecycle.

```text
id UUID PK
owner_id UUID FK
current_revision INTEGER
status ACTIVE | TRASHED
pinned BOOLEAN
created_at
updated_at
deleted_at NULL
version BIGINT
```

### memo_revisions

Immutable source snapshots.

```text
memo_id UUID FK
revision INTEGER
content TEXT
content_hash VARCHAR
created_at
created_by UUID
PRIMARY KEY (memo_id, revision)
```

### analysis_runs

One execution of mock, deterministic, local-model, or cloud analysis.

```text
id UUID PK
owner_id UUID FK
memo_id UUID
memo_revision INTEGER
route MOCK | LOCAL | CLOUD | HYBRID
status QUEUED | RUNNING | REVIEW_REQUIRED | FAILED | STALE | APPLIED | REJECTED
schema_version VARCHAR
analyzer_version VARCHAR
embedding_model_version VARCHAR NULL
provider_model_version VARCHAR NULL
ambiguity_reasons JSONB
elapsed_ms INTEGER NULL
failure_code VARCHAR NULL
created_at
completed_at NULL
```

An analysis is stale when `memo_revision != memos.current_revision`.

### analysis_proposals

Validated but not yet canonical candidate data.

```text
id UUID PK
analysis_run_id UUID FK UNIQUE
proposal_json JSONB
proposal_hash VARCHAR
created_at
```

### analysis_applications

Groups all changes produced by one user confirmation.

```text
id UUID PK
owner_id UUID FK
proposal_id UUID FK
memo_id UUID FK
memo_revision INTEGER
idempotency_key VARCHAR
status APPLIED | UNDONE
selection_json JSONB
applied_at
undone_at NULL
UNIQUE (owner_id, idempotency_key)
```

### memo_items

Confirmed semantic facets extracted from a memo revision. A memo may have multiple items.

```text
id UUID PK
owner_id UUID FK
memo_id UUID FK
memo_revision INTEGER
application_id UUID FK
kind TASK | EVENT | INFORMATION | IDEA | RECORD
title VARCHAR
source_start INTEGER NULL
source_end INTEGER NULL
created_at
archived_at NULL
```

MVP should limit one proposal to at most three automatically extracted items.

### task_details

```text
memo_item_id UUID PK/FK
status TODO | DONE | CANCELLED
due_at_utc TIMESTAMPTZ NULL
date_surface_text VARCHAR NULL
date_precision EXACT_TIME | DATE_ONLY | RELATIVE_EXACT | APPROXIMATE | UNKNOWN
source_time_zone VARCHAR NULL
time_was_explicit BOOLEAN
completed_at NULL
```

`OVERDUE` is derived when status is TODO and due time/date policy places it before now.

### event_details

```text
memo_item_id UUID PK/FK
status SCHEDULED | CANCELLED
starts_at_utc TIMESTAMPTZ NULL
ends_at_utc TIMESTAMPTZ NULL
date_surface_text VARCHAR NULL
date_precision ...
source_time_zone VARCHAR NULL
```

`PAST` is a derived view state.

## Taxonomy

### tags

```text
id UUID PK
owner_id UUID FK
canonical_name VARCHAR
normalized_name VARCHAR
state PROVISIONAL | ACTIVE | MERGED | ARCHIVED
merged_into_tag_id UUID NULL
created_at
updated_at
version BIGINT
UNIQUE (owner_id, normalized_name)
```

### tag_aliases

```text
id UUID PK
owner_id UUID FK
tag_id UUID FK
alias VARCHAR
normalized_alias VARCHAR
source USER | ANALYSIS | IMPORT
created_at
UNIQUE (owner_id, normalized_alias)
```

An alias collision that could refer to two concepts becomes a review conflict rather than silently reassigning.

### item_tags

```text
memo_item_id UUID FK
tag_id UUID FK
application_id UUID FK
source USER | LOCAL | CLOUD
score DOUBLE PRECISION NULL
confirmed_at
PRIMARY KEY (memo_item_id, tag_id)
```

Only confirmed `item_tags` influence canonical tag centroids.

### tag_relations

Supports richer graph evolution than a single parent id.

```text
id UUID PK
owner_id UUID FK
source_tag_id UUID FK
target_tag_id UUID FK
relation_type PARENT_OF | RELATED_TO
application_id UUID NULL
created_at
```

### item_relations

```text
id UUID PK
owner_id UUID FK
source_item_id UUID FK
target_item_id UUID FK
relation_type RELATED_TO | CONTINUES | DEPENDS_ON | REFERENCES
application_id UUID FK
created_at
```

## Embeddings

### embeddings

```text
id UUID PK
owner_id UUID FK
target_type MEMO_REVISION | TAG_CENTROID
target_id UUID
target_revision INTEGER NULL
model_id VARCHAR
model_version VARCHAR
dimension INTEGER
quantization VARCHAR
vector VECTOR/BYTEA
confirmed_member_count INTEGER NULL
created_at
UNIQUE (...target identity..., model_version)
```

If pgvector is not included in the first migration, keep the repository interface and add storage in a later migration.

## Reminders and asynchronous work(P1)

### reminders

```text
id UUID PK
owner_id UUID FK
memo_item_id UUID FK
scheduled_at_utc TIMESTAMPTZ
status PENDING | CLAIMED | SENT | FAILED | CANCELLED
attempt_count INTEGER
deduplication_key VARCHAR UNIQUE
last_error_code VARCHAR NULL
sent_at NULL
```

### push_subscriptions

Stores one user's browser push endpoints and required encrypted subscription data. Treat endpoint data as sensitive.

### outbox_events / background_jobs

```text
id UUID PK
owner_id UUID NULL
job_type VARCHAR
payload JSONB
status PENDING | RUNNING | SUCCEEDED | FAILED
available_at
attempt_count
locked_at NULL
last_error_code NULL
created_at
```

## Graph projection(P1)

### graph_clusters

```text
id UUID PK
owner_id UUID FK
cluster_type PERIOD | TOPIC | STATUS
label VARCHAR
summary TEXT NULL
summary_model_version VARCHAR NULL
member_version_hash VARCHAR
created_at
updated_at
```

### graph_cluster_members

```text
cluster_id UUID FK
memo_id UUID FK
PRIMARY KEY (cluster_id, memo_id)
```

Clusters do not change canonical tags, items, or relations.

### graph_layouts

Optional stable positions for important nodes, scoped by graph view and layout version.

## Audit and undo

### audit_events

```text
id UUID PK
owner_id UUID FK
event_type VARCHAR
aggregate_type VARCHAR
aggregate_id UUID
application_id UUID NULL
metadata JSONB
created_at
```

Undo uses the application id to reverse exactly the derived records created by that application. It never rewrites the source revision.

## Index expectations

- `memos(owner_id, status, updated_at)`
- `memo_revisions(memo_id, revision)`
- `analysis_runs(memo_id, memo_revision, status)`
- unique idempotency key per owner
- normalized canonical tag and alias indexes
- task status/due time indexes
- lexical/trigram indexes on searchable title/content fields
- owner-scoped relation indexes
- vector indexes only after measured need and sufficient row count

