# ADR 0010: EVENT temporal-binding contract preparation

- Status: Accepted for dark-compatible source preparation; activation requires independent human approval
- Date: 2026-08-24
- Product status: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Deployment status: personal database migration, rebuild, product smoke, and analyzer activation are not authorized

## Context

Milestone 6A.1 added a canonical EVENT schedule only after an explicit reviewed Apply. Proposal
schema version 2 can identify dates and bind one precise date to a TASK due, but it cannot describe
which date is an EVENT start or end. The public version-2 evaluation data also has no independently
reviewed EVENT temporal-binding labels. In particular, a `DATE_ONLY` mention does not prove an
all-day intent, and a natural-language inclusive last day is not the same value as the canonical
exclusive all-day end.

The existing evaluation "version 3" overlay is exclusively a TASK-due dataset overlay. It cannot be
reinterpreted as proposal schema version 3 or as EVENT evidence.

## Decision

1. Add proposal schema version 3 as a backward-compatible contract preparation. Every v3 item has
   `eventScheduleCandidates` and `suggestedEventScheduleCandidateId`. Version 1 and version 2 forbid
   both fields.
2. `eventScheduleCandidates` is a bounded list of strict alternatives. Each alternative has its own
   proposal-local ID, an explicit `TIMED` or `ALL_DAY` mode, a start date-candidate ID, an optional
   end descriptor, and an untrusted score. All references are by ID; array position is never a
   temporal binding.
3. The end descriptor is either null or an exact date-candidate reference plus one of:
   - `EXCLUSIVE_AT_VALUE`: the candidate value is already the canonical exclusive boundary;
   - `INCLUSIVE_THROUGH_VALUE`: for `ALL_DAY` only, the candidate names the included last day and
     validation derives the exclusive boundary by adding one calendar day.
   `TIMED` permits only `EXCLUSIVE_AT_VALUE`. Overflow, a non-later normalized end, incompatible
   precision, dangling references, duplicate semantic alternatives, and invented ends fail closed.
4. A non-EVENT item must have an empty candidate list and null suggestion. Multiple distinct EVENT
   alternatives require the proposal-level `CONFLICTING_DATES` signal. A candidate list may be empty
   when the source is unresolved.
5. `suggestedEventScheduleCandidateId` is an explicit future preselection pointer and never falls
   back to list order or score. The current production domain gate rejects every non-null value.
   Enabling it requires a separately approved policy, independently reviewed labels, frozen
   thresholds, and a held-out release.
6. `FakeAnalyzer` remains proposal schema version 2, and the localhost LiquidAI semantic-patch
   adapter remains version 2. Neither producer emits an EVENT temporal binding in this slice.
7. The PWA negotiates a maximum understood proposal version of 3 and can display v3 alternatives,
   but every review draft still starts with `eventSchedule = null`. Only a separate user action on a
   displayed alternative copies its normalized value into the editable schedule. Apply remains the
   existing explicit selection-schema-v2 operation; no automatic Apply or calendar side effect is
   added.
8. Read negotiation is monotonic. No header still returns strict v1. A max-v2 client receives a v2
   projection of stored v3 with the two EVENT fields removed; a max-v1 client additionally loses
   date IDs and TASK due references. Historical v1/v2 proposals are never synthesized upward.
9. Add a separate strict, ID-only EVENT temporal-binding evaluation-overlay contract and structural
   integrity validator. They may use only in-memory test data. No filled overlay, reviewer manifest,
   adjudication, score, threshold, or `PASS` is created by this source preparation.
10. Proposal JSON stays in the existing JSONB/run schema, so this contract needs no Flyway migration
    and rewrites no historical proposal. The source remains `SOLO_PROVISIONAL` / `REPORT_ONLY`.

## Consequences

- A future analyzer can represent multiple explicit alternatives without hiding ambiguity or
  guessing by array order.
- Inclusive all-day ends cannot be silently shortened by treating the mentioned last day as an
  exclusive boundary.
- Current users see no analyzer-selected schedule because current producers remain v2 and the
  non-null suggestion gate is closed.
- Existing v1/v2 clients and stored proposals retain their exact behavior.
- Temporal-candidate-bearing v3 proposals and schedule-bearing selections remain
  `UNCLASSIFIABLE`; Apply is not correctness gold.

## Activation gate

Before any analyzer emits v3 EVENT candidates in a product run, and especially before a non-null
suggestion may become a review default, all of the following are required:

- independent approval of the EVENT label policy;
- two genuinely independent label passes and human adjudication;
- explicit coverage of timed/all-day intent, missing time, optional end, inclusive/exclusive end,
  multiple events/dates, ambiguity alternatives, DST gaps/overlaps, and unbound dates;
- a frozen report-only metric definition and numeric thresholds chosen before candidate output is
  inspected;
- a separately held release;
- source-time-zone-aware validation of proposed timed offsets before any review prefill;
- a separate product/deployment decision.

Fine-tuning, LoRA, RAG ingestion, provider activation, alarms/reminders, iCalendar publication, and
automatic Apply remain outside this decision.
