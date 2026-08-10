package local.personalmemo.analysis.infrastructure;

import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import org.springframework.stereotype.Component;

@Component
public class FakeCloudAnalysisGateway implements CloudAnalysisGateway {
  private static final CloudGatewayDescriptor DESCRIPTOR =
      new CloudGatewayDescriptor(
          "fake-cloud-v2", "fake", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);

  @Override
  public CloudGatewayDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CloudAnalysisResult enrich(CloudAnalysisRequest request) {
    return CloudAnalysisResult.success(request.validatedLocalProposal());
  }
}
