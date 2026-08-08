# Analysis evaluation

## Purpose and boundary

This checkpoint measures the deterministic/Fake analysis boundary before any real local model or
cloud LLM is selected. It does not authorize a provider, send memo text over a network, or let an
analyzer write canonical data.

The public baseline contains two version-2 synthetic splits:

- `fixtures/korean-memo-cases.json` is a 12-case **regression** set. Several cases are deliberately
  represented by fixture-specific branches in `FakeAnalyzer`, so this split protects known behavior
  but is not an unbiased accuracy estimate.
- `fixtures/korean-memo-challenge-cases.json` is a 12-case **visible challenge** set with split
  `VISIBLE_CHALLENGE`. A test prevents its text from reusing the currently known fixture-specific
  branch markers. Its failures are intentionally reported rather than hidden or rewritten to match
  the Fake implementation. Because the set is committed and visible, it is neither statistically
  independent nor a blind accuracy estimate.

Every case is validated by `contracts/korean-memo-evaluation-case.schema.json`. The test-resource
copies must remain structurally identical to the repository fixtures and contract.

The regression cases retain `expectedRoute` and `expectedSignals` for the product flow after
owner-scoped tag/alias resolution. `analyzerExpectedRoute` and `analyzerExpectedSignals` are the
fixed gold labels for the owner-neutral `FakeAnalyzer` boundary, where an otherwise known tag is
still a new-topic candidate. Version 2 also carries pre-annotated date and item gold, including
accepted alternatives where the memo is genuinely ambiguous. A runner must never change a gold
label or accepted alternative after seeing analyzer output.

## Running the baseline

From `backend/`:

```text
mvn -Dtest=DeterministicEvaluationBaselineTest test
```

The test runs automatically as part of normal Maven test/verify and writes:

```text
backend/target/evaluation/deterministic-baseline.json
```

The generated report contains only dataset case IDs, split names, versions, labels, booleans, and
aggregate counts/rates. It contains no memo body, fixture text, title, note, content hash, user ID,
or owner ID. The test fails if any fixture content appears in the report. After a successful backend
run, CI uploads this generated report for 14 days so a checkpoint can be inspected without
publishing raw evaluation text.

## Metrics

Metrics are reported separately for regression, `VISIBLE_CHALLENGE`, and the combined set, keyed by
`analyzerVersion`, `deterministicRulesVersion`, and `routingPolicyVersion`.

- **Schema/domain-valid rate**: proposals accepted by the version-1 proposal JSON Schema and the
  production domain validator.
- **Route confusion**: expected/actual `LOCAL_REVIEW` and `CLOUD_ENRICH` counts and accuracy.
- **Wrong local**: an actual `LOCAL_REVIEW` result whose schema, route, preferred type, or complete
  ambiguity-signal set disagrees with the fixed gold label. This approximates the primary safety
  failure: a wrong decision presented as unambiguous.
- **Type**: preferred-type accuracy, candidate-set exactness, and candidate precision/recall.
- **Signals**: micro precision, recall, F1, and exact-case rate over ambiguity reasons.
- **Date**: normalized value/precision/time-specified agreement, annotated mention/surface matching,
  invented precise-date safety failures, and overflow that was incorrectly routed to local review.
- **Item**: resolution, complete acceptable-set agreement, and title/action/object/source-span
  agreement without copying analyzer-generated values into gold.

Date, item, item-source-span, and semantic false-confident-local metrics are now reported. Their quality
rates remain diagnostic until the labels have independent two-person adjudication and an external
blind run. Title prose quality, relations, and tag ranking are not provider-selection metrics at this
checkpoint.

## Current observed checkpoint

The first `fake-v3` challenge baseline reported 9 wrong-local cases, route accuracy `0.583333`,
preferred-type accuracy `0.25`, and signal recall `0.25`. `fake-v4` / `korean-rules-v2` replaces the
missing fallback behavior with general action, reference, event, weekday/time, and approximate-date
rules. It is protected by different-wording unit cases, negative substring/date cases, and a source
check that rejects copied full challenge sentences or three-token challenge branches.

`fake-v5` / `korean-rules-v3` adds source-aligned UTF-16 item spans, sequential action facets, explicit
three-item truncation after full detection, and fail-closed alternative handling. These changes do not
turn the public fixture into blind evidence; the generated version-2 report remains the only current
measurement artifact and its item quality fields remain diagnostic.

