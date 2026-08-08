# AI analysis pipeline

## Objective

Turn unstructured memo text into a reviewable proposal while minimizing latency, cloud cost, privacy exposure, and irreversible mistakes.

The first implementation must support a mock analyzer. Real model selection comes after the domain flow and evaluation harness exist.

## Current implementation status

The repository now implements the model-free portion of Milestone 2:

- a revision-context Korean date parser with explicit `UNKNOWN` fallback;
- versioned 12-case regression and 12-case `VISIBLE_CHALLENGE` Korean memo suites, a version-2
  fixture contract with date mention/surface and item/source-span gold, and a raw-content-free
  deterministic baseline report;
- an enum-based ambiguity gate that routes to `LOCAL_REVIEW` or `CLOUD_ENRICH`;
- `FakeAnalyzer` and a no-network, no-tool, mutation-free `FakeCloudAnalysisGateway`;
- Draft 2020-12 contract, domain, and owner-reference validation before routing and again after enrichment;
- server-side reconstruction of field-level routing signals instead of trusting only an analyzer summary;
- general Korean action/reference/event rules plus all-weekday relative date/time parsing, while
  approximate weekends and event-relative deadlines remain null-valued review candidates;
- server-owned analyzer, prompt, local-model, embedding-model, and routing-policy provenance for both `LOCAL` and `HYBRID` runs while every result remains `REVIEW_REQUIRED`;
- required, bounded proposal metadata and UTF-8 payload limits before anything is persisted;
- explicit user resolution of `UNKNOWN` types and partial item application;
- a raw-content-free, owner-scoped review outcome summary derived read-only from stored proposals
  and latest validated selections.
- an explicitly invoked external blind harness that accepts only an outside-repository,
  independently human-curated version-2 release and emits aggregate-only Fake-analyzer metrics from
  a clean, pinned commit. No blind dataset or passing metric threshold is included in this repository.

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

The current deterministic gate thresholds and structured-proposal reconstruction rules belong to
routing policy `field-policy-v1`; changing a gate threshold, cloud-signal set, or reconstruction
rule requires a new policy version. Lexical classification, reference extraction, and date parsing
are separately identified by `fake-v4` and `korean-rules-v2`; changing those inputs does not rename
an otherwise unchanged gate. Runtime configuration and user-specific thresholds remain a later
milestone.

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
    "analyzerVersion": "fake-v4",
    "deterministicRulesVersion": "korean-rules-v2",
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
3. fails closed with `PROPOSAL_RELATIONS_UNSUPPORTED` when `relationCandidates` is non-empty,
   because explicit relation selection, canonical relation persistence, and relation undo are not
   implemented yet;
4. creates the application event only after that boundary passes;
5. writes the currently supported derived items, task records, tags, and tag links in one
   transaction;
6. records provenance for each derived value;
7. commits the transaction; after success, the client refetches the task and graph projections.

The fail-closed relation boundary prevents a future analyzer from making a run appear `APPLIED`
while silently dropping proposed relationships. It creates no partial canonical records and does not
alter the raw memo. Relation application remains a separate vertical slice with its own selection
contract, persistence, ownership constraints, and application-scoped undo.

## Read-only review outcome evidence

`GET /analysis-review-outcomes/summary` derives a rolling owner-scoped aggregate without giving the
analyzer, cloud gateway, or an Agent any write capability. The query is based on
`analysis_proposals.created_at`, reads only the authenticated owner's rows, selects the latest
application per proposal, and returns aggregate counts and server-owned run provenance. It does not
return or log memo text, titles, raw proposal/selection JSON, or domain identifiers and does not add a
general clickstream.

The endpoint keeps three independent dimensions:

- current mutable run state, including current `POSTPONED`, `APPLIED`, `REJECTED`, and `STALE`;
- latest application state (`NONE`, `APPLIED`, or `UNDONE`), including undo followed by reapply;
- semantic comparison of the latest validated selection with a versioned reconstruction of the
  default review draft (`EXACT`, `CORRECTED`, `USER_RESOLVED`, or `UNCLASSIFIABLE`).

`EXACT` means only that the user applied the default selection without a semantic change. It is not
an AI correctness label. `CORRECTED` records changed type/title/tag/item/due fields;
`USER_RESOLVED` records a proposal that lacked a directly applicable default, such as a tied or
`UNKNOWN` type or no item; `UNCLASSIFIABLE` is the fail-closed bucket for unsupported relations,
unknown historical shapes, inconsistent revisions, or other comparisons the current policy cannot
prove. Reject and postpone state also must not be treated as corrected gold labels: rejected runs do
not store a corrected target, and a later transition overwrites the current `POSTPONED` state.

The server reads a 1,001st row only to enforce a hard 1,000-proposal cardinality bound. It returns an
explicit error instead of publishing a silent partial aggregate. This row cap is not a serialized-byte
or JVM-heap bound: the current query materializes proposal and selection JSON for the bounded cohort.
Before public multi-owner expansion, add a fail-closed byte guard for historical JSON and process
classification in tested batches or a bounded stream. Results are grouped by server-owned route and
analyzer/prompt/local-model/embedding-model/routing-policy provenance and are served with
`Cache-Control: no-store`.

This evidence makes personal review behavior observable, but it does not open the real-LLM gate.
Independent adjudication of the version-2 date/item gold, a separately held blind release with a
pre-registered gate, provider privacy/consent/cost/failure boundaries, and the remaining criteria in
[EVALUATION.md](EVALUATION.md) are still required.

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

The executable baseline and provider-entry gate are specified in [EVALUATION.md](EVALUATION.md).
The current version has 12 regression cases and a 12-case `VISIBLE_CHALLENGE` split with fixed gold
for:

- type candidates
- explicit/ambiguous date interpretation
- existing/new tag behavior
- unresolved references
- multi-intent split
- escalation decision
- normalized date value/precision/time and UTF-16 source spans
- acceptable item sets and title/action/object/source-span fields

Track precision of high-confidence local routing separately from overall accuracy. The primary safety metric is the rate of wrong local decisions that were presented as unambiguous.

`fake-v4` generalizes weekday/time, approximate-date, reference, action, event, and multi-intent rules
without copying a challenge sentence. The version-2 report now exposes date mention/item/item-source-span
failures as well as route/type/signal metrics, including missing spans rather than silently treating
them as success. The public visible challenge and the semantic quality rates remain report-only;
they are not a blind or general Korean accuracy claim. The generated public report contains case
identifiers and labels for transparent diagnostics, never fixture or personal memo text.

The separately held blind boundary is documented in [EVALUATION.md](EVALUATION.md). It is local and
explicit only, uses the deterministic `FakeAnalyzer` without network access, and writes a different
aggregate-only report with no per-case identifiers, results, labels, hashes, spans, paths, or raw
text. Its metric status stays `NOT_CONFIGURED` until humans pre-register the release policy and
thresholds before examining candidate output.
