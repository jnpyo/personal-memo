package local.personalmemo.auth.infrastructure;

import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class LocalAccountUserDetailsService implements UserDetailsService {
  private final AuthRepository repository;

  public LocalAccountUserDetailsService(AuthRepository repository) {
    this.repository = repository;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    AuthRepository.LocalCredential credential =
        repository
            .findLocalCredential(normalized)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    return new LocalAccountUserDetails(
        credential.user(), credential.passwordHash(), credential.lockedUntil());
  }
}
