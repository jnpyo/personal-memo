# Personal Memo — Codex handoff

## 1. 목표

모바일에서 사용자가 형식 없이 적은 짧은 메모를 분석하여 다음을 제안하고, 사용자가 승인한 결과만 지식 그래프와 실행 항목으로 반영하는 웹/PWA를 만든다.

- 메모 유형: 할 일, 일정, 정보, 아이디어, 기록
- 속성: 제목, 날짜·시간, 행동, 대상, 인물·과목·프로젝트 등의 개체
- 관계: 기존 태그, 관련 메모, 상위·하위 주제
- 실행 항목: 할 일, 기한 상태, 알림
- 탐색: 그래프 홈, 검색, 오래된 노드 압축

제품을 한 문장으로 표현하면 다음과 같다.

> 대충 적은 메모를 AI가 지식과 할 일로 정리하고, 시간이 흐르면서 스스로 정돈되는 개인 지식 그래프

## 2. 가장 중요한 설계 원칙

1. **원본 메모가 유일한 원본(source of truth)이다.** AI는 원문을 덮어쓰지 않는다.
2. **AI 결과는 제안이다.** 사용자가 승인하기 전에는 태그·할 일·알림·관계를 확정하지 않는다.
3. **입력은 자유 형식이지만 출력은 버전이 있는 구조화 스키마다.**
4. **semantic 판단과 model invocation을 분리하고 외부 전송은 별도 동의 gate 뒤에 둔다.** 기본 runtime은 서버의 결정론적 `FakeAnalyzer`, `NO_NETWORK` Fake gateway와 `UNCERTAINTY_ONLY`다. 개인 overlay만 `AI_PREFERRED`로 고정 localhost LiquidAI semantic-patch gateway를 모든 검증된 memo에 사용하며, 실제 cloud Agent는 연결하지 않는다.
5. **로컬 모델의 self-confidence를 그대로 신뢰하지 않는다.** 분류 마진, 태그 유사도, 날짜 완전성, 미해결 지시어 등으로 필드별 모호성을 계산한다.
6. **그래프는 저장 포맷이 아니라 데이터의 투영(view)이다.** 메모·파생 항목·태그·관계가 원본 데이터다.
7. **압축은 가역적이어야 한다.** 오래된 노드를 화면에서 접거나 요약하되 원본을 삭제·병합하지 않는다.
8. **증분 처리한다.** 새 메모가 들어올 때 전체 메모를 재분석하거나 모든 쌍을 비교하지 않는다.
9. **Agent 도구는 읽기 위주로 제한한다.** 승인 전에는 삭제·대량 수정·알림 생성 도구를 제공하지 않는다.
10. **오프라인에서도 작성 중 원문을 잃지 않는다.** owner별 로컬 draft는 보존하되, canonical memo 생성과 완전한 동기화 대기열은 구분한다.

## 3. 권장 기술 구성

### 클라이언트

- React + TypeScript
- Vite 기반 PWA
- React Flow 기반 그래프
- owner별 임시 캡처 draft 보존; 완전한 오프라인 동기화 대기열은 P1
- Web Worker 안에서 로컬 분석 실행
- 로컬 추론 런타임은 인터페이스로 추상화하고, 초기에는 mock/deterministic analyzer로 시작
- 이후 ONNX Runtime Web 또는 동급 런타임 연결

### 백엔드

- Spring Boot
- Spring Security
- PostgreSQL
- Flyway 데이터베이스 마이그레이션
- LLM provider abstraction + 구조화 출력 검증
- 초기 검색은 PostgreSQL 전문 검색, 의미 검색은 후속 단계에서 pgvector 검토
- 알림은 DB-backed scheduler + Web Push

### 개발환경

- Docker Compose로 PostgreSQL과 백엔드 실행
- 프론트엔드와 백엔드는 독립 실행 가능하게 유지
- 루트에서 공통 개발 명령과 환경변수 예시 제공

특정 라이브러리의 버전은 구현 시작 시 현재 안정 버전을 확인해서 고정한다. 문서에 임의의 버전을 추정해서 넣지 않는다.

## 4. 처리 흐름

```text
사용자 메모 입력
→ 작성 중 원문을 owner별 로컬 draft로 즉시 보존
→ Fake 날짜 파서 + 유형/항목 후보 + JSON Schema/domain 검증
→ 필드별 semantic 모호성 판별과 model invocation policy를 분리
   ├─ 기본: UNCERTAINTY_ONLY, clear는 model 0-call, 모호하면 NO_NETWORK Fake 보완
   └─ 개인 overlay: AI_PREFERRED, 모든 검증된 memo를 exact localhost LiquidAI KEEP/PATCH로 보완
→ 사용자 승인·수정
→ 백엔드 검증
→ 명시적 Apply만 태그·관계·할 일 반영
→ 그래프 투영 업데이트
```

external cloud Agent는 연결하지 않는다. personal model은 current immutable revision과 bounded tag
hint, 최대 K=3 current-anchor/type hint만 exact pinned localhost Ollama에 받는다. historical raw memo,
selection, identifier와 related-memo/vector/embedding/RAG corpus는 제공하지 않는다. 알람/reminder
persistence와 delivery는 별도 미구현 slice다.

## 5. MVP 범위(P0)

MVP는 아래의 수직 흐름 하나를 완성하는 데 집중한다.

1. 자체 email/password 또는 선택적 Google OIDC 인증으로 생성한 서버 세션
2. 자유 텍스트 메모 작성·수정·삭제
3. 작성 중 원문의 owner별 로컬 draft 보존
4. deterministic/mock 로컬 분석기
5. 유형·날짜·기존 태그 후보와 모호성 결과
6. 분석 후보 선택·수정·거부
7. 승인된 태그·관계·할 일 저장
8. 그래프 홈과 노드 상세 패널
9. 할 일 목록, 완료, 기한 초과
10. AI 적용 이력과 1단계 되돌리기

MVP에서 제외한다.

- 음성·이미지 메모
- 양방향 외부 캘린더 동기화·외부 수정/import
- 반복 일정
- 다중 사용자 협업
- 완전 자동 태그 병합·분리
- 전체 그래프 AI 재구성
- 고급 의미 검색
- AI 요약 캡슐의 자동 확정

## 6. 구현 단계

### Phase 0 — 프로젝트 기반

- 모노레포 또는 명확한 `frontend/`, `backend/` 구조 선택
- Docker Compose, PostgreSQL, 환경변수 예시, 기본 CI
- 공통 UUID, 시간대, 오류 응답 규칙 정의

### Phase 1 — AI 없는 완전한 수직 흐름

- 원문 메모 저장
- mock 분석 결과 생성
- 후보 확인 UI
- 태그·할 일 적용
- 그래프 투영
- 이 단계에서 데이터 모델과 UX를 먼저 검증한다.

### Phase 2 — 결정적 분석과 클라우드 보완

- 날짜·시간 파서
- 필드별 ambiguity gate
- 구조화 분석 스키마
- 읽기 전용 검색 도구
- 도구 호출 수·시간·토큰 제한
- 프롬프트 인젝션 방어
- 실패 시 로컬 결과 fallback
- 실제 모델이 없어도 fixture와 fake provider로 평가 가능하게 만든다.
- 별도 explicit test runner는 이미 설치된 localhost 모델을 공개 synthetic fixture에만 shadow
  평가하며 제품 DB/API/Apply 경로와 분리한다.

### Phase 3 — 온디바이스 분석

- Web Worker 기반 분석 인터페이스
- 유형 분류와 임베딩 어댑터
- 태그 대표 벡터 동기화
- WebGPU/WASM/cloud/pending fallback
- 기준 휴대폰 벤치마크 후 모델 확정

### Phase 4 — 알림·오프라인 동기화

- IndexedDB outbox
- idempotency key
- Web Push 구독
- DB-backed reminder dispatch와 재시도
- EVENT 알림은 Phase 6의 canonical start/end/all-day/time-zone 계약 뒤에만 연결하고, TASK due와
  EVENT start를 섞거나 누락된 날짜·종료·duration을 발명하지 않는다.

### Phase 5 — 태그 진화·노드 압축

- provisional tag
- canonical tag와 alias
- 태그 centroid 증분 갱신
- 배치 기반 merge/split/archive 제안
- 기간·주제·상태 기반 가역적 cluster node

### Phase 6 — 명시적 일정과 선택적 읽기 전용 iCalendar 공유

- **6A.1 source 구현:** proposal v2는 그대로 두고 EVENT review를 schedule null로 시작한다. 사용자가
  usable precise date candidate를 직접 고르거나 schedule을 직접 입력한 경우에만 Apply
  `selectionSchemaVersion: "2"`와 V21 `event_details`로 canonicalize한다. bounded owner-scoped
  `GET /events`와 PWA confirmed schedule list까지 포함하고 title-only EVENT는 그대로 허용한다.
  TIMED offset은 revision source zone과 일치해야 하며 DST gap은 거절하고 overlap의 explicit valid
  offset은 보존한다.
- **6A.2a source 준비:** proposal v3는 ID 기반 bounded EVENT schedule alternatives, explicit
  timed/all-day mode, optional end, inclusive/exclusive all-day-end semantics를 dark-compatible하게
  검증·down-project·표시한다. Current Fake/Ollama producer는 v2이고 review는 항상 schedule null로
  시작하며 non-null analyzer suggestion은 domain에서 거절한다.
- **6A.2b 보류:** 독립 human policy/2인 label/adjudication, 사전 고정 metric threshold와 held-out
  release 뒤에만 analyzer/model schedule preselection 또는 binding-quality claim을 검토한다.
- **6B source 구현:** session/expected-owner-guarded `GET /events/calendar.ics`를 plain-text로
  미리보고 정확히 같은 no-store Blob을 내려받는다. Current eligible EVENT만 최대 100개, 완성
  UTF-8 문서 최대 128 KiB로 제한하고 opaque UID/UTC/all-day/CRLF/escaping/folding을 fail closed로
  직렬화한다. 이는 one-time import snapshot이며 share state나 public URL을 만들지 않는다.
- 사용자가 명시적으로 승인한 canonical scheduled EVENT만 미래 공유 대상으로 삼으며 TASK due와
  기존 title-only EVENT를 자동으로 일정화하거나 공개하지 않는다.
- **6C source 구현:** recipient별 `TITLE`/기본 `BUSY_ONLY` feed, client-generated 256-bit secret의
  digest-only verifier, rotate/revoke, 명시적 event membership, recipient-only UID/sequence와
  cancellation tombstone을 V22에 둔다. URL은 생성/rotate 성공 직후 PWA memory에서 한 번만 조립한다.
  Fixed query-token GET/HEAD는 application session과 분리된 stateless chain이며 읽기 DB write는 0이다.
- **6D.1 source 구현:** authenticated no-store `GET /calendar-feeds/capabilities`는 server-owned
  public feed origin/policy를 exact `LOCAL_ONLY`/null/null 또는 `PUBLIC_HTTPS`/strict HTTPS multi-label ASCII hostname/current-policy union으로
  표현한다. Backend는 disabled/blank를 기본으로 하고 invalid enabled 설정에서 startup을 거절한다.
  Public origin은 lowercase/maximum 255자이며 `localhost` 및 `*.localhost`, IP, explicit `:443`, URL suffix를
  거절한다. 이 syntax gate는 public-suffix 소유권이나 DNS reachability 증명이 아니다. Strict PWA는
  PUBLIC_HTTPS에서만 server origin을 쓰고, valid LOCAL_ONLY에는 외부 전달 금지
  경고가 있는 local/isolated URL만 만든다. Failed/malformed capability는 조용히 local fallback하지 않는다.
- **V23 explicit public-consent source 구현:** 모든 기존/new feed는 `LOCAL_ONLY`와 null consent pin으로
  시작한다. External enable은 owner/CSRF/expected-version/idempotency, exact server policy와 fresh
  client bearer를 요구하고 verifier/scope/policy/time/version을 한 transaction에서 바꾼다. LOCAL
  deployment는 local rows만, PUBLIC deployment는 current-policy public rows만 제공하며 mismatch는 같은
  empty 404다. Public disclosure mode change는 재동의 전 fail closed하고 revoke는 scope/pin을 지운다.
  Personal database는 2026-08-28 owner-authorized backup/restore rehearsal 뒤 V23으로 전환됐다.
  Publication environment는 계속 0이고 public activation은 실행하지 않았다.
- **6D public-edge preflight source 구현:** 새 `6D.2` 번호 없이 별도 loopback-only
  `calendar-feed-edge`, edge-only Compose overlay, final activation overlay와 isolated synthetic smoke를
  둔다. Preflight는 backend를 계속 `LOCAL_ONLY`로 유지하고 PWA/API/backend host port/PostgreSQL을
  공개하지 않는다. Exact bodyless canonical-token GET/HEAD만 proxy하며 local/upstream failure는
  generic empty 404, rate rejection은 bodyless 429다. Log는 fixed safe route/method class만 남긴다.
- Recorded isolated smoke는 disposable upstream/generated bearer로 exact path/header/bound와
  query/path/header/custom-method sentinel log 0건을 통과했다. Personal PostgreSQL/session/memo/feed/
  canonical schedule/Apply는 사용하지 않았고 external operator log 증명은 아니다.
- Origin-side provisional bounds는 60r/m + burst 20, connection 8, proxy connect/send/read
  2s/5s/10s다. External per-client policy나 total external deadline/SLA가 아니다. 실제 공개 edge가
  별도 승인되면 전체 앱이 아니라 read-only calendar `GET`/`HEAD` 경로만 trusted HTTPS operator에
  연결하고 token이 frontend/backend/upstream/tunnel/edge success·error log에 남지 않게 한다.
