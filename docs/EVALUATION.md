# Analysis evaluation

## Purpose and boundary

This document's preserved checkpoint measures the deterministic/Fake boundary and public synthetic
LiquidAI diagnostics. It does not authorize an authoritative provider/model, training, or canonical
write. A separately invoked test-scope runner may query one already-installed LiquidAI model through
a fixed localhost Ollama endpoint using only the public synthetic fixtures below. ADR 0007 later
permitted a personal-only `SOLO_PROVISIONAL`/`REPORT_ONLY` uncertainty fallback. ADR 0008 now
supersedes only that personal invocation policy with `AI_PREFERRED` and a bounded approved-type
anchor hint. The application default remains Fake + `UNCERTAINTY_ONLY`, and neither product decision
retroactively converts these diagnostic results into accuracy or provider readiness.

Milestone 6A.1 is a manual product-contract slice, not a new analyzer evaluation cohort. Current
analyzer output and public evaluation fixtures remain v2. Milestone 6A.2a adds a dark-compatible
proposal-v3 contract and an empty EVENT-label preparation boundary, not a new scored cohort. Both
remain `SOLO_PROVISIONAL`/`REPORT_ONLY`.

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

V17's internal gateway-attempt ledger does not change this report contract. The deterministic and
blind reports contain no attempt rows, duration, execution state, token/cost status, provider/model
identifier, or provider-request token; operational evidence is not silently promoted to an accuracy
metric.

## Solo LiquidAI shadow diagnostic

The `SoloLiquidAi*` explicit, test-only runner family is not selected by normal Maven test/verify or
CI patterns. Its external orchestrator must bind Ollama only to each runner's fixed loopback endpoint,
supply the exact opt-in/model/digest/source and hardware preflight values, collect any device-wide GPU
observations, and restore the Ollama process and temporary resources. Each runner verifies the pinned
model metadata and digest, accepts exactly the 12 regression and 12 `VISIBLE_CHALLENGE` public
synthetic version-2 fixtures, runs sequentially without retry or tools, and retains schema/domain
guards appropriate to that diagnostic. No runner starts through a product adapter, calls the Personal
Memo API, reads PostgreSQL, persists a proposal, or invokes Apply.

The later `SoloLiquidAiDeterministicSkillRunner` and v7-A/v7-B/v8-A diagnostic runners use the same
test-only restoration boundary but evaluate much narrower selection envelopes over an authoritative
deterministic proposal. Their metrics are documented separately below and must not be combined with
v1–v5 direct proposal-generation metrics.

### Baseline v1

The preserved v1 report is
`backend/target/evaluation/solo-liquidai-shadow-baseline.json` (SHA-256
`360660c5e283f719465262088e91b168a88dea27944a0e61c5fcd065a830b020`). It is a bounded,
aggregate-only artifact labeled `SOLO_PROVISIONAL`, `REPORT_ONLY`, and `NOT_CONFIGURED`. The public
fixtures are visible, not independently human-adjudicated, not blind, and not training or validation
data. The measured checkpoint is:

- 24 scored requests produced 18 completed responses; all 18 completed responses passed the narrow
  inference-output schema, while 3/24 assembled proposals passed the canonical proposal schema and
  1/24 passed production domain validation;
- LiquidAI recorded 9 wrong-local cases, 11 invented precise-date cases, 1 missing overflow signal,
  and 2 unresolved-field hallucination cases;
- successful-response LiquidAI wall latency was p50 `15451.417 ms`, p95/max `33236.766 ms`, and mean
  `17431.567 ms`; the 24-case Fake comparison was p50 `0.453 ms` and p95 `1.581 ms`;
- Ollama reported an allocation of `3166835834` bytes with context length `8192`;
- a separate, device-wide external sampler collected 853 observations: baseline `3501 MiB`, peak
  `6990 MiB`, peak utilization `92%`, and post-run `3543 MiB`. These samples were not process- or
  model-exclusive, so their delta must not be attributed solely to LiquidAI. The generated report
  itself therefore does not claim an exclusive peak allocation;
- the runner recorded restored model state and no remaining temporary report file. Ollama process
  lifecycle cleanup remained the external orchestrator's responsibility.

The resulting decision was `NO_GO_FOR_TRAINING`; prompt/schema iteration was then `RECOMMENDED`.
That historical recommendation is closed by the v8-A authoritative LiquidAI `NO_GO`. ADR 0007's
later personal uncertainty fallback was a separate bounded decision; ADR 0008 now supersedes only
its personal invocation policy with `AI_PREFERRED` and approved-type hints.
This run does not authorize a product adapter, provider use, fine-tuning, training-tool installation,
or a provider-entry `PASS`.

### Prompt/schema iteration v2

The v2 development iteration keeps the same model, visible 24-case corpus, sequential no-retry/no-tool
execution, canonical JSON Schema, production domain validation, proposal-only boundary, and Fake
comparison. It changes only the test-side prompt/inference schema and runner integrity controls. It
does not install or invoke a trainer, fine-tune the model, add a product adapter, or change an API,
database, Flyway migration, canonical record, or Apply path.

Artifacts and fixed identities:

| Evidence | SHA-256 |
| --- | --- |
| v2 report `solo-liquidai-shadow-baseline-v2.json` | `7507690bc6f80c937f382ce428a210540cede1fde621249b5441755b18cb4f26` |
| frozen source bundle | `2f19402e7ee004de93a4508fecd6b55f344445ce381636742de00b55bd79e76d` |
| regression fixture | `1fb50ef1591659582ea779378d8d699d33d1c98a0522baff92d6cd506c35c524` |
| visible-challenge fixture | `cf43ac1f79eea7e5b88f0a0f5623e82a30b468a25024976de8fbb552ed7c1fba` |
| evaluation-case schema | `029189fec1e3d8f31c52783bcf444a41be6048724627b093d3bd42732c45f2a4` |
| canonical proposal schema | `c52b461d2d0e3a8c425bbd508193d1d75030c03c23c359fa7eca2bf588ef5652` |
| v2 inference-output schema | `db506b6504ee49eec1d031ec0e65f10f2af4f850942f21092d2bb72e4f925f70` |
| machine-local relay source | `03718cf6fc889e456bd87750ab02fc40d2525a966fed1196b20f1ea19ff8b2f2` |

Companion postflight, cleanup, and isolation evidence is recorded in
`solo-liquidai-shadow-baseline-v2-attestation.json`; its digest is finalized after tracked
documentation freezes and is intentionally not embedded here.

The candidate source base HEAD was `17bf37b9d96fbb53056c443f8fb1d3946b336a13`.
The exact model was `hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0`, digest
`677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822`, served by Ollama `0.32.7`
as a reported 2.7B `Q8_0` model with prediction budget `6144` and context length `8192`. All 24 cases
were public and visible; none was blind or independently human-adjudicated.

The v2 report remains `SOLO_PROVISIONAL`, `REPORT_ONLY`, `NOT_CONFIGURED`, and
`PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY`. It records `NOT_AUTHORIZED` for provider and
automatic Apply, `NOT_PERFORMED` for training and canonical writes, and the following observed
results:

