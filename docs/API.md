# API contract — Milestone 1

Base path: `/api/v1`

이 문서는 현재 AI-free vertical slice에서 구현한 HTTP 계약을 설명한다. 후속 마일스톤의 search, reminder, sync API는 구현 전이므로 포함하지 않는다.

기계 판독 가능한 동일 범위의 명세는 [`openapi.yaml`](openapi.yaml)에 있다.

## Conventions

- request/response: JSON
- resource identifier: UUID
- timestamp: ISO 8601 UTC
- 사용자 시간대: IANA identifier(예: `Asia/Seoul`)
- 현재 인증 모드: 서버가 고정한 개발용 owner. client가 `ownerId`를 제출하지 않는다.
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

## Idempotency

다음 endpoint는 1–128자의 `Idempotency-Key` header가 필수다.

| Operation | Endpoint |
| --- | --- |
| memo 생성 | `POST /memos` |
| memo 수정 | `PATCH /memos/{memoId}` |
| memo 휴지통 이동/복원 | `DELETE /memos/{memoId}`, `POST /memos/{memoId}/restore` |
| Fake 분석 시작 | `POST /memos/{memoId}/analysis-runs` |
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

### Start Fake analysis

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

현재 구현은 동기 Fake 분석을 실행한다. 분석 기준 시각과 시간대는 전역 설정이나 요청 시점이 아니라 지정한 immutable memo revision의 `client_recorded_at`과 `source_time_zone`을 사용한다. 따라서 수정 후 재분석과 네트워크 지연 뒤 재시도에서도 원문을 기록한 맥락이 유지된다. `200 OK`:

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
```

`schemaVersion`, `memoId`, `memoRevision`, `suggestedTitle`, `typeCandidates`, `dateCandidates`, `tagCandidates`, `itemCandidates`, `ambiguityReasons`, `providerMetadata`를 포함한 proposal JSON을 반환한다. 이 응답 자체는 canonical tag나 task를 생성하지 않는다.

### Recover proposals awaiting review

```http
GET /api/v1/analysis-proposals?status=REVIEW_REQUIRED&limit=1
GET /api/v1/analysis-proposals?status=POSTPONED&limit=1
```

Recovery query는 `REVIEW_REQUIRED`와 `POSTPONED`를 지원한다. `limit` 기본값은 1이고 서버가 1–100으로 clamp한다. 현재 owner의 활성 memo이면서 run의 revision이 그 memo의 현재 revision과 같은 proposal만 최신순으로 반환한다. 다른 owner, 휴지통 memo, stale revision은 노출하지 않는다. 클라이언트는 두 상태의 최신 결과를 `createdAt`으로 비교해 하나의 검토 화면만 복원한다. 응답 원소는 다음 envelope다.

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
    "itemCandidates": [],
    "relationCandidates": [],
    "ambiguityReasons": [],
    "providerMetadata": {}
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

검증과 application, item, task, tag link 생성은 한 transaction이다. 한 항목이라도 유효하지 않으면 아무 canonical record도 적용하지 않는다. 성공 응답:

```json
{
  "applicationId": "1b49505a-7615-4250-a82e-254525465baf",
  "status": "APPLIED"
}
```

이미 적용되었거나 stale인 proposal은 `409`이며, 분석 provider가 이 endpoint를 직접 호출하는 계약은 없다.

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

이 응답은 `memo_items`, `task_details`, `tags`, `item_tags`에서 매번 투영된다. `memoType`, `taskState`, `overdue`는 memo node metadata이며 별도 system-type hub node가 아니다.

## Health

```http
GET /api/v1/health
GET /actuator/health
```

첫 endpoint는 frontend 연결 상태용이고, Actuator endpoint는 runtime/container health 확인용이다.
