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
4. **명확한 메모는 local-analysis 경계에서 끝내고, 외부 전송은 별도 동의 gate 뒤에 둔다.** 현재 구현은 서버의 결정론적 `FakeAnalyzer`와 `NO_NETWORK` Fake gateway이며, 휴대폰 모델과 실제 cloud Agent는 아직 연결하지 않는다.
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
→ 날짜 파서 + 유형 분류 + 임베딩 검색
→ 필드별 모호성 판별
   ├─ 명확: 로컬 후보 표시
   └─ 모호: 백엔드 → 클라우드 Agent → 후보 보완
→ 사용자 승인·수정
→ 백엔드 검증
→ 태그·관계·할 일·알림 반영
→ 그래프 투영 업데이트
```

클라우드 Agent에는 원문 전체 저장소를 제공하지 않는다. 로컬 결과와 백엔드가 검색한 관련 태그·메모 상위 후보만 전달한다.

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
- 외부 캘린더 동기화
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

### Phase 5 — 태그 진화·노드 압축

- provisional tag
- canonical tag와 alias
- 태그 centroid 증분 갱신
- 배치 기반 merge/split/archive 제안
- 기간·주제·상태 기반 가역적 cluster node

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
- 실제 모델 선택은 샘플 한국어 메모 평가 결과로 결정한다.

## 12. 현재 구현 체크포인트

- Phase 0과 Phase 1의 AI-free 수직 흐름은 구현되어 있다.
- Flyway `V1`–`V15`가 memo/revision, proposal/application, canonical item/tag/task, owner integrity, revision capture context, analyzer·prompt·local model·embedding model·routing policy provenance, local/Google identity, JDBC session schema, claimed user identity 무결성과 일회성 initial-account provisioning gate를 관리한다. `V11`은 owner별 proposal의 최신 application을 찾는 review-outcome 조회 인덱스, `V12`는 memo별 최신 `APPLIED` selection과 활성 item을 읽는 graph projection partial index만 추가한다. `V13`은 boolean-only legacy cloud consent를 폐기하고 owner row의 exact policy-version·granted-at pin을 강제하며, run마다 cloud transfer/gateway/provider/model/policy/outcome evidence를 추가한다. `V14`는 새 run의 내부 authorization/grant snapshot과 결정론적 provider-request token을 일관되게 저장하고 과거 row는 `legacy-v0`로 보존한다. `V15`는 호출 전에 `durable-v1` run과 1:1 `analysis_run_dispatches` preparation을 commit하고 immutable executor binding·descriptor, deadline/lease/fence, reserved proposal와 idempotency evidence를 보존한다. V14까지의 row에는 dispatch를 소급 생성하지 않는다. 새 raw analytics 복제본이나 clickstream table은 만들지 않는다.
- 각 local/Google 로그인 수단은 internal UUID에 매핑되고, 명시적으로 연결한 두 수단은 같은 UUID와 PostgreSQL-backed server session을 사용한다. Google email만으로 자동 연결하지 않고 기존 로그인 뒤 명시적 link intent를 요구하며, 마지막 login method는 해제할 수 없다. domain owner는 client 값이나 개발 상수가 아니라 Spring Security context에서 가져온다.
- React 인증 shell은 capability·CSRF·현재 session을 먼저 확인하고, 로그인 전에는 owner domain API를 호출하지 않는다. service worker는 API와 OAuth/login 경로를 cache하지 않는다.
- owner별 원문 capture draft는 browser localStorage에 동기식으로 보존하고 저장소 실패를 사용자에게 알린다. 제안 수정·새 태그 입력·원문 revision 편집은 통합 dirty 상태로 추적하며, OAuth·로그아웃·브라우저 이탈을 확인하고 service-worker 업데이트는 사용자가 선택하되 미저장 편집 중에는 적용하지 않는다.
- 인증 통합 테스트는 local 가입·로그인, CSRF, session rotation, owner 격리와 mocked OIDC 연결/해제를 검증한다. 실제 Google credential과 provider network round trip은 사용하거나 검증했다고 간주하지 않는다.
- 12개 regression + 12개 `VISIBLE_CHALLENGE` 한국어 fixture, version-2 fixture JSON Schema, raw content를 포함하지 않는 결정론적 평가 report, revision 기준 날짜 파서, `field-policy-v1` ambiguity gate, Draft 2020-12 runtime contract와 strict domain validation이 구현되어 있다. version 2는 route/type/signal뿐 아니라 date mention/item/item-source-span 지표도 report에 노출한다. `fake-v6` / `korean-rules-v4`는 기존 날짜·행동·참조·multi-intent 규칙과 원문 기반 순차 item/source-span 추출을 유지하면서 proposal schema v2를 생성한다. v2의 각 date candidate에는 proposal-local `candidateId`, 각 item에는 nullable `dueDateCandidateId`가 필수이며, TASK item만 존재하는 정밀 date candidate를 참조할 수 있다. schema v1 proposal은 recovery와 outcome 재구성을 위해 계속 지원한다. `providerMetadata`의 다섯 version은 각각 1–64자, 필수 `toolCalls`는 0–100이며 proposal은 64 KiB, metadata는 8 KiB로 제한된다. 이 변경은 기존 `analysis_runs.schema_version`과 `analysis_proposals.proposal_json`을 사용하므로 Flyway migration이나 과거 JSON rewrite가 필요하지 않다.
- proposal 단건 GET과 recovery list는 `X-Analysis-Proposal-Schema-Version`을 협상한다. 무헤더/`1`은 저장된 v2의 응답 복사본만 strict v1으로 내려 설치된 구형 PWA를 보호하고, 현재 PWA는 `2`를 보내 저장된 version을 받는다. 과거 v1은 `2` 요청에도 v1이며, invalid/combined 값은 `422 UNSUPPORTED_PROPOSAL_SCHEMA_VERSION`이다. 성공 응답은 `Cache-Control: no-store`와 schema-header `Vary`를 포함하고 JSONB/hash/run version을 수정하지 않는다.
- Apply due의 client `timeZone`은 호환성 입력으로 유효성만 검사한다. canonical `task_details.source_time_zone`은 잠근 immutable memo revision의 capture zone으로 서버가 교체하므로 승인 기기·여행 중 zone이 date-only overdue 경계를 바꿀 수 없다.
- 공개된 합성 `VISIBLE_CHALLENGE`는 blind/general accuracy가 아니며 계속 report-only다. regression hard gate는 proposal schema/domain validity, 기존 route/type/signal wrong-local 0, invented precise date 0, local overflow 0, missing overflow signal 0, unresolved action/object hallucination 0으로 제한한다. 현재 공개 자료의 item cardinality는 양 split 12/12 case, 필수 source span은 regression 15/15·visible challenge 14/14개가 일치하지만 date mention/item/item-source-span quality rate와 semantic false-confident-local은 독립적인 2인 gold adjudication과 외부 blind 실행 전까지 진단 지표다. evaluation dataset v2에는 date-to-item binding gold가 없으므로 report는 `SUPPORTED_NOT_SCORED_DATASET_V2`만 선언하고 binding 품질을 hard metric으로 승격하지 않는다. strict v2 2인 review schema/verifier와 immutable v2 release를 참조하는 ID-only v3 binding overlay integrity validator는 준비됐지만, 실제 human review manifest·adjudication·v3 dataset·binding score·`PASS`는 없다. `docs/EVALUATION_LABEL_POLICY.md`도 human approval 전 draft다. 저장소 밖의 독립적 human-curated version-2 envelope만 받는 external blind harness와 aggregate-only privacy 경계는 구현되어 있지만 실제 blind 데이터와 사전 등록된 metric `PASS` 정책은 저장소에 없으며 실행했다고 주장하지 않는다. 자세한 진입 조건은 `docs/EVALUATION.md`를 따른다.
- 명확한 결과는 cloud 호출 없이 `LOCAL`/`REVIEW_REQUIRED`로 저장된다. 모호한 결과는 server-owned gateway descriptor를 확인한 뒤 `HYBRID`로 저장된다. `NO_NETWORK` Fake는 consent가 필요 없고, `EXTERNAL_MEMO_CONTENT`는 authenticated owner의 consent boolean·정확한 policy version·non-null 승인 시각이 모두 맞고 `granted_at`이 권한 확인 시각보다 늦지 않아야 호출된다. 미동의·policy mismatch·revoke·미래 시각 grant는 gateway 0-call과 `CONSENT_REQUIRED` evidence를 남긴다.
- LOCAL, cloud SUCCESS, fallback의 모든 새 proposal은 공통 allowlist canonicalizer가 `providerMetadata`를 다시 만든다. cloud typed failure, gateway/descriptor 예외, invalid enriched proposal은 run을 rollback하지 않고 provider 오류 text 없이 server-owned outcome만 남기며, 검증된 local proposal을 `HYBRID`/`REVIEW_REQUIRED`로 저장해 UI가 상세 검토하도록 한다. raw revision과 canonical tag/task/relation은 바뀌지 않는다.
- 현재 실제 external provider와 consent grant/revoke API는 없다. V15는 gateway 호출 전 durable preparation commit, immutable executor binding·descriptor 재검증, DB transaction 밖 bounded timeout 호출, claim/lease/fence/deadline과 caller-driven same-key/body retry, finalize transaction의 memo revision 재검사를 구현한다. 공개 POST는 내부 `QUEUED`/`RUNNING`/`PENDING`을 반환하지 않고 최종 `RunView`를 동기로 유지한다. 같은 key의 live lease/call이 coordination window를 넘으면 `409 ANALYSIS_IN_PROGRESS`이고 caller가 같은 key/body로 다시 호출한다. revision 변경은 `STALE` finalize를 먼저 commit한 뒤 `409 STALE_MEMO_REVISION`을 반환한다. 내부 run evidence와 dispatch의 raw validated-local proposal·binding·provider token은 HTTP/proposal/metadata/log에 노출하지 않는다. transport는 at-least-once이므로 실제 provider의 token 멱등 처리가 필수다. background worker·재시작 자동 recovery·attempt history·duration·model-token·cost와 top-k context는 미구현이다.
- `UNKNOWN` 유형은 UI가 자동 확정하지 않으며 사용자가 유형을 선택하고 항목을 추가해야 적용할 수 있다.
- `GET /analysis-review-outcomes/summary`는 authenticated owner의 rolling `proposal.created_at` cohort에서 현재 run 상태, proposal별 최신 application/undo 상태, versioned latest-selection 비교를 raw content와 identifier 없이 집계한다. `exact`는 “제안 그대로 적용”일 뿐 AI 정확도 label이 아니며, reject에는 corrected target이 없고 `currentPostponed`는 과거 보류 event history가 아니다. 1,001번째 proposal이 있으면 부분 집계를 반환하지 않고 1,000개 cap 오류로 fail-closed한다.
- Apply 계약은 relation 선택·canonical 저장·undo가 구현되기 전 non-empty `relationCandidates`를 `409 PROPOSAL_RELATIONS_UNSUPPORTED`로 거절한다. 관계를 조용히 누락한 채 run을 `APPLIED`로 만들지 않으며 raw memo는 보존한다.
- 실제 로컬 모델·클라우드 LLM, Web Push, 완전한 오프라인 동기화, 자동 taxonomy migration, 노드 압축은 아직 연결하지 않는다.
- private personal-PC checkpoint는 production overlay 위에 기존 frontend Nginx의 private-LAN TLS listener만 추가한다. backend와 PostgreSQL은 host port가 없고, actual personal values·database secret·CA/private leaf key·backup은 Git 밖에 둔다. 첫 local account는 TTY password를 받는 non-web `bootstrap-account` command로 한 번만 만들며 운영 registration을 열지 않는다.
- `scripts/personal`은 Windows에서 local CA/leaf와 ignored config, `Documents\PersonalMemo\Backups`, exact-project start/stop/status, checksummed logical backup, Windows session을 가로지르는 private exclusive lock과 forward-only 실패 처리를 갖춘 database credential rotation, 별도 project restore 검증을 제공한다. Windows PowerShell 5.1 native UTF-8 capture와 secret-bearing JSON error 비노출도 회귀 검사한다. 기준 기기는 Galaxy S24 Ultra이며 safe-area·44/48px touch target·384/412px·landscape·secure context·manifest/SW installability 자동 검사를 추가했지만 실제 기기의 CA 설치·키보드·cutout·home-screen 설치는 사용자가 검증해야 한다.
- 계정별 연속 5회 실패 시 15분 잠금은 구현되어 있다. 공개 배포 전 account hardening의 다음 순서는 local email verification, password reset delivery, IP·edge rate limit/abuse protection, MFA/passkey 검토와 account deletion이다. 제품 분석의 다음 순서는 실제 두 사람의 version-2 gold review와 human adjudication, 승인된 binding label policy와 independently adjudicated dataset v3, 사전 threshold를 등록한 별도 blind release, owner-scoped tag/alias 후보 조회, provider/region·retention·grant UX 결정, top-k와 자동 recovery·duration/model-token/cost 관측성이다. `review-default-v3`, V13 consent/outcome, V14 snapshot/token evidence와 V15 durable caller-driven lifecycle, owner-scoped outcome 집계, review/overlay 검증 준비와 blind harness 경계만으로 실제 blind 결과·실사용 교정 표본·provider privacy/비용/운영 조건이 충족되지는 않는다. 실제 LLM gate는 계속 닫혀 있다.
