package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class AnalysisGatewayConfigurationTest {
  private static final String MODEL = "hf.co/LiquidAI/LFM2.5:Q8_0";
  private static final String DIGEST = "a".repeat(64);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AnalysisGatewayConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(
              AnalysisProposalSchemaValidator.class,
              Draft202012AnalysisProposalSchemaValidator::new)
          .withBean(AnalysisProposalValidator.class, AnalysisProposalValidator::new)
          .withBean(OllamaTransport.class, NoCallTransport::new);

  @Test
  void defaultsToExactlyOneFakeGateway() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          assertThat(context.getBeansOfType(CloudAnalysisGateway.class)).hasSize(1);
          assertThat(context.getBean(CloudAnalysisGateway.class))
              .isInstanceOf(FakeCloudAnalysisGateway.class);
        });
  }

  @Test
  void selectsExactlyOneMachineLocalGatewayOnlyWhenExplicitlyEnabled() {
    enabledRunner("http://127.0.0.1:11434")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeansOfType(CloudAnalysisGateway.class)).hasSize(1);
              CloudAnalysisGateway gateway = context.getBean(CloudAnalysisGateway.class);
              assertThat(gateway).isInstanceOf(OllamaLocalAnalysisGateway.class);
              assertThat(gateway.bind().descriptor().transferMode())
                  .isEqualTo(CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT);
              assertThat(gateway.bind().descriptor().providerId())
                  .isEqualTo(OllamaLocalModelProperties.DESCRIPTOR_PROVIDER_PREFIX + MODEL);
              assertThat(gateway.bind().descriptor().modelVersion()).isEqualTo(DIGEST);
            });
  }

  @Test
  void acceptsOnlyAnExplicitlyAllowlistedDockerHostRelay() {
    enabledRunner("http://host.docker.internal:11435")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());

    enabledRunner("http://host.docker.internal:11435")
        .withPropertyValues(
            "app.analysis.local-model.allowed-docker-host-relay-origins[0]="
                + "http://host.docker.internal:11435")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBean(CloudAnalysisGateway.class))
                  .isInstanceOf(OllamaLocalAnalysisGateway.class);
            });
  }

  @Test
  void rejectsAliasesRemoteOriginsAndInvalidEnabledIdentity() {
    enabledRunner("http://localhost:11434")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    enabledRunner("https://127.0.0.1:11434")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    runner
        .withPropertyValues(
            "app.analysis.local-model.enabled=true",
            "app.analysis.local-model.endpoint=http://127.0.0.1:11434",
            "app.analysis.local-model.model=" + MODEL,
            "app.analysis.local-model.model-digest=not-a-digest")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    enabledRunner("http://127.0.0.1:11434")
        .withPropertyValues(
            "app.analysis.local-model.model="
                + "m".repeat(OllamaLocalModelProperties.MAX_MODEL_TAG_LENGTH + 1))
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  @Test
  void rejectsUnboundedExecutionSettings() {
    enabledRunner("http://127.0.0.1:11434")
        .withPropertyValues(
            "app.analysis.local-model.connect-timeout=11s",
            "app.analysis.local-model.max-response-bytes=1024",
            "app.analysis.local-model.max-model-output-bytes=2048")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull());
  }

  private ApplicationContextRunner enabledRunner(String endpoint) {
    return runner.withPropertyValues(
        "app.analysis.local-model.enabled=true",
        "app.analysis.local-model.endpoint=" + endpoint,
        "app.analysis.local-model.model=" + MODEL,
        "app.analysis.local-model.model-digest=" + DIGEST);
  }

  private static final class NoCallTransport implements OllamaTransport {
    @Override
    public OllamaTransportResponse exchange(OllamaTransportRequest request) {
      throw new AssertionError("Configuration tests must not call Ollama.");
    }
  }
}
