package local.personalmemo.auth.domain;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AppPrincipal(UUID userId, String email, String displayName)
    implements Principal, Serializable {
  @Serial private static final long serialVersionUID = 1L;

  public AppPrincipal {
    if (userId == null
        || email == null
        || email.isBlank()
        || displayName == null
        || displayName.isBlank()) {
      throw new IllegalArgumentException("Authenticated user fields are required.");
    }
  }

  @Override
  public String getName() {
    return userId.toString();
  }
}
