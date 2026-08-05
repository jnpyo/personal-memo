# Data model — implemented Milestone 1

이 문서는 현재 Flyway `V1`–`V4`가 만드는 PostgreSQL schema를 설명한다. SQL이 최종 source of truth이며, 후속 아이디어와 현재 table을 섞지 않는다. `V4`는 이전 구현에서 UTC instant로 저장했던 `DATE_ONLY` 값을 원래 local date 표현으로 안전하게 이관한다.

## Invariants

- raw memo identity와 immutable revision은 AI-derived record와 독립적으로 보존한다.
- analysis run은 정확히 하나의 `memo_revision`을 참조한다.
- proposal과 confirmed canonical record는 분리한다.
- 파생 record는 `analysis_application` provenance를 보유한다.
- 모든 사용자 데이터 read/write는 `owner_id` 범위 안에서 수행한다.
- graph는 canonical table의 projection이며 rendered node 위치를 원본으로 저장하지 않는다.
- `OVERDUE`는 column이나 task source status가 아니다.

## Identity and raw memo

### `users`

```text
id UUID PK
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
```

개발 환경에는 고정 UUID의 단일 사용자를 seed한다. schema와 query는 이후 인증 도입을 위해 처음부터 owner-aware하다.

### `user_settings`

```text
user_id UUID PK/FK -> users
time_zone VARCHAR(64)
cloud_analysis_consent BOOLEAN
settings_version BIGINT
```

### `memos`

안정적인 memo identity와 현재 revision pointer를 저장한다.

```text
id UUID PK
owner_id UUID FK -> users
current_revision INTEGER
status ACTIVE | TRASHED
pinned BOOLEAN
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
deleted_at TIMESTAMPTZ NULL
version BIGINT
```

### `memo_revisions`

원문 snapshot은 append-only 방식으로 추가한다.

```text
memo_id UUID FK -> memos
revision INTEGER
content TEXT
content_hash VARCHAR(64)
created_at TIMESTAMPTZ
created_by UUID FK -> users
PRIMARY KEY (memo_id, revision)
```

content update는 새 row를 insert한 뒤 `memos.current_revision`을 증가시킨다. 과거 raw content를 proposal JSON이나 derived title로 대체하지 않는다.

## Retry safety

### `idempotency_records`

```text
owner_id UUID FK -> users
operation VARCHAR(64)
idempotency_key VARCHAR(128)
request_hash VARCHAR(64)
resource_id UUID
response_json JSONB
created_at TIMESTAMPTZ
PRIMARY KEY (owner_id, operation, idempotency_key)
```

protected mutation과 idempotency record를 같은 database transaction에 쓴다. transaction-scoped advisory lock이 같은 owner/operation/key의 동시 요청을 직렬화한다. 저장된 `request_hash`가 다르면 key 재사용 충돌로 처리한다.

## Analysis lifecycle

### `analysis_runs`

```text
id UUID PK
owner_id UUID FK -> users
memo_id UUID FK -> memos
memo_revision INTEGER
route MOCK | LOCAL | CLOUD | HYBRID
status QUEUED | RUNNING | REVIEW_REQUIRED | POSTPONED | FAILED | STALE | APPLIED | REJECTED
schema_version VARCHAR(16)
analyzer_version VARCHAR(64)
ambiguity_reasons JSONB
created_at TIMESTAMPTZ
completed_at TIMESTAMPTZ NULL
```

Milestone 1은 `MOCK` route를 사용한다. memo가 수정되면 현재 revision보다 오래된 미적용 run을 `STALE`로 표시하며 application 단계에서도 revision을 다시 검사한다.

### `analysis_proposals`

```text
id UUID PK
analysis_run_id UUID FK -> analysis_runs UNIQUE
proposal_json JSONB
proposal_hash VARCHAR(64)
created_at TIMESTAMPTZ
```

proposal은 review input일 뿐 canonical domain data가 아니다.

### `analysis_applications`

한 번의 사용자 승인을 하나의 reversible unit으로 묶는다.

```text
id UUID PK
owner_id UUID FK -> users
proposal_id UUID FK -> analysis_proposals
memo_id UUID FK -> memos
memo_revision INTEGER
idempotency_key VARCHAR(128)
status APPLIED | UNDONE
selection_json JSONB
applied_at TIMESTAMPTZ
undone_at TIMESTAMPTZ NULL
UNIQUE (owner_id, idempotency_key)
```

`selection_json`은 model output 전체를 실행 명령으로 보관하는 필드가 아니라, 사용자가 실제로 승인한 selection의 audit/provenance다.

## Confirmed domain

### `memo_items`

승인된 semantic facet이다. 한 application에서 최대 3개를 생성한다.

```text
id UUID PK
owner_id UUID FK -> users
memo_id UUID FK -> memos
memo_revision INTEGER
application_id UUID FK -> analysis_applications
kind TASK | EVENT | INFORMATION | IDEA | RECORD
title VARCHAR(200)
created_at TIMESTAMPTZ
archived_at TIMESTAMPTZ NULL
```

