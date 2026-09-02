# ADR 0019: Graph-first mobile home and concise capture

## Status

Accepted and source-qualified as Milestone 7.2. A later, separately owner-authorized personal update
deployed the graph-first frontend while preserving V23 and the existing canonical volume. Owner visual
review is in progress. The result remains `SOLO_PROVISIONAL`/`REPORT_ONLY` and is not production
acceptance.

ADR 0020 later supersedes only this ADR's date-less-clock rule in Decision lines 47–48. The
graph-first hierarchy, concise capture, proposal-only/manual Apply, and deployment record remain
historical and authoritative for their original scope.

## Context

Milestone 7.1 source-qualified a Today-first mobile hierarchy with quick capture, today's unfinished
tasks, and confirmed events in the first scan path while keeping the graph secondary. That evidence
and checkpoint remain historical facts.

Owner feedback after reviewing that hierarchy asks for the confirmed knowledge graph to become the
default home and for explanatory status copy to be reduced. The requested change concerns navigation,
information hierarchy, and concise presentation. This source decision did not itself authorize a new
analysis producer, automatic semantic mutation, or a personal-stack deployment; deployment required
the later explicit owner approval and the existing guarded operator sequence.

The existing product already provides a bounded graph projection, raw memo capture and listing,
privacy-first search, tasks, confirmed events, and account settings. Reusing those capabilities keeps
the decision inside the frontend source boundary.

## Decision

Milestone 7.2 makes the confirmed, bounded MEMO-TAG connection map the default signed-in mobile home.
The graph remains a projection of canonical owner data, not a separate source of truth. It renders
only the existing bounded node budget and does not turn TASK, EVENT, INFORMATION, IDEA, or other
system types into global hub nodes.

Bottom navigation provides four destinations:

- the confirmed connection map;
- memo capture, the current raw-memo list, and privacy-first memo search;
- tasks and confirmed schedules; and
- settings and secondary diagnostics.

Every list, search, task, and schedule flow remains usable without understanding or operating the
graph. Selecting a graph node continues to highlight its visible neighborhood and open detail rather
than silently expanding the canonical dataset.

Capture remains concise: the primary save action persists the raw memo first, and background analysis
must not block another capture. Analysis output remains an untrusted proposal. A canonical tag, task,
EVENT schedule, or relation is created only by the existing explicit review and Apply operation.
Schedule alternatives remain unapplied until the user chooses one. A bare or date-less `6시` remains
`UNKNOWN`; the UI and analyzer must not infer today, AM/PM, a schedule, or an alarm.

Model, AI, recovery, and infrastructure diagnostics do not occupy the primary home hierarchy. A
compact same-origin reachability indicator may remain as a global utility, but any detailed diagnostic
surface stays secondary, lazy where applicable, and limited to evidence the current browser state can
actually prove. It must not present configured routes, dispatches, or aggregates as model availability,
correctness, improvement, database health, Tunnel health, or provider readiness.

## Boundaries

- No API, OpenAPI, JSON Schema, Flyway migration, persistence mapping, or canonical-data contract
  changes are part of Milestone 7.2.
- No automatic Apply, schedule preselection, tag/task/event/relation mutation, graph-node
  compression, or new analyzer/model policy is introduced.
- No owner session, personal memo, PostgreSQL row, canonical record, or Apply path is required for
  source qualification; frontend tests use synthetic state.
- No Docker rebuild or restart, personal volume access, backup or restore, installed-PWA refresh,
  Ollama action, Cloudflare Tunnel/Access/connector change, or other deployment operation is part of
  this decision.
- Milestone 7.1 remains a historical source-qualified checkpoint. Milestone 7.2 supersedes only its
  Today-first product hierarchy.

## Deployment record

The bounded source gate passed ESLint, TypeScript, 51 test files with 481 unit tests, the production
PWA build, the public-app source contract, and 27 disposable production-like Playwright flows. The
owner-authorized update then used a checksummed mechanical PostgreSQL backup, an isolated V23-to-V23
restore rehearsal, exact frontend and app-edge rollback tags, and connector-first rollback before
rebuilding only the frontend and loopback app edge.

Local exact-Host shell, service-worker, health, finite auth-capability, unauthenticated fail-closed, and
wrong-Host probes passed without inspecting an owner session or personal memo. The app connector was
started last; cookie-free remote root and health probes reached the Cloudflare Access boundary before
the application, remained non-cacheable, and were not cache hits. All four personal containers were
healthy, the calendar connector stayed stopped, and disposable restore/E2E resources were absent.
The owner has viewed the new interface and begun visual review, but has not supplied a final physical-
device acceptance result for this milestone.

## Consequences

The first signed-in screen emphasizes relationships among confirmed memos and tags, while capture and
action-oriented work remain one bottom-navigation choice away. Removing operational explanation from
the primary hierarchy reduces visual noise without deleting the guarded diagnostic capability.

Source verification must cover the graph-first shell, bottom-navigation reachability, raw-save-first
capture behavior, explicit review/Apply boundaries, keyboard and touch access, safe areas, portrait
and landscape layouts, and horizontal overflow using synthetic data. Passing source verification does
not qualify the personal deployment or installed PWA; those require a separate owner-authorized
update and acceptance record.
