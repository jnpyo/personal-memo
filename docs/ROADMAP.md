# Implementation roadmap

## Working method

Build vertical slices and keep every checkpoint runnable. Do not begin with a model experiment disconnected from the product flow.

## Current checkpoint

- Milestone 0: complete.
- Milestone 1: complete, including memo lifecycle/recovery, bounded hard-priority graph home,
  accessible node detail/pin interaction, explicit relation review/application/undo, production PWA
  packaging, and mobile E2E coverage. V18 stores selected item-scoped directed MEMO/TAG relations
  with owner-aware constraints; graph projection of those typed relations remains a separate product
  decision rather than an implied part of Milestone 1.
- Milestone 5 has two read-only vertical slices. The first is an independently bounded, owner-scoped
  full-corpus MEMO_TAG neighborhood endpoint and drawer navigation from a home tag to an off-home
  memo's current raw detail. The second is privacy-first exact lexical memo search over the current
  raw revision, latest valid applied canonical title, active canonical tag/alias, task/lifecycle/
  overdue state, and current-revision time range. Search uses a JSON-body POST, a 20 default/50
  maximum server page, a five-page/100-result browser cap, a 24-hour full-visible-result digest
  cursor, explicit stale restart, and the shared no-store current raw detail. These slices do not
  complete fuzzy/semantic search, taxonomy evolution, compression, or the Milestone 5 exit criteria.
- Milestone 6A.1 is implemented in source as a provisional canonical EVENT schedule slice. Proposal
  schema v2 still never binds a date to an EVENT automatically. Review starts unscheduled and
  requires the user to choose a usable precise date candidate or enter a schedule directly; only an
  explicit Apply selection with `selectionSchemaVersion: "2"` creates V21 `event_details`. The PWA
  reads current owner schedules through bounded `GET /api/v1/events`.
- Milestone 6A.2a now has dark-compatible contract preparation only. Proposal schema v3 can carry
  bounded ID-referenced EVENT schedule alternatives with explicit timed/all-day and inclusive versus
  exclusive all-day-end semantics. The PWA may display them, but every review still starts
  unscheduled and requires an explicit user action. Fake and the localhost semantic-patch adapter
  remain v2 producers; non-null analyzer preselection is rejected. A separate strict EVENT label
  overlay contract and integrity validator contain no labels or human evidence. This remains
  `SOLO_PROVISIONAL`/`REPORT_ONLY`: the owner-authorized private database is now V23 and remains
  `LOCAL_ONLY`, but no personal
  schedule/feed data smoke, v3 producer activation, or quality claim is authorized. The human
  label/threshold/held-out gate remains 6A.2b.
- Milestone 6B is implemented in source as an authenticated, no-store RFC 5545 snapshot preview and
  exact-Blob download. It exports at most 100 current canonical scheduled EVENTs, fails closed rather
  than returning a partial file, and exposes no raw memo, internal UUID, provenance, alarm, token, or
  public URL. This is a one-time import artifact, not a live subscription.
- Milestone 6C is implemented and present in the private V23 stack with explicit recipient
  membership, digest-only bearer verification, per-recipient UID/sequence/cancellation, and fixed
  stateless GET/HEAD. Private deployment smoke created/read no personal schedule or feed row.
- Milestone 6D.1 is implemented and privately deployed: fail-closed server properties, authenticated no-store
  capability controller, strict frontend decoder, and warned local/public URL branching. The default
  exact branch is `LOCAL_ONLY`/null; only `PUBLIC_HTTPS` uses the strict server-owned multi-label
  ASCII hostname origin. The personal V23 deployment passes no publication environment and remains
  `LOCAL_ONLY`. Syntax validation is not public-suffix/DNS proof.
- V23 explicit per-feed public consent is source-qualified and deployed to the owner-authorized
  private stack as `LOCAL_ONLY`. Existing/new feeds stay
  `LOCAL_ONLY`/null/null until an authenticated owner submits the exact current consent policy and a fresh
  bearer in one atomic enable operation. Disposable Flyway V1-V23 and the full backend/frontend/contract
  gates passed, and an owner-authorized backup, disposable restore rehearsal, migration/rebuild moved
  the personal database from V22 to V23 with no publication environment. Public activation remains
  `NO_GO`/`NOT_AUTHORIZED`.
- **6D public-edge preflight** is implemented in source as a separate loopback-only
  `calendar-feed-edge`, preflight Compose overlay, separately ordered activation overlay, and isolated
  synthetic smoke. It does not enable `PUBLIC_HTTPS` or expose the PWA/API/backend/PostgreSQL. The
  remote named Tunnel, published route/DNS, and stopped hardened service are prepared. A bounded
  46-probe external synthetic run completed and rolled back with transport/path/cache plus owned-log
  evidence, but provider/customer log sentinel and receipt-level replica proof remain unverified.
  Actual activation and Google/Apple smoke remain final-gate `NOT_AUTHORIZED`.
- **6E owner-only remote PWA** reached the `LIVE_OWNER_BETA` qualification on 2026-08-30. On
  2026-08-31 the exact owner reported a successful external email-OTP sign-in, application sign-in,
  and PWA screen load at the configured owner app hostname. This is user-reported acceptance, not provider-log or
  independent automation evidence. An unauthenticated application-session `/auth/me` 401/no-store
  probe and provider/customer log sentinel remain unverified. After the subsequent PC reboot, the
  Manual connector and `app-public-edge` are currently stopped, so the remote route is not presently
  available until the reviewed edge-first, connector-last start sequence is run again. The
  qualification remains `LIVE_OWNER_BETA`, `SOLO_PROVISIONAL/REPORT_ONLY`; unrestricted public and
  production readiness remain `NO_GO`.
- **Milestone 7.1 Today-first mobile home** is source-implemented. It reorders existing UI
  information around quick capture and today's tasks/events, keeps the graph secondary, and adds only
  a read-only summary derived from existing browser/workspace state. It adds no API or JSON Schema
  contract and no browser control over Windows services, Docker, Cloudflare, connector metrics, or
  secrets. Frontend lint, TypeScript, 472 unit tests and the production PWA build pass. A
  backend-free synthetic Chrome smoke passes the 384×854 and 854×384 Today-first shell and overflow
  checks. The full disposable-backend primary flow remains unexecuted because Docker Desktop is
  stopped; no deployed acceptance is claimed yet.
- This is an explicit interim ordering exception, not a claim that Milestones 2–4 are complete. The
  remaining Milestone 2 gates require two independent human reviews and later provider/privacy/cost
  decisions, while Milestones 3–4 require account-delivery or Web Push product choices. The read-only,
  PostgreSQL-only Milestone 5 slices can make bounded user-visible progress without inventing those
  external decisions or opening any real-provider gate.
