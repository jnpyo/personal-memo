# AI analysis pipeline

## Objective

Turn unstructured memo text into a reviewable proposal while minimizing latency, cloud cost, privacy exposure, and irreversible mistakes.

The first implementation must support a mock analyzer. Real model selection comes after the domain flow and evaluation harness exist.

## Current implementation status

The repository implements the deterministic Milestone 2 path plus one guarded, personal-overlay
local-model fallback:

- a revision-context Korean date parser with explicit `UNKNOWN` fallback;
- versioned 12-case regression and 12-case `VISIBLE_CHALLENGE` Korean memo suites, a version-2
  fixture contract with date mention/surface and item/source-span gold, and a raw-content-free
  deterministic baseline report;
- an enum-based ambiguity gate that routes to `LOCAL_REVIEW` or `CLOUD_ENRICH`;
- `FakeAnalyzer` and a default no-network, no-tool, mutation-free `FakeCloudAnalysisGateway`;
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
  owner-scoped internal ledger row, with at most the dispatch's `max_attempts` rows; a returned result
  is `STARTED`, executor rejection is definitively `NOT_STARTED`, and a submitted termination without
  an observed start remains `UNKNOWN`; local termination, remote result, process loss, stale
  finalization, and fenced-out completion keep separate truth;
- V19 raw-free fallback evidence: versioned deterministic decision shape and reason codes plus model
  contribution status/semantic changed fields are stored without copying memo, prompt, or response;
- V20 personal invocation evidence and approved-type hints: `model-invocation-v1` preserves the
  semantic route separately from `UNCERTAINTY_ONLY`/`AI_PREFERRED`; an opted-in personal dispatch
  snapshots at most three current-memo UTF-16 anchors derived from same-owner type-corrected or
  user-resolved Apply rows and scrubs the offset-only JSON at finalization;
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
- a separate owner-scoped, count-only model-evidence summary over all recent analysis runs and their
  optional dispatch. It reads only server-owned enums/counts and fixed JSON-containment booleans,
  remains lazy in the UI, and cannot mutate routing or promote a rule.
- an explicitly invoked external blind harness that accepts only an outside-repository,
  independently human-curated version-2 release and emits aggregate-only Fake-analyzer metrics from
  a clean, pinned commit. No blind dataset or passing metric threshold is included in this repository.
- strict preparation contracts for two independent version-2 review manifests and an ID-only
  version-3 TASK-due binding overlay. No real manifests, human adjudication, version-3 dataset,
  binding score, or passing result exists; `EVALUATION_LABEL_POLICY.md` remains a draft.
- an explicitly selected, test-only Solo LiquidAI runner family that calls an already-installed model
  only through its fixed localhost Ollama endpoint and evaluates only the 24 public synthetic
  fixtures. It preserves v1–v5 direct generation, v6 guarded selection, v7-A output-cap, v7-B
  prompt-overhead, and v8-A compact-wire evidence separately. Every runner remains outside the
  product analyzer/gateway, database, API, review, and Apply paths.
- a preserved deterministic guarded skill evaluation that keeps `FakeAnalyzer` authoritative and validates a
  deterministic projection. V6, v7-A, v7-B, and v8-A accepted no LiquidAI contribution and fell back
  for all 24 cases; after the compact-wire diagnostic the LiquidAI shadow decision is `NO_GO` and the
  direct authoritative model decision remains `NO_GO`.
- a personal-overlay-only `ollama-local-gateway-v2` that uses `AI_PREFERRED` after the deterministic proposal
  is fully validated, uses `LOCAL_MACHINE_MEMO_CONTENT`, and accepts `KEEP` or a narrow exact-substring semantic patch over
  grounded candidates. Redirects, proxies, tools, endpoint/model/digest drift, truncation, and
  schema/domain violations fail closed to revalidated local detailed review. The adapter may receive
  a bounded textual `thinking` field from Ollama, validates its size, then ignores it; only the
  visible `content` is parsed under the strict JSON/schema/domain contract. Non-text, oversized, or
  additional response fields still fail closed. Only a default-`RECORD` fallback is normalized to
  `UNKNOWN`; existing explicit grounded candidates are preserved.
- a permanent, explicitly invoked product-path smoke that compares the fixed three-case public
  synthetic fixture through isolated Fake and exact LiquidAI stacks. It uses tmpfs PostgreSQL and an
  owned `127.0.0.1:11435` Ollama, calls only register/memo/analysis-run/proposal-read APIs, emits only
  a strict aggregate receipt after cleanup, and never calls Apply or a personal/external product
  service. Its 2026-08-28 result is `PASS_NARROW_PRODUCT_PATH` but semantic improvement is
  `NOT_DEMONSTRATED`; it leaves provider/training/LoRA `NO_GO` and RAG unused.

