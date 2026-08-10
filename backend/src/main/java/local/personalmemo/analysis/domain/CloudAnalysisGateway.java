package local.personalmemo.analysis.domain;

public interface CloudAnalysisGateway {
  CloudGatewayDescriptor descriptor();

  CloudAnalysisResult enrich(CloudAnalysisRequest request);
}
