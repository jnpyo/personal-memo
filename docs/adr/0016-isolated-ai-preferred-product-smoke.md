# ADR 0016: Isolated AI-preferred product-path smoke

- Status: Accepted for source implementation; live evidence remains report-only
- Date: 2026-08-28

## Context

The earlier localhost LiquidAI checks qualified a narrow model adapter and a manual disposable
product flow. They did not provide a repeatable tool that compares the deterministic Fake path with
the AI-preferred path while proving that personal configuration, PostgreSQL data, canonical records,
and the normal Ollama endpoint stay outside the dependency graph.

The product boundary is unchanged: model output is untrusted, analysis creates a proposal for review,
and only the existing explicit user approval API may create canonical tags, tasks, events, or
relations. A synthetic smoke must therefore stop at proposal retrieval and must not turn its visible
fixtures into accuracy, release, provider-readiness, training, or LoRA evidence.

## Decision

Add a Windows PowerShell qualification runner with these fixed boundaries:

1. It uses only the three public synthetic cases in
   `fixtures/ai-preferred-product-smoke-cases.json` and validates that fixture before execution.
2. Fake and LiquidAI run in distinct Compose projects. Each project contains only a backend and a
   dedicated tmpfs PostgreSQL database; neither project imports a personal environment file, TLS
   material, a personal volume, or a public-feed overlay.
3. LiquidAI uses an exact runner-owned Ollama process bound to `127.0.0.1:11435`. The normal personal
   endpoint is not queried, stopped, unloaded, or reconfigured.
4. The runner exercises registration, memo capture, synchronous analysis start, and proposal GET.
   Its request allow-list excludes proposal Apply, reject, postpone, application undo, calendar-feed,
   public-service, alarm, and reminder operations.
5. Both arms must return schema-v2, domain-accepted, `REVIEW_REQUIRED` proposals. The affirmative case
   must remain a grounded task with unresolved bare time; the negated and descriptive cases must not
   be promoted to a task or receive a precise date or due binding.
6. Aggregate database evidence must show a zero pre/post delta in every Apply-derived canonical
   table, including the seeded tag tables. Disposable users, raw memos, revisions, runs, and
   proposals are expected inside the temporary databases and are destroyed with the tmpfs projects.
7. A receipt contains only fixture/source hashes, counts, bounded enum evidence, latency statistics,
   device-wide non-exclusive GPU/VRAM observations, and cleanup proof. It contains no memo body,
   account credential, cookie, CSRF value, UUID, provider request token, prompt, or model response.
8. The runner publishes a success receipt only after both exact Compose projects, the owned model
   process/listener, the owned image, and the owned temporary directory are gone and the personal
   Compose container count is unchanged.

## Consequences

- A passing receipt means only `PASS_NARROW_PRODUCT_PATH` under
  `SOLO_PROVISIONAL/REPORT_ONLY`.
- Provider readiness, training, fine-tuning, and LoRA remain `NO_GO`; RAG is not used.
- Model termination reasons such as `STOP` or `LENGTH` are diagnostic and are not safety gates.
- Any unsafe negative/descriptive promotion, schema/domain rejection, canonical write, personal-scope
  dependency, or incomplete restoration prevents receipt publication.
- The smoke does not change the public API, OpenAPI contract, Flyway schema, or production runtime.
