package local.personalmemo.common.auth;

import java.util.UUID;

/** Resolves the canonical owner exclusively from the authenticated server-side context. */
public interface CurrentIdentity {
  UUID ownerId();
}
