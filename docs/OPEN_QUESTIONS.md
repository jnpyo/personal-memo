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
- V18 stores confirmed ITEM-to-MEMO/TAG directed relations separately from tag membership. Whether an
  ITEM source is promoted to its memo, how the four relation types map to graph edges, how TAG-target
  relations differ visually from `MEMO_TAG`, and how multiple application provenances are budgeted or
  deduplicated remain explicit graph product decisions; current graph projection ignores these rows.
- The first user-facing search slice is exact and owner-scoped: current raw body/latest valid applied
  title use normalized literal substring, active canonical tag/alias uses exact normalized equality,
  and a result opens current raw detail without changing the graph. Fuzzy/semantic ranking and
  cluster reveal remain decisions after measured usage.

### Notification and offline scope

- Task state is in the MVP.
- Web Push, reminder dispatch, and full offline synchronization are P1 or later.

### AI provider boundary

- The application default uses deterministic/Fake implementations with `UNCERTAINTY_ONLY` behind
  stable local and cloud interfaces. The single-owner personal overlay alone uses the exact pinned
  localhost LiquidAI binding in `AI_PREFERRED`; this is a `SOLO_PROVISIONAL`/`REPORT_ONLY` proposal
  path, not an authoritative provider decision.
- Proposal schema version 2 identifies date candidates and lets TASK item candidates reference one
  precise due-date candidate explicitly. Historical version-1 proposals remain recoverable, and no
  binding becomes canonical before the ordinary approval API succeeds.
- Proposal response compatibility is resolved by explicit negotiation: no header/`1` gives strict
  v1, the current PWA requests its maximum understood version `3`, historical v1/v2 is not synthesized
  upward, and a max-v2 client receives stored v3 with only EVENT temporal fields removed. Stored
  proposals are never rewritten, and due persistence always uses the immutable memo revision's source
  time zone rather than the approval device's zone.
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
- No consent grant/revoke HTTP API or actual external provider is configured. ADR 0007 first added a
  personal-overlay pinned localhost Ollama/LiquidAI uncertainty fallback; ADR 0008 supersedes only
  that overlay's invocation policy with `AI_PREFERRED`. Every validated current revision is called,
  including deterministic-clear proposals, while semantic ambiguity remains separate V20 evidence.
  The path remains `SOLO_PROVISIONAL`/`REPORT_ONLY`, and the explicit public-fixture runner is still
  test-only and not a provider decision. Related-memo retrieval, fuzzy/vector search, embeddings,
  real-model numeric usage/cost reporting and budget enforcement remain open work.
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
  out-of-transaction configured-gateway call, revision-rechecking finalize, and provider token after process
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
  `NOT_APPLICABLE`/null even when execution start is uncertain. A model-backed attempt is
  `NOT_APPLICABLE` only when definitively `NOT_STARTED`, `UNKNOWN` when execution or remote completion
  is uncertain, and `NOT_REPORTED` after a result until the gateway contract reports usage and price.
  The ledger is absent from public
  DTO/proposal/metadata/UI/eval
  report/log/browser/service-worker boundaries, stores no provider text/ID/token/raw/context, and has
  no independent TTL before an approved purge policy.
- V20 stores `model-invocation-v1` mode/reason separately from semantic fallback reasons. The
  personal overlay may derive at most K=3 conflict-free short exact-unique anchors from same-owner,
  active/current/latest `APPLIED` type-corrected or user-resolved single-item cases. Its durable retry
  snapshot contains only target-memo UTF-16 offsets and approved kind; claim materializes current
  anchor text+kind, and finalization scrubs raw offsets while retaining hash/version/count. Historical
  raw memo, selection, IDs, title/tag/due/relation are excluded. Undo excludes new dispatches while a
  prepared retry stays stable. This is not RAG/vector/embedding ingestion, automatic rule promotion,
  training, fine-tuning, or LoRA.
- No cloud provider is selected or connected. ADR 0008 selects one exact installed LiquidAI
  tag/digest for the personal-only `SOLO_PROVISIONAL`/`REPORT_ONLY` semantic-patch proposal path;
  this is not authoritative provider/model readiness or an accuracy claim.
- V20 source mechanical qualification is separate from model accuracy qualification. Accuracy is
  still `NOT_RUN_NO_CLAIM`. The owner-authorized 2026-08-24 personal backup/restore rehearsal,
  Flyway V18→V20, rebuild, and health checks passed. Product smoke used only a disposable synthetic
  database: adapter-v1 invalid output failed closed and adapter-v2 passed a narrow exact-sentence
  compatibility run. Broad LiquidAI provider status stays `NO_GO`; rollout is
  `GO_TO_DEVICE_ACCEPTANCE`.
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