The current generated report has schema/domain-valid proposals for all 12 regression and 12 visible
challenge cases. Item cardinality matches in 12/12 cases in each split, and required source spans match
15/15 regression items and 14/14 visible-challenge items. Both splits have zero semantic
false-confident-local cases, invented precise-date cases, missing overflow signals, and hallucinated
action/object values where gold requires unresolved fields. These are public synthetic diagnostics,
not independently adjudicated or blind accuracy.
The remaining mismatches in acceptable item semantics and ambiguity signals stay visible in the report
and are not promoted to provider gates.

The generated deterministic report is the source for current measured values. It now exposes the
date and item failures rather than hiding missing spans or unresolved fields behind route/type
success. The visible challenge remains report-only even when its current counts are green. Public
synthetic coverage does not estimate general Korean accuracy and is not evidence that the Fake
analyzer or a future provider passed a blind evaluation.

## Automated gates

The current CI gate is deliberately narrow:

- all regression proposals must pass proposal schema and production domain validation;
- regression route/type/signal `wrongLocal.count` must be zero;
- regression `dates.inventedPreciseDateCaseCount` must be zero;
- regression local-review candidate-overflow count must be zero;
- regression missing-overflow-signal count must be zero;
- regression unresolved action/object hallucination count must be zero.

Visible-challenge results are report-only. A failing case is evidence for a general deterministic rule,
parser improvement, or a correctly bounded escalation rule; it must not be fixed by copying the
challenge phrase into `FakeAnalyzer`. Complete date/item/item-source-span quality rates and semantic
false-confident-local counts remain report-only until the labels receive two-person adjudication and
an independently held blind run. Thresholds must be approved before examining that blind run; they
must not be chosen to fit observed output.

## Separately held blind evaluation

The external blind dataset is a separately controlled release, not a third public fixture split.
It must be written and frozen by independent human curators before the candidate is evaluated.
Codex-generated, developer-generated, or analyzer-generated synthetic sentences cannot be described
as blind evidence. The curator must keep the dataset outside the repository, pull-request artifacts,
ordinary logs, the product database, and any owner raw-memo export.

The external file is a version-2 envelope:

```json
{
  "datasetVersion": "2",
  "releaseId": "opaque-curator-release",
  "labelPolicyVersion": "pre-registered-policy",
  "sourcePolicy": "INDEPENDENT_HUMAN_CURATED",
  "cases": []
}
```

Every enclosed case must independently repeat `split: "BLIND"` and
`sourcePolicy: "INDEPENDENT_HUMAN_CURATED"`. The initial runner requires at least 50 cases and
validates every case against the repository's version-2 evaluation schema. `releaseId`,
`labelPolicyVersion`, the minimum sample size, and metric thresholds must be frozen and approved by
people before the first candidate output is inspected. The current harness deliberately reports the
metric gate as `NOT_CONFIGURED`; it cannot claim `PASS` until a separately reviewed, pre-registered
blind-gate policy is implemented. `sourcePolicy` is a curator attestation, not cryptographic proof of
independent authorship. The curator-assigned `releaseId` must be an opaque label; it must
not encode raw text, a case identifier, or a content/ID hash. Blind case and gold identifiers must
contain at least four characters so the summary's substring leakage check remains fail-closed.

From `backend/`, run it only against a clean, fixed candidate commit:

```powershell
$env:PERSONAL_MEMO_BLIND_DATASET = '<absolute-path-outside-the-repository>'
$env:PERSONAL_MEMO_CANDIDATE_COMMIT = (git rev-parse HEAD).Trim()
try {
  mvn clean -Dtest=ExternalBlindEvaluationRunner test
} finally {
  Remove-Item Env:PERSONAL_MEMO_BLIND_DATASET -ErrorAction SilentlyContinue
  Remove-Item Env:PERSONAL_MEMO_CANDIDATE_COMMIT -ErrorAction SilentlyContinue
}
```

`ExternalBlindEvaluationRunner` does not match the ordinary Surefire test-name patterns and is not
run by normal `test` or `verify`. The two one-run environment variables keep the external path out
of Surefire's serialized system-property element and must be removed in `finally`. It fails closed
when either variable is absent, the worktree is dirty, the candidate commit is not the current
`HEAD`, the input or a path component resolves through a symbolic link, or the real input path is
inside the repository. It also rejects malformed envelopes, non-blind cases, and any public-fixture
ID or content duplicate without printing the offending value. No network or provider credential is
used; the candidate is the deterministic `FakeAnalyzer` only.

