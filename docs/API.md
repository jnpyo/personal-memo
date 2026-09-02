# API contract — authenticated deterministic-analysis MVP

Base path: `/api/v1`

이 문서는 local/Google 이중 로그인, AI-free vertical slice, 외부 모델 없는 결정론적 분석,
Milestone 5의 첫 exact lexical memo search slice, Milestone 6A.1 EVENT schedule,
6A.2a dark-compatible proposal-v3 EVENT binding contract, Milestone 6B의 인증된 iCalendar snapshot,
Milestone 6C recipient feed, 그리고 implemented/private-`LOCAL_ONLY`-deployed Milestone 6D.1
public-origin capability를 설명한다. `6D public-edge preflight`는 기존 root feed API 앞의
loopback-only deployment boundary이며 새 application endpoint를 추가하지 않는다.
후속 fuzzy/semantic search, reminder, sync, proposal-v3 producer/preselection과 실제 public
subscription activation/interoperability는 구현·실행 전이므로 포함하지 않는다.

기계 판독 가능한 명세는 [`openapi.yaml`](openapi.yaml)에 있다. 일정/공유 기능 상태는
`SOLO_PROVISIONAL`/`REPORT_ONLY`다. 개인 stack은 별도 owner 승인과 복구 rehearsal 뒤
2026-08-27 V22로 전환됐고, 후속 6D.1 image도 public publication environment 없이
`LOCAL_ONLY`로 배포됐다. 이후 owner-authorized V23 migration/rebuild도 `LOCAL_ONLY`로
완료됐다. 실제 public activation과 real calendar-client 검증은 `NOT_AUTHORIZED`다.

## Conventions

- request/response: JSON, except authenticated `GET /events/calendar.ics` which returns UTF-8
  `text/calendar` or 204
- resource identifier: UUID
- timestamp: ISO 8601 UTC
- 사용자 시간대: IANA identifier(예: `Asia/Seoul`)
- 인증: PostgreSQL-backed server session cookie. client가 `ownerId`를 제출하지 않으며 서버가 인증 principal에서 owner를 결정한다.
- 인증되지 않은 protected request: `401 Unauthorized`. 단, `POST /auth/logout`은 이미 만료·폐기된 session에서도 안전하게 반복할 수 있도록 유효한 CSRF token이 있으면 `204 No Content`를 반환한다.
- 비활성화된 계정의 protected request: session을 폐기하고 `401 ACCOUNT_DISABLED`
- cookie-authenticated mutation의 CSRF token 누락·불일치: `403 CSRF_TOKEN_INVALID`
- 검증 실패: `422 Unprocessable Entity`
- owner 범위 밖의 resource: 존재 여부를 노출하지 않는 `404 Not Found`

공통 오류 형식:

```json
{
  "code": "STALE_MEMO_REVISION",
  "message": "The memo changed after this proposal was created.",
  "fieldErrors": [],
  "correlationId": "8cda55ca-a46e-49ea-8e43-b14d1591ce69"
}
```

Bean Validation 오류는 `fieldErrors`에 `{ "field", "message" }`를 담는다. malformed JSON은 `MALFORMED_JSON`, 유효하지 않은 필드는 `VALIDATION_FAILED`를 사용한다.

### Authenticated-owner snapshot race guard

owner-scoped API는 선택적인 `X-Expected-Owner-Id` header를 지원한다. 이 값은 client가 request를 시작할 때 보고 있던 authenticated `userId`의 snapshot이며, 여러 탭에서 같은 cookie를 공유할 때 “A의 늦은 요청이 B로 전환된 session에서 실행되는” 경쟁만 차단한다.

이 header는 권한의 근거도, 요청 owner를 선택하는 입력도 아니다. 실제 owner와 authorization은 항상 server session의 authenticated principal에서 결정된다. header를 생략해도 기존 client는 동작하며 owner 검사는 그대로 적용된다. header가 UUID 형식이 아니거나 현재 principal의 user ID와 다르면 server는 controller, idempotency 처리, domain mutation보다 먼저 다음 `409 Conflict`를 반환한다. 이 응답은 현재 server session을 로그아웃시키지 않는다.

```json
{
  "code": "SESSION_OWNER_CHANGED",
  "message": "The authenticated account changed before this request was sent.",
  "fieldErrors": [],
  "correlationId": "8cda55ca-a46e-49ea-8e43-b14d1591ce69"
}
```

적용 범위는 다음과 같다.

| 영역 | 경로 |
| --- | --- |
| memo와 분석 시작 | `/memos/**` |
| 분석 제안, application, 검토·분석 경로 집계 | `/analysis-proposals/**`, `/analysis-applications/**`, `/analysis-review-outcomes/**`, `/analysis-path-evidence/**` |
| task와 graph | `/tasks/**`, `/graph/**` |
| memo search | `/search/**` |
| confirmed event와 authenticated snapshot | `/events/**` |
| calendar feed capability와 관리 mutation | `/calendar-feeds/**` |
| 계정 및 session 작업 | `POST /auth/logout`, `POST /auth/google/link-intent`, `DELETE /auth/identities/google` |

PWA는 authenticated bootstrap 뒤 owner ID를 API client에 설정하고, 각 guard 대상 request 시작 시 그 값을 고정한다. 응답을 기다리는 동안 탭이나 session owner가 바뀌어도 header를 새 owner로 다시 쓰지 않는다. `SESSION_OWNER_CHANGED`를 받으면 이전 owner의 in-flight scope를 폐기하고 현재 session을 다시 bootstrap한다. 특히 실패 후 남아 있던 A의 logout intent가 B session에서 이 오류를 받으면 그 stale intent와 재시도 marker를 제거한 뒤 B session을 유지한다.

`POST /auth/logout`을 제외한 protected endpoint는 유효한 session이 없을 때 `401 AUTHENTICATION_REQUIRED`를 반환한다. 모든 cookie/session mutation은 현재 CSRF token이 없거나 틀리면 domain 처리 전에 `403 CSRF_TOKEN_INVALID`를 반환한다. 위 guard 대상에 authenticated principal이 있으면 정상 domain conflict와 별도로 malformed/mismatched owner snapshot에 `409 SESSION_OWNER_CHANGED`를 반환한다.

## Authentication and CSRF

인증 방식이 달라도 성공 응답은 같은 internal user session을 표현한다. session identifier, 비밀번호, Google authorization code/access token/refresh token은 JSON에 포함하지 않는다.

```json
{
  "userId": "018f4fad-e9a9-7a01-a4d1-936938a8a1e8",
  "email": "student@example.com",
  "displayName": "메모 사용자",
  "loginMethods": ["LOCAL", "GOOGLE"]
}
```

### Bootstrap capabilities and CSRF

```http
GET /api/v1/auth/capabilities
GET /api/v1/auth/csrf
```

두 endpoint는 로그인 전에도 호출할 수 있다. capability 응답은 다음과 같다.

```json
{
  "registrationEnabled": true,
  "googleEnabled": true,
  "googleRegistrationEnabled": false
}
```

`googleEnabled`는 server가 Google 로그인·연결 기능을 명시적으로 켜고 필수 client credential 설정 값이 비어 있지 않은 상태로 기동한 경우에만 `true`다. 이 값은 Google이 credential을 실제로 승인했다는 사전 검증 결과는 아니다. `googleRegistrationEnabled`는 `googleEnabled`와 별도 server policy `GOOGLE_REGISTRATION_ENABLED`가 모두 켜져, 처음 보는 Google identity가 신규 internal user를 만들 수 있을 때만 `true`다. 기본값과 운영값은 `false`이며 SPA는 OAuth를 시작하기 전에 이 값을 이용해 신규 가입 가능 여부를 안내한다. 이 값이 `false`여도 이미 연결된 Google identity 로그인과 인증된 사용자의 명시적 Google 연결은 가능하다. Google credential 없이도 local 가입·로그인은 동작하고, Google 기능을 켠 채 필수 credential을 비워 두면 server는 잘못된 설정으로 fail fast한다.

CSRF 응답은 server가 선택한 이름을 그대로 제공한다.

```json
{
  "headerName": "X-XSRF-TOKEN",
  "parameterName": "_csrf",
  "token": "opaque-csrf-token"
}
```

SPA는 모든 mutation 전에 이 token을 `headerName` header로 전송한다. CSRF-specific `403` 뒤에는 token을 한 번만 갱신해 같은 request body와 idempotency key로 재시도한다.

### Register and local sign-in

```http
POST /api/v1/auth/register
Content-Type: application/json
X-XSRF-TOKEN: ...

{
  "email": "student@example.com",
  "password": "at-least-12-characters",
  "displayName": "메모 사용자",
  "timeZone": "Asia/Seoul"
}
```

성공하면 `201 Created`와 `AuthSession`을 반환하고 session id를 회전한다. 정규화한 email이 이미 사용 중이면 `409 EMAIL_ALREADY_REGISTERED`, 가입이 닫혀 있으면 `403 REGISTRATION_DISABLED`다. 비밀번호는 최소 12자이며 encoder의 안전한 입력 상한도 검증한다.

```http
POST /api/v1/auth/login
Content-Type: application/json
X-XSRF-TOKEN: ...

{
  "email": "student@example.com",
  "password": "at-least-12-characters"
}
```

성공하면 `200 OK`와 같은 `AuthSession`을 반환한다. 계정 존재 여부를 구분하지 않고 잘못된 email/password는 모두 `401 INVALID_CREDENTIALS`다.

### Current session and sign-out

```http
GET /api/v1/auth/me

POST /api/v1/auth/logout
X-XSRF-TOKEN: ...
```

`GET /auth/me`는 로그인 중이면 `AuthSession`, 아니면 `401`이다. sign-out은 session과 authentication을 무효화하고 `204 No Content`를 반환한다. 이미 session이 만료되었거나 이전 logout으로 폐기된 뒤에도 새로 발급받은 유효한 CSRF token을 함께 보내면 반복 logout은 `204`다. CSRF가 누락되거나 틀리면 session 유무와 관계없이 `403 CSRF_TOKEN_INVALID`이며, authenticated principal이 남아 있는 요청의 `X-Expected-Owner-Id`가 현재 owner와 다르면 logout 전에 `409 SESSION_OWNER_CHANGED`로 차단한다.

PWA에서 logout POST와 재시도 권한은 시작 탭의 `sessionStorage` intent에만 남는다. 다른 탭에는 owner UUID·무작위 attempt ID·5분 만료 시각만 담은 `localStorage` 관찰 마커와 교차 탭 신호를 전달한다. 새로 열거나 다시 로드한 수신 탭은 즉시 workspace를 잠그고 `GET /auth/me`로 logout 또는 owner 변경만 확인하며, logout POST를 대신 보내지 않는다. 이미 열린 수신 탭의 workspace는 미확정 동안 DOM에서 숨기고 모든 operation을 잠근 채 mounted 상태로 유지한다. 따라서 logout 실패가 in-memory 편집을 먼저 버리지 않으며, server가 logout/owner 변경을 확인한 때만 terminal 전환한다. marker가 사라지거나 만료된 경우에도 GET이 같은 owner를 확인해야 잠금을 풀고 기존 workspace로 돌아가며, 확인이 실패하거나 offline이면 잠금을 유지한다. 이 marker는 session identifier나 인증 credential이 아니며 owner를 선택하거나 권한을 부여하지 않는다.

### Google sign-in and explicit linking

Google capability가 켜진 경우에만 browser를 다음 server endpoint로 이동한다.

```http
GET /oauth2/authorization/google
```

callback은 `/login/oauth2/code/google`이며 authorization code와 provider token은 backend에서만 처리한다. 일반 Google 로그인의 `(GOOGLE, sub)`가 이미 연결되어 있으면 해당 internal user session을 만든다. 정규화 email이 이미 존재하면 자동 병합하거나 중복 사용자를 만들지 않고 SPA로 `ACCOUNT_LINK_REQUIRED` 오류를 돌려보낸다. subject와 email이 모두 처음인 경우에만 `GOOGLE_REGISTRATION_ENABLED=true`일 때 새 internal user를 만든다. 기본값 또는 운영 강제값인 `false`에서는 `/login?error=GOOGLE_REGISTRATION_DISABLED`로 안정적으로 거절한다. 이 gate는 기존 Google identity 로그인이나 인증된 local 사용자의 명시적 Google 연결을 막지 않는다.

기존 계정에 연결할 때는 먼저 그 계정으로 로그인한 뒤 다음 mutation으로 짧은 수명의 link intent를 session에 기록한다.

```http
POST /api/v1/auth/google/link-intent
X-XSRF-TOKEN: ...
```

```json
{ "authorizationUrl": "/oauth2/authorization/google" }
```

Google callback은 로그인한 internal owner를 기록한 session-bound intent와 그 intent에 처음 발급된 OAuth `state`가 함께 검증될 때만 identity를 연결한다. 이미 다른 사용자에게 연결된 subject, 누락·만료·state 불일치 intent, 검증되지 않은 Google email은 거절한다. 실패한 link flow는 유효한 session-indexed principal이 있을 때 그 현재 principal만 복원한다.

