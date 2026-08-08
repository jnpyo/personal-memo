package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EvaluationV2EvaluatorTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final EvaluationV2Evaluator evaluator =
      new EvaluationV2Evaluator(json, analyzer.provenance(), ambiguityGate.version());

  @Test
  void dateSemanticNormalizationIsFailClosedAndOffsetIndependent() {
    ObjectNode invalidApproximate =
        json.createObjectNode()
            .put("precision", "APPROXIMATE")
            .putNull("value")
            .put("timeSpecified", true);
    ObjectNode invalidUnknown = invalidApproximate.deepCopy().put("precision", "UNKNOWN");
    assertThat(EvaluationDateSemantic.from(invalidApproximate)).isNull();
    assertThat(EvaluationDateSemantic.from(invalidUnknown)).isNull();

    ObjectNode seoul =
        json.createObjectNode()
            .put("precision", "EXACT_TIME")
            .put("value", "2026-11-25T18:00:00+09:00")
            .put("timeSpecified", true);
    ObjectNode utc = seoul.deepCopy().put("value", "2026-11-25T09:00:00Z");
    assertThat(EvaluationDateSemantic.from(seoul)).isEqualTo(EvaluationDateSemantic.from(utc));
  }

  @Test
  void typeTopOneUsesScoreArgmaxAndExtraCandidatesReducePrecisionAndSetExactness()
      throws Exception {
    ObjectNode fixture = fixture("information-only");
    String content = fixture.path("content").asText();
    UUID memoId = UUID.randomUUID();
    ObjectNode proposal =
        new FakeAnalyzer(json)
            .analyze(
                memoId,
                1,
                content,
                Instant.parse(fixture.path("baseInstant").asText()),
                fixture.path("timeZone").asText());
    ArrayNode types = (ArrayNode) proposal.path("typeCandidates");
    ObjectNode expected = ((ObjectNode) types.get(0)).deepCopy().put("score", 0.9);
    types.removeAll();
    types.add(json.createObjectNode().put("value", "TASK").put("score", 0.1));
    types.add(expected);

    CaseEvaluation result = evaluator.evaluate(fixture, proposal, memoId, 1, content);
    assertThat(result.topTypeCorrect()).isTrue();
    assertThat(result.typeSetExact()).isFalse();
    assertThat(result.semanticFalseConfidentLocal()).isTrue();

    ObjectNode aggregate = EvaluationV2Metrics.aggregate(List.of(result)).toJson(json);
    assertThat(aggregate.at("/type/actualCandidateCount").asInt()).isEqualTo(2);
    assertThat(aggregate.at("/type/matchedCandidatePrecision").asDouble()).isEqualTo(0.5);
    assertThat(aggregate.at("/type/candidateSetExactRate").asDouble()).isZero();
  }

  @Test
  void completeAlternativeSelectionUsesSuggestedTitleAfterItemSemanticTie() {
    EvaluationItemGold item =
        new EvaluationItemGold(
            "item-1",
            "TASK",
            value("Do it"),
            value("Do"),
            value("it"),
            new EvaluationSpanExpectation(EvaluationSpanRequirement.ABSENT, List.of()));
    EvaluationItemGoldSet alphabeticallyFirstWrongTitle =
        new EvaluationItemGoldSet(
            "set-a", value("Wrong title"), "item-1", List.of(item), List.of("item-1"));
    EvaluationItemGoldSet alphabeticallySecondCorrectTitle =
        new EvaluationItemGoldSet(
            "set-b",
            value("Right title"),
            "item-2",
            List.of(withId(item, "item-2")),
            List.of("item-2"));
    EvaluationActualItem actual = new EvaluationActualItem("TASK", "Do it", "Do", "it", null, 0.9);

    ItemCaseEvaluation result =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions(
                "USER_INPUT_NEEDED",
                List.of(alphabeticallyFirstWrongTitle, alphabeticallySecondCorrectTitle)),
            List.of(actual),
            "Right title");

    assertThat(result.completeSetExact()).isTrue();
    assertThat(result.suggestedTitle()).isEqualTo(new EvaluationFieldCounts(1, 0, 0));
  }

  @Test
  void requiredSourceSpanNullIsAFalseNegativeAndBlocksCompleteItemMatch() {
    EvaluationItemGold expected =
        new EvaluationItemGold(
            "item-1",
            "TASK",
            value("Do it"),
            value("Do"),
            value("it"),
            new EvaluationSpanExpectation(
                EvaluationSpanRequirement.REQUIRED, List.of(new EvaluationSpan(0, 5))));
    EvaluationItemGoldSet set =
        new EvaluationItemGoldSet(
            "set-1", value("Do it"), "item-1", List.of(expected), List.of("item-1"));
    EvaluationActualItem actual = new EvaluationActualItem("TASK", "Do it", "Do", "it", null, 0.9);

    ItemCaseEvaluation result =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions("RESOLVED", List.of(set)), List.of(actual), "Do it");

    assertThat(result.sourceSpan()).isEqualTo(new EvaluationFieldCounts(0, 0, 1));
    assertThat(result.completeSetExact()).isFalse();
  }

  @Test
  void unresolvedGoldCannotTurnAnArbitraryValueIntoAnExactItemOrCompleteSet() {
    EvaluationItemGold expected =
        new EvaluationItemGold(
            "item-1",
            "TASK",
            value("Review it"),
            new EvaluationTextExpectation(EvaluationTextState.UNRESOLVED, null),
            new EvaluationTextExpectation(EvaluationTextState.UNRESOLVED, null),
            new EvaluationSpanExpectation(EvaluationSpanRequirement.ABSENT, List.of()));
    EvaluationItemGoldSet set =
        new EvaluationItemGoldSet(
            "set-1", value("Review it"), "item-1", List.of(expected), List.of("item-1"));
    EvaluationActualItem hallucinated =
        new EvaluationActualItem(
            "TASK", "Review it", "invented action", "invented document", null, 0.99);

    ItemCaseEvaluation result =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions("USER_INPUT_NEEDED", List.of(set)),
            List.of(hallucinated),
            "Review it");

    assertThat(result.itemCounts()).isEqualTo(new EvaluationCounts(0, 1, 1));
    assertThat(result.action()).isEqualTo(EvaluationFieldCounts.zero());
    assertThat(result.object()).isEqualTo(EvaluationFieldCounts.zero());
    assertThat(result.unresolvedFieldHallucinationCount()).isEqualTo(2);
    assertThat(result.completeSetExact()).isFalse();
    assertThat(result.topOneCorrect()).isFalse();

    EvaluationActualItem safelyUnresolved =
        new EvaluationActualItem("TASK", "Review it", null, null, null, 0.99);
    ItemCaseEvaluation safeResult =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions("USER_INPUT_NEEDED", List.of(set)),
            List.of(safelyUnresolved),
            "Review it");
    assertThat(safeResult.unresolvedFieldHallucinationCount()).isZero();

    ItemCaseEvaluation extraHallucinationResult =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions("USER_INPUT_NEEDED", List.of(set)),
            List.of(hallucinated, safelyUnresolved),
            "Review it");
    assertThat(extraHallucinationResult.unresolvedFieldHallucinationCount()).isEqualTo(2);
    assertThat(extraHallucinationResult.completeSetExact()).isFalse();
  }

  @Test
  void productionDomainValidationRejectsTamperedProvenanceAndSurrogateSplitSpans()
      throws Exception {
    ObjectNode fixture = fixture("prompt-injection");
    String original = fixture.path("content").asText();
    String content = "😀" + original;
    fixture.put("content", content);
    ObjectNode goldSpan =
        (ObjectNode)
            fixture.at("/expectedItems/acceptableSets/0/allItems/0/sourceSpan/acceptedSpans/0");
    goldSpan.put("start", 0).put("end", content.length());
    UUID memoId = UUID.randomUUID();
    ObjectNode proposal =
        analyzer.analyze(
            memoId,
            1,
            content,
            Instant.parse(fixture.path("baseInstant").asText()),
            fixture.path("timeZone").asText());

    ((ObjectNode) proposal.at("/providerMetadata")).put("analyzerVersion", "tampered");
    assertThat(evaluator.evaluate(fixture, proposal, memoId, 1, content).domainValid()).isFalse();

    ((ObjectNode) proposal.at("/providerMetadata"))
        .put("analyzerVersion", analyzer.provenance().analyzerVersion());
    ((ObjectNode) proposal.at("/itemCandidates/0/sourceSpan")).put("start", 1);
    assertThat(evaluator.evaluate(fixture, proposal, memoId, 1, content).domainValid()).isFalse();
  }

  @Test
  void missingOverflowSignalIsCountedEvenWhenAnotherSignalAlreadyRoutesToCloud() throws Exception {
    ObjectNode fixture = fixture("long-ambiguous-note");
    String content = fixture.path("content").asText();
    UUID memoId = UUID.randomUUID();
    ObjectNode proposal =
        new FakeAnalyzer(json)
            .analyze(
                memoId,
                1,
                content,
                Instant.parse(fixture.path("baseInstant").asText()),
                fixture.path("timeZone").asText());
    removeSignal(proposal, "CANDIDATE_LIMIT_EXCEEDED");

    CaseEvaluation missing = evaluator.evaluate(fixture, proposal, memoId, 1, content);

    assertThat(missing.actualRoute()).isEqualTo("CLOUD_ENRICH");
    assertThat(missing.overflowExpected()).isTrue();
    assertThat(missing.missingOverflowSignal()).isTrue();
    AggregateEvaluation aggregate = EvaluationV2Metrics.aggregate(List.of(missing));
    assertThat(aggregate.missingOverflowSignalCount()).isEqualTo(1);
    assertThat(aggregate.toJson(json).at("/safety/missingOverflowSignalCount").asInt())
        .isEqualTo(1);
    ObjectNode gates =
        EvaluationV2Report.gates(json, aggregate, EvaluationV2Metrics.aggregate(List.of()));
    assertThat(gates.at("/regression/missingOverflowSignalMaximum").asInt()).isZero();
    assertThat(gates.at("/regression/actualMissingOverflowSignalCount").asInt()).isEqualTo(1);
    assertThat(gates.at("/regression/passed").asBoolean()).isFalse();
    ObjectNode visibleOnlyGates =
        EvaluationV2Report.gates(json, EvaluationV2Metrics.aggregate(List.of()), aggregate);
    assertThat(visibleOnlyGates.at("/visibleChallenge/enforced").asBoolean()).isFalse();
    assertThat(visibleOnlyGates.at("/visibleChallenge/actualMissingOverflowSignalCount").asInt())
        .isEqualTo(1);
    assertThat(
            visibleOnlyGates.at("/visibleChallenge/missingOverflowSignalMaximum").isMissingNode())
        .isTrue();

    ((ArrayNode) proposal.path("ambiguityReasons")).add("CANDIDATE_LIMIT_EXCEEDED");
    CaseEvaluation guarded = evaluator.evaluate(fixture, proposal, memoId, 1, content);
    assertThat(guarded.missingOverflowSignal()).isFalse();
  }

  @Test
  void unresolvedObjectHallucinationIsAggregatedAndFailsTheRegressionSafetyGate() throws Exception {
    ObjectNode fixture = fixture("imprecise-reference-task");
    String content = fixture.path("content").asText();
    UUID memoId = UUID.randomUUID();
    ObjectNode proposal =
        new FakeAnalyzer(json)
            .analyze(
                memoId,
                1,
                content,
                Instant.parse(fixture.path("baseInstant").asText()),
                fixture.path("timeZone").asText());
    ((ObjectNode) proposal.at("/itemCandidates/0")).put("object", "invented document");

    CaseEvaluation hallucinated = evaluator.evaluate(fixture, proposal, memoId, 1, content);

    assertThat(hallucinated.items().unresolvedFieldHallucinationCount()).isEqualTo(1);
    AggregateEvaluation aggregate = EvaluationV2Metrics.aggregate(List.of(hallucinated));
    assertThat(aggregate.unresolvedFieldHallucinationCount()).isEqualTo(1);
    assertThat(aggregate.toJson(json).at("/safety/unresolvedFieldHallucinationCount").asInt())
        .isEqualTo(1);
    ObjectNode gates =
        EvaluationV2Report.gates(json, aggregate, EvaluationV2Metrics.aggregate(List.of()));
    assertThat(gates.at("/regression/unresolvedFieldHallucinationMaximum").asInt()).isZero();
    assertThat(gates.at("/regression/actualUnresolvedFieldHallucinationCount").asInt())
        .isEqualTo(1);
    assertThat(gates.at("/regression/passed").asBoolean()).isFalse();
    ObjectNode visibleOnlyGates =
        EvaluationV2Report.gates(json, EvaluationV2Metrics.aggregate(List.of()), aggregate);
    assertThat(visibleOnlyGates.at("/visibleChallenge/enforced").asBoolean()).isFalse();
    assertThat(
            visibleOnlyGates
                .at("/visibleChallenge/actualUnresolvedFieldHallucinationCount")
                .asInt())
        .isEqualTo(1);
    assertThat(
            visibleOnlyGates
                .at("/visibleChallenge/unresolvedFieldHallucinationMaximum")
                .isMissingNode())
        .isTrue();

    ((ObjectNode) proposal.at("/itemCandidates/0")).putNull("object");
    CaseEvaluation safe = evaluator.evaluate(fixture, proposal, memoId, 1, content);
    assertThat(safe.items().unresolvedFieldHallucinationCount()).isZero();
  }

  @Test
  void inventedPreciseDateAndZeroDenominatorsRemainVisibleWithoutRawCases() {
    EvaluationDateGold mention =
        new EvaluationDateGold(
            "date-1",
            new EvaluationSpan(0, 4),
            "soon",
            List.of(new EvaluationDateSemantic("APPROXIMATE", null, false)),
            Set.of("IMPRECISE_DATE"),
            true);
    DateCaseEvaluation dateResult =
        EvaluationV2Matcher.evaluateDates(
            new EvaluationDateGoldSet(List.of(mention), List.of("date-1"), "date-1"),
            List.of(
                new EvaluationActualDate(
                    "soon",
                    new EvaluationDateSemantic("DATE_ONLY", "2026-08-10", false),
                    true,
                    0.95)),
            "soon");
    assertThat(dateResult.inventedPreciseDate()).isTrue();
    assertThat(dateResult.semanticCounts()).isEqualTo(new EvaluationCounts(0, 1, 1));

    AggregateEvaluation empty = EvaluationV2Metrics.aggregate(List.of());
    ObjectNode report =
        EvaluationV2Report.aggregateOnly(
            json,
            new EvaluationReportMetadata("2", "2", "test", "test", "test", "NOT_SUPPORTED"),
            Map.of("blind", empty));
    assertThat(report.at("/splits/blind/type/top1Accuracy").isNull()).isTrue();
    assertThat(report.at("/splits/blind/type/matchedCandidatePrecision").isNull()).isTrue();
    assertThat(report.at("/splits/blind/dates/semantic/precision").isNull()).isTrue();
    assertThat(report.has("cases")).isFalse();
    assertThat(report.findValue("id")).isNull();
  }

  private EvaluationTextExpectation value(String value) {
    return new EvaluationTextExpectation(
        EvaluationTextState.VALUE, EvaluationTextNormalizer.normalize(value));
  }

  private EvaluationItemGold withId(EvaluationItemGold item, String id) {
    return new EvaluationItemGold(
        id, item.kind(), item.title(), item.action(), item.object(), item.sourceSpan());
  }

  private void removeSignal(ObjectNode proposal, String removed) {
    ArrayNode reasons = (ArrayNode) proposal.path("ambiguityReasons");
    List<String> retained = new java.util.ArrayList<>();
    reasons.forEach(
        reason -> {
          if (!removed.equals(reason.asText())) {
            retained.add(reason.asText());
          }
        });
    reasons.removeAll();
    retained.forEach(reasons::add);
  }

  private ObjectNode fixture(String id) throws Exception {
    try (InputStream stream = getClass().getResourceAsStream(REGRESSION_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Regression fixture resource is missing.");
      }
      for (JsonNode fixture : json.readTree(stream)) {
        if (id.equals(fixture.path("id").asText())) {
          return (ObjectNode) fixture.deepCopy();
        }
      }
    }
    throw new IllegalArgumentException("Unknown fixture ID: " + id);
  }
}
