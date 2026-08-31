# Data model — authenticated deterministic-analysis MVP

이 문서는 현재 source와 개인 배포의 Flyway `V1`–`V23` PostgreSQL schema를 설명한다. 개인 database는 2026-08-27 owner-authorized backup/restore rehearsal 뒤 V20에서 V22로 전환됐고, 후속 owner-authorized V23 migration/rebuild로 현재 V23이다. 개인 배포는 계속 `LOCAL_ONLY`이며 actual public activation과 real calendar-client 검증은 `NOT_AUTHORIZED`다. 이 문서는 후속 migration이나 canonical-data smoke를 승인하지 않는다. SQL이 최종 source of truth이며, 후속 아이디어와 현재 table을 섞지 않는다. `V4`는 이전 구현에서 UTC instant로 저장했던 `DATE_ONLY` 값을 원래 local date 표현으로 안전하게 이관한다. `V5`는 하위 table에 명시적인 `owner_id`를 backfill하고 owner-aware composite foreign key로 부모와 자식의 소유권을 데이터베이스에서도 일치시킨다. `V6`는 각 raw revision에 client recorded time과 source IANA time zone을 추가한다. `V7`은 `analysis_runs`에 prompt·local model·embedding model·routing policy version을 추가하고, 비어 있던 기존 analyzer version과 새 version column을 `legacy-v0`으로 backfill해 분석 provenance를 보존한다. `V8`은 local/Google identity와 PostgreSQL-backed server session을 추가하되 기존 개발 owner와 데이터를 그대로 보존한다. `V9`는 legacy unclaimed owner를 제외한 사용자가 email·normalized email·display name을 모두 갖도록 database constraint를 추가한다. `V10`은 fresh private database의 최초 계정을 단 한 번만 만들 수 있는 provisioning gate를 추가한다. `V11`은 owner별 proposal의 최신 application을 bounded read로 찾는 review-outcome 조회 인덱스를 추가하고, `V12`는 최신 `APPLIED` selection과 활성 memo item을 사용하는 graph projection에 맞춘 partial lookup index만 추가한다. `V13`은 cloud consent를 정확한 policy와 승인 시각에 고정하고 run에 server-owned cloud evidence를 추가한다. `V14`는 새 run에 호출 권한 확인 시각·실제로 수락한 grant 시각·결정론적 provider-request token을 일관된 실행 snapshot으로 저장하고, 과거 row는 증거를 추정하지 않은 `legacy-v0`로 보존한다. `V15`는 gateway 호출 전에 `durable-v1` run과 1:1 dispatch preparation을 commit하고 immutable binding, validated-local payload/hash, reserved proposal, idempotency evidence, deadline·lease·fence를 보존한다. `V16`은 같은 dispatch에 bounded tag context raw/hash/version/count를 pre-call snapshot으로 추가하고 finalization에서 raw만 scrub한다. 기존 V15 dispatch에는 context가 있었다고 추정하지 않고 `none`/`0`/null raw/null hash를 보존한다. `V17`은 새 dispatch를 `gateway-attempt-v1`로 versioning하고 fence별 owner-scoped attempt ledger를 추가하되, 기존 dispatch는 `none`으로 두고 과거 attempt row를 backfill하지 않는다. V14까지의 기존 row에는 호출 전 준비가 있었다고 추정해 dispatch를 backfill하지 않는다. `V18`은 사용자가 명시적으로 선택한 proposal relation을 application 소유의 item-scoped directed relation으로 저장하며, owner-aware source/application/target constraint와 application 단위 undo를 강제한다. `V19`는 dispatch에 raw-free deterministic decision projection, fallback reason, model-contribution status와 semantic changed-field evidence를 추가하고 `LOCAL_MACHINE_MEMO_CONTENT` transfer mode를 정직하게 허용한다. 기존 dispatch는 `none`/`legacy-v0`/`NOT_RECORDED`로 보존한다. `V20`은 semantic ambiguity와 실제 model invocation policy를 분리해 저장하고, personal overlay의 승인 교정에서 만든 최대 K=3 current-memo offset/type snapshot을 retry에 고정한 뒤 finalization에서 raw만 지운다. `V21`은 기존 EVENT를 backfill하지 않고 owner·kind가 고정된 canonical `event_details`를 추가한다. `V22`는 backfill 없이 owner가 명시적으로 고른 EVENT만 recipient feed/entry projection에 담고 digest-only bearer verifier와 cancellation tombstone을 추가한다. `V23`은 기존/new feed를 모두 local-only로 유지하고 explicit public consent policy/time을 feed row에 pin한다. 이 migration들은 일반 clickstream, retrieval corpus 또는 raw training table을 만들지 않는다.

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
- EVENT schedule은 TASK due와 분리된 명시적 사용자 승인 결과다. proposal v2의 날짜 배열 순서,
  proposal v3의 candidate order/score/suggestion, 또는 model output만으로 `event_details`를 만들지
  않으며 누락된 end/duration을 발명하지 않는다.
- external memo-content gateway consent는 owner의 boolean·정확한 policy version·grant timestamp가
  모두 일치하고 grant timestamp가 권한 확인 instant보다 늦지 않을 때만 유효하며,
  `NO_NETWORK`와 personal-only `LOCAL_MACHINE_MEMO_CONTENT` gateway에는 적용하지 않는다.
- request·browser·provider result·proposal metadata는 run의
  transfer/gateway/provider/model/policy/outcome evidence를 선택하지 못한다. 서버가
  구성한 descriptor와 application service가 그 값을 소유하고 V13 constraint가 조합을 제한한다.
- 내부 authorization/grant snapshot과 provider-request token도 server-owned이고 V14/V15
  constraint가 호출 여부·transfer mode·outcome과의 nullability 및 token 유일성을 제한한다.
- V15 dispatch는 run과 같은 owner의 1:1 row이며 caller가 선택할 수 없는 reserved proposal,
  idempotency/request hash, immutable executor binding, deadline·lease·fence를 보존한다.
- V16 retrieval context는 authenticated owner의 active tag/alias exact normalized equality 결과만
  사용한 bounded 내부 hint다. canonical owner/reference 유효성은 최종 validation이 다시 판정한다.
- V17 attempt row는 같은 owner/version의 dispatch와
  `(analysis_run_id, owner_id, attempt_history_version)` foreign key로 묶이고 claim fence마다
  하나만 존재한다. local termination, remote result state, duration evidence, model-token
  evidence, cost evidence를 서로 대체하거나 미확인 값을 0으로 추정하지 않는다.
- V19 local-decision evidence는 validated proposal의 bounded counts/types/scores와 enum fields만
  저장한다. memo body, prompt, response, user-selected title/item text와 identifier를 복제하지 않는다.
- V20 invocation evidence는 semantic route를 바꾸거나 ambiguity를 발명하지 않는다. 기본
  `UNCERTAINTY_ONLY`와 personal-only `AI_PREFERRED`를 별도로 기록한다.
