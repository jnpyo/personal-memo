# Personal Memo

Android Chrome용 모바일 우선 PWA와 Spring Boot/PostgreSQL 모듈러 모놀리스입니다. AI 결과는 제안이며 명시적 승인 전에는 태그·할 일·관계를 만들지 않습니다.

## 시작

필수 도구는 Docker Desktop입니다. 저장소 루트에서:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

- PWA: http://localhost:5173
- API health: http://localhost:8080/api/v1/health
- Actuator health: http://localhost:8080/actuator/health

Docker Desktop 엔진이 먼저 실행 중이어야 합니다. 종료는 `docker compose down`이며, 데이터 볼륨은 보존됩니다.

## 독립 실행과 검사

Java 21/Maven 3.9 및 Node 24/npm이 설치된 환경에서는:

```powershell
Set-Location backend
mvn test

Set-Location ../frontend
npm install
npm run lint
npm run test
npm run build
```

마이그레이션은 백엔드 시작 시 Flyway가 자동 적용합니다. 로컬 비밀번호는 개발 전용 기본값이며 `.env`는 커밋하지 않습니다.

## 현재 수직 흐름

`메모 입력 → Fake 분석 후보 → 제목/태그 검토 → 승인 → 태그·할 일 → bounded 그래프 → 적용 되돌리기`

개발용 owner는 서버에서 고정하며 클라이언트가 지정할 수 없습니다. 원문은 immutable revision으로 보존되고, 메모 수정은 revision을 증가시키며 과거 미적용 분석을 `STALE`로 만듭니다. `OVERDUE`는 조회 시 현재 시각과 `TODO` 상태에서 계산됩니다.

설계 결정은 [docs/adr](docs/adr), API 초안은 [docs/API.md](docs/API.md)를 참고하세요.
