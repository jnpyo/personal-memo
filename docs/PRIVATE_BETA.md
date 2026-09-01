# 1차 비공개 베타 체크포인트

기준일은 2026-09-01이다.

- `PRIOR_V19_CODE_AND_AUTOMATED_GATES`: `PASS`
- `CURRENT_V20_BACKEND_MECHANICAL_GATE`: `PASS_773_TESTS`
- `CURRENT_V20_SOURCE_MECHANICAL_QUALIFICATION`: `PASS_ISOLATED`
- `CURRENT_V20_MODEL_ACCURACY_QUALIFICATION`: `NOT_RUN_NO_CLAIM`
- `CURRENT_V20_PRODUCT_SMOKE`: `PASS_NARROW_COMPATIBILITY` / `GENERAL_PROVIDER_NO_GO`
- `CURRENT_V23_AI_PREFERRED_UI_SMOKE`: `PASS_PROVISIONAL_KEEP_ONLY` / `GENERAL_PROVIDER_NO_GO`
- `CURRENT_REPEATABLE_AI_PREFERRED_PRODUCT_SMOKE`: `PASS_NARROW_PRODUCT_PATH` / `GENERAL_PROVIDER_NO_GO`
- `PRIOR_V18_PRIVATE_BETA_DECISION`: `GO_TO_DEVICE_ACCEPTANCE`
- `PRIOR_V20_ROLLOUT_DECISION`: `GO_TO_DEVICE_ACCEPTANCE`
- `PRIOR_PERSONAL_V22_ROLLOUT_DECISION`: `GO_TO_DEVICE_ACCEPTANCE`
- `CURRENT_PERSONAL_V23_ROLLOUT_DECISION`: `GO_TO_DEVICE_ACCEPTANCE`
- `MILESTONE_6A1_V21_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `PERSONAL_V21_MIGRATION_REBUILD_DEPLOYMENT`: `PASS_AS_PART_OF_V22_DEPLOYMENT`
- `MILESTONE_6A2A_DARK_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `MILESTONE_6A2B_PRODUCER_ACTIVATION`: `DEFERRED`
- `MILESTONE_6B_AUTHENTICATED_ICAL_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `MILESTONE_6C_RECIPIENT_FEED_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `PERSONAL_V22_MIGRATION_REBUILD_PRIVATE_ROUTE_SMOKE`: `PASS_OWNER_AUTHORIZED`
- `MILESTONE_6D_PUBLIC_EDGE_PREFLIGHT_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `MILESTONE_6D_PUBLIC_EDGE_PREFLIGHT_ISOLATED_SMOKE`: `PASS_SYNTHETIC_DISPOSABLE`
- `MILESTONE_6D_PUBLIC_ACTIVATION_AND_EXTERNAL_CLIENT_SMOKE`: `DEFERRED` / `NOT_AUTHORIZED`
- `MILESTONE_V23_EXPLICIT_PUBLIC_CONSENT_SOURCE_STATUS`: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `MILESTONE_V23_SOURCE_QUALIFICATION`: `PASS_ISOLATED`
- `PERSONAL_V23_MIGRATION_REBUILD_DEPLOYMENT`: `PASS_OWNER_AUTHORIZED`
- `PERSONAL_ANALYSIS_PATH_DIAGNOSTIC_DEPLOYMENT`: `PASS_OWNER_AUTHORIZED`
- `V23_DEVICE_ACCEPTANCE_AUTOMATED_READINESS`: `PASS`
- `DEVICE_INSTALL_ACCEPTANCE`: `USER_CHECK_REQUIRED`
- `MILESTONE_6E_HISTORICAL_OWNER_REMOTE_PWA_ACCEPTANCE`: `PASS_USER_REPORTED_EXACT_OWNER_LOGIN_AND_PWA`
- `MILESTONE_6E_HISTORICAL_QUALIFICATION_STATUS`: `LIVE_OWNER_BETA` / `SOLO_PROVISIONAL` / `REPORT_ONLY`
- `MILESTONE_6E_ACCESS_CONTROL_PATH_FIX`: `DEPLOYED_LOCAL_AND_EXTERNAL_PREAUTH_PASS`
- `MILESTONE_6E_CURRENT_OWNER_REACCEPTANCE`: `PASS_USER_REPORTED_POST_FIX_HEALTH_AND_PWA`
- `MILESTONE_6E_CURRENT_ACTIVATION`: `LIVE_OWNER_BETA_REQUALIFIED`
- `MILESTONE_6E_CURRENT_RUNTIME`: `APP_CONNECTOR_RUNNING_MANUAL` / `CALENDAR_CONNECTOR_STOPPED_MANUAL` / `APP_EDGE_HEALTHY_LOOPBACK`
- `MILESTONE_6E_UNAUTH_ME_AND_PROVIDER_LOG_SENTINELS`: `UNVERIFIED`
- `MILESTONE_7_1_TODAY_FIRST_MOBILE_HOME`: `SOURCE_QUALIFIED_LINUX_FULL_E2E_PASS_DEPLOYMENT_PENDING`
- `MILESTONE_7_2_GRAPH_FIRST_MOBILE_HOME`: `SOURCE_QUALIFIED_PERSONAL_DEPLOYED_VISUAL_REVIEW_IN_PROGRESS`
- LiquidAI evidence: `SOLO_PROVISIONAL` / `REPORT_ONLY` / `NO_GO`
- Personal AI-preferred proposal path: `SOLO_PROVISIONAL` / `REPORT_ONLY`; owner-authorized V18→V20
  deployment, V20→V22 and V22→V23 backup/restore rehearsal/migration/rebuild/private-route smoke completed;
  LiquidAI remains provider `NO_GO`

이 결정은 한 명의 operator-provisioned owner가 개인 Windows PC와 신뢰하는 RFC1918 LAN에서
Android Chrome으로 온라인 사용한다는 좁은 범위에만 적용된다. local CA HTTPS를 사용하고 공유기
port forwarding은 하지 않으며, backend와 PostgreSQL port는 공개하지 않는다. local registration과
개인 overlay의 Google 기능은 닫는다. 기본 application은 `FakeAnalyzer` +
`UNCERTAINTY_ONLY`와 `NO_NETWORK` Fake gateway를 유지한다. personal overlay만 exact pinned localhost
LiquidAI를 `AI_PREFERRED`로 사용해 deterministic proposal이 clear여도 schema/domain-valid current
revision마다 호출하도록 처음 V20 personal stack에 배포됐고 현재 V23 stack에서도 유지된다. 이는 authoritative 모델 승인이나 정확도 주장이
아니며 모든 결과는 사용자 검토와 명시적 Apply 대상이다.

현재 source에는 별도의 Milestone 6A.1 수동 EVENT schedule foundation, 6A.2a dark-compatible
proposal-v3 contract가 추가됐지만 이 private-beta 결정을 자동으로 갱신하지 않는다. Current Fake와
localhost model producer는 계속 v2이고 모든 EVENT review는 schedule 없이 시작한다. V3 alternative도
사용자가 `아직 미적용` 후보를 명시적으로 고르기 전에는 draft에 복사되지 않으며 current domain은
non-null analyzer suggestion을 거절한다. 사용자가 후보를 직접 선택하거나 일정을 직접 입력한
scheduled Apply만 selection schema v2와 V21 `event_details`를 사용한다. `GET /events`는 그 confirmed
schedule만 읽는다. 이 기능 상태는 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY`지만 개인 stack schema는
2026-08-27 owner 승인 아래 V22로 전환된 뒤 2026-08-28 V23으로 전환됐다. 이 배포는 v3 producer activation이나 schedule 자동
선택을 열지 않았다. Milestone 6B snapshot 자체는 인증된 owner가 current
confirmed schedule의 RFC 5545 snapshot을 plain-text로 미리보고 같은 Blob을 다운로드하는 source까지만
추가하며 공개 URL, recipient membership/token, 자동 update/removal, alarm은 없다. 이어진 6C source에는
recipient feed/token/membership와 update/removal tombstone이 구현되어 현재 personal V23 stack에 배포됐다.
다만 실제 개인 feed row를 만들지 않았고 public activation과 외부 calendar client smoke는 계속 닫혀 있다.
현재 source analyzer는 `fake-v9`/`korean-rules-v7`이며 명시적
`오늘|내일|모레 + 오전|오후 + 1–12시`(optional minutes)만 revision capture context의
`RELATIVE_EXACT`로 만든다. 날짜 없는 `6시`는 `UNKNOWN`이고 today/PM을 추론하지 않는다. TIMED
Apply는 start/end offset이 immutable revision zone에서 유효해야 하며 DST gap을 거절하고 overlap의
두 explicit valid offset 중 하나를 허용한다.