`kind`는 graph node metadata/filter/icon으로 사용하며 모든 memo를 연결하는 거대한 system node를 만들지 않는다.

### `task_details`

`TASK` memo item의 source state와 기한 표현을 저장한다.

```text
memo_item_id UUID PK/FK -> memo_items
status TODO | DONE | CANCELLED
due_at_utc TIMESTAMPTZ NULL
due_local_date DATE NULL
date_surface_text VARCHAR(100) NULL
date_precision EXACT_TIME | DATE_ONLY | RELATIVE_EXACT | APPROXIMATE | UNKNOWN
source_time_zone VARCHAR(64) NULL
time_was_explicit BOOLEAN
completed_at TIMESTAMPTZ NULL
CHECK (due_at_utc IS NULL OR due_local_date IS NULL)
```

두 due column의 의미는 다음과 같다.

| Precision | Stored value | Derived overdue rule |
| --- | --- | --- |
| `EXACT_TIME` | offset timestamp를 UTC `due_at_utc`에 저장 | `TODO && due_at_utc < now()` |
| `DATE_ONLY` | 원래 달력 날짜를 `due_local_date`에 저장 | `TODO && due_local_date < today(source_time_zone)` |

따라서 날짜만 있는 `2026-11-25`를 임의의 UTC 자정 instant로 바꾸지 않는다. `OVERDUE`는 query/DTO에서 계산하고 `status`에는 저장하지 않는다.

## Taxonomy and links

### `tags`

```text
id UUID PK
owner_id UUID FK -> users
canonical_name VARCHAR(100)
normalized_name VARCHAR(100)
state VARCHAR(16)
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
version BIGINT
created_by_application_id UUID FK -> analysis_applications NULL
UNIQUE (owner_id, normalized_name)
```

정규화한 이름은 owner 안에서 유일하다. 승인 중 새로 생성한 tag는 `created_by_application_id`로 provenance를 남겨 undo 때 기존 tag와 구분한다.

### `tag_aliases`

```text
id UUID PK
owner_id UUID FK -> users
tag_id UUID FK -> tags
alias VARCHAR(100)
normalized_alias VARCHAR(100)
source USER | ANALYSIS | IMPORT
created_at TIMESTAMPTZ
UNIQUE (owner_id, normalized_alias)
```

Milestone 1 seed data는 `OS`를 `운영체제` tag의 alias로 제공한다.

### `item_tags`

```text
memo_item_id UUID FK -> memo_items
tag_id UUID FK -> tags
application_id UUID FK -> analysis_applications
source USER | LOCAL | CLOUD
score DOUBLE PRECISION NULL
confirmed_at TIMESTAMPTZ
PRIMARY KEY (memo_item_id, tag_id)
```

review에서 승인된 link만 이 table에 들어간다. model-facing analyzer에는 insert/update 권한이 없다.

## Apply and undo

Apply transaction은 다음 순서의 무결성을 한 단위로 보장한다.

```text
owner + current memo revision 재검사
→ proposal selection/domain 검증
→ analysis_application 생성
→ memo_items / task_details 생성
→ owner-scoped tag 확인 또는 confirmed tag 생성
→ item_tags 연결
→ analysis run APPLIED
```

검증 또는 write 하나가 실패하면 transaction 전체를 rollback한다.

Undo는 application provenance가 가리키는 `item_tags`, `task_details`, `memo_items`를 제거하고 application을 `UNDONE`으로 표시한다. 해당 application이 만든 tag도 다른 confirmed data가 참조하지 않을 때만 제거한다. `memos`와 `memo_revisions`는 수정하거나 삭제하지 않는다.

## Graph projection

현재 graph response는 다음 canonical data에서 bounded query로 만든다.

```text
memos + memo_items + task_details
                 │
                 └── item_tags ── tags
```

별도 graph JSON, 화면 좌표, Neo4j가 source of truth가 아니다. frontend React Flow layout은 표시 책임만 가진다.

## Current indexes

- `memos(owner_id, status, updated_at DESC)`
- `analysis_runs(memo_id, memo_revision, status)`
- `memo_items(owner_id, created_at DESC)`
- `task_details(status, due_at_utc)`
- `task_details(status, due_local_date)`
- partial `tags(created_by_application_id)`
- owner/operation/key primary key on idempotency records
- owner-scoped normalized tag and alias unique constraints

## Deferred schema

아래 영역은 현재 migration에 넣지 않았다.

- reminder와 Web Push subscription
- offline outbox/change feed
- embedding/pgvector storage
- automatic tag merge/split proposal
- graph cluster/compression/layout persistence
- event detail, rich item/tag relation, search index

필요한 vertical slice가 시작될 때 파괴적 변경 없이 새 Flyway migration으로 추가한다.