- Milestone 2: in progress. Korean date policy, versioned regression/`VISIBLE_CHALLENGE` fixtures, a content-free deterministic evaluation report and narrow regression safety gate, runtime schema/domain validation, versioned field-level routing with persisted provenance, provider-independent Fake cloud enrichment, prompt-injection boundaries, `UNKNOWN` user resolution, and raw-content-free owner-scoped review-outcome aggregation are implemented. `fake-v9` / `korean-rules-v7` emits proposal schema v2 with source-aligned sequential items, proposal-local date IDs, nullable TASK due references, guarded affirmative `접속하기`, and conservative incomplete-coverage signals under `field-policy-v2`. It resolves an explicit `오늘|내일|모레 + 오전|오후 + 1–12시` expression (optional minutes) against the immutable revision instant/source zone as `RELATIVE_EXACT`; negative/descriptive forms and a date-less `6시` remain unresolved, with no automatic today/PM inference. Historical schema v1 remains recoverable and `review-default-v3` uses explicit v2/v3 TASK-due bindings while keeping temporal-candidate-bearing v3 proposals and schedule-bearing selections unclassifiable. V13 adds exact owner/policy/timestamp consent and server-owned cloud evidence, V14 adds internal authorization/grant/token evidence, V15 adds durable prepare/claim/fence/lease/deadline/recovery/finalize mechanics, and V16 adds owner-active exact tag/alias K=8 context with pre-call raw/hash/version/count snapshot and final raw scrubbing. V17 adds `gateway-attempt-v1`: at most one owner-scoped row per claimed fence and no more than `max_attempts`, monotonic local elapsed when observed, explicit executor-rejection versus gateway-result semantics, and truthful `UNKNOWN`/`NOT_APPLICABLE`/`NOT_REPORTED` model-token/cost states. Existing dispatches remain `attempt_history_version=none` with no backfilled attempt rows. The production profile runs the same lifecycle through a 25-row, 30-second bounded recovery scanner. No V17 evidence appears in public POST/DTO/proposal/`providerMetadata`/UI/evaluation-report/log/browser/service-worker contracts, and attempt rows contain no provider text/ID/token/raw/context. `NO_NETWORK` Fake needs no consent; unconsented `EXTERNAL_MEMO_CONTENT` is zero-call; typed failure/exception/invalid output persists a validated local fallback without canonical changes. Evaluation dataset v2 still has no date-to-item binding gold. A local-only static reviewer packet and an external two-manifest aggregate verifier now make the prepared protocol executable without exposing fixture notes or analyzer output, but they cannot create human evidence or prove reviewer identity/independence. Completed human review and resolution, an approved version-3 binding dataset and separately held blind gate, provider/region/consent/retention decisions including attempt purge, related-memo/fuzzy/vector/embedding context, and real-model numeric usage/cost reporting, aggregation, and budget enforcement remain before a real provider decision. No blind `PASS` is claimed. The preserved Solo LiquidAI v1 and completed v2 prompt/schema development reports are both `SOLO_PROVISIONAL` / `REPORT_ONLY` and use only the visible 24 public synthetic fixtures. V2 development acceptance is `NOT_MET`, training is `NO_GO_FOR_TRAINING`, and LoRA is `NO_GO`; the historical prompt/schema `RECOMMENDED` decision is closed by the v8-A authoritative LiquidAI `NO_GO`. ADR 0007 historically opened the personal uncertainty fallback; ADR 0008 now supersedes only that personal invocation policy with exact-pinned localhost `AI_PREFERRED`, so every validated memo is called even when the deterministic decision is clear. The normal application default remains Fake + `UNCERTAINTY_ONLY`. V19 adds raw-free decision/fallback/model-contribution evidence. V20 adds separate invocation evidence plus a K=3 same-owner approved-type anchor snapshot containing only current-memo UTF-16 offsets and kind, with final raw scrubbing. The result remains proposal-only/manual Apply and `SOLO_PROVISIONAL`/`REPORT_ONLY`; no accuracy claim, external provider, RAG corpus/vector/embedding, automatic rule promotion, training, fine-tuning, LoRA, or alarm delivery is authorized.
- Milestone 2 validation parity hardening is complete in source. One public-synthetic proposal control
  plus 17 structural/cross-field mutants now pin the expected JSON Schema/domain partition, while one
  raw-free local-decision control plus 15 cross-field mutants prove schema acceptance followed by
  sanitized domain rejection. EVENT temporal-binding integrity now rejects any `TIMED` or `ALL_DAY`
  start/end whose accepted interpretations mix mode-incompatible precisions. Spotless and 55 focused
  tests passed, then full backend `mvn verify` passed against disposable Flyway V1-V23 PostgreSQL with
  zero SpotBugs findings. No personal DB/memo, Ollama inference, canonical/API/Apply path, accuracy
  threshold, training/fine-tuning, or LoRA decision changed; status is `SOLO_PROVISIONAL` / `REPORT_ONLY`.
- The Solo LiquidAI development chain now preserves separate v1–v5 direct-generation reports, a
  deterministic guarded skill v6 report, v7-A output-cap, v7-B prompt-overhead, and v8-A compact-wire
  diagnostics with companion restoration attestations; none is a blind `PASS`. The finalized v5 report is
  `backend/target/evaluation/solo-liquidai-shadow-baseline-v5.json` (`35035` bytes, SHA-256
  `ba9c069d85c038d5c5603f8ddddfeae03aa8778cca7a949180142fee9b873102`) with companion
  `solo-liquidai-shadow-baseline-v5-attestation.json`. It recorded response/inference `24/24`,
  semantic/canonical/domain `8/8/7`, 49 failure observations across 17 unique cases with 32 overlaps,
  wrong-local 16, invented precise-date 2, local overflow 1, missing overflow 1, and route accuracy
  `0.375`; Fake remained canonical/domain `24/24`, route accuracy `1.0`, with those safety errors zero.
  V5 acceptance is `NOT_MET`, training `NO_GO_FOR_TRAINING`, and LoRA `NO_GO`. Fine-tuning and training
  tools are no longer a planned path. The completed v6 report is
  `backend/target/evaluation/solo-liquidai-deterministic-skill-v6.json` (`45708` bytes, SHA-256
  `a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f`) with companion
  `solo-liquidai-deterministic-skill-v6-attestation.json`. All 24 model requests were rejected as
  `MODEL_TRUNCATED_RESPONSE`, completed/accepted model contributions were `0/0`, and skill fallback
  was `24`. Fake, skill-only, and guarded arms were canonical/domain `24/24`, route accuracy `1.0`,
  with wrong-local, safety errors, and protected mismatches `0`; `GuardedSystem MET` is solely the
  deterministic fallback result. Model contribution and development acceptance are `NOT_MET`.
  P95 was Fake `9.509 ms`, skill `0.923 ms`, selector `491.271 ms`, and end-to-end `497.976 ms`;
  Ollama allocation was `2977033092` bytes at context `2048`. V6 is public-visible
  `SOLO_PROVISIONAL`/`REPORT_ONLY`, used no personal memo/DB/canonical/API/Apply or RAG, and keeps
  training `NO_GO_FOR_TRAINING` and LoRA `NO_GO`. Companion evidence records runner/relay/model,
  Ollama process/listener, Docker Desktop, canonical Docker fingerprint, and scoped temp restoration.
- Milestone 2 compatibility hardening now negotiates proposal reads: an absent/`1` schema header returns strict v1 for an installed older PWA, while the current PWA requests its maximum understood version `3`. Historical v1/v2 is never synthesized upward, and a max-v2 client receives stored v3 with only EVENT temporal fields removed. Responses are `no-store`/`Vary`, and projection never rewrites stored JSON. Apply also canonicalizes due source zones from the immutable memo revision instead of trusting the review device's zone. These are contract/application changes over existing JSONB and task columns, so they require no Flyway migration.
- Authentication hardening: complete for the MVP checkpoint. Local email/password, optional Google OpenID Connect, explicit account linking, PostgreSQL-backed server sessions, CSRF protection, owner identity derived from Spring Security, and a deterministic 5-failure/15-minute local-account lock are implemented. Authentication unit/integration coverage and the full local-account primary browser E2E suite pass.
- Production/deployment hardening: complete for the controlled single-owner private-beta checkpoint.
  Explicit dev/prod Compose overlays, required production database secrets, fail-closed production
  registration, Secure/SameSite session and CSRF cookies, validated forwarded headers, non-root
  health-checked images, static verification, and backup/restore/Flyway operating guidance are present.
