# Product decisions and remaining questions

This file separates decisions already reflected in code from choices that still require product or
operational approval. Resolved decisions are not implementation prompts.

## Resolved for the current checkpoint

### Client platform

- Android Chrome mobile-first installable PWA is the first client.
- iOS-specific support and a native wrapper are later options, not current requirements.

### User model and authentication

- Local email/password and optional Google OpenID Connect resolve to one internal user UUID and one
  PostgreSQL-backed server session.
- Account linking is explicit and never inferred from an email match alone.
- Local registration and Google authentication are independent capabilities. Creating a brand-new
  internal user through Google is a separate, fail-closed deployment decision.
- The seeded `LEGACY_UNCLAIMED` owner remains migration-compatible and is never automatically
  attached to the first account that signs in.
- A fresh private database is initialized with a dedicated one-time, interactive, non-web command.
  Its PostgreSQL singleton gate is transactionally locked and preserved by backup/restore; it does
  not temporarily enable either registration path.
- Private-PC identity metadata may live only in the ignored local environment. The account password
  is never accepted through that file, an argument, HTTP, browser storage, model, or Agent tool.

### Canonical data authority and offline boundary

- PostgreSQL is canonical for the MVP.
- The PWA may preserve an owner-scoped raw capture draft locally so navigation or a temporary
  connection loss does not erase typed text.
- A local draft is not a canonical memo and is not an offline mutation queue. Full bidirectional
  synchronization, conflict resolution, and retry outbox behavior remain P1.

### Date policy

- Canonical instants are stored in UTC and retain the source IANA time zone.
- A date without a time remains `DATE_ONLY`; it is not converted to an arbitrary UTC deadline.
- Omitted-year and approximate expressions remain explicit candidates for user review.
- `OVERDUE` is derived from the current time or local date and `TODO`; it is never persisted.

### Confirmation and Agent authority

- Every analysis result is an untrusted proposal and receives user review. A non-success cloud
  outcome bypasses concise approval and opens detailed review.
- No task, tag, relation, reminder, or graph artifact becomes canonical before explicit approval.
- The current Fake gateway has no tools. Any future Agent/model tools remain read-only before
  confirmation.

### Graph and taxonomy

- Graph nodes are memos and canonical topic tags.
- Memo type, task state, overdue state, and similar system concepts are memo metadata, filters, and
  icons rather than global hub nodes.
- Existing tags are selectable suggestions; new names remain proposals requiring confirmation.
- Automatic tag merge/split, node compression, and taxonomy migrations remain deferred.
- The first user-facing search slice is exact and owner-scoped: current raw body/latest valid applied
  title use normalized literal substring, active canonical tag/alias uses exact normalized equality,
  and a result opens current raw detail without changing the graph. Fuzzy/semantic ranking and
  cluster reveal remain decisions after measured usage.

### Notification and offline scope

- Task state is in the MVP.
- Web Push, reminder dispatch, and full offline synchronization are P1 or later.

### AI provider boundary

- The current checkpoint uses deterministic/Fake implementations behind stable local and cloud
  interfaces.
- Proposal schema version 2 identifies date candidates and lets TASK item candidates reference one
  precise due-date candidate explicitly. Historical version-1 proposals remain recoverable, and no
  binding becomes canonical before the ordinary approval API succeeds.
- Proposal response compatibility is resolved by explicit negotiation: no header/`1` gives strict
  v1, the current PWA requests `2`, stored proposals are never rewritten, and due persistence always
  uses the immutable memo revision's source time zone rather than the approval device's zone.
- V13 resolves the storage/enforcement shape for external memo-content consent: the authenticated
  owner must have boolean true, the exact descriptor policy version, and a non-null grant timestamp.
  Legacy boolean-only grants are revoked. `NO_NETWORK` needs no consent; an unconsented,
  mismatched, other-owner, revoked, or future-dated `EXTERNAL_MEMO_CONTENT` grant makes zero gateway
  calls because `granted_at` must not be later than the authorization-check instant.
- Gateway transfer mode and gateway/provider/model/consent-policy versions are server-owned. Every
  new run records those values and a bounded outcome. Typed failure, exception, or invalid cloud
  output stores only a revalidated local proposal as `HYBRID`/`REVIEW_REQUIRED`, exposes no provider
  error text, changes no canonical data, and opens detailed review.
- Every new LOCAL, cloud-success, and fallback proposal rebuilds `providerMetadata` from one bounded
  server allow-list, so a provider cannot retain arbitrary metadata fields.
- No consent grant/revoke HTTP API, actual external provider, or Ollama/LiquidAI adapter is
  configured. Related-memo retrieval, fuzzy/vector search, embeddings, real-model numeric
  usage/cost reporting and budget enforcement remain open work.
- V14 stores an internal final-run authorization/grant snapshot and deterministic provider-request
  token for gateway calls, while legacy rows remain explicitly unsnapshotted. V15 now commits a
  provider-call-only dispatch before execution, binds descriptor and executor identity, claims work
  with a fence and lease, runs each claimed attempt outside the database transaction, and finalizes
  only after rechecking the memo revision and fence. Historical rows receive no invented dispatch.
- V16 resolves at most 10 proposal tag candidates and 20 distinct normalized terms against only the
  authenticated owner's active tag names/aliases using exact normalized equality. It resolves
  uniqueness from the complete result before deterministic K=8 selection. The result is an internal
  hint; final owner/reference validation remains authoritative. It reads no raw or related memo and
  uses no fuzzy/vector/embedding retrieval.
