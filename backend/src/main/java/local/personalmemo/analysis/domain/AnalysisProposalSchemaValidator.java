package local.personalmemo.analysis.domain;

import tools.jackson.databind.JsonNode;

/** Validates the provider-independent structure of an analysis proposal. */
public interface AnalysisProposalSchemaValidator {
  void validate(JsonNode proposal);
}