link callback은 intent를 한 번만 소비한다. stale·만료·재사용 callback 또는 provider가 거절한 link flow에서도 server는 유효한 현재 session-indexed owner가 있으면 그 owner만 복원하고 `/account?error=...`로 보낸다. intent 자체의 owner만으로 principal을 복원하지 않으며, owner가 바뀐 session에 stale intent의 owner를 되살리지 않는다. 일반 Google 로그인의 provider 단계가 실패하면 session-indexed owner가 있을 때 그 owner를 유지하고 `/account?error=OAUTH_FAILED`로 보낸다. OIDC 성공 뒤 domain 검증이 실패한 일반 로그인은 기존 owner를 복원할 수 있더라도 `/login?error=<safe-code>`로 돌아가며, 복원할 session-indexed owner가 없으면 session을 비운다.

local register/login 성공은 기존 session ID만 회전하지 않고 이전 session 자체를 폐기해 새 account boundary를 만든다. 따라서 이전 owner가 시작한 Google authorization request와 link intent도 함께 폐기되며, 늦게 돌아온 old link callback은 새로 local reauthentication한 owner에 identity를 연결할 수 없다.

```http
DELETE /api/v1/auth/identities/google
X-XSRF-TOKEN: ...
```

다른 usable login method가 남을 때만 연결을 해제한다. 마지막 수단을 제거하려 하면 `409 LOGIN_METHOD_REQUIRED`다. 성공 응답은 갱신된 `AuthSession`이다.

현재 인증 계약에는 local email verification, password-reset/recovery endpoint, IP·edge 단위 rate limiting과 abuse 방어, MFA/passkey, account deletion endpoint가 없다. 이 항목은 공개 배포 전 후속 account-hardening 범위다. local 로그인은 같은 계정의 잘못된 비밀번호를 `failed_attempts`에 집계하고 연속 5회 실패하면 `locked_until`을 15분 뒤로 설정한다. 잠금 중 추가 시도는 잠금 만료를 연장하지 않으며, 만료 뒤 정상 로그인하면 `failed_attempts=0`, `locked_until=null`로 초기화한다. 존재하지 않는 계정·잘못된 비밀번호·잠긴 계정은 모두 같은 public credential 오류를 사용한다. 이 계정 단위 잠금은 IP·edge rate limiting을 대체하지 않는다.

## Idempotency

다음 endpoint는 1–128자의 `Idempotency-Key` header가 필수다.

| Operation | Endpoint |
| --- | --- |
| memo 생성 | `POST /memos` |
| memo 수정 | `PATCH /memos/{memoId}` |
| graph-home 고정 변경 | `PATCH /memos/{memoId}/pin` |
| memo 휴지통 이동/복원 | `DELETE /memos/{memoId}`, `POST /memos/{memoId}/restore` |
| 결정론적 분석 시작 | `POST /memos/{memoId}/analysis-runs` |
| 제안 적용 | `POST /analysis-proposals/{proposalId}/apply` |
| 제안 거절/보류 | `POST /analysis-proposals/{proposalId}/reject`, `/postpone` |
| application 되돌리기 | `POST /analysis-applications/{applicationId}/undo` |
| task 상태 변경 | `PATCH /tasks/{taskId}` |
| recipient feed 생성 | `POST /calendar-feeds` |
| feed disclosure 변경 | `PATCH /calendar-feeds/{feedId}` |
| feed membership 추가·제거 | `POST /calendar-feeds/{feedId}/events`, `POST /calendar-feeds/{feedId}/events/{entryId}/remove` |
| feed bearer 회전·영구 revoke | `POST /calendar-feeds/{feedId}/rotate`, `POST /calendar-feeds/{feedId}/revoke` |
| feed 외부 공개 동의 | `POST /calendar-feeds/{feedId}/external-publication/enable` |

서버는 owner, operation, key, request hash와 원래 response를 같은 transaction에 저장한다. 같은 key와 같은 요청은 최초 응답을 반환하고 추가 record를 만들지 않는다. 같은 key를 다른 payload에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. PostgreSQL advisory transaction lock으로 동시에 들어온 동일 요청도 직렬화한다.

메모 수정은 `Idempotency-Key`와 `expectedRevision`을 함께 사용한다. 전자는 동일 mutation의 안전한 재시도를, 후자는 다른 편집과의 optimistic concurrency를 보장한다. 브라우저는 최초 시도에서 `clientUpdatedAt`과 `timeZone`을 포함한 body snapshot을 만들고, 재시도할 때 그 body와 idempotency key를 그대로 사용한다. 휴지통 이동과 복원은 body가 없으므로 request hash에 path의 memo identity를 포함한다.

## Memo

### Create

```http
POST /api/v1/memos
Idempotency-Key: capture-018f...
Content-Type: application/json
```

```json
{
  "id": "61c6c3e8-846a-4472-a58a-321920001868",
  "content": "11.25 OS과제 제출",
  "clientCreatedAt": "2026-08-05T11:00:00+09:00",
  "timeZone": "Asia/Seoul"
}
```

`201 Created`:

```json
{
  "id": "61c6c3e8-846a-4472-a58a-321920001868",
  "currentRevision": 1,
  "content": "11.25 OS과제 제출",
  "status": "ACTIVE",
  "analysisState": "NOT_STARTED",
  "createdAt": "2026-08-05T02:00:00Z",
  "pinned": false,
  "clientRecordedAt": "2026-08-05T01:59:58Z",
  "sourceTimeZone": "Asia/Seoul"
}
```

`clientRecordedAt`과 `sourceTimeZone`은 현재 immutable revision의 capture context다. 지연된
proposal review는 이 두 값을 함께 사용하며 memo의 서버 `createdAt`, review 시각, browser 현재
시간대로 날짜 없는 시각을 다시 해석하지 않는다.

`id`는 client가 생성한다. `content`는 1–20,000자이며 `timeZone`은 서버에서 실제 IANA zone인지 다시 검증한다.

### Read current revision

```http
GET /api/v1/memos/{memoId}
```

현재 revision의 원문, 최신 analysis state, graph-home 고정 여부를 memo view 형식으로 반환한다.
원문을 포함하는 단건·목록 읽기는 `Cache-Control: no-store`를 반환하며 PWA도 단건 상세를
`cache: no-store`로 요청한다.

### List by lifecycle status

```http
GET /api/v1/memos?status=ACTIVE&limit=50
GET /api/v1/memos?status=TRASHED&limit=50
```

`status` 기본값은 `ACTIVE`이며 `ACTIVE` 또는 `TRASHED`만 허용한다. `limit` 기본값은 50이고 서버가 1–100으로 clamp한다. 현재 owner의 memo만 `updated_at` 내림차순으로 반환하며 각 원소는 `MemoView`다. 지원하지 않는 status는 `422 INVALID_MEMO_STATUS`다.

### Update content

```http
PATCH /api/v1/memos/{memoId}
Idempotency-Key: memo-update-018f...
Content-Type: application/json
```

```json
{
  "expectedRevision": 1,
  "content": "11.26 OS과제 제출",
  "clientUpdatedAt": "2026-08-05T11:04:00+09:00",
  "timeZone": "Asia/Seoul"
}
```

`clientUpdatedAt`과 `timeZone`은 둘 다 제공하거나 둘 다 생략해야 한다. `timeZone`은 실제 IANA zone이어야 하며 한쪽만 보내면 `422 INVALID_CAPTURE_CONTEXT`다. 현재 PWA는 항상 둘 다 전송한다. 호환 client가 둘 다 생략하면 서버 기록 시각과 직전 revision의 시간대를 사용한다.

성공하면 `200 OK`와 갱신된 `MemoView`를 반환하고, immutable `memo_revisions` row에 원문과 해당 revision의 client recorded time·source time zone을 함께 추가해 `currentRevision`을 증가시킨다. 적용되지 않은 과거 revision 분석은 `STALE`이 된다. revision이 이미 바뀌었으면 `409 STALE_MEMO_REVISION`이다. 휴지통의 memo는 수정할 수 없다.

### Pin or unpin for graph-home priority

```http
PATCH /api/v1/memos/{memoId}/pin
Idempotency-Key: memo-pin-018f...
Content-Type: application/json
```

```json
{ "pinned": true }
```

`200 OK`:

```json
{
  "id": "61c6c3e8-846a-4472-a58a-321920001868",
  "pinned": true,
  "updated": true
}
```

서버는 authenticated owner의 memo row를 잠그고 `ACTIVE`인지 확인한 뒤 `pinned` metadata와
memo version을 갱신한다. 원문 revision, analysis proposal, 승인된 item/tag/task는 바꾸지 않는다.
이미 같은 상태이면 `updated`가 false다. 같은 owner/operation/key의 재시도는 최초 응답을
재생하며, 같은 key를 다른 memo나 반대 `pinned` 값에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`다.
다른 owner의 memo는 `404`, 휴지통 memo는 `409 MEMO_NOT_ACTIVE`다.

### Move to trash and restore

```http
DELETE /api/v1/memos/{memoId}
Idempotency-Key: memo-trash-018f...

