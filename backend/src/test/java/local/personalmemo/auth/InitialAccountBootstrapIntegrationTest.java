package local.personalmemo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import local.personalmemo.auth.application.AuthService;
import local.personalmemo.auth.domain.AppPrincipal;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@PostgresIntegration
@TestPropertySource(
    properties = {
      "app.auth.registration-enabled=false",
      "app.auth.google.registration-enabled=false"
    })
class InitialAccountBootstrapIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired AuthService auth;
  @MockitoSpyBean AuthRepository authRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @BeforeEach
  void resetProvisioningGate() {
    db.sql(
            "update initial_account_provisioning "
                + "set status='AVAILABLE',provisioned_user_id=null,method=null,consumed_at=null "
                + "where singleton=true")
        .update();
  }

  @Test
  void provisionsOneSeparateInternalOwnerAndPermanentlyConsumesTheGate() {
    String rawPassword = "correct horse battery";
    char[] password = rawPassword.toCharArray();

    AppPrincipal principal =
        auth.bootstrapInitialLocalAccount(
            "owner@example.invalid", password, "Example Owner", "Asia/Seoul");

    assertThat(password).containsOnly('\0');
    assertThat(principal.userId()).isNotEqualTo(OWNER_ID);
    assertThat(
            db.sql(
                    "select count(*) from users where id=:id and status='ACTIVE' "
                        + "and primary_email_normalized='owner@example.invalid' "
                        + "and display_name='Example Owner'")
                .param("id", principal.userId())
                .query(Long.class)
                .single())
        .isOne();
    assertThat(
            db.sql("select count(*) from users where id=:legacy and status='LEGACY_UNCLAIMED'")
                .param("legacy", OWNER_ID)
                .query(Long.class)
                .single())
        .isOne();
    assertThat(
            db.sql("select time_zone from user_settings where user_id=:id")
                .param("id", principal.userId())
                .query(String.class)
                .single())
        .isEqualTo("Asia/Seoul");

    String storedHash =
        db.sql("select password_hash from local_credentials where user_id=:id")
            .param("id", principal.userId())
            .query(String.class)
            .single();
    assertThat(storedHash).startsWith("{bcrypt}").isNotEqualTo(rawPassword);
    assertThat(passwordEncoder.matches(rawPassword, storedHash)).isTrue();
    assertThat(
            db.sql(
                    "select status || ':' || method from initial_account_provisioning "
                        + "where singleton=true and provisioned_user_id=:id and consumed_at is not null")
                .param("id", principal.userId())
                .query(String.class)
                .single())
        .isEqualTo("CONSUMED:INTERACTIVE_CLI");

    char[] retryPassword = "another secure password".toCharArray();
    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "second@example.invalid", retryPassword, "Second Owner", "Asia/Seoul"))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("INITIAL_ACCOUNT_ALREADY_PROVISIONED"));
    assertThat(retryPassword).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isOne();
  }

  @Test
  void concurrentBootstrapAttemptsSerializeAtTheDatabaseGate() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> attempts =
          List.of(
              executor.submit(
                  () -> attemptBootstrap("first@example.invalid", "First Owner", ready, start)),
              executor.submit(
                  () -> attemptBootstrap("second@example.invalid", "Second Owner", ready, start)));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Object> results = List.of(attempts.get(0).get(), attempts.get(1).get());
      assertThat(results).filteredOn(AppPrincipal.class::isInstance).hasSize(1);
      assertThat(results)
          .filteredOn(DomainException.class::isInstance)
          .singleElement()
          .satisfies(
              result ->
                  assertThat(((DomainException) result).code())
                      .isEqualTo("INITIAL_ACCOUNT_ALREADY_PROVISIONED"));
      assertThat(
              db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
          .isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void claimedUserPermanentlyConsumesAnAvailableGateEvenAfterAccountDeletion() {
    UUID existingUser = UUID.randomUUID();
    authRepository.createLocalUser(
        existingUser,
        "existing@example.invalid",
        "existing@example.invalid",
        "Existing Owner",
        passwordEncoder.encode("existing secure password"),
        "Asia/Seoul",
        Instant.parse("2026-08-07T00:00:00Z"));
    char[] attemptedPassword = "another secure password".toCharArray();

    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "new@example.invalid", attemptedPassword, "New Owner", "Asia/Seoul"))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("INITIAL_ACCOUNT_BOOTSTRAP_BLOCKED"));

    assertThat(attemptedPassword).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isOne();
    assertThat(
            db.sql("select status from initial_account_provisioning where singleton=true")
                .query(String.class)
                .single())
        .isEqualTo("CONSUMED");
    assertThat(
            db.sql(
                    "select provisioned_user_id from initial_account_provisioning where singleton=true")
                .query(UUID.class)
                .single())
        .isEqualTo(existingUser);
    assertThat(
            db.sql("select method from initial_account_provisioning where singleton=true")
                .query(String.class)
                .single())
        .isEqualTo("PREEXISTING");
    Timestamp consumedAt =
        db.sql("select consumed_at from initial_account_provisioning where singleton=true")
            .query(Timestamp.class)
            .single();
    assertThat(consumedAt).isNotNull();

    db.sql("delete from user_settings where user_id=:userId")
        .param("userId", existingUser)
        .update();
    db.sql("delete from users where id=:userId").param("userId", existingUser).update();

    char[] retryPassword = "retry secure password".toCharArray();
    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "retry@example.invalid", retryPassword, "Retry Owner", "Asia/Seoul"))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception ->
                assertThat(exception.code()).isEqualTo("INITIAL_ACCOUNT_ALREADY_PROVISIONED"));

    assertThat(retryPassword).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql(
                    "select count(*) from initial_account_provisioning "
                        + "where singleton=true and status='CONSUMED' "
                        + "and provisioned_user_id=:userId and method='PREEXISTING' "
                        + "and consumed_at=:consumedAt")
                .param("userId", existingUser)
                .param("consumedAt", consumedAt)
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void validationFailureRollsBackWithoutConsumingTheGate() {
    char[] tooShort = "short".toCharArray();

    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "invalid@example.invalid", tooShort, "Invalid Owner", "Asia/Seoul"))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("VALIDATION_FAILED"));

    assertThat(tooShort).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql("select status from initial_account_provisioning where singleton=true")
                .query(String.class)
                .single())
        .isEqualTo("AVAILABLE");
  }

  @Test
  void unexpectedFailureAfterUserCreationRollsBackAndLeavesTheGateAvailable() {
    doReturn(false)
        .when(authRepository)
        .consumeInitialAccountProvisioningForCreatedUser(any(UUID.class), any(Instant.class));
    char[] password = "correct horse battery".toCharArray();

    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "failure@example.invalid", password, "Failure Owner", "Asia/Seoul"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Initial-account provisioning state changed unexpectedly.");

    assertThat(password).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql("select status from initial_account_provisioning where singleton=true")
                .query(String.class)
                .single())
        .isEqualTo("AVAILABLE");
  }

  @Test
  void rejectsAnInvalidEmailBeforeCreatingOrConsumingAnything() {
    char[] password = "correct horse battery".toCharArray();

    assertThatThrownBy(
            () ->
                auth.bootstrapInitialLocalAccount(
                    "not-an-email", password, "Invalid Owner", "Asia/Seoul"))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo("VALIDATION_FAILED"));

    assertThat(password).containsOnly('\0');
    assertThat(
            db.sql("select count(*) from users where status='ACTIVE'").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql("select status from initial_account_provisioning where singleton=true")
                .query(String.class)
                .single())
        .isEqualTo("AVAILABLE");
  }

  private Object attemptBootstrap(
      String email, String displayName, CountDownLatch ready, CountDownLatch start) {
    char[] password = "correct horse battery".toCharArray();
    try {
      ready.countDown();
      start.await();
      return auth.bootstrapInitialLocalAccount(email, password, displayName, "Asia/Seoul");
    } catch (DomainException exception) {
      return exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent bootstrap test was interrupted.", exception);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
