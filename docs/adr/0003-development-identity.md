# ADR 0003: Development single-user identity

Status: Superseded by ADR 0005 — 2026-08-05

The initial AI-free slice used one seeded user selected server-side. Every owned table and query still carried `owner_id`, and the client could not choose an owner. ADR 0005 replaces the runtime identity with authenticated internal users while preserving this seeded row and its data for migration compatibility.