- Private personal-PC deployment: implemented for one operator-provisioned owner on a trusted RFC1918
  LAN, online-only, with local CA trust, no router port forwarding, registration and private-overlay
  Google disabled and unpublished backend/PostgreSQL ports. The V20 analysis slice introduced a
  Fake-validated, exact-pinned localhost `AI_PREFERRED` proposal path with K=3 approved-type hints. Its source
  mechanical qualification is `PASS_ISOLATED`: Flyway V1–V20, 773 backend tests, Spotless,
  SpotBugs, frontend lint/301 tests/build, OpenAPI lint, and example-only personal Compose render.
  On 2026-08-24 the owner-authorized checksummed backup and separate restore rehearsal passed, the
  personal database migrated from Flyway V18 to V20, and the rebuilt stack passed health checks.
  A separate owner-authorized 2026-08-27 cutover then migrated that private stack from V20 to V22
  after a checksummed backup and disposable restore rehearsal; V22 health/unknown-token smoke passed
  without creating or inspecting personal schedule/feed rows.
  Product smoke used only a disposable synthetic database: adapter-v1 invalid output failed closed,
  while adapter-v2 completed the exact-sentence strict compatibility smoke twice. This does not
  approve broad model quality and provider status remains `NO_GO`. Rollout is
  `GO_TO_DEVICE_ACCEPTANCE`. A
  one-time PostgreSQL-locked interactive account bootstrap, owner-only secret files, guarded Windows
  operations, logical backups, forward-only database credential rotation, and isolated restore
  verification are available. This is not a multi-user or internet-facing beta.
- On 2026-08-28 a V23 disposable PWA product-flow smoke compared the same synthetic memo through
  Fake/`UNCERTAINTY_ONLY` and exact localhost LiquidAI/`AI_PREFERRED`. Both reached schema-v2 manual
  review with zero Apply/canonical rows; the clean UI wall times were `242.399 ms` and `5349.338 ms`.
  LiquidAI was `SUCCESS` but `ACCEPTED_UNCHANGED`, so this proves narrow integration compatibility,
  not semantic improvement or provider readiness. GPU evidence is device-wide/non-exclusive and the
  run remains `SOLO_PROVISIONAL`/`REPORT_ONLY`.
- On 2026-08-28 the permanent isolated product-path orchestrator completed the fixed public synthetic
  three-case comparison through separate Fake and exact LiquidAI disposable API stacks. Both final
  arms passed schema/domain and the affirmative/negative safety gates with zero Apply, tool/mutation
  calls, or canonical delta. LiquidAI succeeded for 1/3 and used validated local fallback for 2/3;
  median wall latency was `6958 ms` versus Fake `73 ms` (`95.3151×`), so semantic improvement is
  `NOT_DEMONSTRATED`. The strict aggregate receipt SHA-256 is
  `d605ed48935d8dd5acbd98ff7e658c495f70cb1467f69ad8efb1b656f5fcca3b`; it records `dirty=true` and
  therefore is not a clean-release attestation. Cleanup restored the enumerated scoped
  Docker/Ollama/temp resources and left the personal project container count unchanged. This closes
  the repeatable-harness milestone, not the provider gate: status remains
  `SOLO_PROVISIONAL/REPORT_ONLY`, provider/training/LoRA `NO_GO`, RAG unused, and automatic Apply
  disabled.
- Galaxy S24 Ultra readiness is covered by 384/412px touch viewports, landscape overflow checks, safe areas, installability diagnostics, and service-worker cache-boundary E2E tests. Private-LAN HTTPS, local CA trust, login, and the primary flow were also manually verified on the user's S24 Ultra on 2026-08-08. Home-screen installation and keyboard/cutout behavior remain manual checks and are not claimed as completed without an explicit record.
- On 2026-08-28 the live V23 app-shell readiness check reconfirmed the trusted root/manifest,
  standalone scope/start URL, 192/512 icons, service worker, safe-area source coverage, and stopped
  Cloudflare connector without a personal session or canonical read/write. This does not replace the
  four explicit S24 device-acceptance checks, so the rollout remains `GO_TO_DEVICE_ACCEPTANCE`.
- Google behavior is covered with mocked OIDC claims and authorization-request tests; no real-provider credential round trip is claimed. Email verification, password recovery delivery, separate migration/runtime database roles, IP/edge rate limiting and abuse protection, MFA/passkeys, account deletion, a publicly trusted domain/TLS edge, secret management, monitoring, and automated backup drills remain public-release work.
- Authoritative local/cloud and every external provider adapter remain deferred. The personal-only
  pinned localhost adapter is a `SOLO_PROVISIONAL`/`REPORT_ONLY` proposal-path exception and is not
  provider/model readiness.
- The explicit Solo LiquidAI public-fixture runner is evaluation infrastructure outside those product
  adapters. Normal Maven/CI does not select it, and it reads no personal database or canonical state.
  The attested v2–v5 route is a machine-local Docker host bridge to Windows loopback Ollama, not
  OS-level egress isolation; product HTTP, canonical reads/writes, and Apply were zero in the observed
  runner scope, and cleanup restored the model/listener/process and scoped temporary-resource state.
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
- The immediate solo skill/rule and truncation diagnostics over the existing public
  synthetic/de-identified boundary are complete through v8-A. Cap expansion, prompt reduction, and
  compact wire all retained 24/24 LENGTH termination and zero accepted model contribution, so the
  LiquidAI authoritative shadow decision is `NO_GO`; deterministic rule hardening remains the
  dependency-reduction path. The later personal semantic-patch fallback is a separate
  `SOLO_PROVISIONAL`/`REPORT_ONLY` decision and does not change this diagnostic verdict.
  Bounded public/de-identified RAG is evaluated only when a separate retrieval-solvable need is
  documented, with an explicit source allow-list and fixed budgets. Product or
  provider acceptance still requires human execution of the prepared independent version-2 review
  protocol using the clean-commit-pinned static packet and two outside-repository manifests, followed
  by human resolution, approval, two-person adjudication of the prepared version-3 binding-label
  policy/overlay, and a separately held blind release with pre-registered thresholds. The packet has
  no form or manifest generator, and the aggregate runner cannot prove human identity or independence.
  Renderer/verifier success, test-only manifests/overlays, review-outcome counts, structurally valid
  v2 bindings, visible v1–v8-A LiquidAI results, skill/RAG diagnostics, and the runner restoration
  attestations are preparation evidence, not permission to connect or train a real LLM.
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
- V18 application-owned item-to-MEMO/TAG typed relation persistence with owner-aware targets
- memo CRUD with idempotent create
- deterministic `FakeAnalyzer`
- proposal review endpoints
- transactional apply and undo
- bounded owner-visible relation labels, default-unchecked manual selection, and relation-aware undo
- bounded graph home endpoint with canonical pin/overdue/TODO/due/revision priority, deterministic
  memo/tag budget reservation, and stable tag connectivity ordering
- idempotent owner-scoped active-memo pin mutation
- task list and state update

### Frontend

- graph-home shell
- quick-capture input
- pending/saved/error state
- review chips for type, title, tags, and date
- relation review choices with unavailable-target handling and explicit reject-all selection
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
- relation source mapping, owner/ACTIVE target races, semantic duplicate rejection, application
  rollback/undo, target-tag orphan protection, and label privacy

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
- V19/V20 raw-free analysis-path evidence accumulation plus a source-qualified owner-scoped,
  count-only `/analysis-path-evidence/summary` endpoint and lazy “분석 경로 진단” UI. The four
  configured routes are separate from local-model-only accepted contribution states, and the UI
  explicitly avoids treating a route, dispatch, pending state, or local fallback as proof of a model
  call. The endpoint does not expose memo text, evidence JSON, IDs, hashes, offsets, tokens, provider
  output, or per-run values and never promotes rules automatically. Personal deployment and product
  smoke remain separately authorized work
- V20 `model-invocation-v1` evidence that keeps semantic uncertainty separate from
  `UNCERTAINTY_ONLY`/`AI_PREFERRED`, plus a personal-only `approved-type-anchor-k3-v1` retry snapshot.
  It derives at most three same-owner conflict-free exact-unique anchors from eligible latest
  `APPLIED` type corrections, persists only target-memo UTF-16 offsets and kind, materializes only
  current anchor text+kind, and scrubs raw offsets at finalization while retaining hash/version/count
- personal `AI_PREFERRED` calls the exact pinned localhost LiquidAI binding for every validated memo,
  including deterministic-clear proposals. Default runtime remains Fake + `UNCERTAINTY_ONLY`; every
  result remains proposal-only/manual Apply and no historical raw/selection/ID enters the hint
