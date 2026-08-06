package local.personalmemo.auth.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.auth.config.AuthProperties;
import local.personalmemo.auth.domain.AppPrincipal;
import local.personalmemo.auth.domain.GoogleProfile;
import local.personalmemo.auth.domain.LoginMethod;
import local.personalmemo.auth.domain.UserAccount;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.auth.infrastructure.LocalAccountUserDetails;
import local.personalmemo.common.error.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final int MAX_EMAIL_LENGTH = 254;
  private static final int MAX_DISPLAY_NAME_LENGTH = 80;
  private static final int MAX_GOOGLE_SUBJECT_LENGTH = 255;
  private static final int LOCAL_LOGIN_FAILURE_LIMIT = 5;
  private static final Duration LOCAL_LOGIN_LOCK_DURATION = Duration.ofMinutes(15);

  private final AuthRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final AuthProperties properties;
  private final Clock clock;

  @Autowired
  public AuthService(
      AuthRepository repository,
      PasswordEncoder passwordEncoder,
      AuthenticationManager authenticationManager,
      AuthProperties properties) {
    this(repository, passwordEncoder, authenticationManager, properties, Clock.systemUTC());
  }

  AuthService(
      AuthRepository repository,
      PasswordEncoder passwordEncoder,
      AuthenticationManager authenticationManager,
      AuthProperties properties,
      Clock clock) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.authenticationManager = authenticationManager;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public AppPrincipal register(String email, String password, String displayName, String timeZone) {
    if (!properties.registrationEnabled()) {
      throw DomainException.forbidden("REGISTRATION_DISABLED", "Registration is disabled.");
    }
    String normalizedEmail = normalizeEmail(email);
    String cleanDisplayName = requireBounded(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
    validatePassword(password);
    validateTimeZone(timeZone);
    if (repository.findUserByNormalizedEmail(normalizedEmail).isPresent()) {
      throw emailAlreadyRegistered();
    }

    try {
      return repository
          .createLocalUser(
              UUID.randomUUID(),
              email.trim(),
              normalizedEmail,
              cleanDisplayName,
              passwordEncoder.encode(password),
              timeZone,
              clock.instant())
          .toPrincipal();
    } catch (DataIntegrityViolationException exception) {
      throw emailAlreadyRegistered();
    }
  }

  public AppPrincipal login(String email, String password) {
    String normalized = normalizeEmail(email);
    if (!isBcryptSafePassword(password)) {
      throw invalidCredentials();
    }
    try {
      var authenticated =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
      if (!(authenticated.getPrincipal() instanceof LocalAccountUserDetails details)) {
        throw invalidCredentials();
      }
      repository.clearLocalLoginFailures(details.account().id());
      return details.account().toPrincipal();
    } catch (BadCredentialsException exception) {
      repository.recordLocalLoginFailure(
          normalized, clock.instant(), LOCAL_LOGIN_FAILURE_LIMIT, LOCAL_LOGIN_LOCK_DURATION);
      throw invalidCredentials();
    } catch (AccountStatusException exception) {
      // Disabled and currently locked accounts deliberately use the same public response without
      // extending the lock on every attempt.
      throw invalidCredentials();
    } catch (AuthenticationException exception) {
      // Infrastructure/provider failures must remain server errors instead of being disguised as
      // user mistakes.
      throw exception;
    }
  }

  @Transactional
  public AppPrincipal googleLogin(GoogleProfile suppliedProfile) {
    GoogleProfile profile = validateGoogleProfile(suppliedProfile);
    Instant now = clock.instant();
    String normalizedEmail = normalizeEmail(profile.email());
    repository.lockAuthenticationScopes(
        "GOOGLE_SUBJECT:" + profile.subject(), "EMAIL:" + normalizedEmail);
    var existing = repository.findUserByGoogleSubject(profile.subject());
    if (existing.isPresent()) {
      ensureActive(existing.get());
      if (!repository.touchGoogleLogin(profile.subject(), profile.email(), now)) {
        throw googleConflict();
      }
      return existing.get().toPrincipal();
    }

    if (repository.findUserByNormalizedEmail(normalizedEmail).isPresent()) {
      throw DomainException.conflict(
          "ACCOUNT_LINK_REQUIRED",
          "Sign in with an existing method, then explicitly link this Google account.");
    }
    if (!properties.google().registrationEnabled()) {
      throw DomainException.forbidden(
          "GOOGLE_REGISTRATION_DISABLED", "Creating a new account with Google is disabled.");
    }

    try {
      return repository
          .createGoogleUser(
              UUID.randomUUID(),
              UUID.randomUUID(),
              profile.email().trim(),
              normalizedEmail,
              profile.displayName(),
              profile.subject(),
              now)
          .toPrincipal();
    } catch (DataIntegrityViolationException exception) {
      throw DomainException.conflict(
          "ACCOUNT_LINK_REQUIRED",
          "Sign in with an existing method, then explicitly link this Google account.");
    }
  }

  @Transactional
  public AppPrincipal linkGoogle(UUID currentUserId, GoogleProfile suppliedProfile) {
    GoogleProfile profile = validateGoogleProfile(suppliedProfile);
    repository.lockAuthenticationScopes(
        "GOOGLE_SUBJECT:" + profile.subject(), "USER:" + currentUserId);
    UserAccount current =
        repository.findUser(currentUserId).orElseThrow(() -> DomainException.notFound("User"));
    ensureActive(current);

    var subjectOwner = repository.findUserByGoogleSubject(profile.subject());
    if (subjectOwner.isPresent() && !subjectOwner.get().id().equals(currentUserId)) {
      throw googleConflict();
    }
    var currentSubject = repository.findGoogleSubject(currentUserId);
    if (currentSubject.isPresent() && !currentSubject.get().equals(profile.subject())) {
      throw googleConflict();
    }
    if (currentSubject.isEmpty()) {
      try {
        repository.linkGoogle(
            UUID.randomUUID(),
            currentUserId,
            profile.subject(),
            profile.email().trim(),
            true,
            clock.instant());
      } catch (DataIntegrityViolationException exception) {
        throw googleConflict();
      }
    } else {
      if (!repository.touchGoogleLogin(
          profile.subject(), profile.email().trim(), clock.instant())) {
        throw googleConflict();
      }
    }
    return current.toPrincipal();
  }

  @Transactional
  public void unlinkGoogle(UUID currentUserId) {
    requireUser(currentUserId);
    while (true) {
      var subjectBeforeLock = repository.findGoogleSubject(currentUserId);
      if (subjectBeforeLock.isEmpty()) {
        return;
      }

      String subject = subjectBeforeLock.get();
      repository.lockAuthenticationScopes("GOOGLE_SUBJECT:" + subject, "USER:" + currentUserId);
      requireUser(currentUserId);

      var subjectAfterLock = repository.findGoogleSubject(currentUserId);
      if (subjectAfterLock.isEmpty()) {
        return;
      }
      if (!subject.equals(subjectAfterLock.get())) {
        continue;
      }
      if (!repository.hasLocalCredential(currentUserId)) {
        throw DomainException.conflict(
            "LOGIN_METHOD_REQUIRED", "Add another login method before unlinking Google.");
      }
      if (repository.unlinkGoogle(currentUserId, subject)) {
        return;
      }
    }
  }

  public UserAccount requireUser(UUID userId) {
    UserAccount user =
        repository.findUser(userId).orElseThrow(() -> DomainException.notFound("User"));
    ensureActive(user);
    return user;
  }

  public Set<LoginMethod> loginMethods(UUID userId) {
    return repository.loginMethods(userId);
  }

  private GoogleProfile validateGoogleProfile(GoogleProfile profile) {
    if (profile == null || !profile.emailVerified()) {
      throw DomainException.forbidden(
          "GOOGLE_EMAIL_NOT_VERIFIED", "Google must provide a verified email address.");
    }
    return new GoogleProfile(
        requireBounded(profile.subject(), "subject", MAX_GOOGLE_SUBJECT_LENGTH),
        requireBounded(profile.email(), "email", MAX_EMAIL_LENGTH),
        true,
        requireBounded(profile.displayName(), "displayName", MAX_DISPLAY_NAME_LENGTH));
  }

  private String normalizeEmail(String email) {
    return requireBounded(email, "email", MAX_EMAIL_LENGTH).toLowerCase(Locale.ROOT);
  }

  private String requireBounded(String value, String field, int maxLength) {
    if (value == null) {
      throw DomainException.invalid("VALIDATION_FAILED", field + " is required.");
    }
    String trimmed = value.trim();
    int codePoints = trimmed.codePointCount(0, trimmed.length());
    if (trimmed.isEmpty() || codePoints > maxLength) {
      throw DomainException.invalid("VALIDATION_FAILED", field + " has an invalid length.");
    }
    return trimmed;
  }

  private void validatePassword(String password) {
    if (password == null
        || password.codePointCount(0, password.length()) < 12
        || !isBcryptSafePassword(password)) {
      throw DomainException.invalid(
          "VALIDATION_FAILED", "password must be 12 to 128 characters and at most 72 UTF-8 bytes.");
    }
  }

  private boolean isBcryptSafePassword(String password) {
    return password != null
        && password.codePointCount(0, password.length()) <= 128
        && password.getBytes(StandardCharsets.UTF_8).length <= 72;
  }

  private void validateTimeZone(String timeZone) {
    if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw DomainException.invalid("VALIDATION_FAILED", "timeZone must be an IANA time zone.");
    }
  }

  private void ensureActive(UserAccount account) {
    if (!"ACTIVE".equals(account.status())) {
      throw invalidCredentials();
    }
  }

  private DomainException invalidCredentials() {
    return DomainException.unauthorized("INVALID_CREDENTIALS", "Email or password is invalid.");
  }

  private DomainException emailAlreadyRegistered() {
    return DomainException.conflict(
        "EMAIL_ALREADY_REGISTERED", "An account already exists for this email address.");
  }

  private DomainException googleConflict() {
    return DomainException.conflict(
        "GOOGLE_IDENTITY_CONFLICT", "This Google identity is already linked.");
  }
}
