# 배포 및 운영 가이드

이 문서는 현재 저장소의 `compose.yaml`과 개발·운영·개인 PC overlay, Dockerfile, Spring `prod` profile을 기준으로 한다. 현재 배포 형태는 한 호스트에서 실행하는 통제된 포트폴리오·비공개 평가 환경이다. 개인 PC 모드는 기존 frontend Nginx의 private-LAN HTTPS와 로컬 인증서 생성, 수동 백업·복원 검증을 제공한다. 공개 도메인의 신뢰 인증서 자동 갱신, 자동 백업 일정, 모니터링, 고가용성은 제공하지 않는다.

## 안전 원칙

- 항상 `compose.yaml`과 환경별 overlay를 함께 지정한다. 기본 파일만 실행하면 개발 포트와 데이터베이스 자격 증명이 구성되지 않는다.
- 환경마다 충돌하지 않는 Compose project name을 한 번 정하고 계속 재사용한다. 이 이름은 컨테이너뿐 아니라 PostgreSQL named volume의 소유 범위도 결정한다.
- 자신이 시작한 정확한 project name과 overlay를 확인하기 전에는 `docker compose down`을 실행하지 않는다. 특히 개발·운영 데이터에는 `down --volumes`를 사용하지 않는다.
- 기존 프로젝트를 중지하거나 삭제해서 포트 충돌을 해결하지 않는다. 새 프로젝트에는 별도 project name과 host port를 배정한다.
- `.env.prod`와 백업 파일은 저장소에 커밋하지 않는다. `.gitignore`는 `.env.*`를 제외하지만 운영자는 별도의 접근 제어와 암호화도 적용해야 한다.
- PostgreSQL은 서버 데이터와 Spring Session의 원본이다. 운영 배포·마이그레이션 전에 논리 백업을 만들고 별도 프로젝트에서 복원 훈련을 완료한다.
- 개인 PC mode의 LAN HTTPS port는 신뢰하는 private network와 local subnet에만 허용한다. 공유기 port forwarding, DMZ, 공용 Wi-Fi 공개, backend/PostgreSQL host port 노출은 하지 않는다.

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

## 개인 PC와 Galaxy S24 Ultra

`compose.personal.yaml`은 `compose.yaml`과 `compose.prod.yaml` 뒤에 적용하는 얇은 private
overlay다. 기존 production 보안 설정은 유지하면서 frontend Nginx에만 고정된 `8443` TLS listener를
추가하고, 선택한 RFC1918 LAN 주소에만 publish한다. production의 `127.0.0.1:8080` listener는
container health와 PC 운영 점검용으로 남지만 Secure cookie를 사용하는 정상 로그인은 HTTPS
origin에서 수행한다. backend와 PostgreSQL은 host port가 없다.

이 private-LAN overlay는 `GOOGLE_AUTH_ENABLED=false`와 빈 provider credential을 의도적으로
강제한다. Google의 production redirect 검증을 통과하지 못하는 사설 IP callback을 억지로 사용하지
않는다. 자체 로그인으로 개인 시험을 마친 뒤 공개 HTTPS domain의 일반 `compose.prod.yaml` 배포로
이전하면, 그때 별도 credential과 정확한 redirect URI를 넣어 명시적 Google 계정 연결을 켠다.

초기화 예시는 다음과 같다. email과 표시 이름은 예시값이며 실제 개인 값은 ignored
`.env.personal`과 PostgreSQL에만 남겨야 한다. password parameter는 의도적으로 존재하지 않는다.

```powershell
# 현재 PowerShell 프로세스에만 적용되며 창을 닫으면 원래 정책으로 돌아간다.
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

.\scripts\personal\Initialize-PersonalMemo.ps1 `
  -LanIPv4 192.168.1.100 `
  -BootstrapEmail owner@example.invalid `
  -BootstrapDisplayName "Private Owner"

