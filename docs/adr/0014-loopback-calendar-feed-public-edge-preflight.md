# ADR 0014: Loopback-only calendar feed public-edge preflight and two-step activation

- Status: Accepted for source/preflight implementation; public activation remains separately authorized
- Date: 2026-08-27
- Product status: `SOLO_PROVISIONAL` / `REPORT_ONLY`
- Runtime status: source/preflight plus bounded external synthetic qualification; official Windows binary `DOWNLOADED_VERIFIED`; remote named
  Tunnel and exact-path published route/DNS configured; hardened `PersonalMemoCalendarCloudflareTunnel`
  installed and verified `Stopped`/`Manual`/`LocalSystem`, protected token-file-only, no inline token;
  generic default `Cloudflared` service removed; current connector process and port `8787`/`49312`
  listener counts zero; Cloudflare status `Down` after rollback; external transport/path/cache and
  owned-log sentinels recorded, provider/customer log unavailable/unverified and receipt replica proof
  `REQUIRED_NOT_VERIFIED`; activation `NO_GO`; actual activation and
  Google/Apple smoke final-gate `NOT_AUTHORIZED`

## Context

Milestone 6D.1 provides a fail-closed server-owned `LOCAL_ONLY`/`PUBLIC_HTTPS` capability, while
Milestone 6C already has one fixed bearer publication path. Directly enabling `PUBLIC_HTTPS` in the
private application stack would combine two independent changes: introducing a new edge process and
authorizing an external subscription origin. It would also make rollback harder to reason about.

The first external-facing component must never expose the private PWA, authenticated API, OAuth,
Spring Boot port, or PostgreSQL. Query bearer values must not appear in success or error logs, even
when a client places a token in a rejected path. Source verification must remain possible with public
synthetic data and a stub upstream, without reading or changing a personal database, memo, feed, or
canonical schedule.

## Decision

1. The official checkpoint name is **`6D public-edge preflight`**. It is not a new `6D.2` milestone
   and it is not public activation.
2. `compose.public-feed.yaml` adds a dedicated `calendar-feed-edge` on an internal backend network and
   publishes that edge only on Windows loopback,
   `127.0.0.1:${PERSONAL_MEMO_CALENDAR_EDGE_PORT:-8787}`. It does not set the backend publication
   capability to `PUBLIC_HTTPS`. The PWA listener, authenticated API, direct Spring Boot port, and
   PostgreSQL remain outside this edge.
3. The edge accepts only the exact bodyless raw target
   `GET|HEAD /calendar/v1/feed.ics?token=<canonical-43-character-secret>`. Every other method, path,
   encoded-path variant, missing/duplicate/extra query, request body, and intercepted upstream error
   is reduced to a generic empty `404`; rate rejection is a bodyless `429`. Access logs retain only
   fixed safe route and method classifications and never the raw method, request target, query,
   forwarded client values, or bearer. No client cookie, authorization, referer, or forwarded header
   reaches the backend.
4. The source preflight uses provisional **origin-side** bounds: `60 requests/minute` with burst `20`,
   at most `8` concurrent connections, and upstream connect/send/read timeouts of `2s`/`5s`/`10s`.
   These limits are global at this loopback hop because an external tunnel or reverse proxy may be the
   immediate peer. They do not define a per-client external policy, a total external request deadline,
   an end-to-end latency budget, or an SLA. Cloudflare WAF may add a supplementary external rate rule,
   but a separately designed component is required if exact total-deadline or response-size enforcement
   is mandatory.
5. `compose.public-feed.test.yaml` and
   `scripts/public/Test-PersonalMemoPublicFeedEdge.ps1` exercise the edge against a disposable Nginx
   stub and generated synthetic bearer. The smoke covers exact GET/HEAD forwarding, rejected surface,
   header replacement, stripped caller headers, provisional rate limiting, and owned edge/upstream
   token-free logs. It has no product PostgreSQL, personal session, memo, canonical schedule, or Apply
   path. The recorded isolated run passed, including query/path/header/custom-method bearer sentinels;
   this is owned disposable-test evidence, not external-operator log proof.
