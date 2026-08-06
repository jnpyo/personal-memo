package local.personalmemo.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(boolean registrationEnabled, Google google) {
  public AuthProperties {
    if (google == null) {
      google = new Google(false, false, "", "", "{baseUrl}/login/oauth2/code/{registrationId}");
    }
  }

  public record Google(
      boolean enabled,
      boolean registrationEnabled,
      String clientId,
      String clientSecret,
      String redirectUri) {}
}
