package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.junit.jupiter.api.Test;

class AnalysisInvocationPolicyTest {
  @Test
  void uncertaintyOnlyKeepsSemanticRoutingAuthoritative() {
    AnalysisInvocationPolicy policy = policy(AnalysisInvocationMode.UNCERTAINTY_ONLY);

    AnalysisInvocationDecision local = policy.decide(AnalysisRoute.LOCAL_REVIEW, null);
    AnalysisInvocationDecision uncertain = policy.decide(AnalysisRoute.CLOUD_ENRICH, null);

    assertThat(local.shouldInvoke()).isFalse();
    assertThat(uncertain.shouldInvoke()).isTrue();
    assertThat(local.policyVersion()).isEqualTo("model-invocation-v1");
    assertThat(local.mode()).isEqualTo(AnalysisInvocationMode.UNCERTAINTY_ONLY);
    assertThat(local.reason()).isEqualTo(AnalysisInvocationReason.SEMANTIC_UNCERTAINTY);
    assertThat(uncertain.reason()).isEqualTo(AnalysisInvocationReason.SEMANTIC_UNCERTAINTY);
  }

  @Test
  void aiPreferredInvokesAValidMachineLocalBindingWithoutChangingTheSemanticReason() {
    AnalysisInvocationPolicy policy = policy(AnalysisInvocationMode.AI_PREFERRED);
    CloudGatewayBinding binding = binding(CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT);

    AnalysisInvocationDecision preferred = policy.decide(AnalysisRoute.LOCAL_REVIEW, binding);
    AnalysisInvocationDecision uncertain = policy.decide(AnalysisRoute.CLOUD_ENRICH, binding);

    assertThat(preferred.shouldInvoke()).isTrue();
    assertThat(preferred.mode()).isEqualTo(AnalysisInvocationMode.AI_PREFERRED);
    assertThat(preferred.reason()).isEqualTo(AnalysisInvocationReason.AI_PREFERRED_POLICY);
    assertThat(uncertain.shouldInvoke()).isTrue();
    assertThat(uncertain.reason()).isEqualTo(AnalysisInvocationReason.SEMANTIC_UNCERTAINTY);
  }

  @Test
  void aiPreferredFailsClosedWithoutAnExactMachineLocalTransferBoundary() {
    AnalysisInvocationPolicy policy = policy(AnalysisInvocationMode.AI_PREFERRED);

    assertThatThrownBy(() -> policy.decide(AnalysisRoute.LOCAL_REVIEW, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AI_PREFERRED requires a bound machine-local model gateway.");
    assertThatThrownBy(
            () -> policy.decide(AnalysisRoute.CLOUD_ENRICH, binding(CloudTransferMode.NO_NETWORK)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                policy.decide(
                    AnalysisRoute.CLOUD_ENRICH, binding(CloudTransferMode.EXTERNAL_MEMO_CONTENT)))
        .isInstanceOf(IllegalStateException.class);
  }

  private AnalysisInvocationPolicy policy(AnalysisInvocationMode mode) {
    AnalysisInvocationProperties properties = new AnalysisInvocationProperties();
    properties.setMode(mode);
    return new AnalysisInvocationPolicy(properties);
  }

  private CloudGatewayBinding binding(CloudTransferMode transferMode) {
    String modelVersion = transferMode == CloudTransferMode.NO_NETWORK ? "none" : "model-v1";
    return new CloudGatewayBinding(
        new CloudGatewayDescriptor(
            "gateway-v1", "provider-v1", modelVersion, "consent-v1", transferMode),
        request -> new CloudAnalysisResult.Failure(CloudAnalysisFailureReason.UNAVAILABLE));
  }
}
