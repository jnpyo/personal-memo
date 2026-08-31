# ADR 0015: Explicit per-feed public-publication consent

## Status

Accepted for source-only implementation. Runtime public activation remains `NO_GO` and is not
authorized by this ADR.

## Context

Milestone 6C created recipient-specific bearer feeds while the deployment capability remained
`LOCAL_ONLY`. Milestone 6D.1 added a server-owned `PUBLIC_HTTPS` origin, but the V22 feed row did not
record whether its owner had explicitly accepted external publication. If deployment configuration
alone changed to `PUBLIC_HTTPS`, an existing local bearer could otherwise become usable at the
public edge without a fresh disclosure decision or credential.

The bearer enters Cloudflare's processing boundary. `BUSY_ONLY` still reveals selected dates and
times, `TITLE` also reveals approved titles, and already downloaded or cached copies cannot be
recalled. A client-side checkbox alone cannot enforce this boundary.

## Decision

- V23 adds `publication_scope`, `public_consent_policy_version`, and
  `public_consent_granted_at` to each feed. Existing and newly created feeds default to
  `LOCAL_ONLY` with no consent pin; no existing bearer is promoted.
- The publication capability includes the exact server-owned consent-policy version only when
  `PUBLIC_HTTPS` is enabled. Origin and policy configuration must be coherent or startup fails.
- Public publication is enabled only by an authenticated, CSRF-protected, owner-scoped,
  idempotent mutation with an exact expected feed version, the current policy version, and a fresh
  client-generated 32-byte bearer secret.
- Consent persistence, verifier rotation, consent timestamp, and one feed-version increment commit
  in one transaction. The old local or public bearer is invalid immediately, and neither the
  secret nor a URL is returned or stored in idempotency output.
- In `LOCAL_ONLY` deployment mode, the bearer read serves only active local-scope feeds. In
  `PUBLIC_HTTPS` deployment mode it serves only active public-scope feeds whose consent pin matches
  the current server policy. Every mismatch uses the existing indistinguishable empty no-store
  `404`.
- Changing a public feed's disclosure mode requires a new consent flow; the ordinary metadata
  update fails closed instead of expanding disclosure. Explicit membership add/remove operations
  remain owner actions and never select future events automatically.
- Permanent feed revocation withdraws publication consent, clears the public scope/pin, and keeps
  the existing irreversible bearer invalidation semantics. A reversible public-to-local downgrade
  is deferred until a separate safe one-time local-secret delivery contract exists.

## Consequences

Switching deployment configuration can no longer publish a legacy feed. A public deployment may
temporarily make a newly created local feed unreadable until its owner completes the separate
external-publication mutation, which is deliberate fail-closed behavior. A changed consent policy
also makes existing public feeds unreadable until the owner re-consents with a new bearer.

The PWA must treat unknown policy versions and malformed capability/feed responses as blocking
errors. It shows the public URL once from in-memory secret material only after the mutation succeeds;
the URL remains read-only and non-navigable.

This source contract does not resolve Cloudflare provider/customer request-log handling, receipt-
level replica proof, external rate/deadline policy, or Google/Apple subscription behavior. It does
not authorize personal V23 migration, personal feed creation, public connector start, canonical
schedule inspection, Apply, Ollama, fine-tuning, or LoRA. Status remains
`SOLO_PROVISIONAL` / `REPORT_ONLY`.
