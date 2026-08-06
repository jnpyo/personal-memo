# AI analysis pipeline

## Objective

Turn unstructured memo text into a reviewable proposal while minimizing latency, cloud cost, privacy exposure, and irreversible mistakes.

The first implementation must support a mock analyzer. Real model selection comes after the domain flow and evaluation harness exist.

## Current implementation status

The repository now implements the model-free portion of Milestone 2:

- a revision-context Korean date parser with explicit `UNKNOWN` fallback;
- a versioned 12-case Korean memo fixture suite;
- an enum-based ambiguity gate that routes to `LOCAL_REVIEW` or `CLOUD_ENRICH`;
- `FakeAnalyzer` and a no-network, no-tool, mutation-free `FakeCloudAnalysisGateway`;
- Draft 2020-12 contract, domain, and owner-reference validation before routing and again after enrichment;
- server-side reconstruction of field-level routing signals instead of trusting only an analyzer summary;
- server-owned analyzer, prompt, local-model, embedding-model, and routing-policy provenance for both `LOCAL` and `HYBRID` runs while every result remains `REVIEW_REQUIRED`;
- required, bounded proposal metadata and UTF-8 payload limits before anything is persisted;
- explicit user resolution of `UNKNOWN` types and partial item application.

No real local model or cloud provider is connected. The roadmap's real-provider adapter remains deferred by the project decision until explicitly authorized.

## Pipeline boundaries

```text
Raw memo
  → deterministic extraction
  → local classification and embedding
  → schema, domain, and owner-reference validation
  → deterministic field-level ambiguity assessment
      → local proposal, or
      → cloud Agent enrichment
  → enriched proposal validation
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

- top-1 score below `0.70`
- top-1/top-2 margin below `0.10`
- conflict between type and extracted action/date signals

### Tag ambiguity

- best similarity below `0.75`
- equally plausible tags with different meanings
- no match, indicating a possible new topic

Tags in one proposal are independently applicable, so two strong tags are not treated as a
top-1/top-2 conflict merely because their scores are close.

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

The current deterministic thresholds belong to routing policy `field-policy-v1`; changing a
threshold or structural rule requires a new policy version. Runtime configuration and
user-specific thresholds remain a later milestone.

The server unions these derivable signals with the analyzer-declared `ambiguityReasons`. Nested
date reasons must also appear in the proposal summary, and structural omissions such as a new tag,
an unknown type, or an incomplete task cannot be hidden by clearing that summary. A proposal may
show at most five date candidates; detecting more adds `CANDIDATE_LIMIT_EXCEEDED` before display
truncation so the overflow never silently takes the local route.

`analysis_runs.ambiguity_reasons` stores the immutable server assessment that caused routing.
`analysis_proposals.proposal_json.ambiguityReasons` stores the final proposal reasons, which a
future provider may resolve or refine. This separation preserves why a `HYBRID` run occurred.
The provider-independent `CloudAnalysisRequest` also carries those authoritative reasons and the
policy version alongside a defensive copy of the validated local proposal. It never grants a
provider a canonical write tool or includes hidden raw memo text.

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
  "memoId": "61c6c3e8-846a-4472-a58a-321920001868",
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
      "confidence": 0.86,
      "ambiguityReasons": []
    }
  ],
  "tagCandidates": [
    {
      "existingTagId": "10000000-0000-0000-0000-000000000001",
      "canonicalName": "운영체제",
      "matchedAlias": "OS",
      "score": 0.92,
      "isNewProposal": false
    }
  ],
  "itemCandidates": [
    {
      "candidateId": "item-1",
      "kind": "TASK",
      "title": "xv6 과제 제출",
      "sourceSpan": { "start": 0, "end": 18 },
      "action": "제출",
      "object": "xv6 과제",
      "confidence": 0.9
    }
  ],
  "relationCandidates": [],
  "ambiguityReasons": [],
  "providerMetadata": {
    "analyzerVersion": "fake-v3",
    "promptVersion": "none",
    "localModelVersion": "none",
    "embeddingModelVersion": "none",
    "routingPolicyVersion": "field-policy-v1",
    "toolCalls": 0
  }
}
```

The server must validate this against both JSON Schema and domain rules. Unknown enum values and stale revisions are rejected. The five required version strings in `providerMetadata` contain 1–64 characters and must exactly match the server-owned analyzer and routing provenance; `toolCalls` is a required integer from 0 through 100. Provider-specific extra metadata is allowed only inside the metadata object. Before schema validation, the compact serialized proposal is capped at 65,536 UTF-8 bytes (64 KiB) and `providerMetadata` at 8,192 UTF-8 bytes (8 KiB).

## Application

The review UI sends selected candidate values, not the raw model response as an instruction to execute.

The backend:

1. re-checks owner and memo revision;
2. validates selected tags and dates;
3. creates application event;
4. writes derived items, task records, and relations in one transaction;
5. records provenance for each derived value;
6. commits the transaction; after success, the client refetches the task and graph projections.

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
