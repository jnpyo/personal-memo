# Analysis evaluation

## Purpose and boundary

This checkpoint measures the deterministic/Fake analysis boundary before any real local model or
cloud LLM is selected. It does not authorize a provider, send memo text over a network, or let an
analyzer write canonical data.

The baseline contains two version-1 synthetic splits:

- `fixtures/korean-memo-cases.json` is a 12-case **regression** set. Several cases are deliberately
  represented by fixture-specific branches in `FakeAnalyzer`, so this split protects known behavior
  but is not an unbiased accuracy estimate.
- `fixtures/korean-memo-holdout-cases.json` is a 12-case visible synthetic **challenge** set stored
  under the `HOLDOUT` split name. A test prevents its text from reusing the currently known
  fixture-specific branch markers. Its failures are intentionally reported rather than hidden or
  rewritten to match the Fake implementation. Because the set is committed and visible, it is not
  a statistically independent or blind accuracy estimate.

Every case is validated by `contracts/korean-memo-evaluation-case.schema.json`. The test-resource
copies must remain structurally identical to the repository fixtures and contract.

The regression cases retain `expectedRoute` and `expectedSignals` for the product flow after
owner-scoped tag/alias resolution. `analyzerExpectedRoute` and `analyzerExpectedSignals` are the
fixed gold labels for the owner-neutral `FakeAnalyzer` boundary, where an otherwise known tag is
still a new-topic candidate. The test runner never changes a gold label based on analyzer output.

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

Metrics are reported separately for regression, the visible challenge (`holdout`) split, and the combined set, keyed by
`analyzerVersion` and `routingPolicyVersion`.

- **Schema-valid rate**: proposals accepted by the version-1 proposal JSON Schema.
- **Route confusion**: expected/actual `LOCAL_REVIEW` and `CLOUD_ENRICH` counts and accuracy.
- **Wrong local**: an actual `LOCAL_REVIEW` result whose schema, route, preferred type, or complete
  ambiguity-signal set disagrees with the fixed gold label. This approximates the primary safety
  failure: a wrong decision presented as unambiguous.
- **Type**: preferred-type accuracy and recall of expected type candidates.
- **Signals**: micro precision, recall, F1, and exact-case rate over ambiguity reasons.

This version does not yet score title quality, exact date values, item boundaries, relations, or tag
ranking. Those labels must be added before their metrics are used for a model decision.

## Automated gates

The current CI gate is deliberately narrow:

- all regression proposals must be schema-valid;
- regression `wrongLocal.count` must be zero.

Challenge-set results are report-only. A failing case is evidence for a general deterministic rule,
parser improvement, or a correctly bounded escalation rule; it must not be fixed by copying the
challenge phrase into `FakeAnalyzer`. Thresholds become enforceable only after the rules and gold
labels receive a separate review and a separate blind set exists. Overall/type/signal rates are
diagnostic at this checkpoint, not release claims.

## Privacy and real-use evidence

Committed evaluation data is synthetic. Raw personal memos, production database exports, and
review selections must not be copied into Git, ordinary logs, CI artifacts, or this generated
report.

Future real-use evaluation must be explicitly owner-authorized and remain local by default. Keep any
raw evaluation export in an ignored, owner-controlled directory outside the repository; publish only
aggregate metrics or manually de-identified, separately approved examples. A content hash is not a
substitute for de-identification and is also excluded from the baseline report.

The current database preserves the original proposal and the applied `selection_json`, so applied
type/title/tag/item changes can later be compared locally. A rejected run records the terminal state
but not a corrected target, and the UI's “아니오, 다른 경우 보기” click is not a semantic rejection.
Do not treat those events as equivalent labels. Before drawing product conclusions, add a
server-derived, owner-scoped review-outcome summary without collecting a general clickstream.

## Gate before a real LLM

A real provider remains blocked until all of the following are true:

1. At least 1–2 weeks and roughly 50–100 personally reviewed memos exist; exact accepts, corrected
   applies, rejects, postponements, and undo must be distinguishable without exposing memo text.
2. The representative evaluation set is expanded beyond fixture-specific rules, date/item/tag gold
   is complete for the provider task, and the wrong-local safety threshold is approved from measured
   results rather than chosen after seeing provider output.
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