POST /api/v1/memos/{memoId}/restore
Idempotency-Key: memo-restore-018f...
```

두 요청 모두 body가 없고 `200 OK`와 현재 `MemoView`를 반환한다. 휴지통 이동은 memo를 `TRASHED`로 soft-delete하고 `deleted_at`을 기록하며, 아직 검토 중이거나 보류된 run을 `STALE`로 바꾼다. raw memo와 모든 revision, 이미 승인된 파생 record는 삭제하지 않지만 task/graph 조회에서는 비활성 memo의 파생 record를 숨긴다.

복원은 memo를 `ACTIVE`로 바꾸고 `deleted_at`을 비운다. 기존 `STALE` proposal을 되살리지는 않으며 현재 revision에 새 분석 run을 시작할 수 있다. 이미 목표 상태인 memo에 같은 operation을 다시 요청해도 안전하며, idempotency replay는 최초 응답을 돌려준다.

## Analysis proposal

### Start deterministic analysis

```http
POST /api/v1/memos/{memoId}/analysis-runs
Idempotency-Key: analysis-018f...
Content-Type: application/json
```

```json
{
  "memoRevision": 1,
  "policy": "AUTO"
}
```

현재 구현은 동기 `FakeAnalyzer`로 한국어 날짜·유형·태그·항목 후보와 명시적인 ambiguity signal을 만든다. 분석 기준 시각과 시간대는 전역 설정이나 요청 시점이 아니라 지정한 immutable memo revision의 `client_recorded_at`과 `source_time_zone`을 사용한다. 따라서 수정 후 재분석과 네트워크 지연 뒤 재시도에서도 원문을 기록한 맥락이 유지된다.

서버는 local proposal을 루트 `contracts/analysis-proposal.schema.json`의 Draft 2020-12 계약, domain 규칙, owner reference 순서로 검증한다. 현재 analyzer가 서버에 선언한 proposal schema version은 `2`이며 이 값은 proposal JSON과 `analysis_runs.schema_version`에 동일하게 저장된다. 직렬화된 proposal JSON은 최대 65,536 UTF-8 byte(64 KiB), 그 안의 `providerMetadata`는 최대 8,192 UTF-8 byte(8 KiB)다. `providerMetadata`에는 1–64자의 `analyzerVersion`, `promptVersion`, `localModelVersion`, `embeddingModelVersion`, `routingPolicyVersion`과 0–100 범위의 정수 `toolCalls`가 필수다. 다섯 version은 provider 주장이 아니라 서버가 소유하고 `analysis_runs`에 저장하는 provenance와 일치해야 한다. 모든 새 LOCAL·cloud SUCCESS·fallback proposal의 metadata는 공통 server allowlist로 다시 만들어지므로 provider가 임의 field를 보존할 수 없다. 그 뒤 최상위 요약만 신뢰하지 않고 날짜·유형·태그·항목 구조에서 field-level 신호를 재계산한다.

gateway-bound 경로의 V16 내부 tag retrieval은 local proposal의 tag candidate를 최대 10개까지만 보고 canonical name과 matched alias에서 최대 20개의 distinct normalized term을 만든다. authenticated owner의 `ACTIVE` tag와 alias만 exact normalized equality로 조회하고, 잘린 일부가 아닌 전체 query 결과에서 각 source가 UUID 하나로만 해소되는지 먼저 판정한 뒤 deterministic ordering으로 최대 K=8 candidate만 gateway request에 hint로 넣는다. raw memo나 related memo는 조회하지 않고 fuzzy/vector/embedding search도 수행하지 않는다. 이 hint는 canonical 결정 권한이 없으며 gateway 결과의 최종 owner/reference validation이 항상 authoritative하다.

기본 runtime은 `FakeAnalyzer` + `UNCERTAINTY_ONLY`다. 이 mode에서 gate 결과가 `LOCAL_REVIEW`이면 gateway를 호출하지 않고 run route를 `LOCAL`, cloud evidence를 `NOT_REQUIRED`로 저장하며, 중요한 모호성 신호가 있을 때만 구성된 `NO_NETWORK` Fake gateway를 bind한다. personal overlay만 `AI_PREFERRED`를 고정하고, JSON Schema/domain 검증을 통과한 모든 현재 revision을 exact pinned localhost Ollama/LiquidAI에 보낸다. 따라서 deterministic semantic decision이 clear여도 gateway call을 준비하지만, ambiguity reason을 발명하지 않고 V20 invocation evidence에 `AI_PREFERRED_POLICY`를 별도로 기록한다. 모델을 실제 호출한 public run route는 기존 계약대로 `HYBRID`다. descriptor의 `transferMode`, gateway/provider/model/consent-policy version과 최종 outcome은 request나 provider proposal이 선택할 수 없는 server-owned evidence다. 기본 Fake는 `NO_NETWORK`이고 personal Ollama는 `LOCAL_MACHINE_MEMO_CONTENT`다. 둘 다 external-cloud consent를 요구하지 않지만 후자는 현재 immutable revision의 bounded `LocalModelInput`이 있어야 한다. `EXTERNAL_MEMO_CONTENT`만 authenticated owner의 `cloud_analysis_consent=true`, 정확히 일치하는 policy version, non-null `granted_at`과 권한 확인 instant 검사를 받는다. 현재 consent grant/revoke HTTP endpoint와 실제 external provider configuration은 없다. 어느 gateway도 exactly-once가 아니며 process crash/caller interruption 뒤 same-key/body recovery는 같은 provider-request token을 다시 사용할 수 있다.

personal Ollama descriptor는 adapter+prompt contract를 `gatewayVersion`,
`ollama-local@<exact model tag>`를 `providerId`, exact 64-hex digest를 `modelVersion`으로 bind한다. tag,
digest 또는 prompt contract 변경은 binding mismatch로 호출 전에 닫힌다. `/api/chat` body는 top-level
`truncate=false`, `shift=false`를 고정해 input 일부를 조용히 버린 응답을 success로 받지 않는다.

gateway는 defensive success proposal 또는 provider-independent typed failure만 반환하며 provider 오류 text를 반환 계약에 넣지 않는다. typed failure, descriptor/enrichment 예외, schema/domain/owner 검증에 실패한 success는 raw revision이나 canonical tag·task·relation을 바꾸지 않는다. 특히 local-model 실패는 재검증된 local 상세 검토 proposal로 닫히며, default-`RECORD` fallback evidence만 `UNKNOWN`으로 정규화하고 기존 명시 후보는 보존한다. 최종 review proposal에는 `CONSENT_REQUIRED`, `UNAVAILABLE`, `TIMEOUT`, `RETRY_EXHAUSTED`, `PROVIDER_ERROR`, `INVALID_RESPONSE`, `UNEXPECTED_FAILURE` 중 해당하는 bounded outcome만 포함되며 provider 예외 문구는 API/proposal에 포함하지 않는다. `PENDING`과 `CANCELLED_STALE`은 durable run의 내부 lifecycle evidence이고 공개 `RunView`나 proposal metadata outcome이 아니다. PWA는 non-success review proposal에서 간단한 “예” 단계를 생략하고 상세 검토를 연다. 성공과 fallback 모두 run의 `ambiguity_reasons`에 model 처리 전 서버 라우팅 원인을 보존하며, 사용자 승인 전에는 canonical record를 만들지 않는다.

V14는 gateway 요청과 final run에 descriptor·권한 확인 시각·실제로 수락한 grant 시각, owner/operation/idempotency-key hash/request hash로 결정론적으로 만든 `pmr1_...` token을 일치시키는 내부 snapshot을 도입했다. V15는 호출 전에 `QUEUED`/`PENDING` `durable-v1` run과 1:1 dispatch를 commit한다. dispatch에는 reserved proposal ID, idempotency-key/request hash, 정확히 검증한 local proposal와 hash, immutable executor binding ID, timeout/max-attempt/deadline을 저장한다. V16은 첫 gateway call 전에 bounded retrieval context의 serialized raw value, SHA-256 hash, version, candidate count도 같은 dispatch에 commit한다. V15에서 이미 만들어진 dispatch는 context를 소급 생성하지 않고 `none`/`0`/null raw/null hash로 보존한다. claim transaction은 현재 binding의 ID와 descriptor를 준비 값과 비교하고 consent를 다시 확인한 뒤 fence와 deadline에 잘린 lease를 얻는다. 불일치하거나 권한이 사라졌으면 fail closed하고 gateway를 호출하지 않는다.

V19는 같은 dispatch에 `local_decision_evidence_version`, raw-free decision projection,
`fallback_policy_version`, bounded reason-code array, model-contribution status와 semantic
changed-field array를 추가한다. memo body, prompt, response 또는 user-selected text를 이 evidence에
복제하지 않는다. Apply selection은 기존 application 원본이고 자동 학습 label이 아니다. V20의
eligible type correction subset만 아래의 bounded inference-time hint로 사용할 수 있다. 반복 pattern을
결정론 rule로 옮기려면 사람이 public synthetic positive/negative fixture와 일반화된 Fake rule로
별도 검토·승격해야 하며 fine-tuning, LoRA와 RAG ingestion은 없다.

V20은 semantic uncertainty와 model invocation을 분리한다. dispatch의
`model-invocation-v1` tuple은 `UNCERTAINTY_ONLY`/`SEMANTIC_UNCERTAINTY` 또는
`AI_PREFERRED`/`SEMANTIC_UNCERTAINTY|AI_PREFERRED_POLICY`를 기록한다. AI-preferred clear proposal은
semantic fallback reason array가 비어 있어도 되고, 이 사실을 정확성이나 ambiguity로 해석하지 않는다.
V20 자체는 당시 공개 request/response DTO, proposal schema와 `providerMetadata` shape를 바꾸지 않았다.

personal overlay에서 승인 교정 hint가 켜지면 서버는 같은 owner의 active current revision에서 최신
`APPLIED` selection 중 type-corrected 또는 user-resolved인 eligible 단일-item 사례만 bounded하게
검토한다. exact/undone/rejected/postponed/stale/unclassifiable/relation-bearing/multi-item 사례는
제외한다. 과거 memo나 selection은 모델에 보내지 않는다. current memo에도 safe exact-unique하게
나타나는 충돌 없는 짧은 anchor를 최대 K=3 찾고, `approved-type-anchor-k3-v1` snapshot에는 current
memo의 UTF-16 offset와 approved item kind만 저장한다. locked current revision에서 claim 시
`anchorText + approvedKind`를 다시 materialize한다. historical raw/ID/selection/title/tag/due/relation은
snapshot이나 prompt에 복제하지 않는다. hash/version/offset/Unicode/binding이 유효하지 않으면 model
0-call validated Fake fallback이다. Undo는 새 dispatch의 source에서 즉시 빠지고 이미 준비된 retry는
같은 snapshot을 유지한다. 이는 RAG corpus/vector/embedding, automatic rule promotion, training,
fine-tuning 또는 LoRA가 아니다.

gateway 호출은 고정된 bounded executor에서 DB transaction 밖으로 실행되고 남은 deadline보다 길게 기다리지 않는다. timeout은 즉시 검증된 local fallback을 최종화하며 자동으로 두 번째 provider call을 시작하지 않는다. process crash나 caller interruption 뒤에도 caller는 같은 idempotency key와 같은 body를 다시 보내 만료된 lease를 deadline/`max_attempts` 안에서 같은 token으로 재claim할 수 있다. 이때 tag retrieval을 다시 실행하지 않고 DB에 준비된 동일 context snapshot을 사용하므로 같은 token이 다른 context input으로 재시도되지 않는다. 이 caller-driven recovery는 유지된다.

V17은 dispatch에 `attempt_history_version`을 추가한다. 과거 dispatch는 `none`으로 남고 attempt row를 backfill하지 않는다. 새 gateway dispatch는 `gateway-attempt-v1`이며, claim된 fence마다 owner-scoped 내부 ledger row를 하나씩 만들고 application이 `max_attempts`보다 많은 row를 만들지 않는다. gateway result가 반환되면 execution은 `STARTED`다. executor가 submit을 거절하면 local termination `EXECUTOR_REJECTED`, execution `NOT_STARTED`, remote result `UNKNOWN`으로 기록되어 gateway가 실제로 반환한 typed `UNAVAILABLE` result와 구분된다. submit 뒤 timeout·caller interruption·unexpected local termination은 시작이 관측된 경우 `STARTED`, 관측되지 않은 경우 `UNKNOWN`이며 후자를 `NOT_STARTED`로 단정하지 않는다. 이 종료들과 process loss도 관측하지 못한 provider result를 성공/실패로 추정하지 않는다. 늦은 과거 fence 결과는 `FENCED_OUT`, 실행 중 revision이 바뀐 현재 fence 결과는 `STALE_FINALIZE`로 ledger에 남고 final run을 임의로 덮어쓰지 않는다.

프로세스가 종료를 관측한 시도는 monotonic local clock의 submit/wait 구간을 non-negative millisecond로 저장한다. timeout과 interruption은 이 local duration을 보존하더라도 remote result는 `UNKNOWN`이고, process loss는 duration과 model-token/cost evidence도 `UNKNOWN`/null이다. descriptor로 model version `none`의 `NO_NETWORK` Fake임을 확인한 observation은 execution uncertainty와 무관하게 model-token/cost가 `NOT_APPLICABLE`/null이다. 미래 real-model은 확정적 `NOT_STARTED`일 때만 `NOT_APPLICABLE`/null이고, 실행 또는 remote completion이 불명확하면 `UNKNOWN`/null이다. 정상 result가 반환되어도 현재 gateway 계약에는 usage/cost 숫자가 없으므로 `NOT_REPORTED`/null이다. DB는 미래 `REPORTED` 숫자 shape를 검증하지만 현재 runtime은 token/cost 숫자를 쓰지 않으며, 미보고 값을 0으로 만들지 않는다.

운영 프로필에서는 같은 lifecycle을 재사용하는 background recovery가 enabled된다. scheduler는 30초 initial/fixed delay로 실행되고, 한 주기에 DB의 `PREPARED` 또는 lease가 만료된 `RUNNING` dispatch만 최대 25건 선택한다. 선택 owner와 기존 raw idempotency key는 owner가 일치하는 dispatch/run/idempotency row에서만 얻으며 HTTP security context나 요청 DTO가 owner를 정하지 않는다. 각 후보는 기존 owner+`ANALYSIS_START`+raw-key advisory transaction lock 안에서 claim하고, live lease는 호출하지 않고 건너뛴다. process 재시작 뒤에도 남은 eligible row를 다음 bounded cycle에서 처리한다. 자동 복구와 caller 복구 모두 V15 binding/fence/deadline, V16 DB context snapshot, V17 fence별 attempt ledger, DB transaction 밖 bounded configured-gateway call, 같은 provider token, revision 재확인 finalize를 사용한다. fence가 다른 늦은 결과는 최종 상태를 덮어쓸 수 없다. 이 실행은 exactly-once가 아니라 at-least-once이며, 실제 network provider는 같은 token을 멱등하게 처리해야 한다.

finalize transaction은 memo owner·활성 상태·revision과 fence를 다시 잠가 확인하고 proposal, final run evidence, dispatch `FINALIZED`, idempotency 응답을 함께 commit한다. 준비용 validated-local proposal text는 이때 dispatch에서 지우고 hash만 유지한다. V16 tag context와 V20 approved-correction snapshot raw도 지우되 각각의 hash·version·count는 integrity evidence로 유지한다. revision이 바뀌었거나 memo가 휴지통이면 canonical data를 만들지 않고 run을 `STALE`로 확정한다. 호출 전에 stale을 발견하면 `CANCELLED_STALE`과 gateway 0-call을 남기고, 이미 실행된 호출의 늦은 결과라면 실제 bounded outcome을 보존한다. 내부 execution-contract, authorization/grant 시각, 복구용 raw idempotency key, provider token, binding, prepared payload/context, context hash/version/count, attempt ledger, lease와 fence는 `RunView`, proposal JSON, `providerMetadata`, recovery 응답, UI, 평가 report, 일반 log, browser storage, service worker에 포함되지 않는다. attempt row에도 provider 오류 text·provider/model ID·token·raw memo·retrieval context를 저장하지 않는다. 승인된 purge 정책 전에는 현재 run data와 같은 retention 경계를 따르며 V17은 임의 TTL을 추가하지 않는다.

공개 분석 시작 계약은 여전히 동기다. 내부 `QUEUED`/`RUNNING`/`PENDING` 상태, dispatch, attempt ledger를 반환하지 않고 최종 review 결과까지 기다린다. background recovery와 V17–V20은 이 HTTP shape나 공개 schema를 polling/queued/내부 evidence 응답으로 바꾸지 않는다. 실제 model-token/cost 숫자 보고·집계·budget enforcement, related-memo retrieval, fuzzy/vector search, embedding context, RAG corpus는 구현하지 않았다. 기본 runtime은 Fake + `UNCERTAINTY_ONLY`이고 personal overlay만 `AI_PREFERRED` pinned Ollama/LiquidAI semantic-patch adapter와 K=3 approved-type anchor hint를 켠다. 현재 `ollama-local-gateway-v2`는 bounded textual `thinking`을 검증한 뒤 무시하며 visible `content`만 strict JSON/schema/domain validation에 사용한다. non-text·oversized·extra field 또는 malformed/truncated content는 계속 fail-closed fallback이다. 모든 model 결과는 proposal-only이고 기존 explicit Apply가 필요하다. adapter의 provisional `num_predict=1024` hidden-reasoning budget은 visible output/HTTP response/proposal byte 상한을 늘리지 않는다. 알람/reminder persistence·delivery는 이 endpoint의 일부가 아니다. 공개 fixture 전용 test runner는 이 HTTP 계약 밖에 있다. `200 OK`:

```json
{
  "id": "d54d126e-34ef-4840-bf77-4203f08bd23e",
  "memoId": "61c6c3e8-846a-4472-a58a-321920001868",
  "memoRevision": 1,
  "status": "REVIEW_REQUIRED",
  "proposalId": "6b41133d-e81a-4751-b4b0-623b8c794bf3"
}
```

요청 revision이 현재 memo와 다르면 `409 STALE_MEMO_REVISION`이다. durable 호출 도중 revision이 바뀐 경우에는 `STALE` finalize transaction을 먼저 commit한 뒤 이 409를 반환한다. 같은 key의 다른 body는 idempotency conflict다. 같은 key/body의 live lease 또는 gateway 호출이 서버의 coordination window를 넘기면 `409 ANALYSIS_IN_PROGRESS`를 반환하며, caller는 새 key를 만들지 말고 같은 key와 body로 다시 요청해 최종 결과 또는 bounded recovery를 이어간다.

### Read proposal

```http
GET /api/v1/analysis-proposals/{proposalId}
X-Analysis-Proposal-Schema-Version: 3
```

`X-Analysis-Proposal-Schema-Version`은 client가 이해하는 최대 version이다. 생략하거나 `1`로
보내면 서버는 higher-version storage를 수정하지 않고 응답 복사본에서 v3 EVENT fields,
`dateCandidates[].candidateId`, `itemCandidates[].dueDateCandidateId`를 제거한 strict v1 projection을
반환한다. `2`는 stored v3에서 `eventScheduleCandidates`와
`suggestedEventScheduleCandidateId`만 제거하고, `3`은 저장된 supported version을 유지한다. 과거 v1/v2
proposal은 상위 요청에도 합성 upgrade되지 않는다. 현재 PWA는 `3`을 명시하고 설치된 구형 PWA는
헤더를 보내지 않아 v1 응답을 받는다. 다른 값이나 결합된 다중 값은
`422 UNSUPPORTED_PROPOSAL_SCHEMA_VERSION`이다. 성공 응답에는 `Cache-Control: no-store`와
`Vary: X-Analysis-Proposal-Schema-Version`이 포함된다.

`schemaVersion`, `memoId`, `memoRevision`, `suggestedTitle`, `typeCandidates`, `dateCandidates`,
`tagCandidates`, `itemCandidates`, `relationCandidates`, `ambiguityReasons`, `providerMetadata`를
포함한 proposal JSON을 반환한다. 이 응답 자체는 canonical tag, task, event schedule을 생성하지
않는다. 현재 Fake analyzer의 필수 provenance 값은 `fake-v10`, `none`, `none`, `none`,
`field-policy-v2`이며 `toolCalls`는 `0`이다. 추가 metadata의 `deterministicRulesVersion`은
`korean-rules-v8`이다. Current Fake와 localhost semantic-patch adapter는 계속 schema v2 producer다.
V8 rules는 명시적인 `오늘|내일|모레 + 오전|오후 + 1–12시`(optional minutes)를 immutable revision의
captured instant/source zone에서 `RELATIVE_EXACT`로 해석한다. 날짜가 없고 무입자 또는 `에`인 explicit
clock family—bare 1–12시 optional minutes, 오전/오후, Korean 24-hour clock, `HH:mm`—도 같은 작성일의
후보 중 capture instant보다 엄격히 미래인 가장 이른 safe occurrence만 `RELATIVE_EXACT`로 제안한다.
같은 시각이나 지난 후보는 제외한다. DST-gap occurrence는 버리고 더 늦은 unique same-day 후보를
사용할 수 있지만 미래 overlap occurrence가 하나라도 있으면 전체 expression을 `UNKNOWN`으로 fail
closed한다. 남은 safe 후보나 valid source zone이 없을 때도 `UNKNOWN`이며 다음 날로 이월하지 않는다.
이 값은 proposal-only이고 manual Apply 전에는 canonical due/schedule이나 alarm/reminder를 만들지 않는다.
`HYBRID` proposal에는 서버가 덮어쓴 bounded cloud evidence가 추가되며 provider error detail은
포함하지 않는다.

Schema v2의 모든 `dateCandidates[]`에는 proposal-local `candidateId`, 모든
`itemCandidates[]`에는 nullable `dueDateCandidateId`가 필수다. Non-null due reference는 같은
proposal의 precise date candidate를 가리키는 `TASK`에서만 유효하다. Schema v3는 여기에 모든
item의 `eventScheduleCandidates`와 nullable `suggestedEventScheduleCandidateId`를 추가한다. Non-EVENT
item은 empty/null이어야 하고 current domain gate는 EVENT에서도 non-null suggestion을 거절한다.
각 EVENT alternative는 strict `{candidateId, mode, startDateCandidateId, end, score}`다. `end`는 null
또는 `{dateCandidateId, boundary}`이고 boundary는 `EXCLUSIVE_AT_VALUE` 또는 all-day-only
`INCLUSIVE_THROUGH_VALUE`다. TIMED는 `EXACT_TIME|RELATIVE_EXACT`, ALL_DAY는 `DATE_ONLY` reference만
허용한다. Inclusive all-day end는 검증 시 한 calendar day 뒤의 exclusive boundary로 normalize하며
overflow/non-later range를 거절한다. Missing end는 null이고 duration은 발명하지 않는다. Multiple
distinct alternatives는 `CONFLICTING_DATES`를 요구한다. 이 v3 contract는 현재 producer output이나
review default가 아니다.

Non-null `itemCandidates[].sourceSpan`은 해당 immutable raw memo revision을 기준으로 하는 비어 있지
않은 UTF-16 code-unit half-open 범위 `[start, end)`다. 서버는 범위가 원문 안에 있고 surrogate pair를
쪼개지 않는지 검증한다. 과거 schema v1/v2 proposal은 recovery와 application 검토를 위해 계속
반환할 수 있다.

V3 item 예시는 다음과 같다. Candidate order와 score는 default 권한이 없다.

```json
{
  "candidateId": "item-event-1",
  "dueDateCandidateId": null,
  "eventScheduleCandidates": [
    {
      "candidateId": "event-time-1",
      "mode": "ALL_DAY",
      "startDateCandidateId": "date-start",
      "end": {
        "dateCandidateId": "date-last-day",
        "boundary": "INCLUSIVE_THROUGH_VALUE"
      },
      "score": 0.82
    }
  ],
  "suggestedEventScheduleCandidateId": null,
  "kind": "EVENT",
  "title": "워크숍",
  "sourceSpan": null,
  "action": null,
  "object": null,
  "confidence": 0.9
}
```

### Resolve relation candidates for informed review

```http
GET /api/v1/analysis-proposals/{proposalId}/relation-review-candidates
```

서버는 저장된 `relationCandidates` 순서를 그대로 유지한 최대 10개 배열을 반환한다. 각 원소는
`proposalIndex`, `targetType`, `targetId`, nullable `targetLabel`, `available`만 포함한다. owner의
현재 `ACTIVE` MEMO는 current raw revision의 최대 240 Unicode-code-point preview, `ACTIVE` TAG는
canonical name을 label로 쓴다. foreign·missing·trashed/inactive target은 identity 외 정보를
노출하지 않고 `targetLabel:null`, `available:false`로 표시한다. 응답은 `Cache-Control: no-store`이며
canonical record를 만들지 않는다. Apply는 이 label 응답을 권한 증명으로 신뢰하지 않고 선택된
target row를 transaction 안에서 다시 잠그고 검증한다.

### Recover proposals awaiting review

```http
GET /api/v1/analysis-proposals?status=REVIEW_REQUIRED&limit=1
GET /api/v1/analysis-proposals?status=POSTPONED&limit=1
X-Analysis-Proposal-Schema-Version: 3
```

Recovery query는 `REVIEW_REQUIRED`와 `POSTPONED`를 지원한다. `limit` 기본값은 1이고 서버가 1–100으로
clamp한다. 현재 owner의 활성 memo이면서 run의 revision이 그 memo의 현재 revision과 같은 proposal만
최신순으로 반환한다. 다른 owner, 휴지통 memo, stale revision은 노출하지 않는다. 클라이언트는 두
상태의 최신 결과를 `createdAt`으로 비교해 하나의 검토 화면만 복원한다. 단건 조회와 같은 maximum
schema header, v3→v2→v1 down-projection, `Cache-Control: no-store`, `Vary` 규칙을 목록 안의 모든
proposal에 적용한다. 아래 envelope는 기존 데이터 호환을 명확히 하기 위한 schema v1 예시다. 새
분석은 현재 schema v2를 사용하지만 이 v1 shape도 계속 복구된다.

```json
{
  "proposalId": "6b41133d-e81a-4751-b4b0-623b8c794bf3",
  "status": "POSTPONED",
  "createdAt": "2026-08-05T02:00:00Z",
  "proposal": {
    "schemaVersion": "1",
    "memoId": "61c6c3e8-846a-4472-a58a-321920001868",
    "memoRevision": 1,
    "suggestedTitle": {
      "value": "나중에 검토할 메모",
      "confidence": 0.9,
      "needsConfirmation": false
    },
    "typeCandidates": [{ "value": "TASK", "score": 0.9 }],
    "dateCandidates": [],
    "tagCandidates": [],
    "itemCandidates": [
      {
        "candidateId": "item-1",
        "kind": "TASK",
        "title": "나중에 검토할 메모",
        "sourceSpan": null,
        "action": "검토",
        "object": "메모",
        "confidence": 0.9
      }
    ],
    "relationCandidates": [],
    "ambiguityReasons": [],
    "providerMetadata": {
      "analyzerVersion": "fake-v5",
      "deterministicRulesVersion": "korean-rules-v3",
      "promptVersion": "none",
      "localModelVersion": "none",
      "embeddingModelVersion": "none",
      "routingPolicyVersion": "field-policy-v2",
      "toolCalls": 0
    }
  }
}
```

지원하지 않는 status는 `422 INVALID_PROPOSAL_STATUS`다. 분석 직후 아직 처리하지 않은 `REVIEW_REQUIRED` 제안도 서버에서 복구하므로 새로고침이 중복 분석 run을 만들지 않는다.

### Apply reviewed selection

```http
POST /api/v1/analysis-proposals/{proposalId}/apply
Idempotency-Key: apply-018f...
Content-Type: application/json
```

```json
{
  "expectedMemoRevision": 1,
  "selectedType": "TASK",
  "title": "OS 과제 제출",
  "selectedTags": [
    { "existingTagId": "10000000-0000-0000-0000-000000000001", "newCanonicalName": null },
    { "existingTagId": "10000000-0000-0000-0000-000000000002", "newCanonicalName": null }
  ],
  "items": [
    {
      "proposalCandidateId": "item-1",
      "kind": "TASK",
      "title": "OS 과제 제출",
      "due": {
        "surfaceText": "11.25",
        "value": "2026-11-25",
        "precision": "DATE_ONLY",
        "timeZone": "Asia/Seoul",
        "timeSpecified": false
      }
    }
  ],
  "selectedRelations": [
    { "proposalIndex": 0 }
  ]
}
```

한 요청에는 최대 10개 tag와 1–3개 item을 선택할 수 있다. `newCanonicalName`은 well-formed
UTF-16이고 U+0000을 포함하지 않아야 하며, raw name과 NFKC·여백 정규화 후 canonical
name, `Locale.ROOT` lowercase name이 각각 1–100 Unicode code point여야 한다. 위반은 `422
INVALID_TAG_NAME`이다. `DATE_ONLY`는 `YYYY-MM-DD`, `EXACT_TIME`과 `RELATIVE_EXACT`는 offset을
포함한 ISO 8601 timestamp여야 한다. Exact due의 offset은 immutable memo revision의 source IANA
zone이 해당 local date-time에 허용하는 값이어야 한다. DST gap이나 zone/offset 불일치는
`DUE_ZONE_OFFSET_MISMATCH`로 거절하며, overlap에서는 사용자가 명시한 두 valid offset 중 하나를
그대로 보존한다. 기존 tag도 현재 owner 소유인지 검증한다.

`items[].proposalCandidateId`는 저장된 proposal의 `itemCandidates[].candidateId`를 글자 하나도
정규화하지 않고 복사한 opaque identity다. 사용자가 직접 추가한 item은 `null`이다.
`selectedRelations`는 target/type/score를 다시 보내지 않고 잠긴 proposal 배열의 index만 최대 10개
선택한다. 모든 관계 후보는 기본 미선택이며, 명시적인 `[]`는 관계를 전부 제외한 partial apply다.
새 PWA는 후보가 없어도 이 빈 배열을 전송한다. 구형 PWA의 field 누락은 proposal 관계가 비어 있을
때만 호환되며, non-empty proposal에서 누락하면 write 전에 `422 RELATION_SELECTION_REQUIRED`다.
선택한 relation의 `sourceCandidateId`는 정확히 하나의 적용 item과 매핑되어야 한다.
relation field와 source candidate identity가 모두 없는 구형 request는 배포 전 Apply request-hash
shape로 재투영되어 이미 성공한 idempotency key를 그대로 replay할 수 있다. relation-aware request는
별도 versioned hash material을 사용해 구형 hash와 충돌하지 않는다.

apply body의 `items[].due.timeZone`은 기존 client 계약을 깨지 않기 위한 검증 대상 입력일 뿐 canonical zone 선택 권한이 아니다. 서버는 proposal이 참조하는 immutable memo revision을 잠근 뒤 모든 due의 persisted `source_time_zone`을 그 revision의 `source_time_zone`으로 교체한다. Exact due는 그 locked source zone과 offset의 일치도 함께 검증한다. 따라서 다른 기기나 여행 중 복구·승인해도 exact instant와 date-only `OVERDUE` 경계는 원문을 기록한 시간대 의미를 벗어나지 않는다.

Milestone 6A.1은 proposal schema v2 producer를 바꾸지 않았다. 이어진 6A.2a에서 PWA가 proposal-v3
EVENT alternatives를 이해하더라도 모델 출력, candidate order/score, nullable suggestion으로 schedule을
자동 연결하지 않으며 review draft는 모든 version에서 unscheduled로 시작한다. 사용자가 precise 기존
`dateCandidate`, 표시된 v3 alternative, 또는 직접 입력을 명시적으로 선택한 결과만 다음 Apply shape로
보낸다. 현재 `fake-v10`/`korean-rules-v8`의 `오늘 오후 6시` 또는 안전하게 해석된 날짜 없는 clock
candidate도 사용자 선택 전에는
canonical EVENT start가 아니다. V3 inclusive all-day candidate를 사용자가 고른 경우에만 declared
boundary에 따라 exclusive end를 한 day 뒤로 normalize한다; missing end는 그대로 null이다.

```json
{
  "expectedMemoRevision": 1,
  "selectedType": "EVENT",
  "title": "디스코드 접속",
  "selectedTags": [],
  "items": [
    {
      "proposalCandidateId": "item-1",
      "kind": "EVENT",
      "title": "디스코드 접속",
      "due": null,
      "eventSchedule": {
        "mode": "TIMED",
        "start": "2026-08-24T18:00:00+09:00",
        "end": null,
        "timeZone": "Asia/Seoul"
      }
    }
  ],
  "selectedRelations": [],
  "selectionSchemaVersion": "2"
}
```

- `eventSchedule`은 optional이고 EVENT item에만 허용된다. 일정이 없는 title-only EVENT Apply는
  기존처럼 유효하며 `selectionSchemaVersion`을 요구하지 않는다.
- schedule 하나라도 있으면 top-level `selectionSchemaVersion`은 정확히 `"2"`여야 한다. 현재
  지원하는 다른 selection version은 없다.
- `TIMED`의 `start`와 optional `end`는 offset을 포함한 whole-second ISO 8601 timestamp이며 end가
  있으면 start 뒤여야 한다. Fractional second는 RFC 5545로 반올림/절삭하지 않고 거절한다.
  `ALL_DAY`는 `YYYY-MM-DD` start와 optional later exclusive end를 사용한다. 어느
  mode도 누락된 end나 duration을 발명하지 않는다.
- request `timeZone`은 유효한 IANA identifier인지 검사하는 compatibility field다. 서버는 locked
  immutable memo revision의 `source_time_zone`으로 selection과 canonical event row를 교체한다.
  TIMED start/end의 offset은 각 local date-time에서 그 immutable source zone이 허용하는 offset 중
  하나여야 한다. DST gap은 거절하고, overlap에서는 사용자가 명시한 두 valid offset 중 하나를
  그대로 보존한다.
- 관련 domain 오류는 `INVALID_SELECTION_SCHEMA_VERSION`, `EVENT_SCHEDULE_VERSION_REQUIRED`,
  `EVENT_SCHEDULE_REQUIRES_EVENT`, `INVALID_EVENT_SCHEDULE_MODE`,
  `INVALID_EVENT_SCHEDULE_VALUE`, `INVALID_EVENT_SCHEDULE_PRECISION`, `INVALID_EVENT_SCHEDULE_RANGE`,
  `EVENT_SCHEDULE_ZONE_OFFSET_MISMATCH`, `INVALID_TIME_ZONE`이다.
- 기존 일정 없는 Apply request-hash projection은 고정되어 있다. 일정이 있는 selection만 별도
  versioned hash material을 사용하므로 legacy idempotent replay를 깨지 않는다.

검증과 application, item, task/event detail, tag link, 선택 relation 생성은 한 transaction이다. 서버는 각 index를
잠긴 proposal의 full candidate로 해소하고 owner 소유 `ACTIVE` MEMO/TAG target을 종류·UUID 순으로
잠근다. client가 보낸 target/type/score는 받지 않는다. relation은 선택 item에서 target identity로
향하는 typed canonical row이며 TAG relation을 `item_tags`로 합치지 않는다. 하나라도 유효하지 않거나
target이 review 뒤 unavailable해지면 application, item, task/event detail, tag link, relation, idempotency response를
전부 rollback하고 raw memo revision을 보존한다. 성공 응답:

```json
{
  "applicationId": "1b49505a-7615-4250-a82e-254525465baf",
  "status": "APPLIED"
}
```

이미 적용되었거나 stale인 proposal은 기존 conflict다. target race는 `409
RELATION_TARGET_UNAVAILABLE`, index/source/mapping 오류는 relation-specific `422`이며 PWA는 사용자의
검토 draft를 보존한다. 분석 provider가 이 endpoint를 직접 호출하는 계약은 없다. 저장된 relation은
아직 current `MEMO_TAG` graph나 neighborhood에 투영하지 않는다.

### Reject or postpone

```http
POST /api/v1/analysis-proposals/{proposalId}/reject
Idempotency-Key: reject-018f...

