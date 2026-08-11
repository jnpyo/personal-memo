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
- **그래프 노드는 실제 탐색 진입점입니다.** 고정·기한 초과·미완료·가까운 기한·현재 원문
  revision 순으로 제한된 홈을 만들되, node 예산이 찰 때도 memo가 tag를 전부 밀어내지 않도록
  관계용 tag 예산을 예약합니다. 노드를 누르면 보이는 직접 이웃을 강조한 뒤 mobile
  drawer에서 현재 원문 또는 현재 홈 기준 tag 연결을 확인합니다. active memo의 pin/unpin은
  멱등 mutation이며 원문 revision이나 승인된 파생 데이터를 바꾸지 않습니다.
- **소유권 경계는 서버와 데이터베이스에 있습니다.** 각 로그인 수단은 internal user UUID에 매핑되고, 명시적으로 연결한 local·Google 수단은 같은 UUID를 사용합니다. 서버가 Spring Security principal에서 `owner_id`를 결정하며, V5의 owner-aware composite foreign key는 서로 다른 사용자의 하위 record를 데이터베이스 수준에서도 연결할 수 없게 합니다.
- **브라우저에 인증 token을 보관하지 않습니다.** opaque session은 PostgreSQL에 저장하고 항상 HttpOnly인 cookie(운영에서는 Secure)와 CSRF 검증을 사용합니다. Google email 일치만으로 계정을 합치지 않으며, 기존 로그인 뒤 명시적으로 연결해야 합니다.
- **라우팅은 신호 기반으로 결정합니다.** 모델의 confidence 하나에 의존하지 않고 날짜·참조·행동·복합 의도 신호를 enum으로 검증합니다. 명확한 메모는 cloud를 호출하지 않으며, 모호한 메모도 현재는 외부 통신 없는 Fake adapter만 거칩니다.
- **태그 문맥은 owner 안에서 작고 결정적으로 만듭니다.** 구조 검증을 통과한 proposal의 tag 후보를 최대 10개만 보고 canonical name과 alias에서 최대 20개의 정규화 term을 만듭니다. 현재 owner의 `ACTIVE` tag/alias와 exact equality로 찾은 전체 결과를 먼저 사용해 단 하나의 tag로만 귀결될 때 proposal 후보를 안전하게 해소한 뒤, score·원래 후보 순서·match 종류·이름·UUID의 고정 순서로 중복을 제거한 최대 8개의 내부 hint를 gateway request에 제공합니다. raw/related memo, fuzzy·vector·embedding 검색은 사용하지 않습니다.
- **외부 전송 경계는 서버가 소유합니다.** 모호한 경로의 gateway descriptor가 transfer mode·gateway·provider·model·consent-policy version을 선언하고 서버가 run evidence와 proposal metadata를 덮어씁니다. `NO_NETWORK` Fake에는 동의가 필요 없지만, `EXTERNAL_MEMO_CONTENT`는 현재 owner가 정확히 같은 policy version을 승인했고 `granted_at`이 권한 확인 시각보다 늦지 않을 때만 호출됩니다. 미동의·policy mismatch·미래 시각 grant는 gateway 0-call입니다.
- **보완 분석 실패도 원문을 잃게 하지 않습니다.** LOCAL, cloud SUCCESS, fallback의 모든 새 proposal은 `providerMetadata`를 공통 허용 목록으로 다시 만듭니다. typed failure, gateway 예외, invalid cloud proposal은 provider 오류 문구를 노출하지 않고 이미 검증된 local proposal로 되돌아가며, run은 `HYBRID`/`REVIEW_REQUIRED`와 제한된 outcome만 저장하고 UI는 상세 검토를 엽니다. canonical 데이터는 명시적 승인 전까지 바뀌지 않습니다.

## 구현 범위

### Frontend

