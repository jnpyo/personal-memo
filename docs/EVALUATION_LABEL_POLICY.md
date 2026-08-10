# Evaluation label review and TASK-due binding policy

## Status

`DRAFT_REQUIRES_INDEPENDENT_HUMAN_APPROVAL`

This document defines fail-closed preparation for two future human-review checkpoints. It is not an
approved label policy, an adjudication record, a version-3 dataset, a binding-quality result, or a
provider gate. No completed reviewer manifest or binding overlay is currently checked in. The
deterministic/Fake analyzer remains the only analyzer, and no local model or cloud LLM is authorized
by this draft.

The code can validate structure, coverage, release identity, and aggregate agreement. It cannot prove
that a token belongs to a person or that two people worked independently. Opaque reviewer tokens and
boolean attestations are human process attestations, not cryptographic evidence. An Agent must not
create, fill, approve, or duplicate a human reviewer manifest.

## Data boundary

The workflow is limited to the two committed public synthetic version-2 fixture files. It must not
read the product database, an owner export, browser drafts, personal memos, or a separately held
`BLIND` release. Review inputs stay outside the repository. They contain case IDs, one release digest,
opaque protocol/policy identifiers, opaque reviewer tokens, attestations, and verdicts only. They do
not contain memo content, notes, titles, date surfaces, source spans, comments, proposed corrections,
analyzer output, metrics, filesystem paths, owner IDs, or content hashes per case.

All fixture reading and reviewer presentation must be explicitly UTF-8. Existing source spans remain
half-open UTF-16 code-unit ranges over the exact public fixture content. Review tooling must highlight
those spans; reviewers must not infer offsets from a mojibake terminal or count Unicode code points.

## Immutable public version-2 release

The review verifier treats the ordered regression and `VISIBLE_CHALLENGE` arrays as one release. It
computes SHA-256 over canonical JSON with recursively sorted object keys, preserved array order, and
UTF-8 serialization. Whitespace and object-property order therefore do not change the digest, while
case order, content, gold, or metadata changes do. Both review manifests must pin that exact digest.

The version-2 review scope is exactly:

- date mention, surface, interpretation, primary, and emitted-candidate gold;
- acceptable item sets and item kind/title/action/object gold;
- item source-span gold.

Route, type, tag, ambiguity-signal, and TASK-due binding labels are outside this review protocol.
The strict review contract is `contracts/korean-memo-evaluation-review.schema.json` and has the fixed
kind `PUBLIC_V2_DATE_ITEM_GOLD` and protocol `public-v2-gold-review-v1`.

## Independent two-reviewer protocol for version 2

Before review begins, humans must freeze one public release ID, the canonical release digest, and one
approved label-policy version. Two different people then review the same complete 24-case universe.

Each reviewer must:

1. use a distinct non-identifying opaque token of at least eight characters;
2. inspect only the public synthetic source, base instant, time zone, and gold in the declared scope;
3. not inspect Fake/model output, the generated evaluation report, the other review, or fixture
   `notes` while deciding verdicts;
4. return exactly one `ACCEPT` or `CHANGE_REQUIRED` verdict for each of the three scoped groups in
   every case;
5. attest that the reviewer is human, worked independently, did not see analyzer or peer output, and
   reviewed public synthetic data only.

Reviewer tokens are compared case-insensitively. Both manifests must use exactly the same release ID,
release digest, label-policy version, protocol, scope, and case universe. Missing, duplicate, unknown,
or extra cases fail. A manifest with raw text, comments, paths, corrected labels, or any undeclared
field fails the strict JSON Schema.

`PublicGoldAdjudicationVerifier` emits only aggregate counts and one of two statuses:

- `CONSENSUS_ACCEPTED` when both reviewers accepted all scoped groups;
- `NEEDS_HUMAN_RESOLUTION` when any verdict differs or either reviewer requests a change.

The summary contains no case IDs, reviewer tokens, release/policy IDs, digest, path, comment, gold, or
raw text. It never chooses one review, merges a correction, or treats two matching
`CHANGE_REQUIRED` verdicts as resolution. Humans must resolve findings, change and re-freeze the
public release when necessary, and independently review the new digest again. Normal CI tests only
the verifier with clearly test-only objects; they do not claim that real reviews occurred.