- explicit test-only localhost LiquidAI runners over only the 12 regression and 12 visible-challenge
  fixtures, with separately preserved v1–v8-A aggregate-only performance/safety evidence and
  companion restoration attestations; v6 is a guarded deterministic-selection diagnostic, v7-A/
  v7-B/v8-A isolate output cap, prompt overhead, and compact wire respectively, and none is selected
  by normal Maven/CI or has a product database, API, persistence, or Apply path
- an explicit Windows PowerShell 5.1 product-path qualification harness with fixed public-synthetic
  cases, separate tmpfs Fake/LiquidAI arms, an owned exact loopback Ollama endpoint, proposal-read-only
  product API flow, aggregate database/GPU/latency evidence, strict receipt validation, and exact
  scoped cleanup/restoration; live execution remains explicit and is not normal CI
- a test-only deterministic skill/rule comparison over public synthetic or de-identified inputs while
  retaining JSON Schema/domain validation, proposal-only/no-Apply behavior, and Fake comparison
- only after a separate retrieval-solvable gap is documented, an optional bounded
  public/de-identified RAG comparison with an explicit source allow-list and fixed document,
  retrieval-count, and context budgets; personal memo, PostgreSQL, canonical-state, provider, and
  product adapter access remain excluded

A real provider adapter is a separately approved follow-up after privacy, evaluation, latency, and
cost limits are defined. It is not an exit criterion for the current checkpoint.

The implemented review-outcome summary does not satisfy that approval by itself. Its `exact` bucket
means only “latest selection matched the versioned default review projection,” rejected runs have no
corrected target, and current `POSTPONED` state is not an append-only event history. Version-2
date/item metrics are reported, while date-to-item binding is supported but not scored because that
dataset has no binding labels. Independent adjudication, a version-3 binding-label dataset, and a
separately held blind release with a pre-registered gate are required before any provider comparison.
The preserved v1–v5 24-case Solo LiquidAI direct-generation observations remain visible, not blind, not independently
human-adjudicated, `SOLO_PROVISIONAL`, and `REPORT_ONLY`. V1–v4 remain separate history; v3 report
SHA-256 is `f6d6e8de0fc7aad342c0bd68487f1e416f922c75e6ba87cd8463c9b990468fa8` and v4 report SHA-256 is
`ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5`. V5 produced 24/24 responses
and inference-schema-valid outputs but only 8/24 semantic IR, 8/24 canonical-schema, and 7/24
domain-valid outputs, versus Fake canonical/domain validity 24/24. It recorded 49 failure
observations across 17 unique cases with 32 overlaps, route accuracy `0.375` versus Fake `1.0`,
wrong-local 16, invented precise-date 2, local overflow 1, and missing-overflow-signal 1. All-attempt
p50/p95/max/mean latency was `17172.783`/`31117.602`/`31305.739`/`18804.994 ms`; Ollama allocation
was `3166835834` bytes at context `8192`. The non-exclusive device sampler recorded 906 samples with
0 misses, baseline/first/last/maximum `3260`/`3243`/`3249`/`7196 MiB`, and maximum utilization `93%`;
model-exclusive peak is `NOT_AVAILABLE`. V4→v5 improved semantic/canonical/domain `1/1/1→8/8/7`,
failure observations `69→49`, unique failures `23→17`, wrong-local `23→16`, and p50
`22542.110→17172.783 ms`, while invented precise-date worsened `0→2`, p95 changed
`30973.996→31117.602 ms`, and both overflow findings remained. Strict development acceptance is
`NOT_MET`; training is `NO_GO_FOR_TRAINING`, LoRA `NO_GO`, and fine-tuning/training-tool installation
is not planned. Due binding and source spans remain disabled/null-only shadow capabilities, not
accepted product capabilities. None of v1–v8-A or later bounded RAG is a
Milestone 2 exit criterion, provider comparison, provider-readiness `PASS`, or training authorization.

The completed v6 deterministic guarded-skill comparison also used the visible 24 cases. Its report is
`45708` bytes with SHA-256
`a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f`; companion restoration evidence
is `solo-liquidai-deterministic-skill-v6-attestation.json`. Model selection requests/responses were
`24/0`; all 24 were rejected as `MODEL_TRUNCATED_RESPONSE` and used skill fallback, so no model
contribution was accepted. Fake, skill-only, and guarded results were canonical/domain `24/24`, route
accuracy `1.0`, and had zero wrong-local, listed safety errors, or protected mismatches. This makes
only the deterministic `GuardedSystem` gate `MET`; model contribution and development acceptance are
`NOT_MET`. P95 latency was Fake `9.509 ms`, skill `0.923 ms`, selector `491.271 ms`, and end-to-end
`497.976 ms`; Ollama allocated `2977033092` bytes at context `2048`. The run was public-visible
`SOLO_PROVISIONAL`/`REPORT_ONLY`, used no personal/DB/canonical/API/Apply data path and no RAG, and
performed no fine-tuning or LoRA. Training remains `NO_GO_FOR_TRAINING`; LoRA remains `NO_GO`.
The subsequent v7-A/v7-B/v8-A diagnostics changed the output cap, prompt overhead, and compact wire.
V7-A report/attestation is `5925`/`7874` bytes with SHA-256
`5b6a578b2b2222fc6180a4f70af7718526ccce2e127b070a404477a30c19d20f` /
`bccc6a0856ea9055f199d381e7be28e0e8587373687ab1d148f3617e69c4c617`; v7-B is
`7081`/`9743` bytes with SHA-256
`c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37` /
`ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d`.
V7-A prompt tokens/p95 were `9765`/`923.668 ms`; v7-B reduced input by `3792` total and `158` per
case to `5973` with p95 `823.686 ms`. Both remained STOP/LENGTH/accepted/fallback `0/24/0/24`.

V8-A report is `backend/target/evaluation/solo-liquidai-compact-wire-diagnostic-v8a.json`, `11150`
bytes with SHA-256 `bd9f4419fb26b8a2950b80722eef746fff41e4418a8c52ccb94aafc7333365e3`;
its attestation is `12184` bytes with SHA-256
`97e7c67a9a1f01140be7ad25734ce7080002b367ea7c87772c8a4c8287b4cdab`. Strict `{v,p,t}` wire
also produced 24/24 LENGTH at cap `128`, accepted `0`, fallback `24`, prompt tokens `6093`, and
Fake/selector p95 `10.131`/`855.907 ms` (`84.482×`). Guarded schema/domain was `24/24` through
deterministic fallback, leakage/protected mutation `0`. Device-wide sampler observations were
`59` with `0` misses, baseline/max `3033`/`6175 MiB`, max utilization `92%`; no exclusive peak is
claimed. No fine-tuning/LoRA/RAG or personal/DB/canonical/API/Apply access occurred, and postflight
  restored Ollama/Docker/temp state. LiquidAI authoritative shadow is `NO_GO`; the later personal
  semantic-patch path is separately bounded by ADR 0008 and remains
  `SOLO_PROVISIONAL`/`REPORT_ONLY`. RAG is
evaluated only after a documented retrieval need using an allow-listed public/de-identified corpus.

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
observation-free process-loss evidence is `UNKNOWN`. A model-backed attempt is `NOT_APPLICABLE`
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
The desktop public-fixture shadow diagnostic does not satisfy the target-phone benchmark, licensing
review, model-selection, or training-data requirements, and it does not start fine-tuning.

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
- keep TASK due and EVENT start semantics distinct; event reminders may be added only after
  Milestone 6A defines the canonical EVENT temporal contract, and no missing date, start, end, or
  duration may be invented

### Exit criteria

- airplane-mode capture survives reload and synchronizes later;
- duplicate sync does not duplicate domain records;
- reminder retry does not duplicate user-visible notifications.

## Milestone 5 — Search, taxonomy evolution, and graph compression

### Deliverables