- Android Chrome을 첫 대상으로 한 React 19 + TypeScript + Vite PWA
- 자체 가입·로그인 화면, 로그인과 신규 계정 생성을 구분해 안내하는 capability 기반 Google 로그인, Google 로그인 수단 연결·해제와 로그아웃 계정 패널
- server-declared CSRF header를 모든 mutation에 붙이고 CSRF-specific `403`에 한 번만 안전하게 재시도하는 API client
- owner UUID별 `localStorage` 원문 임시 초안, 저장소 차단·용량 실패 경고, 오프라인 편집과 온라인 제출 경계
- 메모 캡처, 연결/실패/재시도 상태, 모바일 제안 팝업의 예/아니오 승인과 필요할 때만 여는 제목·유형·날짜·태그 수정
- 제안·새 태그·원문 revision 편집을 아우르는 dirty-state 보호, 미확정 교차 탭 로그아웃 중 mounted-state 보존, 사용자 선택형 PWA 업데이트
- `UNKNOWN` 유형의 명시적 사용자 선택, 수동 항목 추가·제거와 부분 적용
- 활성/휴지통 메모 목록, 기록 시각·시간대를 포함한 새 revision 편집, 휴지통 이동·복원, 기존 메모 재분석
- 제안 승인·보류·거절과 마지막 application 되돌리기
- 새로고침 뒤 마지막 application과 검토 중·보류한 제안 복구
- `TODO` / `DONE` / `CANCELLED` 전환, 날짜 전용 기한과 기한 초과 표시
- `@xyflow/react` 기반 bounded 메모–태그 그래프, keyboard/touch node detail drawer와 pin control
- 요청 재시도 동안 동일한 client UUID와 idempotency key 유지
- 192px/512px 설치 아이콘, service worker, 오프라인 app shell

### Backend

- Java 21 + Spring Boot 모듈러 모놀리스
- Spring Security local authentication과 선택적 Google OIDC, PostgreSQL-backed Spring Session, 명시적 Google 연결/안전한 해제
- delegating bcrypt password hash, session fixation 방지, JSON `401`/`403`, 모든 domain API의 security-context owner scope
- `LocalAnalyzer`와 `CloudAnalysisGateway` 경계, 실제 모델 대신 `FakeAnalyzer`와 Fake cloud adapter
- revision의 기록 시각·IANA 시간대를 사용하는 한국어 날짜 파서와 versioned 결정론적 ambiguity gate
- Draft 2020-12 runtime contract, domain 규칙, 날짜 의미, owner reference로 local/cloud proposal 재검증
- run마다 analyzer·prompt·local model·embedding model·routing policy version을 저장하고, 동일한 필수 provenance와 `toolCalls`를 담은 `providerMetadata`를 서버 값과 대조(각 version 1–64자, `toolCalls` 0–100)
- V13의 owner·policy-version·granted-at 동의 pin과 legacy boolean grant 폐기, server-owned cloud transfer/gateway/provider/model/policy/outcome evidence
- V14의 내부 authorization/grant snapshot과 결정론적 provider-request token, legacy row의 정직한 `legacy-v0` 보존
- V15의 호출 전 durable prepare commit, immutable gateway binding·descriptor 검증, deadline/lease/fence 기반 caller-driven retry, DB transaction 밖 bounded gateway 실행과 revision 재검사 finalize
- V16의 owner-scoped exact tag/alias K=8 context와 호출 전 raw/hash/version/count snapshot; retry와 restart recovery는 재조회 없이 같은 DB snapshot만 사용하고 `FINALIZED`에서 raw context를 지우되 hash/version/count는 보존
- V17의 owner-scoped fence별 `gateway-attempt-v1` 내부 ledger; gateway result의 `STARTED`, executor 거절의 확정적 `NOT_STARTED`, 제출 뒤 시작 관측이 없는 종료의 `UNKNOWN`을 구분하고, monotonic local elapsed time과 원격 결과·model token·cost evidence를 숫자와 분리
- 운영 프로필에서 30초 간격으로 `PREPARED` 또는 lease가 만료된 `RUNNING` dispatch를 한 번에 최대 25건 복구하는 bounded scheduler; owner와 기존 idempotency key는 owner 일치 DB row에서만 가져오며 live lease는 건너뜀
- LOCAL·cloud SUCCESS·fallback 모두에 같은 `providerMetadata` allowlist canonicalizer를 적용해 임의 provider detail 제거
- 직렬화된 proposal 64 KiB, `providerMetadata` 8 KiB 상한으로 분석 결과 저장 크기 제한
- authoritative routing reason을 전달하는 provider-independent cloud request, typed success/failure 결과와 no-tool `NO_NETWORK` Fake adapter
- owner-scoped memo/analysis/task/graph API, no-store raw/detail reads, idempotent memo pin, 승인
  transaction 안의 tag application
- 메모 lifecycle API와 owner-scoped 보류 제안/마지막 application 복구 API
- revision 경쟁 검증, transactional apply/undo, tag 정규화와 provenance
- HTTP DTO·domain snapshot과 JDBC persistence mapping의 분리
- PostgreSQL advisory transaction lock과 응답 저장을 이용한 요청-해시 멱등성
- PostgreSQL 17.6 + Flyway 순방향 마이그레이션, revision/analysis provenance와 owner-aware composite foreign key

### Verification

