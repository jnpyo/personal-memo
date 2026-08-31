package local.personalmemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ProductionAuthConfigurationTest {
  private static final String REGISTRATION_ERROR =
      "AUTH_REGISTRATION_ENABLED must remain false in production.";
  private static final String GOOGLE_REGISTRATION_ERROR =
      "GOOGLE_REGISTRATION_ENABLED must remain false in production.";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfiguration.class, ProductionAuthConfiguration.class);

  @Test
  void productionFailsFastIfAnEnvironmentOverrideEnablesRegistration() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "app.auth.registration-enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasRootCauseMessage(REGISTRATION_ERROR));
  }

  @Test
  void productionStartsWhenRegistrationRemainsDisabled() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "app.auth.registration-enabled=false")
        .run(context -> assertThat(context.getStartupFailure()).isNull());
  }

  @Test
  void productionFailsFastIfGoogleUserCreationIsEnabled() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "app.auth.registration-enabled=false",
            "app.auth.google.registration-enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasRootCauseMessage(GOOGLE_REGISTRATION_ERROR));
  }

  @Test
  void developmentMayKeepRegistrationEnabled() {
    runner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "app.auth.registration-enabled=true",
            "app.auth.google.registration-enabled=true")
        .run(context -> assertThat(context.getStartupFailure()).isNull());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class PropertiesConfiguration {}
}
