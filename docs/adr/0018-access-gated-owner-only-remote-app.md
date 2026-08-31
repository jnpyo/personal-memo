# ADR 0018: Access-gated owner-only remote application edge

## Status

Accepted for source and disposable-synthetic implementation. Live activation remains
`SOLO_PROVISIONAL/REPORT_ONLY` and requires the owner confirmations listed below.

## Context

The private beta currently serves the PWA over a private-LAN TLS listener. The separately approved
the configured `calendar.<owner-zone>` surface exposes only one exact, bearer-bound iCalendar route through its own
loopback edge and Cloudflare Tunnel. Reusing that route, edge, connector, token, or hostname for the
authenticated PWA would collapse two different privacy and rollback boundaries.

A full remote PWA also changes the data processor boundary. Cloudflare terminates public TLS, so
login credentials, `SESSION` and `XSRF-TOKEN` cookies, API paths and metadata, raw memo request and
response bodies, and canonical application data can pass through Cloudflare infrastructure. Access
restricts who can reach the origin; it does not make Cloudflare unable to process that traffic.

The product is not ready for unrestricted public self-service. Email verification and recovery,
MFA/passkeys, account deletion, least-privilege database roles, and public monitoring and incident
response remain outside this milestone. A single-owner Access gate can provide a narrower remote
beta without claiming those gaps are complete.

## Decision

Add a second, independent public topology for an owner-only remote beta:

1. one reviewed single-label application hostname under the owner's zone, never the zone apex,
   wildcard, or the configured `calendar.<owner-zone>` hostname;
2. one Cloudflare self-hosted Access application with default deny and an allow rule for one exact
   owner email identity only;
3. one dedicated named Tunnel, protected app route, token file, Manual Windows service, and
   loopback metrics listener;
4. one unprivileged, read-only application edge published only on `127.0.0.1:8788`, connected to
   the existing frontend through the internal `app-publication` network, and attached alone to the
   non-internal `app-loopback` bridge required for Windows Docker Desktop host-loopback publishing;
5. the existing same-origin Spring session, CSRF, owner derivation, proposal validation, and explicit
   Apply boundary as a second independent application gate.

The application edge accepts only the exact configured Host. It rejects the public calendar feed,
Actuator, internal paths, registration, unsupported methods, and unsafe requests without the exact
same-origin `Origin`. It replaces authority and forwarded scheme/port headers with reviewed constants,
removes caller-supplied forwarding and Cloudflare Access identity headers, and allow-lists only the
application headers needed for cookies, CSRF, idempotency, content negotiation, and schema-version
validation. Access identity is never mapped directly to an application owner.

The incoming Cookie header is not forwarded wholesale. The edge selects only the first exact,
bounded `SESSION` and `XSRF-TOKEN` values and reconstructs a new application Cookie header.
`CF_Authorization`, unknown cookies, empty values, malformed values, and duplicate trailing values do
not cross into the frontend/backend origin. Cloudflared's Protect with Access validation remains the
perimeter JWT gate; the application never treats an Access cookie or identity header as owner proof.

The edge applies body, header, connection, rate, and timeout bounds. It sets the authoritative CSP,
HSTS, frame, MIME, referrer, permissions, cache, and cross-origin isolation headers. HTML, auth, API,
OAuth, manifest, and service-worker responses are `no-store`; only fingerprinted static assets may
be immutable, and only for final `200`, `206`, or `304` responses. Hash-shaped errors remain
`no-store`. Registration blocking also covers literal and percent-decoded semicolon matrix
parameters before the generic API proxy. The Cloudflare hostname uses an entire-host cache bypass
during this provisional beta.

`app-loopback` can provide container outbound reachability and is therefore a residual risk, not an
isolation boundary. No frontend, backend, or PostgreSQL service joins it. The edge remains
unprivileged, read-only, `cap_drop: ALL`, `no-new-privileges`, and fixed to the frontend upstream over
the separate internal network; it has no configurable forward proxy or arbitrary upstream route.

Access and origin logs must not contain request targets, client addresses, query strings, cookies,
authorization values, Cloudflare identity/JWT headers, memo bodies, or identifiers. Edge logs retain
only fixed method and route classes, status, byte count, and duration. Cloudflared remains at warning
logging without debug or request tracing.

Google authentication and registration remain disabled for the first remote beta. Enabling Google
later requires a separate exact HTTPS redirect review and a real provider round-trip smoke while the
callback remains inside the Access policy.

## Activation gates

Source contracts, Compose rendering, Nginx configuration, and disposable local edge tests may run
without personal data. A remote synthetic qualification may use only the public synthetic fixture and
must roll the connector back before cleanup.

Before any live route or connector start, the owner must explicitly confirm all of the following:

- the exact application hostname;
- the exact Access email identity and chosen identity provider or one-time PIN method;
- default-deny Access, no `Everyone`, email-domain, Bypass, or Service Auth policy, and Protect with
  Access on the Tunnel route;
- acknowledgement that the full application traffic described above crosses Cloudflare's processing
  boundary;
- an entire-host cache bypass and absence of request-body/header/cookie/query log export.

The connector starts last. Automated live smoke stops at public shell/capability and application-401
checks and must not log in, read a memo, query the personal database, inspect canonical data, or call
Apply. The owner performs the first actual login and PWA screen check directly.

## Rollback

Stop the application connector first and prove that the external origin no longer succeeds. Then stop
the application edge. Preserve the private stack, PostgreSQL volume, and calendar connector unchanged.
Any Cloudflare Access, DNS, or Tunnel cleanup is a separately confirmed provider mutation. If HSTS was
activated, keep HTTPS available for its bounded lifetime or first serve `max-age=0` during a planned
rollback.

## Consequences

This architecture supports an Access-restricted personal remote beta while keeping calendar sharing,
private-LAN use, and canonical application authority separate. It does not authorize general public
registration, claim production readiness, add a new API or database migration, weaken JSON Schema or
domain validation, auto-apply analysis proposals, train a model, or add fine-tuning/LoRA.
