# 배포 및 운영 가이드

이 문서는 현재 저장소의 `compose.yaml`과 개발·운영 overlay, Dockerfile, Spring `prod` profile을 기준으로 한다. 현재 배포 형태는 한 호스트에서 실행하는 통제된 포트폴리오·비공개 평가 환경이다. 외부 HTTPS edge, 인증서, 자동 백업, 모니터링, 고가용성은 이 저장소가 제공하지 않는다.

## 안전 원칙

- 항상 `compose.yaml`과 환경별 overlay를 함께 지정한다. 기본 파일만 실행하면 개발 포트와 데이터베이스 자격 증명이 구성되지 않는다.
- 환경마다 충돌하지 않는 Compose project name을 한 번 정하고 계속 재사용한다. 이 이름은 컨테이너뿐 아니라 PostgreSQL named volume의 소유 범위도 결정한다.
- 자신이 시작한 정확한 project name과 overlay를 확인하기 전에는 `docker compose down`을 실행하지 않는다. 특히 개발·운영 데이터에는 `down --volumes`를 사용하지 않는다.
- 기존 프로젝트를 중지하거나 삭제해서 포트 충돌을 해결하지 않는다. 새 프로젝트에는 별도 project name과 host port를 배정한다.
- `.env.prod`와 백업 파일은 저장소에 커밋하지 않는다. `.gitignore`는 `.env.*`를 제외하지만 운영자는 별도의 접근 제어와 암호화도 적용해야 한다.
- PostgreSQL은 서버 데이터와 Spring Session의 원본이다. 운영 배포·마이그레이션 전에 논리 백업을 만들고 별도 프로젝트에서 복원 훈련을 완료한다.

## 개발 환경

저장소 루트의 PowerShell에서 다음처럼 개발용 환경 파일과 고유한 project name을 사용한다. `yourname`은 이 작업 복제본에서 계속 재사용할 짧은 값으로 바꾼다.

```powershell
Copy-Item .env.example .env.dev
$devProject = "personal-memo-dev-yourname"

docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml config --quiet
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml up -d --build --wait
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml ps
```

개발 overlay는 다음 주소만 host loopback에 연다.

- PWA: `http://127.0.0.1:5173`
- API health: `http://127.0.0.1:8080/api/v1/health`
- 내부 운영 상태 확인용 Actuator: `http://127.0.0.1:8080/actuator/health`
- PostgreSQL: `127.0.0.1:5432`

포트가 겹치면 기존 stack을 내리지 말고 `.env.dev`에서 `PERSONAL_MEMO_FRONTEND_PORT`, `PERSONAL_MEMO_BACKEND_PORT`, `PERSONAL_MEMO_POSTGRES_PORT`를 다른 값으로 지정한다.

Google 개발 로그인을 시험할 때만 `.env.dev`의 다음 값을 바꾼다. Google Console의 승인된 redirect URI도 문자 단위로 같아야 한다.

```dotenv
GOOGLE_AUTH_ENABLED=true
GOOGLE_REGISTRATION_ENABLED=false
GOOGLE_CLIENT_ID=your-web-client-id
GOOGLE_CLIENT_SECRET=your-server-only-secret
GOOGLE_REDIRECT_URI=http://127.0.0.1:5173/login/oauth2/code/google
```

개발 stack을 잠시 멈출 때는 정확한 project를 확인한 뒤 `stop`을 사용한다. named volume은 보존된다.

```powershell
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml ps
docker compose --env-file .env.dev -p $devProject -f compose.yaml -f compose.dev.yaml stop
```

## 운영 환경

### 외부 HTTPS edge 전제

운영 overlay는 frontend의 Nginx만 `127.0.0.1:${PERSONAL_MEMO_FRONTEND_PORT:-8080}`에 공개한다. PostgreSQL과 Spring Boot port는 host에 공개하지 않는다. 별도의 신뢰 가능한 reverse proxy 또는 load balancer가 다음 조건을 만족해야 한다.

- 공개 `https://` origin의 TLS 종료, 인증서 발급·갱신과 HTTP→HTTPS 전환을 담당한다.
- 공개 origin의 모든 경로를 frontend loopback port로 전달한다. frontend가 정적 PWA와 `/api`, `/oauth2`, `/login/oauth2`를 같은 origin에서 제공한다.
- 공개 `Host`를 보존하고 `X-Forwarded-Proto`와 `X-Forwarded-Port`를 외부 클라이언트 값과 무관하게 신뢰 가능한 edge 값으로 덮어쓴다.
- backend와 PostgreSQL을 인터넷에 직접 공개하지 않는다.
- cookie, CSRF, OAuth callback을 고려해 PWA와 API를 서로 다른 origin으로 분리하지 않는다. cross-origin 배포는 별도의 CORS·cookie·CSRF 보안 검토 없이는 지원하지 않는다.