## 데이터와 모델 경계

- 보존된 V19 qualification과 V20 isolated qualification은 공개 synthetic fixture, E2E가 만든
  합성 계정·메모, 임시 PostgreSQL만 사용했다. 2026-08-24의 owner-authorized deployment는 exact
  personal stack만 checksummed custom-format으로 백업해 별도 disposable restore rehearsal을 통과한
  뒤 Flyway V18→V20, image rebuild, health 확인 순서로 수행했다.
- raw 개인 memo 내용·identifier는 검사하거나 model 입력·일반 log·보고서에 넣지 않았다. product
  smoke는 개인 PostgreSQL/canonical volume이 아닌 별도 fresh disposable PostgreSQL과 synthetic
  memo만 사용했고 Apply를 호출하지 않았다.
- V20 personal adapter는 자동 Apply 권한이 없고 external provider, fine-tuning, LoRA, RAG/vector/embedding
  ingestion, automatic rule promotion과 학습 도구를 사용하지 않는다.
- localhost LiquidAI v1-v8-A는 test-only shadow evidence다. v7-A cap 증가, v7-B prompt 축소,
  v8-A compact wire가 모두 24/24 LENGTH, accepted 0, deterministic fallback 24였으므로 제품 경로는
  authoritative 분석과 학습에는 계속 `NO_GO`다. 개인 path는 semantic-patch v2 `KEEP` 또는 기존
  grounded candidate의 제한된 `PATCH`만 허용하고 JSON Schema/domain validation,
  proposal-only/manual-Apply 경계를 그대로 유지하는 별도 `SOLO_PROVISIONAL`/`REPORT_ONLY`
  결정이다.

현재 `ollama-local-gateway-v2`는 Ollama의 optional bounded textual `thinking`을 검증한 뒤
무시하고 visible `content`만 strict JSON/schema/domain validator로 보낸다. non-text·oversized·extra
field와 malformed/truncated content는 그대로 fail closed한다. 이전 adapter-v1 smoke/diagnostic에서는
malformed/length-bounded content가 거절됐고, v2의 exact-sentence compatibility smoke는 성공했다. 이 한
문장 성공은 broad provider/model quality 승인이 아니므로 authoritative provider `NO_GO`는 유지된다.

모델 입력은 현재 immutable revision을 bounded 실행 메모리에서만 사용한다. raw memo, prompt 또는
response를 V19/V20 evidence/attempt/provider metadata/log/browser storage/training dataset에 복제하지 않는다.
실패, timeout, model/digest mismatch, redirect/proxy/tool, truncation 또는 validation 실패는
재검증된 local 상세 검토로 닫힌다. default-`RECORD` fallback evidence는 `UNKNOWN`으로 정규화하고
기존 명시 후보는 보존한다. V20은 같은 owner의 active/current/latest `APPLIED` 중 type-corrected 또는
user-resolved 단일-item 사례에서 current memo에도 exact-unique한 충돌 없는 짧은 anchor를 최대 K=3
찾을 수 있다. persisted retry snapshot은 current memo UTF-16 offset와 approved kind만 포함하고,
historical raw/ID/selection/title/tag/due/relation은 포함하지 않는다. claim 시 locked current revision에서
`anchorText + approvedKind`를 materialize하고 finalization은 raw offset snapshot을 지운 뒤
hash/version/count만 남긴다. Undo는 새 dispatch source에서 제외되지만 이미 준비된 retry는 동일
snapshot을 유지한다. 이 inference-time hint를 자동 학습, 정확도 label 또는 rule promotion으로
해석하지 않는다.

## 2026-08-28 반복 가능한 격리 AI-preferred 제품 경로 smoke

영구 오케스트레이터가 공개 synthetic 고정 문장 3건을 별도 Fake/`UNCERTAINTY_ONLY`와
LiquidAI/`AI_PREFERRED` Compose arm에서 register → memo → analysis-run → proposal read 순서로
실행했다. 각 arm은 tmpfs PostgreSQL을 사용했고, LiquidAI arm은 전용 owned Ollama `0.32.7`을
`127.0.0.1:11435`에만 열었다. 개인 `.env`, 개인 API/PostgreSQL/memo/canonical data, 기본 Ollama
endpoint와 외부 product service는 접근하지 않았고 Apply/reject/postpone/undo 및 alarm/reminder
endpoint도 호출하지 않았다.

두 arm의 최종 proposal은 JSON Schema/domain validation을 3/3 통과했고, 긍정 TASK case는 각각
1/1 통과했으며 부정/서술형 TASK 승격, 정확한 날짜 발명, 미해결 hallucination과 canonical write는
모두 0이었다. LiquidAI 자체 성공은 1/3 `ACCEPTED_UNCHANGED`, 나머지 2/3은 validated local
fallback이었다. median wall latency는 Fake `73 ms`, LiquidAI `6958 ms`로 `95.3151×`였고 semantic
improvement는 `NOT_DEMONSTRATED`다. 비독점 device-wide GPU 관측은 baseline/max/post
`3306`/`6478`/`3045 MiB`, max utilization `93%`, exact-target Ollama max VRAM
`3012684676` bytes였다.

aggregate-only receipt는
`backend/target/evaluation/ai-preferred-product-smoke-20260828T054016485Z.json`, SHA-256
`d605ed48935d8dd5acbd98ff7e658c495f70cb1467f69ad8efb1b656f5fcca3b`이고 strict validator를
통과했다. 실행 source는 `dirty=true`라 clean release attestation은 아니다. 사후 독립 확인에서
scoped container/image/network/volume/temp directory/`11435` listener가 모두 0이었고 personal
Compose container는 전후 3개로 같았다. Docker build/pull network infrastructure 자체를 막았다는
증거는 아니며 `externalProductServiceAccessed=false`는 product-service call에만 적용된다. 따라서
상태는 `PASS_NARROW_PRODUCT_PATH`이지만 계속 `SOLO_PROVISIONAL/REPORT_ONLY`; provider/LoRA
`NO_GO`, training `NO_GO_FOR_TRAINING`, RAG `NOT_USED`, automatic Apply disabled다.

## 보존된 V19 qualification과 현재 V20 isolated qualification

| Gate | Result |
| --- | --- |
| Backend + isolated PostgreSQL + Spotless + SpotBugs | Current V20 `PASS`: Flyway V1–V20, 773 tests / 0 failures / 0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors |
| Frontend lint/unit/build | Current V20 `PASS`: lint, 39 files / 301 tests, TypeScript and PWA production build |
| OpenAPI | Current V20 `PASS`: Redocly 2.44.1 |
| Production/personal Compose render | Current V20 `PASS`: `.env.personal.example` only; `AI_PREFERRED`, approved corrections enabled, K=3 rendered |
| Windows PowerShell contracts | Preserved V18 `PASS`: source, UTF-8 capture, rotation lock, Windows PowerShell 5.1 parse |
| Production Nginx TLS configuration | Preserved V18 `PASS`: unprivileged Nginx `nginx -t` |
| Mobile primary/OAuth/PWA E2E | Preserved V18 `PASS`: 23/23 with Playwright 1.60.0 / Chromium, 24.4 s, secure loopback origin |
| Personal backup/restore and V20 deployment | Current `PASS`: checksummed personal custom-format backup retained outside the repository; separate disposable restore rehearsal, personal Flyway V18→V20, rebuilt stack health, and trusted HTTPS health succeeded |
| Personal V22 + 6D.1 `LOCAL_ONLY` deployment | Current `PASS`: owner-authorized V20→V22 restore/migration gate followed by a separately backed-up 6D.1 rebuild; publication environment 0, three-service health, Flyway V22/failed 0, PWA 200, unauthenticated capability 401/no-store, synthetic private feed token-free logs |
| Personal V23 explicit public-consent deployment | Current `PASS`: fresh checksummed V22 backup, disposable V22→V23 restore rehearsal, personal Flyway V23/failed 0, three V23 columns/two validated constraints, unchanged zero feed/entry aggregates, publication environment 0, three-service/trusted-TLS/PWA health and synthetic private-feed route |
| Product AI smoke | `PASS_NARROW_COMPATIBILITY` / `GENERAL_PROVIDER_NO_GO`: fresh disposable synthetic DB only; adapter-v1 invalid output failed closed, adapter-v2 exact-sentence run succeeded, zero Apply/canonical item creation |
| Repeatable isolated AI-preferred product smoke | `PASS_NARROW_PRODUCT_PATH` / `GENERAL_PROVIDER_NO_GO`: fixed public synthetic 3-case Fake/Liquid product API comparison; LiquidAI success/fallback 1/2, median 6958 ms versus Fake 73 ms, semantic improvement not demonstrated, zero Apply/canonical writes, cleanup restored |

