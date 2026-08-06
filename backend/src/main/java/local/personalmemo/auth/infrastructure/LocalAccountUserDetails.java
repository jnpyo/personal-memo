package local.personalmemo.auth.infrastructure;

import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import local.personalmemo.auth.domain.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LocalAccountUserDetails implements UserDetails {
  @Serial private static final long serialVersionUID = 1L;

  private final UserAccount account;
  private final String passwordHash;
  private final Instant lockedUntil;

  LocalAccountUserDetails(UserAccount account, String passwordHash, Instant lockedUntil) {
    this.account = account;
    this.passwordHash = passwordHash;
    this.lockedUntil = lockedUntil;
  }

  public UserAccount account() {
    return account;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return account.normalizedEmail();
  }

  @Override
  public boolean isAccountNonLocked() {
    return lockedUntil == null || !lockedUntil.isAfter(Instant.now());
  }

  @Override
  public boolean isEnabled() {
    return "ACTIVE".equals(account.status());
  }
}
