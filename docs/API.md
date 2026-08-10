# API contract — authenticated deterministic-analysis MVP

Base path: `/api/v1`

이 문서는 local/Google 이중 로그인, AI-free vertical slice와 외부 모델 없는 결정론적 분석 slice에서 구현한 HTTP 계약을 설명한다. 후속 마일스톤의 search, reminder, sync API는 구현 전이므로 포함하지 않는다.

기계 판독 가능한 동일 범위의 명세는 [`openapi.yaml`](openapi.yaml)에 있다.

## Conventions

- request/response: JSON
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
| 분석 제안, application, 검토 결과 집계 | `/analysis-proposals/**`, `/analysis-applications/**`, `/analysis-review-outcomes/**` |
| task와 graph | `/tasks/**`, `/graph/**` |
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
| memo 휴지통 이동/복원 | `DELETE /memos/{memoId}`, `POST /memos/{memoId}/restore` |
| 결정론적 분석 시작 | `POST /memos/{memoId}/analysis-runs` |
| 제안 적용 | `POST /analysis-proposals/{proposalId}/apply` |
| 제안 거절/보류 | `POST /analysis-proposals/{proposalId}/reject`, `/postpone` |
| application 되돌리기 | `POST /analysis-applications/{applicationId}/undo` |
| task 상태 변경 | `PATCH /tasks/{taskId}` |

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
  "createdAt": "2026-08-05T02:00:00Z"
}
```

`id`는 client가 생성한다. `content`는 1–20,000자이며 `timeZone`은 서버에서 실제 IANA zone인지 다시 검증한다.

### Read current revision

```http
GET /api/v1/memos/{memoId}
```

현재 revision의 원문과 최신 analysis state를 memo view 형식으로 반환한다.

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

gate 결과가 `LOCAL_REVIEW`이면 cloud gateway를 호출하지 않고 run route를 `LOCAL`, cloud evidence를 `NOT_REQUIRED`로 저장한다. 중요한 모호성 신호가 있으면 서버에 구성된 gateway의 descriptor를 먼저 읽는다. descriptor의 `transferMode`, gateway/provider/model/consent-policy version과 최종 outcome은 request나 provider proposal이 선택할 수 없는 server-owned evidence다. 현재 `FakeCloudAnalysisGateway`는 `NO_NETWORK`이므로 consent 없이 정확히 한 번 호출된다. `EXTERNAL_MEMO_CONTENT` descriptor는 authenticated owner의 `cloud_analysis_consent=true`, 정확히 일치하는 policy version, non-null `granted_at`이 모두 있고 그 시각이 권한 확인 instant보다 늦지 않아야 호출할 수 있다. 미동의·policy mismatch·다른 owner의 grant·revoke·미래 시각 grant에서는 `cloudOutcome=CONSENT_REQUIRED`를 기록하고 gateway `enrich` 호출은 0회다. V13은 기존 boolean-only grant를 모두 revoke한다. 현재 consent grant/revoke HTTP endpoint와 실제 external provider configuration은 없다.

gateway는 defensive success proposal 또는 provider-independent typed failure만 반환하며 provider 오류 text를 반환 계약에 넣지 않는다. typed failure, descriptor/enrichment 예외, schema/domain/owner 검증에 실패한 cloud success는 raw revision이나 canonical tag·task·relation을 바꾸지 않는다. 서버가 이미 검증한 local proposal의 metadata를 제한하고 다시 검증한 뒤 `HYBRID`/`REVIEW_REQUIRED` run과 proposal을 저장한다. outcome은 `CONSENT_REQUIRED`, `UNAVAILABLE`, `TIMEOUT`, `RETRY_EXHAUSTED`, `PROVIDER_ERROR`, `INVALID_RESPONSE`, `UNEXPECTED_FAILURE` 중 해당 enum만 노출되며 provider 예외 문구는 API/proposal에 포함하지 않는다. PWA는 이런 non-success proposal에서 간단한 “예” 단계를 생략하고 상세 검토를 연다. 성공과 fallback 모두 run의 `ambiguity_reasons`에 cloud 처리 전 서버 라우팅 원인을 보존하며, 사용자 승인 전에는 canonical record를 만들지 않는다.

V14는 현재 gateway 요청에 사용한 descriptor·권한 확인 시각·실제로 수락한 grant 시각과 서버가 owner/operation/idempotency-key hash/request hash로 결정론적으로 만든 `pmr1_...` token을 내부 request/final run evidence로 일치시킨다. 호출하지 않은 LOCAL·descriptor 실패·external consent 거절에는 token이 없고, 실제 `NO_NETWORK` 또는 허가된 external gateway 호출에만 token이 있다. 이 실행 계약 version, 두 시각, token은 `RunView`, proposal JSON, `providerMetadata`, recovery 응답에 포함되지 않는다.

분석 시작은 여전히 동기이고 gateway 호출은 `start()`의 DB transaction 안에서, run insert보다 먼저 일어난다. 따라서 V14는 crash-safe provider dispatch가 아니다. 실제 provider 전에는 immutable descriptor/executor binding과 durable preparation row를 먼저 commit하고, DB transaction 밖 bounded async/timeout 호출·같은 token 재시도·최종 revision 재검사를 구현해야 한다. queued/running worker, 자동 retry·duration·model-token·cost 계측과 top-k context도 구현하지 않았다. `200 OK`:

```json
{
  "id": "d54d126e-34ef-4840-bf77-4203f08bd23e",
  "memoId": "61c6c3e8-846a-4472-a58a-321920001868",
  "memoRevision": 1,
  "status": "REVIEW_REQUIRED",
  "proposalId": "6b41133d-e81a-4751-b4b0-623b8c794bf3"
}
```

요청 revision이 현재 memo와 다르면 `409 STALE_MEMO_REVISION`이다.

### Read proposal

```http
GET /api/v1/analysis-proposals/{proposalId}
X-Analysis-Proposal-Schema-Version: 2
```

`X-Analysis-Proposal-Schema-Version`을 생략하거나 `1`로 보내면 서버는 저장된 v2를 수정하지 않고 응답 복사본에서 두 binding field만 제거한 strict v1 projection을 반환한다. `2`를 보내면 새 proposal은 저장된 v2로 반환되고, 과거에 저장된 v1 proposal은 합성 upgrade 없이 v1 그대로 반환된다. 현재 PWA는 `2`를 명시하고, 설치된 구형 PWA는 헤더를 보내지 않아 v1 응답을 받는다. 다른 값이나 결합된 다중 값은 `422 UNSUPPORTED_PROPOSAL_SCHEMA_VERSION`이다. 성공 응답에는 `Cache-Control: no-store`와 `Vary: X-Analysis-Proposal-Schema-Version`이 포함된다.

`schemaVersion`, `memoId`, `memoRevision`, `suggestedTitle`, `typeCandidates`, `dateCandidates`, `tagCandidates`, `itemCandidates`, `relationCandidates`, `ambiguityReasons`, `providerMetadata`를 포함한 proposal JSON을 반환한다. 이 응답 자체는 canonical tag나 task를 생성하지 않는다. 현재 Fake analyzer의 필수 provenance 값은 `fake-v6`, `none`, `none`, `none`, `field-policy-v1`이며 `toolCalls`는 `0`이다. 추가 metadata의 `deterministicRulesVersion`은 `korean-rules-v4`다. `HYBRID` proposal에는 서버가 덮어쓴 `cloudTransferMode`, `cloudGatewayVersion`, `cloudProviderId`, `cloudModelVersion`, `cloudConsentPolicyVersion`, `cloudOutcome`, `cloudToolCalls=0`, `cloudMutationCalls=0`, `cloudResolvedFields`, `receivedRoutingPolicyVersion`, `receivedRoutingReasons`가 추가된다. 이 값은 provider error detail이 아니며 같은 run의 server-owned evidence와 일치한다. schema v2의 모든 `dateCandidates[]`에는 proposal-local `candidateId`, 모든 `itemCandidates[]`에는 nullable `dueDateCandidateId`가 필수다. non-null due reference는 같은 proposal의 정밀 date candidate를 가리키는 `TASK`에서만 유효하며, 사용자 승인 전에는 canonical due가 아니다. non-null `itemCandidates[].sourceSpan`은 해당 immutable raw memo revision을 기준으로 하는 비어 있지 않은 UTF-16 code-unit half-open 범위 `[start, end)`다. 서버는 범위가 원문 안에 있고 surrogate pair를 쪼개지 않는지 검증한다. 과거 schema v1 proposal은 recovery와 application 검토를 위해 계속 반환할 수 있다.

### Recover proposals awaiting review

```http
GET /api/v1/analysis-proposals?status=REVIEW_REQUIRED&limit=1
GET /api/v1/analysis-proposals?status=POSTPONED&limit=1
X-Analysis-Proposal-Schema-Version: 2
```

Recovery query는 `REVIEW_REQUIRED`와 `POSTPONED`를 지원한다. `limit` 기본값은 1이고 서버가 1–100으로 clamp한다. 현재 owner의 활성 memo이면서 run의 revision이 그 memo의 현재 revision과 같은 proposal만 최신순으로 반환한다. 다른 owner, 휴지통 memo, stale revision은 노출하지 않는다. 클라이언트는 두 상태의 최신 결과를 `createdAt`으로 비교해 하나의 검토 화면만 복원한다. 단건 조회와 같은 schema header, strict v1 projection, `Cache-Control: no-store`, `Vary` 규칙을 목록 안의 모든 proposal에 적용한다. 아래 envelope는 기존 데이터 호환을 명확히 하기 위한 schema v1 예시다. 새 분석은 schema v2를 사용하지만 이 v1 shape도 계속 복구된다.

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
      "routingPolicyVersion": "field-policy-v1",
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
  ]
}
```

