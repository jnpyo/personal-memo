# Implementation roadmap

## Working method

Build vertical slices and keep every checkpoint runnable. Do not begin with a model experiment disconnected from the product flow.

## Current checkpoint

- Milestone 0: complete.
- Milestone 1: complete, including memo lifecycle/recovery, bounded hard-priority graph home,
  accessible node detail/pin interaction, production PWA packaging, and mobile E2E coverage.
- Milestone 5 has started with its first read-only vertical slice: an independently bounded,
  owner-scoped full-corpus MEMO_TAG neighborhood endpoint and drawer navigation from a home tag to an
  off-home memo's current raw detail. It does not complete lexical/fuzzy search, taxonomy evolution,
  compression, or the Milestone 5 exit criteria.
- This is an explicit interim ordering exception, not a claim that Milestones 2–4 are complete. The
  remaining Milestone 2 gates require two independent human reviews and later provider/privacy/cost
  decisions, while Milestones 3–4 require account-delivery or Web Push product choices. The read-only,
  PostgreSQL-only Milestone 5 slices can make bounded user-visible progress without inventing those
  external decisions or opening any real-provider gate.
- Milestone 2: in progress. Korean date policy, versioned regression/`VISIBLE_CHALLENGE` fixtures, a content-free deterministic evaluation report and narrow regression safety gate, runtime schema/domain validation, versioned field-level routing with persisted provenance, provider-independent Fake cloud enrichment, prompt-injection boundaries, `UNKNOWN` user resolution, and raw-content-free owner-scoped review-outcome aggregation are implemented. `fake-v6` / `korean-rules-v4` emits proposal schema v2 with source-aligned sequential items, proposal-local date IDs, and nullable TASK due references; historical schema v1 remains recoverable and `review-default-v3` uses only explicit v2 bindings. V13 adds exact owner/policy/timestamp consent and server-owned cloud evidence, V14 adds internal authorization/grant/token evidence, V15 adds durable prepare/claim/fence/lease/deadline/recovery/finalize mechanics, and V16 adds owner-active exact tag/alias K=8 context with pre-call raw/hash/version/count snapshot and final raw scrubbing. V17 adds `gateway-attempt-v1`: at most one owner-scoped row per claimed fence and no more than `max_attempts`, monotonic local elapsed when observed, explicit executor-rejection versus gateway-result semantics, and truthful `UNKNOWN`/`NOT_APPLICABLE`/`NOT_REPORTED` model-token/cost states. Existing dispatches remain `attempt_history_version=none` with no backfilled attempt rows. The production profile runs the same lifecycle through a 25-row, 30-second bounded recovery scanner. No V17 evidence appears in public POST/DTO/proposal/`providerMetadata`/UI/evaluation-report/log/browser/service-worker contracts, and attempt rows contain no provider text/ID/token/raw/context. `NO_NETWORK` Fake needs no consent; unconsented `EXTERNAL_MEMO_CONTENT` is zero-call; typed failure/exception/invalid output persists a validated local fallback without canonical changes. Evaluation dataset v2 still has no date-to-item binding gold. A local-only static reviewer packet and an external two-manifest aggregate verifier now make the prepared protocol executable without exposing fixture notes or analyzer output, but they cannot create human evidence or prove reviewer identity/independence. Completed human review and resolution, an approved version-3 binding dataset and separately held blind gate, provider/region/consent/retention decisions including attempt purge, related-memo/fuzzy/vector/embedding context, and real-model numeric usage/cost reporting, aggregation, and budget enforcement remain before a real provider decision. No blind `PASS` is claimed. Ollama/LiquidAI and every real provider remain untouched.
- Milestone 2 compatibility hardening now negotiates proposal reads: an absent/`1` schema header returns strict v1 for an installed older PWA, while the current PWA requests `2`; responses are `no-store`/`Vary` and projection never rewrites stored v2 JSON. Apply also canonicalizes due source zones from the immutable memo revision instead of trusting the review device's zone. These are contract/application changes over existing JSONB and task columns, so they require no Flyway migration.
- Authentication hardening: complete for the MVP checkpoint. Local email/password, optional Google OpenID Connect, explicit account linking, PostgreSQL-backed server sessions, CSRF protection, owner identity derived from Spring Security, and a deterministic 5-failure/15-minute local-account lock are implemented. Authentication unit/integration coverage and the full local-account primary browser E2E suite pass.
- Production/deployment hardening: complete for a controlled private checkpoint. Explicit dev/prod Compose overlays, required production database secrets, fail-closed production registration, Secure/SameSite session and CSRF cookies, validated forwarded headers, non-root health-checked images, static verification, and backup/restore/Flyway operating guidance are present.
- Private personal-PC deployment: implemented. A one-time PostgreSQL-locked interactive account bootstrap, a private-LAN HTTPS overlay, local CA/leaf certificate generation, owner-only secret files, guarded Windows operations, logical backups, forward-only database credential rotation, and isolated restore verification are available without exposing Spring Boot or PostgreSQL to the host network.
- Galaxy S24 Ultra readiness is covered by 384/412px touch viewports, landscape overflow checks, safe areas, installability diagnostics, and service-worker cache-boundary E2E tests. Private-LAN HTTPS, local CA trust, login, and the primary flow were also manually verified on the user's S24 Ultra on 2026-08-08. Home-screen installation and keyboard/cutout behavior remain manual checks and are not claimed as completed without an explicit record.
- Google behavior is covered with mocked OIDC claims and authorization-request tests; no real-provider credential round trip is claimed. Email verification, password recovery delivery, separate migration/runtime database roles, IP/edge rate limiting and abuse protection, MFA/passkeys, account deletion, a publicly trusted domain/TLS edge, secret management, monitoring, and automated backup drills remain public-release work.
- Real local/cloud model adapters remain intentionally deferred by the current product decision. No provider is introduced merely to satisfy the original roadmap bullet.
- Metadata/consent hardening now canonicalizes every new LOCAL, cloud-success, and fallback
  `providerMetadata` object through one bounded server allow-list and rejects future-dated grants.
  V14 stores a coherent internal final-run authorization/grant snapshot and deterministic
  gateway-request token. V15 commits the `durable-v1` run and 1:1 dispatch before the call, compares
  the immutable binding and descriptor at claim, executes with a bounded timeout outside the DB
  transaction, and finalizes only after revision/fence rechecks. V16 durably binds the exact bounded
  tag context to that request token and keeps only hash/version/count after finalization. The
  production scheduler reuses that same DB snapshot and lifecycle for a maximum of 25 eligible rows
  every 30 seconds. V17 adds a fence-scoped owner ledger with truthful local duration and explicit
  `STARTED` result, definitive `NOT_STARTED` rejection, uncertain-start `UNKNOWN`, and corresponding
  unknown/not-applicable/not-reported usage/cost states; historical dispatches remain `none` with no
  backfilled attempts. None of the internal evidence, attempt rows, recovery key/context, dispatch
  proposal/binding, or token is exposed through public DTOs, proposal JSON or `providerMetadata`, UI,
  evaluation reports, ordinary logs, browser storage, or service-worker caches.