Spring의 `prod` profile은 forwarded header 해석을 활성화하고 session cookie와 `XSRF-TOKEN` cookie를 `Secure`, `SameSite=Lax`로 발급한다. frontend Nginx는 정확한 `http`/`https` scheme과 1–5자리 forwarded port만 backend로 보존하고, 누락되거나 잘못된 값은 자신의 connection 값으로 대체한다. frontend port가 loopback에만 묶인다는 전제 아래 public header의 신뢰 주체는 외부 edge다. 이 설정은 HTTP만 공개해도 안전해진다는 뜻이 아니며 실제 사용자 트래픽은 반드시 HTTPS edge를 통과해야 한다.

### 운영 환경 변수

`.env.example`의 개발 비밀번호를 운영에 복사하지 말고, 저장소에 없는 `.env.prod`를 별도로 만든다.

| 변수 | 필요 여부 | 규칙 |
| --- | --- | --- |
| `POSTGRES_DB` | 필수 | 운영 전용 데이터베이스 이름 |
| `POSTGRES_USER` | 필수 | 현재 controlled checkpoint의 PostgreSQL 초기화·migration·runtime 공용 역할 |
| `POSTGRES_PASSWORD` | 필수 | 개발 기본값이 아닌 고유한 강한 비밀 |
| `PERSONAL_MEMO_FRONTEND_PORT` | 선택 | loopback port, 기본값 `8080` |
| `GOOGLE_AUTH_ENABLED` | 선택 | 기본값 `false` |
| `GOOGLE_REGISTRATION_ENABLED` | 선택 | 신규 Google user 자동 생성; 기본값 `false`, 운영에서는 강제 `false` |
| `GOOGLE_CLIENT_ID` | 조건부 필수 | Google을 켤 때 필요 |
| `GOOGLE_CLIENT_SECRET` | 조건부 필수 | Google을 켤 때 필요하며 browser에 전달하지 않음 |
| `GOOGLE_REDIRECT_URI` | 조건부 필수 | Google을 켤 때 절대형 public HTTPS URI |

예시는 다음과 같다. 실제 비밀을 문서나 Git에 기록하지 않는다.

```dotenv
POSTGRES_DB=personal_memo
POSTGRES_USER=personal_memo_app
POSTGRES_PASSWORD=replace-with-a-unique-production-secret
PERSONAL_MEMO_FRONTEND_PORT=8080

GOOGLE_AUTH_ENABLED=false
GOOGLE_REGISTRATION_ENABLED=false
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URI=
```

운영 overlay가 다음 값을 강제로 설정하므로 `.env.prod`에서 우회하지 않는다.

- `SPRING_PROFILES_ACTIVE=prod`
- `AUTH_REGISTRATION_ENABLED=false`
- `GOOGLE_REGISTRATION_ENABLED=false`
- `SESSION_COOKIE_SECURE=true`

`prod` profile에서 local 가입이나 신규 Google user 자동 생성을 `true`로 덮어쓰면 backend가 시작을 거부한다. 즉, Google provider 로그인 기능을 켜더라도 기존에 연결된 identity의 로그인과 인증된 계정의 명시적 연결만 가능하다. fresh 운영 database에는 지원되는 self-service onboarding 경로가 없는 fail-closed 상태다.

현재 Compose는 공식 PostgreSQL image의 초기화 `POSTGRES_USER`를 Flyway와 application runtime에도 함께 사용한다. 이 초기 역할은 database owner 권한을 가지므로 공개 배포의 least-privilege 구성이 아니다. 공개 출시 전에는 migration 전용 역할과 제한된 runtime 역할 및 각 비밀을 분리하고, fresh database의 첫 계정을 만드는 감사 가능한 bootstrap/invitation 절차를 별도로 구현해야 한다.

동일 local 계정에서 잘못된 비밀번호가 연속 5회 발생하면 15분 동안 잠긴다. 잠금 중 추가 시도는 만료를 연장하지 않고, 만료 뒤 정상 로그인하면 실패 counter와 `locked_until`을 초기화한다. 이 방어는 IP·edge 단위 rate limiting이나 분산 abuse 방어를 대신하지 않는다.

이미 초기화된 PostgreSQL volume에서 `.env.prod`의 `POSTGRES_PASSWORD`만 바꿔도 database role의 비밀번호는 자동 회전하지 않는다. 비밀번호 회전은 database role 변경과 backend secret 갱신을 하나의 별도 운영 절차로 계획하고, 전후 연결과 rollback을 검증한다.

### 사전 검사와 기동

