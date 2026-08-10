package local.personalmemo.analysis.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(
    prefix = "app.analysis.dispatch-recovery",
    name = "enabled",
    havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(AnalysisDispatchRecoveryProperties.class)
public class AnalysisDispatchRecoveryConfiguration {
  @Bean
  AnalysisDispatchRecoveryWorker analysisDispatchRecoveryWorker(
      AnalysisService analysisService, AnalysisDispatchRecoveryProperties properties) {
    return new AnalysisDispatchRecoveryWorker(analysisService, properties);
  }
}
