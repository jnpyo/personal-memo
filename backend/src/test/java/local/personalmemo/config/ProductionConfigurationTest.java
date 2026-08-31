package local.personalmemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTest {
  @Test
  void productionProfileRequiresExternalDatabaseSecretsAndAppliesSecureDefaults()
      throws IOException {
    PropertySource<?> production = load("production", "application-prod.yml");
    PropertySource<?> defaults = load("defaults", "application.yml");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    environment.getPropertySources().addLast(production);
    environment.getPropertySources().addLast(defaults);

    assertThat(environment.matchesProfiles("prod")).isTrue();
    assertThat(production.getProperty("spring.datasource.url"))
        .isEqualTo("${SPRING_DATASOURCE_URL}");
    assertThat(production.getProperty("spring.datasource.username"))
        .isEqualTo("${SPRING_DATASOURCE_USERNAME}");
    assertThat(production.getProperty("spring.datasource.password"))
        .isEqualTo("${SPRING_DATASOURCE_PASSWORD}");
    assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
    assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))
        .isTrue();
    assertThat(environment.getProperty("app.auth.registration-enabled", Boolean.class)).isFalse();
    assertThat(environment.getProperty("app.auth.google.registration-enabled", Boolean.class))
        .isFalse();
    assertThat(environment.getProperty("app.analysis.dispatch-recovery.enabled", Boolean.class))
        .isTrue();
    assertThat(environment.getProperty("app.analysis.dispatch-recovery.batch-size", Integer.class))
        .isEqualTo(25);
    assertThat(environment.getProperty("app.analysis.dispatch-recovery.fixed-delay"))
        .isEqualTo("30s");
  }

  @Test
  void developmentProfileRetainsLocalRegistrationAndNonSecureCookieDefaults() throws IOException {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    environment.getPropertySources().addLast(load("defaults", "application.yml"));

    assertThat(environment.matchesProfiles("prod")).isFalse();
    assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))
        .isFalse();
    assertThat(environment.getProperty("app.auth.registration-enabled", Boolean.class)).isTrue();
    assertThat(environment.getProperty("app.auth.google.registration-enabled", Boolean.class))
        .isFalse();
    assertThat(environment.getProperty("app.analysis.dispatch-recovery.enabled")).isNull();
  }

  private PropertySource<?> load(String name, String resource) throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(name, new ClassPathResource(resource));
    assertThat(sources).hasSize(1);
    return sources.getFirst();
  }
}
