package local.personalmemo.common.auth;

import java.util.UUID;
import local.personalmemo.auth.domain.AppPrincipal;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class SecurityContextCurrentIdentity implements CurrentIdentity {
  @Override
  public UUID ownerId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
    }
    if (authentication.getPrincipal() instanceof AppPrincipal principal) {
      return principal.userId();
    }

    throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
  }
}