The `clean` lifecycle is mandatory: it removes stale compiled test classes before evaluating the
pinned source. The runner checks the commit and worktree again before writing the summary, but it is
not a substitute for an isolated clean checkout or build attestation.

Only a successful integrity run may create:

```text
backend/target/evaluation/blind-summary.json
```

That report is aggregate-only and allow-listed. It contains the envelope versions, aggregate counts
and rates, the exact candidate commit, and server-owned analyzer/rules/routing provenance. It must
not contain raw text, a case ID, a content or ID hash, a source span, a per-case label/result, an
owner/user/memo identifier, or a filesystem path. The runner checks the serialized report against
the input values and deletes the report on every validation, privacy, execution, or write failure.

Public CI must not receive the blind dataset as a secret, invoke the external runner, or upload its
report or test diagnostics as an artifact. Public CI may only run a leakage guard that fails if a
tracked JSON file contains a blind case marker. A green public build therefore never means that a
blind release passed; an authorized curator/operator must retain the aggregate result separately.

## Privacy and real-use evidence

Committed evaluation data is synthetic. Raw personal memos, production database exports, and
review selections must not be copied into Git, ordinary logs, CI artifacts, or this generated
report.

Future real-use evaluation must be explicitly owner-authorized and remain local by default. Keep any
raw evaluation export in an ignored, owner-controlled directory outside the repository; publish only
aggregate metrics or manually de-identified, separately approved examples. A content hash is not a
substitute for de-identification and is also excluded from the baseline report.

The current database preserves the original proposal and the applied `selection_json`, so applied
type/title/tag/item changes can be compared locally. The owner-scoped review-outcome summary now
reconstructs the current review default and reports aggregate `EXACT`, `CORRECTED`,
`USER_RESOLVED`, and `UNCLASSIFIABLE` evidence without returning memo text, selections, or IDs.
It also keeps current proposal state and latest application state separate. A rejected run records
the terminal state but not a corrected target, and the UI's “아니오, 다른 경우 보기” click is not a
semantic rejection. Do not treat those events as equivalent labels, and do not describe `EXACT` as
AI accuracy: it only means that the latest stored application selection matched the review default
under the versioned comparison policy. That latest application may now be `UNDONE`, so `EXACT` alone
is not a count of currently retained accepts. The independent outcome and application-state totals
also do not reveal their intersection. Product conclusions still require enough owner-authorized
cases and a separately reviewed blind evaluation set.

## Gate before a real LLM

A real provider remains blocked until all of the following are true:

1. At least 1–2 weeks and roughly 50–100 personally reviewed memos exist; exact and corrected
   outcomes must be distinguishable by latest `APPLIED` versus `UNDONE` state, while rejects and
   postponements remain separate, without exposing memo text.
2. The representative evaluation set is expanded beyond fixture-specific rules, date/item gold is
   independently adjudicated, tag gold is complete for the provider task, and the wrong-local safety
   threshold is approved from measured results rather than chosen after seeing provider output.
3. Memo-text transfer consent, allowed provider/region, retention/deletion policy, provider and model
   provenance, per-request context/tool/token/time limits, monthly budget, and outage behavior are
   explicitly decided and enforced fail-closed.
4. A Shadow mode can persist validated proposals and metrics without applying them; only explicit
   user approval may continue to create tags, tasks, or relations.
5. Fake failure tests cover timeout, retry exhaustion, invalid structured output, stale revision,
   and raw-memo survival before any provider credential is configured.

## Known Milestone 2 blockers

These are documented blockers, not features implemented by this baseline:

- Runtime `AnalysisRoute` currently implements only `LOCAL_REVIEW` and `CLOUD_ENRICH`.
  `USER_INPUT_NEEDED` and `PENDING_OFFLINE` in the pipeline document are conceptual states, not
  executable routes yet.
- Analysis starts synchronously. Successful runs are inserted directly as `REVIEW_REQUIRED`, while
  a gateway failure rolls back the run. Therefore queued/running/failed lifecycle duration, timeout,
  retry, and failure metrics are not yet persisted.
- `user_settings.cloud_analysis_consent` exists, but the current analysis service does not enforce it
  because the Fake cloud gateway performs no network transfer. A real gateway must be impossible to
  call without explicit consent.
- The cloud gateway interface does not yet expose server-owned provider/model provenance, token or
  cost usage, or bounded timeout/retry results.
- Top-k owner-scoped retrieval context is not implemented.

Do not connect a real LLM merely to make these roadmap bullets appear complete.
