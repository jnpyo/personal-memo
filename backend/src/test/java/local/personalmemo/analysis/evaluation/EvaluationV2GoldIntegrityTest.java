package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EvaluationV2GoldIntegrityTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void rejectsDanglingReferencesInvalidSpansAndNormalizedDuplicateInterpretations()
      throws Exception {
    ObjectNode dangling = fixture("clear-explicit-task");
    ((ObjectNode) dangling.path("expectedDates"))
        .putArray("emittedCandidateGoldIds")
        .add("date-missing");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(dangling))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dangling");

    ObjectNode emptySpan = fixture("clear-explicit-task");
    ObjectNode span =
        (ObjectNode)
            emptySpan.at("/expectedItems/acceptableSets/0/allItems/0/sourceSpan/acceptedSpans/0");
    span.put("end", span.path("start").asInt());
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(emptySpan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be empty");

    ObjectNode duplicateSemantic = fixture("clear-explicit-task");
    ArrayNode interpretations =
        (ArrayNode) duplicateSemantic.at("/expectedDates/mentions/0/acceptedInterpretations");
    interpretations.add(
        json.createObjectNode()
            .put("precision", "EXACT_TIME")
            .put("value", "2026-11-25T09:00:00Z")
            .put("timeSpecified", true));
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(duplicateSemantic))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized date interpretation");
  }

  @Test
  void enforcesFiveDateBoundaryAndCandidateLimitForTheSixthMention() throws Exception {
    assertThatCode(() -> EvaluationV2GoldIntegrity.validate(withDateMentions(4)))
        .doesNotThrowAnyException();
    assertThatCode(() -> EvaluationV2GoldIntegrity.validate(withDateMentions(5)))
        .doesNotThrowAnyException();
    assertThatCode(() -> EvaluationV2GoldIntegrity.validate(withDateMentions(6)))
        .doesNotThrowAnyException();

    ObjectNode sixEmitted = withDateMentions(6);
    ((ArrayNode) sixEmitted.at("/expectedDates/emittedCandidateGoldIds")).add("date-6");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(sixEmitted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most five");

    ObjectNode missingBoundarySignal = withDateMentions(6);
    ((ObjectNode) missingBoundarySignal).putArray("expectedSignals");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(missingBoundarySignal))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("product boundary signals");
  }

  @Test
  void rejectsOverflowAndResolvedItemGoldThatCouldPassAsConfidentLocal() throws Exception {
    ObjectNode overflow = fixture("long-ambiguous-note");
    ((ArrayNode) overflow.at("/expectedItems/acceptableSets/0/emittedItemGoldIds")).add("item-4");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(overflow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most three");

    ObjectNode unresolved = fixture("clear-explicit-task");
    ObjectNode objectExpectation =
        (ObjectNode) unresolved.at("/expectedItems/acceptableSets/0/allItems/0/object");
    objectExpectation.remove("value");
    objectExpectation.put("state", "UNRESOLVED");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(unresolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RESOLVED");
  }

  @Test
  void alternativeDateInterpretationsRequireReasonsAndBothCloudBoundaries() throws Exception {
    ObjectNode fixture = fixture("clear-explicit-task");
    ObjectNode mention = (ObjectNode) fixture.at("/expectedDates/mentions/0");
    ((ArrayNode) mention.path("acceptedInterpretations"))
        .add(
            json.createObjectNode()
                .put("precision", "EXACT_TIME")
                .put("value", "2026-11-25T19:00:00+09:00")
                .put("timeSpecified", true));

    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(fixture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ambiguity reason");

    ((ArrayNode) mention.path("ambiguityReasons")).add("MISSING_YEAR");
    ((ArrayNode) fixture.path("expectedSignals")).add("MISSING_YEAR");
    ((ArrayNode) fixture.path("analyzerExpectedSignals")).add("MISSING_YEAR");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(fixture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("product cloud boundary");

    fixture.put("expectedRoute", "CLOUD_ENRICH");
    fixture.put("analyzerExpectedRoute", "LOCAL_REVIEW");
    assertThatThrownBy(() -> EvaluationV2GoldIntegrity.validate(fixture))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must route to cloud review");

    fixture.put("analyzerExpectedRoute", "CLOUD_ENRICH");
    assertThatCode(() -> EvaluationV2GoldIntegrity.validate(fixture)).doesNotThrowAnyException();
  }

  private ObjectNode withDateMentions(int count) throws Exception {
    ObjectNode fixture = fixture("information-only");
    String original = fixture.path("content").asText();
    String suffix = "abcdef";
    fixture.put("content", original + suffix);

    ObjectNode dates = (ObjectNode) fixture.path("expectedDates");
    ArrayNode mentions = dates.putArray("mentions");
    ArrayNode emitted = dates.putArray("emittedCandidateGoldIds");
    int start = original.length();
    for (int index = 0; index < count; index++) {
      String goldId = "date-" + (index + 1);
      ObjectNode mention = mentions.addObject();
      mention.put("goldId", goldId);
      mention
          .putObject("sourceSpan")
          .put("start", start + index)
          .put("end", start + index + 1)
          .put("unit", "UTF16_CODE_UNIT");
      mention.put("surfaceText", suffix.substring(index, index + 1));
      mention
          .putArray("acceptedInterpretations")
          .addObject()
          .put("precision", "DATE_ONLY")
          .put("value", "2026-11-" + String.format("%02d", index + 10))
          .put("timeSpecified", false);
      mention.putArray("ambiguityReasons");
      mention.put("primary", index == 0);
      if (index < 5) {
        emitted.add(goldId);
      }
    }
    dates.put("primaryGoldId", "date-1");

    if (count > 5) {
      fixture.put("expectedRoute", "CLOUD_ENRICH");
      fixture.put("analyzerExpectedRoute", "CLOUD_ENRICH");
      fixture.putArray("expectedSignals").add("CANDIDATE_LIMIT_EXCEEDED");
      fixture.putArray("analyzerExpectedSignals").add("CANDIDATE_LIMIT_EXCEEDED");
      ((ArrayNode) mentions)
          .forEach(
              mention ->
                  ((ArrayNode) mention.path("ambiguityReasons")).add("CANDIDATE_LIMIT_EXCEEDED"));
    }
    return fixture;
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