Milestone 6A.1과 6A.2a를 포함한 이전 source-only qualification은 2026-08-24 별도 격리
PostgreSQL에서 실행했다. Flyway V1–V21, backend 813 tests / 0 failures / 0 errors / 1 skipped,
Spotless clean, SpotBugs 0 bugs/errors, frontend lint와 40 files / 324 tests, TypeScript/PWA production
build, Redocly 2.44.1 OpenAPI lint가 통과했다. 이 결과는 source contract만 검증하며 personal V21
migration/rebuild/deployment, personal-data smoke, v3 producer activation을 승인하거나 실행하지 않는다.

Milestone 6B current source-only qualification은 2026-08-25 별도 격리 PostgreSQL에서 Flyway
V1–V21, backend 820 tests / 0 failures / 0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors,
frontend lint와 41 files / 334 tests, TypeScript/PWA production build, Redocly 2.44.1 OpenAPI lint,
production Compose render를 통과했다. Container-local `127.0.0.1` secure-loopback relay에서 focused
Playwright calendar flow 1/1도 통과해 preview와 download byte identity, fixed filename, schedule-only
disclosure, Undo를 확인했다. 이 결과는 개인 V21 deploy나 실제 외부 calendar-client import/
subscription smoke가 아니며, 검증용 Compose·relay·volume·local image는 모두 제거됐다. 개인 V20
stack, 개인 DB/메모, Ollama는 이 6B qualification에서 접근하거나 변경하지 않았다.

Milestone 6C current source-only qualification은 2026-08-25 별도 격리 PostgreSQL에서 Flyway
V1–V22, backend 845 tests / 0 failures / 0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors,
frontend lint와 44 files / 377 tests, TypeScript/PWA production build, Redocly 2.44.1 OpenAPI lint,
production/personal Compose render와 Windows source contract를 통과했다. Secure-localhost 전체
Playwright는 24/24, 25.5 s로 통과했고 수동 EVENT schedule, `BUSY_ONLY` 기본값, 명시적 membership,
one-time copy-only URL, title/raw/internal-ID 비노출, rotate 후 이전 URL 404, revoke 후 현재 URL 404,
offline PWA의 network-only feed navigation을 확인했다. Private isolated Nginx의 합성 unknown token
GET/HEAD도 empty no-store 404였고, 소유한 frontend/backend log에서 합성 sentinel은 0건이었다. 이는
외부 public edge 또는 외부 provider log 증명이 아니다. 추가 합성 integration은 revoked를 포함한
100 lifetime feeds와 tombstone을 포함한 100 lifetime entries의 정확한 경계·실패 rollback, ALL_DAY
active/cancelled same-UID 직렬화, create/add와 actual undo/memo update/trash의 6개 동시성 조합을
검증했다. 최초 835-test 실행 전의 6개 error는 cached Spring context들이 합성 PostgreSQL connection
limit를 소진한 test-harness 오류였다. 후속으로 test resource에만 Hikari maximum-pool-size 4/
minimum-idle 0을 고정했고, 별도 명령행 override 없는 fresh `mvn -B verify`가 위 845-test 결과를
통과했다. 모든 exact 6C 임시 container/volume/network/image는 제거했으며 개인 V20 stack, 개인
PostgreSQL/메모/canonical data와 Ollama는 접근하거나 변경하지 않았다.

## 2026-08-27 owner-authorized personal V22 deployment

- exact personal PostgreSQL은 전환 직전 checksummed custom-format backup
  `personal-memo-20260827-020400432Z.dump`으로 보존했다. live session table data는 제외했다.
- 직전 backup을 별도 disposable project/volume에 복원해 backend 기동 전 Flyway V20, 기동 후 V22,
  failed migration 0, V21/V22 calendar table 존재와 row 0, backend health를 확인한 뒤 그 임시
  container/network/volume을 제거했다. raw memo body나 개인 일정 내용은 읽지 않았다.
- 보존된 V20 backend/frontend image는 각각
  `sha256:a22aade6e9ff88517d9e8ecbfdfea8a181ef6bba5f03880c6a405c9d2b9b6b02`,
  `sha256:b72e00055251907a0befe533712ddfabd448b7d505ace9419c77576ef5338dd9`이고,
  배포된 V22 image는 각각
  `sha256:ae39dbdce7059f7dcbf2844f5f342a88ff573a097cf501661e19427c9411bf94`,
  `sha256:c3ca32e287f4ab3215c4c2d353b13b69e527a9dfea62d6c98bf0abb35b22ab2b`로 태그했다.
- live stack은 Flyway V22, failed migration 0, `event_details` + `calendar_feeds` +
  `calendar_feed_entries` 합계 0, 세 service healthy, trusted private HTTPS health를 통과했다.
- random synthetic unknown token의 private GET/HEAD는 동일한 empty `404`, `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`, no `Set-Cookie`/`Content-Type`였고 frontend의 실제 query-free 두
  access-log 행을 확인했다. frontend/backend log에 token 또는 `?token=`은 없었다.
- 이 smoke는 feed/token/membership row를 만들지 않았고 canonical schedule을 읽거나 변경하지 않았다.
  Public DNS/TLS edge, 외부 provider log, Google/Apple subscription과 refresh 특성은 여전히 6D다.

## 2026-08-27 owner-authorized 6D.1 personal `LOCAL_ONLY` deployment

- 전환 직전 session table data를 제외한 checksummed custom-format backup
  `personal-memo-20260827-033109271Z.dump`과 SHA-256을 저장소 밖에 보존했다.
- 직전 V22 backend/frontend는 `pre-6d1-20260827-033050Z` rollback tag로 보존했다. 배포된 6D.1
  image는 각각 `sha256:3d1295b84aa41ef27e0adf0120775b3b0c021f6eae9bcde94658393d317772d4`와
  `sha256:4593d22806a46ab69ceae5630196c74cc59fb356b5d815535c4fcc2090830f52`다.
- Windows build 26200의 Docker Desktop 4.84.0 stale AF_UNIX socket startup 오류는 factory reset이나
  volume 삭제 없이 runtime directory를 `.stale-*`로 격리하고 Windows를 재부팅해 복구했다. 해당
  보존 directory는 Docker data가 아니며 재부팅 후에도 Windows가 손상 reparse point 제거를 거절해
  3개가 남아 있다.
- PostgreSQL volume을 유지한 rebuild 뒤 publication environment entry는 0이었다. Application
  default `false`/blank가 적용돼 배포 상태는 `LOCAL_ONLY`다.
- 세 service health, trusted private HTTPS, Flyway V22/failed 0, PWA 200, session 없는 capability
  401 `AUTHENTICATION_REQUIRED` + `no-store`, synthetic unknown-token private GET/HEAD와 token-free
  frontend/backend log가 통과했다.
- 개인 session을 사용하지 않아 authenticated capability 200 body는 runtime smoke하지 않았고,
  실제 feed/event/canonical row를 만들거나 개인 일정 내용을 읽지 않았다. Public edge와 external
  client smoke는 계속 `NOT_AUTHORIZED`다.

