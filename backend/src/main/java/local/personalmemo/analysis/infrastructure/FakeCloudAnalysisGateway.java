package local.personalmemo.analysis.infrastructure;

import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class FakeCloudAnalysisGateway implements CloudAnalysisGateway {
  @Override
  public ObjectNode enrich(CloudAnalysisRequest request) {
    ObjectNode enriched = request.validatedLocalProposal();
    JsonNode metadataNode = enriched.get("providerMetadata");
    ObjectNode metadata =
        metadataNode instanceof ObjectNode objectMetadata
            ? objectMetadata
            : enriched.putObject("providerMetadata");
    metadata
        .put("cloudGatewayVersion", "fake-cloud-v1")
        .put("receivedRoutingPolicyVersion", request.routingPolicyVersion())
        .put("cloudToolCalls", 0)
        .put("cloudMutationCalls", 0)
        .putArray("cloudResolvedFields");
    var receivedReasons = metadata.putArray("receivedRoutingReasons");
    request.routingReasons().forEach(reason -> receivedReasons.add(reason.name()));
    return enriched;
  }
}
