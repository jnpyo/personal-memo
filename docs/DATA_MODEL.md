# Data model — authenticated deterministic-analysis MVP

이 문서는 현재 Flyway `V1`–`V10`이 만드는 PostgreSQL schema를 설명한다. SQL이 최종 source of truth이며, 후속 아이디어와 현재 table을 섞지 않는다. `V4`는 이전 구현에서 UTC instant로 저장했던 `DATE_ONLY` 값을 원래 local date 표현으로 안전하게 이관한다. `V5`는 하위 table에 명시적인 `owner_id`를 backfill하고 owner-aware composite foreign key로 부모와 자식의 소유권을 데이터베이스에서도 일치시킨다. `V6`는 각 raw revision에 client recorded time과 source IANA time zone을 추가한다. `V7`은 `analysis_runs`에 prompt·local model·embedding model·routing policy version을 추가하고, 비어 있던 기존 analyzer version과 새 version column을 `legacy-v0`으로 backfill해 분석 provenance를 보존한다. `V8`은 local/Google identity와 PostgreSQL-backed server session을 추가하되 기존 개발 owner와 데이터를 그대로 보존한다. `V9`는 legacy unclaimed owner를 제외한 사용자가 email·normalized email·display name을 모두 갖도록 database constraint를 추가한다. `V10`은 fresh private database의 최초 계정을 단 한 번만 만들 수 있는 provisioning gate를 추가한다.

## Invariants

- raw memo identity와 immutable revision은 AI-derived record와 독립적으로 보존한다.
- analysis run은 정확히 하나의 `memo_revision`을 참조한다.
- 날짜 해석은 analysis 요청 시점의 전역 값이 아니라 참조한 revision의 기록 시각과 시간대를 사용한다.
- proposal과 confirmed canonical record는 분리한다.
- 파생 record는 `analysis_application` provenance를 보유한다.
- 모든 사용자 데이터 read/write는 `owner_id` 범위 안에서 수행하며, owner-bound foreign key가 교차 owner 참조를 거절한다.
- client는 `owner_id`를 선택하지 못한다. 인증된 `users.id`가 server security context를 통해 owner가 된다.
- local credential과 Google identity는 한 internal user에 연결되는 login method일 뿐 domain data의 owner key가 아니다.
- Google identity는 `(provider, provider_subject)`로 유일하며 email 일치만으로 internal user를 연결하지 않는다.
- server session은 PostgreSQL에 저장하고 browser에는 opaque session id만 전달한다.
- graph는 canonical table의 projection이며 rendered node 위치를 원본으로 저장하지 않는다.
- `OVERDUE`는 column이나 task source status가 아니다.

## Identity and raw memo

### `initial_account_provisioning`

```text
singleton BOOLEAN PK (always TRUE)
status AVAILABLE | CONSUMED
provisioned_user_id UUID NULL
method INTERACTIVE_CLI | PREEXISTING NULL
consumed_at TIMESTAMPTZ NULL
```

이 table은 계정이나 권한 목록이 아니라 fresh database의 일회성 운영 gate다. bootstrap
transaction이 singleton row를 `FOR UPDATE`로 잠근 뒤 claimed user가 없는지 다시 확인하고,
`users`·`user_settings`·`local_credentials` 생성과 같은 transaction에서 `CONSUMED`로 바꾼다.
동시 실행 중 하나만 성공하며, 재시작·논리 백업·서버 이전 뒤에도 gate가 다시 열리지 않는다.
V10 적용 시 이미 `ACTIVE` 또는 `DISABLED` 사용자가 있으면 가장 오래된 internal UUID를 감사
metadata로 남기고 `PREEXISTING`으로 즉시 소비한다. `LEGACY_UNCLAIMED` 개발 owner는 이 판단에서
제외되고 새 계정에 합쳐지지 않는다. 의도적으로 foreign key를 두지 않아 향후 계정 삭제 정책도
bootstrap gate를 되살리지 못한다.

### `users`

```text
id UUID PK
primary_email VARCHAR(254) NULL
primary_email_normalized VARCHAR(254) NULL
display_name VARCHAR(80) NULL
status ACTIVE | DISABLED | LEGACY_UNCLAIMED
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
UNIQUE normalized email when present
```