## 6D public-edge preflight source boundary

`6D public-edge preflight`는 새 `6D.2` milestone이나 personal/public rollout이 아니다. Source에는
별도 `calendar-feed-edge`, loopback-only `compose.public-feed.yaml`, 마지막 단계 전용
`compose.public-feed-activation.yaml`, placeholder `.env.public-feed.example`, disposable stub 기반
isolated synthetic smoke가 있다.

- Preflight overlay만 적용하면 edge는 `127.0.0.1`에만 bind되고 backend capability는 계속
  `LOCAL_ONLY`다. PWA, authenticated API/OAuth, Spring Boot host port와 PostgreSQL은 이 edge를 통해
  공개되지 않는다.
- Exact bodyless canonical-token GET/HEAD만 forwarding 대상이다. 다른 path/method/query/body와
  intercepted upstream error는 generic empty 404, rate rejection은 bodyless 429로 축소되고, owned
  edge/upstream log에는 fixed safe route/method class만 남는다.
- Origin-side provisional bounds는 60 requests/minute + burst 20, concurrent connection 8,
  proxy connect/send/read 2s/5s/10s다. 이는 tunnel 뒤 shared loopback hop의 containment이지 external
  per-client 정책, total external deadline, end-to-end latency나 SLA가 아니다.
- Recorded isolated smoke는 생성한 synthetic bearer와 disposable Nginx upstream만 사용해 exact
  GET/HEAD, deny surface, header stripping, 404/429, provisional bounds와 query/path/header/
  custom-method bearer sentinel의 owned log 0건을 통과했다. Personal PostgreSQL/session/memo/feed/
  event/canonical schedule/Apply를 읽거나 변경하지 않았으며 external operator log 증명은 아니다.
- Activation은 reviewed ignored `.env.public-feed`와 activation overlay를 마지막에 적용하는 별도
  2단계다. 실제 hostname/DNS/TLS/operator route, external bounds/log proof와 rollback이 승인되기
  전에는 실행하지 않는다. Rollback은 external route 차단 → activation overlay 없이 backend recreate
  및 `LOCAL_ONLY` 확인 → loopback edge 제거 순서이고 database rollback은 없다.

후속 external synthetic qualification은 strict non-secret receipt에 46 bounded probe(exact positive 3,
origin deny 8, remote catch-all deny 5, rate attempt 30), cache `DYNAMIC 46`/`HIT 0`, 최대 latency
`873.816 ms`, owned-log/external-artifact-reflection sentinel PASS를 기록하고 rollback했다. Rate 429는
bounded attempts 안에서 관측되지 않았다. Current account plan에서 provider/customer log sentinel은
unavailable/unverified이고 receipt replica field는 `REQUIRED_NOT_VERIFIED`다. Cloudflare dashboard에서
rollback과 cleanup 뒤 active replica 0/routes 1/status Down을 수동 관측했지만 receipt proof를 대체하지
않는다. Disposable containers/network/local image는 제거됐고 stopped/manual service, process/listener 0,
healthy personal stack으로 원복했다. 개인 memo/DB/canonical/Apply/Ollama는 접근하지 않았다.

따라서 현재 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`, receipt status
`TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`, decision `NO_GO`다. 실제 일정 publication은
명시적 privacy consent와 provider/customer log 처리 결정 전에는 허용되지 않으며, internet activation과
Google/Apple subscription/update/removal smoke도 `NOT_AUTHORIZED`다.

## V23 feed별 외부 공개 동의 source qualification

V23 source는 모든 기존/new feed를 `LOCAL_ONLY`/동의 없음으로 유지한다. 인증된 owner가 exact current
policy를 명시적으로 동의하고 fresh client-generated bearer를 제출해야만 verifier 회전, 공개 scope,
policy version, consent timestamp가 한 transaction에서 함께 바뀐다. Public deployment에서도 이 상태를
통과하지 않은 feed는 generic empty `404`로 닫히며 revoke는 동의와 공개 scope를 지우고 기존 URL을
영구 무효화한다. 이 흐름은 canonical 일정 생성, 자동 공유, 자동 Apply 또는 analyzer 선택을 추가하지 않는다.

Source-only qualification은 disposable PostgreSQL에 Flyway V1-V23을 적용해 backend 121 suites /
861 tests(`failures 0`, `errors 0`, `skipped 1`)와 Spotless/SpotBugs를 통과했다. Frontend는 44 files /
414 tests, ESLint, TypeScript, PWA production build를 통과했다. OpenAPI 3.1은 Redocly lint와 Draft
2020-12 actual-instance validation을 통과했고, production/personal/preflight/activation Compose render와
public/personal PowerShell source contract도 통과했다. 검증 전용 container/network/volume은 모두 제거했다.

이 source qualification 자체는 개인 stack migration/deployment나 실제 공개 활성화 증거가 아니었다.
후속 owner-authorized V23 deployment는 아래에 별도로 기록한다. 개인 memo/feed/event/canonical/API/Apply
smoke, Ollama, Cloudflare connector 재시작, real-feed publication과 Google/Apple client smoke는 계속
실행하지 않았다. 따라서 V23 기능 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`이고 실제 activation은
provider/customer log와 receipt-level replica proof가 해결되기 전까지 `NO_GO`다.

## 2026-08-28 owner-authorized personal V23 deployment

- live preflight는 기존 backend/frontend image
  `sha256:3d1295b84aa41ef27e0adf0120775b3b0c021f6eae9bcde94658393d317772d4` /
  `sha256:4593d22806a46ab69ceae5630196c74cc59fb356b5d815535c4fcc2090830f52`, exact
  PostgreSQL volume `personal-memo-private-win_personal-memo-postgres`, Flyway V22/failed 0,
  feed/entry 0, publication environment entry 0을 확인했다. 이전 이미지는
  `pre-v23-20260828-024831Z` rollback tag로 보존했다.
- writer를 중지하고 만든 fresh custom-format backup은
  `personal-memo-20260828-024917394Z.dump`, `138456` bytes, SHA-256
  `1A1AD7EDB5F4489D3FAABDB3D5C96518E2988B9FC9400F2B89CB93CE0D07F9CE`다. Live Spring Session
  table data는 제외했고 sidecar가 filename/hash와 일치했다.
- exact backup의 disposable V22→V23 restore rehearsal은 Flyway V23, backend health, V23 열 3개와
  모든 restored feed의 `LOCAL_ONLY`/null/null을 aggregate-only로 확인했다. Project
  `personal-memo-restore-20260828024935-194b5241`의 container/network/volume과 temporary backend
  image는 성공 뒤 제거했다.
- live rebuild는 base+production+personal Compose만 사용했다. 새 backend/frontend image는
  `sha256:434dd1b33886d3f018c1f85c61f36be8e56e1191f361c16679eb780721fb5405` /
  `sha256:d02f6339433ff30778328a99968b0f46e92163c2406818fd983f420ad2c100e2`다. Frontend image build는
  lint, 44 files/414 tests와 PWA production build를 다시 통과했다. 검증된 이미지는 각각
  `deployed-v23-local-only-20260828-0253Z` tag로 추가 보존했다.
- postflight는 세 service health, trusted private HTTPS, PWA 200, Flyway V23/failed 0, V23 열 3개,
  validated CHECK constraint 2개, pre/post feed·entry·version aggregate 불변(모두 0), publication
  environment entry 0, unauthenticated capability 401/no-store와 synthetic unknown-token GET/HEAD
  token-free log를 통과했다. Capability의 유일한 cookie는 값이 출력되지 않은 Secure/SameSite=Lax
  `XSRF-TOKEN`이며 session cookie가 아니었다.
- Cloudflare service는 `Stopped`/`Manual`, `cloudflared` process와 running public edge는 0이었다.
  Public activation overlay, 개인 session/feed/event/canonical/Apply, Ollama와 real calendar client는
  사용하지 않았다.
- Docker Desktop 시작 전에 stale Windows AF_UNIX reparse point가 engine을 두 차례 막았다. Factory
  reset이나 Docker data 이동 없이 runtime-only directory를
  `run.stale-v23-20260828T024532Z`, `run.stale-v23-20260828T024800Z`,
  `docker-secrets-engine.stale-v23-20260828T024800Z`로 보존 이동해 복구했다. 이 세 directory는
  개인 volume/image/backup이 아니며 Windows가 socket child 삭제를 거절해 현재 남겨 두었다.