- The immediate evaluation work is human execution of the prepared independent version-2 review protocol using the clean-commit-pinned scoped static packet and two outside-repository manifests, followed by human resolution, approval, and two-person adjudication of the prepared version-3 binding-label policy/overlay. The packet has no form or manifest generator, and the aggregate runner cannot prove human identity or independence. Only after those labels are frozen may a human-curated separately held blind release use thresholds approved before its first candidate run. Renderer/verifier success, test-only manifests/overlays, review-outcome counts, and structurally valid v2 bindings are preparation evidence, not permission to connect a real LLM.
- Product decisions already reflected in code are recorded as resolved in `OPEN_QUESTIONS.md`; that file now reserves confirmation prompts for genuinely open launch or post-usage choices.

## Authentication hardening slice

### Deliverables

- local registration, sign-in, current-session, and sign-out APIs
- same-account login failure tracking with a non-extending 15-minute lock after five failures and counter reset after successful post-expiry login
- optional Google OpenID Connect sign-in behind configuration
- explicit Google link intent and safe unlink rules; no automatic email-based account merge
- PostgreSQL-backed Spring Session schema and identity tables through Flyway
- `CurrentIdentity` abstraction backed by Spring Security instead of a fixed development UUID
- CSRF-aware React API client and authenticated application shell
- account panel showing available and linked sign-in methods
- mocked-provider integration coverage and local-auth mobile E2E coverage