`users.id`가 모든 login method와 canonical domain data가 공유하는 안정적인 internal identity다. 기존 개발 환경의 고정 UUID 사용자는 migration compatibility를 위해 email과 credential 없이 그대로 남으며, 신규 가입자가 그 데이터를 자동으로 가져가지 않는다. 신규 local/Google account는 email과 표시 이름을 가진다.

### `local_credentials`

```text
user_id UUID PK/FK -> users
password_hash VARCHAR(255)
failed_attempts INTEGER
locked_until TIMESTAMPTZ NULL
password_changed_at TIMESTAMPTZ
```

email lookup은 `users.primary_email_normalized`를 사용해 credential과 user email이 서로 어긋나는 중복 column을 두지 않는다. raw password는 저장하지 않는다. `password_hash`는 Spring Security delegating encoder가 만든 algorithm identifier 포함 one-way hash다. login 실패는 account 존재 여부를 노출하지 않는 공통 오류로 응답한다. 같은 계정에서 잘못된 비밀번호가 연속 5회 발생하면 `failed_attempts=5`와 현재 시각부터 15분 뒤의 `locked_until`을 저장한다. 잠금 중 추가 시도는 counter나 만료 시각을 연장하지 않는다. 잠금이 만료된 뒤 정상 로그인하면 `failed_attempts`와 `locked_until`을 초기화하며, 만료 후 다시 실패하면 새 실패 구간을 1부터 시작한다. 이 table의 계정 단위 잠금과 별도로 공개 배포에는 IP·edge rate limiting과 abuse 방어가 필요하다.

### `external_identities`

```text
id UUID PK
user_id UUID FK -> users
provider GOOGLE
provider_subject VARCHAR(255)
email_at_login VARCHAR(254)
email_verified BOOLEAN
linked_at TIMESTAMPTZ
last_login_at TIMESTAMPTZ
UNIQUE (provider, provider_subject)
UNIQUE (user_id, provider)
```

Google의 stable `sub` claim이 provider subject다. `email_at_login`은 verified profile snapshot이며 identity key나 자동 병합 key가 아니다. 기존 normalized email과 충돌하는 미연결 Google login은 account-link-required로 거절하고, 기존 session에서 시작한 명시적 link flow만 연결을 만든다. provider authorization code/access token/refresh token은 이 schema에 저장하지 않는다.

### `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`

Spring Session JDBC의 opaque browser session을 저장한다. table과 index는 framework auto-DDL이 아니라 V8 Flyway migration이 소유한다. session row에는 생성·최근 접근·만료 시각과 principal name이 있고, serialized security context 같은 attribute는 session primary key에 종속되어 session 삭제 시 함께 삭제된다.

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

휴지통 이동은 row를 삭제하지 않고 `status = TRASHED`와 `deleted_at`을 설정한다. 복원은 `status = ACTIVE`로 되돌리고 `deleted_at`을 비운다. raw revision과 이미 승인된 파생 record는 어느 동작에서도 삭제하지 않으며, task/graph projection은 활성 memo만 조회한다.

### `memo_revisions`

원문 snapshot은 append-only 방식으로 추가한다.

```text
memo_id UUID
owner_id UUID NOT NULL
revision INTEGER
content TEXT
content_hash VARCHAR(64)
created_at TIMESTAMPTZ
created_by UUID FK -> users
client_recorded_at TIMESTAMPTZ NOT NULL
source_time_zone VARCHAR(64) NOT NULL
PRIMARY KEY (memo_id, revision)
UNIQUE (memo_id, revision, owner_id)
```

`client_recorded_at`은 사용자가 해당 원문을 기록한 client 시각을 UTC instant로 보존하고, `source_time_zone`은 그때의 IANA zone을 별도로 보존한다. Create의 `clientCreatedAt`과 Update의 `clientUpdatedAt`이 같은 내부 의미로 매핑된다. V6 이전 row는 기존 `created_at`과 owner의 `user_settings.time_zone`으로 backfill한다.

