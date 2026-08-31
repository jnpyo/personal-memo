# ADR 0005: Server session authentication with local and Google identities

Status: Accepted — 2026-08-05

## Context

The first vertical slice used one seeded development owner while every domain row and query was already owner-scoped. Public deployment requires a real authenticated owner without moving authorization decisions into the browser or weakening the existing database ownership boundary.

The Android Chrome PWA and API are served from one origin in production. The application does not need to expose Google access tokens to the browser or call Google APIs after authentication. Redis, a separate identity service, and browser-managed refresh tokens would add operational and security complexity that the current product does not need.

## Decision

- Spring Security owns both local email/password authentication and Google OpenID Connect login.
- Successful authentication creates one opaque server session. Spring Session JDBC stores sessions in PostgreSQL; the browser receives only an HttpOnly session cookie, which is also Secure in deployed HTTPS environments.
- Browser tokens are never stored in `localStorage` or `sessionStorage`. The Google client secret remains a backend-only environment value.
- Unsafe browser requests require CSRF protection. The PWA obtains a CSRF token from the server and sends the server-declared header on mutations.
- Local passwords use Spring Security's delegating password encoder with an adaptive one-way hash. Raw passwords are never stored or logged.
- The stable application identity is `users.id`. Local credentials and external identities are separate records that point to that internal UUID.
- A Google identity is keyed by the pair `(provider, provider_subject)`, where `provider_subject` is Google's `sub` claim. Email is retained as a verified snapshot, not used as the external primary key.
- A new Google identity requires a provider-verified email. If that normalized email already belongs to an internal user, ordinary Google sign-in fails with an account-link-required conflict: it neither signs in as that user nor creates a duplicate. The user must authenticate the existing account and explicitly initiate Google linking. A short-lived link intent is bound to that session and the OAuth authorization `state` before a callback may attach the identity. Creating a brand-new internal user from a previously unseen Google subject and email is controlled independently and defaults disabled; existing Google login and explicit linking do not depend on that registration flag.
- An identity may be unlinked only when another usable login method remains.
- The existing seeded development user and its data are preserved by migration but are not silently claimed by the first person who signs in.
- Google login is capability-gated. Local authentication and application startup work without Google credentials; real Google endpoints are enabled only by server configuration. Production keeps automatic Google user creation fail-closed even when those login endpoints are enabled.
- Authentication determines the owner on the server. Clients never submit `ownerId`, and existing owner-aware SQL and foreign keys remain authoritative.

## Consequences

- The application becomes a multi-account service while retaining PostgreSQL as its only required data and session store.
- Same-origin deployment is the supported production shape. Cross-origin deployments would require an explicit cookie, CORS, and CSRF review.
- The service worker must not cache API responses, authentication endpoints, OAuth callbacks, or private memo data.
- Development, CI, and browser tests use local accounts and mocked OIDC authentication; they never depend on Google network availability.
- Provider credentials are removed from the authorized-client store after the identity claims are consumed. A failed normal Google login invalidates the transient OAuth session; a failed link restores the original internal principal instead of leaving an OIDC principal in the session.
- Same-account failures receive a bounded lock. Public email verification, password reset delivery, IP/edge rate limiting and abuse protection, MFA/passkeys, and account deletion automation remain separate follow-up slices.