### Exit criteria

- unauthenticated domain access fails with `401` and all reads and writes remain owner-scoped;
- local and linked Google login resolve to the same internal owner without exposing credentials or provider tokens to browser storage;
- Google configuration can be absent without breaking startup or local login;
- linking requires an authenticated explicit intent, and the last usable login method cannot be removed;
- unknown, invalid-password, and locked-account login attempts share one public error while lock state transitions remain integration-tested;
- session, CSRF, ownership, unit, integration, and primary E2E tests pass without contacting Google.

## Production/deployment hardening slice

### Deliverables

- explicit `compose.dev.yaml` and `compose.prod.yaml` overlays over one base topology
- production database credentials without development defaults and fail-fast auth configuration
- loopback-only production frontend behind a trusted same-origin HTTPS edge
- non-root backend/frontend runtime images, read-only backend filesystem, dropped capabilities, and container health checks
- production Secure session/XSRF cookies and validated forwarded scheme/port propagation
- backend Spotless/SpotBugs verification and production Compose contract validation
- PowerShell deployment, health, backup, separate-project restore, forward Flyway migration, and rollback guidance

### Exit criteria

- a developer can start the development stack only through the documented overlay and a unique project name;
- production configuration refuses missing database secrets, enabled local registration, and incomplete or insecure Google settings;
- production exposes neither PostgreSQL nor Spring Boot directly to the host network;
- image and configuration checks are reproducible without introducing a second datastore or service;
- the checkpoint is described as controlled private deployment, not as a public self-service launch.

## Private personal-use deployment slice

### Deliverables

- a fixed `bootstrap-account` non-web command with interactive, non-echoed password confirmation
- Flyway-owned singleton provisioning state locked in the account-creation transaction
- production registration and Google account creation kept disabled before and after bootstrap
- a personal Compose overlay that publishes only the existing frontend Nginx HTTPS listener to one
  RFC1918 LAN address
- locally generated CA and leaf certificates outside Git, with only the leaf certificate and key
  mounted read-only into the frontend container
- guarded PowerShell commands for initialization, account bootstrap, start, stop, status, logical
  backup, database credential rotation, and isolated restore verification
- S24-oriented safe-area, touch-target, secure-context, manifest, service-worker, and viewport checks

### Exit criteria

- a fresh database can create exactly one local account without opening an HTTP registration route;
- concurrent or repeated bootstrap attempts cannot create another account, and the one-time state
  survives backup and restore;
- PostgreSQL and Spring Boot publish no host ports while the phone receives the PWA and API from one
  trusted HTTPS origin;
- personal values, database credentials, CA private keys, leaf private keys, and dumps remain outside
  Git;
- backup output is checksummed and parseable, and restore verification uses a generated disposable
  project and volume rather than the personal canonical volume;
- credential rotation keeps secret-bearing native diagnostics out of PowerShell error history,
  rejects cross-session concurrent execution, preserves the canonical volume, and verifies
  frontend-proxied health;
- moving to a future server requires a logical database restore and a replacement HTTPS edge, not a
  domain-model fork or a second source of truth.

## Milestone 0 — Decisions and scaffolding

### Deliverables

- resolve the P0 questions in `OPEN_QUESTIONS.md`
- write ADRs for PWA target, data authority, authentication, and AI provider boundary
- create frontend/backend directories
- Docker Compose with PostgreSQL
- backend health endpoint and database migration smoke test
- frontend shell that runs on desktop and target Android Chrome
- format, lint, test, and CI commands

### Exit criteria

- a new developer can start the stack from README instructions;
- frontend reaches backend health endpoint;
- migrations apply to an empty database;
- no production secret is committed.