한 요청에는 최대 10개 tag와 1–3개 item을 선택할 수 있다. `DATE_ONLY`는 `YYYY-MM-DD`, `EXACT_TIME`은 offset을 포함한 ISO 8601 timestamp여야 한다. 기존 tag도 현재 owner 소유인지 검증한다.

apply body의 `items[].due.timeZone`은 기존 client 계약을 깨지 않기 위한 검증 대상 입력일 뿐 canonical zone 선택 권한이 아니다. 서버는 proposal이 참조하는 immutable memo revision을 잠근 뒤 모든 due의 persisted `source_time_zone`을 그 revision의 `source_time_zone`으로 교체한다. 따라서 다른 기기나 여행 중 복구·승인해도 date-only `OVERDUE` 경계는 원문을 기록한 시간대에서 계산된다.

검증과 application, item, task, tag link 생성은 한 transaction이다. 한 항목이라도 유효하지 않으면 아무 canonical record도 적용하지 않는다. 현재 Apply DTO와 canonical schema는 relation 선택·저장·application 단위 undo를 아직 표현하지 않는다. 따라서 저장된 proposal의 `relationCandidates`가 비어 있지 않으면 관계를 조용히 누락하지 않고 transaction write 전에 `409 PROPOSAL_RELATIONS_UNSUPPORTED`로 fail-closed한다. 이때 application, item, task, tag link, relation은 하나도 생성하지 않으며 원본 memo revision은 그대로 보존한다. 성공 응답:

```json
{
  "applicationId": "1b49505a-7615-4250-a82e-254525465baf",
  "status": "APPLIED"
}
```

이미 적용되었거나 stale인 proposal, 아직 지원하지 않는 relation 후보가 있는 proposal은 `409`이며, 분석 provider가 이 endpoint를 직접 호출하는 계약은 없다.

### Reject or postpone

```http
POST /api/v1/analysis-proposals/{proposalId}/reject
Idempotency-Key: reject-018f...

POST /api/v1/analysis-proposals/{proposalId}/postpone
Idempotency-Key: postpone-018f...
```

두 요청 모두 body가 없다. 거절은 run을 `REJECTED`, 보류는 `POSTPONED`로 바꾼다. 어느 경우에도 canonical tag/task/relation을 만들지 않는다.

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

해당 application에서 파생된 item, task, tag link와 안전하게 제거할 수 있는 신규 tag만 되돌린다. source memo와 모든 raw revision은 보존한다.

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
      "analyzerVersion": "fake-v6",
      "promptVersion": "none",
      "localModelVersion": "none",
      "embeddingModelVersion": "none",
      "routingPolicyVersion": "field-policy-v1",
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

`review-default-v3`는 proposal schema version에 따라 due 기본값을 재구성한다. v2에서는 `TASK`의 non-null `dueDateCandidateId`가 가리키는 정밀 date candidate만 해당 item의 기본 due가 된다. 배열 순서나 날짜·항목 개수로 연결을 추측하지 않으며, 근사/미확정 날짜, 사용되지 않은 날짜, 연결되지 않은 정밀 날짜 또는 type 변경으로 맞지 않게 된 연결은 상세 검토와 `USER_RESOLVED` 경계를 요구한다. 과거 schema v1에는 호환성을 위해 기존 보수적 규칙을 그대로 적용한다. 즉 제안 항목이 정확히 하나이고 그 항목이 TASK이며 usable date도 정확히 하나일 때만 그 due를 기본 선택에 배정한다. 사용자가 정확한 날짜를 직접 입력하거나 각 TASK의 날짜를 직접 선택한 결과는 그대로 비교·적용된다.

