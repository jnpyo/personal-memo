package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;
import java.util.stream.Stream;
import local.personalmemo.analysis.infrastructure.Draft202012LocalDecisionEvidenceSchemaValidator;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the structural JSON Schema boundary distinct from server-owned cross-field invariants. All
 * values are public synthetic structure values and contain no memo text or identifiers.
 */
class LocalDecisionEvidenceMutationParityTest {
  private static final String INVALID_CODE = "INVALID_LOCAL_DECISION_EVIDENCE";
  private static final String INVALID_MESSAGE = "The local decision evidence is invalid.";

  private final ObjectMapper json = new ObjectMapper();
  private final Draft202012LocalDecisionEvidenceSchemaValidator schemaValidator =
      new Draft202012LocalDecisionEvidenceSchemaValidator();
  private final LocalDecisionEvidenceValidator domainValidator =
      new LocalDecisionEvidenceValidator(schemaValidator);

  @Test
  void acceptsThePublicSyntheticBaselineAtBothValidationLayers() {
    ObjectNode evidence = validEvidence();

    assertThatCode(() -> schemaValidator.validate(evidence)).doesNotThrowAnyException();
    assertThatCode(() -> domainValidator.validate(evidence)).doesNotThrowAnyException();
  }

  @TestFactory
  Stream<DynamicTest> rejectsEverySchemaValidCrossFieldMutationAtTheDomainBoundary() {
    return crossFieldMutations()
        .map(
            mutation ->
                DynamicTest.dynamicTest(
                    mutation.name(),
                    () -> {
                      ObjectNode evidence = validEvidence();
                      mutation.mutate().accept(evidence);

                      assertThatCode(() -> schemaValidator.validate(evidence))
                          .as("structural schema acceptance for %s", mutation.name())
                          .doesNotThrowAnyException();
                      assertThatThrownBy(() -> domainValidator.validate(evidence))
                          .as("domain rejection for %s", mutation.name())
                          .isInstanceOfSatisfying(
                              DomainException.class,
                              exception -> {
                                assertThat(exception.code()).isEqualTo(INVALID_CODE);
                                assertThat(exception.status().value()).isEqualTo(422);
                                assertThat(exception.getMessage())
                                    .isEqualTo(INVALID_MESSAGE)
                                    .doesNotContain(evidence.toString());
                              });
                    }));
  }

  private Stream<MutationCase> crossFieldMutations() {
    return Stream.of(
        mutation(
            "type.single-candidate-runner-up-must-be-null",
            evidence -> {
              ObjectNode summary = typeSummary(evidence);
              summary.put("candidateCount", 1).put("runnerUpScore", 0.8).putNull("margin");
            }),
        mutation(
            "type.single-candidate-margin-must-be-null",
            evidence -> {
              ObjectNode summary = typeSummary(evidence);
              summary.put("candidateCount", 1).putNull("runnerUpScore").put("margin", 0.1);
            }),
        mutation(
            "type.multi-candidate-runner-up-must-be-number",
            evidence -> typeSummary(evidence).putNull("runnerUpScore")),
        mutation(
            "type.multi-candidate-margin-must-be-number",
            evidence -> typeSummary(evidence).putNull("margin")),
        mutation(
            "type.leader-score-must-not-trail-runner-up",
            evidence -> {
              ObjectNode summary = typeSummary(evidence);
              summary.put("leaderScore", 0.7).put("runnerUpScore", 0.8).put("margin", 0.1);
            }),
        mutation(
            "type.margin-must-equal-leader-minus-runner-up",
            evidence -> typeSummary(evidence).put("margin", 0.2)),
        mutation(
            "temporal.precise-plus-imprecise-must-equal-candidate-count",
            evidence -> temporalSummary(evidence).put("impreciseCount", 1)),
        mutation(
            "temporal.explicit-time-must-not-exceed-precise-count",
            evidence -> {
              ObjectNode summary = temporalSummary(evidence);
              summary
                  .put("candidateCount", 1)
                  .put("preciseCount", 0)
                  .put("impreciseCount", 1)
                  .put("explicitTimeCount", 1);
            }),
        mutation(
            "taxonomy.new-proposals-must-not-exceed-candidate-count",
            evidence -> taxonomySummary(evidence).put("newProposalCount", 2)),
        mutation(
            "taxonomy.zero-candidates-require-null-strongest-score",
            evidence -> {
              ObjectNode summary = taxonomySummary(evidence);
              summary
                  .put("candidateCount", 0)
                  .put("newProposalCount", 0)
                  .put("strongestScore", 0.9);
            }),
        mutation(
            "taxonomy.positive-candidates-require-numeric-strongest-score",
            evidence -> taxonomySummary(evidence).putNull("strongestScore")),
        mutation(
            "item.tasks-must-not-exceed-candidate-count",
            evidence -> itemSummary(evidence).put("taskCount", 2)),
        mutation(
            "item.verbs-must-not-exceed-task-count",
            evidence -> itemSummary(evidence).put("verbPresentCount", 2)),
        mutation(
            "item.referents-must-not-exceed-task-count",
            evidence -> itemSummary(evidence).put("referentPresentCount", 2)),
        mutation(
            "item.due-bindings-must-not-exceed-task-count",
            evidence -> itemSummary(evidence).put("dueBindingCount", 2)));
  }

  private MutationCase mutation(String name, Consumer<ObjectNode> mutate) {
    return new MutationCase(name, mutate);
  }

  private ObjectNode typeSummary(ObjectNode evidence) {
    return (ObjectNode) evidence.path("typeSummary");
  }

  private ObjectNode temporalSummary(ObjectNode evidence) {
    return (ObjectNode) evidence.path("temporalSummary");
  }

  private ObjectNode taxonomySummary(ObjectNode evidence) {
    return (ObjectNode) evidence.path("taxonomySummary");
  }

  private ObjectNode itemSummary(ObjectNode evidence) {
    return (ObjectNode) evidence.path("itemSummary");
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

  private record MutationCase(String name, Consumer<ObjectNode> mutate) {}
}
