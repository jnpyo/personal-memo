package local.personalmemo.analysis.domain;

import tools.jackson.databind.JsonNode;

/** Validates the provider-independent structure of raw-free local-decision evidence. */
public interface LocalDecisionEvidenceSchemaValidator {
  void validate(JsonNode evidence);
}