POST /api/v1/analysis-proposals/{proposalId}/postpone
Idempotency-Key: postpone-018f...
```

두 요청 모두 body가 없다. 거절은 run을 `REJECTED`, 보류는 `POSTPONED`로 바꾼다. 어느 경우에도 canonical tag/task/event/relation을 만들지 않는다.

거절 응답:

```json
{
  "proposalId": "6b41133d-e81a-4751-b4b0-623b8c794bf3",
  "status": "REJECTED"
}
```

보류 응답의 `status`는 `POSTPONED`다. 보류한 proposal은 같은 memo revision이 유지되는 동안 다시 적용하거나 거절할 수 있다.

### Undo application

```http
POST /api/v1/analysis-applications/{applicationId}/undo
Idempotency-Key: undo-018f...
```

```json
{
  "applicationId": "1b49505a-7615-4250-a82e-254525465baf",
  "status": "UNDONE"
}
```

해당 application의 relation을 source item보다 먼저 삭제한 뒤 파생 event detail, item, task, tag link와 안전하게
제거할 수 있는 신규 tag만 되돌린다. 다른 application의 canonical relation이 target으로 참조하는
tag는 orphan으로 간주하지 않는다. relation target memo/tag, source memo와 모든 raw revision은
보존한다.

### Recover latest application

```http
GET /api/v1/analysis-applications/latest
```

현재 owner의 가장 최근 application을 조회해 새로고침 뒤 undo 상태를 복구한다. 항상 `200 OK`이며 application이 없으면 다음처럼 명시적인 empty state를 반환한다.

```json
{
  "applicationId": null,
  "status": "NONE"
}
```

application이 있으면 `applicationId`와 현재 `APPLIED` 또는 `UNDONE` status를 반환한다. 다른 owner의 더 최근 application은 결과에 영향을 주지 않는다.

### Read owner-scoped review outcome summary

```http
GET /api/v1/analysis-review-outcomes/summary?days=14
X-Expected-Owner-Id: 018f4fad-e9a9-7a01-a4d1-936938a8a1e8
```

`days` 기본값은 14이며 1–90의 정수만 허용한다. cohort는 server current instant를 끝으로 하는 rolling 24시간 구간이고, `analysis_proposals.created_at`이 `[fromInclusive, toExclusive)`에 들어오는 현재 authenticated owner의 proposal만 포함한다. review·reject·postpone 시각 기준 집계가 아니다. 정수가 아니거나 범위를 벗어난 값은 `422 VALIDATION_FAILED`다.

서버는 최신순 proposal을 1,001개까지 읽어 명시적인 1,000개 cap을 검사한다. 1,001번째 proposal이 있으면 일부 1,000개를 전체처럼 반환하지 않고 `422 REVIEW_OUTCOME_WINDOW_TOO_LARGE`를 반환한다. 사용자는 더 짧은 `days`로 다시 조회할 수 있다. 성공 응답에는 `Cache-Control: no-store`가 포함된다.

```json
{
  "schemaVersion": "1",
  "comparisonPolicyVersion": "review-default-v3",
  "cohort": {
    "basis": "PROPOSAL_CREATED_AT",
    "days": 14,
    "fromInclusive": "2026-07-25T03:00:00Z",
    "toExclusive": "2026-08-08T03:00:00Z",
    "maxProposals": 1000
  },
  "proposals": {
    "total": 4,
    "withApplication": 3,
    "currentStates": {
      "queued": 0,
      "running": 0,
      "reviewRequired": 1,
      "currentPostponed": 1,
      "failed": 0,
      "stale": 0,
      "applied": 2,
      "rejected": 0,
      "other": 0
    }
  },
  "latestApplications": {
    "none": 1,
    "applied": 2,
    "undone": 1
  },
  "outcomes": {
    "exact": 1,
    "corrected": 1,
    "userResolved": 1,
    "unclassifiable": 0,
    "correctedFields": {
      "type": 0,
      "title": 1,
      "tags": 0,
      "items": 0,
      "due": 0
    }
  },
  "byAnalysisVersion": [
    {
      "route": "LOCAL",
      "analyzerVersion": "fake-v10",
      "promptVersion": "none",
      "localModelVersion": "none",
      "embeddingModelVersion": "none",
      "routingPolicyVersion": "field-policy-v2",
      "proposals": { "total": 4, "withApplication": 3, "currentStates": { "queued": 0, "running": 0, "reviewRequired": 1, "currentPostponed": 1, "failed": 0, "stale": 0, "applied": 2, "rejected": 0, "other": 0 } },
      "latestApplications": { "none": 1, "applied": 2, "undone": 1 },
      "outcomes": { "exact": 1, "corrected": 1, "userResolved": 1, "unclassifiable": 0, "correctedFields": { "type": 0, "title": 1, "tags": 0, "items": 0, "due": 0 } }
    }
  ]
}
```

세 counter 영역은 서로 다른 질문에 답하며 상호 배타적인 하나의 정확도 표로 합치면 안 된다.

- `proposals.currentStates`는 현재 mutable `analysis_runs.status` 분포다. 그 합은 `proposals.total`이다. `currentPostponed`는 **현재** `POSTPONED`인 제안만 뜻한다. 보류 뒤 적용·거절된 과거 이력은 현재 schema에 별도 event로 남지 않는다.
- `latestApplications`는 proposal마다 `(applied_at DESC, id DESC)`로 고른 최신 application 상태다. `none + applied + undone = proposals.total`이다. undo 뒤 새 idempotency key로 재적용하면 새 application이 최신이 되며, 이 값은 모든 apply/undo event 횟수가 아니다.
- `outcomes`는 application이 있는 proposal의 최신 `selection_json`을 versioned default-review projection과 의미상 비교한다. `exact + corrected + userResolved + unclassifiable = proposals.withApplication`이다. `correctedFields`는 한 `CORRECTED` selection에서 여러 field가 동시에 증가할 수 있는 비배타적 세부 집계다.

`review-default-v3`는 proposal schema version에 따라 TASK due 기본값만 재구성한다. v2/v3에서는
`TASK`의 non-null `dueDateCandidateId`가 가리키는 precise date candidate만 해당 item의 기본 due가
된다. 배열 순서나 날짜·항목 개수로 연결을 추측하지 않으며, 근사/미확정 날짜, 사용되지 않은 날짜,
연결되지 않은 precise date 또는 type 변경으로 맞지 않게 된 연결은 상세 검토와 `USER_RESOLVED`
경계를 요구한다. Proposal-v3 EVENT alternative/suggestion은 이 comparison policy의 review default가
아니다. 과거 schema v1에는 호환성을 위해 기존 보수적 TASK 규칙을 그대로 적용한다. 즉 제안 항목이
정확히 하나이고 그 항목이 TASK이며 usable date도 정확히 하나일 때만 그 due를 기본 선택에 배정한다.
사용자가 정확한 날짜를 직접 입력하거나 각 TASK의 날짜를 직접 선택한 결과는 그대로 비교·적용된다.

`exact`는 “제안 그대로 적용”을 뜻할 뿐 AI의 정답·정확도를 뜻하지 않는다. `corrected`는 바로 적용
가능한 기본 선택을 수정한 경우, `userResolved`는 동점/`UNKNOWN` 유형 또는 item 부재처럼 기본
선택만으로 적용할 수 없어 사용자가 보완한 경우다. Relation 후보, temporal-candidate-bearing v3
proposal, schedule-bearing selection,
지원하지 않는/손상된 과거 JSON, revision 불일치처럼 현재 비교 정책이 안전하게 재구성할 수 없는
application은 `unclassifiable`이다. V3 EVENT candidate를 사용자가 골랐다는 사실도 정답 label이
아니다. 거절에는 교정 target이 저장되지 않으므로 거절 건수도 정답 label이 아니다.
EVENT schedule을 포함한 selection도 versioned temporal review comparison policy가 없으므로 현재는
`unclassifiable`이며 Apply 자체를 analyzer 정확도 label로 사용하지 않는다.

`byAnalysisVersion`은 provider metadata의 임의 문자열이 아니라 `analysis_runs`의 server-owned `route`, analyzer·prompt·local-model·embedding-model·routing-policy provenance로만 같은 counter를 나눈다. 각 counter를 version group 전체에서 합하면 top-level 값과 일치한다.

응답은 aggregate counter와 provenance version만 포함한다. memo body·title, raw proposal/selection JSON, memo/proposal/application/task/tag/relation identifier는 반환하거나 일반 로그에 기록하지 않는다. 이 read-only endpoint는 clickstream을 추가로 수집하지 않으며 owner는 query/header가 아니라 authenticated server principal에서 결정한다. session이 없으면 `401`, stale owner snapshot이면 `409 SESSION_OWNER_CHANGED`다.

### Read owner-scoped analysis path evidence summary

```http
GET /api/v1/analysis-path-evidence/summary?days=14
X-Expected-Owner-Id: 018f4fad-e9a9-7a01-a4d1-936938a8a1e8
```

이 read-only endpoint는 기존 review outcome과 다른 질문에 답한다. `analysis_runs.created_at` 기준
최근 1–90일 owner cohort에서 dispatch가 있었는지, 어떤 설정 경로·invocation policy·lifecycle이
기록됐는지, 로컬 모델 설정 경로에 어떤 contribution 상태가 남았는지를 count-only로 집계한다.
전체 run을 기준으로 dispatch를 left join하므로 dispatch가 없었던 run도
`runs.withoutDispatch`에 남는다.

서버는 cohort에서 run을 최대 1,001개까지만 읽고 1,000개를 넘으면 일부 결과를 반환하지 않은 채
`422 ANALYSIS_PATH_EVIDENCE_WINDOW_TOO_LARGE`로 닫힌다. 성공 응답은 `Cache-Control: no-store`다.
`fromInclusive`와 `toExclusive`의 실제 UTC instant 차이는 정확히 `days × 24시간`이다.
설정 경로는 `dispatchRoutes.localModel`, `externalMemoTransfer`, `builtInFake`,
`legacyOrOther`로 닫혀 있다. `localModel`은 `LOCAL_MACHINE_MEMO_CONTENT`와 non-`none`
model version이 같이 기록된 경로이고, `builtInFake`는 SQL에 고정된 정확한
`fake-cloud-v2`/`fake`/`none`/`no-network-v1`/`NO_NETWORK` tuple만 뜻한다. descriptor 문자열은
repository 밖으로 나오지 않는다. 경로 또는 dispatch가 기록됐다는 사실은 실제 네트워크 전송이나
모델 호출 성공을 증명하지 않는다. 응답은 다음
불변식을 유지한다.

- `runs.withDispatch + runs.withoutDispatch = runs.total`
- `localDecisionEvidence`, dispatch lifecycle, invocation mode/reason, `dispatchRoutes`는 각각
  `runs.withDispatch`와 같은 합계를 갖는다.
- `localModelContributions`만 `dispatchRoutes.localModel`과 같은 합계를 갖는다.
- fallback reason과 changed field는 한 dispatch에서 여러 값이 증가할 수 있는 비배타적 count다.
  fallback membership은 `invocationReasons`와 구분되며 설정 경로를 선택한 직접 원인이라고 단정하지
  않는다. 전체 membership 수는
  `localDecisionEvidence.current - invocationReasons.aiPreferredPolicy` 이상이다.
- `localModelContributions` 중 `notRecorded`를 제외한 합계는
  `localDecisionEvidence.current` 이하다.
- `approvedCorrectionSnapshots.withSignals`는 1개 이상의 bounded signal이 dispatch snapshot에
  고정된 횟수다. `totalSignals`는 그 signal 수의 합이며 모델이 신호를 실제 사용했거나 품질을
  개선했다는 증거가 아니다.

응답은 고정 enum별 aggregate count만 반환한다. memo/proposal/selection/validated-local-proposal/
evidence/provider text, 모든 ID·hash·offset·token·credential·model output·per-run 값은 SQL select와
HTTP DTO 양쪽에서 제외한다. 로컬 모델 경로의 `ACCEPTED_CHANGED`/`ACCEPTED_UNCHANGED`는 해당 경로에서
성공으로 기록된 결과가 제안에 반영됐다는 운영 기록일 뿐 정확도나 개선 label이 아니다.
`PENDING`/`LOCAL_FALLBACK`은 실제
모델 시도 증거가 아니다. 이 API에는 routing, rule registration/promotion, model invocation 또는
Apply mutation이 없다. 화면의 “분석 경로 진단”도 처음 접힌 상태에서는 요청을 보내지 않고 사용자가
처음 펼쳤을 때만 읽으며, 이후에는 명시적인 `진단 새로고침`만 다시 요청한다.

## Task

### List

```http
GET /api/v1/tasks
```

```json
[
  {
    "id": "2f270221-2727-4153-a412-c602277b4117",
    "title": "OS 과제 제출",
    "status": "TODO",
    "dueAt": null,
    "dueDate": "2026-11-25",
    "overdue": false
  }
]
```

- exact-time 기한은 UTC `dueAt`, 날짜만 지정한 기한은 `dueDate`(`YYYY-MM-DD`)로 반환한다.
- `overdue`는 저장하지 않는다.
- exact-time task는 `TODO && dueAt < now`일 때 overdue다.
- date-only task는 source IANA time zone의 오늘보다 `dueDate`가 이전이고 상태가 `TODO`일 때 overdue다.

### Change source status

```http
PATCH /api/v1/tasks/{taskId}
Idempotency-Key: task-state-018f...
Content-Type: application/json
```

```json
{ "status": "DONE" }
```

허용 상태는 `TODO`, `DONE`, `CANCELLED`뿐이다. `OVERDUE`를 요청 본문으로 저장할 수 없다.

```json
{
  "id": "2f270221-2727-4153-a412-c602277b4117",
  "status": "DONE",
  "updated": true
}
```

## Confirmed scheduled events (Milestone 6A.1 source supplement)

```http
GET /api/v1/events?limit=50
```

`limit` 기본값은 50이고 1–100만 허용한다. 범위를 벗어나면 clamp하지 않고 `422
INVALID_EVENT_LIMIT`이다. 서버는 현재 조건을 만족하는 일정 중 최근 Apply 순으로 bounded window를
먼저 고른 뒤, 그 window를 start 오름차순과 item UUID tie-break로 반환한다. 따라서 오래된 일정이
한도를 채워도 방금 승인한 일정은 window에 포함된다. 성공 응답은 `Cache-Control: no-store`를
포함한다.

```json
[
  {
    "id": "8d8185b4-f61a-4d88-a7ed-ab99357a1f5a",
    "title": "디스코드 접속",
    "scheduleKind": "TIMED",
    "startAt": "2026-08-24T09:00:00Z",
    "endAt": null,
    "startDate": null,
    "endDateExclusive": null,
    "sourceTimeZone": "Asia/Seoul"
  },
  {
    "id": "bd2b2f90-6072-4ef6-bba4-9fa32f53b823",
    "title": "휴가",
    "scheduleKind": "ALL_DAY",
    "startAt": null,
    "endAt": null,
    "startDate": "2026-08-26",
    "endDateExclusive": "2026-08-29",
    "sourceTimeZone": "Asia/Seoul"
  }
]
```

서버는 authenticated principal의 owner를 사용하고 다음 조건을 모두 만족하는 row만 반환한다.

- `event_details`가 있는 EVENT item
- unarchived item, `ACTIVE` memo, memo의 current revision과 같은 item revision
- status가 현재 `APPLIED`인 application

따라서 foreign-owner, title-only/unscheduled, undone, archived, trashed, stale-revision EVENT는
제외된다. 응답은 raw memo, memo/proposal/application identity, revision, selection JSON, AI provenance를
포함하지 않는다. 이 JSON endpoint는 PWA의 confirmed schedule 목록이며, 아래 인증된 `.ics`
snapshot과 eligibility만 공유한다. share/feed, reminder/alarm, recurrence 또는 외부 calendar write는
아니다. 이 endpoint code는 2026-08-27 owner-authorized private V22 stack에 포함됐고 후속 V23
migration/rebuild에도 포함됐다. 실제 개인 schedule을 읽는 product smoke나 canonical
backfill/Apply는 별도 승인 없이 실행하지 않는다.

## Authenticated iCalendar snapshot (Milestone 6B source supplement)

```http
GET /api/v1/events/calendar.ics
Accept: text/calendar
X-Expected-Owner-Id: <current authenticated owner snapshot>
```

일정이 있으면 `200 text/calendar; charset=UTF-8`, `Cache-Control: no-store`, 고정
`Content-Disposition: attachment; filename="personal-memo-calendar.ics"`를 반환한다. 일정이 없으면
RFC-invalid empty calendar를 만들지 않고 `204 No Content`를 반환한다. 101번째 eligible EVENT가
보이면 일부만 내보내지 않고 `422 ICALENDAR_EVENT_LIMIT_EXCEEDED`로 실패한다. 완성 문서는 128 KiB
상한이며 잘못된 schedule shape/year, fractional-second canonical instant, unsafe control text도 고정
오류로 fail closed한다. 이 endpoint의 `422` code는
`ICALENDAR_EVENT_LIMIT_EXCEEDED`, `ICALENDAR_EXPORT_TOO_LARGE`, `INVALID_ICALENDAR_EVENT`,
`ICALENDAR_UNSAFE_TEXT` 중 하나다. 128 KiB는 UTF-8 byte 상한이며 문자 수 상한이 아니다.

```text
BEGIN:VCALENDAR\r\n
PRODID:-//Personal Memo//Authenticated iCalendar Export 1.0//EN\r\n
VERSION:2.0\r\n
CALSCALE:GREGORIAN\r\n
BEGIN:VEVENT\r\n
UID:pm-auth-v1-<opaque-sha256>@personal-memo.invalid\r\n
DTSTAMP:20260824T090000Z\r\n
SEQUENCE:0\r\n
DTSTART:20260824T090000Z\r\n
SUMMARY:디스코드 접속\r\n
END:VEVENT\r\n
END:VCALENDAR\r\n
```

TIMED start/end는 whole-second UTC, ALL_DAY는 `VALUE=DATE`이며 end는 canonical exclusive date를
그대로 쓴다. 명시하지 않은 end나 duration을 생성하지 않는다. title은 RFC TEXT escape 후 UTF-8
75-octet physical line으로 접고 모든 line은 CRLF로 끝난다. 안정적 opaque UID는 owner와 canonical
item identity의 domain-separated SHA-256이지만 UUID 자체를 출력하지 않으며 authorization 값이
아니다. `DTSTAMP`는 immutable canonical item 생성 시각, sequence는 `0`이다.

파일에는 current eligible EVENT의 승인 title/schedule만 들어간다. raw memo, TASK due, tag,
relation, selection/proposal/application evidence, internal UUID, source-zone extension, `VALARM`,
recurrence, description/location/URL/attendee/organizer는 포함하지 않는다. GET 전후 canonical write는
0이다. PWA는 session epoch와 expected-owner guard를 거친 한 Blob을 plain-text로 미리본 뒤 바로 그
Blob을 내려받는다. 이 파일은 1회 import용 snapshot이지 자동 동기화 URL이나 Personal Memo 알람이
아니다. recipient별 지속 구독은 아래 6C 계약의 별도 UID와 저장 상태를 사용한다.

## Recipient calendar feeds (Milestone 6C source supplement)

관리 API는 일반 owner session 경계 안에 있다. 모든 mutation은 CSRF,
`X-Expected-Owner-Id`, `Idempotency-Key`를 사용하고 response는 `no-store`다. 이미 존재하는 feed를
바꾸는 update/rotate/external-publication-enable/revoke/add/remove는 `expectedVersion`으로 optimistic
concurrency를 검사하며, create에는 아직 존재하지 않는 feed version을 보내지 않는다.

```http
GET  /api/v1/calendar-feeds/eligible-events
GET  /api/v1/calendar-feeds
POST /api/v1/calendar-feeds
GET  /api/v1/calendar-feeds/{feedId}
PATCH /api/v1/calendar-feeds/{feedId}
POST /api/v1/calendar-feeds/{feedId}/rotate
POST /api/v1/calendar-feeds/{feedId}/external-publication/enable
POST /api/v1/calendar-feeds/{feedId}/revoke
POST /api/v1/calendar-feeds/{feedId}/events
POST /api/v1/calendar-feeds/{feedId}/events/{entryId}/remove
```

생성 예시는 다음과 같다. `eventIds`는 UI에서 처음에 모두 미선택이며 owner가 직접 고른 current
canonical scheduled EVENT만 허용한다. eligible picker가 101번째 row를 발견해 `truncated: true`를
반환하면 PWA는 partial selection을 진행하지 않는다.

Owner는 폐기된 feed까지 포함해 lifetime feed를 최대 100개만 보존할 수 있다. 6C에는 delete나
capacity 회수 API가 없으므로 101번째 create는 `422 CALENDAR_FEED_LIMIT_EXCEEDED`로 닫힌다. 각 feed도
`CANCELLED` tombstone을 포함한 lifetime entry를 최대 100개만 보존한다.

```json
{
  "displayName": "가족 공유",
  "disclosureMode": "BUSY_ONLY",
  "eventIds": ["50000000-0000-4000-8000-000000000001"],
  "bearerSecret": "<canonical 43-character base64url secret>"
}
```

`bearerSecret`는 PWA가 Web Crypto로 만든 정확히 32-byte secret의 unpadded base64url 표현이다.
서버는 lowercase domain-separated SHA-256 verifier만 저장하고 response/idempotency JSON에는
secret, verifier, subscription URL을 넣지 않는다. Feed summary는 다음 metadata만 반환하며 detail은
bounded entry list를 추가한다.

```json
{
  "id": "60000000-0000-4000-8000-000000000001",
  "displayName": "가족 공유",
  "disclosureMode": "BUSY_ONLY",
  "status": "ACTIVE",
  "publicationScope": "LOCAL_ONLY",
  "publicConsentPolicyVersion": null,
  "publicConsentGrantedAt": null,
  "version": 1,
  "eventCount": 1,
  "createdAt": "2026-08-25T09:00:00Z",
  "updatedAt": "2026-08-25T09:00:00Z",
  "rotatedAt": "2026-08-25T09:00:00Z",
  "revokedAt": null,
  "entries": [
    {
      "id": "70000000-0000-4000-8000-000000000001",
      "eventId": "50000000-0000-4000-8000-000000000001",
      "title": "디스코드 접속",
      "scheduleKind": "TIMED",
      "startAt": "2026-08-25T09:00:00Z",
      "endAt": null,
      "startDate": null,
      "endDateExclusive": null,
      "sourceTimeZone": "Asia/Seoul",
      "state": "ACTIVE",
      "sequence": 0
    }
  ]
}
```

Create는 deployment capability와 무관하게 항상 `LOCAL_ONLY`/동의 없음으로 시작한다. Rotate는 새
in-memory secret과 expected version을 보내 기존 verifier를 즉시 무효화한다. Revoke는
되돌릴 수 없는 `REVOKED` 상태로 바꿔 기존 verifier를 조회 대상에서 제외하며, digest는 다른 feed에서
같은 secret이 다시 유효해지지 않도록 보존한다. Explicit remove 및 memo edit/trash/application undo는 같은
recipient UID의 `CANCELLED` tombstone을 먼저 만들고 sequence를 증가시킨다. CANCELLED management
entry의 `eventId`와 `title`은 null이다. Restore는 자동 reshare가 아니며 명시적 eligible add만 같은
entry를 재활성화한다.

Publication endpoint는 `/api/v1` server가 아니라 root fixed path다.

```http
GET  /calendar/v1/feed.ics?token=<canonical 43-character bearer>
HEAD /calendar/v1/feed.ics?token=<canonical 43-character bearer>
```

이 route는 application session, CSRF, expected-owner header를 읽지 않는 별도 stateless chain이다.
토큰 digest로 찾은 active owner의 server-owned feed/owner와 배포 mode에 맞는 feed별 공개 scope/동의
pin만 authority다. `LOCAL_ONLY` deployment는 active `LOCAL_ONLY` feed만, `PUBLIC_HTTPS` deployment는
현재 server policy와 exact match하는 동의가 있는 active `PUBLIC_HTTPS` feed만 제공한다.
Missing/malformed/unknown/rotated/revoked, disabled owner, impossible ineligible `ACTIVE` projection은
모두 동일한 empty `404`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`를 반환하며 partial
calendar를 만들지 않는다. Valid empty feed는 204, non-empty feed는 최대 100 lifetime entries와
128 KiB UTF-8 bytes로 제한한다. HEAD는 GET과 status/content header가 같고 body만 없다. 어떤
fetch도 access timestamp를 포함해 DB를 쓰지 않는다.