- V20 approved-correction context는 같은 owner의 eligible latest `APPLIED` 교정에서 current memo에도
  exact-unique하게 나타나는 짧은 anchor만 찾되, 저장하는 raw snapshot에는 current memo의 UTF-16
  offset과 approved item kind만 둔다. historical memo body, selection, identifier는 복제하지 않는다.

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
cloud_analysis_consent_policy_version VARCHAR(64) NULL
cloud_analysis_consent_granted_at TIMESTAMPTZ NULL
settings_version BIGINT
```

V13은 과거 boolean-only `TRUE` 값을 모두 `FALSE`로 폐기한다. false consent는 policy와
timestamp가 모두 null이어야 하며, true consent는 비어 있지 않은 1–64자 policy version과
non-null grant timestamp를 함께 가져야 한다. 분석 서비스는 authenticated owner의 row에서
descriptor policy와 정확히 일치하는지를 읽는다. 다른 owner의 grant, policy mismatch, revoke는
권한이 아니며, 권한 확인 instant보다 미래인 `granted_at`도 호출을 허가하지 않는다.
현재 이 값을 부여·철회하는 HTTP API와 실제 external provider 설정은 없다.

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

`pinned`는 graph-home 우선순위를 위한 owner-owned memo metadata다. idempotent pin mutation은
authenticated owner의 memo row를 잠그고 `ACTIVE` 상태에서만 값을 바꾸며 `version`과
`updated_at`을 증가시킨다. 이 동작은 `memo_revisions`, analysis proposal, item/tag/task를
수정하지 않는다. Graph recency는 `memos.updated_at`이 아니라 현재 raw revision의
server-created 시각을 사용하므로 pin/unpin이나 restore가 원문 edit recency로 오인되지 않는다.

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
cloud_transfer_mode NOT_REQUIRED | LEGACY_UNKNOWN | DESCRIPTOR_UNAVAILABLE | NO_NETWORK | EXTERNAL_MEMO_CONTENT | LOCAL_MACHINE_MEMO_CONTENT
cloud_gateway_version VARCHAR(64)
cloud_provider_id VARCHAR(64)
cloud_model_version VARCHAR(64)
cloud_consent_policy_version VARCHAR(64)
cloud_outcome NOT_REQUIRED | LEGACY_UNKNOWN | PENDING | SUCCESS | CONSENT_REQUIRED | UNAVAILABLE | TIMEOUT | RETRY_EXHAUSTED | PROVIDER_ERROR | INVALID_RESPONSE | UNEXPECTED_FAILURE | CANCELLED_STALE
cloud_execution_contract_version legacy-v0 | snapshot-v1 | durable-v1
cloud_authorization_checked_at TIMESTAMPTZ NULL
cloud_accepted_consent_granted_at TIMESTAMPTZ NULL
cloud_provider_request_token VARCHAR(69) NULL
ambiguity_reasons JSONB
created_at TIMESTAMPTZ
completed_at TIMESTAMPTZ NULL
FK (memo_id, owner_id) -> memos(id, owner_id)
FK (memo_id, memo_revision, owner_id) -> memo_revisions(memo_id, revision, owner_id)
```

현재 결정론적 analyzer는 run이 참조하는 revision의 `client_recorded_at`과 `source_time_zone`을 입력으로 사용한다. 서버가 소유하는 `analyzer_version`, `prompt_version`, `local_model_version`, `embedding_model_version`, `routing_policy_version`은 각각 비어 있지 않은 1–64자 값으로 run마다 저장된다. 현재 Fake 경로는 `fake-v9`, `none`, `none`, `none`, `field-policy-v2`를 사용하고 proposal의 추가 metadata에 `korean-rules-v7`를 남긴다. analyzer/rules version은 날짜·유형·행동·참조·원문 item span, default-fallback coverage, guarded affirmative `접속하기` 및 명시적인 TASK due binding 추출을 식별한다. v7 date rules는 명시적인 `오늘|내일|모레 + 오전|오후 + 1–12시`와 optional minutes만 revision의 captured instant/source zone에서 `RELATIVE_EXACT`로 해석한다. 날짜 없는 `6시`는 `UNKNOWN`이며 today/PM을 추론해 정밀 due나 알람으로 승격하지 않는다. `routing_policy_version`은 이미 구조화된 proposal에서 점수 임계값과 routing signal을 재구성해 route로 매핑하는 gate를 식별한다. `ambiguity_reasons`는 model 처리 전 서버가 재구성한 최초 라우팅 원인을 보존한다. 모호성 gate가 local proposal로 충분하다고 판정하면 `LOCAL`, configured gateway enrichment가 필요하면 `HYBRID`를 저장한다. 기본 application의 gateway는 `NO_NETWORK` Fake이고 personal overlay만 pinned `LOCAL_MACHINE_MEMO_CONTENT` Ollama gateway를 사용한다. `MOCK`·`CLOUD` 값은 이전 단계 호환을 위해 표현 가능하지만 현재 실행 경로에서는 사용하지 않는다. memo가 수정되면 현재 revision보다 오래된 미적용 run을 `STALE`로 표시하며 application 단계에서도 revision을 다시 검사한다.

V13 cloud evidence는 route와 일관된 조합만 허용한다. clear `LOCAL`/`MOCK`에는
`NOT_REQUIRED`와 `none` 값이, 현재 Fake `HYBRID`에는 `NO_NETWORK`와 descriptor version 및
outcome이 저장된다. descriptor를 읽지 못한 새 `HYBRID` run은 `DESCRIPTOR_UNAVAILABLE` /
`UNEXPECTED_FAILURE`와 `unavailable` evidence를 사용한다. V13 이전 `CLOUD`/`HYBRID` row는
실제 전송 여부나 성공을 추측하지 않고 `LEGACY_UNKNOWN`으로 backfill한다. external mode의
`CONSENT_REQUIRED`는 gateway method를 호출하지 않았음을 나타낸다. typed failure, gateway 예외,
invalid enriched result도 validated local proposal과 함께 `HYBRID`/`REVIEW_REQUIRED`로 남고
canonical record를 만들지 않는다. provider error text는 저장하지 않는다.
V14의 `snapshot-v1` row는 LOCAL/descriptor 실패면 세 내부 실행 값이 모두 null이고,
실제 `NO_NETWORK` gateway 호출에는 token만, 동의가 거절된 external 경로에는 권한 확인 시각만,
허가되어 gateway를 호출한 external 경로에는 권한 확인 시각·수락한 grant 시각·token을 모두
저장한다. 수락 grant는 권한 확인 시각보다 늦을 수 없고 token은 `pmr1_`과 lowercase SHA-256
형식이며 전체 table에서 유일하다. 이 세 값은 proposal, provider metadata, HTTP DTO, 일반 log에
노출하지 않는다. 기존 row는 전부 `legacy-v0`와 null 세 값으로 backfill하여 과거 승인이나 호출을
소급 추정하지 않는다.

V15 `durable-v1`은 gateway 호출이 필요한 경우 `analysis_runs`를 `QUEUED` / `PENDING`으로,
`analysis_run_dispatches`를 `PREPARED`로 먼저 commit한다. claim transaction은 현재 gateway를 다시
bind하고 준비 시 저장한 immutable binding ID와 descriptor를 정확히 대조한 뒤 fence를 증가시키고
run/dispatch를 `RUNNING`으로 바꾼다. gateway는 고정된 bounded executor에서 database transaction
밖으로 호출된다. finalize transaction은 owner·memo 활성 상태·revision과 fence를 다시 확인하고
현재 revision이면 `REVIEW_REQUIRED`, 바뀌었거나 휴지통이면 `STALE`을 저장한다. 호출 전에 이미
stale이면 gateway 0-call로 `CANCELLED_STALE`을 남기며, 호출 뒤 revision이 바뀌면 `STALE` status와
실제 cloud outcome을 함께 보존한다.

V16은 local proposal의 tag candidate를 최대 10개, canonical-name/alias normalized term을 최대
20개까지만 사용한다. owner의 active tag/alias exact equality query가 돌려준 전체 결과에서 source별
UUID가 하나인지 해소한 뒤 deterministic ordering과 UUID deduplication으로 최대 K=8의 내부 context를
만든다. raw memo·related memo·fuzzy/vector/embedding retrieval은 사용하지 않는다. 이 context는
gateway hint일 뿐이고 final owner/reference validation을 대체하지 않는다.

`PENDING`은 `durable-v1`의 `QUEUED`/`RUNNING` 또는 아직 완료되지 않은 `STALE` run에만 허용되고
`completed_at`은 null이다. `CANCELLED_STALE`은 완료된 `STALE` run에만 허용된다. 다른 final outcome은
`QUEUED`/`RUNNING`과 함께 저장할 수 없고 `completed_at`이 필요하다. run과 dispatch 사이의 준비/완료
일관성, 최종 proposal 존재 여부 같은 cross-table invariant는 단일 table `CHECK`로 표현할 수 없어
application transaction과 restore 검증이 함께 강제한다.