- 첫 live start는 PS5.1에서 `System.ServiceProcess` assembly가 자동 로드되지 않아 migration 전에
  fail closed했다. Guard가 assembly를 명시적으로 로드하도록 수정하고 source contract와 실제 PS5.1
  guard를 통과한 뒤에만 다시 시작했다.

이 배포는 V23 schema와 private `LOCAL_ONLY` 제품 경계의 배포 증거다. 인터넷 publication,
provider/customer log와 receipt-level replica proof, 실제 feed·Google/Apple subscription은 여전히
`NO_GO`/`NOT_AUTHORIZED`다.

E2E의 최종 유효 실행은 isolated Compose network 안의 일회용 browser loopback relay로 CI와 같은
`127.0.0.1` secure origin을 재현했다. 앞선 `host.docker.internal`과 Docker Desktop의
`.localhost` 해석 시도는 각각 insecure origin 또는 browser 연결 전 단계에서 실패했으며 제품
request를 만들지 못한 harness-invalid 실행이다. 최종 실행은 `window.isSecureContext=true`,
`crypto.randomUUID` 사용 가능, service-worker/installability 검증을 포함해 통과했다.

보존된 V18 백업 smoke는 합성 E2E database를 별도 disposable PostgreSQL에 복원한 기록이다.
2026-08-24에는 그 절차를 개인 stack backup에 적용해 dump checksum/parse와 disposable restore/backend
health/Flyway V20 및 aggregate integrity를 확인했다. backup은 복구 자산으로 저장소 밖에 보존하고,
restore project·test volume·temporary image는 scoped cleanup 대상으로 분리했다.

## 2026-08-24 제품 smoke와 fallback 결과

제품 API smoke는 fresh disposable PostgreSQL의 synthetic memo만 사용했다. 이전 adapter-v1 실행과
직접 diagnostic에서는 optional `thinking` 뒤의 visible `content`가 malformed 또는 length-bounded여서
strict validation이 거절했고 검증된 deterministic proposal로 fallback했다. binding
`ollama-local-gateway-v2+local-semantic-patch-v2` 재실행은 같은
strict JSON/schema/domain 경계를 유지한 채 exact synthetic 문장을 두 번 완료했다. clean 측정에서
gateway/wall latency는 약 `4.596 s`/`4.713 s`, outcome은 `SUCCESS`, model contribution은
`ACCEPTED_UNCHANGED`, tool/mutation call은 0이었다. 이는 좁은 wire-compatibility smoke일 뿐 정확도
benchmark나 blind `PASS`가 아니며 authoritative provider 결정은 계속 `NO_GO`다.

그 V20 product smoke의 `fake-v8` / `korean-rules-v6` patch와 adapter-v2의 accepted-unchanged result에서 exact synthetic 문장 `6시 디스코드 접속하기`는 TASK,
action `접속하기`, object `디스코드`, source span `디스코드 접속하기`로 제안된다. `접속하기 싫다`와
`접속하기 좋은 시간`은 명령으로 승격하지 않는다. 문장에 날짜가 없으므로 `6시`는 정확한 오늘
18시나 due로 해석하지 않으며 알람/reminder 저장·전달도 구현하지 않았다. 결과는 모두 manual Apply
전 untrusted proposal이고 smoke에서는 Apply와 canonical item 생성을 하지 않았다.

## 2026-08-28 V23 AI_PREFERRED disposable UI smoke

개인 session, memo, PostgreSQL 또는 canonical data를 사용하지 않고, 현재 배포된 V23 backend/frontend
image를 두 개의 별도 tmpfs PostgreSQL stack에서 재사용했다. 같은 공개 synthetic 문장
`6시 디스코드 접속하기`를 synthetic 계정으로 PWA에 입력해 capture → synchronous analysis → proposal
GET → review dialog까지 실행하고 Apply/reject/postpone 전에 멈췄다.

LiquidAI arm은 exact `ollama-local-gateway-v2+local-semantic-patch-v2`, model tag와 digest를 유지했고
clean UI run 1/1이 schema v2, `SUCCESS`, `REVIEW_REQUIRED`, `ACCEPTED_UNCHANGED`로 완료됐다. UI wall
latency는 `5349.338 ms`였다. Harness assertion을 현재 product shape에 맞추는 과정까지 포함한 실제 model
attempt 3회는 모두 `STARTED`/`RESULT`/`OBSERVED`/`APPLIED_TO_RUN`이었고 duration은
`4990`/`5012`/`5108 ms`였다. 이 결과는 유효한 localhost model response가 strict proposal 경계를
통과했음을 뜻하지만, `changedFields=[]`이므로 semantic 변경 기여는 없었다.

같은 문장의 Fake arm clean UI run은 `242.399 ms`, internal attempt duration은 `0 ms`였다. 두 arm은
모두 TASK `디스코드 접속하기`를 보존하고 날짜 없는 `6시`를 확정 날짜·오늘 18시·알람으로 만들지 않아
상세 수동 검토를 요구했다. 이 단일 paired sample의 wall 차이는 `5106.939 ms`, 비율은 약 `22.1x`이며
benchmark나 p95 claim이 아니다. LiquidAI sampling 120회/누락 0에서 device-wide non-exclusive GPU
baseline/max는 `2999`/`6229 MiB`, maximum utilization은 `91%`였다. Ollama가 보고한 maximum target
allocation은 `3012684676 bytes`, context는 `4096`이었다.

LiquidAI/Fake disposable database 모두 `analysis_applications`, `memo_items`, `task_details`,
`event_details`, `item_tags`, `memo_item_relations`, `calendar_feeds`, `calendar_feed_entries`가 0이었다.
Tool/mutation call도 0이었고 personal V23 container 세 개는 같은 ID로 healthy를 유지했다. 두 smoke
project의 container/network/volume과 임시 test 파일은 제거했으며 Ollama는 기존 process와
loopback-only `127.0.0.1:11434` listener를 유지한 채 loaded model 0으로 복구됐다.

현재 checkout은 clean release snapshot이 아니고 이번 host orchestration은 임시 파일을 정리한 수동
qualification이므로, 이 기록은 `SOLO_PROVISIONAL`/`REPORT_ONLY`이며 반복 가능한 release gate가 아니다.
Provider와 training은 `NO_GO`, LoRA는 `NO_GO`, fine-tuning·LoRA·RAG는 실행하지 않았다. 다음 source
milestone은 개인 overlay와 분리된 permanent synthetic orchestrator, aggregate-only receipt schema와
Fake/LiquidAI 고정 fixture comparison을 추가하는 것이다.

## 2026-08-28 V23 device-acceptance automated readiness

개인 session, memo, schedule, feed, canonical row와 Apply를 사용하지 않는 live app-shell 재검증을
수행했다. Trusted private origin의 root와 manifest는 HTTP 200이었고 manifest는 `name: Personal Memo`,
`id: /`, `display: standalone`, `scope: /`, `start_url: /`를 유지했다. 192x192/512x512 PNG icon은
각각 HTTP 200과 `image/png`, service worker는 HTTP 200과 JavaScript로 제공됐다. Current source에는
safe-area inset 참조가 47개 있으며, Cloudflare service는 계속 `Stopped`/`Manual`, `cloudflared`
process는 0이었다.

따라서 자동 readiness는 `PASS`지만 이는 실제 Galaxy S24 Ultra의 홈 화면 설치, standalone 재실행,
cutout/회전, soft keyboard focus/scroll 또는 authenticated private sharing mutation을 대신하지 않는다.
`DEVICE_INSTALL_ACCEPTANCE`는 아래 네 항목을 사용자가 직접 확인하기 전까지
`USER_CHECK_REQUIRED`다.

## 사용자 확인이 필요한 마지막 단계

2026-08-08 실제 Galaxy S24 Ultra에서 private-LAN CA trust, 로그인과 primary flow는 이미 검증됐다.
V23 personal backup/restore rehearsal, migration/rebuild와 private-route verification이 끝났으므로
사용자가 실제 Android Chrome에서 확인할 항목은 다음 네 가지다.

