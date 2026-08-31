package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class Draft202012LocalDecisionEvidenceSchemaValidatorTest {
  private static final String PRIVATE_TEXT = "private title and memo content";

  private final ObjectMapper json = new ObjectMapper();
  private final Draft202012LocalDecisionEvidenceSchemaValidator validator =
      new Draft202012LocalDecisionEvidenceSchemaValidator();

  @Test
  void loadsTheCanonicalContractAndAcceptsBoundedRawFreeEvidence() {
    assertThat(
            Draft202012LocalDecisionEvidenceSchemaValidator.class.getResource(
                Draft202012LocalDecisionEvidenceSchemaValidator.SCHEMA_RESOURCE))
        .isNotNull();
    assertThatCode(() -> validator.validate(validEvidence())).doesNotThrowAnyException();
  }

  @Test
  void rejectsFreeTextPropertiesAndOversizedDocumentsWithoutLeakingThem() {
    ObjectNode freeText = validEvidence().put("title", PRIVATE_TEXT);
    ObjectNode oversized =
        validEvidence()
            .put(
                "padding",
                "x"
                    .repeat(
                        Draft202012LocalDecisionEvidenceSchemaValidator.MAX_EVIDENCE_JSON_BYTES));

    assertInvalidWithoutLeak(freeText);
    assertInvalidWithoutLeak(oversized);
  }

  @Test
  void rejectsOutOfRangeAndUnknownEnumValues() {
    ObjectNode count = validEvidence();
    ((ObjectNode) count.path("temporalSummary")).put("candidateCount", 6);
    ObjectNode leader = validEvidence();
    ((ObjectNode) leader.path("typeSummary")).put("leader", PRIVATE_TEXT);

    assertInvalidWithoutLeak(count);
    assertInvalidWithoutLeak(leader);
  }

  private ObjectNode validEvidence() {
    ObjectNode evidence = json.createObjectNode();
    evidence.put("version", "local-decision-v1");
    evidence
        .putObject("typeSummary")
        .put("candidateCount", 1)
        .put("leader", "TASK")
        .put("leaderScore", 0.96)
        .putNull("runnerUpScore")
        .putNull("margin");
    evidence
        .putObject("temporalSummary")
        .put("candidateCount", 1)
        .put("preciseCount", 1)
        .put("impreciseCount", 0)
        .put("explicitTimeCount", 1);
    evidence
        .putObject("taxonomySummary")
        .put("candidateCount", 0)
        .put("newProposalCount", 0)
        .putNull("strongestScore");
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

  private void assertInvalidWithoutLeak(ObjectNode evidence) {
    assertThatThrownBy(() -> validator.validate(evidence))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_LOCAL_DECISION_EVIDENCE");
              assertThat(exception.getMessage())
                  .isEqualTo("The local decision evidence is invalid.")
                  .doesNotContain(PRIVATE_TEXT)
                  .doesNotContain(evidence.toString());
            });
  }
}
