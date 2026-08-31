package local.personalmemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class GoogleOAuthConfigurationTest {
  private static final String CREDENTIALS_ERROR =
      "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are required when Google auth is enabled.";
  private static final String REDIRECT_ERROR =
      "GOOGLE_REDIRECT_URI must be a public absolute HTTPS URI without userinfo or a fragment when Google auth is enabled in production.";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfiguration.class, GoogleOAuthConfiguration.class);

  @Test
  void productionNormalizesAndAcceptsAPublicAbsoluteHttpsRedirect() {
    productionRunner("https://memo.example.com/oauth/../login/oauth2/code/google")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              var registrations = context.getBean(ClientRegistrationRepository.class);
              assertThat(registrations.findByRegistrationId("google").getRedirectUri())
                  .isEqualTo("https://memo.example.com/login/oauth2/code/google");
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/login/oauth2/code/google",
        "http://memo.example.com/login/oauth2/code/google",
        "{baseUrl}/login/oauth2/code/{registrationId}",
        "https://user@memo.example.com/login/oauth2/code/google",
        "https://memo.example.com/login/oauth2/code/google#callback",
        "https://localhost/login/oauth2/code/google",
        "https://accounts.localhost/login/oauth2/code/google",
        "https://memo/login/oauth2/code/google",
        "https://service.internal/login/oauth2/code/google",
        "https://127.0.0.1/login/oauth2/code/google",
        "https://0177.0.0.1/login/oauth2/code/google",
        "https://10.0.0.1/login/oauth2/code/google",
        "https://169.254.10.20/login/oauth2/code/google",
        "https://192.168.1.2/login/oauth2/code/google",
        "https://[::1]/login/oauth2/code/google",
        "https://[fc00::1]/login/oauth2/code/google",
        "https://[fe80::1]/login/oauth2/code/google"
      })
  void productionRejectsUnsafeRedirects(String redirectUri) {
    productionRunner(redirectUri)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasRootCauseMessage(REDIRECT_ERROR));
  }

  @Test
  void productionRejectsMissingGoogleCredentials() {
    runner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "app.auth.google.enabled=true",
            "app.auth.google.client-secret=secret",
            "app.auth.google.redirect-uri=https://memo.example.com/login/oauth2/code/google")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasRootCauseMessage(CREDENTIALS_ERROR));
  }

  @Test
  void productionDoesNotRequireGoogleSecretsWhenGoogleLoginIsDisabled() {
    runner
        .withPropertyValues("spring.profiles.active=prod", "app.auth.google.enabled=false")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
            });
  }

  @Test
  void developmentKeepsTheFrameworkRedirectTemplateDefault() {
    runner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "app.auth.google.enabled=true",
            "app.auth.google.client-id=client-id",
            "app.auth.google.client-secret=client-secret",
            "app.auth.google.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              var registrations = context.getBean(ClientRegistrationRepository.class);
              assertThat(registrations.findByRegistrationId("google").getRedirectUri())
                  .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
            });
  }

  @Test
  void developmentAllowsAndNormalizesADocumentedHttpLoopbackRedirect() {
    runner
        .withPropertyValues(
            "spring.profiles.active=dev",
            "app.auth.google.enabled=true",
            "app.auth.google.client-id=client-id",
            "app.auth.google.client-secret=client-secret",
            "app.auth.google.redirect-uri= http://127.0.0.1:5173/oauth/../login/oauth2/code/google ")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              var registrations = context.getBean(ClientRegistrationRepository.class);
              assertThat(registrations.findByRegistrationId("google").getRedirectUri())
                  .isEqualTo("http://127.0.0.1:5173/login/oauth2/code/google");
            });
  }

  private ApplicationContextRunner productionRunner(String redirectUri) {
    return runner.withPropertyValues(
        "spring.profiles.active=prod",
        "app.auth.google.enabled=true",
        "app.auth.google.client-id=client-id",
        "app.auth.google.client-secret=client-secret",
        "app.auth.google.redirect-uri=" + redirectUri);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AuthProperties.class)
  static class PropertiesConfiguration {}
}