ACTIVE `TITLE`은 current eligible canonical title, `BUSY_ONLY`는 fixed `Busy`만 SUMMARY에 쓴다.
CANCELLED는 SUMMARY를 생략하고 같은 recipient UID, 증가한 SEQUENCE, 보존한 DTSTART/optional
DTEND와 `STATUS:CANCELLED`를 낸다. 서로 다른 feed와 6B export는 UID를 공유하지 않는다. Raw memo,
TASK/tag/relation, proposal/application/model provenance, internal UUID, description/location/URL,
attendee/organizer/attachment, recurrence, METHOD, VALARM은 포함하지 않는다.

이 private route를 실제 internet에서 구독할 수 있다는 뜻은 아니다. Public hostname/TLS/edge
operator, operator-selected external request/connection bounds와 total external deadline,
all-owned-and-external-log sentinel smoke, Google/Apple update-removal behavior는 별도 승인된 6D
runtime 뒤에만 검증한다.

## Public calendar origin capability (Milestone 6D.1 source and private deployment)

6D의 첫 source slice는 public URL authority를 server-owned deployment configuration으로 옮기는
아래 authenticated read와 strict PWA 소비 경계를 구현한다.

```http
GET /api/v1/calendar-feeds/capabilities
```

응답은 `Cache-Control: no-store`이고 다음 두 JSON 중 정확히 하나다. 필드를 생략하거나 추가할 수
없고, `mode`, `publicOrigin`, `consentPolicyVersion`의 조합도 바꿀 수 없다.