공개 분석 POST는 내부 상태를 polling DTO로 노출하지 않고 최종 run까지 기다리는 동기 계약을
유지한다. 동일 key/body를 다시 호출하는 caller-driven recovery와 운영 프로필의 bounded background
recovery가 같은 deadline과 `max_attempts` 안에서 lease를 재claim한다. scheduler는 30초마다 최대
25건의 `PREPARED` 또는 lease가 만료된 `RUNNING` row를 owner-consistent dispatch/run/idempotency join으로
선택하고, 기존 owner+operation+raw-key advisory lock 아래 claim한다. live lease는 skip하므로 process
재시작 뒤에도 eligible row만 다음 주기에서 이어진다. transport는 exactly-once가 아니라
at-least-once이고 실제 provider는 같은 `pmr1_...` token을 멱등하게 처리해야 한다. V16 recovery는
retrieval을 다시 실행하지 않고 dispatch의 동일한 DB snapshot만 decode하므로 같은 token에 다른
context input을 붙이지 않는다. V17은 새 `gateway-attempt-v1` dispatch의 claim fence마다 내부
attempt row를 만들고, 종료가 로컬에서 관측된 경우 monotonic elapsed를 저장한다. gateway result는
`STARTED`, executor rejection은 확정적 `NOT_STARTED`다. submit 뒤 timeout·interruption·unexpected
termination은 시작 관측이 있으면 `STARTED`, 없으면 `UNKNOWN`이며 `NOT_STARTED`로 단정하지 않는다.
provider 결과를 확인하지 못한 termination과 process loss의 remote result는 `UNKNOWN`이다. local
termination을 관측한 현재 model-free Fake의 model-token/cost는 execution uncertainty와 무관하게
`NOT_APPLICABLE`/null이고, observation-free process loss는 `UNKNOWN`/null이다. 실제 model usage/cost
숫자 수집·집계와 related-memo/fuzzy/vector/embedding context는 없다.

Proposal schema v2는 `dateCandidates[].candidateId`와 nullable
`itemCandidates[].dueDateCandidateId`를 기존 `proposal_json` JSONB 안에 저장하고, run의 기존
`schema_version`에 `2`를 기록한다. schema v1 JSON과 hash는 수정하지 않으며 recovery와
`review-default-v3`의 v1 호환 projection에서 계속 읽는다. 관계형 table이나 canonical due
표현이 바뀌지 않으므로 이 계약 변경만을 위한 Flyway migration 또는 JSON backfill은 없다.
실제 due는 여전히 사용자가 승인한 `selection_json`과 그 application이 만든
`task_details`에만 canonical하게 반영된다.

Dark-compatible proposal schema v3는 같은 JSONB 안의 각 item에 bounded
`eventScheduleCandidates`와 nullable `suggestedEventScheduleCandidateId`를 추가할 수 있다. 각 EVENT
alternative는 proposal-local ID, explicit mode, start date-candidate ID, nullable end descriptor,
and score만 저장한다. End descriptor는 date-candidate ID와
`EXCLUSIVE_AT_VALUE|INCLUSIVE_THROUGH_VALUE` boundary를 갖고, source time zone이나 canonical row를
포함하지 않는다. 이 JSON은 여전히 untrusted proposal이며 V21 `event_details`를 만들지 않는다.
현재 Fake와 personal localhost adapter는 v2만 만들고 current domain gate는 non-null suggestion을
거절한다. 따라서 v3 contract preparation도 Flyway migration, JSON backfill, canonical schedule
write, personal DB change를 만들지 않는다.

GET/recovery의 schema negotiation은 저장 형식을 바꾸지 않는다. 헤더가 없거나 `1`이면 서버는
higher-version JSON의 응답 복사본에서 v3 EVENT fields, `dateCandidates[].candidateId`,
`itemCandidates[].dueDateCandidateId`를 제거하고 `schemaVersion`을 `1`로 내려 strict v1을 만든다.
`2` 요청은 stored v3의 EVENT fields만 제거해 strict v2로 만들고, `3` 요청은 저장된 supported
version을 그대로 유지한다. 과거 v1/v2는 상위 요청에도 합성 upgrade되지 않는다. 어떤 경로도
`proposal_json`, `proposal_hash`, `analysis_runs.schema_version`을 update하지 않는다.

### `analysis_run_dispatches`

Gateway 호출이 필요한 `durable-v1` run에만 존재하는 내부 1:1 preparation row다. 공개 DTO나
proposal metadata가 아니며 V14 이전 run에는 backfill하지 않는다.

```text
analysis_run_id UUID PK
owner_id UUID NOT NULL
reserved_proposal_id UUID UNIQUE NOT NULL
idempotency_key_hash VARCHAR(64) NOT NULL
request_hash VARCHAR(64) NOT NULL
validated_local_proposal TEXT NULL
validated_local_proposal_hash VARCHAR(64) NOT NULL
retrieval_context TEXT NULL
retrieval_context_hash VARCHAR(64) NULL
retrieval_context_version none | tag-alias-exact-k8-v1
retrieval_context_candidate_count INTEGER NOT NULL
executor_binding_id VARCHAR(69) NOT NULL
call_timeout_ms INTEGER NOT NULL
max_attempts INTEGER NOT NULL
deadline_at TIMESTAMPTZ NOT NULL
attempt_history_version none | gateway-attempt-v1
local_decision_evidence_version none | local-decision-v1
local_decision_evidence JSONB NULL
fallback_policy_version legacy-v0 | model-fallback-v1
fallback_reason_codes JSONB array
model_contribution_status NOT_RECORDED | PENDING | ACCEPTED_CHANGED | ACCEPTED_UNCHANGED | LOCAL_FALLBACK
model_changed_fields JSONB array
invocation_policy_version legacy-v0 | model-invocation-v1
invocation_mode LEGACY_UNKNOWN | UNCERTAINTY_ONLY | AI_PREFERRED
invocation_reason_code LEGACY_UNKNOWN | SEMANTIC_UNCERTAINTY | AI_PREFERRED_POLICY
approved_correction_context TEXT NULL
approved_correction_context_hash VARCHAR(64) NULL
approved_correction_context_version none | approved-type-anchor-k3-v1
approved_correction_context_count INTEGER NOT NULL
state PREPARED | RUNNING | FINALIZED
fence_token BIGINT NOT NULL
last_attempt_started_at TIMESTAMPTZ NULL
lease_expires_at TIMESTAMPTZ NULL
prepared_at TIMESTAMPTZ NOT NULL
finalized_at TIMESTAMPTZ NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (owner_id, idempotency_key_hash)
UNIQUE (analysis_run_id, owner_id, attempt_history_version)
FK (analysis_run_id, owner_id) -> analysis_runs(id, owner_id)
```

필수인 idempotency/request/validated-local hash와 present context hash는 lowercase SHA-256이고
binding ID는 `cgb1_` + lowercase SHA-256 형식이다. 준비 시 정확히 검증한 local proposal text와
hash를 보존하며, `PREPARED`는 attempt/lease 없이 fence 0이다.
`RUNNING`은 양수 fence, deadline보다 이른 attempt 시작, attempt보다 늦고 deadline을 넘지 않는
lease가 필요하다. `FINALIZED`는 lease를 비우고 final 시각을 남기며 준비 payload text를 null로
scrub하지만 hash와 reserved proposal ID는 보존한다. `updated_at`은 silent default 없이 각 write가
명시한다. deadline과 최대 attempt는 caller 및 운영 scheduler recovery 모두의 상한이다. 이 row는
개별 attempt history가 아니며, fence가 다른 늦은 attempt는 final 결과를 덮어쓸 수 없다. 과거
dispatch의 `attempt_history_version`은 `none`이고 attempt child row가 없다. 새 gateway dispatch는
반드시 `gateway-attempt-v1`을 명시한다.