.\scripts\personal\Test-PersonalMemoConfig.ps1
.\scripts\personal\Initialize-PersonalAccount.ps1
# 휴대폰 접속 전, 별도의 관리자 PowerShell에서 한 번 실행한다.
.\scripts\personal\Enable-PersonalMemoFirewall.ps1
.\scripts\personal\Start-PersonalMemo.ps1
.\scripts\personal\Get-PersonalMemoStatus.ps1
```

initializer는 다음 경계를 지킨다.

- database password를 CSPRNG로 만들고 ignored `.env.personal`에만 기록한다.
- `Documents\PersonalMemo\PrivateTls`에 local CA와 LAN IP·hostname SAN을 가진 leaf certificate를
  만들고, staging/final directory와 각 파일 ACL을 민감 내용 생성 전부터 현재 Windows user 하나로
  제한한다.
- ignored `.env.personal`은 비밀을 쓰기 전에 빈 임시 파일의 ACL부터 현재 Windows user 하나로
  제한하며, `Documents\PersonalMemo\Backups`도 같은 상속 ACL로 저장소 밖에 만든다.
- S24로 복사할 공개 CA `personal-memo-ca.cer`를 별도로 만든다. `ca-key.pem`과
  `server-key.pem`은 PC 밖이나 container의 CA 위치로 복사하지 않는다. frontend에는 leaf
  certificate와 leaf key만 read-only로 mount한다.
- 기존 `.env.personal`이나 `PrivateTls`를 덮어쓰지 않는다. 주소가 바뀌어 certificate를 다시
  발급해야 할 때는 먼저 backup을 만들고 기존 private material의 보존·폐기를 사람이 결정한다.
- 이전 checkpoint에서 만든 개인 파일의 ACL을 다시 검증하거나 복구할 때는
  `.\scripts\personal\Repair-PersonalMemoPrivateAcl.ps1`을 실행한다. 내용은 바꾸지 않고 현재 Windows
  사용자 한 명의 full-control 규칙만 남긴 뒤 다시 검사한다.

`Initialize-PersonalAccount.ps1`은 현재 source의 backend image를 먼저 build한 뒤 non-web
`bootstrap-account` command를 attached terminal에서 실행한다. password는 echo 없이 두 번 직접
입력하며 environment, argument, file, log, HTTP, browser, Agent/model 경로로 받지 않는다. Flyway
singleton row와 claimed-user 검사를 같은 account
creation transaction에서 잠그므로 두 번째 실행과 동시 실행은 계정을 추가하지 못한다. local 가입과
신규 Google account 생성은 전후 모두 꺼져 있다.

S24에는 `personal-memo-ca.cer`만 전송한 뒤 설정의 **보안 및 개인정보 보호 → 기타 보안 설정 →
기기 저장공간에서 설치 → CA 인증서**에 해당하는 메뉴에서 설치한다. One UI 버전에 따라 문구가
조금 다를 수 있다. 인증서 경고를 읽고 이 PC에서 직접 만든 fingerprint인지 확인한다. 같은 private
Wi-Fi에서 `https://<LAN-IP>:8443/`를 Chrome으로 연 뒤 주소창 인증서 오류가 없는지 확인하고,
Chrome 메뉴의 **설치**를 사용한다. Chrome의 공식 Android 설치 절차는
[Google Chrome 도움말](https://support.google.com/chrome/answer/9658361?co=GENIE.Platform%3DAndroid)과
같다. 오프라인 app shell은 열리지만 network-only 인증과 canonical mutation 때문에 완전한 offline
workspace/sync를 의미하지 않는다.

Windows 기본 실행 정책이 `.ps1` 실행을 막으면 시스템 전체 정책을 낮추지 말고, 작업할 각
PowerShell 창에서 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`를 먼저 실행한다.
Windows 방화벽은 private network profile의 TCP `8443`만 `LocalSubnet`에서 허용한다. 관리자
PowerShell에서 `.\scripts\personal\Enable-PersonalMemoFirewall.ps1`을 한 번 실행하면 exact project
이름, LAN IP, port와 기존 rule scope를 검증한 뒤 이 좁은 rule만 만든다. 기존 같은 이름의 rule이
다른 범위라면 임의로 고치지 않고 중단한다. PC의 LAN address는 공유기의 DHCP reservation으로
고정하는 편이 좋다. 주소가 바뀌면 URL과 certificate SAN이 맞지 않으므로 재발급이 필요하다. router
port forwarding은 하지 않는다.

일상 운영 command는 exact project `personal-memo-private-win`만 허용한다.

```powershell
.\scripts\personal\Get-PersonalMemoStatus.ps1
.\scripts\personal\Backup-PersonalMemo.ps1
.\scripts\personal\Rotate-PersonalMemoDatabasePassword.ps1
.\scripts\personal\Stop-PersonalMemo.ps1
```

backup script는 unique container temp file에 custom-format `pg_dump`를 만들고 `pg_restore --list`와
container/host SHA-256 일치를 확인한 뒤에만 `Documents\PersonalMemo\Backups`의 최종 이름으로
원자적으로 이동한다. partial·최종 dump와 checksum은 모두 현재 Windows user 전용 ACL을 갖는다.
dump에는 원문과 account hash를 포함한 private data가 있지만 live Spring Session row는 제외하고
복원에 필요한 session table schema만 유지한다. Git이나 공유 폴더에 두지 않는다. 실제 복구 가능성은
다음 격리 검증으로 확인한다.

```powershell
.\scripts\personal\Test-PersonalMemoRestore.ps1 `
  -BackupFile "C:\Users\you\Documents\PersonalMemo\Backups\personal-memo-....dump" `
  -RemoveAfterVerification
```

restore script는 생성 규칙이 고정된 별도 project와 volume만 사용하고, checksum·dump parse·restore·
Flyway·session truncate·backend health·민감 원문을 출력하지 않는 row count를 확인한다. 또한
initial-account gate singleton, claimed user와 `AVAILABLE` gate의 비공존, orphan local credential 부재를
scalar query로 검증한다. 개인 canonical volume에 `down --volumes`를 실행하지 않는다.

database credential이 console, chat, screenshot 등에 노출됐다면 먼저 backup과 checksum을 만든 뒤
`Rotate-PersonalMemoDatabasePassword.ps1`을 실행한다. script는 CSPRNG로 새 값을 만들고 현재 user 전용
임시 파일과 원자 교체를 사용해 `.env.personal`을 갱신한다. 새 값은 stdout, Docker argument, host 임시
environment에 넣지 않고 PostgreSQL local-socket `psql`의 standard input으로만 role 변경을 전달한다.
동시 회전은 `.env.personal` 옆의 current-user-only exclusive lock file로 Windows session을 가로질러
거부한다. 이후 PostgreSQL·backend·frontend container를 재생성해 Nginx가
새 backend address를 다시 해석하게 하며, exact canonical named volume이 그대로인지, container credential
digest, backend database health, frontend를 경유한 API health가 모두 일치하는지 검증한다. role 변경
입력이 시작된 뒤 결과가 불명확하면 같은 새 값으로 한 번 재시도하고 forward-only 상태를 유지한다.
이 경우 노출된 이전 값으로 되돌리지 말고 script warning에 따라 회전을 다시 실행해 새 값으로 수렴시킨다.

향후 서버 이전은 live Docker volume 복사가 아니라 `pg_dump`와 checksum을 새 server에 전달하고,
별도 volume에 restore한 뒤 Flyway와 health/data 검증을 통과시키는 순서로 한다. application topology와
PostgreSQL owner UUID는 그대로 유지하고 personal TLS overlay 대신 server의 public HTTPS edge만
교체한다.

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

`prod` profile에서 local 가입이나 신규 Google user 자동 생성을 `true`로 덮어쓰면 backend가 시작을 거부한다. 즉, Google provider 로그인 기능을 켜더라도 기존에 연결된 identity의 로그인과 인증된 계정의 명시적 연결만 가능하다. fresh private database는 위의 one-time interactive bootstrap으로만 첫 local account를 만들 수 있고, public self-service onboarding은 계속 없는 fail-closed 상태다.

현재 Compose는 공식 PostgreSQL image의 초기화 `POSTGRES_USER`를 Flyway와 application runtime에도 함께 사용한다. 이 초기 역할은 database owner 권한을 가지므로 공개 배포의 least-privilege 구성이 아니다. 공개 출시 전에는 migration 전용 역할과 제한된 runtime 역할 및 각 비밀을 분리하고, private one-account command를 유지할지 audited invitation/administrative provisioning으로 대체할지 결정해야 한다.

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

개인 PC mode에서는 아래의 긴 수동 예시보다 경로·project·checksum·dump parse guard를 포함한
`.\scripts\personal\Backup-PersonalMemo.ps1`을 사용한다. 기본 directory는
`[Environment]::GetFolderPath('MyDocuments')\PersonalMemo\Backups`다. 아래 예시는 별도 일반
production edge를 운영할 때 각 단계의 의미를 보여 주기 위해 남긴다.

```powershell
$backupDirectory = Join-Path ([Environment]::GetFolderPath('MyDocuments')) "PersonalMemo\Backups"
$backupStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = Join-Path $backupDirectory "personal-memo-$backupStamp.dump"
New-Item -ItemType Directory -Force -Path $backupDirectory

$postgresContainer = docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml ps -q postgres
if ([string]::IsNullOrWhiteSpace($postgresContainer)) { throw "PostgreSQL container not found for $prodProject" }

docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres rm -f /tmp/personal-memo-backup.dump
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl --exclude-table-data=spring_session --exclude-table-data=spring_session_attributes --file=/tmp/personal-memo-backup.dump'
docker cp "${postgresContainer}:/tmp/personal-memo-backup.dump" $backupFile
docker compose --env-file .env.prod -p $prodProject -f compose.yaml -f compose.prod.yaml exec -T postgres rm -f /tmp/personal-memo-backup.dump

Get-Item $backupFile
Get-FileHash -Algorithm SHA256 $backupFile
```

백업에는 canonical data와 Spring Session table schema가 포함되지만 live session row와 attribute data는 제외한다. 백업 파일과 checksum을 암호화된 별도 저장소에 보관하고 retention·복원 훈련 결과를 기록한다. 파일이 존재한다는 사실만으로 복구 가능하다고 판단하지 않는다.

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
- `V11`과 `V12`의 일반 `CREATE INDEX`는 현재 작은 private database에는 적합하지만 큰 `analysis_applications`·`memo_items` table에서는 migration 동안 write를 막을 수 있다. 아직 이 migration을 적용하지 않은 대규모 공개 database를 upgrade할 때는 사전 table-size·lock 검증, writer drain과 maintenance window를 release gate로 둔다. 이후 큰 table의 index는 PostgreSQL/Flyway transaction 경계를 고려한 별도 concurrent migration 절차를 먼저 검증하며, 이미 적용된 versioned migration을 고쳐 쓰지 않는다.
- migration 실패 시 트래픽과 새 쓰기를 중단하고 실패한 database를 증거로 보존한다. 원인을 모른 채 `flyway repair`로 rollback을 대신하지 않는다.
- 이전 application image로 되돌리는 것은 새 schema와 이전 코드가 호환될 때만 가능하다. 호환되지 않으면 검증된 backup을 새 volume에 복원하고 edge를 전환한다.
- 데이터베이스 rollback은 migration SQL을 역으로 임의 실행하는 작업이 아니라, 알려진 정상 backup의 별도 복원·검증·전환 절차다.

## 현재 public-launch 제한

현재 production overlay는 안전한 기본값과 배포 형태를 검증하기 위한 것이며 공개 self-service 서비스 완성을 의미하지 않는다.

- local 가입과 신규 Google user 자동 생성은 운영에서 강제로 비활성화된다. fresh private database의 일회성 운영 bootstrap은 있지만 public self-service onboarding, invitation, password recovery는 없다. Google 로그인을 켜도 기존 identity와 명시적으로 연결된 계정만 사용할 수 있다.
- 실제 Google credential round trip은 자동화된 테스트에서 검증하지 않았고, 운영자가 Google Console 설정과 callback을 별도로 확인해야 한다.
- 계정별 5회 실패·15분 잠금은 구현되어 있지만 IP·edge 단위 rate limiting과 분산 abuse 대응은 없다.
- email verification, password-reset delivery, MFA/passkey, 완전한 계정 삭제 자동화가 없다.
- 개인 LAN용 local CA/TLS와 수동 backup/restore guard는 있지만 publicly trusted TLS edge와 인증서 자동 갱신, secret manager, 중앙 로그·metrics·alert, 자동 backup schedule·retention·restore drill은 포함되지 않는다.
- compose는 현재 source에서 image를 build한다. immutable registry release, 서명·SBOM 정책, 무중단 배포, 다중 host 장애 조치는 별도 운영 체계가 필요하다.
- 실제 local/cloud AI, 완전한 offline sync, Web Push와 reminder dispatcher도 의도적으로 보류되어 있다.

따라서 현재 형태는 접근 사용자가 제한된 포트폴리오·비공개 평가 환경에만 사용한다. 공개 출시 전에는 위 항목과 개인정보 처리·보존·삭제 정책, edge 신뢰 경계, 복구 목표를 별도 검토하고 검증해야 한다.
