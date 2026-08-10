# Implementation roadmap

## Working method

Build vertical slices and keep every checkpoint runnable. Do not begin with a model experiment disconnected from the product flow.

## Current checkpoint

- Milestone 0: complete.
- Milestone 1: complete, including memo lifecycle/recovery, production PWA packaging, and mobile E2E coverage.
- Milestone 2: in progress. Korean date policy, versioned regression/`VISIBLE_CHALLENGE` fixtures, a content-free deterministic evaluation report and narrow regression safety gate, runtime schema/domain validation, versioned field-level routing with persisted provenance, provider-independent Fake cloud enrichment, prompt-injection boundaries, `UNKNOWN` user resolution, and raw-content-free owner-scoped review-outcome aggregation are implemented. `fake-v6` / `korean-rules-v4` preserves the sequential item/source-span and overflow boundaries while emitting proposal schema v2 with proposal-local date candidate IDs and nullable TASK due-date references. Historical schema v1 proposals remain recoverable with the former conservative single-task/single-precise-date projection, while `review-default-v3` uses only explicit v2 bindings. No Flyway change or historical proposal rewrite is needed because the existing run schema-version column and proposal JSONB already carry the contract. Evaluation dataset v2 still reports date mention/item/item-source-span metrics and the existing narrow regression safety failures, but it contains no date-to-item binding gold: reports declare `SUPPORTED_NOT_SCORED_DATASET_V2`, and binding quality plus the visible challenge remain report-only rather than blind or general Korean accuracy estimates. The external blind harness enforces an outside-repository, independently human-curated dataset, clean candidate commit, aggregate-only output, and privacy fail-closed behavior, but no real blind dataset or pre-registered metric gate is claimed. The outcome summary separates current run state, latest application/undo state, and versioned latest-selection comparison; `exact` means unchanged application, not AI accuracy. Independent gold adjudication, a binding-label policy and adjudicated evaluation dataset v3, a separately held blind release and pre-registered thresholds, provider failure/consent boundaries, top-k context, and async lifecycle remain before a real provider decision.
- Milestone 2 compatibility hardening now negotiates proposal reads: an absent/`1` schema header returns strict v1 for an installed older PWA, while the current PWA requests `2`; responses are `no-store`/`Vary` and projection never rewrites stored v2 JSON. Apply also canonicalizes due source zones from the immutable memo revision instead of trusting the review device's zone. These are contract/application changes over existing JSONB and task columns, so they require no Flyway migration.
- Authentication hardening: complete for the MVP checkpoint. Local email/password, optional Google OpenID Connect, explicit account linking, PostgreSQL-backed server sessions, CSRF protection, owner identity derived from Spring Security, and a deterministic 5-failure/15-minute local-account lock are implemented. Authentication unit/integration coverage and the full local-account primary browser E2E suite pass.
- Production/deployment hardening: complete for a controlled private checkpoint. Explicit dev/prod Compose overlays, required production database secrets, fail-closed production registration, Secure/SameSite session and CSRF cookies, validated forwarded headers, non-root health-checked images, static verification, and backup/restore/Flyway operating guidance are present.
- Private personal-PC deployment: implemented. A one-time PostgreSQL-locked interactive account bootstrap, a private-LAN HTTPS overlay, local CA/leaf certificate generation, owner-only secret files, guarded Windows operations, logical backups, forward-only database credential rotation, and isolated restore verification are available without exposing Spring Boot or PostgreSQL to the host network.
- Galaxy S24 Ultra readiness is covered by 384/412px touch viewports, landscape overflow checks, safe areas, installability diagnostics, and service-worker cache-boundary E2E tests. Private-LAN HTTPS, local CA trust, login, and the primary flow were also manually verified on the user's S24 Ultra on 2026-08-08. Home-screen installation and keyboard/cutout behavior remain manual checks and are not claimed as completed without an explicit record.
- Google behavior is covered with mocked OIDC claims and authorization-request tests; no real-provider credential round trip is claimed. Email verification, password recovery delivery, separate migration/runtime database roles, IP/edge rate limiting and abuse protection, MFA/passkeys, account deletion, a publicly trusted domain/TLS edge, secret management, monitoring, and automated backup drills remain public-release work.
- Real local/cloud model adapters remain intentionally deferred by the current product decision. No provider is introduced merely to satisfy the original roadmap bullet.
- The immediate evaluation work is independent adjudication of the version-2 date mention/item/item-source-span gold, followed by a separately reviewed version-3 binding-label policy and dataset. Only after those labels are frozen may a human-curated separately held blind release use thresholds approved before its first candidate run. Review-outcome counts and structurally valid v2 bindings are supporting owner-local evidence, not permission to connect a real LLM.
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
- bounded graph home endpoint
- task list and state update

### Frontend

- graph-home shell
- quick-capture input
- pending/saved/error state
- review chips for type, title, tags, and date
- partial apply and reject
- task side panel/list
- graph memo/tag rendering

### Tests

- primary `11.25 OS과제 제출` E2E scenario
- duplicate create/apply
- stale revision rejection
- undo preserves source
- owner isolation

### Exit criteria

The complete user flow works without a real AI provider.

## Milestone 2 — Deterministic analysis and bounded cloud enrichment

### Deliverables

- Korean date expression representation and policy tests
- tag normalization and alias lookup
- local-result DTO and schema validation
- deterministic ambiguity gate
- cloud provider abstraction with a fake adapter
- provider-independent failure and consent boundaries exercised with a fake adapter
- top-k retrieval context
- structured-output and domain validation
- async analysis status, timeout, retry policy, and cost metrics
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

- lexical/fuzzy search and alias search
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