The normal application default remains Fake plus `UNCERTAINTY_ONLY`, while `compose.personal.yaml`
pins the only enabled Ollama/LiquidAI adapter to `http://host.docker.internal:11434`, the reviewed
exact model identity, `AI_PREFERRED`, and approved-correction anchors.
No cloud provider is connected. The public-fixture test runner remains separate from this adapter.
There is no consent grant/revoke HTTP API. The analysis HTTP operation remains
synchronous: its caller waits for final review or stale-revision handling even though the gateway
attempt runs in a bounded in-process executor outside database transactions. The production profile
also enables a bounded periodic recovery worker, so a committed dispatch can resume after a caller
interruption or process restart without changing the public contract. V17 persists internal
per-attempt lifecycle and monotonic local elapsed evidence, but does not expose it through the public
POST, DTOs, proposal, `providerMetadata`, UI, or evaluation report. The current Fake has no model, so
any local termination observation is `NOT_APPLICABLE` with null model-token and cost numbers even
when execution start is uncertain; an observation-free process loss remains `UNKNOWN`. The adapter's
`num_predict=1024` is a provisional hidden-reasoning budget required for an observed STOP response;
it does not relax the separately enforced bounded visible model-output, HTTP response, or canonical
proposal sizes. Real-model numeric usage/cost
reporting, aggregation, budget enforcement, related-memo retrieval, fuzzy/vector search, and
embeddings remain unimplemented. The roadmap's real-provider adapter remains deferred by the
project decision until explicitly authorized.

Milestone 5 now also has a user-facing exact lexical memo-search endpoint. That read-only product
search is not an analyzer input or Agent tool: it receives a user query in a JSON POST body, returns
bounded owner-scoped current-raw/latest-applied canonical results with no-store semantics, and never
copies its query or results into `CloudAnalysisRequest`, a proposal, provider metadata, browser
storage, or a dispatch context. It adds no fuzzy/vector/embedding retrieval and does not broaden the
V16 exact tag-context boundary.

## Pipeline boundaries

```text
Raw memo
  → deterministic extraction
  → deterministic local classification
  → schema, domain, and owner-reference validation
  → deterministic field-level ambiguity assessment
      → default mode: local proposal when complete, or
      → personal AI-preferred mode: immutable local gateway binding for every validated proposal
      → immutable gateway binding and transfer-policy gate
          → bounded owner-active exact tag/alias retrieval and deterministic K=8 context
          → optional same-owner APPLIED type-correction lookup and offset-only K=3 anchor snapshot
          → durable run/dispatch prepare commit
          → claim with descriptor/binding comparison, fence, lease, and deadline
          → create one V17 internal ledger row for the claimed fence
           → bounded gateway execution outside a database transaction
               → personal overlay only: exact pinned localhost semantic patch
          → record local observation and revision/fence-rechecking finalize
              → typed enrichment result or validated local fallback, or
              → committed STALE result
  → final proposal validation and cloud-run evidence, when reviewable
  → synchronous HTTP result
  → user review, when current
  → transactional application
```

Analysis and application are separate operations. No model is allowed to write canonical domain data directly.
Model input exists only in bounded execution memory. V19 evidence, attempt rows, provider metadata,
ordinary logs, browser storage, and any training dataset receive no copied raw memo/prompt/response.
User-corrected Apply selections are not learned automatically. The personal overlay may use only
the V20 bounded, type-only inference hint authorized by ADR 0008; recurring rule promotion still
requires reviewed public synthetic positive/negative fixtures. Fine-tuning, LoRA, vector storage,
and background RAG ingestion remain prohibited.
The Solo LiquidAI shadow runner is not a product-runtime branch of this pipeline: it does not enter
an application service, public API, persistence, review, or Apply path, and it reads no PostgreSQL or
personal memo. In test scope it intentionally reuses the production `FakeAnalyzer`, canonical-schema
and domain validators, routing policy, and evaluation code so the comparison exercises those shared
boundaries without publishing a product proposal.

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

`korean-rules-v8` resolves an explicit `오늘|내일|모레 + 오전|오후 + 1–12시` expression with optional
minutes against the immutable revision's capture instant and source zone as `RELATIVE_EXACT`, but
only when the local time has one unambiguous valid offset. For the date-less explicit clock family
with no particle or `에`—bare 1–12시 with optional minutes, explicit 오전/오후, Korean 24-hour clock,
and `HH:mm`—it enumerates capture-day local occurrences, keeps only instants strictly after capture,
and emits the earliest safe occurrence. Equality is not future. A DST-gap occurrence is discarded
and a later unique same-day occurrence may be used; any future overlap occurrence fails the whole
expression closed as `UNKNOWN`. No safe remaining occurrence or missing/invalid source zone also
stays `UNKNOWN`; the resolver never rolls forward to tomorrow. The result is still proposal-only and
cannot create an alarm/reminder or canonical data without the ordinary manual Apply.

### Reference ambiguity

- unresolved expressions such as `그거`, `저번 것`, `교수님이 말한 것`
- several equally plausible referenced memos

### Action ambiguity

- task-like verb without an object
- object/date with no clear action
- several actions mixed in one memo