- independently bounded full-corpus local-neighborhood endpoint and off-home memo detail navigation
  (implemented as the first read-only slice; server page 20, browser cap 100, visible-state digest
  invalidation and explicit first-page restart for stale traversals)
- exact lexical current-body/latest-applied-title search and exact normalized canonical tag/alias
  search (implemented as the second read-only slice; JSON-body POST, current-revision recency keyset,
  server page 20 default/50 maximum, browser cap 5 pages/100 results, full-visible-result digest
  invalidation, lifecycle/task/overdue/revision-time filters, and explicit stale restart)
- retain the measured no-migration decision from the opt-in 10,000-memo worst-case all-match
  PostgreSQL plan runner; its one hot-buffer observation is not an endpoint SLA
- open the shared current-raw detail experience from exact-search results outside the recent
  memo/home-graph bounds without injecting them into React Flow (implemented)
- fuzzy search after measured PostgreSQL evidence
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

## Milestone 6 — Explicit schedules and selective read-only iCalendar publishing

Milestones 6A.1, 6B, and 6C are present in the owner-authorized private V23 stack, but that deployment
is not a personal schedule/feed-data smoke or internet-exposure claim. Later slices remain ordered so
a public bearer URL cannot precede canonical schedule data, manual sharing consent, a local serializer
test, and a fail-closed server-owned public-origin capability.

### Deliverables

- **6A.1 — manual canonical EVENT schedule foundation (implemented in source):** keep proposal v2
  unchanged and initialize EVENT review without a schedule. The user may explicitly select a usable
  precise proposal date candidate or directly enter a timed start with optional explicit end, or an
  all-day start with optional exclusive end. Apply selection schema v2, V21 `event_details`,
  transactional undo/idempotency/owner/revision checks, the bounded owner-scoped `GET /events`, and
  the PWA confirmed-event list are the complete slice. Do not guess a one-hour duration or backfill
  existing title-only EVENT items.
- **6A.2a — EVENT temporal-binding contract preparation (implemented in source, dark only):** proposal
  schema v3 adds bounded per-EVENT alternatives referenced by proposal-local IDs, explicit
  `TIMED`/`ALL_DAY` mode, optional end, and explicit exclusive-at-value versus inclusive-through-value
  end semantics. Version negotiation projects stored v3 down to strict v2/v1 for older clients. The
  PWA displays candidates as untrusted and does not initialize a schedule from them. Current Fake and
  localhost model producers remain schema v2, and the domain gate rejects a non-null suggested
  candidate. A separate EVENT evaluation-overlay contract and integrity validator contain no labels.
- **6A.2b — evaluated analyzer activation (deferred to people):** independently approve the EVENT
  label policy, complete two independent label passes and human adjudication, freeze metrics and
  numeric thresholds before candidate output is inspected, and use a separately held release before
  any analyzer/model may preselect a schedule or any binding-quality claim is made. Dataset/proposal
  v2 and the existing TASK-due dataset-v3 overlay must not be reinterpreted as EVENT authority.
- **6B — authenticated export (implemented in source):** signed-in, no-store
  `GET /events/calendar.ics` returns a deterministic RFC 5545 snapshot after the PWA previews the
  exact in-memory Blob that it downloads. It reuses 6A.1 eligibility, probes 101 rows and fails
  closed above 100, returns 204 for no eligible component, and bounds the completed UTF-8 response
  at 128 KiB. Stable 6B-only opaque UIDs, immutable item-creation DTSTAMP, sequence 0, whole-second
  UTC timed values, all-day exclusive ends, CRLF/TEXT escaping/75-octet folding, fixed filename,
  owner/session guards, and zero canonical writes are covered. It creates no share state or URL.
- **6C — selective subscription (implemented in source):** let the owner create recipient-specific
  feeds, explicitly add or remove only canonical scheduled EVENTs, choose `TITLE` or `BUSY_ONLY`
  disclosure, and rotate or revoke a client-generated 256-bit bearer secret whose verifier alone is
  stored as a digest; every feed-management mutation remains session-, CSRF-, owner-, and
  idempotency-protected, and mutations of an existing feed additionally require its expected version.
  The URL is assembled once in PWA memory and never stored by the app.
- emit stable opaque non-internal `UID`, `DTSTAMP`, `DTSTART`, optional `DTEND`, and update sequence;
  handle UTF-8, CRLF, RFC escaping and 75-octet line folding, UTC timed values, source zones, DST,
  and all-day exclusive ends deterministically
- exclude raw memo bodies, TASK items, tags, relations, proposal/selection JSON, AI provenance,
  internal UUIDs, attendee/organizer data, attachments, URLs, and `VALARM` from the first feed
- **6D.1 — public-origin capability (implemented and privately deployed `LOCAL_ONLY`):** provide authenticated
  no-store `GET /calendar-feeds/capabilities` as the exact union
  `{mode: "LOCAL_ONLY", publicOrigin: null, consentPolicyVersion: null}` or
  `{mode: "PUBLIC_HTTPS", publicOrigin: "https://<public-fqdn>[:port]",
  consentPolicyVersion: "<server-policy>"}`. Keep `LOCAL_ONLY` as the
  public-publication default; make the maximum 255-character multi-label ASCII hostname origin
  server-owned and reject
  userinfo, IP literals, `localhost` and `*.localhost`, path/query/fragment, trailing slash, explicit `:443`,
  invalid ports, and browser/request-derived public authority. This is not public-suffix ownership or
  DNS reachability proof. A valid LOCAL_ONLY may produce only a clearly warned local/isolated URL;
  failed/malformed capability reads never silently fall back.
- **6D explicit per-feed public consent (V23 source-qualified; private `LOCAL_ONLY` deployment complete):**
  default every existing/new feed to
  `LOCAL_ONLY` with no consent pin. Require an authenticated owner, CSRF, expected owner, idempotency,
  exact feed version, exact current server policy, and a fresh client-generated bearer to atomically
  enable `PUBLIC_HTTPS`. Rotate the verifier in the same transaction so deployment configuration can
  never promote an old local URL. Serve only deployment/scope/current-policy matches, fail every
  mismatch as the same empty no-store 404, block disclosure-mode changes on a public feed without
  re-consent, and clear scope/consent on permanent revoke. This source slice itself did not migrate
  the personal V22 database. A later owner-authorized backup/restore rehearsal/migration/rebuild placed
  the personal database on V23 with no publication environment; it did not authorize or perform
  connector start, personal feed creation, or external clients. Public activation remains
  `NO_GO`/`NOT_AUTHORIZED`.
- **6D public-edge preflight (implemented in source; public activation not authorized):** add a
  dedicated edge that binds only to host loopback and forwards the exact bodyless stateless read-only
  `GET`/`HEAD /calendar/v1/feed.ics?token=<canonical-secret>` target. Reject every other method, path,
  encoded variant, query shape, and request body; keep the PWA, authenticated API, Spring Boot port,
  and PostgreSQL on their existing private boundary.
- log only fixed safe route/method classifications at the preflight edge and strip client credentials,
  cookies, referer, forwarding headers, and the query target before proxying. Local/upstream failures
  become generic empty 404 and rate rejection is bodyless 429. Use provisional origin-side
  bounds of 60 requests/minute with burst 20, 8 concurrent connections, and 2s/5s/10s upstream
  connect/send/read timeouts. These are not a total external deadline or an external SLA.
- keep `compose.public-feed.yaml` fail-closed and loopback-only with backend capability `LOCAL_ONLY`;
  keep `compose.public-feed-activation.yaml` absent through local and external synthetic qualification.