| Metric | Fake | LiquidAI v2 |
| --- | ---: | ---: |
| scored requests / responses | 24 / 24 | 24 / 24 |
| inference-schema valid | not a Fake boundary | 20 / 24 |
| canonical-schema valid | 24 / 24 | 20 / 24 |
| domain valid | 24 / 24 | 10 / 24 |
| route accuracy | 1.0 | 0.541667 |
| wrong-local | 0 | 4 |
| invented precise date | 0 | 0 |
| local overflow | 0 | 0 |
| missing overflow signal | 0 | 1 |
| unresolved-field hallucination | 0 | 0 |

LiquidAI's failure counters total 22 observations: inference-schema invalid 4,
canonical-schema invalid 4, and domain invalid 14. These category counters must not be described as
22 unique failed cases. The strict public-visible development acceptance required 24/24 inference,
canonical, and domain validity plus zero failures and zero listed safety errors; observed status is
therefore `NOT_MET`. This is neither a provider-selection gate nor a `PASS` result.

All-attempt LiquidAI wall latency was minimum/p50/p95/max/mean
`9896.043`/`16754.523`/`24241.698`/`24655.245`/`17540.866 ms`. Successful-response latency was
`9895.774`/`16754.176`/`24241.137`/`24654.879`/`17540.185 ms`. Fake wall latency was
`0.377`/`0.547`/`11.795`/`114.698`/`5.872 ms`. These are one desktop shadow execution, not target-phone
or product-endpoint latency.

The observed device was an NVIDIA GeForce RTX 5080 with driver `610.88` and `16303 MiB` total memory.
Ollama reported `3166835834` bytes allocated entirely in VRAM at context length `8192`. The report
correctly keeps peak VRAM and utilization `NOT_AVAILABLE`. The companion attestation records only 9
coarse device-wide manual samples: baseline `3197 MiB`, maximum observed `6671 MiB`, and maximum
observed utilization `89%`. Those samples are not process- or model-exclusive and are not a peak or
model-exclusive delta claim.

The v2 inference schema intentionally makes `dueDateCandidateId` and `sourceSpan` null-only. The report
therefore marks both `DISABLED_NULL_ONLY_IN_SHADOW_V2`; it must not be read as successful due-binding
or source-span capability. Relation output is disabled as an empty proposal array, and tag ranking is
not scored. The visible v2 dataset still has no scored due-binding gold, and the null-only source span
choice produced no LiquidAI source-span matches.

The attested request path was a machine-local Docker host bridge, not OS-level egress isolation. The
runner called `127.0.0.1:11435` inside its container, a container-local relay forwarded to
`host.docker.internal:11435` (expected host gateway `192.168.65.254`), and Windows Ollama listened only
on `127.0.0.1:11435`; no port was published. The attestation explicitly makes no claim that the OS
blocked all internet egress.

Postflight recorded that the runner and relay exited, the model-loaded count returned to zero, the
exact installed model tag/digest was unchanged, no owned Ollama process or listener remained, the
scoped temporary directory was removed, and Ollama logs were not persisted. The observed runner code
path had no database/API imports or calls, product HTTP calls were 0, canonical reads/writes were 0,
and automatic Apply was 0. These statements are scoped to the runner code path and observed process
and network evidence; they are not a claim about unrelated machine history.

The v2 decision was `NO_GO_FOR_TRAINING`, and the LoRA decision was `NO_GO`. Fine-tuning was not
performed, no training tool was installed, and the visible public data is insufficient for training
or validation. Prompt/schema iteration was then `RECOMMENDED` because contract or safety findings
remained; v8-A closes that historical path with authoritative LiquidAI `NO_GO`. The later personal
semantic-patch fallback does not change this diagnostic verdict. No provider authority, training,
target-phone readiness, or release gate
is authorized.

### Preserved prompt/schema iterations v3 and v4

V3 and v4 used the same visible public 24-case corpus, pinned model/digest, sequential no-retry/no-tool
execution, proposal-only boundary, canonical JSON Schema, production domain validation, Fake
comparison, and external restoration controls. They are separate artifacts and do not overwrite v1
or v2:

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| v3 report `solo-liquidai-shadow-baseline-v3.json` | 33530 | `f6d6e8de0fc7aad342c0bd68487f1e416f922c75e6ba87cd8463c9b990468fa8` |
| v4 report `solo-liquidai-shadow-baseline-v4.json` | 34697 | `ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5` |

Their companion restoration/isolation evidence is
`solo-liquidai-shadow-baseline-v3-attestation.json` and
`solo-liquidai-shadow-baseline-v4-attestation.json`. Both reports remain `SOLO_PROVISIONAL`,
`REPORT_ONLY`, and `NOT_CONFIGURED`, with acceptance `NOT_MET`, training `NO_GO_FOR_TRAINING`, and
LoRA `NO_GO`; neither is a `PASS` or provider-selection result.

V4 completed 24/24 responses and inference-schema checks, but only 1/24 passed semantic IR, 1/24
canonical schema, and 1/24 domain validation. It recorded 69 failure observations over 23 unique
failed cases with 46 overlaps, 23 wrong-local cases, 0 invented precise dates, 1 local-overflow case,
and 1 missing overflow signal. All-attempt p50/p95 latency was
`22542.110`/`30973.996 ms`. V3/v4 remain diagnostic history rather than training or product
authorization.

### Atomic-slot prompt/schema iteration v5

The finalized v5 report is
`backend/target/evaluation/solo-liquidai-shadow-baseline-v5.json`, exactly `35035` bytes with SHA-256
`ba9c069d85c038d5c5603f8ddddfeae03aa8778cca7a949180142fee9b873102`. Its companion postflight,
cleanup, and isolation evidence is `solo-liquidai-shadow-baseline-v5-attestation.json`; the
attestation digest is intentionally not embedded here. V5 kept the same pinned
`hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0` model/digest, visible public synthetic dataset, fixed localhost
Ollama path, sequential no-retry/no-tool execution, Fake comparison, JSON Schema/domain validation,
and proposal-only/no-Apply boundary. It did not install training tools, fine-tune, call a product API,
read or write personal PostgreSQL or canonical data, or configure an external provider.

The report remains `SOLO_PROVISIONAL`, `REPORT_ONLY`, `NOT_CONFIGURED`, and
`PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY`. Observed quality was:

| Metric | Fake | LiquidAI v5 |
| --- | ---: | ---: |
| scored requests / responses | 24 / 24 | 24 / 24 |
| inference-schema valid | not a Fake boundary | 24 / 24 |
| semantic IR valid | not a Fake boundary | 8 / 24 |
| canonical-schema valid | 24 / 24 | 8 / 24 |
| domain valid | 24 / 24 | 7 / 24 |
| route accuracy | 1.0 | 0.375 |
| wrong-local | 0 | 16 |
| invented precise date | 0 | 2 |
| local overflow | 0 | 1 |
| missing overflow signal | 0 | 1 |

V5 recorded 49 failure observations: 16 semantic-IR invalid, 16 canonical-schema invalid, and 17
domain invalid. They affected 17 unique cases and overlapped 32 times; 49 must not be described as 49
unique failed cases. The strict visible-development target remained 24/24 at every validation layer
and zero safety errors, so acceptance is `NOT_MET`.