The current deterministic gate thresholds and structured-proposal reconstruction rules belong to
routing policy `field-policy-v2`; changing a gate threshold, cloud-signal set, or reconstruction
rule requires a new policy version. Lexical classification, reference extraction, date parsing,
default-fallback coverage detection, guarded affirmative `접속하기`, and explicit TASK due binding are
separately identified by `fake-v10` and `korean-rules-v8`; changing
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

For the personal Ollama adapter, `gatewayVersion` is the adapter version plus the prompt-contract
version, `providerId` is `ollama-local@<exact model tag>`, and `modelVersion` is the exact 64-hex model
digest. Changing any of those changes the durable binding and closes recovery before another model
call. The chat request also pins top-level `truncate=false` and `shift=false`; context overflow must
fail instead of silently discarding input.

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
`IN_FLIGHT` row for a run. A returned gateway result always means execution `STARTED`; its typed
`UNAVAILABLE` outcome is therefore an observed result. A local `EXECUTOR_REJECTED` observation is the
definitive `NOT_STARTED` case for executor submission, and its remote result remains `UNKNOWN`.
After submission, timeout, caller interruption, and unexpected local termination record `STARTED`
when start was observed and otherwise `UNKNOWN`; they never convert an unobserved start into
`NOT_STARTED`, and they do not claim a provider result. Process loss likewise claims no provider
result. Late completion from an obsolete fence is retained as `FENCED_OUT` instead of overwriting the
run, and revision change during the current attempt records `STALE_FINALIZE`.

Observed attempts use a monotonic local clock for non-negative elapsed milliseconds. This measures
only the current process's submit/wait interval. Timeout and interruption retain that local duration
but keep remote result truth `UNKNOWN`; an unobserved process loss has unknown duration and null
milliseconds, and its model-token/cost evidence is also `UNKNOWN`. While a row is in flight,
model-token and cost evidence is `PENDING`. A local termination observation for the model-free
`NO_NETWORK` Fake is `NOT_APPLICABLE` with null numeric fields regardless of execution-start
uncertainty. A model-backed attempt is `NOT_APPLICABLE` only when execution is definitively
`NOT_STARTED`; uncertain execution or remote completion is `UNKNOWN`. An observed real-model result is
currently `NOT_REPORTED` with null numbers because the gateway result contract does not carry usage or
price. The database validates a future `REPORTED` numeric shape, but no runtime path writes model-token
or cost numbers in this checkpoint and zero is never substituted for missing evidence.

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
continue reviewing proposals after a server upgrade. The current PWA sends `3`. A max-v2 client
receives stored v2 unchanged and stored v3 with its EVENT candidate/suggestion fields removed; a
max-v1 client additionally receives no date IDs or TASK due references. Historical v1/v2 proposals
are never synthesized upward. Downgrade changes only an in-memory copy, never rewrites JSONB/hash,
and responses use `no-store` plus `Vary: X-Analysis-Proposal-Schema-Version`.

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
    "analyzerVersion": "fake-v10",
    "deterministicRulesVersion": "korean-rules-v8",
    "promptVersion": "none",
    "localModelVersion": "none",
    "embeddingModelVersion": "none",
    "routingPolicyVersion": "field-policy-v2",
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

### Proposal schema v3 EVENT contract preparation

Schema v3 adds two required item fields without changing the current producer:

```json
{
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
  "suggestedEventScheduleCandidateId": null
}
```

Every reference is proposal-local by ID. Version 1 and version 2 forbid both fields. Non-EVENT items
must carry `[]` and null in v3. TIMED candidates reference only `EXACT_TIME` or `RELATIVE_EXACT` dates;
ALL_DAY candidates reference only `DATE_ONLY`. A nullable end declares either
`EXCLUSIVE_AT_VALUE`, meaning its candidate is already the canonical exclusive boundary, or
all-day-only `INCLUSIVE_THROUGH_VALUE`, whose normalized exclusive boundary is the following calendar
day. Overflow, a non-later normalized end, dangling/imprecise/mode-incompatible references, duplicate
IDs or semantic alternatives, and multiple distinct alternatives without `CONFLICTING_DATES` fail
domain validation. A missing end remains null.

The nullable suggestion exists so a future policy cannot silently select by list position or score.
The current domain gate rejects every non-null suggestion. Current `fake-v10` and the localhost
semantic-patch adapter remain v2 producers. The PWA can display a v3 list but every EVENT review draft
still starts unscheduled; only a user action copies one candidate into the editable selection. Apply
remains explicit and selection schema v2.

The separate EVENT temporal-label overlay contract and validator are structural preparation only.
They contain no checked-in labels, reviewer evidence, adjudication, metric result, numeric threshold,
or `PASS`; the existing dataset-v3 TASK-due overlay grants no EVENT authority. Independent human
policy approval, two independent reviews and adjudication, predeclared thresholds, a held-out release,
source-zone-aware prefill validation, and a separate activation decision are required before any v3
producer or review preselection.

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
2. validates selected tags, items, dates, and proposal-index-only relation selections;
3. replaces each validated due `timeZone` compatibility input with the locked immutable memo
   revision's source time zone before canonical task persistence;
