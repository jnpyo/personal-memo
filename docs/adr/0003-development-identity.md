# ADR 0003: Development single-user identity

Status: Accepted — 2026-08-05

Development uses one seeded user selected server-side. Every owned table and query still carries `owner_id`. The client cannot choose an owner. Production authentication and retention policy require a later ADR; secure cookie authentication is the current recommendation.