- **6D Cloudflare operator preparation (selected; remote route prepared, connector stopped):** use a remotely-managed named
  Cloudflare Tunnel with an owner-controlled domain in an active Cloudflare DNS zone and the
  single-label hostname `calendar.<zone>`. Do not use Quick Tunnels or `*.trycloudflare.com`. Prepare
  tunnel/DNS/cache/WAF/log policy while the connector is stopped. The official Windows binary is
  `DOWNLOADED_VERIFIED` (version `2026.8.2`, SHA-256
  `c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5`, Authenticode
  `Valid`, signer `Cloudflare, Inc.`). Cloudflare login and the owner-controlled active zone are
  verified. The remote named Tunnel, single-label published application/DNS, and exact-path loopback
  route are configured. Hardened `PersonalMemoCalendarCloudflareTunnel` is installed and verified as
  `Stopped`/`Manual`/`LocalSystem` with token-file-only ImagePath and no inline token. The generic
  default `Cloudflared` service is removed; `cloudflared` process count and port `8787`/`49312` listener
  counts are zero. Cloudflare reports `Down` after stop. Activation remains `NO_GO`.
- source-qualify PS5 Install/Start/Stop scripts that create only a protected token-file/manifest-backed,
  stopped Manual LocalSystem service. The installer hidden input accepts only a raw token or one exact
  Windows `cloudflared.exe service install <token>` command, extracts the token without executing the
  input, and rejects malformed or multiline input. Revalidate manifest/hash/current version/Valid Cloudflare signer,
  ACL/reparse/ObjectName before each start; and run with warn/transport-warn logging, grace 30s, and
  loopback metrics/diagnostics. Startup failure must auto-stop and preserve actionable diagnostics.
  Private-stack Start/Stop must fail closed while Cloudflare public topology is active. The PS5/source
  contract in `Test-PersonalMemoCloudflareSourceContracts.ps1` passes.
- use `Test-PersonalMemoCloudflareExternal.ps1` with standalone
  `compose.public-feed.cloudflare-test.yaml` for the three-phase disposable `127.0.0.1:8787`
  prepare/external-qualification/stopped-connector-and-replica cleanup lifecycle. The recorded strict
  non-secret receipt contains 46 probes: exact positive 3, origin deny 8, remote catch-all deny 5,
  bounded rate attempts 30. Cache observations were `BYPASS 0`, `DYNAMIC 46`, `HIT 0`; maximum observed
  latency was `873.816 ms`; owned-log and external-artifact-reflection sentinels passed. A 429 was
  `NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS`, so this does not prove external rate enforcement. The current
  account plan made provider/customer request-log sentinel evidence unavailable/unverified, and the
  receipt replica field remains `REQUIRED_NOT_VERIFIED` despite a separate dashboard observation of
  active replicas 0/routes 1/status Down after rollback and cleanup. Disposable containers, network,
  and local image were removed; the hardened service is stopped/manual, cloudflared and relevant
  listeners are zero, and the personal stack remained healthy and unchanged.
- never promote transport/path/cache evidence to overall PASS without provider/customer log sentinel
  and remote-replica verification. The maximum partial receipt classification is
  `TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`.
- treat the query bearer as data that enters Cloudflare's processing boundary. Configure customer
  request logs with a minimum allow-list such as timestamp, `ClientRequestHost`,
  `ClientRequestMethod`, query-free `ClientRequestPath`, response status/bytes, cache status, and Ray
  ID only. Exclude `ClientRequestURI`, request/response headers, cookies, referer and other raw request
  fields, and never run `cloudflared` at debug log level. Require an exact-host/path cache-bypass rule
  and prove `CF-Cache-Status != HIT` with external synthetic probes.
- start the connector first only against a disposable synthetic origin, complete external
  path/method/cache/log sentinel verification, stop the connector, and remove the synthetic origin.
  Then start the live loopback edge, apply `compose.public-feed-activation.yaml`, and start the
  connector **last**. No personal schedule/feed/token is used as smoke evidence.
- keep Cloudflare WAF rate limiting as defense in depth because enforcement can lag and counters are
  not a global exact origin quota. Neither Cloudflare Tunnel nor the existing origin timeouts prove a
  total 10s external deadline; the backend's 128-KiB generation bound is not a Cloudflare hard response
  cap. Any stricter external total-deadline/response-size gate requires a separately designed and
  verified component.
- roll back by stopping the connector first, proving there is no successful feed response through the
  tunnel (non-2xx; a Cloudflare edge error is acceptable), recreating the
  backend without the activation overlay and verifying `LOCAL_ONLY`, and then stopping the loopback
  edge. Tunnel/DNS cleanup may follow only after authority is closed.
- **6D external activation and interoperability (not authorized):** expose only the same feed surface
  through separately approved publicly trusted HTTPS, reject every other external path/method, prove
  token-free owned and external success/error logs, and complete separately approved Google/Apple
  subscription/update/removal smoke before any interoperability claim.
- leave CalDAV, provider API writes, external modification/import, bidirectional sync, recurrence,
  and automatic sharing outside this milestone

### 6A.1 source acceptance boundary

- only a manual reviewed schedule with selection schema version 2 can create `event_details`; title-
  only EVENT remains valid and absent from the scheduled-event list
- timed/all-day shape, exclusive-end/range, EVENT-only, IANA-zone, owner, revision, idempotency,
  source-zone offset/DST, rollback, and undo boundaries have focused source tests; DST gaps are
  rejected and either explicit valid offset is allowed during an overlap
- `GET /events` is bounded to 1–100 (default 50), owner-scoped, `no-store`, and excludes undone,
  archived, trashed, stale-revision, foreign-owner, and unscheduled items plus raw/proposal/application
  provenance
- the owner-authorized private stack reached V22 on 2026-08-27; that deployment did not create/read a
  personal schedule and does not authorize schedule backfill, Apply, canonical-data smoke, or repair

### 6A.2a source acceptance boundary

- schema/domain validation rejects dangling or precision-incompatible references, duplicate semantic
  alternatives, invalid ranges, all-day end overflow, non-EVENT candidates, and every current
  non-null suggestion
- a missing end remains null; an inclusive all-day end is normalized only when an explicit candidate
  boundary says `INCLUSIVE_THROUGH_VALUE`, never from date proximity or array order
- v1/v2 stored proposals and clients preserve their existing behavior, and no proposal JSON or
  database row is rewritten
- v3 display support creates no canonical data; only the existing explicit Apply path can persist a
  user-reviewed schedule
- no filled EVENT overlay, reviewer manifest, adjudication, threshold, score, `PASS`, producer
  activation, or personal deployment is part of this preparation
- current source-only isolation passes Flyway V1–V21, backend 813 tests with 0 failures/0 errors/1
  skipped plus Spotless/SpotBugs, frontend lint and 40 files/324 tests plus TypeScript/PWA build, and
  Redocly 2.44.1 OpenAPI lint; this is not personal deployment or model-quality evidence

### 6B source acceptance boundary

- only the authenticated owner can preview/download, and a session-owner change invalidates an
  in-flight response before its Blob is used
- the exact same bytes are previewed and downloaded; the browser stores neither a server path nor a
  persistent Blob, and the service worker remains network-only for the API
- foreign-owner, undone, archived, trashed, stale-revision, title-only, or otherwise incomplete rows
  are excluded; reads do not change canonical row counts or timestamps
- UTF-8 Unicode, emoji, comma/semicolon/backslash/newline escaping, property-injection resistance,
  CRLF and 75-octet folding are deterministic; malformed controls, fractional schedule seconds,
  four-digit-year violations, over-100 events, and over-128-KiB documents fail closed
- the later private V22 deployment does not turn source evidence into a personal schedule snapshot
  smoke; external-client import, provider/model call, RAG, training, fine-tuning, LoRA, alarm, public
  token, and public edge remain outside this slice
- current 6B source-only isolation passes Flyway V1–V21 and backend 820 tests with 0 failures/0
  errors/1 skipped plus Spotless/SpotBugs, frontend lint and 41 files/334 tests plus TypeScript/PWA
  build, Redocly 2.44.1 OpenAPI lint, production Compose render, and a focused secure-loopback
  Playwright calendar flow 1/1; all scoped containers, volumes, relay, and local images were removed

### 6C source acceptance boundary and 6D runtime gate

- a foreign-owner, unshared, undone, archived, trashed, or temporally incomplete item never appears
  in a feed, and reading/exporting a feed performs no canonical write