4. resolves every selected index from the locked stored `relationCandidates` array, requires the
   exact `sourceCandidateId` to map to one applied item, and ignores no selected candidate;
5. locks each owner-owned `ACTIVE` MEMO/TAG target in deterministic order and fails closed if the
   review label became stale;
6. creates the application event and writes items, task records, tags, tag links, and confirmed
   item-scoped typed relations in one transaction;
7. records the resolved relation execution fields and source-item mapping in `selection_json`, while
   score remains provenance only in the immutable proposal rather than execution authority;
8. commits the transaction; after success, the client refetches the task and current MEMO_TAG graph
   projections.

The request contains only `proposalIndex`, never a client-asserted target/type/score. An explicit
empty selection applies the other reviewed fields while rejecting all relation candidates; omission
is accepted only for an older client when the proposal relation array is already empty. Undo deletes
the application's relation rows before their source items and preserves target memo/tag identities.
These canonical item-to-MEMO/TAG relations are intentionally absent from the current graph: mapping
four directed relation types onto MEMO/TAG graph edges is a separate product decision. Fake output,
evaluation fixtures, and `review-default-v3` remain unchanged; any non-empty relation proposal,
temporal-candidate-bearing v3 proposal, or schedule-bearing selection stays `UNCLASSIFIABLE` in
review-outcome evidence.

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

`review-default-v3` reconstructs v2/v3 TASK drafts from explicit due references and retains the former
conservative projection only for recoverable v1 proposals. Missing, unused, imprecise, or
type-incompatible date mappings require user resolution rather than a guessed due. It does not turn
proposal-v3 EVENT alternatives or suggestions into a schedule default.

`EXACT` means only that the user applied the default selection without a semantic change. It is not
an AI correctness label. `CORRECTED` records changed type/title/tag/item/due fields;
`USER_RESOLVED` records a proposal that lacked a directly applicable default, such as a tied or
`UNKNOWN` type or no item; `UNCLASSIFIABLE` is the fail-closed bucket for relation-bearing proposals,
temporal-candidate-bearing v3 proposals, schedule-bearing selections, unknown historical shapes,
inconsistent revisions, or other comparisons the policy cannot prove. Relation and schedule Apply
are supported, but `review-default-v3` has no
adjudicated relation-selection or EVENT temporal target. Reject and postpone state also must not be
treated as corrected gold labels: rejected runs do not store a corrected target, and a later
transition overwrites the current `POSTPONED` state.

The server reads a 1,001st row only to enforce a hard 1,000-proposal cardinality bound. It returns an
explicit error instead of publishing a silent partial aggregate. This row cap is not a serialized-byte
or JVM-heap bound: the current query materializes proposal and selection JSON for the bounded cohort.
Before public multi-owner expansion, add a fail-closed byte guard for historical JSON and process
classification in tested batches or a bounded stream. Results are grouped by server-owned route and
analyzer/prompt/local-model/embedding-model/routing-policy provenance and are served with
`Cache-Control: no-store`.

This evidence makes personal review behavior observable, but it does not open the authoritative or
external-provider LLM gate. The V19 personal adapter remains `SOLO_PROVISIONAL`/`REPORT_ONLY`.
V15/V16/V17 plus the bounded production recovery worker supply durable pre-call, descriptor-bound,
context-snapshotted, attempt-observed, out-of-transaction configured-gateway execution and restart recovery;
they do not authorize a real provider or expose the internal dispatch contract over HTTP. Completed independent
adjudication of the
version-2 date/item gold, an approved and independently reviewed version-3 binding label
policy/dataset, a separately held blind release
with a pre-registered gate, approved provider/region/retention/cost limits, a consent grant/revoke UX
and API, provider-side token deduplication, and the remaining criteria in
[EVALUATION.md](EVALUATION.md) are still required. Real-provider usage/cost collection and budget
enforcement, an approved attempt-retention/purge policy, related-memo retrieval, fuzzy/vector search,
and embedding context also remain separate work.

## Read-only analysis path evidence

`GET /api/v1/analysis-path-evidence/summary` is separate from review-outcome comparison. Its rolling cohort
starts from the authenticated owner's `analysis_runs.created_at` rows and left joins the optional
dispatch, so both dispatched and non-dispatched analyses remain countable. A hard 1,000-run bound
fails without returning a partial aggregate. The half-open UTC interval is exactly `days × 24`
hours; the unordered 1,001-row sentinel avoids sorting an oversized cohort because every returned
counter is order-independent and no partial result is ever served.

The repository selects only lifecycle, current/legacy local-decision versions, invocation
mode/reason, local-model contribution enum, approved-correction signal count, and fixed booleans for
configured dispatch routes, V19 fallback reasons, and changed fields. The exact built-in Fake tuple
is matched inside SQL; descriptor strings do not leave the repository. The query does not select the
memo, proposal, selection, validated local proposal, evidence JSON, provider output, any
ID/hash/offset/token/credential, or a per-run value. The response is a closed count-only DTO served
with `Cache-Control: no-store`.

