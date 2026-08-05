# Open decisions

These choices materially change implementation. Do not bury them as incidental code decisions.

## Decisions needed before Phase 0 finishes

### 1. Client platform

Recommended default: Android Chrome PWA first, with native Android considered later.

Confirm:

- Is installable PWA sufficient for the first release?
- Is iOS required in the first release?
- Is a native wrapper acceptable later for stronger on-device inference and notifications?

### 2. User model and authentication

Recommended default: develop with a single seeded user but include `ownerId` everywhere; add real authentication before deployment.

Confirm:

- Personal prototype only or public multi-account service?
- Password login, social login, or both?

### 3. Canonical data authority

Recommended MVP default: server database is canonical; offline local edits arrive in P1 through an idempotent outbox.

Confirm whether full local-first sync is a product requirement. Full local-first changes conflict resolution, deletion, and graph projection architecture substantially.

### 4. Cloud provider and budget

Confirm:

- allowed provider(s)
- monthly budget ceiling
- maximum acceptable cloud analysis latency
- whether memo text may be sent to a cloud model by default or only after opt-in

### 5. Date policy

Recommended starting policy:

- store all canonical instants in UTC;
- retain IANA time zone and original expression;
- omitted year uses the nearest plausible future date only as an explicit candidate;
- omitted time remains `DATE_ONLY` rather than silently becoming 23:59;
- default reminder time is a separate user policy;
- approximate expressions remain approximate and require confirmation for reminders.

Confirm whether `11.25` should default to the current/next year and whether due dates without time use a default deadline.

### 6. Confirmation policy

Recommended MVP default: always show a compact review before semantic application.

Later options:

- auto-apply trusted aliases
- auto-apply high-confidence type only
- never auto-create a task or reminder

### 7. Graph contents

Recommended MVP default:

- graph nodes: memo and topic tag;
- task/event/type: metadata, filter, icon, and side-panel information;
- derived task nodes become separate visible nodes only if user testing proves useful.

### 8. Initial tag creation

Recommended default:

- existing tag suggestions are one-tap selectable;
- new tag names are proposals requiring confirmation;
- provisional clusters remain hidden until repeated evidence exists.

### 9. Notifications and offline scope

Recommended default: task state in MVP; Web Push and full offline synchronization in P1.

Confirm whether notification delivery is required for the very first usable release.

### 10. Deletion and retention

Confirm:

- trash retention duration
- whether analysis runs are deleted with a memo
- whether embeddings are deleted immediately
- backup/export expectations

## Decisions after the first vertical slice

- Exact local classifier and embedding model, based on target-phone benchmarks
- Maximum accepted model download size
- WebGPU/WASM fallback policy
- Vector search implementation and when pgvector is justified
- Tag promotion/merge/split thresholds
- Node activity score and compression timing
- External calendar integration
- Native app strategy

