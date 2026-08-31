# ADR 0011: Authenticated iCalendar snapshot export

- Status: Accepted for source-only Milestone 6B
- Date: 2026-08-25
- Product status: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Deployment status: personal V21 migration, rebuild, product smoke, public subscription edge, and
  external calendar-client smoke are not authorized

## Context

Milestone 6A.1 created a canonical EVENT schedule only after explicit review and Apply. Before a
recipient bearer URL can exist, the serializer and disclosure boundary need verification through an
authenticated surface that does not expose the application or create sharing state. A downloaded
`.ics` file is a point-in-time import; it is not a continuously refreshed subscription.

RFC 5545 requires a non-empty calendar component, CRLF content lines, UTF-8-safe folding at no more
than 75 octets, escaped TEXT values, persistent opaque `UID`, UTC `DTSTAMP`, `DTSTART`, and a
type-compatible later `DTEND` when one is present. Its DATE-TIME grammar has whole-second precision.

## Decision

1. Add authenticated `GET /api/v1/events/calendar.ics`. It is session- and owner-snapshot-guarded,
   read-only, and `Cache-Control: no-store`. A successful response is UTF-8 `text/calendar` with the
   fixed ASCII attachment name `personal-memo-calendar.ics`.
2. Reuse the exact 6A.1 eligibility query: current owner, `APPLIED`, active memo, current revision,
   unarchived EVENT item, and complete canonical `event_details`. Probe 101 rows and fail closed when
   more than 100 are eligible rather than silently returning a partial calendar. Return `204` when
   none are eligible rather than generating an RFC-invalid empty `VCALENDAR`.
3. Emit timed values in UTC and all-day values as `VALUE=DATE`. Preserve the canonical exclusive
   all-day end and emit `DTEND` only when the user approved an explicit end. Do not infer a duration,
   emit a source-zone extension, or add `METHOD`, recurrence, `VALARM`, location, description,
   attendee, organizer, attachment, or URL properties.
4. Derive a stable 6B-only opaque UID from a domain-separated SHA-256 of owner UUID and canonical
   memo-item UUID. Output neither UUID. Use immutable canonical item creation time for `DTSTAMP` and
   `SEQUENCE:0`; this is honest because a current item is immutable and undo removes it.
5. Escape backslash, comma, semicolon, and normalized newlines in `SUMMARY`. Reject unsupported
   controls and malformed surrogate text without putting the title in an error. Fold each physical
   line at 75 UTF-8 octets including the continuation space, terminate every line with CRLF, omit a
   BOM, and fail closed above 128 KiB or outside RFC's four-digit year range.
6. Reject fractional-second TIMED Apply values before canonical write. The serializer repeats this
   check defensively, so an exact time is never rounded or truncated into a different schedule.
7. The PWA fetches the file through the existing same-origin session epoch and
   `X-Expected-Owner-Id` boundary. It previews escaped plain text and downloads the exact same
   in-memory Blob. It does not navigate directly to the API, store the Blob, or involve the service
   worker cache.
8. This slice creates no Flyway migration, share row, token, public route, canonical write, model
   call, RAG corpus, training, fine-tuning, or LoRA activity. Personal V21 deployment remains a
   separate owner decision.

## Consequences

- The owner can inspect and download a deterministic schedule-only snapshot before any public bearer
  endpoint exists.
- Raw memo bodies, TASK due values, tags, relations, AI/application provenance, and internal UUIDs
  remain outside the file.
- A downloaded copy remains on the receiving device and cannot be revoked by Personal Memo.
- Reanalysis creates a new canonical item rather than updating the previously imported file. This
  slice therefore makes no automatic update or removal claim.

## 6C gate

Continuous recipient sharing still requires a separate persisted feed identity, membership and
disclosure policy, recipient-scoped UID/sequence/removal semantics, high-entropy revocable bearer
secret verifier, token-free owned logs, rate and execution bounds, and an explicitly approved
feed-only trusted HTTPS edge. The 6B UID must not be reused across recipient feeds.