## Version-3 TASK-due binding overlay

Version 3 is represented as an ID-only overlay on one immutable, independently reviewed version-2
release. This avoids duplicating or silently drifting the existing date and item gold. The strict
overlay contract is `contracts/korean-memo-binding-overlay.schema.json`.

The overlay pins:

- the base dataset version, public release ID, canonical release SHA-256, and version-2 adjudication
  policy version;
- a separately approved binding-label policy version;
- every public case ID and every acceptable version-2 item-set ID.

Each item set contains one or more complete assignment alternatives. An assignment is
`{itemGoldId, dueDateGoldId}`, where `dueDateGoldId` may be `null`. Whole alternatives are required:
independent per-item lists would accidentally admit invalid cross-products when two tasks and two
dates can be paired in only specific combinations.

The integrity rules are:

- the overlay release digest and complete case universe must exactly match the base version-2
  release;
- every acceptable item set appears exactly once;
- every alternative covers every emitted `TASK` in that item set exactly once;
- omitted overflow items, non-`TASK` items, and items from another acceptable set cannot appear;
- a non-null date must be an emitted date gold ID with at least one accepted `DATE_ONLY`,
  `EXACT_TIME`, or `RELATIVE_EXACT` interpretation;
- `APPROXIMATE` and `UNKNOWN` dates cannot be due bindings;
- multiple tasks may intentionally share one precise date;
- alternative IDs and normalized whole assignment alternatives must be unique;
- `RESOLVED` has exactly one alternative, while `USER_INPUT_NEEDED` has at least two.

Semantically, a due binding means the deadline of that specific TASK. An event date, record date,
reference date, intermediate milestone, or date in another clause is not a TASK due date merely
because it is nearby. A precise date may remain unbound. An ambiguous association must be represented
as complete alternatives rather than guessed. These semantic rules still require separate human
approval before anyone creates labels.

## Missing human gate for version 3

Overlay schema and integrity validation prove only that references are structurally consistent with
one frozen version-2 release. They do not prove that a binding is semantically correct or that the
overlay was independently reviewed. The version-2 review verifier must not be used to claim
version-3 binding adjudication.

A separate two-human binding-label freeze remains required. Each reviewer must label the overlay
without seeing Fake/model output or the other review, disagreements must be resolved by humans, and
the final overlay must be frozen before any analyzer is scored against it. That protocol and its
completed human inputs are intentionally absent from this preparation slice. Until they exist:

- there is no current version-3 dataset;
- `dateItemDueBinding` remains `SUPPORTED_NOT_SCORED_DATASET_V2`;
- no binding precision, recall, exactness, safety pass, or provider comparison may be claimed;
- overlay integrity success must not emit an `ADJUDICATED`, `SCORED`, or `PASS` status.

## Gates

The preparation code may hard-fail only structural and privacy boundaries: strict schemas, canonical
release digest, complete coverage, unique references, TASK-only assignment, precise emitted dates,
complete alternatives, distinct reviewer tokens, matching protocols, required attestations, and
aggregate-only verifier output.

Public version-3 binding quality remains report-only after real labels are frozen. A provider-quality
hard gate additionally requires a separately held, independently curated version-3 blind release and
thresholds approved before the first candidate output is inspected. The existing external blind
runner is version-2-only and must not be broadened by this draft.

## Agent-safe preparation and next human actions

The current Agent-safe slice consists only of strict contracts, test-resource mirrors, validators,
focused unit tests, and this human-unapproved draft. Test builders may create synthetic test-only
manifests and overlays in memory; those objects are not reviewer evidence or dataset labels.

The next valid steps require people:

1. independently approve or revise this policy;
2. freeze the public version-2 release and complete two genuinely independent review manifests
   outside Git;
3. resolve every requested change or disagreement and re-review any changed digest;
4. independently author and adjudicate a binding overlay with coverage for single due, unbound due,
   two-task/two-date, shared due, clause-local date, approximate/unknown date, non-task date, and
   genuinely ambiguous full alternatives;
5. only then add a content-free report-only binding evaluator; do not connect a real provider.

Focused preparation tests run from `backend/` with:

```text
mvn -Dtest=PublicGoldAdjudicationVerifierTest,EvaluationV3BindingGoldIntegrityTest test
```
