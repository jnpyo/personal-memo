# EVENT temporal-binding label policy

## Status

`DRAFT_REQUIRES_INDEPENDENT_HUMAN_APPROVAL`

This document is Agent-safe mechanical preparation for Milestone 6A.2. It is not an approved label
policy, a reviewer instruction approved by people, a completed review, an adjudicated dataset, a
binding-quality result, a threshold decision, a provider gate, or permission for an analyzer or model
to preselect an EVENT schedule. No filled EVENT temporal-binding overlay or reviewer manifest is
checked in.

The existing `korean-memo-binding-overlay.schema.json` is a separate TASK-due contract. It must not be
reinterpreted or broadened into EVENT temporal authority. The strict structural contract introduced by
this preparation is `contracts/korean-memo-event-temporal-binding-overlay.schema.json`, whose EVENT
evaluation dataset lineage starts at `datasetVersion` `4` to avoid colliding with TASK-due dataset v3.
This dataset-v4 identifier is unrelated to, and does not rename or advance, proposal schema v3.

## Data and authority boundary

- The preparation code may read only the committed public synthetic version-2 fixture release.
- It must not read the product database, personal memos, browser drafts, owner exports, canonical
  selections, a private reviewer input, or a separately held blind release.
- The overlay is ID-only. It contains release/policy identifiers, case/item-set/item/date gold IDs,
  resolutions, and complete assignment alternatives. It contains no raw memo, note, title, action,
  object, source text, path, owner ID, analyzer output, correction, or per-case metric.
- Structural validation can prove release identity, coverage, reference compatibility, alternative
  completeness, and range safety. It cannot prove semantic correctness, reviewer identity,
  independence, policy approval, adjudication, or quality.
- An Agent may create schemas, validators, and clearly test-only in-memory objects. An Agent must not
  create, fill, approve, duplicate, merge, or resolve a human EVENT label or reviewer manifest.

## Overlay semantics requiring human approval

The overlay pins one immutable public version-2 release digest and its independently approved base
date/item adjudication policy. Every public case and every acceptable item set appears exactly once.

Each item set contains one or more whole assignment alternatives. Every alternative assigns every
emitted `EVENT` exactly once and assigns no other item kind. Independent per-field candidate lists are
not used because they would admit invalid cross-products across multiple EVENTs, starts, and ends.

An assignment contains `itemGoldId` and an explicit `schedule`:

- `null` means that the alternative authorizes no safe schedule preselection for that EVENT. It does
  not mean the EVENT is wrong, and it does not invent a missing date or time.
- A non-null schedule declares `mode`, `startDateGoldId`, and an explicit `end` that is either `null`
  or a complete end object. An omitted end is invalid; validators never infer a duration.
- `TIMED` start/end references must resolve to emitted `EXACT_TIME` or `RELATIVE_EXACT` gold. A TIMED
  end uses `EXCLUSIVE_AT_VALUE` only.
- `ALL_DAY` start/end references must resolve to emitted `DATE_ONLY` gold. An ALL_DAY end may use
  `EXCLUSIVE_AT_VALUE` or `INCLUSIVE_THROUGH_VALUE`.
- `INCLUSIVE_THROUGH_VALUE` is normalized to the next exclusive calendar date for validation. Date
  overflow fails closed. Every accepted normalized end interpretation must be after every accepted
  start interpretation.
- `RESOLVED` has exactly one whole alternative. `USER_INPUT_NEEDED` has at least two distinct whole
  alternatives. Matching array position never establishes a binding.

Mode is separate gold. A `DATE_ONLY` mention near an EVENT does not by itself prove all-day intent;
it may instead mean that a time is missing. Likewise, an EVENT kind does not itself authorize a
canonical schedule, alarm, reminder, recurrence, or external calendar side effect.

## Mechanical integrity checks

The preparation validator may fail only structural or temporal-safety boundaries:

