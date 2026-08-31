package local.personalmemo.auth.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

public record UserAccount(
    UUID id, String email, String normalizedEmail, String displayName, String status)
    implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  public AppPrincipal toPrincipal() {
    return new AppPrincipal(id, email, displayName);
  }
}