The route counters distinguish a recorded local-model configuration, external-memo-transfer
configuration, exact built-in Fake, and legacy/other paths. A configured path, dispatch row,
`PENDING`, or `LOCAL_FALLBACK` does not prove that a model was attempted. Only local-model-route
`ACCEPTED_CHANGED` and `ACCEPTED_UNCHANGED` say that a successful result was accepted into the
proposal; neither says that the result is correct or better. A nonzero approved-correction snapshot
means only that bounded server-owned signals were fixed to the dispatch, not that the model used
them.

The corresponding “분석 경로 진단” UI is collapsed and sends no request until the owner first opens
it. Reopening does not silently fetch again; an explicit `진단 새로고침` creates a new point-in-time
read.

This source slice adds no migration and does not invoke a model, alter `UNCERTAINTY_ONLY` or
`AI_PREFERRED`, learn or promote a rule, call Apply, or create RAG/training data. It remains
`SOLO_PROVISIONAL`/`REPORT_ONLY`; personal deployment requires a separate owner-authorized rebuild
and product smoke.

## Personalization without fine-tuning

MVP/P1 personalization uses stored behavior rather than model fine-tuning.

- canonical tag aliases
- accepted and rejected tag candidates
- common type corrections
- default date/time policy
- frequent tag co-occurrence
- user-specific routing thresholds when enough data exists

Do not train a model on a user's notes during the MVP.
The visible, unadjudicated 24-case public synthetic shadow set is also excluded from future training
and validation data. Every preserved v1–v8-A shadow report decides `NO_GO_FOR_TRAINING`; v2–v8-A also
record LoRA `NO_GO`. No fine-tuning or training tool was installed, invoked, or authorized. The user
has chosen not to pursue fine-tuning or LoRA. V7-A increased the output cap, v7-B reduced prompt
overhead, and v8-A used a strict compact wire, but all three still ended LENGTH 24/24 with zero
accepted LiquidAI contribution. The authoritative LiquidAI shadow decision is therefore `NO_GO`.
The later personal semantic-patch path is a separate user-approved
`SOLO_PROVISIONAL`/`REPORT_ONLY` boundary. ADR 0008 additionally permits bounded inference-time
approved-type anchors without creating a corpus or trainer; deterministic rule hardening remains
the dependency-reduction path. Broader public/de-identified RAG is not automatic
follow-up: first identify a retrieval-solvable need, then use an explicit source allow-list and fixed
document, retrieval-count, and context budgets. The current truncation is not such a need, so RAG was
not used. It cannot retrieve personal memos, the personal PostgreSQL database, or canonical product
state. Every comparison remains proposal-only, no-Apply, schema/domain validated, Fake-compared,
`SOLO_PROVISIONAL`, and `REPORT_ONLY` until a separate product decision changes those boundaries.

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

`fake-v10` / `korean-rules-v8` keeps the generalized weekday/time, approximate-date, reference,
event, and multi-intent rules while extracting sequential item facets and source-aligned UTF-16
spans from the immutable raw revision without copying a challenge sentence. It recognizes guarded
affirmative `접속하기` as a TASK action while keeping negative or descriptive forms unresolved. It
keeps the explicit relative-day meridiem rule and adds the bounded capture-day future-occurrence
policy above. A supported date-less explicit clock becomes precise only when one safe capture-day
occurrence remains strictly after the revision instant and no future overlap occurrence exists;
otherwise it stays `UNKNOWN`.
It also emits
proposal-local date IDs and only structurally safe TASK due references. Evaluation dataset v2 and
its report still have no binding gold, so the capability is
`SUPPORTED_NOT_SCORED_DATASET_V2`; binding quality cannot be a hard metric until independently
adjudicated version-3 labels are frozen. The version-2 report exposes date
mention/item/item-source-span failures as well as route/type/signal metrics, including missing spans
rather than silently treating them as success. The public visible challenge and the semantic quality
rates remain report-only; they are not a blind or general Korean accuracy claim. The generated
public report contains case identifiers and labels for transparent diagnostics, never fixture or
personal memo text.

### Solo LiquidAI shadow diagnostic

`SoloLiquidAiShadowBaselineRunner` is excluded from normal Maven/CI selection and requires an
external orchestrator to establish the exact loopback endpoint, pinned model/digest and source
preflight, device sampling, and process/resource cleanup. The runner uses only the 12 regression and
12 `VISIBLE_CHALLENGE` public synthetic cases, compares LiquidAI and Fake independently against the
same fixed gold, and writes aggregate-only reports. It never reads the personal database, uses a
product API, persists a proposal, or invokes Apply.

#### Baseline v1 (preserved)

