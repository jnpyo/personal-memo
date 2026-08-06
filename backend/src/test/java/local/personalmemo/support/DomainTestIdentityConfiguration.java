package local.personalmemo.support;

import java.util.UUID;
import local.personalmemo.common.auth.CurrentIdentity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class DomainTestIdentityConfiguration {
  @Bean
  @Primary
  CurrentIdentity fixedDomainTestIdentity() {
    return () -> UUID.fromString("00000000-0000-0000-0000-000000000001");
  }
}
