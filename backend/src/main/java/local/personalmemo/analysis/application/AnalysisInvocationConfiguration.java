package local.personalmemo.analysis.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisInvocationProperties.class)
public class AnalysisInvocationConfiguration {
  @Bean
  AnalysisInvocationPolicy analysisInvocationPolicy(AnalysisInvocationProperties properties) {
    return new AnalysisInvocationPolicy(properties);
  }
}
