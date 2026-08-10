package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CloudGatewayDescriptorTest {

  @Test
  void retainsServerOwnedTransferAndConsentPolicyIdentity() {
    var descriptor =
        new CloudGatewayDescriptor(
            "gateway-v1",
            "provider-a",
            "model-a",
            "memo-transfer-v1",
            CloudTransferMode.EXTERNAL_MEMO_CONTENT);

    assertThat(descriptor.gatewayVersion()).isEqualTo("gateway-v1");
    assertThat(descriptor.providerId()).isEqualTo("provider-a");
    assertThat(descriptor.modelVersion()).isEqualTo("model-a");
    assertThat(descriptor.consentPolicyVersion()).isEqualTo("memo-transfer-v1");
    assertThat(descriptor.transferMode()).isEqualTo(CloudTransferMode.EXTERNAL_MEMO_CONTENT);
  }

  @Test
  void rejectsMissingOrOversizedDescriptorFields() {
    assertThatThrownBy(
            () ->
                new CloudGatewayDescriptor(
                    " ", "provider", "model", "policy", CloudTransferMode.NO_NETWORK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CloudGatewayDescriptor(
                    "gateway",
                    "provider",
                    "model",
                    "😀".repeat(AnalysisProvenance.MAX_VERSION_LENGTH + 1),
                    CloudTransferMode.NO_NETWORK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CloudGatewayDescriptor("gateway", "provider", "model", "policy", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new CloudGatewayDescriptor(
                    "legacy-unknown", "provider", "model", "policy", CloudTransferMode.NO_NETWORK))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