- **6D operator decision:** Cloudflare의 remotely-managed named Tunnel을 사용한다. Cloudflare account와
  Cloudflare DNS에 올라간 owner-controlled domain/zone이 prerequisite이며 hostname은 single-label
  `calendar.<zone>`로 제한한다. Quick Tunnel/`*.trycloudflare.com`은 사용하지 않는다. Query bearer가
  Cloudflare 처리 경계에 들어가는 사실은 제거할 수 없으므로 customer logs는 query 없는
  `ClientRequestPath` 등 최소 field allow-list만 쓰고 `ClientRequestURI`, request headers/cookies/referer,
  `cloudflared` debug logging을 금지한다. Cache Rule은 exact host/path bypass이고 external smoke에서
  `CF-Cache-Status != HIT`를 증명한다. Cloudflare WAF rate limiting은 보조 방어이며 Tunnel 자체가 total
  10s deadline 또는 128 KiB hard response cap을 제공한다고 보지 않는다.
- 공식 Windows binary는 `DOWNLOADED_VERIFIED`: version `2026.8.2`, SHA-256
  `c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5`, Authenticode
  `Valid` / signer `Cloudflare, Inc.`다. Cloudflare login과 owner-controlled active zone을 확인했고 remote
  named Tunnel과 single-label published application/DNS, exact-path loopback route는 configured다.
  Hardened `PersonalMemoCalendarCloudflareTunnel` service는 protected token-file-only ImagePath에 inline
  token이 없음을 포함해 `Stopped`/`Manual`/`LocalSystem`으로 설치·검증했다. 일반 기본 `Cloudflared`
  service는 제거했고 현재 `cloudflared` process와 port `8787`/`49312` listener는 모두 0이다. Connector
  stop 뒤 Cloudflare status는 `Down`이며 activation은 `NO_GO`다.
- PS5 source는 Install/Start/Stop Cloudflare scripts, protected token-file + manifest, stopped Manual
  LocalSystem service contract를 구현한다. Installer hidden input은 raw token 또는 exact Windows
  `cloudflared.exe service install <token>` command 한 줄만 받아 token을 추출하고 malformed/multiline
  input은 거절한다. Start 직전 manifest/hash/actual version/Valid Cloudflare signer/
  ACL/reparse/ObjectName을 재검증하고 `warn`/transport `warn`, grace 30s, `127.0.0.1` metrics/diagnostic을
  강제한다. 실패하면 auto-stop하며 diagnostic과 stop 검증을 보존한다. Personal Start/Stop은 public
  topology가 active이면 fail closed한다. `Test-PersonalMemoCloudflareSourceContracts.ps1` PS5/source
  contract는 PASS다.
- External harness는 standalone `compose.public-feed.cloudflare-test.yaml`의 disposable
  `127.0.0.1:8787` origin을 prepare/qualify/connector+remote-replica-stopped cleanup 세 단계로 다룬다.
  Recorded external qualification은 strict non-secret receipt에 총 46 probe(exact positive 3, origin
  deny 8, remote catch-all deny 5, bounded rate attempt 30), cache `BYPASS 0`/`DYNAMIC 46`/`HIT 0`,
  maximum observed latency `873.816 ms`, owned-log/external-artifact-reflection sentinel PASS를 남겼다.
  Rate 429는 bounded attempts 안에서 `NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS`였다. Current account
  plan에서는 provider/customer log sentinel이 unavailable/unverified이고 receipt replica field는
  `REQUIRED_NOT_VERIFIED`다. 별도 dashboard에서 cleanup 전후 active replica 0/routes 1/status Down을
  수동 관측했지만 strict receipt proof를 대체하지 않는다. 따라서 decision은 `NO_GO`, status는
  `TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`다. Disposable containers/network/local image를
  제거했고 service/process/listener와 personal stack을 원복했다. Harness는 curl HEAD `size_download`
  판정과 output header block을 분리하고, impossible method marker를 HEAD marker + force-recreate log
  boundary로 교체했다.
- CalDAV, 외부 수정/import, recurrence, provider write, `VALARM`, automatic sharing은 제외한다.
- 6A.1/6B/6C, 6D.1/V23과 6D preflight는 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY` 기능 상태다. 개인 database는 2026-08-27
  owner-authorized 절차로 V22가 됐고, 2026-08-28 fresh backup/restore rehearsal 뒤 V23으로 전환됐다.
  6D.1 capability와 V23 feed-level consent gate는 public 설정 없는 `LOCAL_ONLY` 상태로 배포됐다.
  Preflight activation overlay는
  적용하지 않았다. 후속 owner 승인으로 remote Tunnel/route/DNS와
  stopped hardened service와 bounded external synthetic transport/cache/owned-log evidence까지만
  준비했다. 실제 activation, provider/customer log decision, real-feed qualification, 개인 canonical
  일정 smoke와 Google·Apple smoke는 새 승인 없이 실행하지 않는다.

## 7. 구현 전에 읽을 문서

- `docs/PRD.md`: 사용자 가치, UX, 범위
- `docs/REQUIREMENTS.md`: 기능·비기능 요구사항과 수용 기준
- `docs/ARCHITECTURE.md`: 컴포넌트와 처리 경계
- `docs/DATA_MODEL.md`: 초기 데이터 모델
- `docs/API.md`: 초안 API 계약
- `docs/AI_PIPELINE.md`: 로컬 분석, 모호성 판별, Agent 도구
- `docs/ROADMAP.md`: 단계별 작업 순서와 완료 조건
- `docs/OPEN_QUESTIONS.md`: 구현 중 임의 결정하면 안 되는 항목

## 8. 첫 구현 과제

Codex는 바로 실제 모델을 연결하지 말고 다음 세로 흐름부터 구현한다.

> 메모 작성 → mock 분석 후보 → 사용자 승인 → 태그/할 일 저장 → 그래프 표시

첫 체크포인트의 완료 조건은 다음과 같다.

- `11.25 OS과제 제출`을 입력할 수 있다.
- 원문이 AI 결과와 별도로 저장된다.
- mock analyzer가 `TASK`, 날짜 후보, `운영체제`, `과제` 후보를 반환한다.
- 사용자가 후보를 제거·수정·승인할 수 있다.
- 승인 후 할 일 목록과 그래프에 반영된다.
- 분석 적용을 취소하면 파생 데이터만 되돌아가고 원문은 남는다.

## 9. 품질 기준

- target device에서 warm local analysis p95 1초 이내를 목표로 한다.
- 클라우드 분석 p95 8초, 도구 호출 포함 12초 이내를 목표로 한다.
- AI 분석 실패가 메모 저장 실패로 이어지면 안 된다.
- 같은 동기화 요청이 재전송돼도 메모·할 일·알림이 중복 생성되면 안 된다.
- 첫 그래프는 50~150개의 의미 있는 노드만 렌더링한다.
- 최소 10,000개 개인 메모를 전체 재분석 없이 처리할 수 있는 구조로 만든다.
- 색상만으로 상태를 구분하지 않는다.

## 10. 제품 위험

다음 위험을 코드 구조에서 우선 방어한다.

- 잘못된 AI 분석이 반복 학습되어 그래프를 오염시키는 문제
- 유사 태그 폭증
- 로컬·클라우드 결과 불일치
- 모바일 모델 캐시 삭제와 WebGPU 미지원
- 그래프 과밀 및 force layout 재배치
- 알림 누락·중복
- 프롬프트 인젝션을 통한 도구 오용
- 오프라인 편집과 다중 기기 동기화 충돌
- 임베딩 모델 변경 시 벡터 비호환
- 확인 과정이 많아져 빠른 메모 경험이 사라지는 문제

## 11. 작업 원칙

- 작은 vertical slice를 끝까지 검증한 후 확장한다.
- 새로운 기능을 추가할 때 원본 보존·되돌리기·오프라인 대체 경로를 함께 고려한다.
- 문서와 API 스키마를 변경하면 관련 테스트와 예제도 갱신한다.
- 사용자 데이터가 있는 마이그레이션은 파괴적으로 작성하지 않는다.
- 실제 모델 선택은 독립적으로 adjudicate된 한국어 평가와 blind 근거로 결정한다. 공개 visible
  shadow 결과만으로 모델 선택이나 학습을 승인하지 않는다.

## 12. 현재 구현 체크포인트

2026-08-23 V20 source mechanical qualification은 isolated PostgreSQL에서 Flyway V1–V20,
backend 773 tests, Spotless, SpotBugs, frontend lint/301 tests/build, OpenAPI lint와 example-only
personal Compose render까지 `PASS_ISOLATED`였다. model accuracy qualification은 여전히
`NOT_RUN_NO_CLAIM`이다. 2026-08-24 owner 승인 뒤 exact personal stack을 checksummed custom-format으로
백업하고 별도 disposable restore rehearsal을 통과한 다음 personal PostgreSQL을 Flyway V18→V20으로
migration하고 image rebuild와 trusted health를 확인했다. product smoke는 개인 memo/DB가 아니라 fresh
disposable PostgreSQL과 synthetic memo에만 실행했으며 Apply/canonical item write는 없었다. 이전
adapter-v1 smoke/diagnostic의 malformed/length-bounded visible content는 strict validation에서 fail
closed했고, `ollama-local-gateway-v2+local-semantic-patch-v2` exact-sentence 재실행은 strict contract 아래 두 번 성공했다.
clean run은 gateway/wall 약 `4.596 s`/`4.713 s`, `SUCCESS`, `ACCEPTED_UNCHANGED`, tool/mutation 0이었다.
그 V20 smoke 당시 `fake-v8`/`korean-rules-v6` proposal은 `6시 디스코드 접속하기`를 TASK(action `접속하기`, object
`디스코드`)로 보존하되 날짜 없는 `6시`를 정밀 due나 알람으로 만들지 않는다. 이 좁은 compatibility
성공은 broad model quality 승인이 아니므로 authoritative provider `NO_GO`가 유지된다.
rollout은 `GO_TO_DEVICE_ACCEPTANCE`이며 실제 S24 home-screen 설치와 keyboard/cutout 확인은 사용자
확인으로 남는다. 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`이고 fine-tuning·LoRA·RAG는 실행하지 않았다.
범위와 증거는 `docs/PRIVATE_BETA.md`에 고정한다.

2026-08-28에는 현재 배포 V23 image와 별도 tmpfs PostgreSQL을 사용해 synthetic PWA capture부터 review
dialog까지 다시 관통했다. 같은 `6시 디스코드 접속하기`의 clean wall time은 Fake `242.399 ms`, exact
localhost LiquidAI `5349.338 ms`였고, LiquidAI attempt는 `SUCCESS`/`ACCEPTED_UNCHANGED`라 semantic
changed field는 0이었다. 두 arm 모두 schema v2 수동 상세 검토에서 정지했고 Apply/canonical table은
0이었다. 120-sample device-wide sampler는 baseline/max `2999`/`6229 MiB`, max utilization `91%`,
Ollama allocation `3012684676 bytes`/context `4096`을 관측했다. Temporary container/network/volume/test
file을 제거하고 기존 personal V23 container IDs/health와 Ollama loopback process/listener, loaded model
0을 복구했다. Dirty checkout에서 임시 orchestration으로 수행했으므로 반복 가능한 release gate가 아닌
`SOLO_PROVISIONAL`/`REPORT_ONLY` 기록이며 provider/training/LoRA는 계속 `NO_GO`, RAG는 미사용이다.

2026-08-24 Milestone 6A.1 source에는 proposal v2를 변경하지 않는 수동 EVENT schedule review,
Apply selection schema v2, Flyway V21 `event_details`, transactional Apply/undo, bounded owner-scoped
`GET /api/v1/events`, PWA confirmed schedule list가 추가됐다. 일정은 사용자가 정밀 날짜 후보를
직접 고르거나 직접 입력해야 하며 model/Fake가 자동 연결하지 않는다. 이어진 6A.2a source는
proposal v3 alternative contract, strict domain validation/down-projection, explicit-user-choice-only
PWA display, separate empty EVENT label-overlay integrity preparation을 추가했다. Current producers는
v2, suggested candidate gate는 closed이며 temporal-candidate-bearing v3 proposal과 schedule-bearing
selection은 `UNCLASSIFIABLE`이다. Human label/threshold/held-out gate는 6A.2b로 보류한다.
2026-08-25의 6B source는 같은 eligibility를 쓰는 authenticated RFC 5545 snapshot endpoint와
PWA preview-first exact-Blob download를 추가했다. Empty는 204, 101개째와 128 KiB 초과는 partial
export 없이 실패하며 TIMED Apply는 whole-second precision을 요구한다. Raw memo/internal UUID/
provenance/token/alarm/public URL은 없다. 이어진 6C source는 V22 recipient feed/entry projection,
authenticated management, client-secret digest verifier, one-time copy-only PWA URL,
recipient-scoped UID/sequence/cancellation과 fixed stateless GET/HEAD를 추가했다. Public hostname,
edge operator/rate bound와 external client smoke는 6D로 보류한다. 이 상태는
`SOLO_PROVISIONAL`/`REPORT_ONLY`이며 당시 source qualification은 personal deployment나
personal-data smoke 권한을 확장하지 않았다. 이후 별도 owner 승인의 V22 전환은 아래에 기록한다.
현재 `fake-v10`/`korean-rules-v8`는 명시적 `오늘|내일|모레 + 오전|오후 + 1–12시`뿐 아니라 날짜가
없고 무입자 또는 `에`인 explicit clock family—bare 1–12시 optional minutes, 오전/오후, Korean
24-hour clock, `HH:mm`—도 immutable revision capture instant/source zone을 기준으로 해석한다. 작성
당일 후보 중 capture instant보다 엄격히 미래인 가장 이른 safe occurrence를 `RELATIVE_EXACT`
proposal candidate로 만들며 같은 시각이나 이미 지난 후보는 선택하지 않는다. DST-gap occurrence는
제외하고 더 늦은 unique same-day 후보를 허용하지만 미래 overlap occurrence가 하나라도 있으면 전체
expression은 `UNKNOWN`이다. 남은 safe 후보나 valid source zone이 없어도 `UNKNOWN`으로 남긴다. 이
정책은 proposal-only/manual Apply이며 다음 날 이월,
자동 Apply, alarm/reminder 생성이나 전달을 허용하지 않는다. TIMED Apply의 start/end offset은 immutable
revision zone에서 유효해야 하며 DST gap은 거절하고 overlap의 두 explicit valid offset 중 하나는 허용한다.