```json
{
  "mode": "LOCAL_ONLY",
  "publicOrigin": null,
  "consentPolicyVersion": null
}
```

```json
{
  "mode": "PUBLIC_HTTPS",
  "publicOrigin": "https://calendar.example.com",
  "consentPolicyVersion": "calendar-feed-public-v1"
}
```

Backend는 `app.calendar-feed.publication.enabled=false`, blank `public-origin`, blank
`consent-policy-version`을 기본으로 bind한다. Disabled인데 origin/policy가 있거나 enabled인데 origin이
maximum 255-character canonical lowercase HTTPS
multi-label ASCII hostname이 아니면 기동을 거절한다. Valid origin은 optional non-default port만
허용하고 userinfo, IP literal, `localhost`와 그 subdomain, path, query, fragment, trailing slash,
explicit `:443`, request `Host`/forwarded header, memo/feed metadata와 browser 입력에서 유도하지
않는다. 이는 hostname syntax 검사이며 public-suffix 소유권이나 DNS reachability 증명이 아니다.
Controller는 authenticated session 아래 exact union을 no-store로 반환한다.

Capability는 배포 준비 상태일 뿐 기존 feed를 공개하지 않는다. V23에서 모든 기존/신규 feed는
`publicationScope: LOCAL_ONLY`와 null consent pin으로 시작하며 다음 mutation을 별도로 완료해야 한다.

