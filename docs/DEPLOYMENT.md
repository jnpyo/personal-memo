# 배포 및 운영 가이드

이 문서는 현재 저장소의 `compose.yaml`과 개발·운영·개인 PC overlay, Dockerfile, Spring `prod`
profile을 기준으로 한다. 현재 완료 대상으로 부르는 **1차 비공개 베타**는 한 호스트에서 단일
operator-provisioned owner가 신뢰하는 RFC1918 LAN 안에서 온라인으로 사용하는 접근 제한형
환경이다. local CA를 신뢰한 기기만 접속하고, 공유기 port forwarding은 없으며, registration과
개인 overlay의 Google 기능은 꺼지고, 일반 application은 Fake/deterministic 분석을 기본으로 한다.
개인 overlay만 exact-pinned localhost LiquidAI를 `AI_PREFERRED` proposal path로 사용한다. 개인 PC
모드는 기존 frontend Nginx의 private-LAN HTTPS와 로컬 인증서 생성, 수동 백업·복원 검증을 제공한다.
공개 도메인의 신뢰 인증서 자동 갱신, 자동 백업 일정, 모니터링, 고가용성은 제공하지 않는다.

## 안전 원칙

- 항상 `compose.yaml`과 환경별 overlay를 함께 지정한다. 기본 파일만 실행하면 개발 포트와 데이터베이스 자격 증명이 구성되지 않는다.
- 환경마다 충돌하지 않는 Compose project name을 한 번 정하고 계속 재사용한다. 이 이름은 컨테이너뿐 아니라 PostgreSQL named volume의 소유 범위도 결정한다.
- 자신이 시작한 정확한 project name과 overlay를 확인하기 전에는 `docker compose down`을 실행하지 않는다. 특히 개발·운영 데이터에는 `down --volumes`를 사용하지 않는다.
- 기존 프로젝트를 중지하거나 삭제해서 포트 충돌을 해결하지 않는다. 새 프로젝트에는 별도 project name과 host port를 배정한다.
- `.env.prod`와 백업 파일은 저장소에 커밋하지 않는다. `.gitignore`는 `.env.*`를 제외하지만 운영자는 별도의 접근 제어와 암호화도 적용해야 한다.
- PostgreSQL은 서버 데이터와 Spring Session의 원본이다. 운영 배포·마이그레이션 전에 논리 백업을 만들고 별도 프로젝트에서 복원 훈련을 완료한다.
- 개인 PC mode의 LAN HTTPS port는 신뢰하는 private network와 local subnet에만 허용한다. 공유기 port forwarding, DMZ, 공용 Wi-Fi 공개, backend/PostgreSQL host port 노출은 하지 않는다.
- 이 베타 범위를 multi-user, untrusted LAN 또는 internet-facing으로 넓히지 않는다. 그런 변경은
  onboarding/recovery/rate-limit/deletion/edge/monitoring 요구와 새 배포 결정을 먼저 필요로 한다.
- Source에 V21 migration이 존재하더라도 별도 owner 승인 없이 personal stack을 새 image로 rebuild하거나
  backend를 그 source로 재시작하지 않는다. Flyway auto-migration은 문서 정합성 작업의 부수 효과로
  실행해서는 안 된다.

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

### 개인 overlay의 guarded localhost LiquidAI

`compose.personal.yaml`은 아래 비밀이 아닌 값을 source에 직접 고정한다. `.env.personal`로
endpoint/model을 바꾸지 않는다.

- endpoint와 유일한 allowed Docker-host relay origin:
  `http://host.docker.internal:11434`
- model: `hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0`
- digest: `677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822`
- invocation mode: `AI_PREFERRED`
- approved-correction hint: enabled, `approved-type-anchor-k3-v1`, K=3
- outer durable gateway timeout: `45s` (each direct Ollama request remains bounded to `30s`)

기본 `application.yml`은 `app.analysis.local-model.enabled=false`, invocation
`UNCERTAINTY_ONLY`, approved corrections disabled라 personal overlay 없이 실행하면 Fake gateway를
선택하고 clear proposal에는 model call을 하지 않는다. personal overlay만 local model,
`AI_PREFERRED`, approved corrections와 K=3을 함께 켜며 위 exact identity를 공급한다. 이 mode는
deterministic Fake proposal이 JSON Schema/domain validation을 통과한 모든 current revision에서
Ollama를 호출한다. semantic decision이 clear여도 ambiguity를 발명하지 않고 invocation policy를
별도 V20 evidence로 남긴다. adapter는 `/api/tags`와 `/api/chat`만 직접 호출하고 proxy, redirect,
model tool을 허용하지 않는다. Ollama를 `0.0.0.0` 또는 LAN에 publish하지 않는다. backend와
PostgreSQL host port도 계속 없다.

Chat request는 top-level `truncate=false`, `shift=false`를 고정해 context 초과를 조용히 잘라내지
않는다. Durable descriptor의 `gatewayVersion`은 adapter+prompt contract, `providerId`는
`ollama-local@<exact model tag>`, `modelVersion`은 exact 64-hex digest다. tag, digest, prompt contract
중 하나라도 달라지면 persisted binding과 일치하지 않아 recovery call 전에 fail closed한다.

personal `AI_PREFERRED`에서는 결정론적 analyzer가 모든 cue를 설명해도
`LOCAL_MACHINE_MEMO_CONTENT` 호출을 준비한다. 모델 호출용 full current revision은 bounded 실행
메모리에서만 전달한다. V19/V20 evidence, attempt, provider metadata, log, browser storage와 training
dataset에는 memo/prompt/response를 새로 복제하지 않는다. 기존 V15의 recoverable validated-local
proposal은 grounded source text를 포함할 수 있지만 `FINALIZED`에서 scrub된다. model은 semantic-patch
v2 `KEEP` 또는 narrow `PATCH`만 반환할 수 있고 둘 다 JSON Schema/domain/owner/revision 검증 뒤
proposal로만 제공된다. automatic Apply는 없다. failure, timeout, model/digest mismatch, truncation
또는 invalid output은 재검증된 local 상세 검토다. default-`RECORD` fallback evidence만 `UNKNOWN`으로
정규화하며 기존 명시 후보는 유지한다. `num_predict=1024`는 STOP을 위해 관측된 provisional
hidden-reasoning budget이며 visible model output, HTTP response와 proposal byte limit는 별도로 더 작게
유지된다.

승인 교정 hint는 같은 owner의 active/current/latest `APPLIED` selection 중 type-corrected 또는
user-resolved인 eligible 단일-item 사례만 본다. exact, undone, rejected, postponed, stale,
unclassifiable, relation-bearing 또는 multi-item 사례는 제외한다. historical memo/selection을 prompt나
snapshot에 넣지 않고, current memo에도 exact-unique하게 나타나는 충돌 없는 짧은 anchor 최대 K=3의
UTF-16 offset와 approved kind만 호출 전에 저장한다. claim 시 locked current revision에서 anchor text를
materialize하고 retry는 동일 snapshot을 사용한다. `FINALIZED`에서는 raw offset snapshot을 지우고
hash/version/count만 보존한다. hash/version/offset/Unicode/binding이 유효하지 않으면 model 0-call
validated Fake fallback이다. 이 경로는 RAG corpus/vector/embedding, 자동 rule promotion, training,
fine-tuning 또는 LoRA가 아니다.

V20은 V19 dispatch에 별도 invocation-policy evidence와 approved-correction offset/type snapshot
lifecycle을 추가하는 forward-only migration이다. 개인 stack을 새 source로 재시작하기 전에는
합성 PostgreSQL migration/integration gate, backend 및 frontend 회귀, OpenAPI와 Compose render를
먼저 통과시키고 기존 backup/restore 절차를 따른다. V20 source mechanical qualification은
isolated Flyway V1–V20, backend 773 tests, Spotless, SpotBugs, frontend lint/301 tests/build,
OpenAPI lint와 example-only personal Compose render까지 `PASS_ISOLATED`다. 이 gate와 별개의 model
accuracy qualification은 `NOT_RUN_NO_CLAIM`이며 상태는
`docs/PRIVATE_BETA.md`에 기록한다. 2026-08-24 owner 승인 뒤 exact personal stack의 checksummed backup과
별도 restore rehearsal, Flyway V18→V20, rebuild와 health 확인을 완료했다. product smoke는 개인 DB가
아닌 disposable synthetic PostgreSQL에서만 수행했다. adapter-v1 invalid output은 fail closed했고
adapter-v2 exact-sentence compatibility run은 strict contract 아래 성공했지만 broad provider/model
quality를 승인하지 않으므로 provider `NO_GO`를 유지했다. rollout은 `GO_TO_DEVICE_ACCEPTANCE`이며 이 문서/config 검증만으로 실제
개인 memo 정확도나 기기 UI acceptance를 완료했다고 주장하지 않는다.

### 반복 가능한 격리 AI-preferred 제품 경로 smoke

