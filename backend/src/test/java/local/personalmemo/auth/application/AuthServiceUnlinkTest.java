package local.personalmemo.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.auth.config.AuthProperties;
import local.personalmemo.auth.domain.GoogleProfile;
import local.personalmemo.auth.domain.UserAccount;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

class AuthServiceUnlinkTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void unlinkLocksGoogleSubjectAndUserThenDeletesOnlyTheRecheckedSubject() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthService service = service(repository);
    UUID userId = UUID.randomUUID();
    String subject = "linked-subject";
    when(repository.findUser(userId)).thenReturn(Optional.of(activeUser(userId)));
    when(repository.findGoogleSubject(userId)).thenReturn(Optional.of(subject));
    when(repository.hasLocalCredential(userId)).thenReturn(true);
    when(repository.unlinkGoogle(userId, subject)).thenReturn(true);

    service.unlinkGoogle(userId);

    verify(repository).lockAuthenticationScopes("GOOGLE_SUBJECT:" + subject, "USER:" + userId);
    verify(repository).unlinkGoogle(userId, subject);
  }

  @Test
  void unlinkRetriesWithTheCurrentSubjectWhenItChangesBeforeTheLock() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthService service = service(repository);
    UUID userId = UUID.randomUUID();
    when(repository.findUser(userId)).thenReturn(Optional.of(activeUser(userId)));
    when(repository.findGoogleSubject(userId))
        .thenReturn(
            Optional.of("old-subject"),
            Optional.of("new-subject"),
            Optional.of("new-subject"),
            Optional.of("new-subject"));
    when(repository.hasLocalCredential(userId)).thenReturn(true);
    when(repository.unlinkGoogle(userId, "new-subject")).thenReturn(true);

    service.unlinkGoogle(userId);

    verify(repository).lockAuthenticationScopes("GOOGLE_SUBJECT:old-subject", "USER:" + userId);
    verify(repository).lockAuthenticationScopes("GOOGLE_SUBJECT:new-subject", "USER:" + userId);
    verify(repository, never()).unlinkGoogle(userId, "old-subject");
    verify(repository).unlinkGoogle(userId, "new-subject");
  }

  @Test
  void disabledOwnerCannotUnlinkEvenWhenAProviderIdentityExists() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthService service = service(repository);
    UUID userId = UUID.randomUUID();
    when(repository.findUser(userId)).thenReturn(Optional.of(disabledUser(userId)));

    assertThatThrownBy(() -> service.unlinkGoogle(userId))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("INVALID_CREDENTIALS"));

    verify(repository, never()).lockAuthenticationScopes(org.mockito.ArgumentMatchers.any());
    verify(repository, never())
        .unlinkGoogle(
            org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void googleLoginFailsSafelyWhenTheLockedIdentityTouchUpdatesNoRow() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthService service = service(repository);
    UUID userId = UUID.randomUUID();
    UserAccount account =
        new UserAccount(
            userId, "google@example.com", "google@example.com", "Google Owner", "ACTIVE");
    when(repository.findUserByGoogleSubject("stable-sub")).thenReturn(Optional.of(account));
    when(repository.touchGoogleLogin(
            "stable-sub", "google@example.com", Instant.parse("2026-08-05T00:00:00Z")))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                service.googleLogin(
                    new GoogleProfile("stable-sub", "google@example.com", true, "Google Owner")))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("GOOGLE_IDENTITY_CONFLICT"));

    verify(repository)
        .lockAuthenticationScopes("GOOGLE_SUBJECT:stable-sub", "EMAIL:google@example.com");
  }

  @Test
  void disabledGoogleRegistrationRejectsOnlyNewInternalUserCreation() {
    AuthRepository repository = mock(AuthRepository.class);
    AuthService service = service(repository);
    GoogleProfile newIdentity =
        new GoogleProfile("new-subject", "new-google@example.com", true, "New Google User");
    when(repository.findUserByGoogleSubject("new-subject")).thenReturn(Optional.empty());
    when(repository.findUserByNormalizedEmail("new-google@example.com"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.googleLogin(newIdentity))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("GOOGLE_REGISTRATION_DISABLED"));

    verify(repository)
        .lockAuthenticationScopes("GOOGLE_SUBJECT:new-subject", "EMAIL:new-google@example.com");
  }

  private AuthService service(AuthRepository repository) {
    return new AuthService(
        repository,
        mock(PasswordEncoder.class),
        mock(AuthenticationManager.class),
        new AuthProperties(false, new AuthProperties.Google(false, false, "", "", "")),
        CLOCK,
        mock(PlatformTransactionManager.class));
  }

  private UserAccount activeUser(UUID userId) {
    return new UserAccount(userId, "owner@example.com", "owner@example.com", "Owner", "ACTIVE");
  }

  private UserAccount disabledUser(UUID userId) {
    return new UserAccount(userId, "owner@example.com", "owner@example.com", "Owner", "DISABLED");
  }
}