LiquidAI all-attempt p50/p95/max/mean wall latency was
`17172.783`/`31117.602`/`31305.739`/`18804.994 ms`. Ollama reported `3166835834` allocated bytes at
context length `8192`. The external, device-wide, non-exclusive sampler collected 906 samples with 0
misses: baseline `3260 MiB`, first observed `3243 MiB`, last observed `3249 MiB`, maximum observed
`7196 MiB`, and maximum utilization `93%`. These are not process/model-exclusive measurements, and a
model-exclusive peak remains `NOT_AVAILABLE`.

From v4 to v5, semantic/canonical/domain validity improved `1/1/1→8/8/7`, failure observations fell
`69→49`, unique failed cases fell `23→17`, wrong-local fell `23→16`, and p50 improved from
`22542.110` to `17172.783 ms`. Invented precise-date errors worsened `0→2`, p95 changed from
`30973.996` to `31117.602 ms`, and local-overflow plus missing-overflow-signal remained `1` each.
Those regressions and remaining validation failures prevent a `PASS` claim.

The companion attestation records runner/relay exit, unloaded model and listener/process cleanup,
removed scoped temporary resources, unchanged exact model tag/digest, and zero product HTTP,
canonical read/write, and Apply activity in the observed runner scope. It does not expand that scoped
claim to unrelated machine history. V5 therefore remains `NO_GO_FOR_TRAINING`, LoRA `NO_GO`, with no
fine-tuning, trainer installation, product adapter, provider, target-phone, or automatic-Apply
authorization.

The next solo comparison was not a training step: the deterministic guarded skill v6 diagnostic
described below kept public synthetic input, Fake authority, JSON Schema/domain validation,
proposal-only/no-Apply behavior, and aggregate-only output. It did not make RAG or personal-data
retrieval part of the run. Human adjudication and a separately held blind gate remain necessary for
any later provider/model-readiness decision.

### Deterministic guarded skill v6

The completed v6 report is
`backend/target/evaluation/solo-liquidai-deterministic-skill-v6.json`, exactly `45708` bytes with
SHA-256 `a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f`. Its companion postflight,
isolation, and restoration evidence is `solo-liquidai-deterministic-skill-v6-attestation.json`; the
attestation digest is intentionally not embedded in documentation. The report is labeled
`SOLO_PROVISIONAL`, `REPORT_ONLY`, `PUBLIC_VISIBLE_DEVELOPMENT_ONLY`, and `NOT_CONFIGURED` for a
provider. It is not blind, independently human-adjudicated, or a provider-selection result.

The v6 authority split is explicit:

- `FakeAnalyzer` produces the authoritative proposal;
- the deterministic skill validates and projects that proposal;
- LiquidAI may select only an already-existing item-title ordinal for `/suggestedTitle/value`;
- topic ordinals are diagnostic-only and cannot change proposal fields;
- an invalid selection rejects the whole model envelope and falls back to the skill/Fake proposal,
  without repair or retry.

All 24 model-selection requests produced zero completed model responses and zero accepted model
contributions. Every request was rejected as `MODEL_TRUNCATED_RESPONSE`, so rejection and skill
fallback counts were both `24`. No title changed, improved, or regressed, and no diagnostic topic
ordinal was accepted. Those failures mean the guarded output cannot be credited to LiquidAI.

| Metric | Fake | Skill only | LiquidAI guarded |
| --- | ---: | ---: | ---: |
| canonical-schema valid | 24 / 24 | 24 / 24 | 24 / 24 |
| domain valid | 24 / 24 | 24 / 24 | 24 / 24 |
| route accuracy | 1.0 | 1.0 | 1.0 |
| wrong-local | 0 | 0 | 0 |
| listed safety errors | 0 | 0 | 0 |
| protected-proposal mismatch | 0 | 0 | 0 |

`GuardedSystem MET` is therefore solely a deterministic Fake/skill fallback result. The report sets
`fallbackValidityAttributedToLiquidAi=false` and gives the model no credit for schema/domain validity,
route accuracy, or safety. Model-contribution acceptance is `NOT_MET`; overall development acceptance,
which requires both guarded-system and model-contribution success, is also `NOT_MET`.

P95 wall latency was Fake `9.509 ms`, deterministic skill projection `0.923 ms`, model-selection
attempt `491.271 ms`, and end-to-end `497.976 ms`. The selector latency gate itself passed, but this
does not offset zero valid responses or contributions. Ollama reported `2977033092` bytes allocated
in VRAM at context length `2048`. These are one desktop shadow execution's observations, not target-
phone or product-endpoint readiness.

The runner used only the 24 public synthetic fixtures through localhost Ollama. It read or changed no
personal memo, personal PostgreSQL, canonical data, product API, or Apply path. RAG was not used.
Fine-tuning and LoRA were not performed, no training tool was installed, training remains
`NO_GO_FOR_TRAINING`, and LoRA remains `NO_GO`. The companion records runner/relay exit, zero loaded
model and Ollama process/listener, Docker Desktop restored to its original `OFF` state, an unchanged
canonical Docker fingerprint, and removal of scoped temporary resources.

V6 demonstrates deterministic containment, not LiquidAI success, provider readiness, a `PASS`, or
automatic-Apply authority. The subsequent bounded truncation diagnostics are recorded below.

### Output-cap diagnostic v7-A

V7-A increased `num_predict` from `64` to `128`. The report is `5925` bytes with SHA-256
`5b6a578b2b2222fc6180a4f70af7718526ccce2e127b070a404477a30c19d20f`; its companion
attestation is `7874` bytes with SHA-256
`bccc6a0856ea9055f199d381e7be28e0e8587373687ab1d148f3617e69c4c617`. STOP/LENGTH/accepted/
fallback counts were `0/24/0/24`. Prompt tokens were `9765` and selector p95 was `923.668 ms`.
The larger cap did not yield one completed selection.

### Prompt-overhead diagnostic v7-B

V7-B reduced prompt tokens to `5973`, which is `3792` total and `158` per case below v7-A, and
selector p95 was `823.686 ms`. The report is `7081` bytes with SHA-256
`c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37`; its attestation is
`9743` bytes with SHA-256 `ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d`.
STOP/LENGTH/accepted/fallback remained `0/24/0/24`. V7-B therefore proves only a bounded overhead
reduction, not a LiquidAI contribution.

### Compact-wire diagnostic v8-A

V8-A replaced the selection envelope with strict compact `{v,p,t}` JSON and retained an unmodified
deterministic mapper. The report is
`backend/target/evaluation/solo-liquidai-compact-wire-diagnostic-v8a.json`, `11150` bytes with
SHA-256 `bd9f4419fb26b8a2950b80722eef746fff41e4418a8c52ccb94aafc7333365e3`. The companion
`solo-liquidai-compact-wire-diagnostic-v8a-attestation.json` is `12184` bytes with SHA-256
`97e7c67a9a1f01140be7ad25734ce7080002b367ea7c87772c8a4c8287b4cdab`.