content update는 새 row를 insert한 뒤 `memos.current_revision`을 증가시킨다. Update capture context는 API에서 둘 다 제공하거나 둘 다 생략하며, 생략 시 server now와 직전 revision의 zone을 사용한다. 과거 raw content나 capture context를 proposal JSON이나 derived title로 대체하지 않는다.

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
memo_id UUID
memo_revision INTEGER
route MOCK | LOCAL | CLOUD | HYBRID
status QUEUED | RUNNING | REVIEW_REQUIRED | POSTPONED | FAILED | STALE | APPLIED | REJECTED
schema_version VARCHAR(16)
analyzer_version VARCHAR(64)
prompt_version VARCHAR(64)
local_model_version VARCHAR(64)
embedding_model_version VARCHAR(64)
routing_policy_version VARCHAR(64)
ambiguity_reasons JSONB
created_at TIMESTAMPTZ
completed_at TIMESTAMPTZ NULL
FK (memo_id, owner_id) -> memos(id, owner_id)
FK (memo_id, memo_revision, owner_id) -> memo_revisions(memo_id, revision, owner_id)
```

현재 결정론적 analyzer는 run이 참조하는 revision의 `client_recorded_at`과 `source_time_zone`을 입력으로 사용한다. 서버가 소유하는 `analyzer_version`, `prompt_version`, `local_model_version`, `embedding_model_version`, `routing_policy_version`은 각각 비어 있지 않은 1–64자 값으로 run마다 저장된다. 현재 Fake 경로는 `fake-v3`, `none`, `none`, `none`, `field-policy-v1`을 사용한다. `routing_policy_version`은 점수 임계값과 구조적 신호 규칙의 버전을 보존하고, `ambiguity_reasons`는 cloud 처리 전 서버가 재구성한 최초 라우팅 원인을 보존한다. 모호성 gate가 local proposal로 충분하다고 판정하면 `LOCAL`, Fake cloud enrichment가 필요하면 `HYBRID`를 저장한다. `MOCK`·`CLOUD` 값은 후속 adapter와 이전 단계 호환을 위해 표현 가능하지만 현재 실행 경로에서는 사용하지 않는다. memo가 수정되면 현재 revision보다 오래된 미적용 run을 `STALE`로 표시하며 application 단계에서도 revision을 다시 검사한다.

### `analysis_proposals`

```text
id UUID PK
owner_id UUID NOT NULL
analysis_run_id UUID UNIQUE
proposal_json JSONB
proposal_hash VARCHAR(64)
created_at TIMESTAMPTZ
FK (analysis_run_id, owner_id) -> analysis_runs(id, owner_id)
```

proposal은 review input일 뿐 canonical domain data가 아니다. 직렬화한 proposal JSON은 최대 65,536 UTF-8 byte(64 KiB), 그 안의 `providerMetadata`는 최대 8,192 UTF-8 byte(8 KiB)다. `providerMetadata`에는 위 다섯 version과 0–100 범위의 정수 `toolCalls`가 필수이며, proposal이 주장하는 version은 서버가 해당 run에 저장하는 provenance와 정확히 일치해야 한다.

### `analysis_applications`

한 번의 사용자 승인을 하나의 reversible unit으로 묶는다.

```text
id UUID PK
owner_id UUID FK -> users
proposal_id UUID
memo_id UUID
memo_revision INTEGER
idempotency_key VARCHAR(128)
status APPLIED | UNDONE
selection_json JSONB
applied_at TIMESTAMPTZ
undone_at TIMESTAMPTZ NULL
UNIQUE (owner_id, idempotency_key)
FK (proposal_id, owner_id) -> analysis_proposals(id, owner_id)
FK (memo_id, owner_id) -> memos(id, owner_id)
FK (memo_id, memo_revision, owner_id) -> memo_revisions(memo_id, revision, owner_id)
```

`selection_json`은 model output 전체를 실행 명령으로 보관하는 필드가 아니라, 사용자가 실제로 승인한 selection의 audit/provenance다.

## Confirmed domain

### `memo_items`

승인된 semantic facet이다. 한 application에서 최대 3개를 생성한다.

```text
id UUID PK
owner_id UUID FK -> users
memo_id UUID
memo_revision INTEGER
application_id UUID
kind TASK | EVENT | INFORMATION | IDEA | RECORD
title VARCHAR(200)
created_at TIMESTAMPTZ
archived_at TIMESTAMPTZ NULL
FK (memo_id, owner_id) -> memos(id, owner_id)
FK (memo_id, memo_revision, owner_id) -> memo_revisions(memo_id, revision, owner_id)
FK (application_id, memo_id, memo_revision, owner_id)
  -> analysis_applications(id, memo_id, memo_revision, owner_id)