## Milestone 1 — AI-free vertical slice

### Backend

- memo identity and immutable revision migrations
- analysis run/proposal/application migrations
- tags, aliases, memo items, task details, and applied links
- memo CRUD with idempotent create
- deterministic `FakeAnalyzer`
- proposal review endpoints
- transactional apply and undo
- bounded graph home endpoint with canonical pin/overdue/TODO/due/revision priority, deterministic
  memo/tag budget reservation, and stable tag connectivity ordering
- idempotent owner-scoped active-memo pin mutation
- task list and state update

### Frontend

- graph-home shell
- quick-capture input
- pending/saved/error state
- review chips for type, title, tags, and date
- partial apply and reject
- task side panel/list
- keyboard/touch graph memo/tag rendering, direct-neighbor highlight, and mobile node detail drawer

### Tests

- primary `11.25 OS과제 제출` E2E scenario
- duplicate create/apply
- stale revision rejection
- undo preserves source
- owner isolation
- graph pin replay/mismatch, priority ordering, no-store detail, focus restoration, and current-home
  neighborhood bounds

### Exit criteria

The complete user flow works without a real AI provider.

## Milestone 2 — Deterministic analysis and bounded cloud enrichment

### Deliverables

- Korean date expression representation and policy tests
- tag normalization and alias lookup
- local-result DTO and schema validation
- deterministic ambiguity gate
- cloud provider abstraction with a fake adapter
- provider-independent failure and exact consent boundaries exercised with fake/test adapters
- server-owned cloud descriptor and persisted transfer/gateway/provider/model/policy/outcome evidence
- internal V14 execution snapshot and deterministic request-token shape, exercised only with
  fake/test adapters
- V15 durable pre-call preparation, immutable descriptor/executor binding, internal queued/running
  state, bounded out-of-transaction timeout execution, caller-driven lease/deadline/fence recovery,
  and revision-rechecking finalization
- owner-active exact tag/alias retrieval from at most 10 proposal candidates and 20 normalized terms,
  complete-result unique resolution, deterministic K=8 internal context, and V16 durable
  raw/hash/version/count lifecycle
- structured-output and domain validation
- production-profile bounded background worker and automatic restart recovery
- V17 internal per-attempt fence history, monotonic local duration, remote-result truth, and explicit
  model-token/cost evidence status; actual real-model numeric usage/cost reporting remains separate
- prompt-injection test cases
- read-only owner-scoped review-outcome aggregation with bounded cohort, provenance grouping, and no raw-content response

A real provider adapter is a separately approved follow-up after privacy, evaluation, latency, and
cost limits are defined. It is not an exit criterion for the current checkpoint.

The implemented review-outcome summary does not satisfy that approval by itself. Its `exact` bucket
means only “latest selection matched the versioned default review projection,” rejected runs have no
corrected target, and current `POSTPONED` state is not an append-only event history. Version-2
date/item metrics are reported, while date-to-item binding is supported but not scored because that
dataset has no binding labels. Independent adjudication, a version-3 binding-label dataset, and a
separately held blind release with a pre-registered gate are required before any provider comparison.

The implemented V13 consent/outcome, V14 execution snapshot, and V15/V16/V17 durable dispatch slices do not
authorize a provider. There is no public consent grant/revoke API or external provider configuration.
V15 now commits the accepted evidence and validated local fallback before a call, binds descriptor
lookup to one immutable executor identity, runs the bounded gateway outside database transactions,
and uses claim/lease/fence/deadline plus caller-driven same-key recovery before a revision-rechecking
finalize. V16 derives an owner-active exact tag/alias K=8 hint, snapshots it before the call, and
reuses only that database value during recovery; context never replaces final owner/reference
validation. The transport boundary remains at-least-once after an uncertain crash, so any future
approved provider must honor the durable token idempotently. Production background scheduling and
restart recovery reuse that same lifecycle with a 25-row/30-second bound; they do not provide an
exactly-once guarantee. V17 records at most one internal row per claimed fence, distinguishes
definitive `NOT_STARTED` executor rejection from a `STARTED` gateway-returned `UNAVAILABLE`, and uses
monotonic local elapsed for observed termination. A submitted timeout/interruption or unexpected local
termination is `STARTED` when start was observed and otherwise `UNKNOWN`, never `NOT_STARTED`; these
terminations and process loss leave remote truth unknown. A local termination observation for the
model-free Fake keeps model-token/cost `NOT_APPLICABLE`/null even when start is uncertain, while
observation-free process-loss evidence is `UNKNOWN`. A future real-model attempt is `NOT_APPLICABLE`
only when definitively `NOT_STARTED`, `UNKNOWN` for uncertain execution or remote completion, and
`NOT_REPORTED` for an observed result until reporting exists; real-model numeric
reporting, aggregation, budget enforcement, an approved
attempt-retention/purge policy, related-memo/fuzzy/vector/embedding context, and the remaining privacy,
evaluation, provider/region/retention/budget decisions remain separate gates.