The preserved v1 report is `backend/target/evaluation/solo-liquidai-shadow-baseline.json`, SHA-256
`360660c5e283f719465262088e91b168a88dea27944a0e61c5fcd065a830b020`. It is labeled
`SOLO_PROVISIONAL`, `REPORT_ONLY`, and `NOT_CONFIGURED`. Of 24 scored requests, 18 returned completed
responses and passed the narrow inference schema; 3/24 assembled proposals passed the canonical
schema and 1/24 passed domain validation. LiquidAI produced 9 wrong-local cases, 11 invented precise
dates, 1 missing overflow signal, and 2 unresolved-field hallucinations. Successful-response wall
latency was p50 `15451.417 ms`, p95/max `33236.766 ms`, and mean `17431.567 ms`, versus Fake p50
`0.453 ms` and p95 `1.581 ms` over 24 cases. Ollama observed `3166835834` allocated bytes and context
length `8192`.

The external device-wide sampler recorded 853 samples, baseline `3501 MiB`, peak `6990 MiB`, peak
utilization `92%`, and post-run `3543 MiB`; these are non-exclusive device observations and cannot be
attributed solely to the model. The decision at this stage was `NO_GO_FOR_TRAINING`, with
prompt/schema iteration `RECOMMENDED`. V8-A later closed that historical path with LiquidAI `NO_GO`
and skill-only as the current default. The fixtures are visible, not independently human-adjudicated,
not blind, and not training data; the run authorizes no product adapter, provider, or fine-tuning.

#### Prompt/schema iteration v2

The completed v2 development run kept the exact installed
`hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0` model at digest
`677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822`, the same visible 12+12
public synthetic fixtures, sequential no-retry/no-tool execution, canonical JSON Schema, production
domain validation, proposal-only boundary, and Fake comparison. It changed only test-side
prompt/inference-schema and integrity controls. The report
`backend/target/evaluation/solo-liquidai-shadow-baseline-v2.json` has SHA-256
`7507690bc6f80c937f382ce428a210540cede1fde621249b5441755b18cb4f26`; companion postflight/isolation
evidence is recorded in `solo-liquidai-shadow-baseline-v2-attestation.json`, and the frozen execution
source bundle has SHA-256 `2f19402e7ee004de93a4508fecd6b55f344445ce381636742de00b55bd79e76d`.

The v2 result remains `SOLO_PROVISIONAL`, `REPORT_ONLY`, `NOT_CONFIGURED`, and
`PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY`. LiquidAI returned 24/24 responses; 20/24 passed the
inference schema, 20/24 the canonical schema, and 10/24 production domain validation. Route accuracy
was `0.541667`, with 4 wrong-local cases, 0 invented precise dates, 0 local-overflow cases, 1 missing
overflow signal, and 0 unresolved-field hallucinations. Failure-category counters were 4 inference,
4 canonical, and 14 domain invalid observations (22 overlapping category observations, not 22 unique
cases). Fake passed canonical schema and domain validation 24/24, route accuracy was `1.0`, and all
listed safety-error counters were zero.

LiquidAI all-attempt minimum/p50/p95/max/mean wall latency was
`9896.043`/`16754.523`/`24241.698`/`24655.245`/`17540.866 ms`; successful-response latency was
`9895.774`/`16754.176`/`24241.137`/`24654.879`/`17540.185 ms`. Fake latency was
`0.377`/`0.547`/`11.795`/`114.698`/`5.872 ms`. The device was an NVIDIA GeForce RTX 5080 with driver
`610.88` and `16303 MiB` total memory. Ollama reported `3166835834` bytes entirely in VRAM at context
length `8192`; the report correctly leaves peak VRAM/utilization `NOT_AVAILABLE`. The attestation's 9
coarse device-wide manual samples observed baseline `3197 MiB`, maximum used `6671 MiB`, and maximum
utilization `89%`. They are neither process/model-exclusive nor a peak claim.

The test-only v2 inference schema deliberately made `dueDateCandidateId` and `sourceSpan` null-only,
so both are `DISABLED_NULL_ONLY_IN_SHADOW_V2`, not demonstrated capabilities; relation output was
disabled as an empty proposal array, and tag ranking was not scored. The attested network path was
`MACHINE_LOCAL_DOCKER_HOST_BRIDGE`: runner `127.0.0.1:11435` to a container-local relay, then
`host.docker.internal:11435` at expected host gateway `192.168.65.254`, with Windows Ollama listening
only on `127.0.0.1:11435` and no published container port. This is not OS-level internet-egress
isolation, and the attestation makes no such claim.

Postflight recorded runner/relay exit, zero loaded models, unchanged exact model tag/digest, no owned
Ollama process or listener, removed scoped temporary directory, and no persisted Ollama logs. Within
the observed runner code/process/network scope, product HTTP calls, canonical reads/writes, and Apply
were all zero; this is not a claim about unrelated machine history. The strict public-visible
development acceptance is `NOT_MET`, not `PASS` or provider readiness. Training remains
`NO_GO_FOR_TRAINING`, LoRA is `NO_GO`, and no training/fine-tuning, product adapter, provider use,
target-phone readiness, or release gate is authorized.