이 검증은 personal overlay나 기본 `11434` Ollama를 재사용하지 않는다. 고정 공개 synthetic fixture,
별도 Fake/Liquid Compose project, tmpfs PostgreSQL, 고유 temporary backend image와 owned Ollama
`127.0.0.1:11435`만 사용한다. 실행 전 source contract를 확인하고 저장소 루트에서 다음 명령을
사용한다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\analysis\Test-PersonalMemoAiProductSmokeSourceContracts.ps1'
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -Command "& '.\scripts\analysis\Invoke-PersonalMemoAiPreferredSyntheticSmoke.ps1' -Confirm:`$false"
```

오케스트레이터는 exact Ollama version/model/digest, free loopback ports, Docker readiness, fixture와
schema hash를 fail-closed로 확인한다. register → memo → analysis-run → proposal GET만 호출하며
Apply/reject/postpone/undo, alarm/reminder, personal API/DB, external product service를 호출하지 않는다.
성공 receipt는 모든 arm과 owned Ollama/image/temp 정리가 끝나고 personal project container count가
변하지 않은 뒤에만 `backend/target/evaluation`에 이동한다. 실패 시 aggregate receipt를 발행하지
않고 bounded safe code만 출력한다.

2026-08-28 receipt
`ai-preferred-product-smoke-20260828T054016485Z.json`은 strict validation을 통과했고 SHA-256은
`d605ed48935d8dd5acbd98ff7e658c495f70cb1467f69ad8efb1b656f5fcca3b`다. 결과는 LiquidAI
success/fallback `1/2`, median `6958 ms` 대 Fake `73 ms`, semantic improvement
`NOT_DEMONSTRATED`, zero canonical delta였다. device-wide/non-exclusive baseline/max/post VRAM은
`3306`/`6478`/`3045 MiB`, max utilization `93%`였다. 이 결과는
`PASS_NARROW_PRODUCT_PATH`/`SOLO_PROVISIONAL/REPORT_ONLY`이고 provider/training/LoRA는 계속
`NO_GO`, RAG는 `NOT_USED`, automatic Apply는 disabled다. Receipt의 `dirty=true` 때문에 clean release
qualification으로 사용하지 않는다. `externalProductServiceAccessed=false`는 외부 product service
호출이 없었다는 뜻이며 Docker build/pull egress까지 차단하거나 관측했다는 증거는 아니다. 복구
증거도 열거된 owned resource와 personal container count 범위이며 머신 전체 상태의 byte-for-byte
동일성을 뜻하지 않는다.

### Milestone 6A.1/V21, 6B, 6C/V22 source and personal deployment checkpoint

현재 source에는 proposal v2를 그대로 유지한 수동 EVENT schedule Apply, Flyway
`V21__event_temporal_foundation.sql`, owner-scoped `GET /api/v1/events`, PWA confirmed schedule list가
있다. V21은 기존 title-only EVENT를 backfill하지 않고 scheduled Apply에만 `event_details`를 만든다.
이 기능 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`다. 개인 stack의 deployed schema는 2026-08-27
owner-authorized backup/restore rehearsal과 forward-only migration 뒤 V22다.
Source analyzer는 `fake-v10`/`korean-rules-v8`로 명시적
`오늘|내일|모레 + 오전|오후 + 1–12시`(optional minutes)를 revision source zone의
`RELATIVE_EXACT` candidate로 만든다. 날짜가 없고 무입자 또는 `에`인 explicit clock family—bare
1–12시 optional minutes, 오전/오후, Korean 24-hour clock, `HH:mm`—도 immutable revision capture
instant/source zone에서 작성 당일의 엄격히 미래인 가장 이른 safe occurrence만 제안한다. DST-gap
occurrence는 제외하고 더 늦은 unique same-day 후보를 허용하지만 미래 overlap occurrence가 있으면
전체 expression은 `UNKNOWN`이다. 남은 safe 후보나 valid source zone이 없어도 `UNKNOWN`이며 다음
날로 이월하지 않는다. 일정 선택과 final Apply는 계속 수동이고 automatic alarm/reminder는 없다.
TIMED Apply는 immutable revision zone과 start/end offset 일치를 검사하고 DST gap을 거절하며
overlap의 explicit valid offset을 보존한다. 6B RFC 5545 호환성을 위해 fractional-second
TIMED input도 canonical write 전에 거절한다.

Milestone 6A.2a의 proposal-v3 지원은 JSON contract/read projection/PWA display/evaluation-integrity
preparation뿐이라 새 migration이 없다. Fake와 personal localhost adapter는 v2 producer로 유지되고,
EVENT review는 v3에서도 schedule null로 시작하며 current domain은 non-null analyzer suggestion을
거절한다. 따라서 이 source 준비는 V21 또는 v3를 personal stack에 배포하거나 model quality를
승인하지 않는다.

Milestone 6B source에는 session/expected-owner-guarded
`GET /api/v1/events/calendar.ics`와 PWA plain-text preview/exact-Blob download가 있다. 응답은 no-store,
100-event/128-KiB bounded one-time snapshot이며 raw memo, internal UUID, provenance, token, public URL,
alarm과 recurrence를 포함하지 않는다. 새 Flyway table/config/secret/listener/edge를 만들지 않고, source
qualification은 synthetic disposable PostgreSQL만 사용한다. 이 endpoint의 source 구현은 personal
V21 migration·image rebuild·route smoke 또는 internet exposure 승인이 아니다.

Milestone 6C source는 forward-only `V22` calendar feed/entry projection, authenticated management,
client-generated 256-bit secret의 digest-only verifier, recipient-scoped UID/sequence/cancellation,
그리고 fixed `GET|HEAD /calendar/v1/feed.ics?token=...` stateless read boundary를 추가한다. Private
same-origin Nginx는 exact fixed path만 backend로 전달하고 safe access log는 `$uri`만 기록해 query를
제외한다. PWA는 URL을 생성/rotate 성공 직후 memory에서만 조립하고 clickable navigation, browser
storage, service-worker cache를 사용하지 않는다. 이 source slice도 personal V22 migration/rebuild,
public hostname/listener/edge/rate limit, internet exposure 또는 external calendar smoke가 아니다.

이 6C source-only 상태는 2026-08-25 별도 격리 PostgreSQL에서 Flyway V1–V22, backend 845 tests /
0 failures / 0 errors / 1 skipped, Spotless/SpotBugs, frontend lint와 44 files / 377 tests,
TypeScript/PWA build, Redocly 2.44.1, production/personal Compose render, Windows source contract,
secure-localhost Playwright 24/24 (25.5 s)을 통과했다. Private isolated Nginx의 synthetic unknown-token
GET/HEAD는 empty no-store 404였고 owned frontend/backend synthetic sentinel은 0건이었다. 이 결과는
public edge/external-provider log 증명이 아니다. 추가 synthetic integration은 exact lifetime cap/
rollback, ALL_DAY active/cancelled와 6개 source-mutation race를 검증했다. 최초 835-test 실행 전의
cached-context synthetic PostgreSQL connection-limit 6 error는 test resource에만 Hikari pool 4/
minimum-idle 0을 고정해 해결했고, 별도 CLI override 없는 fresh full verify가 845 tests로 통과했다.
Exact 6C temporary Docker resources는 제거했고 개인 V20 stack, 개인 PostgreSQL/메모/canonical data와
Ollama는 접근하거나 변경하지 않았다. 상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`다.

2026-08-27 전환은 checksummed V20 cutover backup, backend 기동 전 V20/기동 후 V22를 확인하는
disposable restore rehearsal, failed Flyway 0과 V21/V22 zero-backfill, rebuilt stack/trusted HTTPS
health, synthetic unknown-token private GET/HEAD와 query-free log sentinel을 통과했다. Restore 임시
container/network/volume은 제거했고 V20/V22 image digest tag는 모두 보존했다. 이 과정에서 raw memo
body나 개인 일정 내용을 읽지 않았고 feed/token/membership canonical row도 만들지 않았다.

다음 작업은 **이 승인에 포함되지 않았다**.

- personal canonical data를 사용하는 schedule backfill, inspection, Apply, smoke, or repair
- proposal-v3 producer/preselection activation, EVENT human-label adjudication/quality gate
- 실제 개인 schedule을 사용하는 `.ics` preview/download나 recipient feed 생성·membership·rotate·revoke
- public calendar hostname/TLS/edge/operator/rate limit, internet continuous subscription, external
  calendar-client import/update/removal smoke

향후 schema 변경도 먼저 candidate source의 isolated PostgreSQL migration/integration,
backend/frontend 회귀, API release-contract alignment, Compose render를 고정한다. 그 다음 정확한
personal project/volume을 확인하고 새 checksummed backup과 별도 restore rehearsal을 통과한 뒤에만
migration/rebuild/health/product smoke를 순서대로 실행한다. 이번 V22 승인과 백업은 후속 migration,
canonical-data smoke 또는 public edge 권한을 자동으로 제공하지 않는다.

### Milestone 6D.1 public-origin capability source and private checkpoint

6D의 첫 slice는 future public URL authority를 server-owned 설정으로 고정한 source 구현으로
시작했다. Backend property/authenticated no-store controller와 frontend strict decoder/UI를
구현했다. `GET /api/v1/calendar-feeds/capabilities`는 다음 exact union만 반환한다.

```json
{"mode":"LOCAL_ONLY","publicOrigin":null,"consentPolicyVersion":null}
```

```json
{"mode":"PUBLIC_HTTPS","publicOrigin":"https://calendar.example.com","consentPolicyVersion":"calendar-feed-public-v1"}
```

기본 property는 `APP_CALENDAR_FEED_PUBLICATION_ENABLED=false`, blank
`APP_CALENDAR_FEED_PUBLIC_ORIGIN`, blank `APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION`이다.
Enabled/origin/policy 불일치나 invalid enabled origin/policy는 backend
startup을 거절한다. `PUBLIC_HTTPS`는 server-owned maximum 255-character normalized lowercase
`https://` multi-label ASCII hostname과 optional non-default port 1–65535만 허용한다. Userinfo, IP literal,
`localhost`와 그 subdomain, path, query, fragment, trailing slash, explicit `:443`, request
`Host`/forwarded header, browser input과 feed/memo data는 public origin authority가 아니다. 이
검사는 public-suffix 소유권이나 DNS reachability를 증명하지 않으며 edge gate가 따로 확인한다.

