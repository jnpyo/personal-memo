# Implementation roadmap

## Working method

Build vertical slices and keep every checkpoint runnable. Do not begin with a model experiment disconnected from the product flow.

## Current checkpoint

- Milestone 0: complete.
- Milestone 1: complete, including memo lifecycle/recovery, production PWA packaging, and mobile E2E coverage.
- Milestone 2: in progress. Korean date policy, versioned fixtures, runtime schema/domain validation, versioned field-level routing with persisted provenance, provider-independent Fake cloud enrichment, prompt-injection boundaries, and `UNKNOWN` user resolution are implemented.
- Real local/cloud model adapters remain intentionally deferred by the current product decision. No provider is introduced merely to satisfy the original roadmap bullet.

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
- one real provider adapter behind configuration
- top-k retrieval context
- structured-output and domain validation
- async analysis status, timeout, retry policy, and cost metrics
- prompt-injection test cases

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

- installable app shell
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