#### Prompt/schema iterations v3 and v4 (preserved)

The later test-only iterations preserved v1/v2 rather than replacing them. The v3 report
`backend/target/evaluation/solo-liquidai-shadow-baseline-v3.json` is `33530` bytes with SHA-256
`f6d6e8de0fc7aad342c0bd68487f1e416f922c75e6ba87cd8463c9b990468fa8`; its companion is
`solo-liquidai-shadow-baseline-v3-attestation.json`. The v4 report
`backend/target/evaluation/solo-liquidai-shadow-baseline-v4.json` is `34697` bytes with SHA-256
`ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5`; its companion is
`solo-liquidai-shadow-baseline-v4-attestation.json`. Both remain `SOLO_PROVISIONAL`, `REPORT_ONLY`,
`NOT_CONFIGURED`, acceptance `NOT_MET`, training `NO_GO_FOR_TRAINING`, and LoRA `NO_GO`.

V4 completed response and inference-schema validation `24/24`, but semantic IR, canonical schema,
and domain validation were only `1/24` each. It recorded 69 failure observations over 23 unique cases
with 46 overlaps, 23 wrong-local cases, 0 invented precise dates, 1 local-overflow case, and 1 missing
overflow signal. All-attempt p50/p95 latency was `22542.110`/`30973.996 ms`. These preserved
development observations are not `PASS` results.

#### Atomic-slot prompt/schema iteration v5

The finalized v5 report is
`backend/target/evaluation/solo-liquidai-shadow-baseline-v5.json`, `35035` bytes, SHA-256
`ba9c069d85c038d5c5603f8ddddfeae03aa8778cca7a949180142fee9b873102`. Companion postflight and
restoration evidence is `solo-liquidai-shadow-baseline-v5-attestation.json`; its digest is
intentionally not embedded in this documentation. The run kept the same model/digest, visible 24-case
public synthetic set, sequential no-retry/no-tool mode, canonical JSON Schema, production domain
validation, proposal-only/no-Apply boundary, and Fake comparison. It did not read or change personal
memos, PostgreSQL, canonical data, product APIs, or provider configuration.

| Metric | Fake | LiquidAI v5 |
| --- | ---: | ---: |
| scored requests / responses | 24 / 24 | 24 / 24 |
| inference-schema valid | not a Fake boundary | 24 / 24 |
| semantic IR valid | not a Fake boundary | 8 / 24 |
| canonical-schema valid | 24 / 24 | 8 / 24 |
| domain valid | 24 / 24 | 7 / 24 |
| route accuracy | 1.0 | 0.375 |
| wrong-local | 0 | 16 |
| invented precise date | 0 | 2 |
| local overflow | 0 | 1 |
| missing overflow signal | 0 | 1 |

V5 recorded 49 overlapping failure observations across 17 unique cases: semantic IR invalid 16,
canonical-schema invalid 16, and domain invalid 17, with overlap count 32. LiquidAI all-attempt
p50/p95/max/mean latency was `17172.783`/`31117.602`/`31305.739`/`18804.994 ms`. Ollama reported
`3166835834` allocated bytes at context length `8192`. The non-exclusive device sampler collected 906
samples with 0 misses: baseline/first/last/maximum used memory was
`3260`/`3243`/`3249`/`7196 MiB`, and maximum observed utilization was `93%`. These device-wide values
are not process/model-exclusive, and the model-exclusive peak remains `NOT_AVAILABLE`.

Compared with v4, semantic/canonical/domain validity improved `1/1/1→8/8/7`, failure observations
fell `69→49`, unique failed cases `23→17`, wrong-local `23→16`, and p50
`22542.110→17172.783 ms`. Invented precise-date errors worsened `0→2`, p95 changed
`30973.996→31117.602 ms`, and both overflow findings remained. The v5 attestation records restored
runner, relay, model allocation, listener/process, and scoped temporary-resource state, unchanged
exact model tag/digest, and zero product HTTP, canonical read/write, and Apply activity in the observed
scope. Acceptance is therefore still `NOT_MET`, training `NO_GO_FOR_TRAINING`, and LoRA `NO_GO`.
V5 does not authorize a product adapter, provider, training, or automatic Apply.

#### Deterministic guarded skill v6

The completed v6 report is
`backend/target/evaluation/solo-liquidai-deterministic-skill-v6.json`, exactly `45708` bytes with
SHA-256 `a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f`. Companion postflight and
restoration evidence is `solo-liquidai-deterministic-skill-v6-attestation.json`; its digest is
intentionally not embedded here. The execution is public-visible
`SOLO_PROVISIONAL`/`REPORT_ONLY`/`PUBLIC_VISIBLE_DEVELOPMENT_ONLY`, not blind or independently
human-adjudicated evidence.

