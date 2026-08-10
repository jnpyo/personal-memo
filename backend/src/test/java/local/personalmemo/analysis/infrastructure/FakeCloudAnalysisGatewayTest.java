package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudProviderRequestToken;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class FakeCloudAnalysisGatewayTest {
  private final ObjectMapper json = new ObjectMapper();
  private final FakeCloudAnalysisGateway gateway = new FakeCloudAnalysisGateway();

  @Test
  void enrichesADeepCopyWithZeroToolAndMutationMetadata() {
    ObjectNode local =
        json.createObjectNode()
            .put("memoId", "f3341ab7-ace5-433f-9c1f-26ed115fe4ba")
            .set("providerMetadata", json.createObjectNode().put("route", "CLOUD_ENRICH"));

    CloudAnalysisResult result =
        gateway.enrich(
            new CloudAnalysisRequest(
                local,
                List.of(AmbiguityReason.LOW_TYPE_MARGIN),
                "field-policy-v1",
                gateway.descriptor(),
                Optional.empty(),
                Optional.empty(),
                CloudProviderRequestToken.issue(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "ANALYSIS_START",
                    "fake-cloud-test",
                    "a".repeat(64))));
    ObjectNode enriched = ((CloudAnalysisResult.Success) result).proposal();

    assertThat(enriched).isNotSameAs(local);
    assertThat(enriched.path("memoId").asText()).isEqualTo(local.path("memoId").asText());
    assertThat(local.at("/providerMetadata/cloudGatewayVersion").isMissingNode()).isTrue();
    enriched.put("memoId", "changed");
    assertThat(((CloudAnalysisResult.Success) result).proposal().path("memoId").asText())
        .isEqualTo("f3341ab7-ace5-433f-9c1f-26ed115fe4ba");
  }

  @Test
  void declaresAStableNoNetworkDescriptor() {
    var descriptor = gateway.descriptor();

    assertThat(descriptor.gatewayVersion()).isEqualTo("fake-cloud-v2");
    assertThat(descriptor.providerId()).isEqualTo("fake");
    assertThat(descriptor.modelVersion()).isEqualTo("none");
    assertThat(descriptor.consentPolicyVersion()).isEqualTo("no-network-v1");
    assertThat(descriptor.transferMode()).isEqualTo(CloudTransferMode.NO_NETWORK);
  }

  @Test
  void rejectsARequestBoundToAnotherDescriptor() {
    CloudAnalysisRequest request =
        new CloudAnalysisRequest(
            json.createObjectNode(),
            List.of(),
            "field-policy-v1",
            new CloudGatewayDescriptor(
                "other-gateway-v1",
                "other-provider",
                "none",
                "other-policy-v1",
                CloudTransferMode.NO_NETWORK),
            Optional.empty(),
            Optional.empty(),
            CloudProviderRequestToken.issue(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ANALYSIS_START",
                "fake-cloud-mismatch",
                "b".repeat(64)));

    assertThatThrownBy(() -> gateway.enrich(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("descriptor");
  }
}