6A.2a current source-only 격리 qualification은 Flyway V1–V21, backend 813 tests / 0 failures /
0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors, frontend lint와 40 files / 324 tests,
TypeScript/PWA production build, Redocly 2.44.1 OpenAPI lint를 통과했다. 개인 V20 stack, 개인 DB/메모,
Ollama는 이 qualification에서 접근하거나 변경하지 않았고, v3 producer와 자동 Apply는 계속 닫혀 있다.

6B current source-only 격리 qualification은 Flyway V1–V21, backend 820 tests / 0 failures /
0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors, frontend lint와 41 files / 334 tests,
TypeScript/PWA production build, Redocly 2.44.1 OpenAPI lint, production Compose render를 통과했다.
Container-local `127.0.0.1` secure-loopback relay의 focused Playwright calendar flow도 1/1 통과해
승인 EVENT, plain-text preview, response와 동일한 download bytes, 고정 filename, raw/internal-ID/
`VALARM` 비노출, Undo를 확인했다. `host.docker.internal`을 browser origin으로 쓴 앞선 실행은
`crypto.randomUUID`가 없는 non-secure harness여서 memo request 전에 실패했고 제품 결과에서 제외했다.
모든 6B Compose/relay/container/volume/local image는 제거했으며 개인 V20 stack, 개인 DB/메모,
Ollama는 접근하거나 변경하지 않았다.

6C current source-only 격리 qualification은 Flyway V1–V22, backend 845 tests / 0 failures /
0 errors / 1 skipped, Spotless clean, SpotBugs 0 bugs/errors, frontend lint와 44 files / 377 tests,
TypeScript/PWA production build, Redocly 2.44.1 OpenAPI lint, production/personal Compose render와
Windows source contract를 통과했다. Secure-localhost 전체 Playwright 24/24 (25.5 s)는 수동 EVENT
schedule, `BUSY_ONLY` 기본값, 명시적 feed membership, one-time copy-only URL, title/raw/internal-ID
비노출, rotate/revoke 404와 offline network-only 경계를 확인했다. Private isolated Nginx의 합성
unknown token GET/HEAD는 empty no-store 404였고 owned frontend/backend synthetic sentinel은 0건이었다.
이는 public edge/external-provider log 또는 Google/Apple subscription smoke가 아니다. 추가 synthetic
integration은 정확한 100 lifetime feed/entry 경계와 실패 rollback, ALL_DAY active/cancelled same-UID,
create/add와 actual undo/memo update/trash 6개 경쟁을 검증했다. 최초 835-test 실행 전의 6개 error는
cached Spring context들이 synthetic PostgreSQL connection limit를 소진한 harness 문제였다. 후속
test resource에만 Hikari pool 4/minimum-idle 0을 고정했고 별도 CLI override 없는 fresh full verify가
845 tests로 통과했다. Exact 6C temporary container/volume/network/image는 제거했고 개인 V20 stack,
개인 DB/메모/canonical data와 Ollama는 이 source qualification에서 접근하거나 변경하지 않았다.

그 뒤 2026-08-27 owner 승인으로 exact personal stack의 checksummed V20 cutover backup과 disposable
source-V20/target-V22 restore rehearsal, failed migration 0/zero calendar backfill, rebuild, trusted
private HTTPS health, synthetic unknown-token private GET/HEAD와 query-free log smoke를 완료했다. Live
schema는 V22이고 새 calendar table 합계는 0이었다. Raw memo body/개인 일정은 읽지 않았고 실제
  feed/token/membership row도 만들지 않았다. V20/V22 image digest tag와 cutover backup은 보존했고
restore 임시 container/network/volume은 제거했다. 6D public feed-only edge는 계속 닫혀 있다.

당시 6D.1은 OpenAPI 0.12.0과 backend fail-closed property/authenticated no-store controller,
frontend strict decoder/warned URL UI로
`GET /api/v1/calendar-feeds/capabilities`와 exact
`LOCAL_ONLY`/null 또는 `PUBLIC_HTTPS`/strict HTTPS multi-label ASCII hostname 응답을 source 구현했다. Origin
authority는 server-owned deployment configuration이고 기본은 disabled다. Valid LOCAL_ONLY는 명확히
경고된 private/local URL만 만들 수 있고, failed/malformed capability는 fallback authority가 아니다.
Hostname syntax는 public-suffix/DNS 검증이 아니며 후속 edge gate가 확인한다. 현행 OpenAPI 0.13.0/V23은
여기에 `consentPolicyVersion`을 추가한 3-field exact union으로 대체하고 feed별 explicit consent를 요구한다.
Fresh isolated qualification은 Flyway V1–V22, backend 119 suites / 854 tests / 0 failures / 0 errors /
1 skipped와 Spotless/SpotBugs, frontend lint와 44 files / 401 tests, TypeScript/PWA production build,
production/personal Compose render와 Windows source contract를 통과했다. Fixed local Node OpenAPI
YAML/ref/operationId/origin matrix도 통과했지만 private spec의 third-party image mount가 환경 정책으로
거절돼 Redocly Docker lint는 실행하지 않았다. Exact 6D.1 temporary Docker resources는 제거했다.
후속 owner-authorized 배포는 checksummed backup `personal-memo-20260827-033109271Z.dump`, old-image
rollback tags와 새 backend/frontend image `sha256:3d1295b84aa41ef27e0adf0120775b3b0c021f6eae9bcde94658393d317772d4` /
`sha256:4593d22806a46ab69ceae5630196c74cc59fb356b5d815535c4fcc2090830f52`를 보존했다. Publication
environment entry 0이므로 배포는 `LOCAL_ONLY`이며 세 service health, Flyway V22/failed 0, trusted
PWA 200, unauthenticated capability 401/no-store, synthetic private GET/HEAD와 token-free log를
통과했다. 개인 session을 사용하지 않아 authenticated capability 200 body는 runtime smoke하지 않았다.
Public hostname/DNS/TLS/operator route, external bounds/log proof와 Google/Apple smoke는 실행하지 않았다.
상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`; runtime public activation은 `NOT_AUTHORIZED`다.

그 다음 공식 명칭 `6D public-edge preflight` source가 별도 loopback-only edge와
`compose.public-feed.yaml`, final PUBLIC_HTTPS 전환 전용
`compose.public-feed-activation.yaml`, placeholder `.env.public-feed.example`, disposable isolated
smoke를 추가했다. Edge-only preflight는 publication env를 주지 않으므로 backend는 `LOCAL_ONLY`이고
host bind는 `127.0.0.1:${PERSONAL_MEMO_CALENDAR_EDGE_PORT:-8787}`뿐이다. Exact bodyless
canonical-token GET/HEAD 이외에는 generic empty 404, rate limit은 bodyless 429이며 log는 raw
method/target/query 대신 safe route/method class만 남긴다.

Recorded isolated smoke는 generated bearer와 disposable Nginx upstream으로 exact GET/HEAD, deny
surface, caller-header stripping, provisional 60r/m + burst 20 / connection 8 / proxy 2s·5s·10s
connect·send·read bounds, query/path/header/custom-method sentinel의 owned edge/upstream log 0건을
통과했다. Personal DB/session/memo/feed/event/canonical/Apply는 사용하지 않았고 external log proof는
아니다. 후속 operator는 Cloudflare remotely-managed named Tunnel로 결정했고 Quick Tunnel은 금지했다.
위 공식 binary는 `DOWNLOADED_VERIFIED`이고 Cloudflare login과 owner-controlled active zone을 확인했다.
Remote named Tunnel, single-label published application/DNS와 exact-path loopback route는 configured다.
Hardened `PersonalMemoCalendarCloudflareTunnel` service는 `Stopped`/`Manual`/`LocalSystem`과 protected
token-file-only ImagePath/no-inline-token을 검증했다. 일반 기본 `Cloudflared` service는 제거했고 현재
`cloudflared` process와 port `8787`/`49312` listener는 모두 0이며 stop 뒤 Cloudflare status는 `Down`이다.
Query bearer는 Cloudflare processing boundary에 들어가므로
`ClientRequestURI`/header/cookie/referer log와 cloudflared debug를 금지하고, path-only allow-list log,
exact host/path cache bypass 및 `CF-Cache-Status != HIT` 외부 증거를 요구한다.

Cloudflare 준비/cutover 순서는 connector stopped 상태에서 tunnel/DNS/policy 준비 → connector를
disposable synthetic origin에만 연결해 외부 검증 → connector stop 및 synthetic cleanup → live loopback
edge 시작 → activation overlay 적용 → connector start **last**다. Rollback은 connector stop **first** 및
Tunnel을 통한 성공 feed response 없음(non-2xx이며 Cloudflare edge error 허용) 확인 → activation overlay
없이 backend recreate/`LOCAL_ONLY` 확인 → edge stop이다.
WAF rate rule은 origin bound의 보조 수단이며 Cloudflare Tunnel alone은 total 10s/128 KiB hard bound가
아니다. External synthetic qualification은 46 bounded probe로 transport/path/cache, HIT 0,
owned-log/external-artifact-reflection sentinel PASS를 기록하고 rollback했다. 429는 bounded attempts 안에서
관측되지 않았고 provider/customer log sentinel은 account plan상 unavailable/unverified, receipt replica
field는 `REQUIRED_NOT_VERIFIED`다. 별도 dashboard의 active replica 0/routes 1/status Down 수동 관측은
cleanup 상태 증거이지만 strict receipt proof를 대체하지 않는다. 따라서 activation은 `NO_GO`이고 실제
public activation, real-feed qualification과 Google·Apple smoke는 final gate 전 `NOT_AUTHORIZED`이며 상태는
`SOLO_PROVISIONAL`/`REPORT_ONLY`다.

- Phase 0과 Phase 1의 AI-free 수직 흐름은 구현되어 있다.
- graph home은 별도 graph 저장소 없이 canonical memo/application/item/task/tag에서 최대 100개를
  투영한다. memo는 pin → overdue → TODO → nearest due → 현재 raw revision 시각 → UUID,
  tag는 선택된 memo 안의 연결도 → 이름 → UUID 순으로 안정적으로 제한한다. `limit > 1`이면
  최소 1개이자 전체의 1/5인 슬롯을 tag 쪽에 먼저 예약하여 큰 memo corpus에서도 edge를
  memo node가 전부 밀어내지 않는다. 실제 선택된 tag 수만큼만 슬롯을 유지하고 나머지는 memo로
  안전하게 채운 뒤 final memo set에서 tag 순위와 truncation을 다시 계산한다. tag가 initial
  memo 밖에만 있으면 관계를 빠뜨린 채 complete라 하지 않고 underfill+`truncated`를 유지한다.
  node는 실제 keyboard/touch button이며 current-home direct neighbor를 강조하고 mobile detail
  drawer를 연다. 별도 `GET /graph/nodes/{kind}/{id}/neighborhood`는 Spring Security owner 안의
  canonical `MEMO_TAG` 1-hop을 page당 최대 20개로 읽고, 24시간 snapshot cursor로 MEMO→TAG는
  normalized name/UUID, TAG→MEMO는 home의 pin/overdue/TODO/due/raw-revision/UUID hard priority를
  유지한다. cursor v2는 owner·center·마지막 neighbor identity와 snapshot 외에 첫 page 전체의
  visible membership·정렬·표시 상태 SHA-256 digest를 담는다. continuation은 같은 owner 범위에서
  digest를 재계산해 canonical 상태가 달라졌으면 `422`로 처음부터 다시 읽게 하므로 mutable
  priority 변경으로 이웃을 조용히 누락하지 않는다. cursor는 authorization으로 쓰지 않으며,
  unavailable/foreign center는 cursor 해석 전에 동일한 404다. PWA는 최대 5 page, 100개를
  유지하고 stale cursor에서는 더 불러오기를 숨긴 채 첫 page 재시작을 제공하며, tag에서 home
  밖 memo의 owner-scoped current raw detail을 no-store로 연다.
  active memo pin/unpin은 owner row lock과 idempotency key/body hash를 사용하고 raw revision·proposal·
  item/tag/task를 바꾸지 않는다. 명시적 opt-in 10,000 memo/tag high-fanout EXPLAIN runner는
  양방향 bounded page와 digest query가 기존 V5/V12/PK index를 사용하고 shared read/temp block 없이
  끝나는 한 번의 격리 관측을 남겼다. 이는 SLA가 아니며 재현 명령과 bounded JSON report 계약은
  `docs/DATA_MODEL.md`에 있다. 현재 근거로는 graph-neighborhood 전용 migration/index를 추가하지 않았다.
- Milestone 5의 두 번째 read-only slice는 `POST /api/v1/search/memos` exact lexical search다. query는
  CSRF-protected JSON body에만 있고 URL·browser storage·service-worker cache·ordinary access log에
  저장하지 않는다. query는 U+0000과 lone surrogate를 포함하지 않아야 하고, raw와
  NFKC/strip/`Locale.ROOT` lowercase 결과 모두 200 UTF-16 code unit 이하여야 한다. 저장된 current
  raw BODY와 latest valid `APPLIED` canonical TITLE은
  PostgreSQL NFKC/`und-x-icu` lowercase를 적용해 그 query와 literal substring으로 비교하고, current
  owner의 `ACTIVE` TAG/ALIAS는 `TagNormalizer` exact normalized equality로 찾는다. proposal,
  `UNDONE` application, archived item과 inactive tag는 검색 authority가 아니다. lifecycle,
  aggregated task state, snapshot-derived overdue, current-revision inclusive lower/exclusive upper
  instant filter를 제공한다. 각 bound는 JDBC binding 전
  `0001-01-01T00:00:00Z`–`9999-12-31T23:59:59.999999Z`로 제한하고 current revision
  recency/UUID keyset으로 page한다.
  server page는 기본 20·최대 50, PWA는 5 page/100 result이며 preview는 current raw 최대 240 Unicode
  code point, visible canonical tag는 matching-first 최대 8개다. cursor v1은 query/filter raw나 display
  text 없이 owner·normalized query/filter digest·sort shape·24시간 snapshot·full-visible-result digest·
  last memo identity를 묶는다. 결과 membership/order/display state가 바뀌면 `422
  INVALID_SEARCH_CURSOR`로 기존 목록을 stale 표시하고 cursor 없는 첫 page 재시작을 요구한다.
  결과는 React Flow에 주입하지 않고 owner-scoped `GET /memos/{id}`로 current raw detail을 no-store로
  다시 연다. 이는 exact lexical 첫 slice이며 fuzzy/`pg_trgm`, related-memo, vector/embedding,
  provider/Agent tool, cluster reveal, taxonomy evolution이나 Milestone 5 전체 완료를 뜻하지 않는다.
  별도 opt-in 10,000-memo worst-case all-match runner의 한 hot-buffer 관측에서 BODY/TITLE page/digest는
  812.126/1082.515 ms, exact ALIAS page/digest는 555.671/1009.93 ms였고 shared read/temp I/O가
  없었다. 이는 end-to-end latency나 SLA가 아니다. canonical join이 기존 index를 사용하고 새 B-tree
  효용 근거가 없어 search 전용 migration/index를 추가하지 않았으며 exact 재현/report 계약은 `docs/DATA_MODEL.md`에
  있다.
- Source Flyway `V1`–`V23`가 memo/revision, proposal/application, canonical item/tag/task/event/relation, recipient calendar-feed projection와 explicit publication consent, owner integrity, revision capture context, analyzer·prompt·local model·embedding model·routing policy provenance, local/Google identity, JDBC session schema, claimed user identity 무결성과 일회성 initial-account provisioning gate를 관리한다. 개인 배포 database는 2026-08-28 owner-authorized backup·disposable restore rehearsal·migration/rebuild 뒤 V23이며 publication environment가 없는 `LOCAL_ONLY`다. Public activation은 계속 `NO_GO`/`NOT_AUTHORIZED`다. `V11`은 owner별 proposal의 최신 application을 찾는 review-outcome 조회 인덱스, `V12`는 memo별 최신 `APPLIED` selection과 활성 item을 읽는 graph projection partial index만 추가한다. `V13`은 boolean-only legacy cloud consent를 폐기하고 owner row의 exact policy-version·granted-at pin을 강제하며, run마다 cloud transfer/gateway/provider/model/policy/outcome evidence를 추가한다. `V14`는 새 run의 내부 authorization/grant snapshot과 결정론적 provider-request token을 일관되게 저장하고 과거 row는 `legacy-v0`로 보존한다. `V15`는 호출 전에 `durable-v1` run과 1:1 `analysis_run_dispatches` preparation을 commit하고 immutable executor binding·descriptor, deadline/lease/fence, reserved proposal와 idempotency evidence를 보존한다. `V16`은 새 dispatch에 owner-scoped exact tag/alias K=8 context의 raw/hash/version/count를 함께 준비하고, 기존 V15 row는 `none/0/NULL/NULL`로 보존한다. `V17`은 새 dispatch를 `gateway-attempt-v1`로 versioning하고 claim fence별 owner-scoped `analysis_run_dispatch_attempts` row를 `max_attempts` 상한 안에서 보존한다. 기존 dispatch는 `attempt_history_version=none`이고 attempt row를 소급 생성하지 않는다. V14까지의 row에도 dispatch를 소급 생성하지 않는다. `V18`은 사용자가 선택한 proposal relation을 application 소유의 item-scoped directed MEMO/TAG relation으로 저장하고 owner-aware source/application/target constraint와 undo를 강제한다. 새 raw analytics 복제본이나 일반 clickstream table은 만들지 않는다.
- `V19`는 dispatch에 raw-free 결정론 decision shape, versioned fallback reason, model contribution
  status와 semantic changed-field만 추가한다. memo body나 model prompt/response를 새 evidence에 복제하지
  않으며 과거 dispatch는 정직한 legacy/default shape로 보존한다.
- `V20`은 `model-invocation-v1` mode/reason을 semantic fallback reason과 분리한다. personal-only
  `approved-type-anchor-k3-v1`은 same-owner eligible latest `APPLIED` type 교정에서 current memo에도
  exact-unique한 anchor를 최대 3개 찾고, retry snapshot에는 target memo UTF-16 offset와 kind만 둔다.
  claim은 locked current revision에서 anchor text+kind를 materialize하며 finalization은 raw offset을
  scrub하고 hash/version/count만 남긴다. historical raw/selection/ID는 복제하지 않는다.
- `V21`은 기존 EVENT를 backfill하지 않고 TIMED/ALL_DAY `event_details`를 추가한다. owner+EVENT-kind
  composite FK와 null-safe shape/range CHECK를 사용하며 optional end를 발명하지 않는다. scheduled
  Apply만 selection schema version 2와 별도 versioned idempotency hash를 사용하고 Undo는 detail을
  source item보다 먼저 지운다.
- 6B iCalendar export는 새 table/migration 없이 current owner의 active/current/APPLIED scheduled
  EVENT query를 재사용한다. Domain-separated SHA-256 UID, immutable item-created DTSTAMP, sequence 0,
  UTC TIMED/`VALUE=DATE` ALL_DAY, explicit end only, CRLF/TEXT escaping/75-octet folding을 출력한다.
  PWA direct navigation은 금지하고 session epoch/expected-owner가 검증된 한 Blob만 preview/download한다.
  6B UID는 recipient feed에서 재사용하지 않는다.
- `V22`는 digest-only `calendar_feeds`와 recipient-random public UID, monotonic sequence, last explicit
  schedule, ACTIVE/CANCELLED state를 갖는 `calendar_feed_entries`를 추가한다. Create/add는 전부
  explicit이고 default disclosure는 BUSY_ONLY다. Memo edit/trash/application undo는 source mutation
  전에 tombstone을 만들며 restore는 자동 reshare하지 않는다. Public fixed-path GET/HEAD는 matched
  verifier가 정한 server-owned scope만 읽고 세션/owner header/access write를 사용하지 않는다.
- `V23`은 `publication_scope`, `public_consent_policy_version`, `public_consent_granted_at`을 추가하고
  기존/new row를 local/null로 유지한다. Fresh secret을 포함한 explicit enable만 public/current-policy
  row를 만들며 revoke는 consent를 지운다. Source-only qualification은 disposable PostgreSQL Flyway
  V1-V23, backend 121 suites/861 tests(`failures 0`, `errors 0`, `skipped 1`), Spotless/SpotBugs, frontend
  44 files/414 tests와 ESLint/TypeScript/PWA build, OpenAPI lint/actual-instance validation, 4개 Compose
  render와 public/personal PowerShell source contract를 통과했다. 검증 전용 Docker 자원은 제거했다.
  후속 owner-authorized personal deployment는 fresh V22 backup, disposable V22→V23 restore rehearsal,
  live Flyway V23/failed 0, three columns/two validated constraints, aggregate 불변, publication environment
  0과 trusted private-route smoke를 통과했다. Cloudflare connector/real feed/client smoke는 실행하지
  않았다. 상세 증거는 `docs/PRIVATE_BETA.md`에 있다. 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`,
  activation은 `NO_GO`다.
