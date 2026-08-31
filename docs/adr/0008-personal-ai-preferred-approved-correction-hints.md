# ADR 0008: Personal AI-preferred analysis with approved-correction hints

- Status: Accepted for the single-owner personal overlay; `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Date: 2026-08-23
- Supersedes: ADR 0007 only for the personal overlay's invocation policy; the default application
  remains uncertainty-only

## Context

The deterministic analyzer is still the safe fallback and contract validator, but its cold-start
coverage is not accurate enough to be the primary user experience. The owner authorized the
personal overlay to invoke the already installed, exact-digest localhost LiquidAI model for every
validated current revision. The owner also authorized local, inference-time use of explicit Apply
corrections, while rejecting fine-tuning and LoRA.

An Apply row contains validated type/title/item/due selections and internal identifiers. It does not
contain reviewed action/object spans. Copying complete selections or historical memo bodies into a
prompt would therefore expose unnecessary data and would not provide a trustworthy action/object
label.

## Decision

1. Keep `UNCERTAINTY_ONLY` as the application default. `compose.personal.yaml` alone selects
   `AI_PREFERRED`, and that mode accepts only a bound `LOCAL_MACHINE_MEMO_CONTENT` gateway.
2. Preserve the deterministic semantic route and `ambiguity_reasons`. Store the separate
   `model-invocation-v1` mode and reason in the durable dispatch; never manufacture ambiguity to
   justify an AI-preferred call.
3. Run the Fake analyzer first and validate its complete JSON Schema/domain proposal. The local model
   may return semantic-patch v2 `KEEP` or a bounded `PATCH`; both pass the existing exact-substring,
   schema, domain, owner, revision, fence, and idempotency checks. A model result remains an untrusted
   proposal and cannot Apply anything.
4. When approved corrections are enabled, read only the owner's active, current-revision, latest
   `APPLIED` selections whose review result is type-corrected or user-resolved. Exclude exact,
   undone, rejected, postponed, stale, unclassifiable, relation-bearing, and multi-item cases.
5. Do not send a historical memo or selection to the model. Derive at most three conflict-free,
   safe, exact-unique lexical anchors that also occur in the current memo. Snapshot only current-memo
   UTF-16 offsets and the approved type under `approved-type-anchor-k3-v1`; materialize
   `anchorText + approvedKind` from the locked current revision at claim time.
6. Hash and persist the offset-only snapshot before the call so recovery reuses the same provider
   token and request shape. Scrub the raw snapshot at every finalization and retain only
   version/hash/count. Do not expose it through HTTP, proposal metadata, UI, logs, browser storage,
   evaluation reports, or training data.
7. An Undo immediately excludes that selection from new dispatches. A dispatch already prepared
   keeps its snapshot for retry stability. Invalid hash/version/offset/Unicode/binding evidence causes
   a zero-call validated Fake fallback.

## Consequences

- This is a bounded, read-only retrieval hint, not a vector database, corpus ingestion, background
  learner, automatic rule writer, fine-tune, or LoRA run.
- Version 1 can personalize only the approved type. Action/object correction requires a future
  explicit review contract; title/tag/due or relation data is not smuggled into the prompt.
- `KEEP` is a successful unchanged model contribution, not a fallback or a correctness claim.
- The existing 24 public synthetic fixtures and their semantic route gold remain frozen. A separate
  invocation-policy arm must measure call rate, KEEP/PATCH/fallback, schema/domain validity, latency,
  GPU/VRAM, and user correction outcomes. `EXACT` Apply is not called accuracy.
- Fine-tuning remains `NO_GO_FOR_TRAINING`; LoRA remains `NO_GO`. Alarm/reminder persistence and
  delivery remain a separate product slice.
