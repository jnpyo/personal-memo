# ADR 0021: Capture-only entry and graph-centered retrieval

## Status

Accepted for Milestone 7.5, with the 2026-09-05 corrective review below superseding the original
interaction-safety qualification. Full disposable PostgreSQL/production-like E2E, personal deployment,
and physical-device acceptance remain separate gates. The result remains
`SOLO_PROVISIONAL`/`REPORT_ONLY`.

## Context

Milestone 7.2 made the bounded confirmed graph the default signed-in view, but kept capture, search,
and a complete raw-memo card list in one secondary memo destination. Owner visual review found that
the memo destination still mixed two different jobs: quickly recording a thought and browsing old
material.

The owner wants the capture destination to contain only the memo input and save action, and wants
ordinary memo retrieval to begin from the connection map. The current home graph is deliberately
bounded to 100 nodes and contains only its current canonical projection. Raw-only, off-home, or
trashed memos therefore cannot safely become unreachable when the card list is removed.

## Decision

The second bottom-navigation destination becomes `추가` and opens a capture-only surface. It keeps
the accessible memo label, raw-draft preservation, required validation, bounded status/error copy,
character count, and save action, but no raw-memo list or search surface.

The connection-map destination remains the default view and owns a collapsed `메모 찾기` entry.
Opening it reuses the existing private bounded search and current raw-detail flow. Search results do
not become React Flow nodes and do not alter the bounded home projection. This fallback is how an
owner reaches raw-only, off-home, or trashed memos without reintroducing a primary all-memo list.

The corrective slice also provides query-free recent-memo and trash browsing inside that disclosure,
using the existing owner-scoped memo arrays. This is explicitly bounded to the most recently updated
50 memos per lifecycle state, not full-corpus browsing. Older material still requires a remembered
search term. Query-free traversal beyond that bound needs a separately reviewed pagination contract;
the client must not bypass required search-query validation or mislabel this list as all memos.

Memo management moves to detail surfaces. An active memo opened from the graph or graph search keeps
explicit edit, proposal-generation, and trash actions. A trashed search result keeps an explicit
restore action. The existing revision, idempotency, owner, unsaved-edit, review, and retry guards
remain authoritative.

## Boundaries

- No API, OpenAPI, JSON Schema, evaluation fixture, Flyway migration, persistence mapping, or
  canonical-data contract changes are part of this milestone.
- The graph remains a bounded projection. The client does not render the whole corpus, inject search
  results into React Flow, synthesize connections, or turn raw text into canonical tags or titles.
- Raw save remains first. Analysis remains an untrusted proposal and cannot create a canonical tag,
  task, EVENT schedule, relation, or alarm without the existing explicit review and Apply operation.
- No personal memo, owner session, PostgreSQL row, canonical record, or Apply path is required for
  source qualification. Tests use public synthetic state.
- No Docker, Ollama, Cloudflare, connector, personal-stack rebuild, or installed-PWA update is
  authorized by this source decision.

## Verification

The original bounded gate passed ESLint, TypeScript, 53 Vitest files with 532 tests, the Vite 7.3.6
production PWA build, the public-app source contract, and two backend-free Microsoft Edge synthetic
flows covering 384x854 and 854x384. The full disposable PostgreSQL/production-like Playwright suite
was not run in this source turn.

The original two browser flows covered shell/navigation/capture layout, not the new detail editing,
failure, conflict, trash, or restore interactions. The three new action unit tests only checked static
markup. Those passes did not prove that moving management into modal detail preserved the existing
guards. The 2026-09-05 audit reproduced delayed-save selection mixup and an inaccessible modal retry,
and identified graph-refresh draft loss, stale conflict recovery, and a duplicate `추가` E2E locator.

The corrective implementation binds save/retry completion to its selection and request generation,
keeps editors mounted during detail reload, scopes save errors and stable-request retries inside the
dialog, and requires an explicit draft-retention choice after loading a newer revision. That choice
does not save; the user must still press save. Dirty edits disable pin changes and retain the selected
detail if a bounded graph refresh removes its root, without injecting it into the graph projection.
The full-stack and deployment gates remain pending; focused synthetic evidence is recorded separately
in the handoff and roadmap rather than relabeling the original two flows.

## Consequences

Capture becomes visually quiet and task-specific. The graph remains the primary browsing model,
while private search provides a bounded escape hatch for material that cannot honestly be shown in
the home projection. Removing the card list does not remove edit, proposal, trash, or restore
capabilities, and it does not broaden model authority.