- local 가입·로그인, CSRF, session fixation 방지, owner 격리, 명시적 Google 연결/해제를 검증하는 통합 테스트. Google 경로는 mocked OIDC claim으로 검증하며 실제 provider credential이나 Google network를 사용하지 않음
- 날짜 처리, DST·윤일·잘못된 시각, 모호성 gate, 태그 정규화, 제안 편집, 그래프 priority·이웃
  변환, 재시도 identity 단위 테스트
- 12개 regression + 12개 visible synthetic challenge 한국어 memo fixture, fixture JSON Schema, content-free
  평가 report와 prompt-injection/no-tool 경계 테스트
- Testcontainers PostgreSQL + MockMvc 통합 테스트
- primary flow, 중복 요청, owner 격리, stale revision, apply rollback, memo lifecycle/recovery, task 상태/overdue, undo 원문 보존 검증
- exact consent pin, 미동의 external gateway 0-call, typed/exception/invalid cloud fallback, server-owned evidence와 provider 오류 문구 비노출 통합 검증
- V15 fresh/V14-upgrade migration, durable prepare·binding mismatch·bounded timeout·caller-driven 및 운영 scheduler recovery·fence·stale finalize·owner 격리 통합 검증
- V16의 V15 `none/0/NULL/NULL` 보존, exact lookup owner 격리·결정성·unique resolution, strict context codec와 PREPARED/RUNNING/FINALIZED raw lifecycle 통합 검증
- V17의 과거 dispatch `attempt_history_version=none`/0-row 보존, fence별 최대-attempt ledger, executor 거절·provider result·timeout/interrupt의 시작 관측·process-loss·늦은 fence 분리와 evidence nullability 통합 검증
- Playwright의 모바일 viewport에서 보류·새로고침·승인·그래프 node 상세·pin·focus 복원·되돌리기와
  설치 가능한 오프라인 app shell 검증
- 로컬 초안 owner 격리·저장 실패, 교차 탭 인증 전이, 미저장 편집 guard와 prompt형 service-worker 업데이트 단위 테스트
- GitHub Actions에서 OpenAPI/JSON Schema, backend, frontend, 브라우저 E2E 검사를 실행

## 아키텍처

```text
Android Chrome PWA
  React + TypeScript + React Flow
             │ session cookie + CSRF / REST JSON
             ▼
Spring Boot modular monolith
  auth │ memo │ analysis │ taxonomy │ task │ graph
      │ optional Google OIDC
             │
             ▼
PostgreSQL (source of truth + sessions) + Flyway
```

모델 구현은 도메인 적용 코드와 분리되어 있습니다. 현재 Fake 분석기도 canonical 데이터를 직접 변경하지 않으며, 브라우저나 분석 provider가 owner를 선택하거나 서버 비밀을 전달받는 경로도 없습니다.

## 실행하기

필수 도구는 Docker Desktop입니다. 기본 `compose.yaml`만으로는 개발 포트와 database 자격 증명이 구성되지 않으므로 반드시 개발 overlay를 함께 지정합니다. `yourname`은 이 작업 복제본에서 계속 재사용할 고유한 값으로 바꿉니다.

```powershell
Copy-Item .env.example .env.dev
$devProject = "personal-memo-dev-yourname"

docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml config --quiet
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml up -d --build --wait
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml ps
```

- PWA: <http://127.0.0.1:5173>
- API health: <http://127.0.0.1:8080/api/v1/health>
- Actuator health: <http://127.0.0.1:8080/actuator/health>

frontend container는 unprivileged Nginx로 빌드된 PWA를 제공하고 `/api`, `/oauth2`, `/login/oauth2`를 `http://backend:8080`으로 proxy합니다. 브라우저에는 같은 origin의 상대 URL만 노출됩니다.

처음 열면 자체 계정을 만든 뒤 로그인합니다. 비밀번호는 12자 이상이며 bcrypt 안전 범위인 UTF-8 72바이트 이하여야 합니다. 그 다음 확인할 시나리오는 `11.25 OS과제 제출`입니다. 원문 저장 후 제안의 제목과 태그를 수정하거나 제외할 수 있고, 승인하면 할 일과 그래프가 갱신됩니다. 그래프 memo/tag 노드를 누르면 현재 홈의 이웃과 원문 상세를 확인하고 memo를 고정할 수 있습니다. 이후 **마지막 적용 되돌리기**를 누르면 파생 데이터만 제거됩니다.

작성 중 원문은 서버 저장 전까지 internal owner UUID로 분리된 브라우저 `localStorage` 초안입니다. 저장소가 막히거나 가득 차면 화면에 데이터 손실 경고가 표시되고 이탈·업데이트 guard가 켜집니다. 이 초안은 암호화된 보관소가 아니며 로그아웃만으로 삭제되지 않으므로, 공유 기기에서는 브라우저 프로필 자체를 분리하거나 원문을 서버에 저장한 뒤 입력을 비워야 합니다. canonical memo와 완전한 오프라인 동기화 대기열로 취급해서는 안 됩니다.

