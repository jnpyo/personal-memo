# ADR 0002: Server database is canonical in MVP

Status: Accepted — 2026-08-05

PostgreSQL is the canonical store. Raw memo revisions are immutable and independent of proposals. Full offline synchronization, conflict resolution, Web Push, taxonomy automation, and node compression are deferred until P1 or later. Mutations intended for retry accept an idempotency key.