- missing date/start/end information never becomes an invented instant or default duration
- title-like text cannot inject a new iCalendar property; Unicode, escaping, folding, timed update,
  removal, year/range fail-closed, and ALL_DAY active/cancelled same-UID cases pass deterministic
  integration tests
- explicit remove and eligible re-add of the same feed entry retain its UID; a memo edit followed by a
  new application may cancel the old UID and create a new entry. No public UID reveals an internal
  UUID or cross-recipient identity.
- `BUSY_ONLY` reveals no event title, and neither disclosure mode contains raw memo, taxonomy,
  relation, analysis, or internal provenance data
- an invalid or revoked token receives the same bounded not-found response, the token is absent from
  browser/service-worker storage, and source configuration logs only the fixed URI. The UI warns that
  already downloaded or cached copies cannot be remotely recalled; public-edge and external-provider
  log sentinel proof remains 6D work.
- actual Google Calendar and Apple Calendar import/subscription update-removal smoke is completed
  only after the owner separately approves the feed-only public HTTPS edge; external calendar refresh
  timing and notifications are not claimed as Personal Memo alarm delivery
- create/rotate management responses and persisted idempotency JSON contain no secret, verifier, or
  URL; a canonical 32-byte secret is hashed immediately, and old/revoked/malformed/unknown tokens
  share one empty no-store 404 response
- each lifetime feed entry has a recipient-only random UID, monotonic sequence, and temporal snapshot;
  explicit removal plus memo edit/trash/application undo create a same-UID cancellation tombstone in
  the canonical mutation transaction, while restore never automatically reshares
- each owner can retain at most 100 lifetime feeds including revoked rows and each feed at most 100
  lifetime entries including tombstones; 6C has no feed-delete or quota-reclamation operation
- the private same-origin source proxy may route only the fixed query-token path without logging
  query arguments; public DNS/TLS/edge operation, externally enforced request/connection rates,
  owned/external error-log sentinel smoke, and Google/Apple interoperability remain unimplemented 6D
  work and require separate approval
- current 6C source-only isolation passes Flyway V1–V22 and backend 845 tests with 0 failures/0
  errors/1 skipped plus Spotless/SpotBugs, frontend lint and 44 files/377 tests plus TypeScript/PWA
  build, Redocly 2.44.1 OpenAPI lint, production/personal Compose render, the Windows source
  contract, and secure-localhost Playwright 24/24 in 25.5 s. Private isolated Nginx returned an
  empty no-store 404 for synthetic unknown-token GET/HEAD and owned frontend/backend logs contained
  zero synthetic sentinel values. This is private source/runtime evidence, not public-edge or
  external-provider-log evidence. Added synthetic integration covers the exact 100 lifetime
  feed/entry limits with rollback, ALL_DAY active/cancelled same-UID output, and six create/add
  versus actual undo/memo update/trash races. Before the earlier 835-test pass, cached Spring
  contexts exhausted the synthetic PostgreSQL connection limit. A product-config-neutral test
  resource now fixes Hikari pool 4/minimum-idle 0, and a fresh full verify without a CLI pool
  override produced the reported 845-test pass.
- all exact 6C temporary containers, volumes, networks, and local image tags were removed. The
  personal V20 stack, personal PostgreSQL/memos/canonical data, and Ollama were not accessed or
  changed during that 2026-08-25 source qualification.
- on 2026-08-27 the owner separately authorized the exact personal V20→V22 deployment. A checksummed
  cutover backup passed a disposable source-V20/target-V22 restore rehearsal with failed migration 0
  and zero calendar backfill. The rebuilt live stack passed trusted HTTPS health, Flyway V22, zero
  new calendar rows, and synthetic unknown-token private GET/HEAD plus query-free log verification.
  No personal feed row or schedule was created/read by the smoke. A later owner-authorized rebuild
  deployed the 6D.1 implementation as `LOCAL_ONLY`; all public-edge and external calendar-client work
  remains unauthorized.

### 6D.1 source acceptance and private deployment boundary

- Prior OpenAPI version 0.12.0 defined authenticated no-store
  `GET /api/v1/calendar-feeds/capabilities`; the response schema has
  `additionalProperties: false`, required both fields, and conditionally permitted only the exact
  `LOCAL_ONLY`/null or `PUBLIC_HTTPS`/HTTPS-origin shapes
- backend properties default disabled/blank and fail startup for inconsistent or invalid enabled
  values; the authenticated controller returns no-store without a canonical/feed write
- a `PUBLIC_HTTPS` origin is server-owned, lowercase, at most 255 characters, and consists only of a
  canonical HTTPS multi-label ASCII hostname plus optional non-default port 1–65535. Userinfo, IP
  literal, `localhost` and `*.localhost`, path, query, fragment, trailing slash, and explicit `:443` are
  rejected. Public-suffix ownership and DNS reachability remain edge-deployment checks
- frontend strict decoding rejects extra/missing fields and mode/origin mismatch. Only PUBLIC_HTTPS
  uses the server origin; valid LOCAL_ONLY may assemble a warned private/local HTTP(S) URL, while a
  failed or malformed capability read never silently falls back
- a fresh 2026-08-27 isolated qualification passed PostgreSQL Flyway V1–V22 and backend 119 suites /
  854 tests with 0 failures / 0 errors / 1 skipped plus Spotless/SpotBugs; frontend lint and 44 files /
  401 tests plus TypeScript/PWA production build; production/personal Compose render; and the Windows
  source contract. Fixed local Node validation parsed the OpenAPI YAML, resolved 384 internal and 2
  external JSON Schema references, confirmed 45 unique operation IDs, and passed the capability-origin
  matrix. Redocly Docker lint was not executed because the environment rejected mounting the private
  spec into a third-party image
- the implementation adds no database migration. An owner-authorized private rebuild retained the
  PostgreSQL volume, used a fresh checksummed backup and old-image rollback tags, and deployed new
  backend/frontend images with zero publication environment entries, therefore `LOCAL_ONLY`
- private deployment smoke passed three-service health, trusted HTTPS, Flyway V22/failed 0, PWA 200,
  unauthenticated capability 401/no-store, and synthetic private GET/HEAD with token-free owned logs.
  It did not use a personal session to execute the authenticated 200 response or create a feed row
- public hostname/DNS/TLS/operator routing, external request/connection/deadline bounds,
  owned/external token-log sentinel, and Google/Apple subscription execution remain outside 6D.1
- all exact 6D.1 temporary containers, volumes, and the local qualification image were removed
- status remains `SOLO_PROVISIONAL`/`REPORT_ONLY`; runtime public-edge activation and
  external-client work are `NOT_AUTHORIZED`

### V23 explicit public-consent source and private deployment acceptance boundary

- all pre-V23 and newly created feeds are `LOCAL_ONLY` with null policy/time; no migration or
  deployment-mode change can publish their existing bearer
- the capability exact union includes a null/current consent-policy version, and incoherent
  enabled/origin/policy configuration fails startup
- successful enable binds owner, expected version, idempotency body, fresh secret verifier and exact
  current policy in one transaction; old bearer, response JSON and idempotency JSON contain no usable
  secret or URL
- LOCAL deployment reads only local-scope feeds; PUBLIC deployment reads only current-policy public
  feeds; scope/policy/revocation mismatch remains the same empty no-store 404
- public disclosure-mode expansion requires fresh consent, permanent revoke clears public scope/pin,
  and explicit membership never becomes automatic schedule selection or Apply
- isolated source qualification passed disposable PostgreSQL Flyway V1-V23, backend 121 suites / 861
  tests with 0 failures / 0 errors / 1 skipped plus Spotless/SpotBugs, frontend 44 files / 414 tests plus
  ESLint/TypeScript/PWA build, OpenAPI lint/actual-instance validation, four Compose renders, and both
  public/personal PowerShell source contracts; the exact temporary Docker resources were removed
