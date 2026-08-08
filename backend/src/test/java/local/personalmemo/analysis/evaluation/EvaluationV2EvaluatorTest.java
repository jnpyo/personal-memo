package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EvaluationV2EvaluatorTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";

  private final ObjectMapper json = new ObjectMapper();

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

    CaseEvaluation result =
        new EvaluationV2Evaluator(json).evaluate(fixture, proposal, memoId, 1, content.length());
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
            value("Review"),
            new EvaluationTextExpectation(EvaluationTextState.UNRESOLVED, null),
            new EvaluationSpanExpectation(EvaluationSpanRequirement.ABSENT, List.of()));
    EvaluationItemGoldSet set =
        new EvaluationItemGoldSet(
            "set-1", value("Review it"), "item-1", List.of(expected), List.of("item-1"));
    EvaluationActualItem hallucinated =
        new EvaluationActualItem("TASK", "Review it", "Review", "invented document", null, 0.99);

    ItemCaseEvaluation result =
        EvaluationV2Matcher.evaluateItems(
            new EvaluationItemGoldOptions("USER_INPUT_NEEDED", List.of(set)),
            List.of(hallucinated),
            "Review it");

    assertThat(result.itemCounts()).isEqualTo(new EvaluationCounts(0, 1, 1));
    assertThat(result.object()).isEqualTo(EvaluationFieldCounts.zero());
    assertThat(result.completeSetExact()).isFalse();
    assertThat(result.topOneCorrect()).isFalse();
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
