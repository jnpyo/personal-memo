package local.personalmemo.analysis.infrastructure;

import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaLocalModelProperties.class)
public class AnalysisGatewayConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "app.analysis.local-model",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  @ConditionalOnMissingBean(CloudAnalysisGateway.class)
  FakeCloudAnalysisGateway fakeCloudAnalysisGateway() {
    return new FakeCloudAnalysisGateway();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.analysis.local-model",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(OllamaTransport.class)
  OllamaTransport ollamaTransport(OllamaLocalModelProperties properties) {
    return new JdkOllamaTransport(properties);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.analysis.local-model",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(CloudAnalysisGateway.class)
  OllamaLocalAnalysisGateway ollamaLocalAnalysisGateway(
      ObjectMapper json,
      OllamaLocalModelProperties properties,
      OllamaTransport transport,
      AnalysisProposalSchemaValidator proposalSchemaValidator,
      AnalysisProposalValidator proposalValidator) {
    return new OllamaLocalAnalysisGateway(
        json, properties, transport, proposalSchemaValidator, proposalValidator);
  }
}
