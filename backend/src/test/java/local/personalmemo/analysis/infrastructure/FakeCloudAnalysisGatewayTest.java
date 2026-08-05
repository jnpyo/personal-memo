package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
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

    ObjectNode enriched =
        gateway.enrich(
            new CloudAnalysisRequest(
                local, List.of(AmbiguityReason.LOW_TYPE_MARGIN), "field-policy-v1"));

    assertThat(enriched).isNotSameAs(local);
    assertThat(enriched.path("memoId").asText()).isEqualTo(local.path("memoId").asText());
    assertThat(enriched.at("/providerMetadata/cloudGatewayVersion").asText())
        .isEqualTo("fake-cloud-v1");
    assertThat(enriched.at("/providerMetadata/cloudToolCalls").asInt()).isZero();
    assertThat(enriched.at("/providerMetadata/cloudMutationCalls").asInt()).isZero();
    assertThat(enriched.at("/providerMetadata/cloudResolvedFields").isArray()).isTrue();
    assertThat(enriched.at("/providerMetadata/receivedRoutingPolicyVersion").asText())
        .isEqualTo("field-policy-v1");
    assertThat(enriched.at("/providerMetadata/receivedRoutingReasons/0").asText())
        .isEqualTo("LOW_TYPE_MARGIN");
    assertThat(local.at("/providerMetadata/cloudGatewayVersion").isMissingNode()).isTrue();
  }
}
