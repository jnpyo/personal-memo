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
| Fake 분석 시작 | `POST /memos/{memoId}/analysis-runs` |
| 제안 적용 | `POST /analysis-proposals/{proposalId}/apply` |
| 제안 거절/보류 | `POST /analysis-proposals/{proposalId}/reject`, `/postpone` |
| application 되돌리기 | `POST /analysis-applications/{applicationId}/undo` |
| task 상태 변경 | `PATCH /tasks/{taskId}` |

서버는 owner, operation, key, request hash와 원래 response를 같은 transaction에 저장한다. 같은 key와 같은 요청은 최초 응답을 반환하고 추가 record를 만들지 않는다. 같은 key를 다른 payload에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. PostgreSQL advisory transaction lock으로 동시에 들어온 동일 요청도 직렬화한다.

메모 수정은 idempotency key 대신 `expectedRevision`을 사용하는 optimistic concurrency 계약이다.

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

### Update content

```http
PATCH /api/v1/memos/{memoId}
Content-Type: application/json
```

```json
{
  "expectedRevision": 1,
  "content": "11.26 OS과제 제출"
}
```

성공하면 immutable `memo_revisions` row를 추가하고 `currentRevision`을 증가시킨다. 적용되지 않은 과거 revision 분석은 `STALE`이 된다. revision이 이미 바뀌었으면 `409 STALE_MEMO_REVISION`이다.

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

현재 구현은 동기 Fake 분석을 실행한다. `200 OK`:

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