```http
POST /api/v1/calendar-feeds/{feedId}/external-publication/enable
X-XSRF-TOKEN: <csrf>
X-Expected-Owner-Id: <owner snapshot>
Idempotency-Key: <stable retry key>
Content-Type: application/json

{
  "expectedVersion": 3,
  "bearerSecret": "<new canonical 43-character base64url secret>",
  "consentPolicyVersion": "calendar-feed-public-v1"
}
```

Server가 `PUBLIC_HTTPS`가 아니거나 policy가 다르면 0-row fail closed한다. 성공하면 fresh verifier,
`PUBLIC_HTTPS` scope, exact policy와 server grant time, version/rotatedAt/updatedAt을 한 transaction에서
바꾼다. 이전 local/public URL은 즉시 404가 되며 response/idempotency JSON은 secret/URL을 포함하지
않는다. 공개 중 disclosure mode 변경은 새 동의 없이 제목 노출 범위를 바꿀 수 없도록 거절한다.
외부 공개 철회는 현재 reversible toggle이 아니라 기존 permanent revoke를 사용하며 scope/consent도
함께 지운다. Policy가 변경되면 기존 public feed는 자동으로 404가 되고 fresh secret으로 다시 동의해야
한다.

Frontend는 추가/누락 필드와 mode/origin/policy 불일치까지 strict decode한다. Valid `PUBLIC_HTTPS`만
server `publicOrigin`에 fixed `/calendar/v1/feed.ics?token=...`을 붙인다. Valid `LOCAL_ONLY`는 별개의
명시적 server state로서 현재 PWA의 exact HTTP(S) origin으로 **로컬·격리 검증용** URL을 만들 수
있지만, 화면은 외부 수신자에게 전달하지 말라는 경고를 표시한다. Capability 요청 실패·missing·
malformed 응답은 valid `LOCAL_ONLY`로 간주하지 않으며 private origin으로 조용히 fallback하지 않는다.
PUBLIC_HTTPS에서도 create 직후 local secret을 공개 URL처럼 표시하지 않는다. Owner가 unchecked 상태로
시작하는 Cloudflare/token/date-time/title/cache warning에 동의하고 enable이 성공한 뒤에만 fresh public
URL을 PWA memory에서 한 번 조립한다.

2026-08-27 owner-authorized personal V22 배포는 publication environment를 전달하지 않아
fail-closed `LOCAL_ONLY`였다. 당시 health, Flyway V22/failed 0, trusted PWA, unauthenticated
capability 401/no-store와 synthetic private feed route/token-free log를 확인했지만 개인 session을
사용한 authenticated 200 body는 runtime smoke하지 않았다. 후속 owner-authorized personal V23
migration/rebuild도 publication environment 없이 완료돼 현재 배포는 `LOCAL_ONLY`다. Actual public
activation, external request/connection/deadline bounds, owned/external token-log 검증과 Google/Apple
real-client smoke는 `NOT_AUTHORIZED`다.
Capability read는 feed/token/canonical row를 만들거나 변경하지 않고, 자동 공유나 알람도 활성화하지
않는다.

## Calendar feed public-edge preflight (Milestone 6D source/deployment boundary)

이 preflight는 OpenAPI application contract를 넓히지 않는다. Public candidate surface는 계속 기존
root path의 exact bodyless
`GET|HEAD /calendar/v1/feed.ics?token=<canonical-43-character-bearer>` 하나뿐이다.
`compose.public-feed.yaml`의 별도 edge는 host `127.0.0.1`에만 bind하고 private internal network로
backend의 그 path만 호출한다. PWA/API/OAuth route, backend host port와 PostgreSQL은 edge surface가
아니다. Edge는 caller Authorization/Cookie/Referer/forwarded header와 body를 전달하지 않고 fixed
safe route/method class만 기록한다. Local rejection과 intercepted upstream error는 generic empty
404이고 rate limit은 bodyless 429다.

Preflight의 60 requests/minute + burst 20, connection 8, upstream connect/send/read 2s/5s/10s는
provisional origin-side containment다. External per-client quota나 DNS/TLS/tunnel을 포함하는 total
deadline/SLA가 아니다. Recorded isolated smoke는 generated synthetic bearer와 disposable upstream만
사용해 exact GET/HEAD, deny surface, header stripping, bodyless 404/429, bounds와 query/path/header/
custom-method sentinel의 owned edge/upstream log 0건을 통과했다. Personal database/session/memo/feed/
canonical schedule/API Apply는 사용하지 않았다. 이는 external operator log proof가 아니다.

`compose.public-feed-activation.yaml`은 reviewed ignored `.env.public-feed`를 backend의
`PUBLIC_HTTPS` capability로 공급하는 별도 마지막 overlay다. Preflight에는 포함하지 않는다. 실제
hostname/DNS/TLS/operator/external bounds와 token-free success/error logs가 승인·검증되기 전에는
activation하지 않으며, rollback은 external route를 먼저 닫고 activation overlay 없이 backend를
recreate해 `LOCAL_ONLY`를 확인한 뒤 edge를 제거한다. Google/Apple smoke는 그 뒤에도 별도 승인이다.
상태는 `SOLO_PROVISIONAL`/`REPORT_ONLY`; public activation은 `NOT_AUTHORIZED`다.

## Graph projection

```http
GET /api/v1/graph/home?limit=100
```

`limit`은 서버에서 1–100으로 제한한다.

```json
{
  "nodes": [
    {
      "id": "memo:61c6c3e8-846a-4472-a58a-321920001868",
      "kind": "MEMO",
      "label": "OS 과제 제출",
      "memoType": "TASK",
      "taskState": "TODO",
      "overdue": false,
      "pinned": false
    },
    {
      "id": "tag:10000000-0000-0000-0000-000000000001",
      "kind": "TAG",
      "label": "운영체제",
      "memoType": null,
      "taskState": null,
      "overdue": false,
      "pinned": false
    }
  ],
  "edges": [
    {
      "id": "memo-tag:61c6c3e8-846a-4472-a58a-321920001868:10000000-0000-0000-0000-000000000001",
      "source": "memo:61c6c3e8-846a-4472-a58a-321920001868",
      "target": "tag:10000000-0000-0000-0000-000000000001",
      "kind": "MEMO_TAG"
    }
  ],
  "truncated": false,
  "projectionVersion": "7e483d25-5693-4eb8-9638-33a03db521aa"
}
```

이 응답은 `memos`, 현재 `memo_revisions`, `analysis_applications`, `memo_items`, `task_details`,
`tags`, `item_tags`에서 매번 투영하며 `Cache-Control: no-store`다. memo node의 `label`과
`memoType`은 최신 `APPLIED` application의 사용자 승인 selection을 사용하므로 여러 항목의
UUID나 생성 시각에 따라 대표값이 바뀌지 않는다. `taskState`는 해당 memo의 모든 활성 child
task를 `TODO` 우선으로 집계하고, 그중 하나라도 현재 시각 기준으로 overdue이면 memo의
`overdue`가 true다. `pinned`, `memoType`, `taskState`, `overdue`는 metadata이며 별도 system-type
hub node가 아니다.

memo 후보는 `pinned` → overdue → unfinished TODO → 가장 가까운 TODO due → 현재 raw revision의
server-created 시각 → UUID 순으로 제한 전에 정렬한다. pin/unpin이 `memos.updated_at`을
갱신하더라도 현재 raw revision의 recency는 바뀌지 않는다. 제한된 memo 집합 안의 tag 후보는
서로 연결된 memo 수가 많은 순서, normalized 표시 이름, UUID로 정렬한다. access-frequency나
영속 중요도 score는 아직 사용하지 않는다.

`limit > 1`이면 `max(1, floor(limit / 5))` 슬롯을 tag 쪽에 먼저 예약하고 나머지를 위
우선순위의 memo에 배정한다. memo 수가 그 예산보다 적으면 남은 슬롯도 tag budget이 된다.
initial memo에 실제로 존재하는 tag 수만큼만 tag 슬롯을 유지하고 남은 슬롯은 다음 memo 후보로
채운다. 이때 final selected memo 집합에서 tag 연결도·순위와 생략 여부를 다시 조회한다. initial
memo에는 tag가 없을 때도 전체 잠재 memo 범위를 한 번 probe하며, 그 범위에 tag가 전혀 없다고
확인된 경우에만 memo로 전부 backfill한다. tag가 initial 범위 밖에만 있으면 관계를 누락한 채
complete라고 하지 않고 underfill과 `truncated=true`를 유지한다. `limit=1`도 선택 memo의 tag
존재를 probe하므로 표시할 edge 공간이 없어 tag를 생략했다면 `truncated=true`다.