1. Chrome 메뉴에서 PWA를 홈 화면에 설치하고 standalone으로 다시 실행한다.
2. 세로와 가로 방향에서 cutout/safe-area 때문에 버튼이나 dialog가 잘리지 않는지 확인한다.
3. 화면 키보드를 연 상태에서 원문 입력, 제안 수정, 승인 버튼까지 focus와 scroll이 유지되는지
   확인한다.
4. 본인이 공개해도 되는 테스트 일정 하나로 calendar sharing dialog의 생성·복사·rotate·revoke를
   확인한다. 현재 주소는 private-LAN 전용이며 외부 구독 주소로 전달하지 않는다.

따라서 `CURRENT_PERSONAL_V23_ROLLOUT_DECISION`은 `GO_TO_DEVICE_ACCEPTANCE`다. V23 personal stack과 health는
검증됐지만 자동 qualification이나 disposable synthetic smoke가 실제 개인 memo 정확도 또는 실제
기기 UI 조작을 대신 완료했다고 주장하지 않는다. 위 네 항목이 통과하면 같은 좁은 범위의 current
private-beta 결정을 `GO`로 닫을 수 있다.
문제가 발견되면 해당 화면만 재현해 관련 검증 묶음을 다시 실행한다.

## 이 결정에 포함되지 않는 것

다음은 현재 personal V23 `GO_TO_DEVICE_ACCEPTANCE`를 확장하지 않는다.

- 공개 self-service, multi-user, internet-facing 배포
- untrusted/public Wi-Fi, router port forwarding 또는 DMZ
- password recovery delivery, 공개 Google onboarding, MFA/passkey, 자동 계정 삭제
- personal overlay 밖의 local model, 모든 cloud model/provider, RAG/vector/embedding ingestion,
  automatic rule promotion, training, fine-tuning 또는 LoRA
- 알람/reminder persistence와 delivery
- proposal-v3 producer activation, non-null EVENT schedule preselection, human EVENT label gate와
  그 accuracy claim (dark-compatible contract/validator만 source에 존재)
- 실제 개인 일정으로 6B snapshot preview/download 또는 6C feed 생성·membership·rotate·revoke를
  수행하는 device acceptance
- 외부 일정 공유용 public feed origin/edge와 Google/Apple import/subscription/update/removal smoke
- 완전한 offline mutation outbox, Web Push, 자동 backup schedule/retention
- 공개 TLS edge, 중앙 monitoring/alerting, immutable signed release 운영

이 중 하나를 시작하려면 별도 요구, 위협 경계와 release decision을 먼저 기록한다.

## 2026-08-28 분석 경로 진단 source qualification

개인 stack을 갱신하지 않고 owner-scoped read-only
`GET /api/v1/analysis-path-evidence/summary`와 접힌 상태에서 시작하는 “분석 경로 진단” UI를
source에 추가했다. 집계는 raw memo, proposal/selection/evidence JSON, ID, hash, offset, token,
provider/model descriptor와 per-run 값을 반환하지 않는다. 설정 경로와 로컬 모델 경로의 성공 결과
반영 기록도 실제 물리적 모델 호출, 정확도 또는 개선을 증명하지 않는다. UI는 최초로 펼치기 전에는
요청 0회, 첫 펼치기 1회, 닫았다 다시 열 때 추가 요청 0회, 명시적 새로고침 때만 추가 1회다.

일회용 PostgreSQL/Flyway V1–V23 환경에서 backend 전체 `126 suites / 905 tests`
(`failures 0`, `errors 0`, `skipped 1`)와 SpotBugs 0건을 통과했다. Frontend는
`46 files / 465 tests`, lint, TypeScript build, PWA production build를 통과했고, 일회용 제품 stack의
targeted Playwright는 lazy request 계약 `1/1`을 통과했다. OpenAPI lint도 통과했다. 검증 project의
container/network/volume/image와 이번 검증에서 내려받은 Playwright browser cache만 scoped cleanup했고,
기존 personal container 세 개의 ID/health는 전후 동일했다.

이 milestone은 개인 memo/session/PostgreSQL/canonical row를 읽거나 바꾸지 않았고 Apply, Ollama/model
호출, RAG, 학습, fine-tuning, LoRA, 자동 rule promotion을 실행하지 않았다. 따라서 새 latency,
GPU/VRAM 또는 Fake/LiquidAI 품질 수치를 만들지 않는다. Source qualification 시점에는 설치된 개인
앱에 아직 배포되지 않았으며 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`였다. 후속 개인 반영은 아래의
별도 owner-authorized backup/rebuild/update와 bounded non-authenticated product smoke로 수행했다.

## 2026-08-30 owner-authorized 분석 경로 진단 personal deployment

- preflight는 기존 frontend/backend image
  `sha256:d02f6339433ff30778328a99968b0f46e92163c2406818fd983f420ad2c100e2` /
  `sha256:434dd1b33886d3f018c1f85c61f36be8e56e1191f361c16679eb780721fb5405`, exact
  PostgreSQL volume `personal-memo-private-win_personal-memo-postgres`, Flyway V23/failed 0과 세 service
  health를 확인했다. 이전 이미지는 각각
  `rollback-pre-analysis-path-20260830-021222Z` tag로 보존했다.
- frontend/backend writer만 중지하고 만든 fresh custom-format backup은
  `personal-memo-20260830-021312804Z.dump`, `144430` bytes, SHA-256
  `33F5F606C104D885F47BCA806DB7D62EA1B6F154762751D248B8EAA598E6D726`다. Live Spring Session table
  data는 제외했고 dump 내용은 열람하지 않았다.
- exact backup의 disposable V23→V23 restore rehearsal project
  `personal-memo-restore-20260830021355-cb571cb4`는 current backend health와 Flyway V23을 통과했다.
  Session truncate는 restored copy에만 적용했고, 성공 뒤 project container/network/volume과 temporary
  backend image를 정확히 제거했다.
- live rebuild는 base+production+personal Compose만 사용했고 migration 없이 V23을 유지했다. 새
  frontend/backend image는
  `sha256:510e19f697c63c2dae0c1b23b59f06fa18e2775048ece7fb0ddb704d95e33cdc` /
  `sha256:26b0219a53e85d09e9f079f58a3ea3420d96fc9761db1bf15d81344d11eb1745`이며 각각
  `deployed-analysis-path-20260830-0216Z` tag로 보존했다. Frontend image build는 lint,
  `46 files / 465 tests`, TypeScript와 PWA production build를 다시 통과했다.
- postflight는 세 service health, trusted private HTTPS, app shell/manifest/service worker 200, Flyway
  V23/failed 0, 동일 volume, 새 controller class와 frontend endpoint/UI label artifact 포함을 통과했다.
  비인증 summary GET은 401/no-store였고 유일한 cookie name은 session이 아닌 `XSRF-TOKEN`이었다.
  Synthetic unknown-token private-feed GET/HEAD도 empty 404/no-store였고 owned log에 query/token이 없었다.
- Postflight/live smoke에서는 실제 owner session으로 summary 200이나 memo/feed/event/canonical API를
  호출하거나 row 내용을 열람하지 않았고, Apply·calendar mutation·public overlay를 실행하지 않았다.
  Hardened
  `PersonalMemoCalendarCloudflareTunnel` service는 `Stopped`/`Manual`, generic `cloudflared` service는
  absent였고 Cloudflare process와 `8787`/`49312` listener, Ollama process와 `11434` listener는
  postflight에서 모두 0이었다. 새 모델
  평가·latency·GPU/VRAM·Fake/LiquidAI 비교는 `NOT_RUN_NO_CLAIM`; training/fine-tuning/LoRA는 `NO_GO`,
  RAG는 미사용이고 automatic Apply는 계속 false다.
- Windows build 26200의 Docker Desktop AF_UNIX startup bug 복구 중 만든 runtime-only 격리 directory
  `%LOCALAPPDATA%\Docker\run.stale-20260829-203447`,
  `%LOCALAPPDATA%\Docker\run.prestart-20260830-110956`,
  `%LOCALAPPDATA%\docker-secrets-engine.stale-20260829-203815`에는 합계 5개의 0-byte
  socket reparse point만 남아 있다. 활성 Docker runtime, 개인 image/volume/backup과 분리되어 있고,
  Windows가 Docker active 상태에서 child 삭제를 거절해 강제 삭제하지 않았다.

이 반영은 source-qualified raw-free diagnostic을 현재 개인 PWA/API artifact에 배포했다는 증거다.
인증된 owner aggregate 200과 실제 device UI 표시는 사용자가 앱 업데이트 후 별도로 확인해야 하며,
모델 정확도나 호출 횟수 증거로 해석하지 않는다. 상태는 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY`다.

