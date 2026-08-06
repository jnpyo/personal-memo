package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DeterministicAmbiguityGateTest {
  private static final Set<AmbiguityReason> LOCAL_ONLY_SIGNALS =
      EnumSet.of(AmbiguityReason.MISSING_YEAR, AmbiguityReason.MISSING_TIME);

  private final DeterministicAmbiguityGate gate = new DeterministicAmbiguityGate();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void routesNoSignalAndReviewableDateOmissionsLocally() {
    assertThat(gate.version()).isEqualTo("field-policy-v1");
    assertThat(gate.route(Set.of())).isEqualTo(AnalysisRoute.LOCAL_REVIEW);
    assertThat(gate.route(LOCAL_ONLY_SIGNALS)).isEqualTo(AnalysisRoute.LOCAL_REVIEW);
  }

  @TestFactory
  Stream<DynamicTest> everyFieldLevelCriticalSignalRoutesToCloud() {
    return Stream.of(AmbiguityReason.values())
        .filter(reason -> !LOCAL_ONLY_SIGNALS.contains(reason))
        .map(
            reason ->
                DynamicTest.dynamicTest(
                    reason.name(),
                    () ->
                        assertThat(gate.route(Set.of(reason)))
                            .isEqualTo(AnalysisRoute.CLOUD_ENRICH)));
  }

  @Test
  void aCriticalSignalCannotBeHiddenByOtherwiseReviewableSignals() {
    assertThat(
            gate.route(
                EnumSet.of(
                    AmbiguityReason.MISSING_YEAR,
                    AmbiguityReason.MISSING_TIME,
                    AmbiguityReason.UNRESOLVED_REFERENCE)))
        .isEqualTo(AnalysisRoute.CLOUD_ENRICH);
  }

  @Test
  void derivesNestedDateAmbiguityWhenTopLevelSummaryOmitsIt() {
    ObjectNode proposal = emptyProposal();
    ObjectNode date = json.createObjectNode().put("precision", "APPROXIMATE");
    date.putArray("ambiguityReasons").add("IMPRECISE_DATE");
    proposal.putArray("dateCandidates").add(date);

    var signals = gate.routingSignals(proposal);

    assertThat(signals).contains(AmbiguityReason.IMPRECISE_DATE);
    assertThat(gate.route(signals)).isEqualTo(AnalysisRoute.CLOUD_ENRICH);
  }

  @Test
  void derivesLowTypeMarginFromCandidateScores() {
    ObjectNode proposal = emptyProposal();
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.90))
        .add(json.createObjectNode().put("value", "EVENT").put("score", 0.85));

    assertThat(gate.routingSignals(proposal)).contains(AmbiguityReason.LOW_TYPE_MARGIN);
  }

  @Test
  void derivesTagAndTaskSignalsFromCandidateStructure() {
    ObjectNode proposal = emptyProposal();
    proposal
        .putArray("tagCandidates")
        .add(json.createObjectNode().put("score", 0.72).put("isNewProposal", true));
    proposal
        .putArray("itemCandidates")
        .add(json.createObjectNode().put("kind", "TASK").putNull("action").putNull("object"));

    assertThat(gate.routingSignals(proposal))
        .contains(
            AmbiguityReason.LOW_TAG_SIMILARITY,
            AmbiguityReason.NEW_TOPIC,
            AmbiguityReason.MISSING_ACTION,
            AmbiguityReason.MISSING_OBJECT);
  }

  @Test
  void doesNotTreatIndependentTagsOrAHealthyTypeMarginAsAConflict() {
    ObjectNode proposal = emptyProposal();
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "EVENT").put("score", 0.96))
        .add(json.createObjectNode().put("value", "RECORD").put("score", 0.82));
    proposal
        .putArray("tagCandidates")
        .add(json.createObjectNode().put("score", 0.98).put("isNewProposal", false))
        .add(json.createObjectNode().put("score", 0.96).put("isNewProposal", false));

    assertThat(gate.routingSignals(proposal))
        .doesNotContain(AmbiguityReason.LOW_TYPE_MARGIN, AmbiguityReason.TAG_CONFLICT);
  }

  @Test
  void derivesMissingTaskAndTypeItemConflictFromStructure() {
    ObjectNode missingTask = emptyProposal();
    missingTask
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.96));

    ObjectNode conflicting = emptyProposal();
    conflicting
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.96));
    conflicting
        .putArray("itemCandidates")
        .add(
            json.createObjectNode().put("kind", "INFORMATION").putNull("action").putNull("object"));

    assertThat(gate.routingSignals(missingTask)).contains(AmbiguityReason.MISSING_ACTION);
    assertThat(gate.routingSignals(conflicting)).contains(AmbiguityReason.LOCAL_CLOUD_CONFLICT);
  }

  private ObjectNode emptyProposal() {
    ObjectNode proposal = json.createObjectNode();
    proposal.putArray("ambiguityReasons");
    proposal.putArray("typeCandidates");
    proposal.putArray("dateCandidates");
    proposal.putArray("tagCandidates");
    proposal.putArray("itemCandidates");
    return proposal;
  }
}