### 개인 PC에서 Galaxy S24로 사용

공개 스토어에 올리기 전 한 사람이 시험하는 경로는 `compose.personal.yaml`과
`scripts/personal`에 분리되어 있습니다. 같은 application image와 PostgreSQL schema를 사용하므로
나중에 서버로 옮길 때 앱을 다시 만들 필요가 없습니다. 개인 mode는 선택한 private LAN IP의
frontend HTTPS만 공개하고 Spring Boot와 PostgreSQL port는 공개하지 않습니다.
사설 IP callback을 쓰는 이 개인 overlay에서는 Google OAuth와 provider credential을 의도적으로
비활성화합니다. 자체 로그인으로 먼저 안정화한 뒤, 공개 HTTPS domain을 준비해 일반 production
overlay로 이전하면 기존의 명시적 Google 계정 연결 기능을 켤 수 있습니다.

```powershell
# 이 창에서만 로컬 스크립트 실행을 허용합니다. 시스템 정책은 바꾸지 않습니다.
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

.\scripts\personal\Initialize-PersonalMemo.ps1 `
  -LanIPv4 192.168.1.100 `
  -BootstrapEmail owner@example.invalid `
  -BootstrapDisplayName "Private Owner"

.\scripts\personal\Initialize-PersonalAccount.ps1
# 휴대폰 접속 전, 별도의 관리자 PowerShell에서 한 번 실행합니다.
.\scripts\personal\Enable-PersonalMemoFirewall.ps1
.\scripts\personal\Start-PersonalMemo.ps1
.\scripts\personal\Get-PersonalMemoStatus.ps1
```

첫 command는 ignored `.env.personal`, local CA/leaf certificate, 강한 random database password,
`Documents\PersonalMemo\PrivateTls`, `Documents\PersonalMemo\Backups`를 만듭니다. 실제 email과
표시 이름은 tracked template에 넣지 않습니다. 두 번째 command에서만 account password를 echo 없이
두 번 직접 입력합니다. password를 environment, argument, file, browser, Agent/model 도구로 전달하는
경로는 없습니다. Flyway의 one-time gate 때문에 command를 재실행하거나 동시에 실행해도 계정이 더
생기지 않으며, 운영 registration은 계속 닫혀 있습니다.

Windows 기본 정책이 로컬 `.ps1` 실행을 막는 경우에도 위의 `Process` 범위 설정만 사용합니다.
새 PowerShell 창을 닫으면 설정은 사라집니다. 관리자 PowerShell에서 방화벽 스크립트를 실행할 때도
그 관리자 창에서 같은 명령을 먼저 한 번 실행합니다.

S24에는 `Documents\PersonalMemo\PrivateTls\personal-memo-ca.cer`만 복사해 CA certificate로
설치합니다. `ca-key.pem`과 `server-key.pem`은 PC 밖으로 복사하지 않습니다. 같은 private Wi-Fi에서
`https://<PC-LAN-IP>:8443/`를 Chrome으로 열고 인증서 오류가 없는지 확인한 뒤 Chrome 메뉴의
**설치**를 사용합니다. 관리자 PowerShell에서 `Enable-PersonalMemoFirewall.ps1`을 한 번 실행하면
선택한 IP와 port를 private profile·local subnet에만 허용합니다. PC IP는 공유기의 DHCP reservation으로
고정하고 router port forwarding은 하지 않습니다. 자세한 위협 경계, Windows firewall, backup/restore, 서버 이전 순서는
[배포 및 운영 가이드](docs/DEPLOYMENT.md)에 있습니다.

일상 command는 다음과 같습니다. `Stop`은 container만 멈추고 canonical PostgreSQL volume을
보존합니다.

```powershell
.\scripts\personal\Backup-PersonalMemo.ps1
.\scripts\personal\Rotate-PersonalMemoDatabasePassword.ps1
.\scripts\personal\Stop-PersonalMemo.ps1
.\scripts\personal\Start-PersonalMemo.ps1
```

database credential이 terminal, chat, screenshot 등에 노출됐을 때는 먼저 backup을 만든 뒤 rotation
script를 실행합니다. 새 값은 출력·Docker argument·호스트 임시 environment로 전달하지 않으며,
ignored `.env.personal`과 PostgreSQL role을 함께 갱신합니다. canonical volume은 보존하고 PostgreSQL·
backend·frontend container를 재생성한 뒤 Nginx를 경유한 API health와 private ACL을 검증합니다.

