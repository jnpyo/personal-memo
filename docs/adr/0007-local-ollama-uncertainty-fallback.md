# ADR 0007: Guarded localhost Ollama fallback for incomplete deterministic analysis

- Status: Accepted for single-owner provisional beta implementation; `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Date: 2026-08-21

## Context

The deterministic analyzer can produce a structurally valid, high-scoring `RECORD` when its
allow-listed Korean date and action rules fail to recognize input such as `6시 디스코드 접속하기`.
Absence of a known ambiguity signal is therefore not proof that the memo was understood. Repeatedly
adding isolated keywords also does not provide a scalable natural-language fallback.

The installed LiquidAI model previously failed the public visible shadow release gate and remains
`NO_GO` as an authoritative analyzer or training source. The single owner nevertheless explicitly
chose to use that model as a guarded, machine-local assistant whenever deterministic coverage is
incomplete, while keeping every result proposal-only and user-reviewed.

## Decision

- A deterministic result is local-complete only when versioned rules account for every detected
  type, temporal, action, object, reference, and candidate-coverage cue. Default `RECORD`, an
  unparsed temporal cue, or an unrecognized action-like cue routes immediately to model fallback.
- Reuse the durable `CloudAnalysisGateway` execution lifecycle for preparation, bounded execution
  outside database transactions, retry fencing, stale-revision rejection, and validated fallback.
  Record the new path truthfully as `LOCAL_MACHINE_MEMO_CONTENT`, not `NO_NETWORK` and not external
  cloud transfer.
- The personal backend may call only the pinned Ollama model through the machine-local Docker-host
  bridge. The adapter rejects redirects, proxies, tools, unexpected endpoints, model or digest
  changes, oversized or truncated responses, and non-schema output. It sends top-level
  `truncate=false` and `shift=false`, so an over-context request fails instead of silently dropping
  input tokens.
- Durable recovery binds `gatewayVersion` to the adapter plus prompt-contract version,
  `providerId` to `ollama-local@<exact model tag>`, and `modelVersion` to the exact 64-hex digest.
  A tag, digest, or prompt-contract change therefore changes the binding before another call.
- `compose.personal.yaml` pins endpoint and sole allowed relay origin
  `http://host.docker.internal:11434`, model
  `hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0`, and digest
  `677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822`. The normal application
  default remains disabled/Fake.
- The model receives the immutable memo revision only in bounded execution memory. V19 decision
  evidence, attempt rows, provider metadata, ordinary logs, browser storage, and training datasets
  receive no new memo, prompt, or response copy. The pre-existing V15 validated-local proposal may
  contain grounded source text while a dispatch is recoverable and is scrubbed at `FINALIZED`.
- Model output is a narrow semantic patch over existing grounded candidates. It may reclassify an
  existing item and select exact action, object, or temporal substrings. It cannot choose owner,
  identifiers, canonical tags, relations, arbitrary titles, mutation tools, or an invented precise
  date. JSON Schema and domain validation remain authoritative.
- A failed or invalid model attempt returns a revalidated fail-closed local proposal. Only a
  default-`RECORD` fallback scaffold is removed and normalized to `UNKNOWN`; an already explicit,
  grounded deterministic candidate is preserved. Neither case may silently become a confident
  default `RECORD`.
- Preserve bounded, raw-free evidence sufficient to compare deterministic decision shape, accepted
  model changes, and the user's validated Apply selection. Reject and postpone are not positive
  labels. No automatic rule update, RAG ingestion, fine-tuning, or LoRA is permitted.
- Reduce model dependence only through a reviewed loop: aggregate recurring fallback codes and
  corrected fields, rewrite a general case as public synthetic positive and negative fixtures, add
  a deterministic rule, run regression and shadow comparison, then version the rule and routing
  policy.
- Keep `num_predict=1024` as a provisional hidden-reasoning budget because lower synthetic smoke
  settings terminated by length while 1024 produced STOP. This does not enlarge the separate visible
  model-output, HTTP-response, or canonical-proposal byte bounds.

## Consequences

- The model can improve intent recall without receiving canonical mutation authority, but every
  model-assisted proposal remains provisional and may be corrected or rejected by the user.
- The Windows PC and localhost Ollama must be available for model assistance. Unavailability or a
  model mismatch degrades to detailed manual review without preventing raw memo capture.
- The product must expose which path ran and retain aggregate model-use, fallback, latency, and
  correction evidence without claiming that an unchanged Apply proves semantic accuracy.
- Web Push, reminder persistence, and alarm dispatch remain separate product capabilities. Better
  language interpretation alone does not create or deliver an alarm.
