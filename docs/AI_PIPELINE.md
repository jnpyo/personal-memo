# AI analysis pipeline

## Objective

Turn unstructured memo text into a reviewable proposal while minimizing latency, cloud cost, privacy exposure, and irreversible mistakes.

The first implementation must support a mock analyzer. Real model selection comes after the domain flow and evaluation harness exist.

## Pipeline boundaries

```text
Raw memo
  → deterministic extraction
  → local classification and embedding
  → ambiguity gate
      → local proposal, or
      → cloud Agent enrichment
  → schema and domain validation
  → user review
  → transactional application
```

Analysis and application are separate operations. No model is allowed to write canonical domain data directly.

## Local analyzer contract

The frontend exposes a provider-independent interface conceptually equivalent to:

```ts
interface LocalAnalyzer {
  analyze(input: LocalAnalysisInput): Promise<LocalAnalysisResult>;
}
```

Inputs include:

- memo id and revision
- raw content
- created-at/base time
- IANA time zone
- user date defaults
- canonical tag candidates and aliases
- tag centroid/version metadata

Outputs include:

- type score distribution
- detected date expressions and parse candidates
- action/object completeness signals
- top tag similarities and score gaps
- top related memo candidates when available
- unresolved-reference flags
- multi-intent flags
- runtime/model version and elapsed time

## Deterministic extraction

Run deterministic extraction before any generative model.

- explicit dates and times
- relative-date surface forms
- URLs and obvious identifiers
- task-like verbs
- conjunctions that may separate multiple intents
- known aliases from the user's tag vocabulary

The date layer preserves:

```text
surfaceText
startOffset/endOffset
baseInstant
timeZone
candidateValue
precision: EXACT_TIME | DATE_ONLY | RELATIVE_EXACT | APPROXIMATE | UNKNOWN
ambiguityReason[]
```

Do not silently convert `다음 주쯤` into a precise due time.

## Embedding use

Embedding is used to retrieve candidates, not to fully understand or generate the final domain record.

- Embed the new memo once.
- Compare first against canonical tag centroids and aliases.
- Retrieve top-k related tags and memos.
- Do not compare every memo pair during normal capture.
- Store embedding model id, version, dimension, quantization, and creation time.

A tag centroid is updated incrementally after a confirmed assignment:

```text
newCentroid = (count * oldCentroid + newEmbedding) / (count + 1)
```

Centroid changes must not silently rewrite historical assignments.

## Field-level ambiguity gate

Do not reduce all uncertainty to one LLM-provided confidence number. Evaluate fields independently.

### Type ambiguity

- low top-1 probability
- small margin between top-1 and top-2
- conflict between type and extracted action/date signals

### Tag ambiguity

- low best similarity
- small gap between the first and second tag
- equally plausible tags with different meanings
- no match, indicating a possible new topic

### Date ambiguity

- missing year or time where policy cannot safely fill it
- approximate language
- multiple or contradictory dates
- relative expression without a usable base context

### Reference ambiguity

- unresolved expressions such as `그거`, `저번 것`, `교수님이 말한 것`
- several equally plausible referenced memos

### Action ambiguity

- task-like verb without an object
- object/date with no clear action
- several actions mixed in one memo

### Routing result

```text
LOCAL_REVIEW      local proposal is sufficient
CLOUD_ENRICH      cloud Agent should resolve specific fields
USER_INPUT_NEEDED cloud result still cannot safely resolve critical fields
PENDING_OFFLINE   cloud is needed but unavailable
```

Thresholds are configuration, not hard-coded business truth. Calibrate them using a Korean rough-note evaluation set and real confirmation data.

## Cloud Agent input

Send only the context needed to resolve the flagged fields.

```json
{
  "memoId": "uuid",
  "memoRevision": 4,
  "content": "전에 교수님이 말한 거 다음 주쯤 올리기",
  "baseInstant": "2026-08-05T02:00:00Z",
  "timeZone": "Asia/Seoul",
  "localResult": {},
  "ambiguityReasons": [
    "UNRESOLVED_REFERENCE",
    "IMPRECISE_DATE"
  ],
  "candidateTags": [],
  "candidateMemos": [],
  "analysisSchemaVersion": "1"
}
```

## Allowed Agent tools before confirmation

- `searchCanonicalTags(query, limit)`
- `searchRelatedMemoItems(query, limit, filters)`
- `getMemoContext(memoItemIds)` with strict ownership and result limits
- `getUserDateDefaults()`
- `resolveRelativeDate(expression, baseInstant, timeZone)`

Do not expose create, update, merge, delete, notify, or bulk-rewrite tools before user confirmation.

The orchestration layer enforces:

- allow-listed tools
- owner scope
- argument validation
- maximum result count
- maximum tool rounds
- token and elapsed-time budget
- cancellation
- structured final-output validation

Memo content must be delimited and explicitly described as untrusted source data. Text inside a memo never overrides system or tool policy.

## Structured proposal

The version-1 result should contain fields conceptually equivalent to:

```json
{
  "schemaVersion": "1",
  "memoId": "uuid",
  "memoRevision": 4,
  "suggestedTitle": {
    "value": "xv6 과제 제출",
    "confidence": 0.91,
    "needsConfirmation": false
  },
  "typeCandidates": [
    { "value": "TASK", "score": 0.94 }
  ],
  "dateCandidates": [
    {
      "surfaceText": "다음 주 화요일까지",
      "value": "2026-08-11",
      "precision": "DATE_ONLY",
      "timeSpecified": false,
      "ambiguityReasons": []
    }
  ],
  "tagCandidates": [
    {
      "existingTagId": "uuid-or-null",
      "canonicalName": "운영체제",
      "matchedAlias": "OS",
      "score": 0.92,
      "isNewProposal": false
    }
  ],
  "itemCandidates": [
    {
      "kind": "TASK",
      "title": "xv6 과제 제출",
      "sourceSpan": { "start": 0, "end": 18 },
      "action": "제출",
      "object": "xv6 과제"
    }
  ],
  "relationCandidates": [],
  "ambiguityReasons": [],
  "providerMetadata": {}
}
```

The server must validate this against both JSON Schema and domain rules. Unknown enum values and stale revisions are rejected.

## Application

The review UI sends selected candidate values, not the raw model response as an instruction to execute.

The backend:

1. re-checks owner and memo revision;
2. validates selected tags and dates;
3. creates application event;
4. writes derived items, task records, and relations in one transaction;
5. records provenance for each derived value;
6. publishes a graph-projection update after commit.

## Personalization without fine-tuning

MVP/P1 personalization uses stored behavior rather than model fine-tuning.

- canonical tag aliases
- accepted and rejected tag candidates
- common type corrections
- default date/time policy
- frequent tag co-occurrence
- user-specific routing thresholds when enough data exists

Do not train a model on a user's notes during the MVP.

## Tag evolution maintenance

Tag evolution is batch maintenance, not part of the synchronous capture path.

```text
1–2 similar notes: hidden provisional cluster
repeated stable cluster: new-tag proposal
highly overlapping tags: merge proposal
large heterogeneous tag: split proposal
inactive low-value tag: archive proposal
```

The algorithm discovers cluster candidates; a cloud model may propose a human-readable label. Semantic changes require confirmation and must preserve aliases and history.

## Evaluation

Create a versioned dataset of rough Korean notes with expected:

- type candidates
- explicit/ambiguous date interpretation
- existing/new tag behavior
- unresolved references
- multi-intent split
- escalation decision

Track precision of high-confidence local routing separately from overall accuracy. The primary safety metric is the rate of wrong local decisions that were presented as unambiguous.