운영 호스트마다 안정적이고 고유한 project name을 정한다. 배포할 때마다 이름을 바꾸면 새 database volume이 만들어지므로 같은 환경에서는 반드시 재사용한다.

```powershell
$prodProject = "personal-memo-prod-host01"

docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml config --quiet
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml up -d --build --wait
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps
```

현재 backend image는 multi-stage build로 생성되며 runtime에서는 numeric UID/GID `10001:10001`로 실행된다. frontend도 unprivileged Nginx 사용자로 실행된다. 운영 overlay는 backend filesystem을 read-only로 만들고 `/tmp`만 tmpfs로 제공하며 capability와 privilege escalation을 제한한다.

운영 stack을 멈춰야 할 때도 project name과 overlay를 먼저 대조한 뒤 `stop`만 사용한다.

```powershell
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml stop
```

### Google redirect URI

Google 로그인을 켜면 세 값이 모두 필요하다.

```dotenv
GOOGLE_AUTH_ENABLED=true
GOOGLE_REGISTRATION_ENABLED=false
GOOGLE_CLIENT_ID=your-production-web-client-id
GOOGLE_CLIENT_SECRET=your-production-server-only-secret
GOOGLE_REDIRECT_URI=https://memo.example.com/login/oauth2/code/google
```

Google Console의 OAuth Web client에도 같은 URI를 등록한다. 운영 backend는 client ID·secret 누락, 상대 URI, HTTP URI, `{baseUrl}` template, userinfo·fragment가 있는 URI, localhost·내부 전용 이름·사설/링크 로컬/예약 IP host 또는 `GOOGLE_REGISTRATION_ENABLED=true`를 잘못된 설정으로 보고 시작을 거부한다. `GOOGLE_AUTH_ENABLED=true`와 `GOOGLE_REGISTRATION_ENABLED=false` 조합은 기존 Google identity 로그인과 명시적 account linking만 허용한다. secret은 backend 환경에만 주입하고 frontend build, browser storage, 로그에 넣지 않는다.

## 상태 확인

먼저 Compose가 세 서비스의 health 상태를 어떻게 판단하는지 확인한다.

- PostgreSQL: `pg_isready`
- backend container: `http://127.0.0.1:8080/actuator/health`
- frontend container: `http://127.0.0.1:5173/`

```powershell
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T backend wget -q -O - http://127.0.0.1:8080/actuator/health
Invoke-RestMethod https://memo.example.com/api/v1/health
```

외부 감시는 HTTPS의 `/api/v1/health`를 API 도달성 probe로 사용한다. 이 endpoint는 PostgreSQL까지 포함한 종합 health가 아니며, frontend 자체 health도 backend의 사후 장애를 대신 감지하지 않는다. 현재 frontend는 `/actuator`를 공개 API로 proxy하지 않으므로 database contributor를 포함하는 Actuator는 container 내부 확인용이다. health가 실패하면 정확한 project의 제한된 로그만 확인한다.

```powershell
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml logs --tail 200 backend frontend postgres
```

## PostgreSQL 백업

정기 백업과 모든 배포·Flyway migration 직전에 `pg_dump` custom format 백업을 만든다. migration이 실행되는 동안에는 백업이나 restore를 시작하지 않는다. 다음 명령은 dump를 container의 고정된 임시 파일에 만든 뒤 저장소 밖의 host directory로 복사한다.

```powershell
$backupDirectory = Join-Path $env:USERPROFILE "personal-memo-backups"
$backupStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = Join-Path $backupDirectory "personal-memo-$backupStamp.dump"
New-Item -ItemType Directory -Force -Path $backupDirectory

$postgresContainer = docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps -q postgres
if ([string]::IsNullOrWhiteSpace($postgresContainer)) { throw "PostgreSQL container not found for $prodProject" }

docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres rm -f /tmp/personal-memo-backup.dump
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl --file=/tmp/personal-memo-backup.dump'
docker cp "${postgresContainer}:/tmp/personal-memo-backup.dump" $backupFile
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres rm -f /tmp/personal-memo-backup.dump

Get-Item $backupFile
Get-FileHash -Algorithm SHA256 $backupFile
```

백업에는 canonical data와 Spring Session table이 함께 포함된다. 백업 파일과 checksum을 암호화된 별도 저장소에 보관하고 retention·복원 훈련 결과를 기록한다. 파일이 존재한다는 사실만으로 복구 가능하다고 판단하지 않는다.

## 별도 프로젝트에서 복원 검증

운영 volume 위에 바로 restore하지 않는다. 별도의 `.env.restore`, 고유 project name, 사용하지 않는 loopback port로 먼저 복구한다. `.env.restore`의 database 이름·사용자·비밀번호는 복원 훈련 전용 값이어야 한다.