V16 current context는 version `tag-alias-exact-k8-v1`, candidate count 0–8, lowercase SHA-256 hash를
필수로 갖는다. `PREPARED`/`RUNNING`에서는 45–16,384 UTF-8 byte의 JSON object raw가 있어야 하고
그 JSON의 version 및 candidate array length가 column과 일치해야 한다. application은 decode할 때
hash와 strict codec shape도 검증한다. `FINALIZED`에서는 raw가 null이어야 하지만 hash/version/count는
evidence로 남는다. 기존 V15 row의 legacy `none` version은 count 0과 null raw/hash만 허용한다.

V19 이전 dispatch는 `none`/null, `legacy-v0`/`[]`, `NOT_RECORDED`/`[]` 조합만 허용한다. 현재
dispatch는 strict `local-decision-v1` object와 `model-fallback-v1`을 준비한다. V20
`UNCERTAINTY_ONLY`와 rolling legacy writer에는 하나 이상의 allow-listed semantic reason이 필요하지만,
`AI_PREFERRED`의 clear route에는 reason이 없는 것이 정직하므로 0개를 허용한다. 호출 근거는 별도
`model-invocation-v1` tuple이 증명한다. `PREPARED`/`RUNNING` 동안 contribution은 `PENDING`이다.
`FINALIZED`는
`ACCEPTED_CHANGED`, `ACCEPTED_UNCHANGED`, `LOCAL_FALLBACK` 중 하나이고 실제 semantic field가
바뀐 첫 상태만 non-empty allow-listed changed-field array를 갖는다. evidence object는 16 KiB 이하의
strict aggregate shape이며 raw memo/prompt/response를 포함하지 않는다.

V20 이전 dispatch는 invocation tuple을 `legacy-v0`/`LEGACY_UNKNOWN`/`LEGACY_UNKNOWN`으로 보존하고
approved-correction context를 `none`/`0`/null raw/null hash로 둔다. 새 dispatch는
`model-invocation-v1`과 `UNCERTAINTY_ONLY` 또는 `AI_PREFERRED`를 기록한다. semantic route가
불완전하면 reason은 `SEMANTIC_UNCERTAINTY`이고, personal AI-preferred policy만 호출 근거인 clear
proposal은 `AI_PREFERRED_POLICY`다. 이 evidence는 public run/proposal shape를 바꾸지 않는다.

승인 교정 기능이 켜진 personal dispatch는 `approved-type-anchor-k3-v1`, count 0–3, lowercase SHA-256
hash를 필수로 갖는다. `PREPARED`/`RUNNING` raw JSON에는 locked target memo의 UTF-16
`startUtf16`/`endUtf16`와 `approvedKind`만 있고, historical raw memo, application/proposal/item ID,
selection JSON, title/tag/date/relation은 없다. claim은 locked current revision에서 anchor text를 다시
materialize하고 exact-unique/offset/Unicode/hash/version을 검증한다. retry는 이 snapshot을 재사용한다.
`FINALIZED`에서는 raw가 null이어야 하지만 hash/version/count는 남는다. eligible source selection의
Undo는 새 dispatch 후보에서 즉시 제외되며 이미 준비된 dispatch input은 retry 안정성을 위해 유지한다.
이 K=3 type hint는 vector/embedding/RAG corpus, background learner, automatic rule promotion,
fine-tuning 또는 LoRA가 아니다.

### `analysis_run_dispatch_attempts`

V17이 추가한 내부 gateway-attempt ledger다. 공개 API, proposal metadata, UI, 평가 report의 source가
아니며 provider 오류 text나 provider/model ID, provider-request token, raw memo, retrieval context를
저장하지 않는다.

```text
analysis_run_id UUID NOT NULL
owner_id UUID NOT NULL
attempt_history_version gateway-attempt-v1
fence_token BIGINT NOT NULL
effective_timeout_ms INTEGER NOT NULL
attempt_state IN_FLIGHT | OBSERVED | SUPERSEDED
execution_state PENDING | NOT_STARTED | STARTED | UNKNOWN
local_termination RESULT | EXECUTOR_REJECTED | TIMEOUT | CALLER_INTERRUPTED | UNEXPECTED_EXCEPTION | PROCESS_LOST | NULL
result_state PENDING | OBSERVED | UNKNOWN
gateway_outcome SUCCESS | UNAVAILABLE | TIMEOUT | RETRY_EXHAUSTED | PROVIDER_ERROR | UNEXPECTED_FAILURE | NULL
disposition PENDING | APPLIED_TO_RUN | STALE_FINALIZE | FENCED_OUT | RECOVERY_PENDING | SUPERSEDED
duration_status UNKNOWN | MEASURED
duration_ms BIGINT NULL
model_token_status PENDING | UNKNOWN | NOT_APPLICABLE | NOT_REPORTED | REPORTED
model_input_tokens BIGINT NULL
model_output_tokens BIGINT NULL
model_total_tokens BIGINT NULL
cost_status PENDING | UNKNOWN | NOT_APPLICABLE | NOT_REPORTED | REPORTED
cost_amount NUMERIC(20, 8) NULL
cost_currency VARCHAR(3) NULL
claimed_at TIMESTAMPTZ NOT NULL
lease_expires_at TIMESTAMPTZ NOT NULL
observed_at TIMESTAMPTZ NULL
updated_at TIMESTAMPTZ NOT NULL
PRIMARY KEY (analysis_run_id, fence_token)
FK (analysis_run_id, owner_id, attempt_history_version)
  -> analysis_run_dispatches(analysis_run_id, owner_id, attempt_history_version)
```

claim transaction은 fence row를 `IN_FLIGHT`/`PENDING`으로 만든다. application은 persisted
`max_attempts`보다 많은 row를 만들지 않으며 partial unique index가 run마다 `IN_FLIGHT` row를 최대
하나로 제한한다. 현재 fence의 observed result는 `APPLIED_TO_RUN` 또는 `STALE_FINALIZE`이고, 이미
권한을 잃은 fence의 늦은 observation은 `FENCED_OUT`이다. caller interruption은 local observation을
`RECOVERY_PENDING`으로 남기고, 다음 claim이 그 row를 `SUPERSEDED`로 바꾼다. observation 없이
lease를 잃은 process는 다음 claim에서 `PROCESS_LOST`/`SUPERSEDED`가 되며 remote result와 duration을
`UNKNOWN`/null로 남긴다.

`RESULT`는 execution `STARTED`, result state `OBSERVED`다. gateway가 typed `UNAVAILABLE`을 반환해도
이 규칙을 따르며 gateway outcome만 `UNAVAILABLE`이다. `EXECUTOR_REJECTED`는 확정적 `NOT_STARTED`이고
provider 실행 결과가 아니므로 `result_state=UNKNOWN`, `gateway_outcome=null`이다. submit 뒤
timeout·interrupt·unexpected local termination은 start flag가 관측되면 `STARTED`, 관측되지 않으면
`UNKNOWN`이며 후자를 `NOT_STARTED`로 기록하지 않는다. 이 local control-flow evidence와 provider
remote truth는 분리된다.

프로세스가 termination을 관측하면 `System.nanoTime` 기반 submit/wait elapsed를 non-negative
millisecond로 저장한다. timeout/interruption은 measured local duration과 unknown remote result를
함께 가질 수 있다. process loss는 model-token/cost도 `UNKNOWN`/null이다. locally observed current
Fake는 `NO_NETWORK`, model version `none`이므로 execution-start uncertainty와 무관하게
model-token/cost status가 `NOT_APPLICABLE`이고 모든 숫자 field는 null이다. 미래 real-model은 확정적
`NOT_STARTED`일 때만 `NOT_APPLICABLE`/null이고, execution 또는 remote completion이 불확실하면
`UNKNOWN`/null이다. observed result도 현재 gateway 계약이 usage/cost를 보고하지 않으므로
`NOT_REPORTED`/null이다. `REPORTED` branch는 각 token 값을 0–1,000,000,000으로 제한하고 total이
input+output 이상인지 확인하며, finite non-negative amount와 uppercase 3-letter currency의 미래
shape만 검증한다. 현재 runtime은 numeric usage/cost를 쓰지 않는다.

