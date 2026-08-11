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
- a server-owned `CloudGatewayDescriptor` and typed `CloudAnalysisResult` boundary, exact
  owner/policy/timestamp consent enforcement for `EXTERNAL_MEMO_CONTENT`, and persisted transfer,
  gateway, provider, model, consent-policy, and outcome evidence;
- V14 internal authorization/grant snapshot and deterministic provider-request token evidence for
  actual gateway calls, without adding either value to the proposal or HTTP contract;
- V15 durable cloud preparation: a descriptor-bound executor identity, validated-local payload and
  hash, timeout, attempt ceiling, deadline, fence, and lease are committed before gateway execution;
  the bounded invocation runs outside the database transaction and finalization locks and rechecks
  the memo revision and fence before publishing one proposal;
- V16 bounded tag context: at most 10 tag proposals yield at most 20 distinct normalized
  canonical-name/alias terms; an owner-scoped query matches only active tags by exact normalized
  equality, the complete match set is used for unique-tag resolution, and a deterministic maximum of
  8 candidates is supplied as an internal gateway hint;
- V17 bounded attempt evidence: each claimed fence for a new `gateway-attempt-v1` dispatch gets one
  owner-scoped internal ledger row, with at most the dispatch's `max_attempts` rows; executor
  rejection, gateway-returned failure, timeout, interruption, process loss, stale finalization, and
  fenced-out completion keep separate local/remote truth;
- production-profile bounded recovery: every 30 seconds, a scheduler selects at most 25
  `PREPARED` or expired-lease `RUNNING` dispatches, obtains owner and the existing idempotency key
  only from owner-consistent database rows, and reuses the same claim/invoke/finalize lifecycle;
- validated-local fallback for missing consent, typed failures, gateway exceptions, and invalid
  cloud proposals, with no provider error text and detailed UI review instead of a concise approval;
- Draft 2020-12 contract, domain, and owner-reference validation before routing and again after enrichment;
- server-side reconstruction of field-level routing signals instead of trusting only an analyzer summary;
- general Korean action/reference/event rules plus all-weekday relative date/time parsing, while
  approximate weekends and event-relative deadlines remain null-valued review candidates;
- server-owned analyzer, prompt, local-model, embedding-model, and routing-policy provenance for both
  `LOCAL` and `HYBRID` runs. A durable `HYBRID` run may be internally `QUEUED` or `RUNNING`; each
  non-stale finalized proposal remains `REVIEW_REQUIRED`;
- one common allow-list canonicalizer for every new LOCAL, cloud-success, and fallback
  `providerMetadata` object, plus UTF-8 payload limits before anything is persisted;
- explicit user resolution of `UNKNOWN` types and partial item application;
- a raw-content-free, owner-scoped review outcome summary derived read-only from stored proposals
  and latest validated selections.
- an explicitly invoked external blind harness that accepts only an outside-repository,
  independently human-curated version-2 release and emits aggregate-only Fake-analyzer metrics from
  a clean, pinned commit. No blind dataset or passing metric threshold is included in this repository.
- strict preparation contracts for two independent version-2 review manifests and an ID-only
  version-3 TASK-due binding overlay. No real manifests, human adjudication, version-3 dataset,
  binding score, or passing result exists; `EVALUATION_LABEL_POLICY.md` remains a draft.

No real local model, Ollama/LiquidAI adapter, or cloud provider is connected. There is no consent
grant/revoke HTTP API or external provider configuration. The analysis HTTP operation remains
synchronous: its caller waits for final review or stale-revision handling even though the gateway
attempt runs in a bounded in-process executor outside database transactions. The production profile
also enables a bounded periodic recovery worker, so a committed dispatch can resume after a caller
interruption or process restart without changing the public contract. V17 persists internal
per-attempt lifecycle and monotonic local elapsed evidence, but does not expose it through the public
POST, DTOs, proposal, `providerMetadata`, UI, or evaluation report. The current Fake has no model, so
locally observed model-token and cost status is `NOT_APPLICABLE` with null numbers; an unobserved
process loss remains `UNKNOWN`. Real-model numeric usage/cost
reporting, aggregation, budget enforcement, related-memo retrieval, fuzzy/vector search, and
embeddings remain unimplemented. The roadmap's real-provider adapter remains deferred by the
project decision until explicitly authorized.