### 선택적 Google 로그인

개발 overlay는 Google 없이 기동합니다. Google Cloud에서 OAuth Web client를 준비한 경우 `.env.dev`에서 다음 값을 설정합니다.

```dotenv
GOOGLE_AUTH_ENABLED=true
GOOGLE_REGISTRATION_ENABLED=false
GOOGLE_CLIENT_ID=your-web-client-id
GOOGLE_CLIENT_SECRET=your-server-only-secret
GOOGLE_REDIRECT_URI=http://127.0.0.1:5173/login/oauth2/code/google
```

Google Console에도 redirect URI를 정확히 등록해야 합니다. scope는 `openid profile email`만 사용합니다. `GOOGLE_AUTH_ENABLED`는 기존 Google identity의 로그인과 명시적 연결 기능을 켜고, 별도의 `GOOGLE_REGISTRATION_ENABLED`만 처음 보는 Google subject와 email로 새 internal user를 자동 생성할 수 있게 합니다. 후자의 기본값은 `false`입니다. 따라서 local 계정에 Google을 연결하거나 이미 연결된 Google 계정으로 로그인하는 데는 값을 켤 필요가 없습니다. 개발 중 Google만으로 신규 가입을 시험할 때만 `true`로 바꿉니다. 공개 capability 응답은 `googleEnabled`와 `googleRegistrationEnabled`를 분리해 제공하고, 로그인 화면도 OAuth 이동 전에 Google 신규 가입 가능 여부를 알려 줍니다. `GOOGLE_AUTH_ENABLED=true`인데 client ID나 secret이 비어 있으면 backend는 잘못된 설정으로 기동을 거부합니다. 운영에서는 신규 Google 사용자 생성이 강제로 비활성화되고 절대형 public HTTPS redirect URI만 허용됩니다. Google secret은 backend container에만 전달되며 frontend build나 browser storage에는 포함되지 않습니다.

잠시 멈출 때는 정확한 project를 확인하고 `stop`을 사용합니다. PostgreSQL volume은 보존됩니다. 자신이 만든 project name을 확인하지 않은 일반 `docker compose down`이나 기존 project의 `down --volumes`는 실행하지 않습니다.

```powershell
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml ps
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml stop
```

## 검증

Java 21/Maven 3.9와 Node 24/npm 환경에서 저장소 루트를 기준으로:

```powershell
Push-Location backend
$env:RUN_POSTGRES_INTEGRATION_TESTS = "true"
mvn -B verify
Remove-Item Env:RUN_POSTGRES_INTEGRATION_TESTS
Pop-Location

Push-Location frontend
npm ci
npm run lint
npm run test
npm run build
Pop-Location
```

`mvn verify`는 backend test와 Spotless·SpotBugs 검사를 실행합니다. 실제 PostgreSQL 17.6을 사용하는 통합 테스트만 별도의 Maven container에서 실행하려면 고유한 test project를 사용합니다.

```powershell
$integrationProject = "personal-memo-integration-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
docker compose -p $integrationProject -f compose.test.yaml up --abort-on-container-exit --exit-code-from backend-integration
docker compose -p $integrationProject -f compose.test.yaml down --volumes
```

이 경로는 test 전용 PostgreSQL과 Maven container를 사용하며 운영용 named volume을 건드리지 않습니다. 위 `down --volumes`는 바로 앞에서 만든 `$integrationProject`에만 사용합니다. Flyway migration은 backend 시작과 통합 test database 생성 시 자동 적용됩니다.

브라우저 E2E도 개발 volume과 분리된 고유 Compose project와 전용 host port를 사용합니다. 기존 개발 stack은 종료하지 않습니다.

