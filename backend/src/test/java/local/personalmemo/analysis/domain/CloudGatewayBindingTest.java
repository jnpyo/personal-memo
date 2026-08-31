package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CloudGatewayBindingTest {
  private static final CloudGatewayDescriptor DESCRIPTOR =
      new CloudGatewayDescriptor(
          "gateway-v1", "provider-v1", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void bindsTheDescriptorToExactlyOneExecutor() {
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR, request -> CloudAnalysisResult.success(request.validatedLocalProposal()));

    CloudAnalysisResult result = binding.execute(request(DESCRIPTOR));

    assertThat(binding.descriptor()).isEqualTo(DESCRIPTOR);
    assertThat(binding.bindingId().value()).hasSize(69).startsWith("cgb1_");
    assertThat(((CloudAnalysisResult.Success) result).proposal().path("value").asText())
        .isEqualTo("safe");
  }

  @Test
  void producesAStableNonSecretBindingIdGoldenVector() {
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR, request -> CloudAnalysisResult.success(request.validatedLocalProposal()));

    assertThat(binding.bindingId().value())
        .isEqualTo("cgb1_112611c1fe5976df5137ac09732d64226066ae48f8ee2c10ee941b190737bb96");
  }

  @Test
  void rejectsDescriptorDriftBeforeCallingTheBoundExecutor() {
    AtomicBoolean called = new AtomicBoolean();
    CloudGatewayBinding binding =
        new CloudGatewayBinding(
            DESCRIPTOR,
            request -> {
              called.set(true);
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    CloudGatewayDescriptor changed =
        new CloudGatewayDescriptor(
            "gateway-v2", "provider-v1", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);

    assertThatThrownBy(() -> binding.execute(request(changed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("descriptor");
    assertThat(called).isFalse();
  }

  @Test
  void rejectsNullCollaboratorsAndNullResults() {
    assertThatThrownBy(() -> new CloudGatewayBinding(null, request -> null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("descriptor");
    assertThatThrownBy(() -> new CloudGatewayBinding(DESCRIPTOR, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("executor");

    CloudGatewayBinding binding = new CloudGatewayBinding(DESCRIPTOR, request -> null);
    assertThatThrownBy(() -> binding.execute(request(DESCRIPTOR)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The bound cloud gateway returned no result.");
  }

  @Test
  void redactsTheExecutorFromStringForm() {
    CloudAnalysisExecutor executor =
        new CloudAnalysisExecutor() {
          @Override
          public CloudAnalysisResult execute(CloudAnalysisRequest request) {
            return CloudAnalysisResult.success(request.validatedLocalProposal());
          }

          @Override
          public String toString() {
            return "provider-api-key-secret";
          }
        };

    String rendered = new CloudGatewayBinding(DESCRIPTOR, executor).toString();

    assertThat(rendered)
        .contains("executor=redacted", "gateway-v1")
        .doesNotContain("provider-api-key-secret");
  }

  private CloudAnalysisRequest request(CloudGatewayDescriptor descriptor) {
    return new CloudAnalysisRequest(
        json.createObjectNode().put("value", "safe"),
        List.of(),
        "field-policy-v1",
        descriptor,
        Optional.empty(),
        Optional.empty(),
        CloudProviderRequestToken.issue(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "ANALYSIS_START",
            "binding-test",
            "a".repeat(64)));
  }
}