## Pipeline boundaries

```text
Raw memo
  → deterministic extraction
  → deterministic local classification
  → schema, domain, and owner-reference validation
  → deterministic field-level ambiguity assessment
      → local proposal, or
      → immutable gateway binding and consent gate
          → bounded owner-active exact tag/alias retrieval and deterministic K=8 context
          → durable run/dispatch prepare commit
          → claim with descriptor/binding comparison, fence, lease, and deadline
          → create one V17 internal ledger row for the claimed fence
          → bounded gateway execution outside a database transaction
          → record local observation and revision/fence-rechecking finalize
              → typed enrichment result or validated local fallback, or
              → committed STALE result
  → final proposal validation and cloud-run evidence, when reviewable
  → synchronous HTTP result
  → user review, when current
  → transactional application
```

Analysis and application are separate operations. No model is allowed to write canonical domain data directly.

## Local analyzer contract

The current backend exposes a provider-independent `LocalAnalyzer` interface. A future on-device
implementation may expose a frontend interface conceptually equivalent to:

```ts
interface LocalAnalyzer {
  analyze(input: LocalAnalysisInput): Promise<LocalAnalysisResult>;
}
```

Current deterministic `LocalAnalyzer` inputs include memo id/revision, raw content, the immutable
revision's base time, and IANA time zone. V16 tag/alias retrieval happens later at the internal cloud
gateway boundary, so it is not a `LocalAnalyzer` input. User defaults, related-memo context,
embeddings, and a browser worker are future inputs, not current behavior. The broader target contract
may include:

- memo id and revision
- raw content
- created-at/base time
- IANA time zone
- user date defaults
- canonical tag candidates and aliases
- tag centroid/version metadata

Current Fake analyzer output includes the structured proposal candidates and bounded provenance
described below. Embedding similarities, related-memo top-k, token use, and cost are not analyzer
output. V17 separately persists gateway-attempt local elapsed time; this is monotonic process-local
execution evidence, not model output or end-to-end product latency. The V16 exact tag/alias context is
a server-derived gateway hint, not analyzer output.
The broader target output may include:

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

## Future embedding use — not implemented

When implemented, embedding is used to retrieve candidates, not to fully understand or generate the
final domain record. The current checkpoint has no embedding storage, vector/semantic or related-memo
retrieval, or centroid update job. V16's bounded exact tag/alias lookup is lexical, internal, and not
an embedding feature.

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
rule requires a new policy version. Lexical classification, reference extraction, date parsing, and
explicit TASK due binding are separately identified by `fake-v6` and `korean-rules-v4`; changing
those inputs does not rename an otherwise unchanged gate. Runtime configuration and user-specific
thresholds remain a later milestone.

The server unions these derivable signals with the analyzer-declared `ambiguityReasons`. Nested
date reasons must also appear in the proposal summary, and structural omissions such as a new tag,
an unknown type, or an incomplete task cannot be hidden by clearing that summary. A proposal may
show at most five date candidates; detecting more adds `CANDIDATE_LIMIT_EXCEEDED` before display
truncation so the overflow never silently takes the local route.

`analysis_runs.ambiguity_reasons` stores the immutable server assessment that caused routing.
`analysis_proposals.proposal_json.ambiguityReasons` stores the final proposal reasons, which a
future provider may resolve or refine. This separation preserves why a `HYBRID` run occurred.
The provider-independent `CloudAnalysisRequest` also carries those authoritative reasons, the policy
version, a defensive copy of the validated local proposal, and the bounded V16 tag context. The
context is a hint only; it never grants a provider a canonical write tool, includes hidden raw memo
text, or replaces final owner/reference validation.

### Routing result

```text
LOCAL_REVIEW      local proposal is sufficient
CLOUD_ENRICH      server gateway enrichment path for specific fields
USER_INPUT_NEEDED cloud result still cannot safely resolve critical fields
PENDING_OFFLINE   cloud is needed but unavailable
```

Only `LOCAL_REVIEW` and `CLOUD_ENRICH` are executable routing results today. `USER_INPUT_NEEDED` and
`PENDING_OFFLINE` remain conceptual; failures on the cloud-enrichment branch persist a validated
local proposal for detailed review rather than creating either route.