- V15 same-key caller recovery remains available. The production profile also enables a bounded
  scheduler that every 30 seconds selects at most 25 `PREPARED` or expired-lease `RUNNING` rows using
  only DB-selected owner/idempotency evidence. It uses the existing owner+operation+raw-key advisory
  lock, skips live leases, and reuses the same binding/fence/deadline, V16 DB context snapshot,
  out-of-transaction Fake call, revision-rechecking finalize, and provider token after process
  restart. Retrieval is never rerun for recovery, so the same token cannot receive different context.
  V16 scrubs context raw at `FINALIZED` but retains hash/version/count; existing V15 rows remain
  `none`/`0`/null raw/null hash. This is bounded at-least-once execution, so an eventual provider must
  deduplicate by that token. The internal raw key, prepared payload/context and its evidence, token,
  binding, fence, lease, and queued/running states remain absent from public synchronous HTTP and
  recovery DTOs, proposal JSON or `providerMetadata`, ordinary logs, browser storage, and
  service-worker caches.
- V17 versions new dispatches as `gateway-attempt-v1` and records at most `max_attempts` owner-scoped
  fence rows. Historical dispatches remain `attempt_history_version=none` with no backfilled rows.
  A gateway result is `STARTED`; executor rejection proves `NOT_STARTED` and is distinct from a
  gateway-returned `UNAVAILABLE`. After submission, timeout, caller interruption, or unexpected local
  termination is `STARTED` when start was observed and otherwise `UNKNOWN`, never definitive
  `NOT_STARTED`; these terminations and process loss keep remote result truth `UNKNOWN`. An
  obsolete-fence observation preserves only actually observed truth and cannot overwrite the run.
  Observed local elapsed uses a monotonic clock, while observation-free process-loss duration and
  usage/cost remain unknown. A local termination observation for the model-free Fake remains
  `NOT_APPLICABLE`/null even when execution start is uncertain. A future real-model attempt is
  `NOT_APPLICABLE` only when definitively `NOT_STARTED`, `UNKNOWN` when execution or remote completion
  is uncertain, and `NOT_REPORTED` after a result until the gateway contract reports usage and price.
  The ledger is absent from public
  DTO/proposal/metadata/UI/eval
  report/log/browser/service-worker boundaries, stores no provider text/ID/token/raw/context, and has
  no independent TTL before an approved purge policy.
- No real local model or cloud provider is selected or connected without a separate product,
  privacy, evaluation, and cost decision.
- Public regression and `VISIBLE_CHALLENGE` fixtures are diagnostic synthetic data, never blind
  evidence. An external blind harness may consume only an independently human-curated version-2
  release outside Git and emits aggregate-only output from a clean, pinned commit.
- Evaluation dataset version 2 has no date-to-item binding labels. Binding support is therefore
  reported as `SUPPORTED_NOT_SCORED_DATASET_V2`; a separately reviewed, independently adjudicated
  version-3 label policy is required before binding quality can become a gate.
- A strict two-reviewer version-2 manifest schema/verifier and an ID-only version-3 binding overlay
  integrity validator are preparation only. There are no real human manifests, completed
  adjudication, version-3 dataset, binding score, or `PASS`, and `EVALUATION_LABEL_POLICY.md` remains
  a human-unapproved draft.
- The external harness currently has no metric `PASS` state. A curator/reviewer must approve the
  release, adjudication policy, sample size, and thresholds before the first candidate run.

## Decisions required before a public self-service launch

### Access and account lifecycle

- Will public Google account creation use an allowlist, invitations, or explicitly enabled open
  registration?
- Will public deployments retain the private one-account bootstrap only for an operator, or replace
  it with an audited invitation/administrative provisioning workflow?
- What verification and delivery provider will support local email verification and password reset?
- Are MFA/passkeys required, and how are all sessions revoked after credential recovery?
- What proof and retention rules govern complete account deletion?

### Cloud provider and budget

- allowed provider and regions;
- which approved provider/region and transfer/retention/deletion policy may use the existing exact
  consent pin, and what audited user-facing grant/revoke API and wording will manage it;
- monthly budget and per-request context/token limits;
- numeric usage/cost source of truth, aggregation rules, and attempt-ledger retention/purge policy;
- maximum accepted latency, retry behavior, and outage policy.
- who independently curates and adjudicates the blind release, and which metric thresholds are
  frozen before any candidate output is inspected.

### Deletion, retention, and export

- trash retention duration;
- analysis/idempotency/session retention periods;
- whether a public/shared-device release should keep owner-scoped raw drafts in browser storage,
  add user-visible draft clearing, or require a stronger at-rest protection policy;
- deletion timing for future embeddings;
- user export format and backup retention requirements.

### Operations

- public domain and trusted HTTPS edge;
- secret manager and credential rotation procedure;
- separate Flyway/migration and least-privilege application database roles;
- IP/edge rate limiting and abuse response;
- monitoring, alerting, backup schedule, restore objectives, and recovery-drill ownership.

## Decisions after measured usage

- Exact local classifier and embedding model, based on target-phone benchmarks and licensing review
- Maximum accepted model download size and WebGPU/WASM fallback policy
- Vector search implementation and when pgvector is justified
- Tag promotion/merge/split thresholds
- Node activity score and compression timing
- External calendar integration
- Native app strategy