- 각 local/Google 로그인 수단은 internal UUID에 매핑되고, 명시적으로 연결한 두 수단은 같은 UUID와 PostgreSQL-backed server session을 사용한다. Google email만으로 자동 연결하지 않고 기존 로그인 뒤 명시적 link intent를 요구하며, 마지막 login method는 해제할 수 없다. domain owner는 client 값이나 개발 상수가 아니라 Spring Security context에서 가져온다.
- React 인증 shell은 capability·CSRF·현재 session을 먼저 확인하고, 로그인 전에는 owner domain API를 호출하지 않는다. service worker는 API와 application OAuth/login 경로뿐 아니라 Cloudflare-owned case-insensitive `/cdn-cgi/access(?:/|\?|$)` namespace도 navigation fallback/cache에서 제외하고 `NetworkOnly`로 처리한다. Access control request에 cached app shell/offline UI를 반환해서는 안 된다.
- owner별 원문 capture draft는 browser localStorage에 동기식으로 보존하고 저장소 실패를 사용자에게 알린다. 제안 수정·새 태그 입력·원문 revision 편집은 통합 dirty 상태로 추적하며, OAuth·로그아웃·브라우저 이탈을 확인하고 service-worker 업데이트는 사용자가 선택하되 미저장 편집 중에는 적용하지 않는다.
- 인증 통합 테스트는 local 가입·로그인, CSRF, session rotation, owner 격리와 mocked OIDC 연결/해제를 검증한다. 실제 Google credential과 provider network round trip은 사용하거나 검증했다고 간주하지 않는다.
- 12개 regression + 12개 `VISIBLE_CHALLENGE` 한국어 fixture, version-2 fixture JSON Schema, raw content를 포함하지 않는 결정론적 평가 report, revision 기준 날짜 파서, `field-policy-v2` ambiguity gate, Draft 2020-12 runtime contract와 strict domain validation이 구현되어 있다. version 2는 route/type/signal뿐 아니라 date mention/item/item-source-span 지표도 report에 노출한다. `fake-v10` / `korean-rules-v8`는 기존 날짜·행동·참조·multi-intent 규칙과 원문 기반 순차 item/source-span 추출을 유지하면서 proposal schema v2를 생성하고, default fallback과 미해석 시간·행동 cue를 보수적으로 model-assisted review로 보낸다. guarded affirmative `접속하기`는 TASK action으로 인정하지만 부정·설명형은 명령으로 승격하지 않는다. 명시적인 `오늘|내일|모레 + 오전|오후 + 1–12시`(optional minutes)는 revision instant/source zone에서 `RELATIVE_EXACT`다. 날짜가 없고 무입자 또는 `에`인 explicit clock family—bare 1–12시 optional minutes, 오전/오후, Korean 24-hour clock, `HH:mm`—도 같은 immutable capture context에서 작성 당일의 엄격히 미래인 가장 이른 safe occurrence만 `RELATIVE_EXACT`로 만든다. DST-gap occurrence는 제외하고 더 늦은 unique same-day 후보를 허용하지만 미래 overlap occurrence가 있으면 전체 expression은 `UNKNOWN`이다. 남은 safe 후보나 valid source zone이 없어도 `UNKNOWN`이다. 이 정밀화도 untrusted proposal일 뿐 final Apply 전에는 canonical due, schedule, alarm/reminder를 만들지 않는다. v2의 각 date candidate에는 proposal-local `candidateId`, 각 item에는 nullable `dueDateCandidateId`가 필수이며, TASK item만 존재하는 정밀 date candidate를 참조할 수 있다. schema v1 proposal은 recovery와 outcome 재구성을 위해 계속 지원한다. `providerMetadata`의 다섯 version은 각각 1–64자, 필수 `toolCalls`는 0–100이며 proposal은 64 KiB, metadata는 8 KiB로 제한된다. 이 변경은 기존 `analysis_runs.schema_version`과 `analysis_proposals.proposal_json`을 사용하므로 Flyway migration이나 과거 JSON rewrite가 필요하지 않다.
- proposal 단건 GET과 recovery list는 `X-Analysis-Proposal-Schema-Version`을 maximum-understood
  version으로 협상한다. Invalid/combined 값은 `422 UNSUPPORTED_PROPOSAL_SCHEMA_VERSION`이고 성공
  응답은 `Cache-Control: no-store`와 schema-header `Vary`를 포함한다.
- 6A.2a 이후 proposal read maximum은 `3`이다. Current PWA는 `3`을 보내지만 current Fake/Ollama
  producers는 계속 v2다. Stored v3를 max-v2 client에 줄 때는 EVENT candidate/suggestion fields만,
  max-v1에는 date ID/TASK due fields까지 응답 copy에서 제거한다. Historical v1/v2는 상위 요청에도
  합성 upgrade되지 않고 storage/hash/run version은 바뀌지 않는다.
- Proposal v3 item은 bounded `eventScheduleCandidates`와 nullable
  `suggestedEventScheduleCandidateId`를 갖는다. Alternative는 ID/mode/start-reference/optional
  end+boundary/score의 strict shape이고 inclusive all-day end만 explicit
  `INCLUSIVE_THROUGH_VALUE`에서 하루 뒤 exclusive boundary로 normalize한다. Non-EVENT, dangling,
  imprecise/mode mismatch, duplicate, overflow/non-later range를 거절하고 multiple alternatives는
  `CONFLICTING_DATES`를 요구한다. Current domain은 non-null suggestion을 거절하며 PWA도 candidate를
  explicit user action 전에는 schedule draft로 복사하지 않는다.