## Milestone 6 status and unresolved decisions

### Resolved for source-only 6A.1

- Keep proposal schema v2 unchanged. It has no EVENT temporal binding, so review starts unscheduled
  and only an explicit user selection from a usable precise date candidate or direct schedule entry
  may create canonical `event_details`.
- Current `fake-v9` / `korean-rules-v7` resolves only an explicit
  `오늘|내일|모레 + 오전|오후 + 1–12시` expression (optional minutes) against revision capture context.
  A date-less `6시` remains `UNKNOWN`; today/PM is never inferred.
- Require Apply `selectionSchemaVersion: "2"` only when an EVENT schedule is present. Preserve
  title-only EVENT compatibility, legacy request-hash projections, manual Apply, transaction rollback,
  owner/revision checks, idempotency, and undo.
- Use V21 `event_details` with separate TIMED/ALL_DAY shapes, no backfill or default duration, and the
  immutable memo revision source zone. Expose only a bounded owner-scoped `no-store` `GET /events`
  confirmed-schedule list.
- Require every TIMED start/end offset to be valid for its local date-time in that source zone. DST
  gaps fail closed; either explicitly chosen valid offset in a DST overlap is preserved.
- These were source decisions under ADR 0009, `SOLO_PROVISIONAL`/`REPORT_ONLY`. The personal database
  was later moved to V22 and then V23 through separately owner-authorized
  backup/restore/migration/rebuild gates; proposal-v3 producer/preselection and personal canonical
  schedule smoke remain unauthorized.

### EVENT temporal binding evaluation (6A.2)

Resolved mechanically for 6A.2a under
[`ADR 0010`](adr/0010-event-temporal-binding-contract-preparation.md):

- Proposal v3 uses bounded per-item `eventScheduleCandidates` with explicit IDs, mode, start
  date-candidate reference, optional end descriptor, and score. The end descriptor distinguishes a
  value that is already exclusive from an all-day inclusive last day. A separate nullable suggestion
  ID exists so no future implementation may infer a default from order or score.
- Current producers remain v2, every review starts unscheduled, and current domain validation rejects
  a non-null suggestion. Version negotiation projects v3 down to strict v2/v1 without rewriting
  storage.
- A separate EVENT overlay contract and structural validator contain no labels. The existing
  dataset-v3 TASK-due overlay is not EVENT authority.

Still requiring people for 6A.2b:

- Which two-person-reviewed EVENT binding ambiguity/adjudication policy, metric definitions, numeric
  thresholds, sample coverage, and held-out release are approved before any analyzer may preselect a
  schedule or claim binding quality?
- How should a schedule-bearing manual selection become classifiable in review-outcome summaries
  without treating Apply itself as correctness gold?
- Which source-zone-aware prefill rule must reject a proposed timed offset before it reaches the
  editable draft, including DST gap/overlap cases?

### Authenticated iCalendar snapshot and selective publishing

6B/6C remain private, and the current personal V23 stack remains private `LOCAL_ONLY`. The `6D
public-edge preflight` has a bounded external synthetic transport/path/cache and owned-log receipt,
but public activation and real-feed external-client validation remain `NO_GO`.

- Resolved mechanically under ADR 0015: V23 separates deployment capability from per-feed public
  authority. Existing/new feeds remain `LOCAL_ONLY`; explicit enable requires the exact current
  server policy and a fresh bearer in one owner/CSRF/idempotency/version-protected transaction.
  LOCAL/PUBLIC deployment serves only matching local/current-policy-public rows, policy drift closes
  a feed, public disclosure-mode changes fail closed, and permanent revoke clears scope/consent.
  The original ADR checkpoint was source-only and had not migrated the then-personal V22 database.
  The subsequent owner-authorized V23 backup/restore rehearsal, forward migration, rebuild, and
  bounded private smoke completed with every feed `LOCAL_ONLY`, no public-consent pin, and no
  connector/public activation.

- Resolved mechanically for 6B under
  [`ADR 0011`](adr/0011-authenticated-icalendar-snapshot-export.md): the signed-in PWA previews and
  downloads one exact no-store RFC 5545 Blob from current eligible canonical EVENTs. It is bounded,
  schedule-only, read-only, contains no internal UUID/raw memo/provenance/token/alarm, and creates no
  sharing state. A missing end remains absent and title-only EVENT rows are not exported. The file is
  a one-time import, not a continuous subscription.
