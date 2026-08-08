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

- Every analysis result is an untrusted proposal and receives a compact user review.
- No task, tag, relation, reminder, or graph artifact becomes canonical before explicit approval.
- Agent/model tools remain read-only before confirmation.

### Graph and taxonomy

- Graph nodes are memos and canonical topic tags.
- Memo type, task state, overdue state, and similar system concepts are memo metadata, filters, and
  icons rather than global hub nodes.
- Existing tags are selectable suggestions; new names remain proposals requiring confirmation.
- Automatic tag merge/split, node compression, and taxonomy migrations remain deferred.

### Notification and offline scope

- Task state is in the MVP.
- Web Push, reminder dispatch, and full offline synchronization are P1 or later.

### AI provider boundary

- The current checkpoint uses deterministic/Fake implementations behind stable local and cloud
  interfaces.
- No real local model or cloud provider is selected or connected without a separate product,
  privacy, evaluation, and cost decision.
- Public regression and `VISIBLE_CHALLENGE` fixtures are diagnostic synthetic data, never blind
  evidence. An external blind harness may consume only an independently human-curated version-2
  release outside Git and emits aggregate-only output from a clean, pinned commit.
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
- whether memo text may leave the service and how consent is recorded;
- monthly budget and per-request context/token limits;
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