ledger row는 승인된 별도 purge 정책이 생기기 전까지 현재 run/dispatch data의 retention을 따른다.
V17은 임의 TTL, 공개 조회 API, 숫자 집계 또는 budget enforcement를 추가하지 않는다.

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

proposal은 review input일 뿐 canonical domain data가 아니다. 직렬화한 proposal JSON은 최대 65,536 UTF-8 byte(64 KiB), 그 안의 `providerMetadata`는 최대 8,192 UTF-8 byte(8 KiB)다. `providerMetadata`에는 위 다섯 version과 0–100 범위의 정수 `toolCalls`가 필수이며, proposal이 주장하는 version은 서버가 해당 run에 저장하는 provenance와 정확히 일치해야 한다. 모든 새 LOCAL·cloud SUCCESS·fallback proposal은 공통 allow-list canonicalizer가 required provenance/tool count와 bounded local field만 복사해 metadata를 다시 만든다. cloud success도 provider가 보낸 임의 field를 유지하지 못한다. `HYBRID` 결과에는 server가 cloud transfer/gateway/provider/model/policy/outcome, received routing policy/reasons, zero tool/mutation calls, resolved-field 목록을 덮어쓴다.

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

`selection_json`은 model output 전체를 실행 명령으로 보관하는 필드가 아니라, 사용자가 실제로 승인한 selection의 audit/provenance다. 관계 선택에는 proposal 배열 index, exact source proposal candidate identity, 실제 생성된 source item identity, target identity/type과 relation type을 서버가 잠긴 proposal에서 해소해 기록한다. proposal score는 immutable proposal에만 provenance로 남고 selection이나 canonical relation row에 복제하지 않는다. client는 target/type/score를 실행 권한으로 제출하지 않는다.

일정이 하나라도 있는 Apply는 top-level `selectionSchemaVersion: "2"`와 각 EVENT item의 strict
`eventSchedule`을 함께 저장한다. server는 그 schedule의 `timeZone`을 locked immutable memo
revision의 `source_time_zone`으로 canonicalize한다. 일정이 없는 legacy Apply는 version field를
요구하지 않고 기존 selection shape를 유지한다. 현재 review-outcome 비교 정책은 temporal candidate가
있는 v3 proposal이나 EVENT schedule이 있는 selection을 accuracy label로 분류하지 않고
`unclassifiable`로 보수적으로 남긴다.

V18 전 성공한 Apply의 retry compatibility를 위해 relation field와 item proposal identity가 모두 없는
request는 기존 request-hash JSON shape로 재투영한다. relation-aware request는 명시적인 hash version을
포함하므로 새 selection 의미가 과거 idempotency record와 우연히 같아지지 않는다.
V21도 기존 legacy/relation-aware hash projection을 수정하지 않는다. 일정이 있는 request만
`EVENT_SCHEDULE_V1` marker, canonicalized schedule, selection schema version을 포함한 새 hash material을
사용한다. 같은 idempotency key와 다른 schedule은 충돌하고, byte-equivalent retry는 기존 응답을
재생한다.

Owner-scoped review-outcome summary는 새 canonical data를 저장하지 않고 `analysis_proposals`,
`analysis_runs`, `analysis_applications`를 read-only로 결합한다. cohort는
`analysis_proposals.created_at`의 rolling half-open interval이며, proposal마다
`analysis_applications`를 `(applied_at DESC, id DESC)`로 정렬해 최신 row 하나를 고른다. 같은
proposal을 undo한 뒤 새 idempotency key로 다시 적용하면 새 application이 최신이 된다.

현재 lifecycle schema의 시간·이력 한계도 명시적으로 유지한다.

- `applied_at`과 `undone_at`은 application의 적용·되돌림 시각을 보존한다.
- `analysis_runs.status`는 현재 상태이며 reject/postpone transition timestamp나 append-only
  history가 아니다.
- 따라서 현재 `POSTPONED` 수는 알 수 있지만, 보류 뒤 적용·거절된 과거 보류 event 수는
  이 schema만으로 복원할 수 없다.
- idempotency record의 `created_at`은 retry 안전성을 위한 operational provenance이므로 review
  event analytics로 재해석하지 않는다.
- proposal/selection JSON에는 title 등 개인 내용이 있을 수 있으므로 summary service 안에서만
  비교하고 응답·일반 로그에는 raw JSON, memo text, domain identifier를 내보내지 않는다.

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
UNIQUE (id, owner_id, kind)
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

Apply DTO의 due `timeZone`은 호환성을 위해 유효한 IANA zone인지 검증하지만 canonical
`task_details.source_time_zone`을 선택하지는 못한다. application transaction은 잠근 immutable
memo revision의 `source_time_zone`으로 그 값을 덮어쓴 뒤 저장한다. `DATE_ONLY` overdue의
`today(source_time_zone)` 경계는 승인 기기의 현재 zone이 아니라 원문 capture context를
따른다.

### `event_details`

명시적으로 승인된 `EVENT` memo item의 canonical timed/all-day schedule을 TASK due와 별도로
저장한다. V21은 기존 title-only EVENT를 backfill하지 않는다.

```text
memo_item_id UUID PK
owner_id UUID NOT NULL
item_kind VARCHAR(24) NOT NULL DEFAULT EVENT, CHECK item_kind = EVENT
schedule_kind TIMED | ALL_DAY
start_at_utc TIMESTAMPTZ NULL
end_at_utc TIMESTAMPTZ NULL
start_local_date DATE NULL
end_local_date_exclusive DATE NULL
source_time_zone VARCHAR(64) NOT NULL
FK (memo_item_id, owner_id, item_kind) -> memo_items(id, owner_id, kind)
```

- `TIMED`는 whole-second offset ISO 8601 input을 instant로 변환해 `start_at_utc`와 optional
  later `end_at_utc`만 사용한다. Fractional-second input은 RFC 5545 표현으로 반올림하거나
  절삭하지 않고 canonical write 전에 거절한다.
- `ALL_DAY`는 ISO calendar `start_local_date`와 optional later
  `end_local_date_exclusive`만 사용한다. exclusive end를 inclusive date나 UTC midnight으로
  바꾸지 않는다.
- `coalesce(..., FALSE)`를 사용한 temporal-shape/range CHECK는 SQL `UNKNOWN`이 malformed row를
  통과시키지 못하게 한다. application domain validator는 mode/value/range와 IANA zone을 먼저
  검증한다.
- `source_time_zone`은 review client가 선택한 authority가 아니라 locked immutable memo revision의
  capture zone이다. TIMED start/end의 explicit offset은 해당 local date-time에서 이 zone이 허용하는
  offset 중 하나여야 한다. DST gap은 허용 offset이 없어 거절하고, overlap은 사용자가 명시한 두 valid
  offset 중 어느 것이든 보존한다.
- `(owner_id, schedule_kind, start_at_utc, start_local_date)` index는 bounded owner event read를
  지원한다. schedule 없는 EVENT에는 row가 없고 `GET /events`에도 나타나지 않는다.

### Authenticated iCalendar projection (no table)

Milestone 6B는 새 table이나 stored export를 만들지 않는다. 같은 owner/current/active/APPLIED
EVENT query를 read-only transaction에서 실행하고 현재 canonical row로부터 매 요청마다 결정론적
UTF-8 bytes를 만든다. 101개를 probe해 100개 초과를 거절하며 zero row는 204다.

```text
UID       = "pm-auth-v1-" + SHA-256(domain || owner UUID || memo item UUID) + fixed domain
DTSTAMP   = memo_items.created_at, UTC whole seconds
SEQUENCE  = 0
DTSTART   = event_details explicit start
DTEND     = event_details explicit end only
SUMMARY   = memo_items.title after RFC TEXT escaping
```

Owner/item UUID는 digest input일 뿐 bytes에 직접 나오지 않고 UID는 authorization이 아니다.
Source zone은 TIMED instant를 검증·확정한 canonical input이지만 export extension으로 공개하지 않는다.
Raw memo, memo revision, TASK/tag/relation, application/proposal/selection, AI evidence, feed identity와
token은 query projection에 들어가지 않는다. Preview/read/download는 어떤 table도 insert/update/delete
하지 않는다. 이 6B UID와 bytes는 아래 수신자 피드에서 재사용하지 않는다.

