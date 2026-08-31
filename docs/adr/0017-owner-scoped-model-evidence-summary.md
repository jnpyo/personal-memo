# ADR 0017: Owner-scoped count-only analysis-path evidence summary

## Status

Accepted for source implementation. Personal deployment remains a separate owner-authorized step.

## Context

V19 and V20 already persist server-owned, raw-free evidence for local fallback reasons, invocation
policy, gateway contribution, changed proposal fields, and the bounded approved-correction snapshot.
The current review-outcome summary answers what the user ultimately applied or corrected, but it does
not answer which configured analysis path received a dispatch, whether a successful local-model-path
result changed the proposal, or how often that path returned to the validated local proposal.

A dispatch is not itself model-call evidence: the default `NO_NETWORK` Fake gateway also uses the
durable dispatch lifecycle, and `PREPARED`, `PENDING`, or `LOCAL_FALLBACK` does not prove that a model
started. The aggregate must therefore separate the exact built-in Fake descriptor from a
server-recorded local-model path before presenting any local-model contribution count.

Without a separate aggregate, dependency-reduction work would be driven by anecdote. Exposing the
stored evidence objects or correlating them with memo/proposal identifiers would create an
unnecessary personal-data surface. These counters are operational evidence, not correctness labels.

## Decision

Add an authenticated, read-only
`GET /api/v1/analysis-path-evidence/summary?days=14` endpoint and a collapsed mobile diagnostic UI.
The server derives the owner from `CurrentIdentity`, returns `Cache-Control: no-store`, accepts only
1–90 rolling 24-hour periods, and fails without a partial response when the owner has more than 1,000
analysis runs in the selected half-open `analysis_runs.created_at` cohort.
The UTC instant difference between the two boundaries is exactly `days × 24` hours. The repository
uses an unordered 1,001-row sentinel: aggregate order is irrelevant, and the 1,001st row causes a
closed error instead of a sorted or partial response.

The query starts from all owner-scoped analysis runs and left joins their dispatch. It reads only
bounded scalar version, enum, state, and approved-snapshot count columns plus fixed JSON containment
and descriptor-classification booleans. The exact built-in Fake tuple is compared inside SQL and only
the boolean result leaves the repository. It never selects memo, proposal, selection, validated local
proposal, evidence JSON, descriptor strings, identifier, hash, offset, provider output, token, or
credential values.

The strict version-1 response contains only aggregate counters for:

- analysis runs with and without a dispatch;
- current versus legacy local-decision evidence and dispatch lifecycle;
- invocation mode and reason;
- configured dispatch route: local model, external memo transfer, exact built-in Fake, or
  legacy/other;
- contribution status only for the server-recorded local-model route;
- approved-correction snapshots with one or more fixed signals and their aggregate signal count;
- the fixed V19 fallback-reason and model-changed-field enums.

The UI remains collapsed and makes zero request until the user first opens “분석 경로 진단”. It
labels configured routes separately and offers an explicit read-only refresh after the first load.
`ACCEPTED_CHANGED` and `ACCEPTED_UNCHANGED` mean only that a successful local-model-path result was
accepted into the proposal; neither is a correctness or improvement label. A path, dispatch,
`PENDING`, or `LOCAL_FALLBACK` count does not prove an actual model attempt.
An approved-correction snapshot count means only that server-owned signals were fixed to a dispatch;
it does not prove the model used them or that quality improved.

## Boundaries

- The endpoint is not an accuracy, recall, quality, cost, or provider-readiness report.
- It does not change routing, invoke a model, create a rule, promote a correction, or call Apply.
- It does not add a migration, training set, RAG corpus, vector/embedding store, fine-tuning, or LoRA.
- The existing review-outcome comparison remains a separate contract and is not joined to this
  summary in version 1.
- Source and disposable synthetic verification do not authorize reading the personal database or
  deploying the rebuilt application.

## Consequences

The owner can inspect model dependence without adding raw-data exposure or initial page-load work.
Later deterministic-rule work may use only aggregate trends to choose a candidate semantic family;
the rule itself still requires separately reviewed public synthetic positive, negative, descriptive,
and near-match fixtures. Automatic rule promotion remains prohibited.