- 2026-08-28 validation parity hardening은 공개 synthetic proposal 1개와 17개 mutant로 JSON
  Schema/domain 책임 분리를 고정하고, raw-free local-decision evidence 1개와 15개 cross-field
  mutant가 schema 통과 후 generic domain error로 차단됨을 검증한다. EVENT overlay integrity는
  `TIMED`/`ALL_DAY` start/end의 accepted interpretation 전체가 mode-compatible precision일 때만
  통과하며 혼합 정밀도를 일부 버리고 통과시키지 않는다. 관련 55 tests와 disposable Flyway
  V1-V23 PostgreSQL 전체 backend `mvn verify`, SpotBugs 0건이 통과했다. 개인 DB/memo, Ollama,
  canonical/API/Apply는 사용하지 않았고 상태는 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY`다.
- Apply due와 EVENT schedule의 client `timeZone`은 호환성 입력으로 유효성만 검사한다. canonical
  `task_details/event_details.source_time_zone`은 잠근 immutable memo revision의 capture zone으로
  서버가 교체하므로 승인 기기·여행 중 zone이 canonical context를 바꾸지 못한다. proposal v2에는
  EVENT binding이 없고 review가 schedule을 자동 선택하지 않는다. TIMED start/end offset은 source
  zone의 해당 local date-time에 유효해야 한다. DST gap은 `EVENT_SCHEDULE_ZONE_OFFSET_MISMATCH`이고,
  overlap은 명시된 두 valid offset 중 하나를 보존한다.
- `GET /events`는 기본 50·최대 100의 owner-scoped no-store read다. current active memo revision의
  unarchived, scheduled, current `APPLIED` EVENT만 title/schedule/source zone으로 반환하고 raw memo,
  proposal/selection/application provenance와 title-only EVENT는 제외한다.
- 공개된 합성 `VISIBLE_CHALLENGE`는 blind/general accuracy가 아니며 계속 report-only다. regression hard gate는 proposal schema/domain validity, 기존 route/type/signal wrong-local 0, invented precise date 0, local overflow 0, missing overflow signal 0, unresolved action/object hallucination 0으로 제한한다. 마지막으로 기록된 `fake-v8` 공개 합성 실행은 schema/domain 24/24, wrong-local·invented precise date·local overflow·missing overflow signal·unresolved action/object hallucination 모두 0이며, 이전 `fake-v7` 기록과 같은 aggregate를 재현했다. item cardinality는 regression 11/12, visible challenge 12/12 case이고 필수 gold source span recall은 regression 15/15·visible challenge 14/14개다. regression에는 default fallback scaffold로 인한 추가 span 1개가 있다. `fake-v9`/`korean-rules-v7`의 relative-day-time 규칙과 현재 source `fake-v10`/`korean-rules-v8`의 date-less future-occurrence 규칙은 focused source tests로만 보강됐고 이 과거 v8 aggregate를 v9 또는 v10 측정으로 다시 이름 붙이지 않는다. date mention/item/item-source-span quality rate와 semantic false-confident-local은 독립적인 2인 gold adjudication과 외부 blind 실행 전까지 진단 지표다. evaluation dataset v2에는 date-to-item binding gold가 없으므로 report는 `SUPPORTED_NOT_SCORED_DATASET_V2`만 선언하고 binding 품질을 hard metric으로 승격하지 않는다. strict v2 2인 review schema/verifier와 immutable v2 release를 참조하는 ID-only v3 binding overlay integrity validator는 준비됐지만, 실제 human review manifest·adjudication·v3 dataset·binding score·`PASS`는 없다. `docs/EVALUATION_LABEL_POLICY.md`도 human approval 전 draft다. 저장소 밖의 독립적 human-curated version-2 envelope만 받는 external blind harness와 aggregate-only privacy 경계는 구현되어 있지만 실제 blind 데이터와 사전 등록된 metric `PASS` 정책은 저장소에 없으며 실행했다고 주장하지 않는다. 자세한 진입 조건은 `docs/EVALUATION.md`를 따른다.
- 공개 v2 human review 실행을 위한 두 도구도 명시 실행 전용으로 준비한다. `PublicGoldReviewPacketRunner`는 같은 clean candidate commit을 읽기 전과 원자 게시 직전에 확인하고 strict public release를 검증한 뒤 case ID/split, 공개 source/base instant/time zone과 date/item gold만 수동 허용 목록으로 렌더링하며 fixture notes, route/type/tag/signal, analyzer/Fake/report/peer-review data, verdict form과 manifest 생성을 배제한다. 모든 source span은 반개구간 UTF-16 숫자와 강조 조각으로 표시한다. 실제 두 사람이 저장소 밖에 만든 완전한 manifest 두 개가 있을 때만 `ExternalPublicGoldReviewRunner`가 absolute/non-link/distinct input, strict UTF-8/JSON/schema, exact release, distinct reviewer token, attestation, clean pinned commit을 확인하고 aggregate-only summary를 원자적으로 쓴다. summary의 `CONSENSUS_ACCEPTED`도 human identity·independence·policy approval·adjudication·v3 binding·blind `PASS`·provider readiness를 증명하지 않는다. 일반 Maven/CI는 두 Runner를 실행하지 않는다.
- 기본 Fake + `UNCERTAINTY_ONLY`에서 명확한 결과는 gateway 호출 없이
  `LOCAL`/`REVIEW_REQUIRED`로 저장되고 모호한 결과만 `NO_NETWORK` Fake를 거친다. personal overlay는
  `AI_PREFERRED`라 schema/domain-valid current revision마다 exact pinned localhost LiquidAI를 호출하며,
  clear semantic decision에도 ambiguity를 발명하지 않고 별도 V20 reason을 남긴다.
  `EXTERNAL_MEMO_CONTENT`는 authenticated owner의 consent boolean·정확한 policy version·non-null 승인
  시각이 모두 맞고 `granted_at`이 권한 확인 시각보다 늦지 않아야 호출되지만 실제 external adapter는
  없다.
- LOCAL, cloud SUCCESS, fallback의 모든 새 proposal은 공통 allowlist canonicalizer가 `providerMetadata`를 다시 만든다. cloud typed failure, gateway/descriptor 예외, invalid enriched proposal은 run을 rollback하지 않고 provider 오류 text 없이 server-owned outcome만 남기며, 검증된 local proposal을 `HYBRID`/`REVIEW_REQUIRED`로 저장해 UI가 상세 검토하도록 한다. raw revision과 canonical tag/task/relation은 바뀌지 않는다.
- 실제 external provider와 consent grant/revoke API는 없다. 일반 application은 계속 `NO_NETWORK`
  Fake gateway를 쓰고 개인 overlay만 pinned `host.docker.internal:11434` → Windows loopback Ollama,
  exact LiquidAI tag/digest의 `LOCAL_MACHINE_MEMO_CONTENT` `AI_PREFERRED` 경로를 켠다. 현재
  `ollama-local-gateway-v2`는 bounded textual `thinking`을 검증한 뒤 무시하고 visible `content`만 strict
  JSON/schema/domain validation에 사용한다. non-text·oversized·extra-field 또는 malformed/truncated
  output은 fail closed한다. V15–V20 durable
  prepare/claim/fence/retry/stale/finalize 경계를 그대로 사용하며 모델 입력은 실행 메모리에만 둔다.
  timeout·unavailable·digest mismatch·redirect/proxy/tool·truncation·schema/domain 위반은 canonical
  write 없이 재검증된 local 상세 검토 fallback이다. default-`RECORD` fallback evidence만
  `UNKNOWN`으로 정규화하고 기존 명시 후보는 보존한다. 모든 model result는 proposal-only/manual
  Apply다. related-memo/fuzzy/vector/embedding/RAG corpus, automatic rule promotion, training,
  fine-tuning, LoRA, 알람/reminder delivery와 실제 model-token/cost 집계·budget enforcement는 미구현이다.
- V17의 Fake `NOT_APPLICABLE`/null은 descriptor로 no-model임을 확인하고 local termination observation이 있는 attempt에 적용된다. real-model의 `NOT_APPLICABLE`/null은 확정적 `NOT_STARTED`에만 적용하고, 시작 여부나 remote completion이 불확실하면 `UNKNOWN`/null이다. observation 없이 process가 유실되면 duration·remote truth·model-token/cost evidence는 `UNKNOWN`/null이다.
- `SoloLiquidAiShadowBaselineRunner`의 보존된 v1 baseline은 일반 Maven/CI가 선택하지 않는 test-only 실행으로, 외부
  orchestrator가 고정 loopback Ollama preflight와 process/resource cleanup을 책임진다. 공개
  synthetic v2 12+12, 총 24회 중 scored response와 inference-schema valid는 18건, canonical
  schema valid는 3건, domain valid는 1건이었다. wrong-local 9건, invented precise date 11건,
  missing overflow signal 1건, unresolved hallucination 2건이었다. 성공 응답 LiquidAI latency는
  p50 `15451.417 ms`, p95/max `33236.766 ms`, mean `17431.567 ms`, Fake는 p50 `0.453 ms`, p95
  `1.581 ms`였다. Ollama allocation은 `3166835834` bytes/context `8192`였다. 외부 device-wide
  853개 sample의 baseline/peak/utilization/post는 `3501 MiB`/`6990 MiB`/`92%`/`3543 MiB`였지만
  model-exclusive하지 않다. report는
  `backend/target/evaluation/solo-liquidai-shadow-baseline.json`, SHA-256
  `360660c5e283f719465262088e91b168a88dea27944a0e61c5fcd065a830b020`이며
  `SOLO_PROVISIONAL`/`REPORT_ONLY`/`NOT_CONFIGURED`다. 공개 24건은 blind, independently
  human-adjudicated, train/validation data가 아니며 당시 결정은 `NO_GO_FOR_TRAINING`,
  prompt/schema iteration `RECOMMENDED`였다. 이 historical path는 v8-A LiquidAI `NO_GO`와
  skill-only 기본 경로로 종료됐다. 개인 DB/API/Apply를 사용하지 않았고 provider, 제품 adapter,
  fine-tuning을 승인하지 않는다. `NOT_CONFIGURED`는 이 historical runner 당시 상태이며, 이후
  personal semantic-patch fallback 결정과 authoritative/provider readiness는 별개다.
- 같은 공개 visible 12+12 fixture와 고정 모델/digest를 사용한 v2 prompt/schema 개발 실행도
  완료했다. v2 report는 `backend/target/evaluation/solo-liquidai-shadow-baseline-v2.json`
  (SHA-256 `7507690bc6f80c937f382ce428a210540cede1fde621249b5441755b18cb4f26`), companion postflight/isolation
  evidence는 `solo-liquidai-shadow-baseline-v2-attestation.json`, frozen execution source bundle은
  SHA-256 `2f19402e7ee004de93a4508fecd6b55f344445ce381636742de00b55bd79e76d`다. LiquidAI는 24/24
  response, inference/canonical/domain valid `20/24`/`20/24`/`10/24`, route accuracy `0.541667`,
  wrong-local 4, invented precise date 0, local overflow 0, missing overflow signal 1, unresolved
  hallucination 0이었다. invalid category는 inference 4/canonical 4/domain 14로 합계 22 observation이며
  서로 겹치므로 22개 고유 실패 case가 아니다. Fake는 canonical/domain `24/24`, route accuracy
  `1.0`, 위 safety error 전부 0이었다.
- v2 LiquidAI all-attempt min/p50/p95/max/mean latency는
  `9896.043`/`16754.523`/`24241.698`/`24655.245`/`17540.866 ms`, successful-response는
  `9895.774`/`16754.176`/`24241.137`/`24654.879`/`17540.185 ms`, Fake는
  `0.377`/`0.547`/`11.795`/`114.698`/`5.872 ms`였다. Ollama는 context `8192`에서
  `3166835834` bytes를 VRAM에 할당했지만 report의 peak/utilization은 `NOT_AVAILABLE`이다. attestation의
  9개 coarse device-wide sample은 baseline `3197 MiB`, maximum observed `6671 MiB`, utilization
  `89%`이며 model/process-exclusive도 peak claim도 아니다.
- v2는 `SOLO_PROVISIONAL`/`REPORT_ONLY`/`NOT_CONFIGURED`/
  `PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY`이고 공개 24건은 blind 또는 independently
  human-adjudicated가 아니다. `dueDateCandidateId`와 `sourceSpan`은 test schema에서 null-only로
  `DISABLED_NULL_ONLY_IN_SHADOW_V2`, relation은 empty-array로 disabled, tag ranking은 미채점이다.
  network는 OS-level egress isolation이 아니라 runner `127.0.0.1:11435` → container-local relay →
  `host.docker.internal:11435`(expected gateway `192.168.65.254`) → Windows loopback Ollama의
  `MACHINE_LOCAL_DOCKER_HOST_BRIDGE`였고 published port는 없었다. runner/relay 종료, loaded model 0,
  동일 tag/digest, owned process/listener 없음, scoped temp 제거와 log 미보존을 확인했다. runner 범위의
  product HTTP/canonical read/write/Apply는 모두 0이고 개인 메모·PostgreSQL을 사용하지 않았다.
  development acceptance는 명시적으로 `NOT_MET`, training은 `NO_GO_FOR_TRAINING`, LoRA는 `NO_GO`다.
  학습/fine-tuning 또는 도구 설치, provider/product adapter/API/DB/Apply 변경과 `PASS`를 승인하지 않는다.
- 같은 공개 fixture·모델·proposal-only 경계에서 수행한 v3/v4 진단도 이전 artifact를 덮어쓰지 않고
  각각 보존한다. v3 report `solo-liquidai-shadow-baseline-v3.json`은 `33530` bytes, SHA-256
  `f6d6e8de0fc7aad342c0bd68487f1e416f922c75e6ba87cd8463c9b990468fa8`이고 companion은
  `solo-liquidai-shadow-baseline-v3-attestation.json`이다. v4 report
  `solo-liquidai-shadow-baseline-v4.json`은 `34697` bytes, SHA-256
  `ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5`이고 companion은
  `solo-liquidai-shadow-baseline-v4-attestation.json`이다. v3/v4도 모두
  `SOLO_PROVISIONAL`/`REPORT_ONLY`, acceptance `NOT_MET`, training `NO_GO_FOR_TRAINING`, LoRA
  `NO_GO`이며 `PASS`가 아니다. v4는 response/inference `24/24`, semantic IR/canonical/domain
  `1/1/1`, failure observation `69`(고유 case `23`, 중첩 `46`), wrong-local `23`, invented precise
  date `0`, local overflow `1`, missing overflow `1`이었다.
- 최종 prompt/schema v5 report는
  `backend/target/evaluation/solo-liquidai-shadow-baseline-v5.json` (`35035` bytes, SHA-256
  `ba9c069d85c038d5c5603f8ddddfeae03aa8778cca7a949180142fee9b873102`)이고 companion restoration
  evidence는 `solo-liquidai-shadow-baseline-v5-attestation.json`이다. response/inference는
  `24/24`, semantic IR/canonical/domain은 `8/8/7`, failure observation은 `49`(고유 case `17`,
  중첩 `32`)였다. LiquidAI는 wrong-local `16`, invented precise date `2`, local overflow `1`,
  missing overflow `1`, route accuracy `0.375`였고 Fake는 canonical/domain `24/24`, route accuracy
  `1.0`, 같은 safety error `0`이었다. all-attempt p50/p95/max/mean은
  `17172.783`/`31117.602`/`31305.739`/`18804.994 ms`; allocation은 context `8192`에서
  `3166835834` bytes였다. 비독점 device-wide sampler는 `906` sample·miss `0`, baseline/first/last/max
  `3260`/`3243`/`3249`/`7196 MiB`, maximum utilization `93%`를 관측했으며 model-exclusive peak는
  `NOT_AVAILABLE`이다. v4 대비 semantic/canonical/domain, failure, unique case, wrong-local, p50은
  개선됐지만 invented precise-date가 `0→2`, p95가 `30973.996→31117.602 ms`로 악화됐고 두 overflow
  문제가 남아 acceptance는 계속 `NOT_MET`이다.
- v5 postflight/attestation은 runner·relay/model/listener/process와 scoped temporary resource 복구,
  exact model tag/digest 보존, product HTTP/canonical read/write/Apply `0`을 기록한다. 이 근거는
  runner 범위에 한정하며 개인 메모·개인 PostgreSQL·canonical data를 읽거나 바꾸지 않았다.
  사용자 결정에 따라 fine-tuning/LoRA는 더 진행하지 않고 학습 도구도 설치하지 않는다.
- 후속 deterministic guarded skill v6 report는
  `backend/target/evaluation/solo-liquidai-deterministic-skill-v6.json` (`45708` bytes, SHA-256
  `a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f`)이고 companion restoration
  evidence는 `solo-liquidai-deterministic-skill-v6-attestation.json`이다. Fake가 proposal의
  authoritative producer이고 deterministic skill은 검증된 projection만 만들며, LiquidAI에는 기존
  item title ordinal 선택과 proposal을 바꾸지 않는 topic ordinal 진단만 허용했다. 모델 요청은
  `24`회였으나 완료 response는 `0`, `MODEL_TRUNCATED_RESPONSE` 거절과 skill fallback은 각각 `24`회라
  모델 기여는 없었다. Fake/skill/guarded arm은 모두 canonical schema와 domain `24/24`, route accuracy
  `1.0`, wrong-local·안전 오류·protected-field mismatch `0`이었다. 따라서 `GuardedSystem MET`는 전적으로
  deterministic Fake/skill fallback의 경계 성과이고 LiquidAI에 귀속되지 않는다. model contribution과
  전체 development acceptance는 모두 `NOT_MET`이다. p95는 Fake `9.509 ms`, skill `0.923 ms`, selector
  `491.271 ms`, end-to-end `497.976 ms`; Ollama allocation은 context `2048`에서 `2977033092` bytes였다.
  이 public-visible 실행은 `SOLO_PROVISIONAL`/`REPORT_ONLY`/`PUBLIC_VISIBLE_DEVELOPMENT_ONLY`이고 개인
  메모·PostgreSQL·canonical data·제품 API/Apply에 접근하지 않았으며 RAG도 사용하지 않았다.
  companion은 runner/relay 종료, model unload, Ollama process/listener `0`, Docker Desktop `OFF`
  원복, canonical Docker fingerprint 불변, scoped temp 제거를 기록한다.
  fine-tuning/LoRA는 수행하지 않았고 training `NO_GO_FOR_TRAINING`, LoRA `NO_GO`다. 당시 다음 단계는
  LiquidAI 없는 skill-only path였다. RAG는 검색으로 해결 가능한 필요가 먼저 확인된
  경우에만 허용 목록과 문서·검색·context 크기를 고정한 public/de-identified corpus로 별도 비교한다.
  이 결과는 제품 runtime 연결이나 provider/model readiness를 의미하지 않는다.
- v7-A output-cap, v7-B prompt-overhead, v8-A compact-wire 진단까지 완료했다. v7-A report/attestation은
  `5925`/`7874` bytes, SHA-256
  `5b6a578b2b2222fc6180a4f70af7718526ccce2e127b070a404477a30c19d20f` /
  `bccc6a0856ea9055f199d381e7be28e0e8587373687ab1d148f3617e69c4c617`; v7-B는
  `7081`/`9743` bytes, SHA-256
  `c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37` /
  `ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d`다. v7-A는
  `num_predict 64→128` 뒤에도 STOP/LENGTH/accepted/fallback `0/24/0/24`, prompt tokens `9765`,
  selector p95 `923.668 ms`였다. v7-B는 prompt tokens를 `5973`으로 줄여 total `3792`, case당
  `158` tokens를 절감하고 p95를 `823.686 ms`로 낮췄지만 결과는 다시 `0/24/0/24`였다.
- v8-A report는 `backend/target/evaluation/solo-liquidai-compact-wire-diagnostic-v8a.json`
  (`11150` bytes, SHA-256
  `bd9f4419fb26b8a2950b80722eef746fff41e4418a8c52ccb94aafc7333365e3`), attestation은
  `solo-liquidai-compact-wire-diagnostic-v8a-attestation.json` (`12184` bytes, SHA-256
  `97e7c67a9a1f01140be7ad25734ce7080002b367ea7c87772c8a4c8287b4cdab`)이다. strict compact
  `{v,p,t}` wire와 무수정 deterministic mapping도 24/24 evaluation cap `128` LENGTH,
  STOP/accepted/fallback `0/0/24`였다. prompt tokens `6093`, Fake/selector p95
  `10.131`/`855.907 ms`, ratio `84.482×`; guarded schema/domain `24/24`, 누출·protected mutation
  `0`이었다. 비독점 GPU sampler는 sample/miss `59/0`, baseline/max `3033`/`6175 MiB`, max
  utilization `92%`였고 exclusive peak는 주장하지 않는다. fine-tuning/LoRA 미수행, RAG 미사용,
  product/provider `NO_GO`이며 Ollama/Docker/temp는 원래 상태로 복구됐다. 세 진단 모두 truncation을
  해결하지 못했으므로 LiquidAI shadow의 authoritative 결정은 `NO_GO`였고 당시 기본 경로는
  deterministic skill-only였다. 현재 개인 path는 그 historical 결론과 별개인 user decision 및
  ADR 0008의 `AI_PREFERRED`/approved-type-hint 제한을 따른다.
- `UNKNOWN` 유형은 UI가 자동 확정하지 않으며 사용자가 유형을 선택하고 항목을 추가해야 적용할 수 있다.
- `GET /analysis-review-outcomes/summary`는 authenticated owner의 rolling `proposal.created_at` cohort에서 현재 run 상태, proposal별 최신 application/undo 상태, versioned latest-selection 비교를 raw content와 identifier 없이 집계한다. `exact`는 “제안 그대로 적용”일 뿐 AI 정확도 label이 아니며, reject에는 corrected target이 없고 `currentPostponed`는 과거 보류 event history가 아니다. 1,001번째 proposal이 있으면 부분 집계를 반환하지 않고 1,000개 cap 오류로 fail-closed한다.
- Relation review는 저장된 proposal 배열 순서의 owner-visible bounded label만 no-store로 해소하며 모든 후보를 기본 미선택으로 둔다. Apply는 client가 target/type/score를 다시 주장하지 못하게 `proposalIndex`만 받고, exact opaque `sourceCandidateId`를 적용 item 하나에 매핑한 뒤 owner의 `ACTIVE` MEMO/TAG target을 transaction 안에서 다시 잠근다. 명시 `[]`는 관계 전부 거절이며 non-empty proposal에서 field 누락은 `422 RELATION_SELECTION_REQUIRED`다. V18 row와 resolved selection provenance는 application과 함께 원자적으로 생성되고 undo가 source item보다 먼저 제거한다. TAG relation은 `item_tags`와 별개이며, 네 relation type의 graph edge 의미가 결정될 때까지 current MEMO_TAG graph/neighborhood/`projectionVersion`에는 투영하지 않는다. `review-default-v3`도 relation adjudication target이 없어 non-empty relation proposal을 계속 `UNCLASSIFIABLE`로 분류한다.
- 개인 overlay 밖의 실제 로컬 모델, 모든 클라우드 LLM, Web Push, 완전한 오프라인 동기화,
  자동 taxonomy migration, 노드 압축은 아직 연결하지 않는다.
- private personal-PC checkpoint는 단일 operator-provisioned owner가 신뢰하는 RFC1918 LAN에서
  온라인으로 쓰는 접근 제한형 private beta다. production overlay 위에 기존 frontend Nginx의
  private-LAN TLS listener만 추가하고 공유기 port forwarding은 하지 않는다. backend와 PostgreSQL은
  host port가 없고, actual personal values·database secret·CA/private leaf key·backup은 Git 밖에 둔다.
  첫 local account는 TTY password를 받는 non-web `bootstrap-account` command로 한 번만 만들며 운영
  registration과 private overlay의 Google 기능을 열지 않는다. 기본 제품 분석은 Fake +
  `UNCERTAINTY_ONLY`이고, personal overlay는 `AI_PREFERRED`라 validated memo마다 pinned localhost
  LiquidAI의 `KEEP`/제한된 `PATCH`를 호출한다. 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`이며 자동
  Apply·RAG/vector/embedding ingestion·automatic rule promotion·training·fine-tuning·LoRA·알람
  delivery는 없다.