Thresholds are configuration, not hard-coded business truth. Calibrate them using a Korean rough-note evaluation set and real confirmation data.

## Current cloud gateway, consent, and fallback boundary

`CloudAnalysisRequest` carries a defensive copy of the already validated local proposal, the
server-reconstructed routing reasons and routing-policy version, the descriptor used for this
decision, the accepted authorization snapshot when external transfer is allowed, the bounded V16
tag context, and an opaque server-issued provider-request token whose string/log representation is
redacted. The request DTO, provider result, browser, and memo text cannot choose owner identity, this
token, the retrieval context, or canonical write authority. Context candidates are hints only; final
proposal validation remains authoritative for owner and reference integrity.

Before a `CLOUD_ENRICH` call, the configured server adapter supplies one immutable
`CloudGatewayBinding`: a `CloudGatewayDescriptor` plus the executor that may run that descriptor.
The descriptor contains `transferMode`, `gatewayVersion`, `providerId`, `modelVersion`, and
`consentPolicyVersion`; its deterministic binding ID is persisted before execution. These values and
the final `cloudOutcome` are stamped by the application service into `analysis_runs`; a gateway
response cannot spoof them through proposal metadata.

- `NO_NETWORK` needs no user consent. The current `FakeCloudAnalysisGateway` uses this mode,
  performs no external transfer, has no tools, and returns a defensive copy.
- `EXTERNAL_MEMO_CONTENT` requires the authenticated owner's `cloud_analysis_consent=true`, an exact
  descriptor-policy match, and a non-null grant timestamp no later than the authorization-check
  instant (`granted_at <= authorizationInstant`). Missing, mismatched, another-owner, revoked, or
  future-dated consent produces `CONSENT_REQUIRED` and the gateway method is never called.
- V13 revokes every legacy boolean-only grant because it did not pin an accepted policy. It also
  requires policy and timestamp to be both present for a true grant and both null for a false grant.
- The repository has no public grant/revoke API and no actual external provider configuration, so
  this is a fail-closed integration boundary rather than provider authorization.
- V14 introduced the final internal execution-evidence shape. LOCAL and descriptor failure have no
  authorization/token values; a `NO_NETWORK` call has only a deterministic token; denied external
  transfer has only its authorization-check instant; an allowed external call has that instant, the
  exact accepted grant timestamp, and the token. Historical rows remain `legacy-v0` with no invented
  snapshot, while V15 call-ready rows use the same evidence under `durable-v1`.
- V15 stores provider-call-only preparation in `analysis_run_dispatches`. Existing V14 and older
  runs receive no synthetic dispatch row because none was committed before its historical call.
- V16 derives context only from the authenticated owner's active tags and aliases. It examines at
  most 10 tag proposals and at most 20 distinct normalized terms, matches exact normalized equality
  only, resolves uniqueness from the complete query result, and then deterministically keeps K=8.
  It does not inspect raw memo text or related memos and performs no fuzzy, vector, embedding, Ollama,
  or real-provider operation.

`CloudAnalysisResult` is either a defensive success proposal or a typed failure reason
(`UNAVAILABLE`, `TIMEOUT`, `RETRY_EXHAUSTED`, `PROVIDER_ERROR`, or `UNEXPECTED_FAILURE`). Binding or
execution exceptions become `UNEXPECTED_FAILURE`, and a success proposal that fails
schema/domain/owner validation becomes `INVALID_RESPONSE`. No exception or provider error text is
copied into an API response, proposal, run, or UI notice.

Every non-stale, non-success branch minimizes untrusted provider metadata, stamps bounded server
evidence, validates the original local proposal again, and stores it as `HYBRID` /
`REVIEW_REQUIRED`. Raw memo and canonical tag/task/relation data remain unchanged. The PWA opens the
detailed alternatives editor for every reviewable cloud outcome other than `SUCCESS` or
`NOT_REQUIRED`; `CONSENT_REQUIRED` has a specific safe notice and all other failures share one
generic notice. If the memo changes or is trashed before a claim, finalization uses
`CANCELLED_STALE`; if it changes during an in-flight call, the run becomes `STALE` while retaining
the bounded outcome of that completed attempt. Neither stale branch creates canonical data.

