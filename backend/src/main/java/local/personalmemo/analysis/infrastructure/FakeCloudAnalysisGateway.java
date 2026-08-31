package local.personalmemo.analysis.infrastructure;

import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;

public class FakeCloudAnalysisGateway implements CloudAnalysisGateway {
  private static final CloudGatewayDescriptor DESCRIPTOR =
      new CloudGatewayDescriptor(
          "fake-cloud-v2", "fake", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);

  @Override
  public CloudGatewayBinding bind() {
    return new CloudGatewayBinding(DESCRIPTOR, this::fakeEnrich);
  }

  private CloudAnalysisResult fakeEnrich(CloudAnalysisRequest request) {
    return CloudAnalysisResult.success(request.validatedLocalProposal());
  }
}
