# ADR 0006: Private PC deployment and initial account provisioning

- Status: Accepted
- Date: 2026-08-07

## Context

The current checkpoint is intended for one person's use on a Windows PC and a Samsung Galaxy S24
Ultra before any public launch. The production profile deliberately disables local registration and
new-user creation through Google, so a fresh PostgreSQL database otherwise has no safe first-account
path. The phone also needs a trusted HTTPS same-origin endpoint for Secure cookies and an installable
PWA, while PostgreSQL and Spring Boot must remain inaccessible from the LAN.

The same application and canonical database must be movable to a future server without changing the
domain model or introducing a second data authority.

## Decision

- Keep the existing production Compose topology as the base and add a narrow personal-PC overlay.
  Only the frontend Nginx HTTPS listener is published to the private LAN. Spring Boot and PostgreSQL
  remain Compose-network-only.
- Terminate TLS in the existing unprivileged frontend container for this personal-PC mode. Generate a
  private local root CA and a leaf certificate whose subject alternative names contain the selected
  LAN address and Windows host name. Mount only the leaf certificate and key into the container; keep
  the root private key outside the repository and outside every container.
- Keep local and Google registration disabled. Provision the first local account through a dedicated,
  non-web, interactive command. A PostgreSQL singleton row is locked in the provisioning transaction,
  making the command one-time even after restart, backup, or migration to another host.
- Never accept the bootstrap password through an environment variable, command-line argument, file,
  HTTP endpoint, browser, model, or Agent tool. Read it twice from an attached console without echo and
  clear the input arrays after use.
- Keep personal environment values and certificates outside Git. Store logical PostgreSQL backups
  under the current Windows user's Documents folder with a checksum and validate each dump before it
  is finalized.
- Move to a future server by restoring a validated logical PostgreSQL backup into the same Compose
  application, applying forward-only Flyway migrations, clearing restored sessions, and replacing the
  personal TLS overlay with the server's trusted HTTPS edge. Do not copy a live Docker volume.

## Consequences

- The user must install the generated root CA certificate on the S24 Ultra once and must keep the PC's
  LAN address stable. A changed address requires a newly issued leaf certificate and an updated URL.
- The PC must be powered on and reachable through the same private network. Router port forwarding and
  public exposure are explicitly unsupported at this checkpoint.
- Possession of the local root private key permits issuing certificates trusted by the phone. The key
  therefore needs user-only filesystem access, must never be copied to the phone, and must not be part
  of ordinary backups shared elsewhere.
- Losing the only local password has no self-service recovery path yet. Password reset, email delivery,
  MFA/passkeys, rate limiting, monitoring, and a public certificate/domain remain requirements before
  broader availability.
- Google authentication remains an optional login method for a later public-HTTPS production deployment;
  the private-LAN overlay deliberately forces it off and ignores empty provider settings. Email equality
  never links it to the local account; the signed-in user must explicitly link the Google identity after
  the deployment has moved away from the private overlay and an administrator enables the capability.