6. The selected public operator is a **Cloudflare remotely-managed named Tunnel**. It requires a
   Cloudflare account and an owner-controlled domain whose active DNS zone is on Cloudflare. Login and
   active-zone ownership are verified. The remote named Tunnel, stable single-label published
   application/DNS and exact-path loopback route are configured. The connector is stopped and
   Cloudflare reports `Down`.
   Quick Tunnels and
   `*.trycloudflare.com` are prohibited because they are temporary development facilities rather than
   the reviewed stable publication origin. The official Windows binary is `DOWNLOADED_VERIFIED`:
   version `2026.8.2`, SHA-256
   `c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5`, Authenticode
   `Valid`, signer `Cloudflare, Inc.`. Hardened `PersonalMemoCalendarCloudflareTunnel` is installed and
   verified `Stopped`/`Manual`/`LocalSystem`; its ImagePath references only the protected token file and
   has no inline token. The generic default `Cloudflared` service is removed, and current `cloudflared`
   process and port `8787`/`49312` listener counts are zero. These remote and local objects do not by
   themselves activate the product feed.
   - Windows PowerShell 5.1 Install/Start/Stop scripts implement a protected token-file and manifest,
     stopped Manual LocalSystem service contract. Installer hidden input accepts only a raw Tunnel token
     or one exact Windows `cloudflared.exe service install <token>` command, extracts the token without
     executing the pasted command, and rejects malformed or multiline input. Start revalidates
     manifest/hash/actual version/Valid
     Cloudflare signer/ACL/reparse/ObjectName, fixes warn + transport-warn logs, grace 30s and loopback
     metrics/diagnostics, and auto-stops on failure while preserving diagnostics.
   - Personal stack Start/Stop fails closed if Cloudflare public topology is active. The Windows
     PowerShell 5.1 parse/source/ordering contract passes; this is not runtime installation evidence.
   - `Test-PersonalMemoCloudflareExternal.ps1` and
     `compose.public-feed.cloudflare-test.yaml` separate disposable `127.0.0.1:8787` prepare, explicit
     external qualification, and cleanup gated by both local connector and remote replica stop proof.
     The recorded prepare smoke passed and cleaned up with synthetic project/port/receipt count zero;
     the personal stack remained healthy and unchanged.
   - The bounded external run wrote a strict non-secret receipt for 46 probes: exact positive 3,
     origin deny 8, remote catch-all deny 5, and rate attempts 30. Cache observations were
     `BYPASS 0`/`DYNAMIC 46`/`HIT 0`; maximum observed latency was `873.816 ms`; owned-log and
     external-artifact-reflection sentinels passed. Rate 429 was
     `NOT_OBSERVED_WITHIN_BOUNDED_ATTEMPTS`.
   - The current account plan did not expose provider/customer request-log sentinel evidence, so that
     boundary remains unavailable/unverified. The receipt replica field remains
     `REQUIRED_NOT_VERIFIED`; a separate dashboard observation of active replicas 0/routes 1/status
     Down after rollback and cleanup does not replace strict receipt proof. The resulting status is
     `TRANSPORT_PATH_CACHE_PASS_LOG_AND_REPLICA_REQUIRED`, decision `NO_GO`.
   - Cleanup removed disposable containers/network/local image and restored the service stopped/manual,
     cloudflared process 0 and port `8787`/`49312` listeners 0. The existing personal stack remained
     healthy. No personal memo/database/canonical/Apply/Ollama path was accessed. The exact-path cache
     bypass rule and protected tunnel installation remain intentionally persistent.
   - Harness correction: HEAD uses curl `size_download` rather than treating the output header block as
     a body; the impossible custom-method marker was replaced by a HEAD marker with a force-recreated
     per-run log boundary.
7. The query bearer necessarily enters Cloudflare's request-processing boundary. Owned origin logs
   being token-free does not mean Cloudflare never receives or processes it. Customer-configurable
   request logs therefore use a minimum field allow-list: timestamps, host, method, query-free
   `ClientRequestPath`, response status/bytes, cache status, and Ray ID as needed. They exclude
   `ClientRequestURI` because Cloudflare defines it as full path plus query, as well as raw
   request/response headers, cookies, referer, body, and fields that can reconstruct the query.
   `cloudflared` debug logging is prohibited because it may record request URLs and headers. Any
   provider-internal retention that cannot be configured or inspected remains an explicit privacy/
   contract risk, not a token-free claim.