```powershell
$e2eProject = "personal-memo-e2e-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$env:POSTGRES_DB = "personal_memo_e2e"
$env:POSTGRES_USER = "personal_memo_e2e"
$env:POSTGRES_PASSWORD = "e2e-only-password"
$env:PERSONAL_MEMO_POSTGRES_PORT = "55432"
$env:PERSONAL_MEMO_BACKEND_PORT = "18081"
$env:PERSONAL_MEMO_FRONTEND_PORT = "15174"
$env:AUTH_REGISTRATION_ENABLED = "true"
$env:GOOGLE_AUTH_ENABLED = "true"
$env:GOOGLE_REGISTRATION_ENABLED = "false"
$env:GOOGLE_CLIENT_ID = "e2e-fake-client"
$env:GOOGLE_CLIENT_SECRET = "e2e-fake-secret"
$env:GOOGLE_REDIRECT_URI = "http://127.0.0.1:15174/login/oauth2/code/google"

docker compose --env-file .env.example -p $e2eProject -f compose.yaml -f compose.dev.yaml up -d --build --wait

Push-Location frontend
npm ci
npx playwright install chromium
$env:E2E_BASE_URL = "http://127.0.0.1:15174"
$env:E2E_GOOGLE_ENABLED = "true"
npm run test:e2e
Pop-Location

docker compose --env-file .env.example -p $e2eProject -f compose.yaml -f compose.dev.yaml down --volumes
Remove-Item Env:POSTGRES_DB, Env:POSTGRES_USER, Env:POSTGRES_PASSWORD
Remove-Item Env:PERSONAL_MEMO_POSTGRES_PORT, Env:PERSONAL_MEMO_BACKEND_PORT, Env:PERSONAL_MEMO_FRONTEND_PORT
Remove-Item Env:AUTH_REGISTRATION_ENABLED, Env:GOOGLE_AUTH_ENABLED, Env:GOOGLE_REGISTRATION_ENABLED, Env:GOOGLE_CLIENT_ID, Env:GOOGLE_CLIENT_SECRET, Env:GOOGLE_REDIRECT_URI
Remove-Item Env:E2E_BASE_URL, Env:E2E_GOOGLE_ENABLED
```

Playwright는 Android Chrome에 가까운 412×915 touch viewport, `ko-KR`, `Asia/Seoul`에서 primary flow, 계정 경계와 OAuth state, `UNKNOWN` 선택, 설치 가능한 offline app shell을 검증합니다. 실제 Google network는 호출하지 않습니다. `down --volumes`는 이 명령에서 만든 `$e2eProject`에만 사용합니다.

## 운영

운영은 개발 `.env`를 재사용하지 않고, 필수 PostgreSQL 비밀을 가진 별도 `.env.prod`와 안정적인 고유 project name을 사용합니다.

```powershell
$prodProject = "personal-memo-prod-host01"

docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml config --quiet
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml up -d --build --wait
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps
```

일반 운영 overlay는 frontend만 loopback에 열며 외부 HTTPS same-origin edge를 별도로 요구합니다. 개인 PC overlay는 같은 production 경계 위에서 private LAN용 local TLS listener와 일회성 초기 계정 command를 제공하고 Google OAuth를 강제로 끕니다. local 가입과 신규 Google 사용자 자동 생성은 모두 fail-closed입니다. 추후 공개 HTTPS domain의 일반 production overlay로 이전한 뒤 Google 로그인을 켤 때는 client 자격 증명과 절대형 HTTPS redirect URI가 모두 필요하며, 이미 연결된 Google identity 로그인과 로그인한 local 계정의 명시적 Google 연결만 지원합니다. 현재 형태는 공개 self-service 출시가 아니라 접근을 제한한 포트폴리오·평가용 checkpoint입니다. 환경 변수, health check, Google 설정, backup/restore, Flyway와 rollback 절차는 [배포 및 운영 가이드](docs/DEPLOYMENT.md)를 따릅니다.

## 현재 경계

이 저장소는 포트폴리오용 MVP 체크포인트이며 다음 기능은 아직 연결하지 않았습니다.

- 실제 로컬 AI 모델 또는 클라우드 LLM
- 실제 external provider 설정과 사용자 consent grant/revoke API
- related-memo context, fuzzy/vector/embedding retrieval과 전체 검색 UI
- 실제 model token·cost 숫자 수집·예산 집행·집계와 승인된 attempt 보존/삭제 정책
- local email 검증·비밀번호 재설정 delivery, IP·edge rate limit/abuse protection, MFA/passkey, 완전한 계정 삭제 자동화
- 완전한 오프라인 동기화와 IndexedDB outbox
- Web Push 및 reminder dispatcher
- 자동 태그 병합·분리, 의미 검색, 노드 압축
- Neo4j, Kafka, Redis, 별도 AI 마이크로서비스