- `scripts/personal`은 Windows에서 local CA/leaf와 ignored config, `Documents\PersonalMemo\Backups`, exact-project start/stop/status, checksummed logical backup, Windows session을 가로지르는 private exclusive lock과 forward-only 실패 처리를 갖춘 database credential rotation, 별도 project restore 검증을 제공한다. Windows PowerShell 5.1 native UTF-8 capture와 secret-bearing JSON error 비노출도 회귀 검사한다. 기준 기기는 Galaxy S24 Ultra이며 safe-area·44/48px touch target·384/412px·landscape·secure context·manifest/SW installability 자동 검사를 추가했지만 실제 기기의 CA 설치·키보드·cutout·home-screen 설치는 사용자가 검증해야 한다.
- 계정별 연속 5회 실패 시 15분 잠금은 구현되어 있다. 공개 배포 전 account hardening의 다음 순서는 local email verification, password reset delivery, IP·edge rate limit/abuse protection, MFA/passkey 검토와 account deletion이다. 공개 fixture만 사용하는 solo 분석 개선은 v8-A compact-wire 진단까지 완료됐고 LiquidAI shadow는 `NO_GO`, 이후 기본 경로는 skill-only다. RAG는 retrieval 필요가 확인된 뒤에만 bounded public/de-identified 비교로 진행한다. 실제 provider/model 승인 경로에는 여전히 사람 둘이 안전 packet을 각각 보고 version-2 gold manifest를 직접 작성하는 실제 review와 human resolution, 승인된 binding label policy와 independently adjudicated dataset v3, 사전 threshold를 등록한 별도 blind release, provider/region·retention·grant UX와 ledger purge 정책 결정, 실제 provider model-token/cost 숫자 수집·집계·budget enforcement가 필요하다. Agent는 manifest를 만들거나 채우거나 승인하지 않는다. `review-default-v3`, V13 consent/outcome, V14 snapshot/token evidence, V15 durable lifecycle·bounded production recovery, V16 exact tag/alias context와 V17 내부 attempt ledger, owner-scoped outcome 집계, packet/manifest verifier/overlay 검증 준비, visible v1–v8-A shadow 결과와 blind harness 경계만으로 실제 blind 결과·실사용 교정 표본·provider privacy/비용/운영 조건이 충족되지는 않는다. 실제 LLM gate는 계속 닫혀 있다.
- 위 `NO_GO`와 닫힌 gate는 LiquidAI를 authoritative analyzer/provider 또는 학습 source로 승인하지
  않는다는 뜻이다. 사용자가 별도로 승인한 personal AI-preferred semantic-patch path는
  `SOLO_PROVISIONAL`/`REPORT_ONLY`로만 열며, `num_predict=1024`는 synthetic smoke에서 STOP을
  관측하기 위한 provisional hidden-reasoning budget이다. visible output/response/proposal 상한은
  별도로 유지한다. eligible 사용자 type 교정은 current memo의 exact-unique anchor+kind K=3 hint에만
  사용하고 자동 학습·rule promotion하지 않는다. 결정론 규칙 승격은 별도로 synthetic
  positive/negative fixture를 사람이 검토해야 한다.
