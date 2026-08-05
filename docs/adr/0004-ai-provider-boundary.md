# ADR 0004: Proposal-only AI boundaries

Status: Accepted — 2026-08-05

P0 uses deterministic fake implementations only. Frontend analysis depends on `LocalAnalyzer`; backend cloud enrichment depends on provider-independent `CloudAnalysisGateway`. Neither analyzer receives canonical mutation tools. Validated proposals can create tags, tasks, or relations only through an explicit user-confirmed transactional application.

