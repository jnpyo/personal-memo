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
4. **명확한 메모는 휴대폰 로컬 분석기로 끝내고, 모호한 메모만 클라우드 Agent로 보낸다.**
5. **로컬 모델의 self-confidence를 그대로 신뢰하지 않는다.** 분류 마진, 태그 유사도, 날짜 완전성, 미해결 지시어 등으로 필드별 모호성을 계산한다.
6. **그래프는 저장 포맷이 아니라 데이터의 투영(view)이다.** 메모·파생 항목·태그·관계가 원본 데이터다.
7. **압축은 가역적이어야 한다.** 오래된 노드를 화면에서 접거나 요약하되 원본을 삭제·병합하지 않는다.
8. **증분 처리한다.** 새 메모가 들어올 때 전체 메모를 재분석하거나 모든 쌍을 비교하지 않는다.
9. **Agent 도구는 읽기 위주로 제한한다.** 승인 전에는 삭제·대량 수정·알림 생성 도구를 제공하지 않는다.
10. **오프라인에서도 메모를 잃지 않는다.** 분석이 불가능하면 원본을 먼저 저장하고 대기열에 둔다.

## 3. 권장 기술 구성

### 클라이언트

- React + TypeScript
- Vite 기반 PWA
- React Flow 기반 그래프
- IndexedDB 기반 임시 캡처 보존; 완전한 오프라인 동기화 대기열은 P1
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
→ 원문을 로컬에 즉시 저장
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

1. 사용자 인증 또는 개발용 단일 사용자 세션
2. 자유 텍스트 메모 작성·수정·삭제
3. 원본 메모의 즉시 로컬 저장
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
- Flyway `V1`–`V7`이 memo/revision, proposal/application, canonical item/tag/task, owner integrity, revision capture context와 analyzer·prompt·local model·embedding model·routing policy provenance를 관리한다.
- 12개 한국어 fixture, revision 기준 날짜 파서, `field-policy-v1` ambiguity gate, Draft 2020-12 runtime contract와 strict domain validation이 구현되어 있다. `providerMetadata`의 다섯 version은 각각 1–64자, 필수 `toolCalls`는 0–100이며 proposal은 64 KiB, metadata는 8 KiB로 제한된다.
- 명확한 결과는 `LOCAL`, 모호한 결과는 authoritative routing 사유를 받는 no-tool Fake cloud를 거쳐 `HYBRID` route로 저장되며 항상 사용자 검토가 필요하다.
- `UNKNOWN` 유형은 UI가 자동 확정하지 않으며 사용자가 유형을 선택하고 항목을 추가해야 적용할 수 있다.
- 실제 로컬 모델·클라우드 LLM, Web Push, 완전한 오프라인 동기화, 자동 taxonomy migration, 노드 압축은 아직 연결하지 않는다.
- 다음 안전한 순서는 owner-scoped tag/alias 후보 조회, field-level Fake cloud 계약과 실패 상태, async 분석 수명주기·관측성이다.