V15 commits an `analysis_runs` row as `QUEUED` / `PENDING` and an
`analysis_run_dispatches` row as `PREPARED` before gateway execution. The preparation reserves the
proposal identity and same-key request, retains the validated local proposal plus its integrity
hash, and pins the binding ID, timeout, maximum attempts, and deadline. V16 additionally stores the
bounded context's serialized raw value, SHA-256 hash, version, and candidate count before any gateway
call. A separate claim transaction rechecks the current revision, current external-transfer consent,
and binding; it then changes the
run to `RUNNING`, increments a fence, and records a bounded lease. The bound executor runs through a
fixed-capacity invocation pool outside the database transaction while the public HTTP caller still
waits. A final transaction locks the run and dispatch, rejects an obsolete fence, rechecks the memo
revision, writes exactly one final proposal, and clears the prepared proposal text while retaining
its hash. It also scrubs the serialized context while retaining its hash, version, and count as
integrity evidence. Existing V15 dispatches remain truthful legacy rows with version `none`, count
`0`, and null context/hash rather than receiving invented context.

V17 adds `attempt_history_version` to the dispatch. Existing dispatches are backfilled only with
`none`; no historical attempt rows are invented. Every newly prepared gateway dispatch uses
`gateway-attempt-v1`, and each successful claim inserts one owner-scoped row keyed by run and fence.
The application admits no more rows than the persisted `max_attempts` and keeps at most one
`IN_FLIGHT` row for a run. A local `EXECUTOR_REJECTED` observation means execution did not start and
the remote result is `UNKNOWN`; it is distinct from an observed gateway result whose typed outcome is
`UNAVAILABLE`. Likewise timeout, caller interruption, unexpected local termination, and process loss
do not claim a provider result. Late completion from an obsolete fence is retained as `FENCED_OUT`
instead of overwriting the run, and revision change during the current attempt records
`STALE_FINALIZE`.

Observed attempts use a monotonic local clock for non-negative elapsed milliseconds. This measures
only the current process's submit/wait interval. Timeout and interruption retain that local duration
but keep remote result truth `UNKNOWN`; an unobserved process loss has unknown duration and null
milliseconds, and its model-token/cost evidence is also `UNKNOWN`. While a row is in flight,
model-token and cost evidence is `PENDING`. A locally observed no-model Fake and any execution that
never starts finish as `NOT_APPLICABLE` with null numeric fields. A future
real-model result is currently `NOT_REPORTED` with null numbers because the gateway result contract
does not carry usage or price; a terminated real-model attempt whose remote completion is uncertain
is `UNKNOWN`. The database validates a future `REPORTED` numeric shape, but no runtime path writes
model-token or cost numbers in this checkpoint and zero is never substituted for missing evidence.

If a caller is interrupted or a process stops after a claim, the committed row remains recoverable.
A later request with the same idempotency key still binds the currently configured gateway, requires
the persisted descriptor/binding identity to match, and may reclaim an expired lease within the
persisted attempt ceiling and deadline. In the production profile, a scheduler additionally runs
after an initial 30-second delay and then with a 30-second fixed delay. Each cycle selects at most 25
`PREPARED` or expired-lease `RUNNING` rows. It takes the owner and existing raw idempotency key only
from owner-matched dispatch/run/idempotency joins, verifies the stored key hash, and enters the same
owner + `ANALYSIS_START` + raw-key advisory transaction lock used by callers. A live lease is not
selected, and a row made live by a race is skipped by the claim, so no second call is started for
that lease. One malformed or concurrently changed candidate does not stop the rest of the bounded
cycle.

