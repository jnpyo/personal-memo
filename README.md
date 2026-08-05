# Personal Memo

[![CI](https://github.com/jnpyo/personal-memo/actions/workflows/ci.yml/badge.svg)](https://github.com/jnpyo/personal-memo/actions/workflows/ci.yml)

형식 없이 적은 한국어 메모를 **검토 가능한 제안**으로 바꾸고, 사용자가 승인한 결과만 태그·할 일·그래프에 반영하는 모바일 우선 PWA입니다.

첫 번째 목표는 모델 성능이 아니라 데이터 무결성이었습니다. 실제 AI를 연결하기 전에 아래 흐름을 결정론적 Fake 분석기로 끝까지 구현하고, 재시도·수정 경쟁·되돌리기에서도 원문이 손상되지 않도록 설계했습니다.

```text
메모 입력
→ 원문 revision 저장
→ 결정론적 로컬 후보
→ 모호성 gate (`LOCAL` 또는 Fake cloud `HYBRID`)
→ 사용자 수정·승인 / 보류 / 거절
→ 태그와 할 일 생성
→ React Flow 그래프 투영
→ 새로고침 뒤 검토 상태 복구
→ 적용 되돌리기
```

## 핵심 설계

- **AI는 제안만 합니다.** 분석 결과는 별도 proposal로 저장되며, 승인 API만 canonical 태그와 할 일을 생성합니다.
- **원문과 기록 맥락을 독립적으로 보존합니다.** 메모 수정마다 immutable revision이 추가되고, 각 revision에는 브라우저가 기록한 시각과 IANA 시간대를 함께 저장합니다. 이전 revision의 늦은 분석 결과는 `STALE`로 바뀌어 적용할 수 없습니다.
- **재시도를 안전하게 처리합니다.** 주요 mutation은 요청 본문 해시와 `Idempotency-Key`를 함께 저장합니다. 같은 요청은 원래 응답을 재생하고, 같은 키의 다른 요청은 거절합니다. 메모 수정 재시도도 최초 시도에서 고정한 원문·client timestamp·시간대·키를 그대로 사용합니다.
- **승인 단위를 되돌립니다.** application provenance를 따라 파생 데이터만 제거하며 원본 메모와 revision 이력은 남깁니다.
- **기한 초과는 사실이 아니라 시점에 따른 상태입니다.** `OVERDUE`를 저장하지 않고 `TODO`와 현재 시각을 기준으로 조회할 때 계산합니다. 날짜만 지정한 기한은 UTC 자정으로 왜곡하지 않고 `due_local_date`로 보존합니다.
- **그래프는 canonical 데이터의 투영입니다.** 메모 유형을 거대한 공통 노드로 만들지 않고 노드의 속성·필터·아이콘으로 표현합니다.
- **소유권 경계는 서버와 데이터베이스에 있습니다.** 현재는 개발용 단일 사용자이지만 사용자 데이터는 `owner_id`를 가지며, V5의 owner-aware composite foreign key가 서로 다른 사용자의 하위 record를 데이터베이스 수준에서도 연결할 수 없게 합니다.
- **라우팅은 신호 기반으로 결정합니다.** 모델의 confidence 하나에 의존하지 않고 날짜·참조·행동·복합 의도 신호를 enum으로 검증합니다. 명확한 메모는 cloud를 호출하지 않으며, 모호한 메모도 현재는 외부 통신 없는 Fake adapter만 거칩니다.

## 구현 범위

### Frontend

- Android Chrome을 첫 대상으로 한 React 19 + TypeScript + Vite PWA
- 메모 캡처, 연결/실패/재시도 상태, 제안 제목·유형·태그 수정
- `UNKNOWN` 유형의 명시적 사용자 선택, 수동 항목 추가·제거와 부분 적용
- 활성/휴지통 메모 목록, 기록 시각·시간대를 포함한 새 revision 편집, 휴지통 이동·복원, 기존 메모 재분석
- 제안 승인·보류·거절과 마지막 application 되돌리기
- 새로고침 뒤 마지막 application과 검토 중·보류한 제안 복구
- `TODO` / `DONE` / `CANCELLED` 전환, 날짜 전용 기한과 기한 초과 표시
- `@xyflow/react` 기반 bounded 메모–태그 그래프
- 요청 재시도 동안 동일한 client UUID와 idempotency key 유지
- 192px/512px 설치 아이콘, service worker, 오프라인 app shell

### Backend

- Java 21 + Spring Boot 모듈러 모놀리스
- `LocalAnalyzer`와 `CloudAnalysisGateway` 경계, 실제 모델 대신 `FakeAnalyzer`와 Fake cloud adapter
- revision의 기록 시각·IANA 시간대를 사용하는 한국어 날짜 파서와 versioned 결정론적 ambiguity gate
- Draft 2020-12 runtime contract, domain 규칙, 날짜 의미, owner reference로 local/cloud proposal 재검증
- run마다 analyzer·prompt·local model·embedding model·routing policy version을 저장하고, 동일한 필수 provenance와 `toolCalls`를 담은 `providerMetadata`를 서버 값과 대조(각 version 1–64자, `toolCalls` 0–100)
- 직렬화된 proposal 64 KiB, `providerMetadata` 8 KiB 상한으로 분석 결과 저장 크기 제한
- authoritative routing reason을 전달하는 provider-independent cloud request와 no-tool Fake adapter
- owner-scoped memo/analysis/task/graph API와 승인 transaction 안의 tag application
- 메모 lifecycle API와 owner-scoped 보류 제안/마지막 application 복구 API
- revision 경쟁 검증, transactional apply/undo, tag 정규화와 provenance
- HTTP DTO·domain snapshot과 JDBC persistence mapping의 분리
- PostgreSQL advisory transaction lock과 응답 저장을 이용한 요청-해시 멱등성
- PostgreSQL 17.6 + Flyway 순방향 마이그레이션, revision/analysis provenance와 owner-aware composite foreign key

### Verification

- 날짜 처리, DST·윤일·잘못된 시각, 모호성 gate, 태그 정규화, 제안 편집, 그래프 변환, 재시도 identity 단위 테스트
- 12개 versioned 한국어 memo fixture와 prompt-injection/no-tool 경계 테스트
- Testcontainers PostgreSQL + MockMvc 통합 테스트
- primary flow, 중복 요청, owner 격리, stale revision, apply rollback, memo lifecycle/recovery, task 상태/overdue, undo 원문 보존 검증
- Playwright의 모바일 viewport에서 보류·새로고침·승인·그래프·되돌리기와 설치 가능한 오프라인 app shell 검증
- GitHub Actions에서 OpenAPI/JSON Schema, backend, frontend, 브라우저 E2E 검사를 실행

## 아키텍처

```text
Android Chrome PWA
  React + TypeScript + React Flow
             │ REST / JSON
             ▼
Spring Boot modular monolith
  memo │ analysis │ taxonomy │ task │ graph
             │
             ▼
PostgreSQL (source of truth) + Flyway
```

모델 구현은 도메인 적용 코드와 분리되어 있습니다. 현재 Fake 분석기도 canonical 데이터를 직접 변경하지 않으며, 브라우저나 분석 provider가 owner를 선택하거나 서버 비밀을 전달받는 경로도 없습니다.

## 실행하기

필수 도구는 Docker Desktop입니다. 저장소 루트에서 다음을 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

- PWA: <http://localhost:5173>
- API health: <http://localhost:8080/api/v1/health>
- Actuator health: <http://localhost:8080/actuator/health>

Compose의 frontend container는 개발 서버가 아니라 `npm run preview`로 빌드된 production preview를 5173 port에 제공합니다. 브라우저의 `/api` 요청은 Vite preview proxy가 `API_PROXY_TARGET=http://backend:8080`으로 전달하므로, host와 container에서 같은 상대 API URL을 사용합니다.

처음 확인할 시나리오는 `11.25 OS과제 제출`입니다. 원문 저장 후 제안의 제목과 태그를 수정하거나 제외할 수 있고, 승인하면 할 일과 그래프가 갱신됩니다. 이후 **마지막 적용 되돌리기**를 누르면 파생 데이터만 제거됩니다.

종료할 때는 아래 명령을 사용합니다. PostgreSQL volume은 보존됩니다.

```powershell
docker compose down
```

## 검사 명령

Java 21/Maven 3.9와 Node 24/npm 환경에서 저장소 루트를 기준으로:

```powershell
Push-Location backend
mvn test
Pop-Location

Push-Location frontend
npm ci
npm run lint
npm run test
npm run build
Pop-Location
```

기본 `mvn test`는 빠른 단위 테스트를 실행합니다. 실제 PostgreSQL 17.6을 사용하는 MockMvc 통합 테스트는 Docker가 켜진 상태에서 저장소 루트의 격리된 test Compose로 실행할 수 있습니다.

```powershell
docker compose -f compose.test.yaml up --abort-on-container-exit --exit-code-from backend-integration
docker compose -f compose.test.yaml down --volumes
```

이 경로는 test 전용 PostgreSQL과 Maven container를 사용하며 운영용 named volume을 건드리지 않습니다. Flyway migration은 backend 시작과 통합 test database 생성 시 자동 적용됩니다. 로컬 Java와 Testcontainers를 사용하려면 Docker를 켠 뒤 `RUN_POSTGRES_INTEGRATION_TESTS=true` 환경 변수와 함께 `backend`에서 `mvn test`를 실행할 수도 있습니다.

브라우저 E2E는 일반 개발 volume과 분리된 Compose project와 전용 host port에서 production stack을 띄운 뒤 실행합니다. 기존 개발 stack을 종료할 필요가 없습니다. E2E는 재시도 격리를 위해 대상 API의 검토 중·보류 proposal을 거절하고 마지막 application을 되돌릴 수 있으므로, `E2E_ALLOW_DESTRUCTIVE_CLEANUP=true`는 아래처럼 **폐기 가능한 E2E 전용 데이터베이스에 연결한 경우에만** 설정합니다. 개인 개발·운영 데이터베이스를 가리키는 URL에는 절대 사용하지 않습니다.

```powershell
$env:PERSONAL_MEMO_POSTGRES_PORT = "55432"
$env:PERSONAL_MEMO_BACKEND_PORT = "18081"
$env:PERSONAL_MEMO_FRONTEND_PORT = "15174"
docker compose -p personal-memo-e2e up -d --build

Push-Location frontend
npm ci
npx playwright install chromium
$env:E2E_BASE_URL = "http://127.0.0.1:15174"
$env:E2E_ALLOW_DESTRUCTIVE_CLEANUP = "true"
npm run test:e2e
Remove-Item Env:E2E_ALLOW_DESTRUCTIVE_CLEANUP
Pop-Location

docker compose -p personal-memo-e2e down --volumes
```

Playwright는 Android Chrome에 가까운 412×915 touch viewport, `ko-KR`, `Asia/Seoul`에서 primary flow를 실행합니다. 원문 저장 → 검토 focus/새로고침 복구 → 보류 → 보류 제안 복구 → 수정·승인 → task/graph 표시 → undo 뒤 원문 보존을 확인합니다. 별도 시나리오는 `UNKNOWN` 유형의 명시적 선택과 수동 항목 추가·삭제 focus를 검증하고, app-shell 시나리오는 192/512 manifest icon, service worker 등록, 오프라인 reload를 확인합니다. GitHub Actions는 PostgreSQL 통합 test와 이 브라우저 E2E를 모두 실행합니다.

## 현재 경계

이 저장소는 포트폴리오용 MVP 체크포인트이며 다음 기능은 아직 연결하지 않았습니다.

- 실제 로컬 AI 모델 또는 클라우드 LLM
- 운영 인증과 다중 사용자 UI
- 완전한 오프라인 동기화와 IndexedDB outbox
- Web Push 및 reminder dispatcher
- 자동 태그 병합·분리, 의미 검색, 노드 압축
- Neo4j, Kafka, Redis, 별도 AI 마이크로서비스

다음 단계는 owner-scoped tag/alias 후보 조회와 Fake cloud의 field-level 요청·실패 상태(`USER_INPUT_NEEDED`, `PENDING_OFFLINE`)를 더 구체화하는 것입니다. 실제 provider와 로컬 모델 연결은 현재 기본 결정에 따라 보류하며, 별도 승인과 평가·비용·개인정보 경계가 준비되기 전에는 도입하지 않습니다.

## 문서

- [제품 및 인수 조건](docs/REQUIREMENTS.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [현재 API 계약](docs/API.md)
- [OpenAPI 3.1 명세](docs/openapi.yaml)
- [현재 데이터 모델](docs/DATA_MODEL.md)
- [AI 안전 경계](docs/AI_PIPELINE.md)
- [마일스톤](docs/ROADMAP.md)
- [ADR](docs/adr)