## 2026-08-30 Access-gated remote app source milestone

설정된 `calendar.<owner-zone>` 일정 공유 경계는 유지하고 전체 앱용 별도 loopback edge, Compose overlay,
Cloudflare connector 설치/시작/중지 guard와 ADR 0018을 추가했다. 앱 edge는 exact host, same-origin unsafe
request, 허용 method/path/header, body/rate/connection/timeout, fixed-class log, CSP/HSTS/no-store를
강제하며 calendar feed, actuator, internal path, registration과 초기 Google/OAuth를 차단한다. 기존
Spring session/CSRF/owner 및 JSON Schema/domain validation과 manual Apply가 두 번째 권위 경계다.
Raw Cookie 전달도 차단하고 bounded `SESSION`/`XSRF-TOKEN`만 재구성하므로 `CF_Authorization`과 임의
cookie는 origin에 전달되지 않는다. Frontend의 request-scoped error log도 raw target을 남기지 않는
`emerg` 경계로 제한했다.

최종 disposable smoke는 exact/wrong/missing/case/port Host, same-origin 및 잘못된 Origin,
GET/POST/PATCH/DELETE와 OPTIONS/PUT/TRACE 차단, 허용 header/cookie 보존, forwarding/Access header 및
forbidden cookie 제거, 256 KiB body 경계, no-CORS/security/cache/log sentinel을 통과했다. Bounded rate
120회에서 `200=23`, `429=97`을 관측했다. CONNECT는 browser Fetch가 금지하고 authority-form이 별도
parser 경계이므로 probe하지 않았으며 Cloudflare perimeter 동작을 주장하지 않는다. Synthetic
container/network/volume/image와 port 18788 listener는 모두 정리됐다.
추가 adversarial review 뒤 literal/percent-encoded semicolon registration matrix path도 bodyless
404/no-store로 닫았고, hash-shaped missing asset도 no-store임을 확인했다. Production edge start source는
기존 frontend image/network snapshot과 실패 복원, exact project/container/port/network/local probe를
강제하지만 실제 personal rollback 실행 증거는 아직 없다.

이 source/local synthetic 단계는 개인 memo/session/PostgreSQL/canonical data를 읽거나 변경하지 않고,
Cloudflare live connector를 시작하지 않는다. 실제 앱 route를 열기 전에는 앱 hostname, Access exact-owner
email/IdP, default-deny/no-bypass/cache-bypass와 Cloudflare가 password/cookie/raw memo/canonical API traffic의
TLS 처리자가 된다는 개인정보 경계를 사용자가 명시 확인해야 한다. 따라서 현재 판정은
`SOURCE_PREFLIGHT`, `SOLO_PROVISIONAL/REPORT_ONLY`; live activation은 해당 확인 전 `NO_GO`다.

## 2026-08-30 Access-gated owner-only live qualification

위 source-only snapshot 이후 owner는 설정된 `memo.<owner-zone>` hostname, exact-owner email OTP, default-deny/Protect with
Access, entire-host cache bypass, Cloudflare TLS 처리 경계와 connector-first rollback을 명시 승인했다.
별도 `personal-memo-app` Tunnel과 protected token-file-only Windows service를 설치했고 startup은
`Manual`로 유지했다. Token 값, service command line, 개인 memo/session/PostgreSQL/canonical/API/Apply는
검사하거나 기록하지 않았다.

Windows PowerShell 5.1이 Docker Go-template 안의 quoted `8080/tcp`를 손상시키는 activation 오류는 전체
ports object를 읽어 exact key/binding을 PowerShell에서 검증하도록 수정했다. Zero/one-item array 축약과
native stderr 조기 `NativeCommandError`도 bounded array/error 처리로 닫았고, source contract와 실제
Windows PowerShell 5.1 read-only Docker inspection이 통과했다.

Live activation 뒤 다음 bounded evidence를 확인했다.

- service는 `Running`/`Manual`, metrics `127.0.0.1:49313`, local `/ready=200`, `/diag/tunnel=200`이고
  연결 4개가 모두 connected였다.
- Cloudflare Dashboard는 `personal-memo-app`을 `Healthy`, active replica 1, route 1로 표시했다.
- cookie 없는 외부 요청은 `302`로 Cloudflare Access hostname에 redirect됐고 `CF-Cache-Status`는
  없었다. 이미 Access 인증된 browser에서는 앱 로그인 shell과 비식별
  `/api/v1/auth/capabilities` 응답만 확인했다.
- connector-first rollback에서 edge는 실행 상태로 두고 connector만 `Stopped`/`Manual`로 수렴시켰다.
  같은 Access 인증 browser가 cache/service-worker를 우회하도록 unique query를 붙여 capability를 새로
  요청했을 때 app JSON이나 cached shell이 아니라 Cloudflare Tunnel `Error 1033`이 반환됐다.
- 같은 reviewed gate로 connector를 재시작한 뒤 capability JSON, Dashboard `Healthy`, active replica 1,
  local connected 4개와 미인증 Access redirect가 다시 확인됐다. 최종 상태는 `Running`/`Manual`이다.

2026-08-31 exact owner는 외부 email-OTP, application sign-in과 설정된 owner PWA hostname의 화면 로드가
성공했다고 직접 보고했다. 이는 user-reported acceptance이며 provider/customer log나 별도
자동화가 재현한 증거로 확장하지 않는다. Application-session이 없는 `/auth/me` 401/no-store
확인과 provider/customer request-log sentinel은 계속 unavailable/unverified다.

후속 PC 재부팅 뒤 Manual connector와 `app-public-edge`는 현재 중지 상태이며 외부 route는
검토된 edge-first, connector-last 시작을 다시 실행하기 전까지 사용할 수 없다. 이 현재 runtime
상태는 2026-08-30/31의 bounded qualification과 user acceptance를 취소하지 않는다. 따라서
qualification은 `LIVE_OWNER_BETA`, `SOLO_PROVISIONAL/REPORT_ONLY` 상태를 유지하고 unrestricted public
self-service와 production readiness는 계속 `NO_GO`다.

## 2026-09-01 Access control-path service-worker qualification and activation hold

Cloudflare Dashboard read-only review에서 exact owner-only/default-deny Access, 전체 host/path protected
Tunnel route, catch-all 404, Protect with Access와 entire-host cache bypass가 모두 유지됨을 확인했다.
설정 변경 없이 app connector를 시작하자 Tunnel은 `Healthy`가 됐지만, Access authorization callback에서
기존 설치 PWA service worker가 Cloudflare network response 대신 cached app shell/offline UI를 반환했다.
이번 acceptance는 완료되지 않았으며 connector-first rollback으로 app/calendar service를
`Stopped`/`Manual`, local `cloudflared` process를 0으로 복구했다. `app-public-edge`는 healthy loopback으로
유지했다.

원인은 app-owned API/OAuth 경계에는 있던 service-worker network-only 규칙에 provider-owned
`/cdn-cgi/access` namespace가 빠진 것이었다. Shared case-insensitive
`^/cdn-cgi/access(?:/|\?|$)` 경계와 positive/query/case/near-match unit, offline fetch와 top-level
navigation E2E를 추가했다. ESLint, TypeScript, 48 files/472 tests, public-app source contract,
production PWA build, generated `sw.js` deny-list/`NetworkOnly` inspection과 disposable system-Edge E2E
1/1이 통과했다. 검증용 synthetic PostgreSQL/app container, network, volume과 image는 모두 제거했다.