local 로그인에는 같은 계정의 연속 5회 실패 시 15분 잠금이 적용되며 잠금 중 추가 시도로 만료가 연장되지 않습니다. 만료 뒤 정상 로그인하면 실패 기록을 초기화합니다. V13은 과거 boolean-only cloud consent를 모두 폐기하고, true consent가 owner row의 정확한 policy version과 승인 시각을 함께 갖도록 강제합니다. V14는 내부 authorization/grant/token snapshot을 도입했고, V15는 `analysis_runs`의 `QUEUED`/`PENDING` row와 `analysis_run_dispatches`의 검증된 local proposal·reserved proposal ID·idempotency/request hash·immutable executor binding·deadline을 gateway 호출 전에 함께 commit합니다. V16은 owner의 `ACTIVE` tag/alias에 대한 exact equality 결과로 만든 deterministic K=8 context를 같은 dispatch에 raw/hash/version/count로 저장합니다. 기존 V15 dispatch는 과거 문맥을 지어내지 않고 `none/0/NULL/NULL`로 남습니다. V17은 새 dispatch를 `gateway-attempt-v1`로 표시하고 claim한 각 fence마다 owner-scoped ledger row를 `max_attempts` 상한 안에서 하나씩 만듭니다. 과거 dispatch는 `attempt_history_version=none`으로 남고 attempt row를 backfill하지 않습니다. gateway result가 반환되면 execution은 `STARTED`다. executor가 작업을 받지 못하면 local `EXECUTOR_REJECTED`, execution `NOT_STARTED`, remote result `UNKNOWN`으로 남겨 gateway가 실제로 반환한 `UNAVAILABLE` result와 구분합니다. 제출 뒤 timeout·caller interruption·unexpected local termination이 발생하면 시작이 관측된 경우 `STARTED`, 관측되지 않은 경우 `UNKNOWN`이며 후자를 `NOT_STARTED`로 단정하지 않습니다. 완료·timeout·caller interruption처럼 프로세스가 종료를 관측한 시도는 `System.nanoTime` 기반 local elapsed millisecond를 저장하지만, process loss는 duration과 remote result를 `UNKNOWN`/null로 남깁니다. local termination을 관측하고 descriptor가 model version `none`의 `NO_NETWORK` Fake임을 확인한 경우 execution uncertainty와 무관하게 model-token/cost가 `NOT_APPLICABLE`/null입니다. 미래 real-model은 확정적 `NOT_STARTED`일 때만 `NOT_APPLICABLE`/null이고, 실행 또는 remote completion이 불확실하면 `UNKNOWN`/null이며, 관측된 result도 usage/cost가 gateway 계약에 연결되기 전까지 `NOT_REPORTED`/null입니다. `REPORTED` 숫자 저장 shape만 DB가 검증하며 미확인 값을 0으로 지어내지 않습니다. caller는 lease와 fence를 claim한 뒤 DB transaction 밖에서 bounded gateway 호출을 수행하며, 같은 key/body retry와 운영 scheduler recovery는 tag를 다시 조회하지 않고 저장된 context와 같은 provider token을 사용합니다. 운영 프로필에서는 bounded scheduler가 30초 fixed delay로 DB에서 `PREPARED` 또는 lease가 만료된 `RUNNING`을 최대 25건 고르고, row가 가리키는 owner와 기존 raw idempotency key에 같은 owner+operation+key advisory lock을 적용해 동일 lifecycle을 실행합니다. live lease는 호출하지 않고 다음 주기로 넘기며, process 재시작 뒤에도 남은 dispatch가 같은 상한 안에서 다시 선택됩니다. finalize는 memo owner·활성 상태·revision을 다시 잠가 확인하고, 변경되었으면 결과를 `STALE`로 확정한 transaction을 commit한 뒤 caller에게 `409 STALE_MEMO_REVISION`을 반환합니다. 이때 prepared local proposal과 retrieval-context raw text를 지우고 각 hash와 context version/count는 보존합니다. context는 hint일 뿐이며 최종 owner/reference validation이 계속 authoritative합니다. 공개 POST·DTO·proposal·`providerMetadata`·UI·평가 report는 V17에서 바뀌지 않았습니다. attempt ledger와 내부 상태, 복구용 key, prepared payload/context, provider/model ID, binding, lease, fence, provider token은 일반 log·browser/service-worker storage에도 노출하지 않습니다. ledger에는 provider text·ID·token·raw memo·retrieval context를 저장하지 않으며, 승인된 purge 정책이 생기기 전까지 현재 run data와 함께 보존하고 임의 TTL로 지우지 않습니다. 현재 Fake descriptor는 `NO_NETWORK`라 동의 없이 동작하며, 실제 external provider, Ollama/LiquidAI와 consent grant/revoke HTTP API는 구성되어 있지 않습니다. 이 경계는 exactly-once가 아니라 at-least-once이므로 실제 provider는 동일 token의 중복 호출을 멱등하게 처리해야 합니다. raw/related memo와 fuzzy/vector/embedding retrieval, 실제 model-token/cost 숫자 수집·집계·budget enforcement는 아직 구현하지 않았습니다.

위 `NOT_APPLICABLE`은 descriptor로 no-model임을 확인하고 local termination을 관측한 Fake 또는 확정적으로 시작되지 않은 real-model 실행에만 해당합니다. 시작 여부나 remote completion이 불확실한 real-model attempt와 observation 없이 유실된 process의 duration·model-token·cost evidence는 `UNKNOWN`/null로 남깁니다.