Both recovery paths reuse the same deterministic `pmr1_...` provider-request token, the V15 binding,
fence, lease, deadline, and the exact V16 context snapshot already stored in the database. Recovery
does not rerun tag retrieval, so one provider-request token cannot be retried with different context.
This is bounded at-least-once execution, not an exactly-once promise; a future external provider must
honor the token as its deduplication identity. The raw idempotency key, prepared payload, provider
token, binding ID, fence, lease, retrieval-context raw/hash/version/count, attempt ledger, and internal
queued/running state remain internal database/application values; they are not added to `RunView`,
proposal JSON or `providerMetadata`, UI, evaluation reports, recovery responses, ordinary logs,
browser storage, or service-worker caches. Attempt rows contain no provider error text, provider/model
identifier, request token, raw memo, or retrieval context. They follow the current run-data retention
boundary until an approved purge policy exists; V17 introduces no arbitrary TTL. The public POST
remains synchronous, and if a same-key live lease
or invocation outlasts its coordination window the caller receives `409 ANALYSIS_IN_PROGRESS` and
may retry the identical key/body. A stale finalization commits before a caller request returns
`409 STALE_MEMO_REVISION`. Real-model numeric usage/cost reporting and aggregation, related-memo
retrieval, fuzzy/vector search, and embeddings are still not supplied.

## Future Agent tools before confirmation — not implemented

- `searchCanonicalTags(query, limit)`
- `searchRelatedMemoItems(query, limit, filters)`
- `getMemoContext(memoItemIds)` with strict ownership and result limits
- `getUserDateDefaults()`
- `resolveRelativeDate(expression, baseInstant, timeZone)`

These are design allow-list candidates only. V16 performs its bounded exact tag/alias lookup directly
inside the application; it is not a callable Agent tool. The current Fake gateway has zero tool calls,
and no search/context tool endpoint is wired to it. Do not expose create, update, merge, delete,
notify, or bulk-rewrite tools before user confirmation.

Any future real-provider orchestration layer must enforce:

- allow-listed tools
- owner scope
- argument validation
- maximum result count
- maximum tool rounds
- token and elapsed-time budget
- cancellation
- structured final-output validation

The current Fake path makes no network call. If a future approved adapter transfers memo content, it
must delimit it as untrusted source data; text inside a memo never overrides system or tool policy.

Proposal schema negotiation changes only the read representation. A client that omits
`X-Analysis-Proposal-Schema-Version`, or sends `1`, receives strict v1 so an installed older PWA can
continue reviewing proposals after a server upgrade. The current PWA sends `2`; new stored proposals
then remain v2 while historical stored v1 remains v1. The downgrade removes only the two v2 binding
fields from an in-memory copy, never rewrites the JSONB/hash, and responses use `no-store` plus
`Vary: X-Analysis-Proposal-Schema-Version`.

## Structured proposal

The current version-2 result contains fields conceptually equivalent to:

```json
{
  "schemaVersion": "2",
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
      "candidateId": "date-1",
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
      "dueDateCandidateId": "date-1",
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
    "analyzerVersion": "fake-v6",
    "deterministicRulesVersion": "korean-rules-v4",
    "promptVersion": "none",
    "localModelVersion": "none",
    "embeddingModelVersion": "none",
    "routingPolicyVersion": "field-policy-v1",
    "toolCalls": 0
  }
}
```

The server validates this against both JSON Schema and domain rules. In schema v2 every date
candidate has a unique proposal-local `candidateId`, and every item has a nullable
`dueDateCandidateId`. A non-null reference must resolve to a precise date candidate and may appear
only on a `TASK`; approximate or unresolved dates remain unbound and force detailed user review.
Historical schema-v1 proposals remain readable. Only that v1 compatibility path retains the former
single-TASK/single-precise-date conservative default, while v2 never infers a due from array order or
cardinality. Unknown enum values and stale revisions are rejected. A non-null item `sourceSpan` is a
non-empty UTF-16 code-unit half-open range `[start, end)` over the exact immutable raw memo revision;
it must stay in bounds and must not split a surrogate pair. The five required version strings in
`providerMetadata` contain 1–64 characters and must exactly match the server-owned analyzer and
routing provenance; `toolCalls` is a required integer from 0 through 100. Although the JSON contract
keeps the metadata object structurally extensible for compatibility, the analysis-start path rebuilds
every new LOCAL, cloud-success, and fallback object from one bounded server allow-list. Cloud success
therefore inherits trusted local provenance rather than arbitrary provider fields. Before schema
validation, the compact serialized proposal is capped at 65,536 UTF-8 bytes (64 KiB) and
`providerMetadata` at 8,192 UTF-8 bytes (8 KiB).

