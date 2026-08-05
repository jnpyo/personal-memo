package local.personalmemo.analysis.domain;

import tools.jackson.databind.node.ObjectNode;

public interface CloudAnalysisGateway {
  ObjectNode enrich(ObjectNode validatedLocalProposal);
}
