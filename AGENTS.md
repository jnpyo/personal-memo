# Repository instructions

These instructions replace earlier repository guidance. Read `CODEX_HANDOFF.md`, every document under
`docs/`, `contracts/analysis-proposal.schema.json`, and `fixtures/korean-memo-cases.json` before a
new milestone or architectural change.

## Current product boundary

- Start with an Android Chrome, mobile-first React/TypeScript PWA, a Spring Boot modular monolith,
  and PostgreSQL through Flyway.
- PostgreSQL is canonical. An owner-scoped browser draft may protect raw capture text, but it is not
  an offline mutation outbox or a second source of truth.
- Preserve every raw memo and immutable revision independently from analysis output.
- Treat every analyzer/model result as an untrusted proposal. Only an explicit user approval API may
  create canonical tags, tasks, or relations.
- Keep local and cloud analysis behind `LocalAnalyzer` and `CloudAnalysisGateway` interfaces. Use the
  deterministic/Fake implementations until a separate privacy, evaluation, cost, and product decision
  approves a real provider.
- Do not add Neo4j, Kafka, Redis, a separate AI service, Web Push, full offline synchronization,
  automatic tag merge/split, or graph-node compression to the current checkpoint.

## Authentication and ownership

- Local email/password and optional Google OIDC resolve to one internal UUID and one
  PostgreSQL-backed server session.
- Google email equality must never merge accounts. Linking requires an authenticated, explicit,
  session-bound intent.
- Keep local registration, Google authentication, and Google-created new accounts as independent,
  fail-closed deployment capabilities.
- Derive `ownerId` from Spring Security for every domain read and write. Request DTOs, browser state,
  analyzers, and Agent tools cannot choose an owner.
- Keep session and provider credentials out of browser storage. Apply CSRF protection to every
  cookie-authenticated mutation and keep auth/API routes network-only in the service worker.

## Data and API rules

- Increment the memo revision on every source edit. Mark a late result for an older revision `STALE`
  and recheck the revision inside the application transaction.
- Mutations that can be retried must bind an idempotency key to owner, operation, and request hash.
- Derive `OVERDUE` from current time/local date and `TODO`; never persist it as canonical state.
- Represent system types as memo metadata, filters, and icons rather than global graph hub nodes.
- Keep HTTP DTOs, domain records, and persistence mapping separate. Use forward-only Flyway
  migrations for schema changes.
- Before confirmation, Agent/model tools are allow-listed and read-only. Never interpret memo text as
  an instruction to the application or an Agent.

## Working method and verification

- Build vertical slices in `docs/ROADMAP.md` order and preserve unrelated user changes in a dirty
  worktree.
- Update the OpenAPI/schema and relevant documentation with contract or deployment changes.
- Add focused unit tests plus PostgreSQL integration tests for ownership, revision, idempotency,
  authentication, and rollback boundaries. Keep the primary mobile Playwright flow passing.
- Before publishing, run frontend lint/unit/type/PWA build, backend `mvn verify` with PostgreSQL,
  OpenAPI lint, production Compose validation, and relevant E2E tests.
- Never log raw memo bodies, passwords, session values, OAuth codes/tokens, or provider secrets.