- 2026-08-21 product-like synthetic smoke는 exact installed tag/digest와 공개 합성 문자열 3건만
  사용했다. `6시 디스코드 접속하기`는 약 `5.304 s`에 STOP으로 끝나 원문 substring 기반 TASK
  patch가 schema/domain 검증을 통과했고, `접속하기 싫다`와 `접속하기 좋은 시간`은 약
  `9.523 s`/`8.485 s`에 LENGTH로 끝나 상세 검토로 복귀했다. 정확한 날짜나 알람은 만들지 않았고,
  개인 API/DB/메모에 접근하지 않았으며 Ollama와 scoped temporary resource를 원복했다. 이 3건은
  `SOLO_PROVISIONAL`/`REPORT_ONLY` 안전 smoke일 뿐 Fake와 동등한 benchmark나 provider GO가 아니다.
- 2026-08-28 permanent isolated product-path smoke는 같은 공개 synthetic 3건을 별도
  Fake/`UNCERTAINTY_ONLY`와 exact LiquidAI/`AI_PREFERRED` tmpfs PostgreSQL stack에서 실제
  register → memo → analysis-run → proposal-read API로 비교했다. strict aggregate receipt는
  `backend/target/evaluation/ai-preferred-product-smoke-20260828T054016485Z.json`, SHA-256
  `d605ed48935d8dd5acbd98ff7e658c495f70cb1467f69ad8efb1b656f5fcca3b`다. 두 arm 모두 최종
  schema/domain 3/3, 긍정 TASK 1/1, 부정/서술형 TASK 승격·날짜 발명·hallucination·canonical delta
  0이었다. LiquidAI success/fallback은 1/2, median은 `6958 ms`로 Fake `73 ms`의 `95.3151×`였고
  semantic improvement는 `NOT_DEMONSTRATED`다. 비독점 GPU baseline/max/post는
  `3306`/`6478`/`3045 MiB`, utilization max `93%`, exact-target max VRAM `3012684676` bytes였다.
  개인 data/service와 기본 Ollama는 접근하지 않았고 Apply/tool/mutation은 0, cleanup 뒤 scoped
  Docker/image/network/volume/temp/`11435` listener는 0, personal container count는 3→3이었다.
  Receipt는 `dirty=true`이므로 clean-release attestation이 아니다. 상태는
  `PASS_NARROW_PRODUCT_PATH`, `SOLO_PROVISIONAL/REPORT_ONLY`; provider/training/LoRA `NO_GO`, RAG
  unused, automatic Apply disabled다. 이 결과가 닫은 것은 repeatability tooling gap뿐이며 다음은
  S24 device acceptance, 충분한 owner-approved review evidence, independent human/blind gate와
  provider privacy/cost 결정이다.
- 2026-08-28 후속 source slice는 owner-scoped raw-free
  `GET /api/v1/analysis-path-evidence/summary`와 lazy “분석 경로 진단” UI를 추가했다. 설정 경로,
  dispatch, `PENDING`, `LOCAL_FALLBACK`을 실제 모델 호출로 해석하지 않고 로컬 모델 설정 경로의 성공
  결과 반영 상태도 정확도/개선과 분리한다. Disposable backend `126 suites / 905 tests`(skip 1),
  SpotBugs 0, frontend `46 files / 465 tests`, lint/type/PWA build, OpenAPI lint와 targeted Playwright
  `1/1`이 통과했다. 개인 memo/session/PostgreSQL/canonical/API/Apply와 Ollama를 사용하지 않았고
  개인 container IDs/health는 전후 동일했다. Source qualification 시점에는 개인 앱에 미배포였고
  `SOLO_PROVISIONAL`/`REPORT_ONLY` 상태에서 별도 backup/rebuild/update 승인을 기다렸다.
- 2026-08-30 owner 승인 뒤 기존 V23 개인 stack의 writer만 정지하고 checksummed backup
  `personal-memo-20260830-021312804Z.dump`(144430 bytes, SHA-256
  `33F5F606C104D885F47BCA806DB7D62EA1B6F154762751D248B8EAA598E6D726`)과 disposable V23→V23 restore
  rehearsal을 통과한 뒤 base+prod+personal만 재빌드했다. 이전 frontend/backend는
  `rollback-pre-analysis-path-20260830-021222Z`, 새 image는
  `sha256:510e19f697c63c2dae0c1b23b59f06fa18e2775048ece7fb0ddb704d95e33cdc` /
  `sha256:26b0219a53e85d09e9f079f58a3ea3420d96fc9761db1bf15d81344d11eb1745`와
  `deployed-analysis-path-20260830-0216Z` tag로 보존했다. 세 service/trusted TLS/PWA와 Flyway V23/failed
  0, 동일 canonical volume, artifact controller/endpoint/UI label, 비인증 401/no-store가 통과했다.
  Postflight/live smoke는 실제 owner aggregate나 memo/session/canonical API를 호출하거나 row 내용을
  열람하지 않았고 Apply/public overlay도 실행하지 않았다. Cloudflare와 Ollama process/listener는
  0이었다. 새 모델 품질/latency/GPU 비교는 `NOT_RUN_NO_CLAIM`; 상태는 계속
  `SOLO_PROVISIONAL`/`REPORT_ONLY`다. Docker AF_UNIX 복구용 0-byte socket 격리 directory 3개는 활성
  runtime 밖에 있으나 Windows가 child 삭제를 거절해 강제 삭제하지 않았다.
- 2026-08-30 ADR 0018 source milestone은 calendar 공개 경계를 재사용하지 않고 owner-only remote PWA용
  `app-public-edge`(loopback 8788), 별도 `PersonalMemoAppCloudflareTunnel`(metrics 49313), protected
  token-file/Manual service와 connector-first rollback을 준비한다. Edge는 exact Host/Origin,
  method/path/header allowlist, body/rate/timeout/connection bounds, fixed-class raw-free logs,
  authoritative CSP/HSTS/no-store를 강제하고 calendar/actuator/internal/register/초기 OAuth를 막는다.
  Raw Cookie는 전달하지 않고 bounded `SESSION`/`XSRF-TOKEN`만 재구성해 `CF_Authorization`과 임의
  cookie를 origin에서 제거하며, frontend request-scoped error log도 raw target을 남기지 않는다.
  Access identity는 Spring owner로 매핑하지 않으며 기존 session/CSRF/JSON/domain/manual Apply가 권위다.
  Source/local synthetic는 개인 DB/memo/session/canonical/Apply와 live Cloudflare traffic을 사용하지 않는다.
  최종 disposable smoke는 host/origin/method/header/cookie/body/cache/security/log 경계를 통과했고 rate
  120회에서 `200=23`, `429=97`을 관측했다. CONNECT는 browser-Fetch 금지 authority-form이라 probe하지
  않았고 perimeter 동작을 주장하지 않는다. Literal/encoded registration matrix path는 bodyless
  404/no-store이며 hash-shaped missing asset도 no-store다. Start source는 exact frontend image/network
  snapshot/rollback과 exact project/container/port/network/local probe를 강제한다. Synthetic
  container/network/volume/image와 18788 listener는
  0으로 복구됐으며 기존 personal frontend/backend/PostgreSQL은 모두 healthy, app connector service는
  미설치, calendar connector는 `Stopped`/`Manual`, cloudflared process와 관련 listener는 0이었다.
  실제 activation 전 exact hostname, Access owner email/IdP/default-deny/no-bypass/cache-bypass 및 Cloudflare가
  password/cookie/raw memo/canonical API traffic을 TLS 처리한다는 명시 승인이 필요하다. 현재 상태는
  `SOURCE_PREFLIGHT`, `SOLO_PROVISIONAL/REPORT_ONLY`; live는 그 전까지 `NO_GO`다.
- 2026-08-30 위 승인 뒤 설정된 owner app hostname의 exact-owner email-OTP Access와 entire-host cache bypass를 유지한
  별도 `personal-memo-app` Tunnel을 activation했다. Connector는 protected token-file-only
  `PersonalMemoAppCloudflareTunnel`, `Running`/`Manual`, metrics `127.0.0.1:49313`; local ready/diag 200과
  connected 4개, Dashboard `Healthy`/replica 1을 확인했다. Cookie 없는 외부 요청은 Access hostname으로
  302 redirect됐고, Access 인증 browser의 로그인 shell/비식별 capability가 응답했다. Connector-first
  rollback은 edge를 유지한 채 connector만 중지하고 같은 Access 인증 browser의 unique-query capability가
  app JSON/cache가 아닌 Cloudflare Tunnel `Error 1033`을 반환함을 확인한 뒤 재시작·Healthy·capability
  복구까지 통과했다. 개인 memo/session/PostgreSQL/canonical/API/Apply와 token은 읽거나 기록하지 않았다.
  2026-08-31 exact owner가 외부 email-OTP, application sign-in과 설정된 owner PWA hostname의 화면 로드
  성공을 직접 보고했다. 이는 user-reported acceptance이며 provider log나 별도 자동화 증거로
  확장하지 않는다. Application-session이 없는 `/auth/me` 401/no-store와 provider/customer log
  sentinel은 계속 미검증이다. 후속 PC 재부팅 뒤 Manual connector와 `app-public-edge`는 현재
  stopped이므로 외부 route는 다시 시작하기 전까지 사용 불가능하다. Qualification은
  `LIVE_OWNER_BETA`, `SOLO_PROVISIONAL/REPORT_ONLY`를 유지하며 public self-service/production은
  `NO_GO`다.
- 2026-09-01 Cloudflare Dashboard read-only recheck는 exact-owner/default-deny Access, all-path protected
  route, catch-all 404, Protect with Access와 entire-host cache bypass를 통과했다. App Tunnel은 reviewed
  connector start 뒤 `Healthy`가 됐지만 기존 installed PWA worker가 Access authorization callback에
  cached offline shell을 반환해 current acceptance는 실패했다. Connector-first rollback 뒤 app/calendar
  services는 `Stopped`/`Manual`, cloudflared process는 0, app edge는 healthy loopback이다. Shared
  case-insensitive `/cdn-cgi/access(?:/|\?|$)` `NetworkOnly`/navigation-deny fix는 lint, TypeScript,
  48 files/472 tests, source contract, production PWA build, generated-worker inspection과 disposable Edge
  E2E 1/1을 통과했다. Fresh private browser의 Error 1033 proof로 mutation hold를 해제한 뒤 이전
  frontend/app-edge를 rollback-tag하고 current source를 rebuild/deploy했다. Deployed worker marker,
  loopback root/health/capabilities/unauthenticated 401 no-store, connector-last startup과 body/cookie 없는
  external Access 302/no-store/cache-absent가 통과했다. App connector는 `Running`/`Manual`, calendar는
  `Stopped`/`Manual`, app edge는 healthy다. 이어서 exact owner는 안내한 post-fix health/root/application-
  PWA check가 정상 작동한다고 보고했다. 이는 user-reported acceptance다. Historical `LIVE_OWNER_BETA`
  evidence는 보존하고 current activation은 `LIVE_OWNER_BETA_REQUALIFIED`, overall status는
  `SOLO_PROVISIONAL/REPORT_ONLY`, public/production은 `NO_GO`다. 개인 memo/session/PostgreSQL/canonical/
  Apply/model과 Cloudflare 설정은 읽거나 변경하지 않았다.
- 2026-08-31 Milestone 7.1은 Today-first mobile home을 active next slice로 두고 빠른 capture와
  오늘의 미완료 task/확정 event를 첫 스캔 경로로, graph를 secondary retrieval로 재배치한다.
  요약 상태는 기존 in-memory connection/recovery/loading/error를 read-only로 파생하고 database,
  Ollama, Cloudflare Tunnel/Access/provider health를 주장하지 않는다. API/OpenAPI/JSON Schema/
  Flyway/canonical 계약을 바꾸지 않으며 browser에 Windows service, Docker, connector metrics/token
  제어를 넣지 않는다. Today-first source와 focused tests가 추가됐고 frontend lint, TypeScript,
  48 files/472 unit tests와 production PWA build가 통과했다. Backend-free synthetic Chrome smoke도
  production PWA의 384×854/854×384 Today-first shell, 기존 capture/connection 표시와 horizontal
  overflow 0을 통과했고 임시 preview/artifact를 제거했다. 상태는
  `SOURCE_QUALIFIED_LINUX_FULL_E2E_PASS_DEPLOYMENT_PENDING`이다. Commit
  `19ce1fbc49744ba9c6dbefbc313e48b36e5c81e6`의 GitHub Actions push run `33358387450`와 pull-request
  run `33358390766`이 API/OpenAPI, production Compose, Windows PowerShell source contracts,
  frontend, backend와 disposable Ubuntu production-like stack을 통과했다. Stack readiness 뒤
  primary/OAuth-state E2E 26건과 exact stack cleanup도 통과했다. 앱 내 화면 업데이트 확인 전에는
  배포 acceptance를 주장하지 않는다.