```

`kind`는 graph node metadata/filter/icon으로 사용하며 모든 memo를 연결하는 거대한 system node를 만들지 않는다.

### `task_details`

`TASK` memo item의 source state와 기한 표현을 저장한다.

```text
memo_item_id UUID PK
owner_id UUID NOT NULL
status TODO | DONE | CANCELLED
due_at_utc TIMESTAMPTZ NULL
due_local_date DATE NULL
date_surface_text VARCHAR(100) NULL
date_precision EXACT_TIME | DATE_ONLY | RELATIVE_EXACT | APPROXIMATE | UNKNOWN
source_time_zone VARCHAR(64) NULL
time_was_explicit BOOLEAN
completed_at TIMESTAMPTZ NULL
CHECK (due_at_utc IS NULL OR due_local_date IS NULL)
FK (memo_item_id, owner_id) -> memo_items(id, owner_id)
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
created_by_application_id UUID NULL
UNIQUE (owner_id, normalized_name)
FK (created_by_application_id, owner_id) -> analysis_applications(id, owner_id)
```

정규화한 이름은 owner 안에서 유일하다. 승인 중 새로 생성한 tag는 `created_by_application_id`로 provenance를 남겨 undo 때 기존 tag와 구분한다.

### `tag_aliases`

```text
id UUID PK
owner_id UUID FK -> users
tag_id UUID
alias VARCHAR(100)
normalized_alias VARCHAR(100)
source USER | ANALYSIS | IMPORT
created_at TIMESTAMPTZ
UNIQUE (owner_id, normalized_alias)
FK (tag_id, owner_id) -> tags(id, owner_id)
```

Milestone 1 seed data는 `OS`를 `운영체제` tag의 alias로 제공한다.

### `item_tags`

```text
memo_item_id UUID
owner_id UUID NOT NULL
tag_id UUID
application_id UUID
source USER | LOCAL | CLOUD
score DOUBLE PRECISION NULL
confirmed_at TIMESTAMPTZ
PRIMARY KEY (memo_item_id, tag_id)
FK (memo_item_id, application_id, owner_id)
  -> memo_items(id, application_id, owner_id)
FK (tag_id, owner_id) -> tags(id, owner_id)
```

review에서 승인된 link만 이 table에 들어간다. model-facing analyzer에는 insert/update 권한이 없다.

## Owner integrity in V5

공개 identity는 계속 전역 UUID primary key를 사용한다. V5가 추가한 `(identity, owner_id)` unique key는 API identity를 바꾸기 위한 것이 아니라 PostgreSQL composite foreign key의 target을 만들기 위한 것이다.

Composite target용 unique key는 `memos(id, owner_id)`, `memo_revisions(memo_id, revision, owner_id)`, `analysis_runs(id, owner_id)`, `analysis_proposals(id, owner_id)`, `analysis_applications(id, owner_id)`, `analysis_applications(id, memo_id, memo_revision, owner_id)`, `tags(id, owner_id)`, `memo_items(id, owner_id)`, `memo_items(id, application_id, owner_id)`다.

```text
memos(owner) ── memo_revisions(owner)
      └──────── analysis_runs(owner) ── analysis_proposals(owner)

analysis_applications(owner, memo, revision)
      └──────── memo_items(owner, same memo/revision/application)
                     ├── task_details(owner)
                     └── item_tags(owner, same application) ── tags(owner)
```

따라서 application, item, task, tag link를 만들 때 application owner와 memo/tag owner가 다르면 service query를 우회하더라도 database constraint가 write를 거절한다. `analysis_proposals`, `memo_revisions`, `task_details`, `item_tags`의 `owner_id`는 V5에서 canonical parent로부터 backfill한 뒤 `NOT NULL`로 강화했다.

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

- partial unique `users(primary_email_normalized)` when email is present
- unique `external_identities(provider, provider_subject)` and `(user_id, provider)`
- Spring Session lookup/expiry/principal indexes
- `memos(owner_id, status, updated_at DESC)`
- `analysis_runs(memo_id, memo_revision, status)`
- `memo_items(owner_id, created_at DESC)`
- `task_details(status, due_at_utc)`
- `task_details(status, due_local_date)`
- partial `tags(created_by_application_id)`
- `analysis_proposals(owner_id, created_at DESC)`
- `task_details(owner_id, status, due_at_utc, due_local_date)`
- `item_tags(owner_id, tag_id)`
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
- local email verification and password-reset token/delivery state
- login abuse/rate-limit audit state if the selected policy requires additional persistence
- MFA/passkey authenticators and account recovery codes

필요한 vertical slice가 시작될 때 파괴적 변경 없이 새 Flyway migration으로 추가한다.