All 24 attempts hit the evaluation cap of `128` and terminated LENGTH. STOP/LENGTH/accepted/fallback
were `0/24/0/24`; prompt tokens were `6093`, only `120` total and `5` per case above v7-B. Fake p95
was `10.131 ms`, selector p95 `855.907 ms`, and their p95 ratio `84.482×`. Guarded schema/domain
validity was `24/24` only because every attempt fell back to the authoritative deterministic path;
LiquidAI receives no validity credit. Leakage and protected-mutation counters were zero.

The device-wide, non-exclusive sampler recorded 59 observations and zero misses, baseline/max used
memory `3033`/`6175 MiB`, and maximum utilization `92%`. It is not a model-exclusive peak claim.
No personal memo, personal PostgreSQL, canonical data, product API, or Apply path was accessed. No
fine-tuning, LoRA, or RAG was performed. Postflight restored Ollama, Docker Desktop, model allocation,
listener/process state, and scoped temporary resources.

V7-A cap expansion, v7-B prompt reduction, and v8-A compact wire all failed to resolve 24/24
truncation. The diagnostic decision is `NO_GO`, full reliability is false, and product/provider
readiness remains `NO_GO`. Training remains `NO_GO_FOR_TRAINING` and LoRA `NO_GO`. Deterministic rule
hardening remains the dependency-reduction path; ADR 0008's personal AI-preferred proposal path is a
separate bounded product decision and does not change this diagnostic verdict.
This failure is not retrieval-solvable evidence, so
RAG is not a next step; a separate documented retrieval need may later justify only a bounded,
allow-listed public/de-identified comparison.

### Guarded product-like synthetic smoke (2026-08-21)

A separate three-case smoke used the exact installed tag and digest through localhost Ollama with the
provisional `num_predict=1024` hidden-reasoning budget. It used only synthetic strings and did not
read or call the personal API, personal PostgreSQL, canonical data, or `.env.personal`.

- `6시 디스코드 접속하기` terminated `STOP` in approximately `5304 ms`. Its strict selection was
  accepted as a narrow `TASK` patch with exact source substrings `접속하기`, `디스코드`, and `6시`.
  No calendar date was invented: `6시` remains unresolved rather than silently becoming today at
  18:00. Reminder persistence and alarm delivery are separate, unimplemented capabilities.
- `접속하기 싫다` terminated `LENGTH` in approximately `9523 ms` and was rejected to detailed local
  review rather than treated as a positive task.
- `접속하기 좋은 시간` terminated `LENGTH` in approximately `8485 ms` and was rejected to detailed
  local review rather than treated as a requested action.

This tiny product-like smoke is safety evidence, not an accuracy estimate. The one accepted model
case took about `5.304 s`; the historical deterministic-skill-v6 Fake p95 was `9.509 ms`, but the
cohorts and wire paths differ, so their ratio is not a benchmark. No new exclusive model VRAM peak
was measured. The preserved v8-A device-wide, non-exclusive observation remains baseline/max
`3033`/`6175 MiB` and maximum utilization `92%`; it cannot be attributed exclusively to this smoke
or to the model. Fine-tuning and LoRA were not performed and remain `NO_GO`; RAG was not used.

Postflight left no owned Ollama process, listener, or loaded model and removed scoped temporary
resources; the installed model remained unchanged. The result is `SOLO_PROVISIONAL` /
`REPORT_ONLY`. It supports only the guarded semantic-patch fallback and does not change the
authoritative LiquidAI/provider `NO_GO` decision.

### Repeatable isolated AI-preferred product-path smoke (2026-08-28)

The permanent isolated orchestrator now compares the fixed three-case public synthetic fixture
through the real register → memo → analysis-run → proposal-read product API path. It builds one
temporary backend image, runs separate Fake/`UNCERTAINTY_ONLY` and exact LiquidAI/`AI_PREFERRED`
Compose projects with tmpfs PostgreSQL, and starts an owned Ollama `0.32.7` only on
`127.0.0.1:11435`. It does not read the personal environment, API, PostgreSQL, memo, or canonical
state and never calls Apply, reject, postpone, undo, alarm/reminder, or an external product service.

The aggregate-only receipt is
`backend/target/evaluation/ai-preferred-product-smoke-20260828T054016485Z.json`, SHA-256
`d605ed48935d8dd5acbd98ff7e658c495f70cb1467f69ad8efb1b656f5fcca3b`. The receipt passed its strict
JSON Schema and source/domain contract validator. It binds commit
`17bf37b9d96fbb53056c443f8fb1d3946b336a13` and exact source hashes, and truthfully records
`dirty=true`; it is therefore evidence for this working tree, not a clean-release attestation.

| Metric | Fake | LiquidAI |
| --- | ---: | ---: |
| Final schema/domain accepted | 3/3 | 3/3 |
| Affirmative TASK safety case passed | 1/1 | 1/1 |
| Negative/descriptive TASK promotions | 0 | 0 |
| Invented precise dates / unresolved hallucinations | 0 / 0 | 0 / 0 |
| Model/gateway success | not a model measurement | 1/3 |
| Validated local fallback | 3/3 | 2/3 |
| Median wall latency | 73 ms | 6,958 ms |
| Min / max / mean wall latency | 50 / 120 / 81 ms | 5,477 / 7,202 / 6,546 ms |
| Canonical write delta and mutation/tool calls | 0 | 0 |

LiquidAI median wall latency was `95.3151×` the Fake median. The single accepted model result was
unchanged and two cases used validated local fallback, so semantic improvement is
`NOT_DEMONSTRATED`. Model token and cost numbers were not reported. Device-wide, non-exclusive GPU
sampling recorded 74/74 samples, baseline/max/post VRAM `3306`/`6478`/`3045 MiB`, maximum
utilization `93%`, and maximum exact-target Ollama VRAM `3012684676` bytes (`2873.1 MiB`). The
device-wide delta must not be attributed exclusively to the model.

Cleanup independently reconfirmed zero scoped containers, images, networks, volumes, temporary
directories, and `11435` listeners; the personal Compose project remained 3 containers before and
after. The result is only `PASS_NARROW_PRODUCT_PATH`, `SOLO_PROVISIONAL/REPORT_ONLY`, with provider
and LoRA `NO_GO`, training `NO_GO_FOR_TRAINING`, RAG `NOT_USED`, and automatic Apply disabled.
`externalProductServiceAccessed=false` covers product-service calls, not Docker build/pull egress,
and restoration covers the enumerated owned resources rather than byte-for-byte machine state.

## Metrics

Metrics are reported separately for regression, `VISIBLE_CHALLENGE`, and the combined set, keyed by
`analyzerVersion`, `deterministicRulesVersion`, and `routingPolicyVersion`.

- **Schema/domain-valid rate**: proposals accepted by the supported proposal JSON Schema and the
  production domain validator. Current `fake-v10` output is schema v2; recoverable v1 remains part of
  the compatibility contract rather than the generated baseline format.
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

The V20 personal mode requires a separate invocation-policy arm instead of rewriting the frozen
24-case semantic route gold. That arm must report `UNCERTAINTY_ONLY` versus `AI_PREFERRED`, total and
clear-route call rate, invocation reason, model `KEEP`/`PATCH`/fallback counts, schema/domain validity,
semantic changed fields, end-to-end latency, Ollama/GPU/VRAM evidence with measurement limitations,
and later owner correction outcomes. Approved-hint use must report only aggregate count/version
behavior; it must not export memo text, anchor text, offsets, historical selections, identifiers, or
hashes. `KEEP` means accepted unchanged model output, not correctness. `EXACT` Apply means a selection
matched the versioned review default, not AI accuracy.