Frontend는 valid `PUBLIC_HTTPS`와 exact known policy에서만 server origin을 사용한다. Valid exact `LOCAL_ONLY`는 현재
PWA의 exact HTTP(S) origin으로 로컬·격리 검증용 URL을 만들 수 있지만 외부 수신자에게 보내지
말라는 경고를 표시한다. Capability request failure 또는 missing/malformed response는 valid
`LOCAL_ONLY`로 바꾸거나 private origin으로 조용히 fallback하지 않는다.

2026-08-27 fresh isolated qualification은 PostgreSQL Flyway V1–V22와 backend 119 suites / 854 tests /
0 failures / 0 errors / 1 skipped, Spotless/SpotBugs, frontend lint와 44 files / 401 tests,
TypeScript/PWA production build, production/personal Compose render와 Windows source contract를
통과했다. Fixed local Node 검증은 OpenAPI YAML, 384 internal refs, 2 external JSON Schema refs,
45 unique operation IDs와 capability-origin positive/negative matrix를 통과했다. Private spec을
third-party image에 mount하는 실행은 환경 보안 정책이 거절해 Redocly Docker lint는 이번 slice에서
실행하지 않았다. Exact 6D.1 temporary container/volume/image는 모두 제거했다.

후속 owner-authorized personal 배포는 session table data를 제외한 checksummed backup
`personal-memo-20260827-033109271Z.dump`과 SHA-256, 이전 backend/frontend rollback tag
`pre-6d1-20260827-033050Z`를 먼저 보존했다. Docker Desktop 4.84.0이 Windows build 26200의 stale
AF_UNIX runtime socket 오류로 시작되지 않아 factory reset 없이 `Docker\run`과
`docker-secrets-engine` runtime directory를 `.stale-*` 이름으로 격리하고 Windows를 재부팅했다.
Docker image/volume/configuration은 삭제하지 않았고 `.stale-*` directory 3개는 재부팅 후에도 Windows가
손상 reparse point 제거를 거절해 보존돼 있다.

지원 `Start-PersonalMemo.ps1`로 기존 PostgreSQL volume을 유지한 채 backend/frontend를 rebuild했고,
배포 image는 각각
`sha256:3d1295b84aa41ef27e0adf0120775b3b0c021f6eae9bcde94658393d317772d4`와
`sha256:4593d22806a46ab69ceae5630196c74cc59fb356b5d815535c4fcc2090830f52`다. Current Compose overlay와
실행 container의 publication environment entry는 0이므로 application default `false`/blank가 적용된
`LOCAL_ONLY`다. 세 service health, trusted private HTTPS, Flyway V22/failed 0, PWA 200,
unauthenticated capability 401 `AUTHENTICATION_REQUIRED` + `no-store`, synthetic unknown-token private
GET/HEAD와 query-free token log 검증이 통과했다. 개인 session을 사용하지 않아 authenticated
capability 200 body는 runtime smoke하지 않았고 실제 feed/event/canonical row도 만들지 않았다.

새 secret/listener/service/volume/database migration/row, DNS, certificate 또는 public route는 만들지
않았다. 따라서 6D.1은 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY`다. 당시에는 public edge/DNS/TLS/operator,
rate/connection/execution bound, owned/external success/error log sentinel, internet exposure와
Google/Apple smoke가 모두 미구현 `NOT_AUTHORIZED`였다. 아래 후속 preflight는 loopback origin-side
source/test만 추가하며 public activation과 external proof를 열지 않는다.

### V23 explicit per-feed public consent source and private deployment checkpoint

ADR 0015와 V23 source는 deployment capability만으로 기존 V22 bearer가 외부 공개되는 경로를 닫는다.
모든 기존/신규 feed는 `publication_scope=LOCAL_ONLY`, null policy/time으로 시작한다. `PUBLIC_HTTPS`
deployment에서 실제 feed read를 허용하려면 authenticated owner가 exact current policy와 fresh 32-byte
client secret으로 별도 external-publication enable mutation을 완료해야 한다. Verifier 교체, public
scope, policy/time, rotation/update time과 version 증가는 하나의 transaction이다. 기존 local/public URL은
즉시 무효화되고 secret/URL은 response나 idempotency JSON에 남지 않는다.

LOCAL_ONLY deployment는 local-scope feed만, PUBLIC_HTTPS deployment는 current-policy public-scope feed만
제공한다. Scope/policy가 맞지 않거나 policy가 바뀌면 기존과 같은 empty no-store 404다. 공개 feed의
disclosure mode는 새 동의 없이 바꿀 수 없고, revoke는 feed를 영구 폐기하면서 public scope/pin도
지운다. Membership add/remove는 계속 owner가 명시적으로 수행하며 future event 자동 공유가 아니다.

이 source qualification 시점에는 개인 V22 database에 V23을 적용하지 않았고 personal feed/session/
schedule/canonical data를 읽거나 변경하지 않았다. 후속 owner-authorized rollout은 checksummed backup,
disposable V22-to-V23 restore rehearsal, exact personal volume migration/rebuild, Flyway V23/failed 0,
V23 columns/validated constraints, bounded aggregate 불변, publication environment 0, private
health/TLS/PWA smoke를
확인했다. 현재 개인 database는 V23/`LOCAL_ONLY`다. `.env.public-feed`에는 activation 승인이 생긴
뒤에만 exact origin과 `APP_CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION=calendar-feed-public-v1`을 함께 넣는다.
Provider/customer log 처리와 receipt-level replica proof가 해결되지 않았으므로 connector start,
PUBLIC_HTTPS activation, 실제 feed와 Google/Apple smoke는 계속 `NO_GO`/`NOT_AUTHORIZED`다.

### 2026-08-30 analysis-path diagnostic personal deployment checkpoint

Owner-authorized rollout은 현재 V23/`LOCAL_ONLY` stack의 frontend/backend writer만 정지한 뒤 session
table data를 제외한 checksummed backup
`personal-memo-20260830-021312804Z.dump`(144430 bytes, SHA-256
`33F5F606C104D885F47BCA806DB7D62EA1B6F154762751D248B8EAA598E6D726`)을 만들었다. Exact backup은
disposable project `personal-memo-restore-20260830021355-cb571cb4`에서 V23→V23 restore, current backend
health와 Flyway V23을 통과했고 성공 뒤 container/network/volume/image를 제거했다.

직전 frontend/backend image는 `rollback-pre-analysis-path-20260830-021222Z` tag로 보존했다. Base,
production, personal Compose만 재빌드한 새 frontend/backend image는
`sha256:510e19f697c63c2dae0c1b23b59f06fa18e2775048ece7fb0ddb704d95e33cdc` /
`sha256:26b0219a53e85d09e9f079f58a3ea3420d96fc9761db1bf15d81344d11eb1745`이며
`deployed-analysis-path-20260830-0216Z` tag를 갖는다. Postflight는 세 service/trusted TLS, PWA
shell/manifest/service worker 200, Flyway V23/failed 0, 기존
`personal-memo-private-win_personal-memo-postgres` volume, 새 controller와 frontend endpoint/UI label
artifact, 비인증 summary 401/no-store를 확인했다. 401은 `XSRF-TOKEN`만 설정하고 session cookie는 만들지
않았다. Private calendar-feed synthetic unknown-token GET/HEAD도 empty 404/no-store였고 owned log에
query/token이 없었다.

Live smoke는 실제 owner session으로 memo/feed/event/canonical API를 호출하거나 row 내용을 열람하지
않았고 Apply, public activation overlay, calendar mutation을 실행하지 않았다. Cloudflare와 Ollama
process/listener는 0이었다.
따라서 이 checkpoint는 배포 artifact와 비인증/정적 경계만 증명한다. 모델 호출·정확도·latency,
GPU/VRAM, Fake/LiquidAI 비교는 `NOT_RUN_NO_CLAIM`; training/fine-tuning/LoRA는 `NO_GO`, RAG는 미사용이며
상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`다.