`exact`는 “제안 그대로 적용”을 뜻할 뿐 AI의 정답·정확도를 뜻하지 않는다. `corrected`는 바로 적용 가능한 기본 선택을 수정한 경우, `userResolved`는 동점/`UNKNOWN` 유형 또는 item 부재처럼 기본 선택만으로 적용할 수 없어 사용자가 보완한 경우다. relation 후보, 지원하지 않는/손상된 과거 JSON, revision 불일치처럼 현재 비교 정책이 안전하게 재구성할 수 없는 application은 `unclassifiable`이다. 거절에는 교정 target이 저장되지 않으므로 거절 건수도 정답 label이 아니다.

`byAnalysisVersion`은 provider metadata의 임의 문자열이 아니라 `analysis_runs`의 server-owned `route`, analyzer·prompt·local-model·embedding-model·routing-policy provenance로만 같은 counter를 나눈다. 각 counter를 version group 전체에서 합하면 top-level 값과 일치한다.

응답은 aggregate counter와 provenance version만 포함한다. memo body·title, raw proposal/selection JSON, memo/proposal/application/task/tag/relation identifier는 반환하거나 일반 로그에 기록하지 않는다. 이 read-only endpoint는 clickstream을 추가로 수집하지 않으며 owner는 query/header가 아니라 authenticated server principal에서 결정한다. session이 없으면 `401`, stale owner snapshot이면 `409 SESSION_OWNER_CHANGED`다.

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
      "overdue": false
    },
    {
      "id": "tag:10000000-0000-0000-0000-000000000001",
      "kind": "TAG",
      "label": "운영체제"
    }
  ],
  "edges": [
    {
      "id": "memo-tag:...",
      "source": "memo:61c6c3e8-846a-4472-a58a-321920001868",
      "target": "tag:10000000-0000-0000-0000-000000000001",
      "kind": "MEMO_TAG"
    }
  ],
  "truncated": false,
  "projectionVersion": "opaque-uuid"
}
```

이 응답은 `analysis_applications`, `memo_items`, `task_details`, `tags`, `item_tags`에서 매번 투영된다. memo node의 `label`과 `memoType`은 최신 `APPLIED` application의 사용자 승인 selection을 사용하므로 여러 항목의 UUID나 생성 시각에 따라 대표값이 바뀌지 않는다. `taskState`는 해당 memo의 모든 활성 child task를 `TODO` 우선으로 집계하고, 그중 하나라도 현재 시각 기준으로 overdue이면 memo의 `overdue`가 true다. `memoType`, `taskState`, `overdue`는 metadata이며 별도 system-type hub node가 아니다.

## Health

```http
GET /api/v1/health
GET /actuator/health
```

첫 endpoint는 frontend 연결 상태용이고, Actuator endpoint는 runtime/container health 확인용이다.