- strict JSON Schema and synchronized repository/test-resource copies;
- exact immutable base-release digest and complete case universe;
- exact acceptable item-set coverage;
- EVENT-only, same-item-set assignments with every emitted EVENT covered exactly once;
- emitted and mode-compatible start/end date references;
- explicit null end rather than an inferred end;
- TIMED exclusive-end and ALL_DAY inclusive/exclusive normalization rules;
- end-after-start and inclusive-end calendar overflow;
- unique alternative IDs and unique normalized whole alternatives;
- `RESOLVED`/`USER_INPUT_NEEDED` alternative cardinality; and
- rejection of raw-content or undeclared fields.

A green integrity test means only that a test-only object satisfied these mechanics. It must not emit
`ADJUDICATED`, `SCORED`, `PASS`, a quality rate, or permission to change product behavior.

## Proposed report-only metrics

The following names are draft measurement dimensions only. Every numeric threshold and every hard
gate remains `NOT_CONFIGURED` until people approve them before inspecting the first candidate output.

| Metric name | Intended aggregate meaning | Threshold status |
| --- | --- | --- |
| `eventScheduleAlternativeExactCaseRate` | Candidate assignments match one complete acceptable alternative | `NOT_CONFIGURED` |
| `eventStartBindingPrecision` / `eventStartBindingRecall` | Correct EVENT-to-start associations | `NOT_CONFIGURED` |
| `eventEndBindingPrecision` / `eventEndBindingRecall` | Correct optional EVENT-to-end associations | `NOT_CONFIGURED` |
| `eventModeExactRate` | TIMED versus ALL_DAY intent agreement | `NOT_CONFIGURED` |
| `eventBoundaryExactRate` | End-boundary intent agreement after normalization | `NOT_CONFIGURED` |
| `eventUserInputNeededRecall` | Ambiguous cases kept out of automatic preselection | `NOT_CONFIGURED` |
| `inventedPreciseEventStartCount` | Precise starts emitted without acceptable gold | `NOT_CONFIGURED` |
| `inventedOrInvalidEventEndCount` | Unsupported, inferred, overflowing, or non-forward ends | `NOT_CONFIGURED` |
| `falseConfidentEventPreselectionCount` | Wrong schedule presented as resolved | `NOT_CONFIGURED` |

Even after real public labels exist, these metrics start as `REPORT_ONLY`. A provider-quality hard
gate additionally requires a separately held, independently curated EVENT dataset-v4 release,
pre-registered sample size and thresholds, and an aggregate-only evaluator approved before the first
candidate run. The current version-2 blind runner must not be broadened by this draft.

## Missing human gate and required order

People must complete these steps in order:

1. approve or revise this EVENT policy and the overlay semantics;
2. freeze the public version-2 release and complete two genuinely independent reviews of the base
   date/item/item-source-span gold outside Git;
3. resolve every requested base-gold change or disagreement, freeze the resulting digest, and repeat
   independent review when the digest changes;
4. predeclare EVENT coverage requirements, reviewer protocol, ambiguity rules, metric definitions,
   minimum sample size, and numeric thresholds without inspecting analyzer/model output;
5. have two different people independently author EVENT temporal assignments without seeing Fake,
   model, or peer output, then have people resolve disagreements and freeze the final overlay;
6. independently curate and hold a blind EVENT dataset-v4 release outside Git and product data; and
7. only then add and run a content-free, aggregate-only report evaluator. Product preselection remains
   disabled until a separate product decision accepts the resulting evidence.

The current public fixtures contain too little EVENT variety to establish a general quality gate.
Human coverage planning must include at least no-safe-schedule, TIMED, explicit ALL_DAY intent,
missing versus explicit end, inclusive/exclusive all-day range, multiple EVENT/date clause-local
association, shared dates, ambiguous complete alternatives, approximate/unknown dates, and source-zone
DST gap/overlap safety. This list is a coverage proposal, not a label or an approved threshold.

## Focused preparation verification

The structural tests use only in-memory values clearly marked test-only and do not write a fixture,
overlay, manifest, report, or product record:

```text
cd backend
mvn -Dtest=EvaluationV4EventTemporalBindingGoldIntegrityTest test
```

Until the human steps are complete, status remains `SOLO_PROVISIONAL` / `REPORT_ONLY`; EVENT binding
quality is `NOT_SCORED`, thresholds are `NOT_CONFIGURED`, and automatic schedule preselection is not
authorized.