### Recipient calendar feeds (V22 projection + V23 public-consent source)

`calendar_feeds`는 한 owner가 한 수신자에게 발급한 독립적인 읽기 전용 projection identity다.

```text
id UUID PK
owner_id UUID NOT NULL
display_name VARCHAR(80) NOT NULL
disclosure_mode BUSY_ONLY | TITLE
token_verifier CHAR(64) NOT NULL
status ACTIVE | REVOKED
publication_scope LOCAL_ONLY | PUBLIC_HTTPS DEFAULT LOCAL_ONLY
public_consent_policy_version VARCHAR(64) NULL
public_consent_granted_at TIMESTAMPTZ NULL
version BIGINT NOT NULL CHECK (version > 0)
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
rotated_at TIMESTAMPTZ NOT NULL
revoked_at TIMESTAMPTZ NULL
UNIQUE (id, owner_id)
UNIQUE (token_verifier)
```

`token_verifier`는 canonical 43-character base64url secret 자체가 아니라
`SHA-256("calendar-feed-bearer-v1\\0" || secret)`의 lowercase hex다. Revoke는 row를 되돌릴 수 없는
`REVOKED`로 바꿔 조회 대상에서 제외하되 verifier digest는 재사용 방지를 위해 그대로 보존한다.
원문 secret·전체 URL은 이 table, 다른 domain table, idempotency response JSON 어디에도 저장하지
않는다.

V23은 모든 기존/신규 row를 `LOCAL_ONLY`와 null consent fields로 둔다. `PUBLIC_HTTPS`는 ACTIVE,
non-revoked feed에만 허용되며 lowercase version pattern과 server grant time이 모두 필요하다. Public
enable은 fresh verifier 교체와 이 세 field, `rotated_at`/`updated_at`, 한 번의 version 증가를 같은
transaction에서 commit한다. Revoke는 status 변경과 함께 scope를 LOCAL_ONLY로 되돌리고 consent
fields를 null로 지운다. Deployment mode/policy와 row scope/pin이 맞지 않으면 bearer read는 generic
404다. V23은 개인 database에도 적용됐지만 기존/new feed를 `LOCAL_ONLY`와 null consent
fields로 유지하며 backfill 공개 권한을 만들지 않는다. Actual public activation과 real calendar-client
검증은 `NOT_AUTHORIZED`다.

Application service는 owner row를 잠근 뒤 `REVOKED`까지 포함한 lifetime feed 수를 세어 owner당
100개로 제한한다. V22에서 도입된 이 경계는 현재 V23에도 feed delete나 capacity 회수 경로가
없으므로 101번째 create는
`422 CALENDAR_FEED_LIMIT_EXCEEDED`이고, 폐기된 row도 quota에서 빠지지 않는다.

`calendar_feed_entries`는 canonical EVENT와 분리된 수신자별 update/removal projection이다.

```text
id UUID PK
feed_id UUID NOT NULL
owner_id UUID NOT NULL
source_event_hash CHAR(64) NOT NULL
public_uid VARCHAR(96) NOT NULL
active_memo_item_id UUID NULL
active_owner_id UUID NULL
active_item_kind EVENT NULL
state ACTIVE | CANCELLED
sequence INTEGER NOT NULL
schedule_kind TIMED | ALL_DAY
start_at_utc TIMESTAMPTZ NULL
end_at_utc TIMESTAMPTZ NULL
start_local_date DATE NULL
end_local_date_exclusive DATE NULL
source_time_zone VARCHAR(64) NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
cancelled_at TIMESTAMPTZ NULL
FK (feed_id, owner_id) -> calendar_feeds(id, owner_id)
FK (active_memo_item_id, active_owner_id, active_item_kind)
  -> event_details(memo_item_id, owner_id, item_kind)
UNIQUE (feed_id, source_event_hash)
UNIQUE (public_uid)
```

ACTIVE는 같은 owner의 완전한 EVENT detail을 가리키고 CANCELLED는 active reference 세 개를 모두
null로 만든다. 두 state 모두 사용자가 승인한 마지막 temporal shape를 보존하지만 title snapshot은
저장하지 않는다. ACTIVE `TITLE`은 현재 eligible canonical title만 읽고, `BUSY_ONLY`는 fixed `Busy`,
CANCELLED는 SUMMARY 자체를 내보내지 않는다. TIMED/ALL_DAY shape와 later-end constraint는
`event_details`와 같은 `coalesce(..., FALSE)` 방식으로 fail closed한다.

Feed 생성과 명시적 add가 schedule snapshot을 만든다. 명시적 remove, memo edit/trash, application
undo는 source mutation transaction 안에서 먼저 entry를 CANCELLED로 바꾸고 sequence를 1 증가시킨다.
따라서 뒤의 EVENT detail/item delete가 public UID와 마지막 DTSTART를 지우지 않는다. Restore는
자동 공유하지 않으며, owner가 같은 eligible source를 다시 명시적으로 add할 때만 같은 entry/UID를
ACTIVE로 되돌리고 sequence를 다시 증가시킨다. Public GET/HEAD는 이 projection이나 canonical row를
절대 갱신하지 않고, ACTIVE source의 current/active/APPLIED/unarchived eligibility를 매번 재검사한다.
Feed lifetime entry 수는 cancellation tombstone을 포함해 100으로 제한한다.

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

### `memo_item_relations` (V18)

명시적으로 선택된 proposal relation을 application이 만든 source item에서 owner의 canonical MEMO 또는
TAG로 향하는 directed typed relation으로 보존한다. TAG target relation은 membership 의미의
`item_tags`와 합치지 않는다.

```text
application_id UUID NOT NULL
proposal_relation_index SMALLINT NOT NULL
owner_id UUID NOT NULL
source_memo_item_id UUID NOT NULL
target_type MEMO | TAG
target_memo_id UUID NULL
target_tag_id UUID NULL
relation_type RELATED_TO | CONTINUES | DEPENDS_ON | REFERENCES
confirmed_at TIMESTAMPTZ NOT NULL
PRIMARY KEY (application_id, proposal_relation_index)
CHECK (proposal_relation_index BETWEEN 0 AND 9)
CHECK (target type과 target UUID가 정확히 하나만 일치)
FK (application_id, owner_id) -> analysis_applications(id, owner_id)
FK (source_memo_item_id, application_id, owner_id)
  -> memo_items(id, application_id, owner_id)
FK (target_memo_id, owner_id) -> memos(id, owner_id)
FK (target_tag_id, owner_id) -> tags(id, owner_id)
```

proposal validator와 partial unique index는 같은 source candidate·target type/identity·relation type의
semantic duplicate를 거절한다. proposal array index는 application 안의 원래 제안 provenance를
유지하며 별도 relation UUID를 만들지 않는다. Apply는 target row를 owner/`ACTIVE` 조건으로 다시
잠근 뒤에만 insert하고, relation review label은 이 권한 검사를 대체하지 않는다.

## Owner integrity in V5, V18, and V21

공개 identity는 계속 전역 UUID primary key를 사용한다. V5가 추가한 `(identity, owner_id)` unique key는 API identity를 바꾸기 위한 것이 아니라 PostgreSQL composite foreign key의 target을 만들기 위한 것이다.

Composite target용 unique key는 `memos(id, owner_id)`, `memo_revisions(memo_id, revision, owner_id)`, `analysis_runs(id, owner_id)`, `analysis_proposals(id, owner_id)`, `analysis_applications(id, owner_id)`, `analysis_applications(id, memo_id, memo_revision, owner_id)`, `tags(id, owner_id)`, `memo_items(id, owner_id)`, `memo_items(id, application_id, owner_id)`, `memo_items(id, owner_id, kind)`다.

