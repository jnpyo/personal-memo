# API contract draft

Base path: `/api/v1`

This is a design contract, not a final OpenAPI document. Generate and commit OpenAPI once the first vertical slice begins.

## Conventions

- JSON request and response bodies
- UUID resource identifiers
- UTC timestamps in ISO 8601
- user time zone as an IANA identifier, for example `Asia/Seoul`
- `Idempotency-Key` required for retryable creation/application endpoints
- optimistic version field or `If-Match` for updates
- cursor pagination for growing collections

Error shape:

```json
{
  "code": "STALE_MEMO_REVISION",
  "message": "The memo changed after this proposal was created.",
  "fieldErrors": [],
  "correlationId": "uuid"
}
```

## Memo APIs

### Create memo

```http
POST /api/v1/memos
Idempotency-Key: <client-mutation-id>
```

```json
{
  "id": "client-generated-uuid",
  "content": "11.25 OS과제 제출",
  "clientCreatedAt": "2026-08-05T11:00:00+09:00",
  "timeZone": "Asia/Seoul"
}
```

Response `201 Created`:

```json
{
  "id": "uuid",
  "currentRevision": 1,
  "content": "11.25 OS과제 제출",
  "status": "ACTIVE",
  "analysisState": "NOT_STARTED",
  "createdAt": "2026-08-05T02:00:00Z"
}
```

### Read/update/delete

```http
GET    /api/v1/memos/{memoId}
PATCH  /api/v1/memos/{memoId}
DELETE /api/v1/memos/{memoId}
POST   /api/v1/memos/{memoId}/restore
```

Update request includes `expectedRevision`. A successful content update creates a new immutable revision and makes older pending proposals stale.

### List memos

```http
GET /api/v1/memos?cursor=...&limit=...&status=ACTIVE
```

## Analysis APIs

### Start analysis

```http
POST /api/v1/memos/{memoId}/analysis-runs
Idempotency-Key: <uuid>
```

```json
{
  "memoRevision": 1,
  "policy": "AUTO",
  "localResult": {
    "schemaVersion": "1",
    "analyzerVersion": "mock-v1",
    "typeScores": [],
    "dateSignals": [],
    "tagMatches": [],
    "ambiguityReasons": []
  }
}
```

Possible responses:

- `201 Created`: proposal is immediately available
- `202 Accepted`: cloud/background work queued; includes `analysisRunId`
- `409 Conflict`: memo revision is stale
- `422 Unprocessable Entity`: local result schema/domain invalid

### Poll analysis

```http
GET /api/v1/analysis-runs/{analysisRunId}
```

```json
{
  "id": "uuid",
  "memoId": "uuid",
  "memoRevision": 1,
  "status": "REVIEW_REQUIRED",
  "proposalId": "uuid",
  "ambiguityReasons": [],
  "failureCode": null
}
```

SSE may replace polling later, but polling is sufficient for the first implementation.

### Read proposal

```http
GET /api/v1/analysis-proposals/{proposalId}
```

Returns the validated versioned proposal described in `AI_PIPELINE.md`.

### Apply proposal

```http
POST /api/v1/analysis-proposals/{proposalId}/apply
Idempotency-Key: <uuid>
```

```json
{
  "expectedMemoRevision": 1,
  "selectedType": "TASK",
  "title": "OS 과제 제출",
  "selectedTags": [
    { "existingTagId": "uuid" },
    { "newCanonicalName": "과제" }
  ],
  "items": [
    {
      "kind": "TASK",
      "title": "OS 과제 제출",
      "due": {
        "surfaceText": "11.25",
        "value": "2026-11-25",
        "precision": "DATE_ONLY",
        "timeZone": "Asia/Seoul"
      }
    }
  ],
  "relations": []
}
```

Response `200 OK` returns application id, created/linked resources, and a graph update token. Duplicate idempotency keys return the original result.

### Reject or postpone

```http
POST /api/v1/analysis-proposals/{proposalId}/reject
POST /api/v1/analysis-proposals/{proposalId}/postpone
```

### Undo application

```http
POST /api/v1/analysis-applications/{applicationId}/undo
Idempotency-Key: <uuid>
```

Undo preserves the raw memo.

## Tag APIs

```http
GET  /api/v1/tags?query=OS&includeAliases=true
POST /api/v1/tags
PATCH /api/v1/tags/{tagId}
POST /api/v1/tags/{tagId}/aliases
```

Taxonomy proposal endpoints are P1:

```http
GET  /api/v1/taxonomy-proposals
POST /api/v1/taxonomy-proposals/{id}/apply
POST /api/v1/taxonomy-proposals/{id}/reject
```

No model-facing endpoint directly creates or merges tags.

## Task APIs

```http
GET   /api/v1/tasks?state=TODO&dueBefore=...
GET   /api/v1/tasks/{taskId}
PATCH /api/v1/tasks/{taskId}
```

Update operations support TODO, DONE, and CANCELLED. API responses may include derived `overdue: true`.

## Graph APIs

### Home

```http
GET /api/v1/graph/home?limit=100&type=ALL
```

```json
{
  "nodes": [
    {
      "id": "memo:uuid",
      "kind": "MEMO",
      "label": "OS 과제 제출",
      "memoType": "TASK",
      "taskState": "TODO",
      "overdue": false,
      "collapsedMemberCount": 0
    }
  ],
  "edges": [],
  "truncated": false,
  "projectionVersion": "opaque-token"
}
```

### Neighborhood

```http
GET /api/v1/graph/neighborhood?nodeId=memo:uuid&depth=1&limit=100
```

The server enforces a hard maximum independent of the requested limit.

## Search API

```http
GET /api/v1/search?q=페이지테이블&type=ALL&tagId=...&cursor=...
```

Results identify whether a memo is currently inside a collapsed cluster so the client can reveal it.

## Reminder and push APIs(P1)

```http
POST   /api/v1/push-subscriptions
DELETE /api/v1/push-subscriptions/{id}
POST   /api/v1/tasks/{taskId}/reminders
PATCH  /api/v1/reminders/{id}
DELETE /api/v1/reminders/{id}
```

Reminder creation is an ordinary confirmed backend action, not a pre-confirmation Agent tool.

## Offline synchronization(P1)

```http
POST /api/v1/sync/batch
GET  /api/v1/sync/changes?cursor=...
```

Every client mutation carries:

- client mutation id
- entity id
- base version/revision
- client timestamp
- operation payload

Conflicts return explicit server/current versions. Do not silently use last-write-wins for memo content without a product decision.

