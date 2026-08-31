# ADR 0012: Recipient calendar feed source boundary

- Status: Accepted for Milestone 6C and the owner-authorized private V22 deployment
- Date: 2026-08-25
- Product status: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Deployment status: personal V22 migration/rebuild/private unknown-token route smoke passed on
  2026-08-27; the public feed-only 6D edge is not authorized

## Context

Milestone 6B exports one authenticated point-in-time `.ics` file. Continuous sharing needs a
different identity and revocation boundary: each recipient must receive only schedules that the
owner selected for that feed, and one recipient must not be correlatable with another recipient or
with the 6B export. A bearer URL is a credential. Storing that credential in a feed row, an
idempotency replay body, browser storage, or an owned request log would defeat revocation and the
digest-only requirement.

[RFC 5545](https://www.rfc-editor.org/rfc/rfc5545.html) defines persistent `UID`, revision
`SEQUENCE`, and `STATUS:CANCELLED`. [RFC 7986](https://www.rfc-editor.org/rfc/rfc7986.html) updates
UID guidance so identifiers do not disclose user, host, domain, or other privacy-sensitive data.
Whether Google Calendar or Apple Calendar removes a subscribed event from a cancellation tombstone
is an interoperability question for the separately approved 6D client smoke, not a source claim.

## Decision

1. Add authenticated, owner-scoped management APIs under `/api/v1/calendar-feeds`. Create, edit,
   explicit event add/remove, token rotation, and revocation remain cookie-session, CSRF,
   `X-Expected-Owner-Id`, and idempotency guarded. Mutations of an existing feed additionally require
   its expected version; create has no pre-existing version. No operation automatically adds future
   events.
2. Default creation to `BUSY_ONLY`; every event starts unselected in the PWA. `TITLE` is an explicit
   per-feed choice with a title-disclosure warning. Only a current canonical scheduled EVENT that
   passes the 6A.1 owner, revision, application, memo-state, archive, and temporal checks may be
   added.
3. The PWA creates exactly 32 random bytes with Web Crypto and sends the canonical 43-character
   base64url-without-padding secret in create or rotate. The server immediately computes
   `SHA-256("calendar-feed-bearer-v1\\0" || secret)` and stores only the lowercase verifier. API
   responses and idempotency replay JSON contain neither secret, verifier, nor subscription URL.
4. A successful create or rotate lets the PWA assemble the URL once in React memory. It presents a
   read-only, non-link copy control and clears the secret on close, session change, or update
   departure. The URL, secret, and fetched feed body are not written to browser or service-worker
   storage. A retry reuses the same in-memory secret, request body, and idempotency key. At this
   checkpoint the URL uses the private same-origin PWA base; 6D must define and validate a separate
   trusted public feed base before it can be an internet subscription URL.
5. Use one fixed publication target,
   `GET|HEAD /calendar/v1/feed.ics?token=<43-character-secret>`. A dedicated first-order stateless
   Spring Security chain handles only that path and never loads an application session, owner
   header, CSRF token, or request cache. Missing, malformed, unknown, rotated, and revoked tokens
   return the same empty no-store `404` shape. Publication is read-only and records no last-access
   timestamp.
6. Store a per-feed entry projection with a random public UID, monotonic sequence, explicit temporal
   snapshot, source identity digest, and `ACTIVE` or `CANCELLED` state. The 6B UID is never reused.
   Removing an entry, editing/trashing its memo, or undoing its application first preserves a
   cancellation tombstone and increments the sequence in the same canonical mutation transaction.
   Restore does not automatically reshare; an explicit eligible add may reactivate the same entry
   and increment its sequence again.
7. An ACTIVE component emits its recipient UID, projection `DTSTAMP`/`SEQUENCE`, approved DTSTART,
   optional approved DTEND, and either the current approved title or fixed `Busy`. A CANCELLED
   component keeps the UID and approved temporal snapshot, increments `SEQUENCE`, emits
   `STATUS:CANCELLED`, and omits `SUMMARY`. Every public read rechecks current canonical eligibility;
   if an `ACTIVE` projection ever fails that invariant despite the transactional hooks, the whole
   publication fails as the same generic empty `404` instead of returning a misleading partial feed.
8. Bound each owner to 100 lifetime feeds including revoked rows, each feed to 100 lifetime entries
   including cancellation tombstones, and a completed response to 128 KiB. Milestone 6C has no feed
   deletion or capacity reclamation. Do not emit raw memo, TASK, tag, relation,
   proposal/application/model provenance, internal UUID, description, location, attendee, organizer,
   attachment, URL, recurrence, `METHOD`, or `VALARM` data.
9. The current private same-origin proxy may route this fixed path while logging only the fixed
   normalized URI and never query arguments. A public hostname, edge operator, rate/connection
   limits, timeout behavior, and success/error log sentinel smoke belong to Milestone 6D and require
   separate approval. The later private V22 deployment did not open an internet listener.

## Consequences

- Each recipient can be rotated or revoked independently, and different recipients see different
  opaque event identities.
- `BUSY_ONLY` still reveals selected dates and times; the UI must state that clearly. Any copied,
  imported, or cached calendar data already held by a recipient cannot be recalled.
- The server cannot reproduce a lost bearer secret. If the one-time response is lost after the PWA
  loses its in-memory retry state, the owner rotates the feed and redistributes a new URL.
- Cancellation compatibility with external calendar clients, real edge rate limiting, public DNS
  and TLS, and owned/external log verification remain unclaimed until 6D.
- No analyzer, model, RAG corpus, training, fine-tuning, or LoRA path participates in calendar-feed
  management or publication.