```text
memos(owner) ── memo_revisions(owner)
      └──────── analysis_runs(owner) ── analysis_proposals(owner)

analysis_applications(owner, memo, revision)
      └──────── memo_items(owner, same memo/revision/application)
                     ├── task_details(owner)
                     ├── event_details(owner, EVENT kind)
                     ├── item_tags(owner, same application) ── tags(owner)
                     └── memo_item_relations(owner, same application)
                               ├── target memos(owner)
                               └── target tags(owner)
```

따라서 application, item, task, event detail, tag link와 V18 relation을 만들 때 application owner와 source
item/target memo/tag owner가 다르면 service query를 우회하더라도 database constraint가 write를
거절한다. V21의 `item_kind='EVENT'` composite foreign key는 TASK/다른 kind에 event row를 붙이는
우회도 거절한다. `analysis_proposals`, `memo_revisions`, `task_details`, `item_tags`의 `owner_id`는 V5에서
canonical parent로부터 backfill한 뒤 `NOT NULL`로 강화했다.

## Apply and undo

Apply transaction은 다음 순서의 무결성을 한 단위로 보장한다.

```text
owner + current memo revision 재검사
→ proposal selection/domain 검증
→ selected relation index를 잠긴 proposal의 full candidate로 해소
→ relation source를 exact proposalCandidateId의 적용 item에 1:1 매핑
→ owner-scoped ACTIVE relation target을 종류/UUID 순으로 잠금
→ analysis_application 생성
→ memo_items / task_details / event_details 생성
→ owner-scoped tag 확인 또는 confirmed tag 생성
→ item_tags 연결
→ memo_item_relations 연결
→ analysis run APPLIED
```

검증 또는 write 하나가 실패하면 transaction 전체를 rollback한다.

Undo는 application provenance가 가리키는 `memo_item_relations`를 source item보다 먼저 제거한 뒤
`item_tags`, `event_details`, `task_details`, `memo_items`를 제거하고 application을 `UNDONE`으로 표시한다. 해당
application이 만든 tag도 다른 confirmed link나 relation target이 참조하지 않을 때만 제거한다.
relation target memo/tag, source `memos`와 `memo_revisions`는 수정하거나 삭제하지 않는다.

## Graph projection

현재 graph response는 다음 canonical data에서 bounded query로 만든다. 대표 label/type은 최신 `APPLIED` application의 승인 selection에서 가져오고, task state와 overdue는 같은 memo의 모든 활성 task를 집계한다. 따라서 여러 child item이 있어도 임의 UUID가 대표 metadata를 결정하지 않는다. memo 후보는 pin, overdue, TODO, nearest due, 현재 raw revision 시각, UUID 순으로 정렬하고, 선택된 memo 집합의 tag 후보는 연결 memo 수와 안정적인 이름/UUID 순으로 정렬한다. node 예산이 찰 때 memo만으로 response가 채워지는 것을 막기 위해 `limit > 1`에서는 최소 1개이자 전체의 1/5인 슬롯을 tag에 우선 예약한다. 실제 tag가 예약보다 적으면 남은 슬롯을 memo로 채운 뒤 final memo set에서 tag 순위·생략 여부를 다시 계산한다. initial memo 밖에만 tag가 있으면 unexamined 관계를 complete로 표시하지 않고 underfill과 `truncated`를 유지하며, `limit=1`도 생략 tag를 probe한다.

```text
memos + current memo_revisions + analysis_applications + memo_items + task_details
                                         │
                                         └── item_tags ── tags
```

별도 graph JSON, 화면 좌표, Neo4j가 source of truth가 아니다. frontend React Flow layout은 표시 책임만 가진다.
V18의 item-scoped typed relation도 canonical source of truth이지만 현재 graph projection input은 아니다.
ITEM source를 MEMO node로 승격하는 규칙, TAG-target relation과 `MEMO_TAG` membership의 관계, 네 가지
방향 relation type의 edge mapping과 provenance deduplication을 결정하기 전에는 graph node/edge,
neighborhood, `projectionVersion`을 바꾸지 않는다.
node 상세와 pagination cursor도 저장하지 않는다. memo drawer는 현재 raw revision을 다시 읽고,
별도 full-corpus neighborhood query가 현재 owner의 `ACTIVE` memo/tag, 최신 유효 `APPLIED`
selection, unarchived item과 confirmed `item_tags`에서 한 hop을 투영한다. MEMO→TAG는
`normalized_name`/UUID keyset, TAG→MEMO는 pin/overdue/TODO/due/current revision/UUID 복합
keyset을 사용한다. cursor version 2는 identity, 24시간 `snapshotAsOf`, 마지막 neighbor와 함께
첫 page의 전체 visible center/neighborhood membership·표시 field·정렬 tuple을 결합한 opaque
SHA-256 digest를 보존한다. 각 continuation은 같은 owner 범위에서 digest를 다시 계산한 뒤 마지막
neighbor의 tuple을 hydrate하므로, canonical 상태가 바뀐 traversal은 `422`로 폐기되고 첫 page부터
다시 읽는다. cursor는 저장 schema나 authorization이 아니다.

`GraphNeighborhoodQueryPlanBenchmarkRunner`는 기본 Surefire pattern에서 제외된 명시적 opt-in
PostgreSQL 17.6 runner다. 10,000 memo, 10,000 tag, 19,999 canonical link를 seed하고 `ANALYZE`한
뒤 양방향 page와 visible-neighborhood digest query 네 개를 각각
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행한다. 2026-08-11의 한 격리 실행에서는
MEMO→TAG page/digest가 각각 약 17.0/54.8 ms, TAG→MEMO page/digest가 약 105.3/180.9 ms였다.
네 query 모두 shared read와 temp read/write가 0이었고 기존 V5/V12/PK index를 사용했다. 이는
warm-cache 단일 실행 관측이며 endpoint SLA나 CI latency threshold가 아니다. continuation은 이
digest 검증 외에도 center·last-neighbor hydration과 page query를 수행하므로 개별 plan 시간을
end-to-end latency로 해석하지 않는다.

저장소 root의 PowerShell에서 아래처럼 재현한다. 명시적 env가 없거나 Maven이 nonzero이면 기존
report를 증거로 사용하지 않는다. runner는 시작 전에 stale report를 지우고 성공한 bounded JSON만
원자적으로 `backend/target/graph-neighborhood-query-plan-report.json`에 발행한다.

```powershell
$planProject = "personal-memo-neighborhood-plan-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
try {
  docker compose -p $planProject -f compose.test.yaml run --rm `
    -e RUN_GRAPH_NEIGHBORHOOD_PLAN_BENCHMARK=true `
    backend-integration `
    mvn -B -Dtest=GraphNeighborhoodQueryPlanBenchmarkRunner test
  if ($LASTEXITCODE -ne 0) { throw "Graph neighborhood benchmark failed." }

  docker compose -p $planProject -f compose.test.yaml run --rm --no-deps `
    backend-integration sh -c 'cat target/graph-neighborhood-query-plan-report.json'
  if ($LASTEXITCODE -ne 0) { throw "Graph neighborhood report read failed." }
} finally {
  docker compose -p $planProject -f compose.test.yaml down --volumes --remove-orphans
}
```

이 개인용 10k checkpoint에서는 새 graph-neighborhood 전용 migration/index를 추가할 효용 근거가 없었다. production
분포에서 runner를 다시 측정해 근거가 생기기 전에는 계산된 overdue/due 우선순위만을 위해
speculative index를 만들지 않는다.

## Exact lexical search projection

Milestone 5의 두 번째 read-only slice도 별도 search table이나 복제본을 만들지 않고 다음 canonical
data를 owner scope 안에서 투영한다.

```text
memos + current memo_revisions
  ├── latest valid APPLIED analysis_application selection title
  ├── APPLIED, unarchived memo_items + task_details
  └── APPLIED, unarchived memo_items + item_tags + ACTIVE tags/tag_aliases
```

- BODY는 `memos.current_revision`이 가리키는 immutable raw content만 읽는다. TITLE은 memo의 최신
  `APPLIED` application selection이 strict object/string title을 가질 때만 읽고, 그 application의
  revision을 nullable `canonicalRevision`으로 함께 반환한다. 따라서 canonical title은 현재 raw
  revision보다 오래될 수 있지만 proposal이나 `UNDONE` application의 title을 대신 사용하지 않는다.
