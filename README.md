# Personal Memo

[![CI](https://github.com/jnpyo/personal-memo/actions/workflows/ci.yml/badge.svg)](https://github.com/jnpyo/personal-memo/actions/workflows/ci.yml)

형식 없이 적은 한국어 메모를 **검토 가능한 제안**으로 바꾸고, 사용자가 승인한 결과만 태그·할 일·그래프에 반영하는 모바일 우선 PWA입니다.

첫 번째 목표는 모델 성능이 아니라 데이터 무결성이었습니다. 실제 AI를 연결하기 전에 아래 흐름을 Fake 분석기로 끝까지 구현하고, 재시도·수정 경쟁·되돌리기에서도 원문이 손상되지 않도록 설계했습니다.

```text
메모 입력
→ 원문 revision 저장
→ Fake 분석 후보
→ 사용자 수정·승인 / 보류 / 거절
→ 태그와 할 일 생성
→ React Flow 그래프 투영
→ 적용 되돌리기
```

## 핵심 설계

- **AI는 제안만 합니다.** 분석 결과는 별도 proposal로 저장되며, 승인 API만 canonical 태그와 할 일을 생성합니다.
- **원문을 독립적으로 보존합니다.** 메모 수정마다 immutable revision이 추가되고, 이전 revision의 늦은 분석 결과는 `STALE`로 바뀌어 적용할 수 없습니다.
- **재시도를 안전하게 처리합니다.** 주요 mutation은 요청 본문 해시와 `Idempotency-Key`를 함께 저장합니다. 같은 요청은 원래 응답을 재생하고, 같은 키의 다른 요청은 거절합니다.
- **승인 단위를 되돌립니다.** application provenance를 따라 파생 데이터만 제거하며 원본 메모와 revision 이력은 남깁니다.
- **기한 초과는 사실이 아니라 시점에 따른 상태입니다.** `OVERDUE`를 저장하지 않고 `TODO`와 현재 시각을 기준으로 조회할 때 계산합니다. 날짜만 지정한 기한은 UTC 자정으로 왜곡하지 않고 `due_local_date`로 보존합니다.
- **그래프는 canonical 데이터의 투영입니다.** 메모 유형을 거대한 공통 노드로 만들지 않고 노드의 속성·필터·아이콘으로 표현합니다.
- **소유권 경계는 서버에 있습니다.** 현재는 개발용 단일 사용자이지만 aggregate root는 `owner_id`를 가지며, 하위 record도 소유 aggregate를 통해 모든 읽기와 쓰기를 owner 범위로 제한합니다.

## 구현 범위

### Frontend

- Android Chrome을 첫 대상으로 한 React 19 + TypeScript + Vite PWA
- 메모 캡처, 연결/실패/재시도 상태, 제안 제목·유형·태그 수정
- 제안 승인·보류·거절과 마지막 application 되돌리기
- `TODO` / `DONE` / `CANCELLED` 전환, 날짜 전용 기한과 기한 초과 표시
- `@xyflow/react` 기반 bounded 메모–태그 그래프
- 요청 재시도 동안 동일한 client UUID와 idempotency key 유지

### Backend

- Java 21 + Spring Boot 모듈러 모놀리스
- `LocalAnalyzer`와 `CloudAnalysisGateway` 경계, 실제 모델 대신 `FakeAnalyzer`와 Fake cloud adapter
- owner-scoped memo/analysis/task/graph API와 승인 transaction 안의 tag application
- revision 경쟁 검증, transactional apply/undo, tag 정규화와 provenance
- HTTP DTO·domain snapshot과 JDBC persistence mapping의 분리
- PostgreSQL advisory transaction lock과 응답 저장을 이용한 요청-해시 멱등성
- PostgreSQL 17.6 + Flyway 순방향 마이그레이션

### Verification

- 날짜 처리, 태그 정규화, 제안 편집, 그래프 변환, 재시도 identity 단위 테스트
- Testcontainers PostgreSQL + MockMvc 통합 테스트
- primary flow, 중복 요청, owner 격리, stale revision, apply rollback, task 상태/overdue, undo 원문 보존 검증
- GitHub Actions에서 backend와 frontend 검사를 독립 실행

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

처음 확인할 시나리오는 `11.25 OS과제 제출`입니다. 원문 저장 후 제안의 제목과 태그를 수정하거나 제외할 수 있고, 승인하면 할 일과 그래프가 갱신됩니다. 이후 **마지막 적용 되돌리기**를 누르면 파생 데이터만 제거됩니다.

종료할 때는 아래 명령을 사용합니다. PostgreSQL volume은 보존됩니다.

```powershell
docker compose down
```

## 검사 명령

Java 21/Maven 3.9와 Node 24/npm 환경에서:

```powershell
Set-Location backend
mvn test

Set-Location ../frontend
npm ci
npm run lint
npm run test
npm run build
```

기본 `mvn test`는 빠른 단위 테스트만 실행합니다. 실제 PostgreSQL 17.6을 사용하는 MockMvc 통합 테스트까지 실행하려면 Docker가 켜진 상태에서:

```powershell
Set-Location backend
$env:RUN_POSTGRES_INTEGRATION_TESTS = 'true'
mvn test
Remove-Item Env:RUN_POSTGRES_INTEGRATION_TESTS
```

GitHub Actions는 이 통합 테스트를 항상 활성화합니다. Flyway 마이그레이션은 backend 시작과 통합 테스트 database 생성 시 자동 적용됩니다.

## 현재 경계

이 저장소는 포트폴리오용 MVP 체크포인트이며 다음 기능은 아직 연결하지 않았습니다.

- 실제 로컬 AI 모델 또는 클라우드 LLM
- 운영 인증과 다중 사용자 UI
- 완전한 오프라인 동기화와 IndexedDB outbox
- Web Push 및 reminder dispatcher
- 자동 태그 병합·분리, 의미 검색, 노드 압축
- Neo4j, Kafka, Redis, 별도 AI 마이크로서비스

다음 단계는 versioned Korean fixture를 이용한 결정적 날짜 파서와 ambiguity gate입니다. 실제 provider 연결은 이 평가 경계와 실패 fallback을 검증한 뒤 진행합니다.

## 문서

- [제품 및 인수 조건](docs/REQUIREMENTS.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [현재 API 계약](docs/API.md)
- [OpenAPI 3.1 명세](docs/openapi.yaml)
- [현재 데이터 모델](docs/DATA_MODEL.md)
- [AI 안전 경계](docs/AI_PIPELINE.md)
- [마일스톤](docs/ROADMAP.md)
- [ADR](docs/adr)