Proposal schema v2 supports explicit TASK-to-precise-date candidate references, but evaluation
dataset v2 has no binding labels. Reports therefore publish
`dateItemDueBinding: SUPPORTED_NOT_SCORED_DATASET_V2`. This is a capability statement, not a
precision, recall, safety, or pass result. Binding quality requires a separately reviewed label
policy and independently adjudicated evaluation dataset v3.

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

The last measured `fake-v8` / `korean-rules-v6` cohort retained source-aligned UTF-16 item spans,
sequential action facets, explicit three-item truncation after full detection, and fail-closed
alternative handling. It added
a guarded affirmative `접속하기` action without promoting negative or descriptive forms; a time such
as `6시` without a date stays unresolved for precise due-date purposes. It emitted
proposal schema v2 with proposal-local date IDs and nullable TASK due references. Historical schema
v1 proposals remain recoverable, while the new default-review policy uses explicit v2 references.
These changes do not turn the public fixture into blind evidence; that generated version-2 report
remains the last full measurement artifact, its item quality fields remain diagnostic, and it does
not score binding quality.

The generated `fake-v8` run reproduced the prior recorded `fake-v7` aggregate: schema/domain-valid
proposals for all 12 regression and 12 visible-challenge cases. Item cardinality matches in 11/12
regression and 12/12 visible-challenge
cases. Required gold source-span recall is 15/15 and 14/14 respectively; the regression split has
one additional scaffold span from the conservative default fallback. Both splits have zero semantic
false-confident-local cases, invented precise-date cases, missing overflow signals, and hallucinated
action/object values where gold requires unresolved fields. These are public synthetic diagnostics,
not independently adjudicated or blind accuracy.
The remaining mismatches in acceptable item semantics and ambiguity signals stay visible in the report
and are not promoted to provider gates.

The earlier source `fake-v9` / `korean-rules-v7` added an explicit relative-day meridiem rule for
`오늘|내일|모레 + 오전|오후 + 1–12시` with optional minutes. It resolves against the immutable
revision instant/source zone and emits `RELATIVE_EXACT` only when that local time has one unambiguous
valid offset. At that checkpoint a date-less `6시` remained `UNKNOWN`, and focused parser/analyzer
tests covered the source change. No full public synthetic report was recorded for that checkpoint.

Current source `fake-v10` / `korean-rules-v8` supersedes only that date-less-clock rule. The supported
date-less explicit clock family with no particle or `에` includes bare 1–12시 with optional minutes,
explicit 오전/오후, Korean 24-hour clock, and `HH:mm`. It uses the immutable revision capture
instant/source zone and proposes the earliest safe capture-day occurrence strictly after capture.
Equality is not future. A gap occurrence is discarded and a later unique same-day occurrence may be
used; any future overlap occurrence fails the whole expression closed as `UNKNOWN`. No safe remaining
occurrence or missing/invalid source zone also stays `UNKNOWN`, with no tomorrow rollover. This is
proposal-only/manual Apply behavior and does not create or deliver an alarm/reminder. Focused source
tests do not constitute a new full public synthetic or blind measurement. The v8 aggregate above
must not be relabeled as a v9 or v10 result.

The immutable AI-preferred product-smoke v1 fixture/schema/receipt remains historical. A separate v2
public-synthetic fixture and source contract pins `2026-08-28T09:00:00+09:00` in `Asia/Seoul` and
requires `6시 디스코드 접속하기` to produce one grounded TASK bound to a
`2026-08-28T18:00:00+09:00` `RELATIVE_EXACT` candidate. Its receipt schema still requires zero Apply,
alarm/reminder, personal-data access, and canonical-write delta. The v2 Docker/Ollama product smoke
has not run and no v2 receipt exists, so there is no new runtime pass, latency, GPU/VRAM, or
Fake-versus-LiquidAI comparison evidence.

The generated deterministic report is the source for the last measured values. It exposes the
date and item failures rather than hiding missing spans or unresolved fields behind route/type
success. The visible challenge remains report-only even when its current counts are green. Public
synthetic coverage does not estimate general Korean accuracy and is not evidence that the Fake
analyzer or a future provider passed a blind evaluation.

V20 source introduces no new accuracy result. Source mechanical qualification and model accuracy
qualification are separate; the latter is `NOT_RUN_NO_CLAIM`. Its personal `AI_PREFERRED` and K=3 approved-type hint
are `SOLO_PROVISIONAL`/`REPORT_ONLY` until a separately executed, predeclared invocation-policy
comparison reports the metrics above. Existing Fake and LiquidAI v1–v8-A numbers remain historical
cohorts and must not be combined into a V20 accuracy claim.

## Milestone 6A.1 EVENT schedule evaluation boundary

- Proposal v2 has date candidates and TASK due binding only. It does not contain EVENT temporal
  binding gold or analyzer output, and 6A.1 does not reinterpret it as doing so.
- EVENT review initializes with no schedule. Reusing a precise proposal date candidate or entering a
  schedule directly is an explicit user selection; it is not a correct model prediction, automatic
  binding, training label, or accuracy datapoint. This includes a `fake-v10` source-zone
  `RELATIVE_EXACT` candidate.
- Selection schema version 2, V21 `event_details`, Apply rollback/idempotency/undo, owner/kind
  constraints, `GET /events`, and PWA review/list tests establish contract mechanics and privacy
  boundaries only. They do not change Fake/LiquidAI route, canonical/domain, latency, GPU/VRAM,
  provider, training, or LoRA results.
- TIMED Apply start/end and exact TASK due values require offsets valid for the immutable revision
  zone. DST gaps fail with `EVENT_SCHEDULE_ZONE_OFFSET_MISMATCH` or
  `DUE_ZONE_OFFSET_MISMATCH`; either explicitly selected valid offset in an overlap is accepted.
  These are domain-safety tests, not analyzer accuracy evidence.
- The existing review-outcome classifier treats a temporal-candidate-bearing v3 proposal or a
  schedule-bearing selection as `unclassifiable` until a versioned temporal-review comparison policy
  exists. It must not silently count such Apply rows as `exact`, `corrected`, or model-quality gold.
- Milestone 6A.2a predeclares proposal schema v3 mechanically. It can represent bounded ID-based
  alternatives, explicit timed/all-day intent, optional end, and explicit exclusive-at-value versus
  inclusive-through-value all-day end semantics. Current producers remain v2, every review draft
  remains unscheduled, and the domain gate rejects a non-null suggested candidate.
- The separate EVENT temporal-binding overlay schema, integrity validator, and
  [`draft policy`](EVALUATION_EVENT_TEMPORAL_LABEL_POLICY.md) are not labels or evidence. They do not
  modify the existing TASK-due dataset-v3 overlay and contain no checked-in overlay, reviewer
  manifest, adjudication, score, numeric threshold, or `PASS`.
