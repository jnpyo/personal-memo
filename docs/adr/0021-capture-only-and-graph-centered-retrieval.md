# ADR 0021: Capture-only entry and graph-centered retrieval

## Status

Accepted and frontend-source-qualified for Milestone 7.5. Full disposable PostgreSQL/production-like
E2E, personal deployment, and physical-device acceptance remain separate gates. The result remains
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

Source verification covers the four labelled bottom-navigation controls, graph-first default view,
capture-only destination, graph-contained search disclosure, off-home/raw-only search access,
active and trashed detail actions, unsaved-edit guards, modal focus behavior, 48-pixel actions,
portrait/landscape safe areas, and horizontal overflow using synthetic data.

The completed bounded gate passed ESLint, TypeScript, 53 Vitest files with 532 tests, the Vite 7.3.6
production PWA build, the public-app source contract, and two backend-free Microsoft Edge synthetic
flows covering 384x854 and 854x384. The full disposable PostgreSQL/production-like Playwright suite
was not run in this source turn.

## Consequences

Capture becomes visually quiet and task-specific. The graph remains the primary browsing model,
while private search provides a bounded escape hatch for material that cannot honestly be shown in
the home projection. Removing the card list does not remove edit, proposal, trash, or restore
capabilities, and it does not broaden model authority.