### Exit criteria

- clear fixtures can be reviewed without cloud;
- ambiguous fixtures are escalated for only the flagged fields;
- invalid/stale cloud output cannot alter domain state;
- cloud outage leaves raw memos usable.

## Milestone 3 — On-device analyzer

### Deliverables

- analyzer Web Worker
- runtime capability detection
- lazy model download and cache state UI
- embedding/classifier adapter interfaces
- selected model prototype
- tag centroid synchronization
- WebGPU/WASM/cloud/pending fallback
- target-phone benchmark harness
- versioned Korean rough-note evaluation set

### Exit criteria

- UI remains responsive during inference;
- measured warm latency and memory fit the chosen budgets;
- local high-confidence error rate is acceptable;
- model cache eviction does not break capture.

Do not hard-code a model before the benchmark and licensing review.

## Milestone 4 — PWA reliability and reminders

### Deliverables

- extend the existing installable app shell and user-prompted update lifecycle for offline reliability
- IndexedDB outbox
- foreground retry and explicit sync state
- conflict response UI
- Web Push subscription
- reminder database state machine
- idempotent dispatch and bounded retry
- notification deep link to source memo

### Exit criteria

- airplane-mode capture survives reload and synchronizes later;
- duplicate sync does not duplicate domain records;
- reminder retry does not duplicate user-visible notifications.

## Milestone 5 — Search, taxonomy evolution, and graph compression

### Deliverables

- independently bounded full-corpus local-neighborhood endpoint and off-home memo detail navigation
  (implemented as the first read-only slice; server page 20, browser cap 100, visible-state digest
  invalidation and explicit first-page restart for stale traversals)
- lexical/fuzzy search and alias search
- open the shared detail experience from results outside the recent memo/home-graph bounds
- optional vector retrieval after measurement
- provisional topic collection
- confirmed-only tag centroids
- batch new-tag/merge/archive proposals
- deterministic period/status clusters
- cluster expand/reveal-from-search
- stable placement for important nodes
- later AI-generated cluster labels/summaries with version invalidation

### Exit criteria

- adding a memo does not run whole-corpus maintenance;
- semantic taxonomy changes require confirmation;
- compression never hides overdue/unfinished/pinned nodes;
- cluster expansion always reaches original data.

## Initial issue order

1. Decide PWA/data authority/auth defaults and write ADRs.
2. Scaffold frontend, backend, database, and CI.
3. Add memo/revision migration and idempotent create API.
4. Add FakeAnalyzer and proposal schema.
5. Add review/apply transaction and undo.
6. Add tags/aliases and task derivation.
7. Add bounded graph endpoint and React Flow view.
8. Add task list/state transitions.
9. Add complete E2E scenario.
10. Only then begin deterministic/cloud/local model work.

## Definition of done for every feature

- user-visible success and failure states exist;
- owner scope and input validation are tested;
- retry/idempotency behavior is defined for mutations;
- raw memo integrity is preserved;
- schema/API docs are updated;
- unit/integration/E2E tests appropriate to the change pass;
- no sensitive memo text is added to ordinary logs;
- measured behavior is reported instead of assumed where device/model performance is involved.