- Milestone 6A.2b still requires independent human approval of the EVENT policy, two independent
  label passes and human adjudication, metrics and numeric thresholds frozen before output is
  inspected, and a separately held release before any analyzer/model schedule preselection or
  binding-quality claim. Source-zone-aware offset validation is also required before a timed proposal
  may become a review default.
- No personal V21 database migration/deployment or personal-data evaluation is authorized by this
  source documentation.

## Automated gates

The current CI gate is deliberately narrow:

- all regression proposals must pass proposal schema and production domain validation;
- regression route/type/signal `wrongLocal.count` must be zero;
- regression `dates.inventedPreciseDateCaseCount` must be zero;
- regression local-review candidate-overflow count must be zero;
- regression missing-overflow-signal count must be zero;
- regression unresolved action/object hallucination count must be zero.

### 2026-08-28 schema/domain/integrity mutation parity hardening

The validation layers now have an explicit public-synthetic mutation gate instead of relying only on
their separate unit tests. `AnalysisProposalSchemaDomainMutationParityTest` fixes the expected
partition for structural/format/size failures versus cross-field identity, reference, precision,
source-span, ambiguity, and schedule-suggestion failures. It covers one valid Fake control, 15 named
general mutants, a UTF-16 surrogate-boundary mutant, and the deliberately closed non-null version-3
schedule suggestion. `LocalDecisionEvidenceMutationParityTest` adds one valid raw-free control and
15 schema-valid/domain-invalid count, margin, temporal, taxonomy, and item-summary mutants; every
domain rejection remains the generic `INVALID_LOCAL_DECISION_EVIDENCE` response without serialized
evidence.

The EVENT temporal-binding integrity validator also fails closed when one gold date has mixed
accepted precisions: every accepted interpretation of a `TIMED` start/end must be `EXACT_TIME` or
`RELATIVE_EXACT`, and every accepted interpretation of an `ALL_DAY` start/end must be `DATE_ONLY`.
It may no longer discard incompatible interpretations and validate only the compatible subset.
Spotless plus 55 focused proposal/evidence/v2/v3/v4 integrity tests passed, followed by the complete
backend `mvn verify` against disposable PostgreSQL with Flyway V1-V23 and zero SpotBugs findings.

This is source/test hardening only and remains `SOLO_PROVISIONAL` / `REPORT_ONLY`. It used no personal
memo, personal database, canonical mutation, API Apply, or Ollama inference and does not add an
accuracy, provider, model-contribution, EVENT-preselection, training, fine-tuning, or LoRA `GO`.

Visible-challenge results are report-only. A failing case is evidence for a general deterministic rule,
parser improvement, or a correctly bounded escalation rule; it must not be fixed by copying the
challenge phrase into `FakeAnalyzer`. Complete date/item/item-source-span quality rates and semantic
false-confident-local counts remain report-only until the labels receive two-person adjudication and
an independently held blind run. Thresholds must be approved before examining that blind run; they
must not be chosen to fit observed output.

The Solo LiquidAI shadow diagnostic does not change these gates. Reusing the evaluator and regression
safety fields checks contract compatibility and exposes errors, but every provider-selection metric
remains unenforced and `NOT_CONFIGURED`; neither a green field nor a comparison with Fake is a PASS.

V20 compile/schema/domain/integration checks qualified source mechanics but did not add an accuracy
gate. The owner separately authorized and completed V20, then V21-V23 backup/restore rehearsal,
personal migration, rebuild, and health verification as recorded in `PRIVATE_BETA.md`; the current
personal stack is V23 and remains `LOCAL_ONLY`. This mutation-parity milestone did not access or
change that stack and does not extend those deployment authorizations to public activation.

The regression hard gate does not contain a binding-quality metric. Schema/domain validation does
hard-fail malformed v2 candidate IDs, dangling references, non-TASK bindings, and references to
approximate or unknown dates. Whether a structurally valid binding is semantically correct remains
unscored under dataset v2 and must not be inferred from a green build.

## Human-review and version-3 preparation status

The repository now contains preparation code, not completed human evidence:

- `contracts/korean-memo-evaluation-review.schema.json` defines one strict, raw-content-free
  per-reviewer manifest; the verifier consumes two independently attested manifests for the public
  version-2 date, item, and item-source-span gold.
- `PublicGoldAdjudicationVerifier` pins both complete manifests to one canonical public-release
  digest, requires distinct opaque reviewer tokens and matching protocol/policy identifiers, and
  emits aggregate agreement only. It never chooses a correction or treats matching
  `CHANGE_REQUIRED` verdicts as resolved.
- `PublicGoldReviewPacketRunner` explicitly generates one local static HTML packet from the strict
  public release. Its renderer manually allow-lists source, capture time, time zone, and the scoped
  date/item gold for presentation. Fixture notes, route/type/tag/signal gold, analyzer output, another
  review, and generated metrics are never rendered or copied into the packet; the displayed canonical
  digest still commits to every field in the complete public release. UTF-16 source spans are shown as
  numeric half-open ranges and highlighted text. The packet has no verdict form, reviewer identity,
  attestation, or manifest generator.
- `ExternalPublicGoldReviewRunner` explicitly consumes two complete human-authored manifests from
  outside the repository, pins a clean candidate commit, delegates verdict comparison to the strict
  verifier, and writes only its fixed aggregate summary. It cannot prove that either token belongs to
  a person or that the reviews were independent.
- `contracts/korean-memo-binding-overlay.schema.json` and `EvaluationV3BindingGoldIntegrity` can
  validate an ID-only TASK-due overlay against one exact immutable public version-2 release digest,
  including complete item-set alternatives and precise emitted-date references. This integrity
  check does not prove that the base labels received human review.

No real reviewer manifest, completed two-person adjudication, approved version-3 overlay/dataset,
binding metric, threshold, or `PASS` result is checked in. Test-only in-memory manifests and overlays
prove validator behavior only. [EVALUATION_LABEL_POLICY.md](EVALUATION_LABEL_POLICY.md) is marked
`DRAFT_REQUIRES_INDEPENDENT_HUMAN_APPROVAL`; an Agent must not fill or approve human review inputs.
Until people complete and freeze both review stages, `dateItemDueBinding` remains
`SUPPORTED_NOT_SCORED_DATASET_V2`.

## Generating the scoped public-review packet

From `backend/`, explicitly generate the local packet from one clean, fixed checkout. On PowerShell:

```powershell
$env:PERSONAL_MEMO_CANDIDATE_COMMIT = (git rev-parse HEAD).Trim()
try {
  mvn clean -Dtest=PublicGoldReviewPacketRunner test
} finally {
  Remove-Item Env:PERSONAL_MEMO_CANDIDATE_COMMIT -ErrorAction SilentlyContinue
}
```

`PublicGoldReviewPacketRunner` does not match the ordinary Surefire test-name patterns, so normal
`test`, `verify`, and public CI do not create the packet. A successful explicit run writes:

```text
backend/target/evaluation/public-v2-review-packet.html
```