8. The exact hostname/path has a Cloudflare Cache Rule set to **Bypass cache**. External synthetic
   proof requires `CF-Cache-Status != HIT`; `BYPASS` and `DYNAMIC` are both acceptable non-hit outcomes
   depending on rule evaluation. Cloudflare WAF rate limiting is defense in depth only: enforcement
   can lag and counters need not be one exact global quota. It does not replace the origin global
   bounds. Cloudflare Tunnel alone does not impose a total external 10-second deadline or an
   independent 128-KiB hard response cap. The 10-second origin read timeout and backend 128-KiB
   generation bound remain narrower, separate controls.
9. Cutover is deliberately staged:
   - **preflight:** render and verify the normal stack plus `compose.public-feed.yaml`; keep the
     activation overlay absent and confirm the application capability remains `LOCAL_ONLY` while the
     new edge is reachable only through host loopback;
   - **Cloudflare preparation:** keep the connector stopped while creating the remotely-managed named
     Tunnel, `calendar.<zone>` public hostname/DNS, loopback target, cache/WAF/log policies, and deny
     behavior;
   - **external synthetic qualification:** point the tunnel only at a disposable synthetic origin,
     start the connector temporarily, prove path/method/cache/header/log behavior with generated
     bearers, then stop the connector, prove there is no successful feed response through the tunnel
     (non-2xx; a Cloudflare edge error is acceptable), and remove the disposable origin/log artifacts;
   - **activation:** start and verify the live loopback edge while capability is still `LOCAL_ONLY`,
     place the reviewed origin in ignored `.env.public-feed`, and apply
     `compose.public-feed-activation.yaml` while the connector remains stopped. Start the connector
     **last**. The overlay changes only the server-owned capability to `PUBLIC_HTTPS`; it does not
     itself create DNS, TLS, a tunnel, a firewall rule, or an external listener. Live qualification
     uses only generated invalid/unknown tokens and deny probes, not a personal feed or schedule.
10. Rollback reverses authority before topology. Stop the Cloudflare connector first and prove there
    is no successful feed response through the tunnel (non-2xx; a Cloudflare edge error is acceptable),
    then recreate the backend without
    `compose.public-feed-activation.yaml` and verify the exact capability is `LOCAL_ONLY`. The loopback
    edge may then be stopped or removed, followed by tunnel/DNS cleanup. No Flyway or canonical data
    rollback is required because preflight/activation add no database migration or row.
11. Google Calendar and Apple Calendar subscription/update/removal smoke, cancellation-tombstone
    behavior, and interoperability claims remain separate post-activation approvals. Personal data is
    not an acceptable substitute for the isolated synthetic preflight.

## Consequences

- Source can verify the narrow proxy and log boundary without making an internet route available.
- Applying only `compose.public-feed.yaml` is fail-closed: the backend remains `LOCAL_ONLY` and the
  listener remains loopback-only.
- Applying the activation overlay before Cloudflare prerequisites and disposable-synthetic external
  qualification violates this decision even if the backend accepts the configured origin.
- The provisional origin-side numbers may be revised from measured public-edge evidence; changing
  them does not silently define external client quotas or a total request deadline.
- Cloudflare's documented `ClientRequestURI` and debug-log behavior make query-bearer exposure a
  provider-processing and contract boundary, not only a local configuration problem.
- The checkpoint remains `SOLO_PROVISIONAL`/`REPORT_ONLY`. Cloudflare is selected; the bounded
  token-bearing synthetic transport/path/cache path and owned-log sentinels were exercised, then the
  connector and disposable topology were rolled back. Current Cloudflare status is `Down` with no
  connector or port `8787`/`49312` listener. This does not prove token-free provider infrastructure or
  authorize a real feed/calendar-client smoke. Provider/customer log evidence remains unavailable/
  unverified and receipt-level replica proof remains required, so overall PASS and live activation are
  forbidden. Actual schedule publication remains `NO_GO` pending explicit privacy consent and the
  provider/customer log decision.