- source/test status remains `SOLO_PROVISIONAL`/`REPORT_ONLY`. The owner-authorized personal
  V22-to-V23 backup/restore rehearsal/migration/rebuild passed with publication environment 0 and
  `LOCAL_ONLY`; provider/customer log handling, receipt-level replica proof, public activation, and
  Google/Apple interoperability remain `NO_GO`/`NOT_AUTHORIZED`

### 6D public-edge preflight source acceptance and activation gate

- `calendar-feed-edge` is a separate least-privilege, read-only container on an internal backend
  network whose host publication is exactly loopback; the preflight overlay neither publishes the
  main frontend nor supplies backend `PUBLIC_HTTPS` settings
- exact raw-target and method checks admit only bodyless canonical-token GET/HEAD. PWA/API/OAuth,
  arbitrary paths, encoded variants, extra or duplicate query values, and upstream failures never
  cross as a broader public surface
- access logs contain only fixed safe route/method classes and never raw methods, request targets, or
  query bearers; client Authorization, Cookie, Referer, and forwarding headers are not proxied
- the provisional origin-side 60r/m + burst 20, connection 8, and proxy 2s/5s/10s
  connect/send/read bounds are tested as local containment. They are global at the loopback hop and do
  not define per-client external quotas, total external deadline, end-to-end latency, or SLA
- the recorded isolated smoke passed exact GET/HEAD, deny surface, header stripping, generic empty
  404, bodyless rate 429, provisional bounds, and query/path/header/custom-method bearer sentinel
  absence from owned edge/upstream logs. It used only generated synthetic bearers and a disposable
  stub upstream, read no personal PostgreSQL, memo, session, feed, event, or canonical data, and
  performed no Apply; it is not external-operator log proof
- activation is a separate second step: a reviewed ignored `.env.public-feed` and
  `compose.public-feed-activation.yaml` may be applied only after Cloudflare remotely-managed named
  Tunnel preparation and disposable-synthetic external qualification complete with the connector
  stopped again
- Cloudflare account, an owner-controlled domain in Cloudflare DNS, and single-label
  `calendar.<zone>` are prerequisites. Quick Tunnel is forbidden. The customer log field set excludes
  full/query-bearing URI and raw headers/cookies/referer; `ClientRequestPath` is the only request-target
  field. `cloudflared` debug logging is forbidden, cache is bypassed for the exact host/path, and
  external proof requires `CF-Cache-Status != HIT`
- after synthetic cleanup, start the live loopback edge, apply the activation overlay, and start the
  connector last. Do not use personal schedule/feed data for qualification
- WAF rate limiting is supplementary and does not replace the tested origin bound. Cloudflare Tunnel
  alone does not enforce a total 10s external deadline or an independent 128-KiB hard response cap
- rollback stops the connector first and proves no successful feed response through the tunnel
  (non-2xx; a Cloudflare edge error is acceptable), then recreates backend without
  the activation overlay and verifies `LOCAL_ONLY`, then removes the loopback edge if required. There
  is no schema/data rollback
- source/preflight status is `SOLO_PROVISIONAL`/`REPORT_ONLY`. The official binary is
  `DOWNLOADED_VERIFIED`; Cloudflare login and the owner-controlled active zone are verified. Remote
  Tunnel, exact-path published application/DNS and loopback route are configured. The hardened
  `PersonalMemoCalendarCloudflareTunnel` service is installed and verified `Stopped`/`Manual`/
  `LocalSystem`, token-file-only and without an inline token; generic `Cloudflared` is removed. Current
  connector process and port `8787`/`49312` listener counts are zero, and Cloudflare status is `Down`.
  Bounded external synthetic transport/path/cache and owned-log proof now exists, but provider/customer
  log sentinel and receipt-level replica proof remain unavailable/unverified. The strict receipt is
  `TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`; decision and activation remain `NO_GO`. Actual
  public activation and Google/Apple smoke remain final-gate `NOT_AUTHORIZED`

### 6E owner-only Access-gated remote PWA

- add an application edge on a separate internal frontend network and loopback host port; it must not
  reuse the calendar hostname, edge, Tunnel, token, Windows service, metrics port, or rollback path
- keep local registration and Google authentication disabled. Cloudflare Access is a perimeter gate;
  the existing application session, CSRF, owner derivation, JSON Schema/domain validation, proposal
  review, and explicit Apply remain authoritative
- require exact Host and same-origin unsafe requests, a request-header allowlist, fixed-class logs,
  bounded body/header/rate/connection/timeouts, bodyless deny responses, security headers, and
  no-store except for fingerprinted static assets
- qualify source and loopback behavior against a disposable synthetic upstream only. A remote
  synthetic route must be protected by Access before it is connected and must be rolled back before
  any personal origin is considered
- live activation requires the owner's exact hostname and Access identity choices plus explicit
  acknowledgement that credentials, cookies, raw memo traffic, and canonical API data cross
  Cloudflare's TLS-processing boundary. The connector starts last and rollback stops it first
- this is an owner-only `SOLO_PROVISIONAL/REPORT_ONLY` remote beta, not unrestricted public
  self-service. Email verification/recovery, MFA/passkeys, deletion, least-privilege database roles,
  monitoring/alerts, and public incident response remain separate launch gates
- 2026-08-30 live owner qualification: the exact-host Access/email-OTP route, `Running`/`Manual` connector,
  local ready/connected state, Dashboard `Healthy`, unauthenticated Access redirect and authenticated
  non-personal capability response passed. Connector-first rollback produced a fresh same-browser
  Cloudflare Tunnel `Error 1033`, then restart restored `Healthy` and capability response. Actual owner
  application sign-in and PWA screen load were then reported successful by the exact owner on
  2026-08-31. The application-session `/auth/me` unauthenticated 401/no-store probe and
  provider/customer log sentinel remain unverified. After a later PC reboot the Manual connector and
  `app-public-edge` are currently stopped, so the remote route is not currently available; this
  operational state does not erase the qualification evidence. Status remains `LIVE_OWNER_BETA`,
  `SOLO_PROVISIONAL/REPORT_ONLY`, while public/production remains `NO_GO`

## Milestone 7 — Owner beta usability and operations

### 7.1 Today-first mobile home

- make quick capture and today's unfinished tasks and confirmed events the primary mobile scan path;
  the graph remains available as a secondary retrieval view rather than the first visual hierarchy
- add a compact read-only summary derived only from existing in-memory connection, recovery, loading,
  and error state. Labels must describe what the current page can prove, such as raw capture
  availability or a partial-load problem, and must not claim database, Ollama, Tunnel, Access, or
  provider health
- preserve the existing explicit PWA refresh prompt and unsaved/pending-operation guards, while
  describing it as a screen/PWA asset refresh rather than a backend, Docker, or connector update
- keep analysis-path counts lazy, owner-scoped, raw-free, and secondary. They are historical aggregate
  evidence, not live model availability or model-quality status
- add no API, JSON Schema, Flyway migration, canonical mutation, automatic Apply, or host operations
  endpoint. Starting or stopping `PersonalMemoAppCloudflareTunnel`, `app-public-edge`, Docker, or
  Ollama remains outside the browser application and follows the reviewed operator scripts
- status: `SOURCE_QUALIFIED_SYNTHETIC_MOBILE_PASS_FULL_E2E_PENDING`. Frontend lint, TypeScript,
  472 unit tests and the production PWA build pass. A backend-free synthetic Chrome test passes at
  384×854 and 854×384 without personal data. The full disposable-backend primary flow remains
  unexecuted while Docker Desktop is stopped; explicit deployment/update acceptance is still required
- 2026-08-31 requalification repeated this bounded slice with Node 24.19.0: ESLint 9.39.2 passed,
  Vitest 4.1.10 passed 48 files/472 tests, TypeScript 5.9.3 and Vite 7.3.6 production PWA build passed,
  and Playwright 1.60.0 with local Edge passed the 384×854/854×384 synthetic shell with zero horizontal
  overflow. This does not promote the Docker-backed primary flow or deployment acceptance to PASS

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