결정론적 평가 v2는 regression의 proposal schema/domain 유효성, wrong-local 0, 정밀 날짜 발명 0, local overflow 0, 누락된 overflow 신호 0, 미해결 action/object 환각 0을 hard gate로 검사합니다. `fake-v6` / `korean-rules-v4`는 순차 item과 immutable 원문의 UTF-16 source span을 보존하면서 proposal schema v2의 날짜 candidate ID와 TASK별 정밀 due candidate 참조를 제안합니다. 과거 schema v1 proposal은 복구할 수 있고 기존의 단일 TASK·단일 정밀 날짜 보수적 기본값만 유지합니다. 현재 공개 합성 자료에서 item 수는 regression/challenge 각각 12/12 case, source span은 15/15·14/14개가 일치하지만, dataset v2에는 date-to-item binding gold가 없으므로 report capability는 `SUPPORTED_NOT_SCORED_DATASET_V2`이며 binding 품질을 hard gate로 사용하지 않습니다. 엄격한 v2 2인 review manifest schema/verifier와 immutable v2 release를 참조하는 ID-only v3 binding overlay integrity validator는 준비됐지만, 실제 human manifest·adjudication·v3 dataset·binding score·`PASS`는 없습니다. [평가 label 정책](docs/EVALUATION_LABEL_POLICY.md)도 아직 human approval이 필요한 draft입니다. 외부 blind runner는 원문을 저장소나 CI에 넣지 않는 aggregate-only 경계까지만 준비됐고 metric gate는 `NOT_CONFIGURED`입니다. 실제 AI provider와 로컬 모델 연결은 독립적인 gold 검토, 사전 승인된 threshold, provider/region·보존·비용·실패 수명주기 경계가 준비되기 전까지 보류합니다.

명시 실행 전용 `PublicGoldReviewPacketRunner`는 같은 clean candidate commit을 읽기 전과 원자 게시 직전에 확인한 뒤, 공개 합성 fixture의 source·date·item gold만 허용 목록으로 렌더링한 정적 UTF-8 HTML을 `backend/target/evaluation/public-v2-review-packet.html`에 만듭니다. fixture `notes`, route/type/tag/signal gold, analyzer/Fake output, 평가 report와 다른 reviewer의 입력은 포함하지 않고, source span은 숫자 반개구간 UTF-16 `[start,end)`와 강조 표시를 함께 보여 줍니다. 이 packet에는 verdict 입력, manifest 생성, reviewer identity 또는 attestation 기능이 없습니다.

두 사람이 packet을 서로 독립적으로 검토해 strict manifest를 저장소 밖에 직접 작성한 뒤에만 `ExternalPublicGoldReviewRunner`를 명시 실행할 수 있습니다. 이 runner는 두 외부 파일과 깨끗하게 고정된 commit을 fail-closed로 검증하고 aggregate-only `public-v2-review-summary.json`만 만듭니다. `CONSENSUS_ACCEPTED`는 제출된 두 manifest의 구조와 verdict 집계일 뿐 사람의 신원·독립성, policy 승인, adjudication 완료, binding 품질, blind `PASS` 또는 provider 사용 허가를 증명하지 않습니다. 실제 manifest와 완료된 human evidence는 현재 없습니다.

설치된 구형 PWA와의 단계적 호환을 위해 proposal GET/recovery는 헤더가 없거나 `X-Analysis-Proposal-Schema-Version: 1`이면 strict v1을 반환하고, 현재 PWA는 `2`를 명시해 저장된 v2 binding을 받습니다. 이 응답 projection은 JSONB와 hash를 바꾸지 않으며 `no-store`와 `Vary`로 캐시를 분리합니다. 승인 요청의 due `timeZone`도 canonical 선택값이 아니며, 서버는 immutable memo revision의 capture time zone을 `task_details.source_time_zone`에 저장합니다.

## 문서

- [제품 및 인수 조건](docs/REQUIREMENTS.md)
- [아키텍처](docs/ARCHITECTURE.md)
- [현재 API 계약](docs/API.md)
- [OpenAPI 3.1 명세](docs/openapi.yaml)
- [현재 데이터 모델](docs/DATA_MODEL.md)
- [AI 안전 경계](docs/AI_PIPELINE.md)
- [분석 평가 기준선과 실제 LLM 진입 조건](docs/EVALUATION.md)
- [평가 label 검토와 v3 binding 준비 정책 초안](docs/EVALUATION_LABEL_POLICY.md)
- [배포 및 운영 가이드](docs/DEPLOYMENT.md)
- [마일스톤](docs/ROADMAP.md)
- [ADR](docs/adr)