- 2026-08-31 M7.1 bounded requalification은 Node 24.19.0 / ESLint 9.39.2 / Vitest 4.1.10 /
  TypeScript 5.9.3 / Vite 7.3.6 / Playwright 1.60.0 + Edge로 lint, 48 files/472 tests,
  production PWA build, 384×854/854×384 synthetic shell 1/1과 horizontal overflow 0을 모두 다시
  통과했다. 631.21 kB chunk warning만 남았다. 개인 memo/DB/API/Apply/Docker는 접근하지
  않았고 preview는 종료했다. 핵심 code/test 8 files는 private local source checkpoint로 보존했고
  archive SHA-256은 `DEB5C332820417BDEC22C9BD76EF1BEE52C8AF82E65EC557A11579D87C13F563`이다.
- 2026-09-01 M7.2는 confirmed bounded graph를 default signed-in view로 바꾸고 연결/메모/일정/설정
  bottom navigation과 concise raw-save-first capture를 구현했다. Access control-path fix와 exact owner
  address surface가 같은 production bundle/E2E/source contract에 포함된다. ESLint, TypeScript,
  51 files/481 tests, PWA build, public-app source contract와 disposable production-like Playwright
  27/27이 통과했다. Owner-authorized 144,430-byte mechanical backup/checksum과 disposable V23-to-V23
  restore, connector-first Error 1033 proof, rollback tags, frontend/app-edge-only rebuild, local boundary
  smoke, connector-last start와 remote Access 302/non-HIT가 통과했다. App connector는
  `Running`/`Manual`, calendar는 `Stopped`/`Manual`, 네 personal container는 healthy이고 temporary
  restore/E2E container와 volume은 0개다. Personal memo/session/canonical/Apply/Ollama/Cloudflare 설정은
  읽거나 변경하지 않았다. 실행 image는 `deployed-m72-20260901-075555Z`, 이전 image는
  `rollback-pre-m72-20260901-074019Z` tags로 보존했다. Owner는 배포 화면의 시각 검토를 시작했지만
  final physical-device acceptance는 열려 있다. 상태는
  `SOURCE_QUALIFIED_PERSONAL_DEPLOYED_VISUAL_REVIEW_IN_PROGRESS`, `SOLO_PROVISIONAL/REPORT_ONLY`다.
- 2026-09-02 M7.3는 공개 synthetic `6시 디스코드 접속하기`처럼 날짜와 오전/오후가 없는
  1–12시 UNKNOWN 후보를 proposal review 안의 compact 명시 선택으로 처리한다. 날짜와 AM/PM은
  초기 선택하지 않고 `오늘`도 사용자 action일 때만 채운다. TASK `까지`/EVENT `부터` 방향만
  좁게 연결하며, `시간 없이 두기`도 explicit draft-only 선택이다. Confirm 뒤에도 기존 final Apply가
  필요하고 raw memo/proposal/canonical data는 그 전까지 바뀌지 않는다. Source-zone wall-clock
  resolution은 DST gap을 거절하고 overlap offset을 사용자에게 고르게 한다. Exact TASK due도 server
  canonical write 전에 immutable revision zone과 offset을 대조하며 mismatch는
  `DUE_ZONE_OFFSET_MISMATCH`다. Frontend ESLint, 52 files/505 Vitest tests, TypeScript 5.9.3, Vite
  7.3.6 PWA build와 OpenAPI YAML parse는 통과했다. Backend validator/unit/PostgreSQL no-write test와
  focused Playwright flow는 source에 추가됐지만 Maven/PostgreSQL/isolated E2E는 실행하지 않았다.
  Personal data/runtime/deployment는 건드리지 않았다. 상태는
  `SOURCE_IMPLEMENTED_FRONTEND_GATES_PASS_BACKEND_AND_E2E_PENDING`,
  `SOLO_PROVISIONAL/REPORT_ONLY`다.
- 2026-09-02 M7.4는 owner가 지정한 date-less-clock 기본을 ADR 0020으로 확정했다. 현재
  `fake-v10`/`korean-rules-v8` 계약은 무입자/`에` bare 1–12시 optional minutes, 오전/오후, Korean
  24-hour clock, `HH:mm`을 immutable revision `client_recorded_at`/`source_time_zone`에서 해석한다.
  작성 당일 capture instant보다 엄격히 미래인 가장 이른 safe occurrence만 `RELATIVE_EXACT`
  proposal로 제안한다. Equality는 미래가 아니다. DST-gap occurrence는 제외하고 더 늦은 unique
  same-day 후보를 허용하지만 미래 overlap occurrence가 있으면 전체 expression은 `UNKNOWN`이다.
  남은 safe 후보나 valid source zone이 없어도 `UNKNOWN`이고 다음 날로 넘기지 않는다. 과거
  fake-v8/fake-v9 평가·shadow·배포 기록은 당시 사실로 보존하며 fake-v10 결과로 relabel하지 않는다.
  Immutable product-smoke v1 fixture/schema/receipt는 보존하고 별도 public-synthetic v2 source
  contract를 추가했다. V2는 `2026-08-28T09:00:00+09:00`/`Asia/Seoul`에서 `6시 디스코드
  접속하기`를 grounded TASK와 `2026-08-28T18:00:00+09:00` `RELATIVE_EXACT` due candidate로
  고정하고 TASK due reference를 요구한다. Receipt v2는 Apply/alarm/personal/canonical access와
  canonical write delta 0을 유지한다. 이 v2 Docker/Ollama product smoke와 receipt는 실행하지 않아
  runtime PASS, latency, GPU/VRAM, Fake/LiquidAI comparison evidence는 없다.
  이 결정은 proposal-only/manual Apply, no automatic alarm/reminder 경계를 유지한다. Focused
  parser/analyzer/validator 106/106과 전체 non-PostgreSQL backend unit suite 663 executed/252
  environment-gated skipped/0 failed가 통과했다. Frontend ESLint, 52 files/529 Vitest tests,
  TypeScript/PWA build와 v2 product-smoke source-contract gate도 통과했다. Exact source SHA
  `2117cb2`의 GitHub-hosted Linux CI runs `33594337649`/`33594340097`은 OpenAPI, production/personal/
  public Compose/source contracts, Windows PowerShell contracts, frontend gates, disposable
  PostgreSQL 17.6/Flyway V1-V23 backend `mvn verify` 924 tests/0 failure/0 error/0 skip, 그리고
  production-like isolated Playwright 28/28을 모두 통과했다. Actual Docker/Ollama v2 product
  smoke는 실행하지 않아 runtime latency, GPU/VRAM, Fake/LiquidAI 비교 evidence는 계속 없다. 상태는
  `SOURCE_QUALIFIED_LINUX_FULL_E2E_PASS_RUNTIME_AND_DEPLOYMENT_PENDING`,
  `SOLO_PROVISIONAL/REPORT_ONLY`다.
- Docker Desktop 4.88.1 업데이트 후 `dockerInference`, secrets engine, `sailor-ingest.sock`순으로
  Windows host AF_UNIX stale-endpoint 실패가 재현됐다. 승인된 runtime-only 격리 외에 factory
  reset, clean/purge, volume/VHDX 삭제는 하지 않았고 Docker는 stopped로 두었다. 2026-09-02 M7.4
  local isolated qualification 시도에서도 backend가 `sailor-ingest.sock` 제거 실패로 container 시작
  전에 중단됐다. 새 quarantine/reset은 하지 않고 이번에 시작된 Docker process만 종료해 원래 stopped
  상태로 복구했으며 개인 container/volume은 열거나 변경하지 않았다. 로컬 진단 묶음은
  private ACL로 생성했지만 열어보거나 외부로 업로드하지 않았다. 2026-08-31 기준 동일
  Docker tracker issue는 open이고, Windows build 26200.9168에 pending update/reboot은 없으며,
  WSL 2.7.12 release note에도 해당 수정은 없어 설치하지 않았다. GitHub-hosted Linux의 disposable
  full E2E 통과는 이 Windows host defect를 해결했다는 뜻이 아니다. 이 blocker가 해결되기 전에
  personal V23 rebuild나 Windows-host deployment acceptance를 진행하지 말 것.
- 2026-09-02 M7.5 source work follows owner visual feedback: the `추가` destination is capture-only,
  while the default connection view owns a collapsed private memo finder. The old visible all-memo
  card list is no longer part of capture; active edit/proposal/trash and trashed restore actions remain
  available from graph/search detail. The bounded 100-node home graph is not widened, raw/off-home
  search results are not injected into React Flow, and proposal-only/manual Apply remains unchanged.
  ADR 0021 records this frontend-only decision. No personal memo/session/PostgreSQL/canonical/Apply,
  Docker, Ollama, Cloudflare, connector, or deployment state was accessed or changed for this source
  slice. ESLint, TypeScript, 53 Vitest files/532 tests, the Vite 7.3.6 production PWA build, the
  public-app source contract, and backend-free Microsoft Edge portrait/landscape synthetic flows 2/2
  passed. Full disposable PostgreSQL/production-like E2E was not run. Current source status is
  `SOURCE_QUALIFIED_FRONTEND_FOCUSED_E2E_PASS_FULL_STACK_AND_DEPLOYMENT_PENDING`,
  `SOLO_PROVISIONAL/REPORT_ONLY`; personal deployment and physical-device acceptance are not claimed.
- 2026-09-05 M7.5 corrective source work follows the owner's audit and fix request. The original two
  shell tests and three static action tests did not establish modal edit/recovery safety; ADR 0021
  now states that limitation explicitly instead of treating the original 532-test gate as proof of
  every new interaction. The corrective work keeps updates and retries bound to the submitting
  selection/request generation, shows save failures and stable-body/idempotency retries inside the
  active detail, reloads latest source without unmounting the draft, and requires an explicit
  `내 수정 내용 유지` choice followed by separate save after a revision conflict. Dirty edits prevent
  pin changes and retain their detail root through bounded graph refresh without adding graph nodes.
  Query-free recent/trash browsing uses existing owner-scoped arrays and explicitly shows a 50-per-
  lifecycle bound. This fixes the lost recent-browsing path, NOT unknown-text full-corpus traversal;
  pagination beyond that bound remains separate API/PostgreSQL/E2E work. No API/Schema/Flyway,
  analyzer, training, automatic Apply, personal data/session/DB, Docker/Ollama/Cloudflare, connector,
  deployment, commit, or PR mutation is part of this corrective source work.
- Operational handoff caveat: the preceding Docker-stopped/deployment-pending entries are historical
  snapshots, not a live-state attestation. Conversation history reports later personal update and
  connector work, but that lineage was not recorded here contemporaneously. This source-only turn
  does not invent deployment receipts or certify current services. Before another personal rollout,
  reconcile the approved deployment evidence and current non-content operational state; do not
  infer permission or current safety from either an old blocker or a conversation success message.
- Corrective verification on 2026-09-05: frontend ESLint, app TypeScript, 54 Vitest files/547 tests,
  Vite 7.3.6 PWA build, public-app source contracts, and `git diff --check` passed. Standalone E2E
  TypeScript checking passed with bundled Node types, without changing dependencies; Playwright
  collection found 38 tests. Only 12 browser tests were executed (10 new recovery/browsing scenarios
  plus the two existing mobile shell flows), passing both against Vite source and the production
  preview serving `index-Dmwd9ZN9.js` / `index-DFcbww19.css`. They exercised different/same reopened
  search selections during save, graph selection locks, in-dialog stable-request retry, graph/search
  conflict reload/draft preservation/explicit rebase/separate save, dirty pin/unpin guards, and raw-only
  recent/trash access with explicit restore and zero search requests. All API responses were synthetic
  mocks and unexpected API calls were asserted empty. The owned loopback preview and temporary test
  output were cleaned up. The existing >500 kB JavaScript chunk warning remains non-blocking.
  PostgreSQL/backend full verification, the full production-like E2E suite, CI publication, personal
  rollout, installed-PWA refresh, and physical-device acceptance were NOT run. Current corrective
  state is `SOURCE_CORRECTED_SYNTHETIC_INTERACTIONS_PASS_FULL_STACK_AND_DEPLOYMENT_PENDING`,
  `SOLO_PROVISIONAL/REPORT_ONLY`. The prior full-stack pass belongs to its earlier source, not this
  uncommitted corrective worktree based on `bd443b4`.
- 2026-09-05 continuation expanded the browser matrix to the recent-memo detail too: delayed
  completion after switching/reopening, stable-request save retry, and conflict reload/rebase/separate
  save. The built-PWA mocked subset passed 16/16 (14 recovery/browsing + two shell flows); collection
  now finds 42 tests, not 42 executed tests. Targeted E2E TypeScript and frontend lint passed. The
  previous 547-unit/build evidence remains attached to the unchanged product code. Five source
  contract gates passed (private deployment, public feed, calendar connector, owner-only app,
  AI-product-smoke); private ACL checks ran under Windows PowerShell 5.1 on a disposable test folder.
  PowerShell 7 cannot run the .NET Framework ACL helper, and the sandbox initially blocked the 5.1
  temporary ACL operation; the bounded unsandboxed 5.1 test passed without touching personal ACLs.
  All 36 PowerShell source files parsed. Nine Compose combinations passed daemonless `config --quiet`
  with an empty temporary Docker config, explicit `.env.example`, and synthetic parameters, never
  `.env.personal`. Docker Desktop processes and the Linux engine pipe were absent at preflight;
  Docker was not started. PostgreSQL/backend full verify, OpenAPI linter, edge-container smoke, and
  the 26 integrated primary flows remain unexecuted for this corrective source. Prefer the existing
  GitHub-hosted Linux CI after approval to publish the selected source-only branch/PR. No remote
  upload, merge, personal rebuild, canonical access, or connector action has occurred. Preview port
  5197 and its generated test output were cleaned up. Full-corpus query-free pagination is still
  pending, and graph-root disappearance is covered by unit-level snapshot retention rather than a
  newly exercised late-refresh browser scenario. Do not turn either limitation into a completed gate.
