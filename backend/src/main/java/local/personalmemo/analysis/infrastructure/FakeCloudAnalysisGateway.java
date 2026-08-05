package local.personalmemo.analysis.infrastructure;

import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

@Component
public class FakeCloudAnalysisGateway implements CloudAnalysisGateway {
  @Override
  public ObjectNode enrich(ObjectNode validatedLocalProposal) {
    return validatedLocalProposal.deepCopy();
  }
}