The packet contains the exact public synthetic memo text and scoped gold needed by a reviewer. It is
therefore not an aggregate report and must stay local under ignored `target/`; do not upload it as a
CI artifact or confuse it with a blind release. It is deterministic UTF-8 static HTML with an
offline-only content-security policy and no script, network request, form, browser storage, or
manifest-writing function. Its release digest and case count identify the exact release being shown.
The runner requires the exact lowercase candidate commit, checks the same clean `HEAD` before reading
the synchronized repository/source/bundled resources and again immediately before atomic publication,
and fails closed if either check changes. Mirror equality alone is not treated as a commit pin. It
deletes a safe stale packet before verification and attempts cleanup after later failures; any nonzero
Maven result invalidates every remaining packet if the host prevents cleanup.
Humans must independently approve or revise the draft policy and freeze their own opaque release ID
and policy version before authoring reviews.

Each reviewer receives the same packet but must not receive fixture `notes`, analyzer/Fake output, the
deterministic report, or the other review. Reviewers create their strict manifests themselves outside
Git. An Agent may render the fixed packet and validate completed inputs, but must not create, fill,
approve, copy, or infer either human manifest.

## Verifying two external public-review manifests

Only after two different people have completed independent manifests, run the aggregate verifier from
one clean, fixed checkout. On PowerShell, from `backend/`:

```powershell
$env:PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_A = '<absolute-outside-repository-path-A>'
$env:PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_B = '<absolute-outside-repository-path-B>'
$env:PERSONAL_MEMO_CANDIDATE_COMMIT = (git rev-parse HEAD).Trim()
try {
  mvn clean -Dtest=ExternalPublicGoldReviewRunner test
} finally {
  Remove-Item Env:PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_A -ErrorAction SilentlyContinue
  Remove-Item Env:PERSONAL_MEMO_PUBLIC_REVIEW_MANIFEST_B -ErrorAction SilentlyContinue
  Remove-Item Env:PERSONAL_MEMO_CANDIDATE_COMMIT -ErrorAction SilentlyContinue
}
```

The runner is also outside ordinary Surefire patterns. It fails closed unless both paths are absolute,
outside the repository, regular non-link files, and different real files; hard-linked aliases are not
two reviews. Inputs must be non-empty bounded strict UTF-8 JSON without a BOM, duplicate keys, trailing
content, or schema additions. The bundled and repository public fixtures/schema must match. Both
manifests must pin the exact public release, use distinct case-insensitive reviewer tokens, cover the
same complete case universe, and carry every required human attestation. The runner checks the exact
clean `HEAD` before reading and again before output, never prints an input value, and removes stale or
temporary output before verification. It attempts the same cleanup after later failures; if deletion
itself is denied or interrupted, the Maven command still fails and every remaining file must be treated
as invalid stale output.

Only a successful integrity run may create:

```text
backend/target/evaluation/public-v2-review-summary.json
```

The fixed summary contains only the verifier's status, booleans, and aggregate counts. It excludes
paths, tokens, release/policy identifiers, digests, case IDs, content, notes, gold, corrections, and
per-case verdicts. `CONSENSUS_ACCEPTED` means only that the two submitted manifests both said `ACCEPT`
for every scoped field under the same structural contract. It is not proof of reviewer identity or
independence, policy approval, adjudication, a version-3 binding dataset, blind `PASS`, metric quality,
or provider readiness. `NEEDS_HUMAN_RESOLUTION` and any `CHANGE_REQUIRED` require people to resolve the
gold, freeze a changed release when needed, and repeat independent review of the new digest.

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

This version-2 blind envelope can score its existing date and item fields but has no date-to-item
binding labels. Its aggregate summary therefore also reports
`SUPPORTED_NOT_SCORED_DATASET_V2`, and no pre-registered policy may treat that capability value as a
binding metric. A future binding gate must use an independently adjudicated version-3 case contract
and freeze its binding thresholds before the first candidate run.

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
the input values, deletes stale output before validation, and attempts cleanup after any later
validation, privacy, execution, or write failure. A nonzero command result invalidates every
remaining report even when the host denies or interrupts deletion.

Public CI must not receive the blind dataset or reviewer/adjudication inputs as secrets, invoke the
external runner, or upload those inputs, its report, or diagnostics as an artifact. The tracked-file
leakage guard enumerates only `git ls-files`, scans JSON/JSONL/YAML/YML/CSV/TSV and chained
backup/temporary variants for blind and filled-review markers, and rejects sensitive
blind/annotation/adjudication/reviewer/freeze filenames regardless of extension. Missing Git
metadata in public CI, unreadable/non-text/oversized relevant files, or Git enumeration failure is a
generic fail-closed error; the known local Compose test layout without `.git` may skip this one
checkout-specific assertion. A green public build therefore never means that a blind release or
human review passed; an authorized curator/operator must retain evidence separately.

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

V20 may use a much narrower local inference-time view of eligible latest `APPLIED` corrections:
same-owner type-corrected or user-resolved single-item cases can yield at most three conflict-free
exact-unique short anchors that also occur in the current memo. The durable retry snapshot contains
only current-memo UTF-16 offsets and approved kind; the prompt materializes only current anchor text
and kind. Historical raw memo, selection, IDs, title/tag/due/relation do not enter that snapshot or
prompt, and finalization scrubs the raw offset snapshot while retaining hash/version/count. This is
not a RAG/vector/embedding corpus, training set, automatic rule promotion, fine-tuning, or LoRA.

V18 makes explicitly selected relation candidates canonical and reversible, but it does not change
the evaluation dataset, Fake output, or `review-default-v3`. That comparison policy has no adjudicated
relation-selection target, so any proposal with a non-empty `relationCandidates` array remains
`UNCLASSIFIABLE` even after a valid application. This means “outside the current comparison policy,”
not “outside the Apply contract,” and it must not be counted as relation accuracy.

## Gate before an authoritative or external LLM

An authoritative or external provider remains blocked until all of the following are true. The
personal-only pinned localhost `AI_PREFERRED` proposal path is a provisional exception to invocation,
not to these provider-readiness gates:

1. At least 1–2 weeks and roughly 50–100 personally reviewed memos exist; exact and corrected
   outcomes must be distinguishable by latest `APPLIED` versus `UNDONE` state, while rejects and
   postponements remain separate, without exposing memo text.
2. The representative evaluation set is expanded beyond fixture-specific rules, version-2
   date/item gold is independently adjudicated, a version-3 binding-label policy and dataset are
   independently adjudicated before binding quality is gated, tag gold is complete for the provider
   task, and the wrong-local safety threshold is approved from measured results rather than chosen
   after seeing provider output.
3. The implemented owner/policy/timestamp consent pin and server-owned
   transfer/gateway/provider/model/policy/outcome evidence are retained, and an approved provider,
   region, retention/deletion policy, consent grant UX/API, per-request context/tool/token/time
   limits, monthly budget, and outage behavior are explicitly decided and enforced fail-closed. The
   V14 final-run authorization/grant/token evidence, V15's durable descriptor/executor binding,
   V16's bounded context hash/version/count evidence, and V17's truthful attempt-state semantics must
   be retained. V15/V16 commit authority and the exact context snapshot before execution, then reuse
   the original token and database snapshot during caller-driven or bounded production recovery for
   configured gateways. V17 treats returned results as `STARTED`, executor rejection as definitive
   `NOT_STARTED`, and a submitted termination without an observed start as `UNKNOWN`. It leaves
   unobserved remote result, duration, usage, and cost unknown rather than inventing values. Any
   approved external provider
   must honor the same token as its deduplication identity and preserve the fail-closed consent and
   finalization checks. Attempt retention/purge and real usage/cost reporting still require explicit
   approval.
