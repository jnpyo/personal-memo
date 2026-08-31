# ADR 0009: Manual canonical EVENT schedule foundation

- Status: Accepted for source implementation; `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Date: 2026-08-24
- Deployment status: personal database migration, rebuild, and product smoke are not authorized

## Context

Read-only calendar export needs canonical schedule data before it can safely serialize or share an
event. Existing proposal schema version 2 has proposal-local date candidates and an explicit TASK
due binding, but it has no EVENT-to-date binding. Dataset version 2 likewise has no reviewed EVENT
temporal-binding gold. Treating array order or a nearby date as an EVENT schedule would therefore
turn an untrusted analyzer guess into canonical time data.

Existing EVENT items are also allowed to be title-only. A migration must not invent a start, end, or
duration for them, and EVENT start semantics must remain separate from TASK due semantics.

## Decision

1. Keep analysis proposal schema version 2 unchanged for Milestone 6A.1. Neither the Fake analyzer
   nor LiquidAI gains an EVENT temporal-binding output in this slice.
2. Initialize every EVENT review item without a schedule. The user may explicitly select one usable
   precise proposal `dateCandidate` as a convenience or enter a schedule directly. `DATE_ONLY`
   becomes an all-day start; `EXACT_TIME` or `RELATIVE_EXACT` becomes a timed start. No candidate is
   associated by array order and no end is inferred.
   `fake-v9` / `korean-rules-v7` may emit a source-zone `RELATIVE_EXACT` candidate only for an
   explicit `오늘|내일|모레 + 오전|오후 + 1–12시` expression (with optional minutes). A date-less
   time such as `6시` remains `UNKNOWN`; the analyzer does not invent today or PM.
3. Keep title-only EVENT application valid. An EVENT becomes scheduled only when the reviewed Apply
   item includes `eventSchedule` and the request declares `selectionSchemaVersion: "2"`. Apply
   requests without an event schedule retain the legacy selection shape.
4. Support two strict schedule shapes:
   - `TIMED`: an ISO 8601 offset `start`, optional later offset `end`, and an IANA `timeZone`;
   - `ALL_DAY`: an ISO calendar `start`, optional later exclusive `end`, and an IANA `timeZone`.
   A due value remains TASK-only and an event schedule remains EVENT-only.
5. Treat the request time zone as a validated compatibility field. Inside the same locked Apply
   transaction, replace it with the immutable memo revision's `source_time_zone` before persisting
   the selection and canonical schedule. For every timed start/end, require the supplied numeric
   offset to be one of the valid offsets for that local date-time in the immutable revision zone.
   Reject a DST gap; during a DST overlap accept either valid explicitly supplied offset.
6. Flyway V21 creates `event_details` without backfill. A composite foreign key through
   `(memo_item_id, owner_id, item_kind)` requires an owner-matched `EVENT` item. Database checks make
   the timed and all-day shapes mutually exclusive and require every supplied end to be after its
   start.
7. Create `analysis_applications`, `memo_items`, `event_details`, tags, links, and relations in the
   existing owner-scoped idempotent Apply transaction. Preserve the pre-schedule request-hash
   projections; schedule-aware requests use a distinct versioned hash shape. Undo deletes
   `event_details` before its source item while preserving the raw memo and immutable revisions.
8. Add signed-in `GET /api/v1/events` as a bounded, owner-scoped, `no-store` read. It returns only
   scheduled EVENT items from the current memo revision whose memo is active, item is unarchived,
   and application remains `APPLIED`. It returns title and schedule fields only, not raw memo,
   proposal, selection, application, or memo provenance. The bounded window is selected by most
   recent Apply before chronological display, so accumulated older rows cannot permanently hide a
   newly confirmed schedule.
9. The source implementation and documentation remain `SOLO_PROVISIONAL` / `REPORT_ONLY`. V21 has
   not been authorized for the personal database, and this ADR does not authorize a rebuild,
   deployment, or personal-data smoke.

## Consequences

- The current PWA can review and display explicitly confirmed schedules without waiting for an
  analyzer contract change.
- A proposal date candidate can reduce typing, but selecting it is a user action rather than an AI
  temporal-binding claim. Scheduled selections remain unclassifiable by the existing review-outcome
  accuracy policy until a versioned temporal-review policy exists.
- Existing title-only EVENT rows remain valid and do not appear in the scheduled-event list.
- Missing ends stay absent. There is no implicit one-hour duration, alarm, reminder, recurrence, or
  external calendar side effect.
- Explicit relative-day parsing is grounded in the revision's captured instant and source zone. It
  does not broaden a bare clock time into a precise date.
- The new relational table is canonical PostgreSQL state and remains subject to the same owner,
  revision, idempotency, rollback, and undo boundaries as other confirmed domain records.

## Deferred and out of scope

- **Milestone 6A.2:** proposal schema version 3, explicit EVENT temporal candidate binding, reviewed
  evaluation labels/metrics, automatic review defaults, and any analyzer/model schedule claim.
- **Milestone 6B and later:** RFC 5545 `.ics` preview/download, feed membership, bearer tokens,
  public feed edge, Google/Apple client smoke, and sharing policy.
- CalDAV, external provider writes or imports, bidirectional sync, recurrence, `VALARM`, automatic
  Apply, alarm/reminder persistence or delivery, training, fine-tuning, and LoRA.
