package local.personalmemo.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionAuthConfiguration {
  @Bean
  ProductionRegistrationPolicy productionRegistrationPolicy(AuthProperties properties) {
    if (properties.registrationEnabled()) {
      throw new IllegalStateException("AUTH_REGISTRATION_ENABLED must remain false in production.");
    }
    if (properties.google().registrationEnabled()) {
      throw new IllegalStateException(
          "GOOGLE_REGISTRATION_ENABLED must remain false in production.");
    }
    return new ProductionRegistrationPolicy();
  }

  static final class ProductionRegistrationPolicy {}
}
