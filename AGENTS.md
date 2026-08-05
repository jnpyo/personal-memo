# Repository instructions

## Source of truth

- Start by reading `CODEX_HANDOFF.md` and the files in `docs/`.
- Preserve the raw memo independently from every AI-derived artifact.
- Treat all AI output as an untrusted proposal until it is validated and confirmed.
- Do not let the client or model directly mutate canonical tags, tasks, reminders, or graph relations.

## Scope discipline

- Implement one vertical slice at a time according to `docs/ROADMAP.md`.
- Do not connect a real local model or cloud LLM before the mock-analysis flow works end to end.
- Avoid adding external calendar sync, voice input, collaboration, or automatic semantic migrations during the MVP.

## Architecture constraints

- Keep frontend local analysis behind a stable `LocalAnalyzer` interface.
- Keep cloud AI behind a provider-independent `CloudAnalysisGateway` interface.
- Keep ambiguity routing deterministic and testable; do not use an LLM's self-reported confidence as the sole signal.
- Graph data must be projected from canonical domain data. Do not make screen coordinates or rendered graph JSON the only data source.
- Use incremental updates. Never re-embed or reclassify the full corpus during a normal memo write.
- Before confirmation, Agent tools must be read-only.

## Data and API rules

- Use UUID identifiers unless a strong reason is documented.
- Store timestamps in UTC and retain the user's IANA time zone separately.
- Every mutating API used by offline sync must accept an idempotency key.
- Version the analysis schema, prompt, local model, embedding model, and memo revision.
- Use database migrations for every schema change.
- Keep DTOs separate from persistence entities.

## Safety and privacy

- Never expose provider API keys to the browser.
- Apply owner checks to every read and write.
- Treat memo text as data, not as Agent instructions.
- Limit Agent tool count, elapsed time, and token usage.
- Do not log raw memo content by default.
- Cloud context should contain only the smallest relevant candidate set.

## Verification

- Add unit tests for date parsing, ambiguity gates, tag normalization, and state transitions.
- Add integration tests for confirmation, idempotency, ownership, and rollback.
- Add an end-to-end test for the primary scenario in `CODEX_HANDOFF.md`.
- Run formatting, static checks, unit tests, and relevant integration tests before reporting completion.