`FakeAnalyzer` remained the authoritative proposal producer. `ShadowDeterministicSkill` validated
and projected that proposal, while LiquidAI could only select one already-existing item-title ordinal
for `/suggestedTitle/value`; topic ordinals were diagnostic and could not mutate proposal fields.
Invalid model envelopes were rejected as a whole and used the skill/Fake fallback without repair or
retry. All 24 model selection requests ended as `MODEL_TRUNCATED_RESPONSE`: completed response,
schema-valid selection, accepted model contribution, and title improvement counts were all `0`, while
rejection and fallback counts were both `24`.

Fake, skill-only, and LiquidAI-guarded arms each passed canonical schema and production domain
validation `24/24`, route accuracy `1.0`, with wrong-local, safety-error, Fake-to-skill deep mismatch,
and model protected-mutation counts all `0`. `GuardedSystem MET` therefore measures only the
deterministic Fake/skill fallback boundary and gives LiquidAI no validity credit. Model contribution
acceptance is `NOT_MET`, and development acceptance is also `NOT_MET`.

P95 wall latency was Fake `9.509 ms`, deterministic skill projection `0.923 ms`, model selector
`491.271 ms`, and end-to-end `497.976 ms`. Ollama reported `2977033092` bytes allocated in VRAM at
context length `2048`. The run read or changed no personal memo, personal PostgreSQL, canonical data,
product API, or Apply path; RAG was `false`. Fine-tuning and LoRA were not performed, no training tool
was installed, training remains `NO_GO_FOR_TRAINING`, and LoRA remains `NO_GO`. Companion evidence
records runner/relay exit, an unloaded model, zero Ollama process/listener, Docker Desktop restored to
its original `OFF` state, an unchanged canonical Docker fingerprint, and removed scoped temporary
resources.

V6 did not demonstrate LiquidAI success or authorize a product adapter, provider, training, or
automatic Apply. The subsequent bounded diagnostics are recorded below.

#### Output-cap diagnostic v7-A

V7-A changed only the selector prediction cap from `64` to `128`. Its report is `5925` bytes with
SHA-256 `5b6a578b2b2222fc6180a4f70af7718526ccce2e127b070a404477a30c19d20f`; the companion
attestation is `7874` bytes with SHA-256
`bccc6a0856ea9055f199d381e7be28e0e8587373687ab1d148f3617e69c4c617`. STOP/LENGTH/accepted/
fallback counts were `0/24/0/24`, prompt tokens were `9765`, and selector p95 was `923.668 ms`.
Increasing the cap did not produce one completed envelope.

#### Prompt-overhead diagnostic v7-B

V7-B reduced prompt input to `5973` tokens, `3792` fewer than v7-A and `158` fewer per case. Its
selector p95 was `823.686 ms`. The report is `7081` bytes with SHA-256
`c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37`; the attestation is
`9743` bytes with SHA-256 `ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d`.
STOP/LENGTH/accepted/fallback remained `0/24/0/24`, so this run demonstrates overhead reduction only,
not model contribution.

#### Compact-wire diagnostic v8-A

V8-A used a strict compact `{v,p,t}` selection wire and an unmodified deterministic mapper. The
final report is `backend/target/evaluation/solo-liquidai-compact-wire-diagnostic-v8a.json`, `11150`
bytes with SHA-256 `bd9f4419fb26b8a2950b80722eef746fff41e4418a8c52ccb94aafc7333365e3`.
Its companion `solo-liquidai-compact-wire-diagnostic-v8a-attestation.json` is `12184` bytes with
SHA-256 `97e7c67a9a1f01140be7ad25734ce7080002b367ea7c87772c8a4c8287b4cdab`.

All 24 attempts reached the evaluation cap of `128` and ended LENGTH: STOP/LENGTH/accepted/fallback
were `0/24/0/24`. Prompt tokens were `6093`, only `120` total and `5` per case above v7-B. Fake p95
was `10.131 ms`; selector p95 was `855.907 ms`, a ratio of `84.482×`. Guarded schema and domain
validation remained `24/24` through deterministic fallback, and leakage and protected-mutation
counters were zero. These validity values are not attributed to LiquidAI.

The non-exclusive device-wide sampler collected 59 observations with no misses: baseline/max used
memory was `3033`/`6175 MiB` and maximum utilization was `92%`. No model-exclusive peak is claimed.
The run performed no fine-tuning, LoRA, RAG, personal-memo/PostgreSQL/canonical access, product API,
or Apply. Postflight restored Ollama, Docker Desktop, model/listener/process, and scoped temporary
resources to their original state.

V7-A cap expansion, v7-B overhead reduction, and v8-A compact wire all failed to resolve 24/24
truncation. The final diagnostic decision is `NO_GO`; product/provider readiness is false, training
remains `NO_GO_FOR_TRAINING`, and LoRA remains `NO_GO`. The default path is deterministic skill-only
hardening for authoritative behavior. ADR 0007's later personal semantic-patch fallback does not
change this diagnostic verdict or grant training/provider authority. RAG is deferred unless a separate retrieval-solvable requirement is documented and then
may use only a bounded allow-listed public/de-identified corpus.

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