Owner가 fresh private browser의 `/api/v1/health` OTP flow 뒤 cached shell이 아닌 Cloudflare Tunnel
`Error 1033`을 보고해 rollback `MUTATION_HOLD`가 해제됐다. 이전 frontend/app-edge image에는 같은 UTC
stamp의 `rollback-pre-access-sw-20260901-042111Z` tag를 붙였다. Connector가 stopped인 상태에서 current
source를 다시 build했고 frontend lint, 48 files/472 tests, production PWA build, isolated Nginx config,
exact/wrong-Host local edge contract가 통과했다. 새 frontend/app-edge에는
`deployed-access-sw-20260901-042405Z` tag를 붙였다.

배포된 loopback artifact는 root와 `sw.js` 200, Access `NetworkOnly` marker, health exact UP 200,
3-boolean auth capabilities 200, unauthenticated `/auth/me` 401 `AUTHENTICATION_REQUIRED`와 모든 dynamic
response no-store를 통과했다. App connector를 마지막에 시작한 뒤 service는 `Running`/`Manual`, calendar
service는 `Stopped`/`Manual`, cloudflared process 1, metrics ready 200이고 모든 personal Docker service가
healthy다. Cookie/body 없는 외부 `/`와 unique-query health는 모두 Access 302, no-store/private,
`CF-Cache-Status` absent를 통과했다.

개인 memo, owner session, PostgreSQL/canonical data, Apply, model/Ollama와 Cloudflare 설정은 읽거나
변경하지 않았다. Old-worker client의 broad site-data clear는 owner draft/session 손실 위험 때문에
허용하지 않는다. 이어서 exact owner는 안내한 fresh private-browser health, root, application/PWA
재확인이 정상 작동한다고 보고했다. 이는 user-reported acceptance이며 provider/customer log나 독립
자동화 증거로 확장하지 않는다. 2026-08-30/31 historical `LIVE_OWNER_BETA` evidence는 보존하고 current
activation은 `LIVE_OWNER_BETA_REQUALIFIED`다. Overall status는 `SOLO_PROVISIONAL/REPORT_ONLY`,
unrestricted public/production은 `NO_GO`다.

## Milestone 7.1 Today-first mobile home checkpoint

다음 source slice는 빠른 메모 입력과 오늘의 할 일·확정 일정을 mobile home의 첫 스캔 경로로
두고 graph를 보조 탐색 화면으로 내린다. 상태 요약은 이미 browser에 있는 connection/recovery/
loading/error state만 읽어 원문 저장 가능, 원문만 저장 가능, 일부 기능 문제, 연결 필요 등을
표시한다. 이 요약은 database, Ollama, Cloudflare Tunnel, Access, provider health를 주장하지
않는 read-only UI다.

M7.1은 API/OpenAPI/JSON Schema/Flyway/canonical 계약을 바꾸지 않고 automatic Apply를 추가하지 않는다.
Browser에 Windows service, Docker, Cloudflare connector/metrics, token 시작·중지 제어도 넣지 않는다.
현재 상태는 `SOURCE_QUALIFIED_LINUX_FULL_E2E_PASS_DEPLOYMENT_PENDING`이다. Frontend lint,
TypeScript, 48 files/472 unit tests와 production PWA build는 통과했다. Production PWA를 임시
loopback에서 열고 synthetic API만 사용한 Chrome test도 384×854/854×384 Today-first shell, 기존
capture/connection 표시와 horizontal overflow 0을 통과했다. 임시 preview와 Playwright artifact는
종료·제거했다. 이어서 commit `19ce1fbc49744ba9c6dbefbc313e48b36e5c81e6`의 GitHub Actions push
run `33358387450`와 pull-request run `33358390766`이 API/OpenAPI, production Compose, Windows
PowerShell source contracts, frontend, backend와 disposable Ubuntu production-like stack을 통과했다.
Stack readiness 뒤 primary/OAuth-state E2E 26건이 통과했고 exact stack cleanup도 통과했다.

2026-08-31 bounded requalification에서 Node 24.19.0, ESLint 9.39.2, Vitest 4.1.10,
TypeScript 5.9.3, Vite 7.3.6, Playwright 1.60.0을 사용했다. Lint 0 error, 48 files/472 tests,
production PWA build, Edge synthetic 384×854/854×384 1/1과 horizontal overflow 0을 다시
통과했다. 631.21 kB JavaScript chunk warning은 남았지만 실패는 아니다. 개인 메모,
PostgreSQL, canonical data, 실제 API, Apply, Docker는 사용하지 않았고 임시 preview는 종료했다.
검증된 M7.1 code/test 파일 8개는 owner-only 로컬 source checkpoint로 별도 보존했으며,
압축파일 SHA-256은 `DEB5C332820417BDEC22C9BD76EF1BEE52C8AF82E65EC557A11579D87C13F563`이다.
그 뒤 source-only commit과 Draft PR로 선별돼 checkout/head/upstream 일치와 전체 CI 통과를
확인했다. 이는 Windows Docker Desktop defect 해결, 개인 V23 backup/rebuild/update, owner session이나
canonical data 동작, Cloudflare runtime, 실제 S24 UI 또는 모델 품질 증거가 아니다. 앱 내 화면
업데이트 확인 전에는 배포 acceptance를 주장하지 않으며 상태는 `SOLO_PROVISIONAL/REPORT_ONLY`다.

## 2026-09-01 Milestone 7.2 graph-first personal deployment

Milestone 7.2는 confirmed bounded MEMO-TAG graph를 기본 화면으로 만들고 하단 내비게이션을 연결,
메모, 일정, 설정으로 나눴다. 메모 화면은 raw-save-first capture, 원문 목록과 검색을 유지하고 일정
화면은 task와 confirmed event를 유지한다. AI/model/recovery/infrastructure 진단은 설정의 보조 경로로
이동했다. API, OpenAPI, JSON Schema, Flyway, persistence, analyzer policy, canonical mutation과 배포
topology는 바꾸지 않았다.

Source gate는 ESLint, TypeScript, 51 files/481 tests, production PWA build와 public-app source contract를
통과했다. 별도 synthetic project의 production-like Playwright 27/27은 graph-first shell, portrait/
landscape와 horizontal overflow, raw capture, review/Apply/undo, graph neighborhood와 exact cleanup을
통과했다. Personal memo, owner session, PostgreSQL row, canonical record와 Apply를 사용하지 않았다.

별도 owner 승인으로 144,430-byte mechanical PostgreSQL backup과 checksum을 만들고 disposable
V23-to-V23 restore rehearsal을 통과했다. 기존 canonical volume은 삭제·교체하지 않았다. App connector를
먼저 중지하고 owner가 fresh private browser에서 Error 1033을 확인한 뒤, 기존 frontend/app-edge를
`rollback-pre-m72-20260901-074019Z`로 보존했다. Current source에서 frontend와 app edge만 rebuild했고
실행 이미지는 `deployed-m72-20260901-075555Z`로 고정했다.

Loopback exact-Host root와 `sw.js`, health UP, exact three-boolean capabilities, cookie 없는 `/auth/me`
401 `AUTHENTICATION_REQUIRED`, wrong-Host bodyless 404가 통과했다. 익명 401 응답은 XSRF cookie만
발급했고 owner session cookie는 없었다. Connector-last start 뒤 app service는 `Running`/`Manual`,
calendar service는 `Stopped`/`Manual`, cloudflared process와 metrics listener는 각각 1개이며 네 personal
container가 모두 healthy였다. Cookie/body 없는 remote root와 health는 Cloudflare Access 302,
no-store/private, cache non-HIT를 통과했다. Restore/E2E temporary container와 volume은 0개였다.

사용자는 배포된 화면을 직접 보고 시각적 유사성과 제품 차이를 검토하기 시작했다. 이는 최종 Android
physical-device acceptance가 아니다. Personal memo body, owner session, canonical data, Apply, Ollama와
Cloudflare 설정은 읽거나 변경하지 않았다. 현재 판정은
`SOURCE_QUALIFIED_PERSONAL_DEPLOYED_VISUAL_REVIEW_IN_PROGRESS`, `SOLO_PROVISIONAL/REPORT_ONLY`이며
unrestricted public/production은 `NO_GO`다.