PWA에서 node를 누르면 현재 home response 안의 직접 이웃을 먼저 강조하고 상세 drawer를 연다.
그 뒤 아래 별도 read endpoint로 전체 owner corpus의 canonical 1-hop을 읽는다. 이 조회는 home
projection에 node를 주입하거나 home highlight 범위를 바꾸지 않는다.

```http
GET /api/v1/graph/nodes/{kind}/{id}/neighborhood?limit=20&cursor={opaque}
X-Expected-Owner-Id: 018f4fad-e9a9-7a01-a4d1-936938a8a1e8
```

`kind`는 정확히 `MEMO` 또는 `TAG`, `id`는 entity UUID다. `limit` 기본값은 20이며 1–20 밖의
값은 clamp하지 않고 `422 INVALID_GRAPH_NEIGHBORHOOD_LIMIT`이다. 성공 응답은 owner-scoped
`ACTIVE` canonical data만 사용하고 `Cache-Control: no-store`를 반환한다.

```json
{
  "center": {
    "id": "tag:10000000-0000-0000-0000-000000000001",
    "kind": "TAG",
    "label": "운영체제",
    "memoType": null,
    "taskState": null,
    "overdue": false,
    "pinned": false
  },
  "neighbors": [
    {
      "id": "memo:20000000-0000-0000-0000-000000000001",
      "kind": "MEMO",
      "label": "운영체제 과제",
      "memoType": "TASK",
      "taskState": "TODO",
      "overdue": false,
      "pinned": true
    }
  ],
  "edges": [
    {
      "id": "memo-tag:20000000-0000-0000-0000-000000000001:10000000-0000-0000-0000-000000000001",
      "source": "memo:20000000-0000-0000-0000-000000000001",
      "target": "tag:10000000-0000-0000-0000-000000000001",
      "kind": "MEMO_TAG"
    }
  ],
  "truncated": false,
  "nextCursor": null
}
```

MEMO center의 이웃은 `normalized_name`, UUID 순의 TAG이고 TAG center의 이웃은 graph home과
같은 pin → overdue → TODO → nearest due → current raw revision 시각 → UUID 순의 MEMO다.
각 neighbor에는 center와 연결된 `MEMO_TAG` edge가 정확히 하나 있다. `truncated=true`이면
`nextCursor`가 반드시 존재하고, 마지막 page는 `truncated=false`, `nextCursor=null`이다.

cursor는 canonical URL-safe Base64 JSON version 2이며 owner UUID, center kind/UUID, sort shape,
UTC `snapshotAsOf`, opaque lowercase SHA-256 `neighborhoodDigest`, 마지막 neighbor UUID를 포함한다.
digest는 첫 page와 같은 `REPEATABLE_READ` snapshot에서 보인 center 및 전체 neighbor의 membership,
표시 field와 정렬 tuple에 결합된다. 다음 page는 같은 owner 범위에서 digest를 다시 계산하므로
pin/task/due/current revision, tag name/state, 적용 link 등 관련 canonical 상태가 바뀌면 조용한
skip/duplicate 대신 `422 INVALID_GRAPH_CURSOR`로 처음부터 다시 읽게 한다. digest 바깥의 label,
raw memo, title, task/due metadata 자체는 cursor에 넣지 않는다.

cursor는 authorization이 아니며 모든 hydration query가 현재 principal owner를 다시 적용한다.
다른 owner·없는·휴지통 MEMO와 다른 owner·없는·inactive TAG는 cursor를 읽기 전에 동일한
`404 RESOURCE_NOT_FOUND`다. malformed/non-canonical/mismatched/deleted-last/digest-mismatch cursor,
24시간이 지난 cursor, 1분을 넘는 미래 cursor는 `422 INVALID_GRAPH_CURSOR`다.

PWA는 page를 dedupe-append하되 한 drawer에서 최대 5 page/100 neighbor와 100 edge만 유지한다.
`INVALID_GRAPH_CURSOR` 또는 page merge 불일치가 발생하면 이미 보인 목록을 stale로 표시하고
기존 더 불러오기를 비활성화한 뒤, 사용자가 첫 page부터 다시 시작할 수 있게 한다.
TAG neighbor memo는 React Flow home에 추가하지 않고 기존 owner-scoped `GET /memos/{memoId}`로
현재 raw revision을 no-store로 연다. Back은 해당 neighbor control로, Close는 원래 home node로
focus를 복원하되 node remount나 사용자 focus 이동을 안전하게 처리한다. lexical/alias 검색,
fuzzy/vector retrieval, tag alias 상세는 이 endpoint가 구현했다고 간주하지 않는다.

## Exact lexical memo search

```http
POST /api/v1/search/memos
Content-Type: application/json
X-XSRF-TOKEN: ...
X-Expected-Owner-Id: 018f4fad-e9a9-7a01-a4d1-936938a8a1e8
```

검색어를 URL, query string, browser storage 또는 service-worker cache에 남기지 않기 위해
read-only 검색도 JSON body를 받는 `POST`로 제공한다. Cookie-authenticated `POST`이므로 일반 mutation과
같이 현재 CSRF token이 필요하지만, canonical 또는 idempotency 상태를 쓰지 않으므로
`Idempotency-Key`는 받지 않는다. frontend Nginx access log는 method와 normalized `$uri`만 기록하며,
Spring application도 query나 memo preview를 일반 로그에 기록하지 않는다.

```json
{
  "query": "OS 과제",
  "lifecycleStatus": "ACTIVE",
  "taskState": "TODO",
  "overdue": false,
  "revisedFrom": "2026-08-01T00:00:00Z",
  "revisedBefore": "2026-09-01T00:00:00Z",
  "limit": 20,
  "cursor": null
}
```

Request 계약은 다음과 같다.

- `query`는 필수이며 well-formed UTF-16이어야 한다. raw 값과 NFKC/strip/lowercase 결과가 각각
  최대 200 UTF-16 code unit이고, normalized 결과는 1–200 Unicode code point여야 한다. lone high/low
  surrogate, U+0000, normalized blank, 어느 쪽이든 초과한 값은 `422 INVALID_SEARCH_QUERY`다. `%`, `_`,
  backslash 같은 wildcard 문자는 query language가 아니라 일반 문자다.
- `lifecycleStatus`는 `ACTIVE` 또는 `TRASHED`이고 기본값은 `ACTIVE`다.
- `taskState`는 `NONE`, `TODO`, `DONE`, `CANCELLED` 중 하나다. 생략하면 task state로 거르지 않는다.
- `overdue`는 derived Boolean filter다. `true`는 정의상 `TODO`만 매칭하므로 명시한
  `taskState`가 있다면 `TODO`여야 한다. `OVERDUE`는 저장 상태나 `taskState` 값이 아니다.
- `revisedFrom`은 현재 raw revision의 server-created instant에 대한 inclusive lower bound,
  `revisedBefore`는 exclusive upper bound다. 각 값은
  `0001-01-01T00:00:00Z`–`9999-12-31T23:59:59.999999Z` 범위여야 하고, 둘 다 있으면
  `revisedFrom < revisedBefore`여야 한다. 범위나 순서가 틀리면 `422 INVALID_SEARCH_DATE_RANGE`다.
- `limit` 기본값은 20이고 1–50 밖의 값은 clamp하지 않고 `422 INVALID_SEARCH_LIMIT`이다.
- `cursor`는 이전 page가 반환한 1–1,024자의 canonical unpadded URL-safe Base64 값이다. 첫 page에서는
  생략하거나 null로 보낸다.

Java는 query를 NFKC/strip/`Locale.ROOT` lowercase로 만든다. PostgreSQL은 저장된 BODY/TITLE에
`normalize(..., NFKC)`와 `lower(... COLLATE "und-x-icu")`를 적용한 뒤 query operand와 literal
substring으로 비교한다. 이 두 구현 경로보다 넓은 locale/collation 동등성은 계약하지 않는다.
BODY는 현재 immutable raw revision만, TITLE은 memo의 최신 유효 `APPLIED` application에 저장된
canonical title만 비교한다. 그 application은 현재 raw revision보다 오래될 수 있으므로 응답은
`currentRevision`과 nullable `canonicalRevision`을 분리한다. TAG와 ALIAS는 같은 원문 query를
`TagNormalizer`로 정규화할 수 있을 때만 현재 owner의 `ACTIVE` canonical tag/alias에 exact normalized
equality를 적용한다. 부분 tag 이름, fuzzy score, proposal tag, `UNDONE` application, archived item,
inactive tag는 결과 근거가 아니다.

성공 응답은 current raw revision 시각 내림차순, memo UUID 오름차순의 stable keyset page이며
`Cache-Control: no-store`를 포함한다.

```json
{
  "items": [
    {
      "memoId": "61c6c3e8-846a-4472-a58a-321920001868",
      "currentRevision": 3,
      "canonicalRevision": 2,
      "title": "OS 과제 제출",
      "preview": "11.26 OS 과제 수정본 제출",
      "lifecycleStatus": "ACTIVE",
      "canonicalTags": [
        {
          "id": "10000000-0000-0000-0000-000000000001",
          "name": "운영체제"
        }
      ],
      "taskState": "TODO",
      "overdue": false,
      "pinned": true,
      "revisedAt": "2026-08-11T10:00:00Z",
      "matchedFields": ["TITLE", "BODY", "ALIAS"]
    }
  ],
  "nextCursor": null,
  "truncated": false
}
```

각 item의 `preview`는 현재 raw content를 최대 240 Unicode code point로 제한하고, 잘리면 마지막
code point를 `…`로 바꾼다. `title`과 `canonicalRevision`은 함께 값이 있거나 함께 null이며 title은
최신 유효 `APPLIED` selection에서만 온다. `canonicalTags`는 matching tag/alias 우선, normalized
name, UUID 순으로 최대 8개인 현재 활성 canonical tag다. `taskState`는 활성 application의
unarchived task를 `TODO` → `DONE` → `CANCELLED` 순으로 집계하며 task가 없으면 `NONE`이다.
`overdue`는 page의 `snapshotAsOf`에서 `TODO`와 exact/date-only due를 계산한 값이다.
`matchedFields`는 `TITLE`, `BODY`, `TAG`, `ALIAS` 중 실제 match를 위 순서로 하나 이상 담는다.

`truncated=true`이면 `nextCursor`가 반드시 존재하고 마지막 page는
`truncated=false`, `nextCursor=null`이다. cursor version 1은 owner, normalized-query digest,
canonical filter digest, `CURRENT_REVISION_RECENCY_V1` sort shape, UTC `snapshotAsOf`, 첫 page의
전체 visible result membership·표시 field·정렬 tuple SHA-256 digest와 마지막 memo UUID를 담는다.
query, title, body, preview, tag/alias name 또는 filter raw value는 cursor에 넣지 않는다. cursor는
authorization이 아니며 모든 query는 authenticated principal의 owner를 다시 적용한다.

Continuation은 같은 owner/query/filter와 24시간 이내 snapshot에서 full-visible-result digest를
재계산한다. current revision/title/application/task/due/pin/tag/alias/link/lifecycle 등 결과의
membership·표시·정렬 상태가 달라졌거나 cursor가 malformed/non-canonical, 다른 owner/query/filter,
마지막 memo가 더 이상 결과에 없거나 1분을 넘는 미래/24시간 초과 snapshot이면
`422 INVALID_SEARCH_CURSOR`다. PWA는 이미 모은 결과를 stale로 표시하고 더 불러오기를 숨긴 뒤,
사용자가 cursor 없는 첫 page부터 명시적으로 다시 시작하게 한다. 한 검색 화면은 최대 5 page와
100 result만 유지한다.

검색 결과 선택은 graph home projection을 확장하거나 React Flow에 node를 주입하지 않는다.
기존 owner-scoped `GET /memos/{memoId}`를 `no-store`로 다시 호출하여 **현재** raw revision의 공유
detail을 연다. 검색 snapshot의 title/preview와 detail 원문이 달라질 수 있으며, detail의 현재 원문이
authoritative하다. `TRASHED` 결과도 원문 detail은 열 수 있지만 graph/pin action은 제공하지 않는다.

오류는 `401 AUTHENTICATION_REQUIRED`, `403 CSRF_TOKEN_INVALID`, `409 SESSION_OWNER_CHANGED`,
`422 INVALID_SEARCH_QUERY`, `INVALID_SEARCH_FILTERS`, `INVALID_SEARCH_DATE_RANGE`,
`INVALID_SEARCH_LIMIT`, `INVALID_SEARCH_CURSOR`를 사용한다. JSON shape/type이나 instant를 읽을 수
없으면 공통 `422 MALFORMED_JSON`이다.
이 endpoint는 exact lexical 첫 slice이며 fuzzy/`pg_trgm`, related-memo, vector/embedding, provider,
새 search migration 또는 별도 search service가 구현됐다는 뜻이 아니다.

## Health

```http
GET /api/v1/health
GET /actuator/health
```

첫 endpoint는 frontend 연결 상태용이고, Actuator endpoint는 runtime/container health 확인용이다.