Windows build 26200의 Docker Desktop AF_UNIX startup bug를 우회하기 위해 활성 runtime 밖으로 격리한
`run.stale-20260829-203447`, `run.prestart-20260830-110956`,
`docker-secrets-engine.stale-20260829-203815`에는 0-byte socket reparse point만 있다. Docker가 active인
상태에서 Windows가 child 삭제를 거절했으므로 broad delete/factory reset 대신 그대로 격리했다. 이
directory는 개인 image, PostgreSQL volume, backup과 무관하며 다음 host maintenance window에서 Docker를
정상 종료하고 exact path만 재검증한 뒤 정리한다.

### 6D public-edge preflight: loopback first, activation last

`6D public-edge preflight`는 `6D.2`가 아니며 실제 internet publication도 아니다. Source는 다음
세 경계를 서로 분리한다.

- `calendar-edge/`는 exact bodyless
  `GET|HEAD /calendar/v1/feed.ics?token=<canonical-43-character-secret>`만 backend로 전달한다. 다른
  path/method, encoded path, extra/duplicate query, body와 upstream error는 generic empty 404로
  축소하고 rate rejection은 bodyless 429로 유지한다. Log에는 raw method/request target/query가
  아니라 fixed safe route/method class만 남기고 caller Authorization/Cookie/Referer/forwarded
  header는 proxy하지 않는다.
- `compose.public-feed.yaml`은 backend와 edge를 private internal network에 연결하고 edge port를
  `127.0.0.1:${PERSONAL_MEMO_CALENDAR_EDGE_PORT:-8787}`에만 bind한다. 이 overlay는 publication
  property를 설정하지 않으므로 backend는 계속 exact `LOCAL_ONLY`다. Main frontend/PWA/API,
  Spring Boot host port와 PostgreSQL은 이 edge에 공개되지 않는다.
- `compose.public-feed-activation.yaml`은 검토된 public origin을 backend에 공급하는 별도 최종
  overlay다. `.env.public-feed.example`은 placeholder이므로 복사한 ignored `.env.public-feed`에
  실제 검토 origin을 넣기 전에는 deployment authority가 아니다. Activation overlay 자체도 DNS,
  certificate, TLS termination, tunnel, firewall rule 또는 public listener를 만들지 않는다.

Preflight Compose file 순서는 다음처럼 고정한다. 마지막 activation file은 1단계에 포함하지 않는다.

```text
compose.yaml
  -> compose.prod.yaml
  -> compose.personal.yaml
  -> compose.public-feed.yaml
```

Public activation을 별도로 승인받은 2단계에서만 동일 순서 뒤에 아래 파일을 **마지막으로** 붙이고,
ignored `.env.public-feed`를 함께 사용한다.

```text
  -> compose.public-feed-activation.yaml
```

#### 1단계: source/loopback preflight

1. `scripts/public/Test-PersonalMemoPublicFeedEdge.ps1`를 사용해
   `compose.public-feed.test.yaml`의 disposable Nginx upstream과 생성한 synthetic bearer만 검증한다.
   이 harness는 product PostgreSQL/session/memo/feed/event/canonical schedule/API Apply를 사용하지
   않는다. 기록된 isolated 실행은 exact GET/HEAD, deny surface, header stripping, generic empty 404,
   bodyless rate 429, provisional bounds와 query/path/header/custom-method bearer sentinel의 owned
   edge/upstream log 0건을 통과했다. 이는 external operator log 증명이 아니다.
2. Compose render에서 edge host bind가 정확히 `127.0.0.1`이고, main frontend/backend/PostgreSQL의
   publication이 넓어지지 않았으며 activation overlay가 빠져 있는지 확인한다.
3. Preflight overlay를 적용한 뒤 host loopback 밖에서는 edge가 직접 도달되지 않고 backend
   capability가 계속 exact `LOCAL_ONLY`인지 확인한다. 이 단계에서는 hostname, DNS, TLS operator,
   external route 또는 Google/Apple client를 사용하지 않는다.

Origin edge의 provisional containment 값은 60 requests/minute, burst 20, concurrent connection 8,
upstream connect/send/read timeout 2s/5s/10s다. Tunnel 또는 reverse proxy 뒤에서는 모든 외부 client가
이 hop에서 같은 immediate peer로 보일 수 있으므로 global origin-side bound다. 이 값은 external
per-client quota, DNS/TLS/tunnel 시간을 포함한 total external deadline, end-to-end latency budget 또는
SLA가 아니다. 선택한 Cloudflare Tunnel/WAF는 외부 rate를 보조할 수 있지만 total deadline이나 response
size hard cap을 제공하지 않는다. Customer-visible success/error log는 path-only field allow-list로
제한하고 query bearer sentinel이 없음을 별도로 증명해야 한다.

#### 2단계: Cloudflare named Tunnel 준비와 별도 activation

Public operator 종류는 **Cloudflare remotely-managed named Tunnel**로 선택했다. 공식 Windows binary는
`DOWNLOADED_VERIFIED`다: version `2026.8.2`, SHA-256
`c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5`, Authenticode
`Valid`, signer `Cloudflare, Inc.`. Cloudflare login과 owner-controlled active zone을 확인했다. Remote
named Tunnel, single-label published application/DNS와 exact-path loopback route는 configured 상태다.
Hardened `PersonalMemoCalendarCloudflareTunnel` service는 protected token-file-only ImagePath에 inline
token이 없음을 포함해 `Stopped`/`Manual`/`LocalSystem`으로 설치·검증했다. 일반 기본 `Cloudflared`
service는 제거했고 현재 `cloudflared` process와 port `8787`/`49312` listener는 모두 0이다. Connector stop
뒤 Cloudflare status는 `Down`이다. 이후 bounded external synthetic qualification을 수행하고 동일한
stopped 상태로 rollback했지만 실제 activation은 계속 `NO_GO`다.

Source에는 Windows PowerShell 5.1용 운영 contract가 구현돼 있다.

- `Install-PersonalMemoCloudflareTunnel.ps1`는 reviewed binary와 hidden credential input에서 protected
  installation root, token file과 manifest를 만들고 LocalSystem **Manual/stopped** service만 등록한다.
  Hidden input은 raw Tunnel token 또는 exact Windows
  `cloudflared.exe service install <token>` command 한 줄만 받아 token을 추출한다. Pasted command를
  실행하지 않으며 malformed/multiline input은 거절한다. Installer는 tunnel, DNS, route나 activation을
  만들거나 connector를 시작하지 않는다.
- `Start-PersonalMemoCloudflareConnector.ps1`는 manifest, pinned hash, actual version, 현재
  Authenticode `Valid` Cloudflare signer, ACL ownership/inheritance/rule 수, reparse 부재, service
  `ObjectName`/ImagePath/start mode/status를 다시 검증한다. `--loglevel warn`,
  `--transport-loglevel warn`, `--grace-period 30s`, loopback-only `127.0.0.1:49312` metrics를 고정하고
  origin `127.0.0.1:8787` 및 local tunnel diagnostic을 확인한다. 시작 실패 시 자동 stop하고 diagnostic/
  stop 실패를 함께 보고한다.
- `Stop-PersonalMemoCloudflareConnector.ps1`는 exact service만 stop한 뒤 성공 feed response 부재를 별도
  외부 probe로 확인하도록 요구한다. Personal `Start-PersonalMemo.ps1`/`Stop-PersonalMemo.ps1`는 public
  Cloudflare topology가 active이면 fail closed해 private lifecycle과 섞지 않는다.
- `Test-PersonalMemoCloudflareSourceContracts.ps1`의 Windows PowerShell 5.1 parse/source/ordering contract는
  PASS다. 이는 service install이나 external network proof가 아니다.

- `VERIFIED`: Cloudflare login and owner-controlled active DNS zone
- `CONFIGURED_DOWN`: remote named Tunnel, stable single-label published application/DNS and exact-path
  loopback route are configured; Cloudflare reports `Down` after connector stop
- `VERIFIED_STOPPED`: hardened `PersonalMemoCalendarCloudflareTunnel` is installed as
  `Stopped`/`Manual`/`LocalSystem`; its ImagePath uses only the protected token file and contains no inline token
- `REMOVED`: generic default `Cloudflared` service
- `ZERO`: current `cloudflared` process count and port `8787`/`49312` listener counts