4. A Shadow mode can persist validated proposals and metrics without applying them; only explicit
   user approval may continue to create tags, tasks, or relations.
5. Fake failure tests cover timeout, retry exhaustion, invalid structured output, stale revision,
   and raw-memo survival before any provider credential is configured.

The public-fixture Solo LiquidAI runner is report-only and deliberately has no database or Apply
path, so it does not satisfy item 4 or any other authoritative/external-LLM entry condition. The V20
personal path also remains proposal-only/manual Apply and makes no accuracy or provider-readiness
claim.

## Known Milestone 2 blockers

This list distinguishes remaining blockers from the execution mechanics V15–V20 now supply:

- Runtime `AnalysisRoute` currently implements only `LOCAL_REVIEW` and `CLOUD_ENRICH`.
  `USER_INPUT_NEEDED` and `PENDING_OFFLINE` in the pipeline document are conceptual states, not
  executable routes yet.
- Analysis remains synchronous at the HTTP boundary. Clear local runs are finalized directly as
  `REVIEW_REQUIRED`; a cloud-bound run is first committed as `QUEUED` / `PENDING` with a V15/V16/V17
  `PREPARED` dispatch, including its bounded retrieval-context and attempt-history version, then
  claimed as `RUNNING`,
  executed with a persisted timeout outside the database transaction, and finalized as
  `REVIEW_REQUIRED` or `STALE` after a locked revision and fence recheck. Typed cloud failure,
  binding/execution exception, and invalid enriched output persist the revalidated local proposal
  with a bounded outcome. Raw and canonical data remain unchanged, and provider error text is not
  stored or returned.
- Same-key caller recovery remains available. In the production profile, a scheduler also scans at a
  30-second fixed delay and selects at most 25 `PREPARED` or expired-lease `RUNNING` dispatches from
  owner-consistent database rows. It uses the selected owner and existing raw key under the same
  owner+operation+key advisory lock, skips live leases, and reuses the V15 binding/fence/deadline,
  the exact V16 database context snapshot, bounded out-of-transaction configured-gateway call,
  revision-rechecking finalize, and deterministic provider token. Retrieval is not rerun on recovery,
  so the same token is never paired with different context. This supports recovery after a process
  restart but remains bounded at-least-once execution, not exactly-once delivery. V17 now records one
  internal owner-scoped row per claimed fence, up to `max_attempts`, with monotonic local duration
  when termination is observed. Gateway result is `STARTED`, executor rejection is definitive
  `NOT_STARTED`, and timeout/interruption or a post-submit unexpected termination without an observed
  start is `UNKNOWN`, never `NOT_STARTED`; unobserved remote truth remains `UNKNOWN`. This is
  operational evidence, not provider-selection accuracy evidence.
- V13 enforces an owner-scoped exact consent pin: boolean true, the descriptor's exact policy
  version, and a non-null grant timestamp no later than the authorization-check instant. It revokes
  legacy boolean-only grants and rejects future-dated grants. `NO_NETWORK` Fake needs no consent;
  `EXTERNAL_MEMO_CONTENT` gets zero gateway calls without a valid pin. V14 records a coherent
  authorization/grant/token snapshot in each new final run and passes it with the descriptor to the
  current gateway request. V15 adds a durable pre-call dispatch and immutable descriptor/executor
  binding for configured-gateway execution, but there is still no grant/revoke API or configured
  external provider. V16's exact tag/alias retrieval is also reused by the V19 personal adapter.
- Every new run carries server-owned transfer mode, gateway/provider/model/consent-policy versions,
  and outcome; provider-call runs additionally pin an immutable gateway binding. V15 stores internal
  dispatch/fence/lease evidence. V16 adds context raw/hash/version/count before the call and scrubs raw
  at finalization while retaining hash/version/count. V17 distinguishes definitive executor
  rejection from an observed `STARTED` gateway `UNAVAILABLE`, records timeout/interruption/process-loss
  remote truth as unknown, and keeps local termination observations for the model-free Fake at
  `NOT_APPLICABLE` with null model-token/cost values even when start is uncertain. Observation-free
  process-loss usage/cost remains `UNKNOWN`; a model-backed attempt is `NOT_APPLICABLE` only when
  definitively `NOT_STARTED`, `UNKNOWN` when execution or remote completion is uncertain, and
  `NOT_REPORTED` for an observed result until reporting exists. None of the
  payload/context evidence, attempt ledger, token, binding, fence, or lease is exposed through public
  DTOs, proposal JSON or `providerMetadata`, UI, evaluation reports, logs, browser storage, or
  service-worker caches. Attempt rows contain no provider text/ID/token/raw/context and receive no
  arbitrary TTL; real-model numeric usage/cost reporting, aggregation, budget enforcement, and an
  approved retention/purge policy remain unimplemented.
- Every new LOCAL, cloud-success, and fallback proposal canonicalizes `providerMetadata` through one
  bounded server allow-list; this is metadata hygiene, not provider authorization.
- A narrow owner-active exact tag/alias context is implemented: at most 10 proposal candidates and
  20 normalized terms are resolved against the complete equality result before deterministic K=8
  selection. It is a hint subject to final owner/reference validation, not accuracy evidence. Raw or
  related-memo retrieval, fuzzy/vector search, embeddings, and a real external provider remain
  absent. ADR 0007 first added a personal-overlay localhost Ollama/LiquidAI semantic-patch adapter;
  ADR 0008 now selects `AI_PREFERRED` plus a K=3 approved-type anchor hint only for that overlay. The
  explicit public-fixture shadow runner remains outside the product path and does not grant
  authoritative-model approval or accuracy evidence.

Do not connect a real LLM merely to make these roadmap bullets appear complete.

## Analysis-path operational evidence qualification (2026-08-28)

The source-qualified owner-scoped analysis-path summary is an operational, raw-free counter surface,
not an accuracy or provider evaluation. It separates configured local-model, external-transfer,
exact built-in Fake, and legacy/other routes from local-model-route contribution states. A route,
dispatch, `PENDING`, or `LOCAL_FALLBACK` count is not physical invocation evidence; an accepted state
only means a result recorded as successful on that configured route was accepted into an untrusted
proposal.

Disposable verification passed backend `126 suites / 905 tests` with one skip and zero
failure/error, SpotBugs with zero findings, frontend `46 files / 465 tests`, lint, type/PWA build,
OpenAPI lint, and one targeted Playwright lazy-load product test. The product test proved zero request
before expansion, one on first expansion, none on close/reopen, and one additional request only after
explicit refresh. All disposable container/network/volume/image resources and the scoped browser
cache downloaded for this run were removed; the existing personal containers kept the same IDs and
health.

No personal memo/session/database/canonical row, Apply mutation, Ollama/model call, RAG, training,
fine-tuning, LoRA, or automatic rule promotion was used. Consequently this slice adds no new
latency/GPU/VRAM or Fake-versus-LiquidAI quality claim. Its status is
`SOLO_PROVISIONAL`/`REPORT_ONLY`, and personal deployment remains separately authorized work.