- raw query는 Java NFKC/strip/`Locale.ROOT` lowercase로 만들고 저장된 current raw content와
  canonical title에는 PostgreSQL `normalize(..., NFKC)`와 `lower(... COLLATE "und-x-icu")`를
  적용한 뒤 literal substring으로 match한다. 두 lowercase 경로보다 넓은 collation 동등성은
  가정하지 않는다. tag와 alias는 `TagNormalizer` exact normalized equality로만 match한다. 현재
  owner의 `ACTIVE` tag, `APPLIED` application, unarchived item/link만 canonical tag/task 근거가 된다.
- lifecycle, task state, snapshot에서 계산한 overdue, current revision `created_at`의 inclusive lower /
  exclusive upper bound를 적용한다. order는 `current_revision.created_at DESC, memo.id ASC`다.
- 한 server page는 기본 20, 최대 50이고 visible canonical tag는 memo당 matching-first stable order로
  최대 8개, raw preview는 최대 240 Unicode code point다. PWA는 최대 5 page/100 result만 유지한다.
- cursor version 1은 owner, normalized query/filter digest, sort shape, 최대 24시간의
  `snapshotAsOf`, full visible result digest, 마지막 memo UUID를 가진 canonical URL-safe Base64다.
  query/filter raw text, body, title, preview, tag/alias display value는 넣지 않는다.
- digest는 owner/query/filter/sort/snapshot과 전체 result의 membership, current/canonical revision,
  canonical title, raw content hash, lifecycle/task/overdue/pin, revision time, match field, 표시되는
  최대 8개 tag ID/name을 결합한다. continuation은 같은 `REPEATABLE_READ` transaction에서 digest와
  마지막 memo tuple을 다시 확인하며 변경되면 `INVALID_SEARCH_CURSOR`로 첫 page 재시작을 요구한다.

`MemoSearchQueryPlanBenchmarkRunner`는 기본 Surefire pattern에서 제외된 명시적 opt-in
PostgreSQL 17.6 runner다. 한 owner에 current revision, latest `APPLIED` title, active canonical tag
link와 canonical task가 각각 10,000개인 worst-case all-match Korean corpus를 seed하고 `ANALYZE`한
뒤 page의 `limit + 1`인 21개 fetch와 full-visible-result digest를 BODY/TITLE 및 exact ALIAS 경로에서
각각 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 측정한다.

2026-08-11의 한 격리 실행은 `postgres:17.6-alpine`, `en_US.utf8/en_US.utf8` database locale,
존재하는 `und-x-icu` collation, Java 21 runner에서 다음 hot-buffer 관측을 남겼다.

| plan | actual rows | execution time |
| --- | ---: | ---: |
| Korean BODY + TITLE page fetch 21 | 21 | 812.126 ms |
| Korean BODY + TITLE full digest | 1 | 1082.515 ms |
| exact Korean ALIAS page fetch 21 | 21 | 555.671 ms |
| exact Korean ALIAS full digest | 1 | 1009.93 ms |

네 plan의 planning time은 2.466–3.437 ms였고 shared read와 temp read/write block은 모두 0이었다.
application/item lookup index와 PK/owner integrity index가 사용됐으며 full index 목록, node type,
query SHA-256와 raw bounded EXPLAIN JSON은 report에 포함된다. 이는 warm-cache 단일 query-plan
관측이지 endpoint 또는 기기의 end-to-end latency, 동시성 결과, SLA나 CI threshold가 아니다.
continuation은 digest 외에도 cursor의 last memo 확인과 page query를 수행한다.

저장소 root의 PowerShell에서 아래처럼 재현한다. runner는 explicit opt-in이 없으면 실패하고 시작
전에 stale report를 지운다. 성공한 512 KiB 미만 JSON만 원자적으로
`backend/target/memo-search-query-plan-report.json`에 발행하며, 고유 test project의 임시 PostgreSQL
volume은 report를 읽은 뒤 `finally`에서 정리한다. Maven이나 report read가 nonzero이면 이전 report를
증거로 사용하지 않는다.

```powershell
$searchPlanProject = "personal-memo-search-plan-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
try {
  docker compose -p $searchPlanProject -f compose.test.yaml run --rm `
    -e RUN_MEMO_SEARCH_PLAN_BENCHMARK=true `
    backend-integration `
    mvn -B -Dtest=MemoSearchQueryPlanBenchmarkRunner test
  if ($LASTEXITCODE -ne 0) { throw "Memo search benchmark failed." }

  docker compose -p $searchPlanProject -f compose.test.yaml run --rm --no-deps `
    backend-integration sh -c 'cat target/memo-search-query-plan-report.json'
  if ($LASTEXITCODE -ne 0) { throw "Memo search report read failed." }
} finally {
  docker compose -p $searchPlanProject -f compose.test.yaml down --volumes --remove-orphans
}
```

이 evidence에서는 NFKC/ICU literal substring에 B-tree를 추가할 측정 근거가 없고 canonical join은
기존 index를 사용했으므로 search 전용 migration/index를 추가하지 않았다. 이 slice는 기존 canonical schema만
사용하며 새 Flyway migration/search index, `pg_trgm`, vector/embedding storage 또는 search service를
추가하지 않는다. 실제 개인 data 분포나 동시성 측정에서 근거가 달라지기 전에는 SLA나 새 index
효과를 추정하지 않는다.

## Current indexes

- partial unique `users(primary_email_normalized)` when email is present
- unique `external_identities(provider, provider_subject)` and `(user_id, provider)`
- Spring Session lookup/expiry/principal indexes
- `memos(owner_id, status, updated_at DESC)`
- `analysis_runs(memo_id, memo_revision, status)`
- partial `analysis_run_dispatches(state, lease_expires_at, deadline_at, prepared_at, analysis_run_id)` for `PREPARED`/`RUNNING` recovery
- partial unique `analysis_run_dispatch_attempts(analysis_run_id) WHERE attempt_state = 'IN_FLIGHT'`
- `memo_items(owner_id, created_at DESC)`
- `task_details(status, due_at_utc)`
- `task_details(status, due_local_date)`
- partial `tags(created_by_application_id)`
- `analysis_proposals(owner_id, created_at DESC)`
- `analysis_applications(owner_id, proposal_id, applied_at DESC, id DESC)` (`V11`, proposal별 최신 application 조회)
- `analysis_applications(owner_id, memo_id, applied_at DESC, id DESC) WHERE status = 'APPLIED'` (`V12`, memo별 graph 대표 application 조회)
- `memo_items(owner_id, memo_id, application_id) WHERE archived_at IS NULL` (`V12`, graph의 활성 child item 조회)
- `task_details(owner_id, status, due_at_utc, due_local_date)`
- `item_tags(owner_id, tag_id)`
- `memo_item_relations(source_memo_item_id, application_id, owner_id)`
- partial `memo_item_relations(target_memo_id, owner_id)` / `(target_tag_id, owner_id)` for non-null targets
- partial unique directed relation tuples for source item + relation type + MEMO/TAG target
- owner/operation/key primary key on idempotency records
- owner-scoped normalized tag and alias unique constraints

## Deferred schema

아래 영역은 현재 migration에 넣지 않았다.

- reminder와 Web Push subscription
- offline outbox/change feed
- embedding/pgvector storage
- automatic tag merge/split proposal
- graph cluster/compression/layout persistence
- relation-to-graph projection/edge semantics and fuzzy/dedicated search index
- local email verification and password-reset token/delivery state
- login abuse/rate-limit audit state if the selected policy requires additional persistence
- MFA/passkey authenticators and account recovery codes
- real-model numeric usage/cost ingestion, aggregation, budget enforcement, and approved attempt-purge state
- related-memo, fuzzy/vector, and embedding retrieval context

필요한 vertical slice가 시작될 때 파괴적 변경 없이 새 Flyway migration으로 추가한다.
