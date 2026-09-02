# ADR 0020: Capture-day future-clock default

## Status

Accepted and implemented in source on 2026-09-02. Focused parser/analyzer/validator tests, the
complete non-PostgreSQL backend unit suite, frontend lint/unit/type/PWA build, and the v2
product-smoke source-contract gate pass. PostgreSQL integration, isolated Playwright, and actual
Docker/Ollama product smoke remain pending. The result is `SOLO_PROVISIONAL` / `REPORT_ONLY` and does
not authorize a personal-stack deployment, personal-data inspection, canonical Apply, or production
acceptance.

## Context

ADR 0009 kept a date-less clock such as `6시` unresolved because the application had no approved
policy for choosing a date or AM/PM occurrence. ADR 0019 carried that conservative rule into the
graph-first mobile capture experience, and Milestone 7.3 added an explicit date/AM-PM clarification
surface without changing analyzer semantics. Those checkpoints and their verification records remain
historical facts.

The owner has now supplied a product default: an explicit clock without a date refers to the day the
memo revision was recorded, and among the clock's AM/PM occurrences on that day the application
should use the closest occurrence that has not already passed. This decision must remain stable when
analysis or review happens later, on another device, or in another time zone. It must also preserve
the existing proposal, ownership, revision, idempotency, and manual Apply boundaries.

## Decision

1. Identify the new deterministic behavior as `fake-v10` / `korean-rules-v8`. Continue producing
   proposal schema version 2 and keep historical stored proposal JSON unchanged.
2. Use the analyzed immutable memo revision's `client_recorded_at` and `source_time_zone` as the only
   temporal reference. Do not use proposal creation time, review time, `Date.now()`, server default
   zone, or the reviewing browser's current zone.
3. Cover the bounded date-less explicit clock family when it has no particle or only `에`: bare
   1–12시 with optional minutes, explicit 오전/오후 1–12시, Korean 24-hour clock text, and `HH:mm`.
   A bare 1–12시 clock derives both AM and PM local occurrences on the revision's capture date;
   explicit-meridiem and 24-hour forms derive only the stated local occurrence. Convert candidates
   through the revision source IANA zone, retain only occurrences whose instant is strictly greater
   than `client_recorded_at`, and propose the earliest remaining safe occurrence as
   `RELATIVE_EXACT`. Equality is not future.
4. Resolve DST per candidate. Discard a gap occurrence because it does not exist; a later unique
   same-capture-day occurrence may still be selected. If any overlap occurrence still has a future
   valid offset, fail the whole expression closed as `UNKNOWN` instead of choosing an offset. A
   fully passed overlap no longer blocks a later unique occurrence. Do not roll a date-less clock to
   the next day. If no safe capture-day occurrence remains or the source zone is missing/invalid,
   keep the candidate `UNKNOWN` and use explicit review.
5. Keep approximate, negative, descriptive, unsupported, incompatible, multiple-item, and
   multiple-temporal-candidate cases outside this narrow default unless a later separately versioned
   decision approves them.
6. Treat every resolved value as untrusted proposal data. Existing review may edit or remove it, and
   the ordinary owner-scoped manual Apply remains the only canonical mutation boundary. The policy
   does not authorize automatic Apply, an implicit EVENT schedule, a next-day rollover, recurrence,
   `VALARM`, Web Push, or any alarm/reminder creation or delivery.
7. Preserve prior evidence labels. Public synthetic, LiquidAI shadow, performance, deployment, and
   source-qualification records produced under fake-v8 or fake-v9 remain records of those versions;
   none may be renamed or counted as fake-v10 evidence.
8. Preserve the immutable AI-preferred product-smoke v1 fixture/schema/receipt and prepare a separate
   public-synthetic v2 source contract. V2 pins `2026-08-28T09:00:00+09:00` in `Asia/Seoul` and
   requires `6시 디스코드 접속하기` to produce one grounded TASK bound to the
   `2026-08-28T18:00:00+09:00` `RELATIVE_EXACT` candidate. Its receipt keeps Apply,
   alarm/reminder, personal-data access, and canonical-write deltas at zero. Until that v2
   Docker/Ollama product smoke runs and emits a validated receipt, it is source-contract preparation,
   not runtime or model evidence.

## Superseded scope

This ADR supersedes only the date-less-clock clauses in ADR 0009 and ADR 0019. It does not supersede
their PostgreSQL authority, manual EVENT schedule selection, explicit Apply, no-inferred-duration,
owner isolation, source-zone validation, deployment-authorization, or alarm/reminder boundaries.
Milestone 7.3 remains the fallback clarification experience whenever this narrow deterministic rule
cannot produce one safe proposal occurrence.

## Consequences

- A memo recorded before 07:00 in its source zone may propose that day's 07:00 for `7시`; a memo
  recorded at or after 07:00 but before 19:00 may propose that day's 19:00.
- A memo recorded exactly at an occurrence does not select that occurrence because the comparison is
  strict. If every same-day occurrence has passed, the candidate remains `UNKNOWN`.
- Delayed analysis and review do not change the interpreted date or AM/PM occurrence.
- A future DST overlap and source-zone uncertainty fail closed instead of silently adopting a
  browser offset; a nonexistent gap occurrence may be skipped only when a later unique same-day
  occurrence remains.
- The current JSON Schema, request shape, Flyway schema, canonical tables, and historical proposals
  do not require migration solely for this policy change.

## Remaining verification before deployment qualification

- Boundary tests immediately before, equal to, and immediately after AM and PM occurrences,
  including optional minutes and the `12시` midnight/noon conversion.
- Capture-day tests across UTC/local-date boundaries and delayed review to prove independence from
  system and browser clocks.
- Missing/invalid source-zone fallback, DST-gap discard with later-unique selection, no-safe-candidate
  fallback, and future-overlap fail-closed behavior with zero invented exact instants.
- Proposal schema/domain, grounded item binding, manual Apply, stale revision, idempotency, rollback,
  and no-canonical-write-on-invalid-selection regressions.
- Public synthetic evaluation must remain version-labeled and report-only; prior aggregates must not
  be relabeled as fake-v10 results.

## Deferred and out of scope

Korean word numerals, `부터`/`까지` role inference, approximate clocks, time ranges, automatic EVENT
temporal binding, automatic tomorrow rollover, recurrence, external calendar writes, alarm/reminder
persistence or delivery, Web Push, RAG, provider changes, training, fine-tuning, and LoRA.