On a `HYBRID` run the server additionally overwrites bounded metadata for cloud transfer mode,
gateway/provider/model/consent-policy versions, outcome, received routing policy/reasons, zero tool
and mutation calls, and resolved fields. Matching columns on `analysis_runs` are authoritative. A
clear `LOCAL` run stores `NOT_REQUIRED`/`none` evidence in the run. Historical pre-V13 `CLOUD` or
`HYBRID` rows are marked `LEGACY_UNKNOWN`, never retroactively described as no-network or successful.
Historical pre-V14 execution rows likewise remain `legacy-v0` without invented authorization or
provider-request evidence, and V15 does not backfill a durable dispatch for any historical run.

## Application

The review UI sends selected candidate values, not the raw model response as an instruction to execute.

The backend:

1. re-checks owner and memo revision;
2. validates selected tags and dates;
3. replaces each validated due `timeZone` compatibility input with the locked immutable memo
   revision's source time zone before canonical task persistence;
4. fails closed with `PROPOSAL_RELATIONS_UNSUPPORTED` when `relationCandidates` is non-empty,
   because explicit relation selection, canonical relation persistence, and relation undo are not
   implemented yet;
5. creates the application event only after that boundary passes;
6. writes the currently supported derived items, task records, tags, and tag links in one
   transaction;
7. records provenance for each derived value;
8. commits the transaction; after success, the client refetches the task and graph projections.

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

`review-default-v3` reconstructs v2 drafts from explicit due references and retains the former
conservative projection only for recoverable v1 proposals. Missing, unused, imprecise, or
type-incompatible v2 date mappings require user resolution rather than a guessed due.

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
V15/V16/V17 plus the bounded production recovery worker supply durable pre-call, descriptor-bound,
context-snapshotted, attempt-observed, out-of-transaction Fake/test execution and restart recovery;
they do not authorize a real provider or expose the internal dispatch contract over HTTP. Completed independent
adjudication of the
version-2 date/item gold, an approved and independently reviewed version-3 binding label
policy/dataset, a separately held blind release
with a pre-registered gate, approved provider/region/retention/cost limits, a consent grant/revoke UX
and API, provider-side token deduplication, and the remaining criteria in
[EVALUATION.md](EVALUATION.md) are still required. Real-provider usage/cost collection and budget
enforcement, an approved attempt-retention/purge policy, related-memo retrieval, fuzzy/vector search,
and embedding context also remain separate work.

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

`fake-v6` / `korean-rules-v4` keeps the generalized weekday/time, approximate-date, reference,
event, and multi-intent rules while extracting sequential item facets and source-aligned UTF-16
spans from the immutable raw revision without copying a challenge sentence. It also emits
proposal-local date IDs and only structurally safe TASK due references. Evaluation dataset v2 and
its report still have no binding gold, so the capability is
`SUPPORTED_NOT_SCORED_DATASET_V2`; binding quality cannot be a hard metric until independently
adjudicated version-3 labels are frozen. The version-2 report exposes date
mention/item/item-source-span failures as well as route/type/signal metrics, including missing spans
rather than silently treating them as success. The public visible challenge and the semantic quality
rates remain report-only; they are not a blind or general Korean accuracy claim. The generated
public report contains case identifiers and labels for transparent diagnostics, never fixture or
personal memo text.

The separately held blind boundary is documented in [EVALUATION.md](EVALUATION.md). It is local and
explicit only, uses the deterministic `FakeAnalyzer` without network access, and writes a different
aggregate-only report with no per-case identifiers, results, labels, hashes, spans, paths, or raw
text. Its metric status stays `NOT_CONFIGURED` until humans pre-register the release policy and
thresholds before examining candidate output.

Preparation now also includes a strict two-reviewer version-2 manifest contract/verifier and an
ID-only version-3 TASK-due overlay contract/integrity validator. They validate release identity,
coverage, references, and aggregate agreement only. They do not create human evidence: no reviewer
manifest, completed adjudication, version-3 dataset, binding score, or `PASS` is checked in. The
governing [label policy](EVALUATION_LABEL_POLICY.md) is explicitly
`DRAFT_REQUIRES_INDEPENDENT_HUMAN_APPROVAL`, so the real-LLM gate remains closed.