Multi-level subdomain은 별도 Advanced Certificate가 필요할 수 있으므로 이 checkpoint에서는 쓰지 않는다.
Temporary `*.trycloudflare.com` **Quick Tunnel은 금지**한다. Cloudflare 문서도 Quick Tunnel을 test/development
용도로 분류하고 production에는 remotely-managed tunnel을 사용하도록 안내한다. Named Tunnel의 public
hostname은 오직 loopback `calendar-feed-edge`로 향하고 PWA/API/OAuth/direct backend/PostgreSQL은 route하지
않는다. Tunnel ingress의 unmatched route는 fail-closed여야 하며 origin edge의 exact raw-target 검사가
최종 path/method allow-list다.

공식 참고:

- [Remotely-managed tunnel 생성](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/)
- [Public hostname과 Cloudflare DNS prerequisite](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel-api/)
- [Quick Tunnel 제한](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)

##### Cloudflare query/log 처리 경계

Bearer는 query parameter이므로 client와 origin 사이에서 Cloudflare의 처리 경계에 들어간다. Origin/edge
log에서 token을 제거해도 Cloudflare가 request를 처리하지 않는다고 주장할 수 없다. 특히 Cloudflare
HTTP request dataset의 `ClientRequestURI`는 full path와 query string을 포함한다. 따라서 customer-owned
Logpush/analytics/request-log export가 가능할 때는 기본 전체 field가 아니라 다음 최소 allow-list만
구성하고, sentinel이 export·dashboard·tail·support artifact에 없는지 외부 합성 probe로 확인한다.

- 허용 예: timestamp, `ClientRequestHost`, `ClientRequestMethod`, query 없는
  `ClientRequestPath`, `EdgeResponseStatus`, response bytes, cache status, `RayID`
- 금지: `ClientRequestURI`, request/response header collections, cookies, referer, raw/full URI/query와
  request body를 포함하거나 재구성할 수 있는 field
- `cloudflared`는 `info`/`warn`/`error` 범위로 운영하고 **debug logging을 금지**한다. Cloudflare 공식
  문서상 debug는 request URL과 request/response headers를 기록할 수 있다.
- Cloudflare account가 제공하지 않는 log product/field-level configuration은 “token-free”로 추정하지
  않는다. 확인할 수 없는 provider-internal retention은 별도 privacy/contract risk로 남긴다.

공식 field 정의와 connector logging 참고:

- [`ClientRequestPath`와 `ClientRequestURI`](https://developers.cloudflare.com/logs/logpush/logpush-job/datasets/zone/http_requests/)
- [`cloudflared` log level](https://developers.cloudflare.com/tunnel/advanced/run-parameters/#loglevel)

##### Cache, WAF와 hard-bound 한계

Exact `calendar.<zone>` + `/calendar/v1/feed.ics` Cache Rule은 **Bypass cache**여야 한다. Query별 cache key에
의존하거나 origin `no-store`만 신뢰하지 않는다. Disposable synthetic 200/404/429 external probe에서
`CF-Cache-Status`가 `HIT`가 아니고 stale cached body가 재사용되지 않는지 확인한다. Bypass rule은 plan과
rule 상태에 따라 `BYPASS` 또는 `DYNAMIC`으로 보일 수 있으므로 성공 조건은 exact `BYPASS`가 아니라
`!= HIT`다. [Cloudflare Cache Rule의 Bypass 동작](https://developers.cloudflare.com/cache/how-to/cache-rules/settings/#bypass-cache)을 기준으로 한다.

Cloudflare WAF rate rule은 exact hostname/path에 적용할 수 있지만 **보조 방어**다. Cloudflare는 rate
counter가 data center 단위일 수 있고 enforcement에 수 초 지연이 있어 초과 요청 일부가 origin에 도달할
수 있다고 명시한다. 따라서 WAF rate rule은 origin의 global 60r/m + burst20/connection8 제한을 대체하지
않고, exact 전역 quota나 origin 도달 요청 수를 증명하지 않는다.
[Cloudflare rate limiting 동작과 한계](https://developers.cloudflare.com/waf/rate-limiting-rules/)를 참조한다.

Cloudflare Tunnel만으로 total external 10s deadline 또는 independent 128-KiB hard response cap을 강제할
수 있다고 주장하지 않는다. Origin edge의 `proxy_read_timeout 10s`는 upstream read timeout이지 DNS/TLS/
Cloudflare/tunnel/client 전 구간의 total deadline이 아니다. Backend의 128-KiB calendar generation
fail-closed bound도 Cloudflare가 독립적으로 enforce하는 response-size cap이 아니다. 더 엄격한 external
total deadline/response-size gate가 필요하면 Worker 또는 별도 gateway 같은 새 component를 별도 설계·
승인·검증해야 하며 현재 checkpoint에 자동 추가하지 않는다.

##### Connector-stopped activation 순서

Connector start는 public route를 실제로 여는 권한 변화이므로 다음 순서를 바꾸지 않는다.

1. `cloudflared` connector/service를 **stopped**로 유지한 채 remotely-managed named Tunnel, exact
   `calendar.<zone>` DNS/public hostname, loopback service target, catch-all deny, cache bypass, WAF 보조
   rule과 customer log field allow-list를 준비한다. Activation overlay는 아직 적용하지 않는다.
2. Live Personal Memo가 아닌 **disposable synthetic origin**만 tunnel upstream으로 설정하고 connector를
   일시 시작한다. Generated synthetic bearer로 exact GET/HEAD, deny path/method/body, cache
   `CF-Cache-Status != HIT`, response header, latency 관측과 Cloudflare/customer/connector log sentinel 0건을
   외부 network에서 확인한다. 개인 PostgreSQL/session/memo/feed/event/canonical schedule/Apply는 사용하지
   않는다.
3. Connector를 다시 **stop**하고 tunnel을 통한 성공 feed response가 없음(non-2xx이며 Cloudflare edge
   error 허용)을 확인한다. Synthetic route/origin,
   generated bearer와 임시 log export를 범위 확인 후 제거한다. Tunnel/DNS object는 stopped 상태로 남길
   수 있지만 live origin을 가리키면 안 된다.
4. `compose.public-feed.yaml`로 live loopback edge를 시작하고 local exact GET/HEAD/deny/log smoke와 backend
   capability `LOCAL_ONLY`를 확인한다. 개인 schedule/feed/token은 smoke에 사용하지 않는다.
5. Reviewed ignored `.env.public-feed`에 exact `https://calendar.<zone>` origin을 넣고
   `compose.public-feed-activation.yaml`을 마지막 Compose overlay로 적용한다. Connector는 여전히 stopped다.
6. Authenticated capability가 exact `PUBLIC_HTTPS`/reviewed origin인지 확인한 뒤 connector upstream을 live
   loopback edge로 바꾸고 connector를 **마지막에 시작**한다. External live probe는 generated invalid/
   unknown token과 deny surface만 사용하며 개인 feed 또는 개인 schedule의 200 response를 qualification에
   사용하지 않는다.

Google/Apple subscription/update/removal과 실제 owner feed interoperability는 이 external containment
proof가 끝난 뒤 다시 별도 승인한다.

##### Disposable external qualification harness와 현재 증거

`Test-PersonalMemoCloudflareExternal.ps1`는 standalone
`compose.public-feed.cloudflare-test.yaml`을 사용해 다음 세 runtime 단계를 분리한다.

1. connector가 stopped인지 확인하고 disposable synthetic backend + edge만
   `127.0.0.1:8787`에 prepare한다.
2. 사용자가 Cloudflare route/connector를 synthetic origin에만 연결한 뒤 explicit external mode로
   transport/path/method/cache/header/latency와 owned log sentinel을 검사한다.
3. local connector와 모든 remote tunnel replica가 stopped임을 각각 확인한 뒤 exact Compose project,
   volumes/images/orphans를 cleanup한다.

Recorded external qualification은 strict non-secret receipt를 작성했다. 총 46 probe는 exact positive
3, origin deny 8, remote catch-all deny 5, bounded rate attempt 30으로 구성됐다. Cache 관측은
`BYPASS 0`/`DYNAMIC 46`/`HIT 0`, maximum observed latency는 `873.816 ms`였다. Owned-log와
external-artifact-reflection sentinel은 PASS였다. Rate 429는
`NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS`였으므로 Cloudflare 또는 origin rate enforcement PASS로 승격하지
않는다.

Current account plan에서는 Cloudflare provider/customer request-log sentinel을 조회·검증할 수 없었다.
따라서 그 항목은 unavailable/unverified이고 token-free provider-log claim을 하지 않는다. Receipt의
remote replica field도 `REQUIRED_NOT_VERIFIED`다. 별도 Cloudflare dashboard에서 connector rollback 뒤와
cleanup 뒤에 각각 active replicas `0`, routes `1`, status `Down`을 수동 관측했지만, 이 UI 관측은
receipt가 요구하는 machine-verifiable replica proof를 대체하지 않는다. Receipt status는
`TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`, decision은 `NO_GO`, product label은
`SOLO_PROVISIONAL`/`REPORT_ONLY`다.

Cleanup은 disposable containers, network와 local image를 제거했다. Hardened service는
`Stopped`/`Manual`, local `cloudflared` process는 0, port `8787`/`49312` listener는 0이며 existing
personal frontend/backend/PostgreSQL containers는 healthy를 유지했다. Personal memo, PostgreSQL data,
canonical schedule, Apply와 Ollama에는 접근하지 않았다. Persistent exact-host/path cache-bypass rule과
protected tunnel installation은 다음 승인된 단계의 prerequisite로 보존했다.

Harness 검증 중 두 오판 가능성을 수정했다. Curl HEAD는 `size_download`가 0이어야 한다는 계약으로
판정하고 output header block 자체를 body로 세지 않는다. 원격에서 생성 불가능한 custom-method marker는
HEAD marker로 교체했으며, 매 실행은 synthetic containers를 force-recreate해 그 시점 이후 log boundary만
검사한다.

#### Rollback

Rollback은 authority를 topology보다 먼저 닫는다.

1. `cloudflared` connector/service를 **먼저 stop**해 새 origin request를 차단하고 tunnel을 통한 성공
   feed response가 없음(non-2xx이며 Cloudflare edge error 허용)을 외부 network에서 확인한다. DNS/cache
   상태만 보고 차단을 추정하지 않는다.
2. `compose.public-feed-activation.yaml`을 제외한 file set으로 backend를 recreate하고 capability가
   exact `LOCAL_ONLY`인지 확인한다.
3. 필요하면 loopback `calendar-feed-edge`를 stop/remove한다. 그 다음 named Tunnel public hostname/DNS/
   route와 customer log export를 disable/remove할 수 있다.

이 preflight/activation은 Flyway migration이나 canonical row를 추가하지 않으므로 database restore나
schedule/feed data rollback을 수행하지 않는다. 상태는 계속 `SOLO_PROVISIONAL`/`REPORT_ONLY`다. Remote
Tunnel/route/DNS는 configured이고 hardened service는 verified stopped/manual/LocalSystem/token-file-only다.
Generic default service는 제거됐으며 current connector process와 port `8787`/`49312` listener는 0,
Cloudflare status는 `Down`이다. External synthetic transport/path/cache와 owned-log proof는 위 receipt에
기록됐지만 provider/customer log sentinel과 receipt-level replica proof는 남아 있다. 따라서 activation은
`NO_GO`이며 실제 public
activation과 Google·Apple smoke만 final gate 전
`NOT_AUTHORIZED`다.

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

이 절의 “모든 경로” 규칙은 향후 PWA와 인증 API 전체를 공개하는 일반 production 배포에만
적용한다. Milestone 6D feed-only edge에 재사용하면 안 된다. 6D는 별도 승인 아래 정확한
`GET|HEAD /calendar/v1/feed.ics`만 전달하고 다른 모든 경로·method를 거절하며, query bearer를 모든
success/error log에서 제거해야 한다. Preflight edge의 origin-side provisional bound와 별개로 외부
operator가 request/connection bound와 total external deadline을 결정하고 검증해야 한다. 6D public-edge
preflight source는 이 exact path를 host loopback에서만 검증하며 public activation이 아니다. 6D.1은
별도 public feed origin을 공급하는 authenticated exact-union API와 backend property/controller,
frontend strict decoder/warned UI를 구현해 personal image에 `LOCAL_ONLY`로 deploy했다. Public route가
없으므로, preflight edge나 public hostname만 준비됐다는 이유로 internet subscription URL을 제공하지
않는다.

- 공개 `https://` origin의 TLS 종료, 인증서 발급·갱신과 HTTP→HTTPS 전환을 담당한다.
- 공개 origin의 모든 경로를 frontend loopback port로 전달한다. frontend가 정적 PWA와 `/api`, `/oauth2`, `/login/oauth2`를 같은 origin에서 제공한다.
- 공개 `Host`를 보존하고 `X-Forwarded-Proto`와 `X-Forwarded-Port`를 외부 클라이언트 값과 무관하게 신뢰 가능한 edge 값으로 덮어쓴다.
- backend와 PostgreSQL을 인터넷에 직접 공개하지 않는다.
- cookie, CSRF, OAuth callback을 고려해 PWA와 API를 서로 다른 origin으로 분리하지 않는다. cross-origin 배포는 별도의 CORS·cookie·CSRF 보안 검토 없이는 지원하지 않는다.

Spring의 `prod` profile은 forwarded header 해석을 활성화하고 session cookie와 `XSRF-TOKEN` cookie를 `Secure`, `SameSite=Lax`로 발급한다. frontend Nginx는 정확한 `http`/`https` scheme과 1–5자리 forwarded port만 backend로 보존하고, 누락되거나 잘못된 값은 자신의 connection 값으로 대체한다. frontend port가 loopback에만 묶인다는 전제 아래 public header의 신뢰 주체는 외부 edge다. 이 설정은 HTTP만 공개해도 안전해진다는 뜻이 아니며 실제 사용자 트래픽은 반드시 HTTPS edge를 통과해야 한다.

frontend Nginx access log는 raw request target이나 정규화된 `$uri`를 기록하지 않고, method와 route를
고정된 분류로만 축약한다. query argument, Referer, client address도 제외하므로 opaque graph cursor나
memo 식별자가 이 로그에 남지 않는다. Exact lexical memo search는
`POST /api/v1/search/memos`의 JSON body로만 query를 전달하고 URL, browser storage, service-worker
cache에 저장하지 않으며 응답은 `Cache-Control: no-store`다. Nginx access log는 request body도
기록하지 않는다. 이 설정은 별도 외부 HTTPS edge나 Spring backend의 body/log 정책을 변경하지
않으므로 각 upstream도 search body, raw preview, cursor를 기록하지 않는지 독립적으로 검토해야 한다.

Exact BODY/TITLE matching은 query 수준에서 PostgreSQL `normalize(..., NFKC)`와
`lower(... COLLATE "und-x-icu")`를 사용한다. 현재 pinned `postgres:17.6-alpine`의 10,000-memo
runner는 `und-x-icu` 존재를 확인했지만, 외부 관리형 PostgreSQL이나 image를 바꾸면 배포 전에 아래
preflight가 `true`인지 확인해야 한다. 이 요구는 search 전용 migration이나 새 index를 만들지 않는다.

```sql
SELECT count(*) = 1
FROM pg_collation
WHERE collname = 'und-x-icu';
```

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

## Private-beta 완료 경계와 public-launch 제한

현재 production/personal overlay는 위 단일-owner private beta의 안전한 기본값과 배포 형태를
검증하기 위한 것이며 공개 self-service 또는 multi-user beta 완성을 의미하지 않는다.

- local 가입과 신규 Google user 자동 생성은 운영에서 강제로 비활성화된다. fresh private database의 일회성 운영 bootstrap은 있지만 public self-service onboarding, invitation, password recovery는 없다. Google 로그인을 켜도 기존 identity와 명시적으로 연결된 계정만 사용할 수 있다.
- 실제 Google credential round trip은 자동화된 테스트에서 검증하지 않았고, 운영자가 Google Console 설정과 callback을 별도로 확인해야 한다.
- 계정별 5회 실패·15분 잠금은 구현되어 있지만 IP·edge 단위 rate limiting과 분산 abuse 대응은 없다.
- email verification, password-reset delivery, MFA/passkey, 완전한 계정 삭제 자동화가 없다.
- 개인 LAN용 local CA/TLS와 수동 backup/restore guard는 있지만 publicly trusted TLS edge와 인증서 자동 갱신, secret manager, 중앙 로그·metrics·alert, 자동 backup schedule·retention·restore drill은 포함되지 않는다.
- compose는 현재 source에서 image를 build한다. immutable registry release, 서명·SBOM 정책, 무중단 배포, 다중 host 장애 조치는 별도 운영 체계가 필요하다.
- personal AI-preferred proposal path 밖의 local model, 모든 cloud AI, 완전한 offline sync, Web Push와
  reminder dispatcher는 의도적으로 보류되어 있다.
- V21 EVENT schedule/authenticated reads와 6B/6C/V22 source 및 V23 consent migration은 personal
  V23 stack에 배포됐지만,
  v3 producer/preselection, 개인 canonical 일정 smoke와 public feed edge는 별도 승인 전까지
  보류되어 있다.
- 6D.1 server-owned public-origin capability property/API/strict PWA도 personal V23 stack에
  publication environment 없는 `LOCAL_ONLY`로 배포됐다. 6D public-edge preflight source는 별도
  loopback-only edge/Compose/test boundary를 준비했다. Operator 종류는 Cloudflare remotely-managed
  named Tunnel로 선택했고 공식 `cloudflared.exe` version `2026.8.2`는 digest/signature로
  `DOWNLOADED_VERIFIED`다. Remote Tunnel/exact-path published route/DNS는 configured다. Hardened
  `PersonalMemoCalendarCloudflareTunnel` service는 `Stopped`/`Manual`/`LocalSystem`, protected
  token-file-only/no-inline-token으로 설치·검증했고 generic default `Cloudflared` service는 제거했다.
  Current connector process와 port `8787`/`49312` listener는 0이고 stop 뒤 Cloudflare status는 `Down`이다.
  Source Install/Start/Stop/external harness와 local prepare cleanup은 검증됐고, disposable origin을
  사용한 bounded external synthetic transport/path/cache 및 owned-log receipt도 기록됐다. 다만 real
  activation, provider/customer log sentinel, receipt-level replica proof와 real-feed proof는 없다.
  남은 staged proof 전 activation은 `NO_GO`이고
  actual activation과 Google/Apple client smoke는 final-gate `NOT_AUTHORIZED`다. Quick Tunnel은 허용되지
  않는다.
- Owner-scoped raw-free analysis-path summary API와 lazy “분석 경로 진단” UI는 2026-08-30
  owner-authorized backup/restore/rebuild 뒤 personal V23 artifact에 배포됐다. Live acceptance는
  비인증 401/no-store와 정적 artifact까지만 확인했으며 authenticated owner aggregate와 device UI는
  사용자가 업데이트 적용 뒤 확인한다. 이 집계는 모델 호출 횟수나 정확도 증거가 아니다.

따라서 현재 형태는 단일 operator-provisioned owner, 신뢰 RFC1918 LAN, local CA, public
port-forwarding 없음, registration/Google 비활성, backend/PostgreSQL 비공개, 수동 backup/restore,
Fake-validated + pinned localhost `AI_PREFERRED` proposal path라는 좁은 private beta에만 사용한다.
모든 result는 manual Apply 전까지 untrusted proposal이고 상태는
`SOLO_PROVISIONAL`/`REPORT_ONLY`다. 알람/reminder delivery는 포함하지 않는다. 공개 또는 multi-user 범위로 넓히기
전에는 위 항목과 개인정보 처리·보존·삭제 정책, edge 신뢰 경계, 복구 목표를 별도 검토하고
검증해야 한다.

## Access-gated owner-only remote app preflight

전체 PWA/API는 calendar Tunnel을 넓히지 않고 별도 `app-public-edge`와
`PersonalMemoAppCloudflareTunnel`을 사용한다. Edge host publish는 `127.0.0.1:8788` 하나뿐이고,
connector metrics는 `127.0.0.1:49313`, service startup은 `Manual`/초기 `Stopped`다. 앱 edge는
frontend와 `app-publication` internal network를 공유하고, Windows Docker Desktop의 host loopback
publish를 위해 non-internal `app-loopback` bridge에 혼자 연결된다. 이 bridge의 outbound 가능성은
residual risk다. frontend/backend/PostgreSQL은 참여하지 않으며, edge의 unprivileged/read-only,
`cap_drop: ALL`, `no-new-privileges`, fixed frontend upstream으로 범위를 제한한다. PostgreSQL/backend
host port는 추가하지 않는다.

외부 요청의 Cookie header를 그대로 내부에 전달하지 않는다. 앱 edge는 첫 번째 exact bounded
`SESSION`과 `XSRF-TOKEN`만 새 Cookie header로 재구성하고 `CF_Authorization` 및 그 밖의 cookie는
제거한다. Protect with Access가 Access JWT를 connector에서 검증하더라도 그 JWT나 Access identity를
Spring owner 권한으로 재사용하지 않는다.

이 Windows host의 execution policy는 `Restricted`이므로 repository root에서 모든 app-publication
script를 Windows PowerShell 5.1의 process-scoped `Bypass`로 실행한다. Global/current-user execution
policy는 바꾸지 않는다. 다음 local qualification은 **관리자 권한이 아닌** 평상시 PowerShell에서
실행한다. 첫 명령은 source/parser contract이고 두 번째 명령은 disposable synthetic Docker smoke이며,
둘 다 개인 stack이나 Windows service를 사용하지 않는다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Test-PersonalMemoPublicAppSourceContracts.ps1'
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Test-PersonalMemoPublicAppEdge.ps1'
```

Tunnel service 설치 및 connector start/stop은 **별도로 “관리자 권한으로 실행”한 Windows PowerShell
5.1 창**에서만 수행한다. 비관리자 parent shell에서 `powershell.exe`를 다시 실행해도 elevation되지
않으며, `-ExecutionPolicy Bypass`도 관리자 권한을 부여하지 않는다. 현재 검토된 calendar connector의
서명·version·SHA-256이 그대로 유효한 binary를 app service로 복사하는 exact 예시는 다음과 같다.
설치는 hidden prompt에서 app Tunnel token을 받고 confirmation prompt를 유지한다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Install-PersonalMemoAppCloudflareTunnel.ps1' -CloudflaredExe 'C:\ProgramData\PersonalMemo\Cloudflare\bin\cloudflared.exe' -ExpectedSha256 'c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5'
```

Connector가 `Stopped`인 상태에서 live backup/rehearsal 승인까지 끝난 뒤 loopback app edge는 Docker
Desktop 접근 권한이 있는 **비관리자** Windows PowerShell 5.1에서 시작한다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Start-PersonalMemoPublicAppEdge.ps1' -PublicAppHostname 'memo.example.com'
```

그 뒤에만 elevated Windows PowerShell 5.1에서 모든 reviewed gate를 명시해 connector를 마지막에
시작한다. 이 switch들은 Cloudflare Dashboard/API 상태를 자동 조회하는 증거가 아니라 사용자가 별도로
검증한 사실에 대한 확인이다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Start-PersonalMemoAppCloudflareConnector.ps1' -PublicAppHostname 'memo.example.com' -AccessExactOwnerVerified -AccessDefaultDenyVerified -ProtectWithAccessVerified -CacheBypassRuleVerified -RemoteRouteVerified -RemoteCatchAllVerified -PrivacyBoundaryAccepted
```

배포 순서는 다음과 같다.

1. source contract, Compose render, Nginx config와 disposable synthetic edge smoke를 실행한다.
2. Cloudflare에서 exact app hostname의 Access application을 먼저 만들고, default deny + owner exact
   email만 확인한다. `Everyone`, domain allow, Bypass, Service Auth는 사용하지 않는다.
3. personal data가 아닌 disposable synthetic origin으로 exact host route, catch-all 404, Protect with
   Access, entire-host cache bypass, 비인가 차단, owned-log sentinel을 확인하고 connector를 다시 끈다.
4. live 전 backup/checksum/restore rehearsal과 image rollback tag를 준비한다. Connector가 정지된 상태로
   frontend를 안전 로그 설정과 internal edge network에 재생성하고 loopback edge를 검증한다.
5. 사용자가 exact hostname/Access identity/IdP와 Cloudflare TLS 처리 경계를 명시 승인한 뒤 connector를
   마지막에 시작한다. 자동 live smoke는 app 로그인 전 shell/capability/401/no-store까지만 수행한다.

Edge start script는 기존 healthy frontend의 image ID와 network set을 먼저 snapshot하고 새 frontend와
edge image를 prebuild/Nginx-validate한 뒤에만 frontend를 재생성한다. 이후 edge나 exact/wrong Host local
probe가 실패하면 이전 image/topology를 복원하고 이번 시도에서 생긴 빈 publication network를 제거한다.
Connector start는 단순 8788 listener가 아니라 exact Compose project/service label, healthy container,
hostname, `127.0.0.1:8788` binding, 두 publication network와 local security-header probe를 확인한다.
이 source guard는 실제 personal rollback rehearsal 증거를 대신하지 않는다.

Rollback은 elevated Windows PowerShell 5.1에서 app connector를 먼저 중지한다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Stop-PersonalMemoAppCloudflareConnector.ps1'
```

이 stop script는 local Windows service/process 중지만 증명하며 Cloudflare 외부 origin의 non-success를
자동 증명하지 않는다. Access 인증 cookie를 정확히 처리하지 않는 새 remote probe는 Access login/block을
origin-down으로 오인해 false assurance를 만들 수 있으므로 제공하지 않는다. App edge는 계속 켜 둔 채,
exact owner로 이미 Access 인증된 browser의 DevTools Network에서 cache와 service-worker interception을
bypass하고 `https://memo.example.com/api/v1/auth/capabilities`를 새로 요청한다. 응답이 non-2xx Cloudflare
tunnel/origin-unavailable이고 app JSON이나 cached PWA shell이 아님을 기록한다. 비인증/incognito의 Access
login 화면, local service `Stopped`, Dashboard의 `Down` 표시는 이 증명을 대신하지 않는다.

이 수동 외부 non-success 증거가 없으면 rollback은 `MUTATION_HOLD`이며 edge 또는 Cloudflare
Access/DNS/Tunnel을 변경·삭제하지 않는다. 증거를 확보한 뒤에만 Docker Desktop 접근 권한이 있는
비관리자 Windows PowerShell 5.1에서 edge를 내린다.

```powershell
& 'C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -ExecutionPolicy Bypass -File '.\scripts\public\Stop-PersonalMemoPublicAppEdge.ps1' -PublicAppHostname 'memo.example.com'
```

Personal PostgreSQL volume과 calendar service/route/token은 그대로 둔다. Cloudflare Access/DNS/Tunnel
삭제는 별도 provider mutation 확인이 필요하다. 이 단계의 표시는 `SOLO_PROVISIONAL/REPORT_ONLY`이며
public self-service GO가 아니다.

### 2026-08-30 owner-only live qualification record

Owner가 exact hostname, exact-owner email OTP, default-deny/Protect with Access, entire-host cache bypass,
Cloudflare TLS 처리 경계와 connector-first rollback을 승인한 뒤 app connector를 마지막에 시작했다.
Windows service는 protected token-file-only, `Running`/`Manual`이고 metrics `127.0.0.1:49313`, local
`/ready=200`, `/diag/tunnel=200`, connected 4개를 확인했다. Dashboard는 exact Tunnel을 `Healthy`, active
replica 1, route 1로 표시했다. Token, service command line, 개인 memo/session/PostgreSQL/canonical/API/Apply는
검사하거나 기록하지 않았다.

Cookie 없는 remote GET은 `302`로 Cloudflare Access hostname에 redirect됐으며 `CF-Cache-Status`는
없었다. 이미 Access 인증된 browser에서는 app login shell과 비식별 auth-capabilities만 확인했다.
Rollback rehearsal은 app edge를 유지한 채 connector를 먼저 `Stopped`/`Manual`로 수렴시킨 후, 같은
Access 인증 browser에서 unique query로 service-worker/cache를 우회한 fresh capabilities request가 app
JSON이나 cached PWA가 아닌 Cloudflare Tunnel `Error 1033`을 반환함을 확인했다. 같은 reviewed gate로
connector를 재시작한 뒤 capabilities, Dashboard `Healthy`, active replica 1, connected 4개와 미인증 Access
redirect가 모두 복구됐다. 최종 connector 상태는 `Running`/`Manual`이다.

실제 owner application login/PWA screen과 application-session `/auth/me` 401/no-store는 수행하지 않았다.
Provider/customer request-log export도 unavailable/unverified라 log sentinel을 주장하지 않는다. 판정은
`LIVE_OWNER_BETA`, `SOLO_PROVISIONAL/REPORT_ONLY`; unrestricted public self-service와 production은
`NO_GO`다.

### 2026-09-01 Access control-path service-worker hold

Cloudflare Dashboard를 읽기 전용으로 다시 확인한 결과 exact owner-email `Allow` 한 개와 default deny,
연결된 Bypass/Service Auth 0개, exact host/all-path protected Tunnel route, catch-all `http_status:404`,
Protect with Access JWT validation, exact-host entire-cache bypass가 유지되고 있었다. 설정은 변경하지
않았다. App connector를 reviewed gate로 시작했을 때 Tunnel은 `Healthy`/connected가 됐고 cookie 없는
외부 요청도 Access redirect/no-store를 유지했다.

그러나 현재 in-app browser의 Access flow가 `/cdn-cgi/access/authorized?...`에 도달했을 때 기존 설치
PWA service worker가 Cloudflare network callback 대신 cached Personal Memo shell/offline UI를 반환했다.
따라서 이번 owner/auth acceptance는 실패로 판정하고 connector-first rollback을 실행했다. App과
calendar connector service는 모두 `Stopped`/`Manual`, local `cloudflared` process는 0이며
`app-public-edge`는 `127.0.0.1:8788` loopback에서 healthy 상태로 보존됐다. 개인 memo, owner session,
PostgreSQL, canonical data와 Apply는 읽거나 변경하지 않았다.

Source fix는 shared navigation-fallback deny-list와 `NetworkOnly` route에 case-insensitive
`^/cdn-cgi/access(?:/|\?|$)` 경계를 추가한다. ESLint, TypeScript, frontend 48 files/472 tests, public-app
source contract, production Vite/PWA build와 generated `sw.js` inspection이 통과했고, 별도 disposable
PostgreSQL/앱 stack의 system-Edge focused production PWA E2E 1/1도 callback/authorized navigation이
offline shell로 resolve되지 않음을 확인했다. 첫 두 local 시도는 각각 잘못된 test selector와 system
Edge의 `in-incognito` installability diagnostic에서 본 assertion 전에 멈춘 harness-only 실패였고,
그 exact disposable container/network/volume/image도 모두 제거했다.

Fresh private browser의 `/api/v1/health` OTP flow 뒤 cached shell이 아닌 Cloudflare Tunnel `Error 1033`이
사용자 화면으로 확인돼 rollback `MUTATION_HOLD`를 해제했다. 이전 frontend와 app-edge image에는
`rollback-pre-access-sw-20260901-042111Z` tag를 붙였다. Connector가 stopped인 상태에서 edge stop/start
script로 current source를 다시 build했고 lint, 48 files/472 tests, production PWA build, isolated Nginx
config, exact/wrong-Host local edge contract가 통과했다. 새 image에는
`deployed-access-sw-20260901-042405Z` tag를 붙였다.

배포 뒤 loopback root/`sw.js`는 200이고 generated worker의 Access boundary marker가 존재했다. Health는
exact UP 200/no-store, auth capabilities는 exact 3 boolean 200/no-store, cookie 없는 `/auth/me`는 401
`AUTHENTICATION_REQUIRED`/no-store였다. App connector를 마지막에 시작한 뒤 service는
`Running`/`Manual`, calendar service는 `Stopped`/`Manual`, cloudflared process 1, metrics ready 200이고
모든 personal container가 healthy였다. Body/cookie 없는 외부 `/`와 unique-query health는 Access 302,
no-store/private, `CF-Cache-Status` absent였다.

기존 PWA에서 broad `Clear site data`를 사용하면 owner draft/session이 손실될 수 있으므로 금지한다.
미저장/pending-operation guard를 존중하고 명시적 “새 화면 적용” 절차로 새 worker가 controller가 된 것을
확인한다. 이어서 exact owner는 안내한 fresh private-browser health, root, application/PWA 재확인이
정상 작동한다고 보고했다. 이는 user-reported acceptance이며 provider/customer log나 독립 자동화
증거로 확장하지 않는다. 과거 `LIVE_OWNER_BETA` evidence는 보존하고 current activation은
`LIVE_OWNER_BETA_REQUALIFIED`다. Overall status는 `SOLO_PROVISIONAL/REPORT_ONLY`, public production은
`NO_GO`다.

### 2026-09-01 Milestone 7.2 graph-first frontend update

Milestone 7.2 source는 confirmed bounded graph를 default signed-in view로 바꾸고 연결/메모/일정/설정
bottom navigation과 concise raw-save-first capture를 추가한다. 이 변경은 frontend hierarchy와
presentation에 한정되며 API, JSON Schema, Flyway, canonical contract, analyzer policy, backend image,
PostgreSQL schema와 public edge topology를 바꾸지 않는다.

Source qualification은 ESLint, TypeScript, 51 files/481 tests, production PWA build, public-app source
contract와 disposable production-like Playwright 27/27을 통과했다. Owner-authorized deployment 전에
144,430-byte mechanical PostgreSQL backup/checksum과 disposable V23-to-V23 restore rehearsal을
완료했고 existing canonical volume은 유지했다. App connector를 먼저 중지한 뒤 fresh private-browser
Error 1033을 rollback 증거로 확인했고 기존 실행 이미지는 같은 UTC stamp의
`rollback-pre-m72-20260901-074019Z` tags로 보존했다.

Reviewed edge stop/start script는 current source에서 frontend와 app-public-edge만 rebuild/recreate했다.
Backend와 PostgreSQL은 재생성하지 않았다. Exact-Host root/`sw.js`, health, finite capabilities,
unauthenticated 401과 wrong-Host empty 404가 local no-store 경계를 통과했다. 실행 이미지는
`deployed-m72-20260901-075555Z` tags로 고정했다. App connector를 마지막에 시작한 뒤 app service는
`Running`/`Manual`, calendar service는 `Stopped`/`Manual`, 모든 personal container는 healthy였다.
Cookie/body 없는 external root와 health는 Access 302, no-store/private와 cache non-HIT를 통과했다.

최종 audit은 backup checksum match, canonical PostgreSQL volume mount 1, temporary restore/E2E
container와 volume 0, deployment/rollback tag 4를 확인했다. Personal memo, owner session, canonical
record, Apply, Ollama와 Cloudflare 설정을 읽거나 변경하지 않았다. Owner visual review는 진행 중이며
physical-device acceptance는 열려 있다. 판정은 `SOLO_PROVISIONAL/REPORT_ONLY`; unrestricted
public/production은 `NO_GO`다.