```powershell
$restoreProject = "personal-memo-restore-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$env:PERSONAL_MEMO_FRONTEND_PORT = "18080"

docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml config --quiet
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml up -d --wait postgres

$restorePostgres = docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml ps -q postgres
if ([string]::IsNullOrWhiteSpace($restorePostgres)) { throw "Restore PostgreSQL container not found" }

docker cp $backupFile "${restorePostgres}:/tmp/personal-memo-restore.dump"
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml exec -T postgres sh -c 'pg_restore --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --clean --if-exists --no-owner --no-acl --exit-on-error /tmp/personal-memo-restore.dump'
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml exec -T postgres rm -f /tmp/personal-memo-restore.dump

docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml up -d --build --wait backend
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml exec -T postgres sh -c 'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --command="TRUNCATE TABLE spring_session CASCADE"'
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml up -d --wait frontend

Invoke-RestMethod http://127.0.0.1:18080/api/v1/health
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml ps
```

backend가 시작될 때 복원된 schema의 `flyway_schema_history`를 검사하고 아직 적용되지 않은 migration을 순서대로 적용한다. 복원된 과거 session은 재사용하지 않도록 frontend를 열기 전에 `spring_session`을 비운다. 이후 대표 계정·메모·태그·할 일·그래프·되돌리기를 읽기 중심으로 검증한다.

폐기 가능한 복원 훈련 project만 정리할 때는 project name을 방어적으로 검사한다. 실제 운영 project에는 이 명령을 사용하지 않는다.

```powershell
if (-not $restoreProject.StartsWith("personal-memo-restore-")) { throw "Unexpected restore project name" }
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml ps
docker compose --env-file .env.restore -p $restoreProject -f compose.yaml -f compose.prod.yaml down --volumes
Remove-Item Env:PERSONAL_MEMO_FRONTEND_PORT
```

실제 장애 복구도 같은 원칙으로 새 project·volume에 복원하고 검증한 다음 HTTPS edge의 upstream을 새 frontend port로 전환한다. 실패한 운영 volume을 덮어쓰거나 먼저 삭제하지 않는다.

## Flyway와 rollback 원칙

- 적용된 versioned migration을 수정·삭제·순서 변경하지 않는다. schema 변경은 다음 번호의 새 migration으로 전진시킨다.
- backend 시작이 Flyway를 자동 실행하므로, 배포 전에 백업과 restore 검증을 끝내고 한 번에 하나의 migration 주체만 시작한다.
- migration 실패 시 트래픽과 새 쓰기를 중단하고 실패한 database를 증거로 보존한다. 원인을 모른 채 `flyway repair`로 rollback을 대신하지 않는다.
- 이전 application image로 되돌리는 것은 새 schema와 이전 코드가 호환될 때만 가능하다. 호환되지 않으면 검증된 backup을 새 volume에 복원하고 edge를 전환한다.
- 데이터베이스 rollback은 migration SQL을 역으로 임의 실행하는 작업이 아니라, 알려진 정상 backup의 별도 복원·검증·전환 절차다.

## 현재 public-launch 제한

현재 production overlay는 안전한 기본값과 배포 형태를 검증하기 위한 것이며 공개 self-service 서비스 완성을 의미하지 않는다.

- local 가입과 신규 Google user 자동 생성은 운영에서 강제로 비활성화된다. Google 로그인을 켜도 기존 identity와 명시적으로 연결된 계정만 사용할 수 있으므로 fresh database에는 지원되는 self-service onboarding 경로가 없다.
- 실제 Google credential round trip은 자동화된 테스트에서 검증하지 않았고, 운영자가 Google Console 설정과 callback을 별도로 확인해야 한다.
- 계정별 5회 실패·15분 잠금은 구현되어 있지만 IP·edge 단위 rate limiting과 분산 abuse 대응은 없다.
- email verification, password-reset delivery, MFA/passkey, 완전한 계정 삭제 자동화가 없다.
- TLS edge, 인증서 자동 갱신, secret manager, 중앙 로그·metrics·alert, 자동 백업·retention·restore drill이 저장소에 포함되지 않는다.
- compose는 현재 source에서 image를 build한다. immutable registry release, 서명·SBOM 정책, 무중단 배포, 다중 host 장애 조치는 별도 운영 체계가 필요하다.
- 실제 local/cloud AI, 완전한 offline sync, Web Push와 reminder dispatcher도 의도적으로 보류되어 있다.

따라서 현재 형태는 접근 사용자가 제한된 포트폴리오·비공개 평가 환경에만 사용한다. 공개 출시 전에는 위 항목과 개인정보 처리·보존·삭제 정책, edge 신뢰 경계, 복구 목표를 별도 검토하고 검증해야 한다.
