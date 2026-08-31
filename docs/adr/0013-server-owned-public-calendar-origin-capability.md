# ADR 0013: Server-owned public calendar origin capability contract

- Status: Accepted, implemented, and privately deployed `LOCAL_ONLY` for Milestone 6D.1
- Date: 2026-08-27
- Product status: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Implementation status: backend fail-closed property/controller and frontend strict decoder/warned UI
- Deployment status: owner-authorized personal V22 `LOCAL_ONLY`; public DNS/TLS/edge/operator/rate/
  external-log/client work is `NOT_AUTHORIZED`

## Context

Milestone 6C can assemble a recipient bearer URL once in PWA memory, but its deployed private UI
currently derives that URL from `window.location.origin`. That origin is a local-CA private-LAN
origin. Publishing it to a recipient would create a URL that is either unreachable or would tempt an
operator to expose the full private application boundary.

The future public subscription origin is deployment authority. It must not be selected by memo
content, feed metadata, browser state, a request `Host` or forwarded header, or caller input. A valid
explicit `LOCAL_ONLY` state may still produce a clearly warned local/isolated URL for private testing,
but absence, request failure, or invalidity must not be converted into that state or silently fall
back. The capability also must not imply that DNS, public-suffix ownership, trusted TLS, a feed-only
listener, bounded edge operation, or external-client interoperability exists.

## Decision

1. Reserve authenticated, read-only
   `GET /api/v1/calendar-feeds/capabilities`. A successful response is
   `Cache-Control: no-store` and has exactly two required fields with no additional properties.
2. The response is the exact discriminated union:

   ```json
   {"mode":"LOCAL_ONLY","publicOrigin":null}
   ```

   or:

   ```json
   {"mode":"PUBLIC_HTTPS","publicOrigin":"https://calendar.example.com"}
   ```

   A missing field, extra field, or mismatched `mode`/`publicOrigin` combination is invalid.
3. `LOCAL_ONLY` is the fail-closed public-publication default. Backend properties bind disabled and
   blank by default. A nonblank origin while disabled, or a missing/noncanonical origin while enabled,
   prevents startup. Neither a request nor a feed/calendar row can enable publication.
4. A `PUBLIC_HTTPS` origin is a maximum 255-character canonical lowercase ASCII `https://` origin
   containing at least two DNS-shaped labels and an optional non-default port from 1 through 65535.
   Userinfo, IP literals, `localhost` and its subdomains, path, query, fragment, trailing slash,
   explicit `:443`, invalid label/port syntax, and non-HTTPS schemes are rejected. This is syntactic
   validation, not public-suffix ownership or DNS reachability proof. The returned string is not
   derived from `Host`, forwarded headers, browser location, memo text, or feed state.
5. The strict PWA decoder rejects missing/extra fields and mismatched branches. Only
   `PUBLIC_HTTPS` assembles
   `<publicOrigin>/calendar/v1/feed.ics?token=<in-memory-secret>`. A valid exact `LOCAL_ONLY` branch
   may instead use the current exact HTTP(S) PWA origin, but the UI labels it local/isolated and warns
   not to send it to an external recipient. Missing, failed, or malformed capability responses are
   not converted to `LOCAL_ONLY` and do not silently fall back.
6. Milestone 6D.1 implements the property binding, authenticated no-store controller, frontend
   type/decoder and warned URL UI. Its owner-authorized personal V22 deployment passes no publication
   environment and therefore remains `LOCAL_ONLY`. It adds no Flyway migration, canonical/feed row,
   listener, firewall rule, DNS record, certificate, public route, or external request. Deployment
   smoke did not use a personal session to execute the authenticated 200 response.
7. Public edge implementation remains a separately authorized 6D step. It must expose only exact
   `GET|HEAD /calendar/v1/feed.ics`, reject all other paths and methods, enforce rate/connection/
   execution/response bounds, exclude query bearers from every owned success/error log, and pass
   owned/external sentinel plus Google/Apple subscription smoke before any interoperability claim.
   ADR 0014 later adds a loopback-only source/preflight implementation of this edge boundary, but does
   not grant the public activation or external-client authorization described here.

## Consequences

- A private application origin can be used only after an explicit valid `LOCAL_ONLY` capability and
  with a local/isolated warning; failure cannot silently become authority.
- The authenticated API can advertise a configured non-secret public hostname without returning or
  storing a bearer token. Capability reads create no canonical or access-state write.
- The exact union gives frontend decoders a fail-closed state instead of an optional string whose
  absence could be misinterpreted.
- This implementation and private `LOCAL_ONLY` deployment are not evidence of public-suffix control,
  a reachable hostname, trusted certificate, publicly operated edge, token-free external logs, or
  Google/Apple behavior. The later loopback-only preflight does not provide that evidence; public
  activation and those external checks remain `NOT_AUTHORIZED`.
- Recipient feed creation, membership, rotation, revocation, disclosure, and manual Apply boundaries
  remain unchanged. No schedule is shared automatically and no alarm/reminder delivery is added.
