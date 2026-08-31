package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import local.personalmemo.analysis.infrastructure.Draft202012LocalDecisionEvidenceSchemaValidator;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LocalDecisionEvidenceValidatorTest {
  private final ObjectMapper json = new ObjectMapper();
  private final LocalDecisionEvidenceValidator validator =
      new LocalDecisionEvidenceValidator(new Draft202012LocalDecisionEvidenceSchemaValidator());

  @Test
  void acceptsCoherentEvidenceAndUniqueStrictReasonCodes() {
    ArrayNode reasons =
        json.createArrayNode().add("DEFAULT_RECORD_FALLBACK").add("UNPARSED_TEMPORAL_CUE");

    assertThatCode(() -> validator.validate(validEvidence())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateFallbackReasonCodes(reasons)).doesNotThrowAnyException();
  }

  @Test
  void rejectsIncoherentSummaryCountsAndMargins() {
    ObjectNode badMargin = validEvidence();
    ((ObjectNode) badMargin.path("typeSummary")).put("margin", 0.2);
    ObjectNode badTemporal = validEvidence();
    ((ObjectNode) badTemporal.path("temporalSummary")).put("impreciseCount", 1);
    ObjectNode badTasks = validEvidence();
    ((ObjectNode) badTasks.path("itemSummary")).put("verbPresentCount", 2);

    assertInvalid(badMargin);
    assertInvalid(badTemporal);
    assertInvalid(badTasks);
  }

  @Test
  void rejectsEmptyDuplicateUnknownAndFreeTextReasonCodes() {
    assertThatThrownBy(() -> validator.validateFallbackReasonCodes(json.createArrayNode()))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                validator.validateFallbackReasonCodes(
                    json.createArrayNode().add("LOW_TYPE_MARGIN").add("LOW_TYPE_MARGIN")))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                validator.validateFallbackReasonCodes(
                    json.createArrayNode().add("private memo text")))
        .isInstanceOf(DomainException.class);
  }

  private ObjectNode validEvidence() {
    ObjectNode evidence = json.createObjectNode();
    evidence.put("version", LocalDecisionEvidenceProjection.EVIDENCE_VERSION);
    evidence
        .putObject("typeSummary")
        .put("candidateCount", 2)
        .put("leader", "TASK")
        .put("leaderScore", 0.9)
        .put("runnerUpScore", 0.8)
        .put("margin", 0.1);
    evidence
        .putObject("temporalSummary")
        .put("candidateCount", 1)
        .put("preciseCount", 1)
        .put("impreciseCount", 0)
        .put("explicitTimeCount", 1);
    evidence
        .putObject("taxonomySummary")
        .put("candidateCount", 1)
        .put("newProposalCount", 0)
        .put("strongestScore", 0.9);
    evidence
        .putObject("itemSummary")
        .put("candidateCount", 1)
        .put("taskCount", 1)
        .put("verbPresentCount", 1)
        .put("referentPresentCount", 1)
        .put("dueBindingCount", 1);
    evidence.put("relationCandidateCount", 0);
    return evidence;
  }

  private void assertInvalid(ObjectNode evidence) {
    assertThatThrownBy(() -> validator.validate(evidence)).isInstanceOf(DomainException.class);
  }
}