- Resolved mechanically for 6C under
  [`ADR 0012`](adr/0012-recipient-calendar-feed-source-boundary.md): default `BUSY_ONLY`, every event
  initially unselected, client-generated 32-byte secret, digest-only verifier, metadata-only
  idempotency response, fixed query-token read path, recipient-random UID, monotonic sequence, and
  persisted `STATUS:CANCELLED` temporal tombstone. Restoring a memo does not automatically reshare.
- The owner-authorized personal V22, 6D.1 `LOCAL_ONLY`, and subsequent V23 `LOCAL_ONLY` deployments do
  not authorize external-client smoke. 6B UIDs remain isolated and are never reused across recipient
  feeds.
- Recipient feed creation, membership, rotation, and revocation use the authenticated session, CSRF,
  owner scope, and idempotency. Mutations of an existing feed additionally require its expected
  version; create has no pre-existing version. Fetching uses only the verifier match in a separate
  stateless chain and never writes access state. An owner can retain at most 100 lifetime feeds,
  including revoked rows, and 6C has no delete/capacity-reclamation API.
- The first public deployment exposes only a feed-specific `GET`/`HEAD` surface through trusted HTTPS.
  It does not expose the PWA, authenticated API, backend, or PostgreSQL and does not include CalDAV,
  external writes/import, recurrence, `VALARM`, or provider API integration.
- ADR 0014 and the `6D public-edge preflight` source now choose provisional origin-hop containment:
  60 requests/minute with burst 20, 8 concurrent connections, and 2s/5s/10s upstream
  connect/send/read timeouts. These shared loopback-hop values do not answer the external per-client
  policy or total external deadline. Cloudflare remotely-managed named Tunnel is selected and the
  bounded external synthetic run recorded HIT 0, but 429 was not observed within 30 rate attempts.
- Still requiring owner/operational decisions before public activation: provider/customer request-log
  handling and privacy consent because the current account plan cannot expose the required sentinel;
  receipt-level remote replica proof despite separate dashboard active-replica-0 observation; external
  request/connection/deadline bounds; first Google/Apple compatibility target;
  tombstone retention/client behavior after real refresh; and proof that query token values are absent
  from every frontend, backend, upstream, tunnel/provider, and external-edge success/error log.
  The current personal V23 stack remains privately deployed with zero publication environment entries
  and exact `LOCAL_ONLY`. The preflight adds only a loopback edge and separate activation overlay. The
  hostname/DNS/Tunnel route and cache-bypass rule are prepared, but `PUBLIC_HTTPS` activation remains
  `NO_GO` while the provider/customer-log decision, real-feed proof, and interoperability smoke remain
  unresolved; the PWA and authenticated API are not made public.
- The V23 in-app disclosure text and per-feed consent pin do not resolve Cloudflare's provider-side
  retention/log visibility. Must the owner accept that unverified provider boundary, move to a plan
  with customer-visible request logs, change the bearer transport/edge design, or keep activation
  `NO_GO`?

## Decisions required before a public self-service launch

The current private beta is already bounded to one operator-provisioned owner on a trusted RFC1918
LAN with local CA, no port forwarding, registration/private-overlay Google disabled, unpublished
backend/PostgreSQL ports, and Fake/deterministic analysis. Expanding it to multiple users, an
untrusted network, or internet access is not a wording change: it must first resolve the applicable
account provisioning/recovery, rate-limit/abuse, deletion, trusted edge, monitoring, and backup-drill
questions below and record a new deployment decision rather than rewriting ADR 0006.

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
- Bidirectional CalDAV/provider sync, external calendar modification/import, recurrence, and provider
  write APIs
- Native app strategy

## Owner-only remote beta decisions still required

- Which exact single-label hostname will be used (`memo`, `app`, or `notes` under the owned zone)?
- Which exact owner email and Cloudflare Access identity provider or one-time PIN method will be used?
- Does the owner accept Cloudflare TLS termination and processing of login credentials, cookies, raw
  memo requests/responses, and canonical API traffic for this remote beta?
- Can the account prove Protect with Access, default deny, entire-host cache bypass, no request-body or
  raw-header/query log export, and connector replica-down rollback with disposable synthetic traffic?
- After Android Chrome/PWA compatibility is measured, should Access Binding Cookie be enabled and
  should the provisional 8-hour session be shortened?
