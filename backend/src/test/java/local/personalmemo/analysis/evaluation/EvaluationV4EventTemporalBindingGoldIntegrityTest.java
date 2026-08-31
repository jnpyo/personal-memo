package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EvaluationV4EventTemporalBindingGoldIntegrityTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String OVERLAY_SCHEMA_RESOURCE =
      "/contracts/korean-memo-event-temporal-binding-overlay.schema.json";

  private final ObjectMapper json = new ObjectMapper();
  private final EvaluationV4EventTemporalBindingGoldIntegrity integrity =
      new EvaluationV4EventTemporalBindingGoldIntegrity(json);

  @Test
  void acceptsCompleteIdOnlyNullTimedAndAllDayAssignments() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode overlay = overlay(regression, challenge);

    schedule(
        assignment(overlay, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        null,
        null);
    schedule(
        assignment(overlay, "challenge-relative-event", "set-1", "item-1"),
        "TIMED",
        "date-1",
        null,
        null);

    assertThatCode(() -> integrity.validate(regression, challenge, overlay))
        .doesNotThrowAnyException();
    assertThat(allFieldNames(overlay))
        .doesNotContain("content", "notes", "surfaceText", "title", "action", "object");
    assertThat(assignment(overlay, "past-event", "set-1", "item-1").path("schedule").isNull())
        .isTrue();
  }

  @Test
  void normalizesInclusiveAllDayEndAndRejectsZeroRangeOrCalendarOverflow() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode inclusive = overlay(regression, challenge);
    schedule(
        assignment(inclusive, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        "date-1",
        "INCLUSIVE_THROUGH_VALUE");
    assertThatCode(() -> integrity.validate(regression, challenge, inclusive))
        .doesNotThrowAnyException();

    ObjectNode exclusive = overlay(regression, challenge);
    schedule(
        assignment(exclusive, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        "date-1",
        "EXCLUSIVE_AT_VALUE");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, exclusive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized ALL_DAY EVENT end");

    ArrayNode overflowChallenge = (ArrayNode) challenge.deepCopy();
    ((ObjectNode)
            findCase(overflowChallenge, "challenge-calendar-event")
                .at("/expectedDates/mentions/0/acceptedInterpretations/0"))
        .put("value", "9999-12-31");
    ObjectNode overflow = overlay(regression, overflowChallenge);
    schedule(
        assignment(overflow, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        "date-1",
        "INCLUSIVE_THROUGH_VALUE");
    assertThatThrownBy(() -> integrity.validate(regression, overflowChallenge, overflow))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overflows the ISO calendar-date boundary");
  }

  @Test
  void acceptsTimedExactRelativeRangeAndRejectsInclusiveOrReversedEnd() throws Exception {
    ArrayNode regression = (ArrayNode) fixtures(REGRESSION_RESOURCE).deepCopy();
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    convertConflictingDatesToTimedEvents(regression);

    ObjectNode valid = overlay(regression, challenge);
    schedule(
        assignment(valid, "conflicting-dates", "set-two-tasks", "item-1"),
        "TIMED",
        "date-1",
        "date-2",
        "EXCLUSIVE_AT_VALUE");
    assertThatCode(() -> integrity.validate(regression, challenge, valid))
        .doesNotThrowAnyException();

    ObjectNode inclusive = overlay(regression, challenge);
    schedule(
        assignment(inclusive, "conflicting-dates", "set-two-tasks", "item-1"),
        "TIMED",
        "date-1",
        "date-2",
        "INCLUSIVE_THROUGH_VALUE");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, inclusive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TIMED EVENT end must use EXCLUSIVE_AT_VALUE");

    ObjectNode reversed = overlay(regression, challenge);
    schedule(
        assignment(reversed, "conflicting-dates", "set-two-tasks", "item-1"),
        "TIMED",
        "date-2",
        "date-1",
        "EXCLUSIVE_AT_VALUE");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, reversed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TIMED EVENT end interpretation");
  }

  @Test
  void rejectsDigestCaseAndItemSetCoverageDrift() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode wrongDigest = overlay(regression, challenge);
    ((ObjectNode) wrongDigest.path("baseDataset")).put("releaseDigestSha256", "0".repeat(64));
    assertThatThrownBy(() -> integrity.validate(regression, challenge, wrongDigest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public version-2 release");

    ObjectNode incompleteCases = overlay(regression, challenge);
    ((ArrayNode) incompleteCases.path("cases")).remove(0);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, incompleteCases))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public case universe");

    ObjectNode incompleteItemSets = overlay(regression, challenge);
    ((ArrayNode) caseBinding(incompleteItemSets, "conflicting-dates").path("itemSetBindings"))
        .remove(0);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, incompleteItemSets))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one EVENT binding section");
  }

  @Test
  void rejectsMissingDuplicateAndNonEventAssignments() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode missing = overlay(regression, challenge);
    assignments(missing, "challenge-calendar-event", "set-1").remove(0);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover every emitted EVENT");

    ObjectNode duplicate = overlay(regression, challenge);
    ArrayNode duplicateAssignments = assignments(duplicate, "challenge-calendar-event", "set-1");
    duplicateAssignments.add(duplicateAssignments.get(0).deepCopy());
    assertThatThrownBy(() -> integrity.validate(regression, challenge, duplicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate EVENT assignment");

    ObjectNode nonEvent = overlay(regression, challenge);
    assignments(nonEvent, "information-only", "set-1")
        .addObject()
        .put("itemGoldId", "item-1")
        .putNull("schedule");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, nonEvent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("emitted EVENT in its own item set");
  }

  @Test
  void rejectsDanglingAndModeIncompatibleDateReferences() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode dangling = overlay(regression, challenge);
    schedule(
        assignment(dangling, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-missing",
        null,
        null);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, dangling))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("start must reference an emitted date");

    ObjectNode dateOnlyAsTimed = overlay(regression, challenge);
    schedule(
        assignment(dateOnlyAsTimed, "challenge-calendar-event", "set-1", "item-1"),
        "TIMED",
        "date-1",
        null,
        null);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, dateOnlyAsTimed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TIMED EVENT start");

    ObjectNode timedAsAllDay = overlay(regression, challenge);
    schedule(
        assignment(timedAsAllDay, "challenge-relative-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        null,
        null);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, timedAsAllDay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ALL_DAY EVENT start");
  }

  @Test
  void rejectsMixedAcceptedInterpretationsInsteadOfDiscardingModeIncompatibleValues()
      throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ArrayNode mixedAllDayChallenge = (ArrayNode) challenge.deepCopy();
    ObjectNode allDayCase = (ObjectNode) findCase(mixedAllDayChallenge, "challenge-calendar-event");
    allDayCase.put("expectedRoute", "CLOUD_ENRICH");
    ((ArrayNode) allDayCase.at("/expectedDates/mentions/0/acceptedInterpretations"))
        .addObject()
        .put("precision", "EXACT_TIME")
        .put("value", "2026-10-03T10:00:00+09:00")
        .put("timeSpecified", true);
    ObjectNode mixedAllDayOverlay = overlay(regression, mixedAllDayChallenge);
    schedule(
        assignment(mixedAllDayOverlay, "challenge-calendar-event", "set-1", "item-1"),
        "ALL_DAY",
        "date-1",
        null,
        null);

    assertThatThrownBy(
            () -> integrity.validate(regression, mixedAllDayChallenge, mixedAllDayOverlay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ALL_DAY EVENT start must reference only DATE_ONLY");

    ArrayNode mixedTimedRegression = (ArrayNode) regression.deepCopy();
    convertConflictingDatesToTimedEvents(mixedTimedRegression);
    ArrayNode mixedTimedEnd =
        (ArrayNode)
            findCase(mixedTimedRegression, "conflicting-dates")
                .at("/expectedDates/mentions/1/acceptedInterpretations");
    mixedTimedEnd
        .addObject()
        .put("precision", "DATE_ONLY")
        .put("value", "2026-11-20")
        .put("timeSpecified", false);
    ObjectNode mixedTimedOverlay = overlay(mixedTimedRegression, challenge);
    schedule(
        assignment(mixedTimedOverlay, "conflicting-dates", "set-two-tasks", "item-1"),
        "TIMED",
        "date-1",
        "date-2",
        "EXCLUSIVE_AT_VALUE");

    assertThatThrownBy(() -> integrity.validate(mixedTimedRegression, challenge, mixedTimedOverlay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TIMED EVENT end must reference only EXACT_TIME or RELATIVE_EXACT");
  }

  @Test
  void enforcesResolutionCardinalityAndUniqueWholeAlternatives() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode resolvedWithTwo = overlay(regression, challenge);
    ObjectNode resolvedItemSet =
        itemSetBinding(resolvedWithTwo, "challenge-calendar-event", "set-1");
    ArrayNode resolvedAlternatives =
        (ArrayNode) resolvedItemSet.path("acceptableAssignmentAlternatives");
    ObjectNode second = (ObjectNode) resolvedAlternatives.get(0).deepCopy();
    second.put("alternativeId", "alternative-2");
    resolvedAlternatives.add(second);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, resolvedWithTwo))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");

    ObjectNode duplicateAlternatives = overlay(regression, challenge);
    ObjectNode ambiguousItemSet =
        itemSetBinding(duplicateAlternatives, "challenge-calendar-event", "set-1");
    ambiguousItemSet.put("resolution", "USER_INPUT_NEEDED");
    ArrayNode ambiguousAlternatives =
        (ArrayNode) ambiguousItemSet.path("acceptableAssignmentAlternatives");
    ObjectNode duplicate = (ObjectNode) ambiguousAlternatives.get(0).deepCopy();
    duplicate.put("alternativeId", "alternative-2");
    ambiguousAlternatives.add(duplicate);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, duplicateAlternatives))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Semantically duplicate EVENT binding alternatives");

    ObjectNode validAmbiguity = overlay(regression, challenge);
    ObjectNode validItemSet = itemSetBinding(validAmbiguity, "challenge-calendar-event", "set-1");
    validItemSet.put("resolution", "USER_INPUT_NEEDED");
    ArrayNode validAlternatives = (ArrayNode) validItemSet.path("acceptableAssignmentAlternatives");
    ObjectNode scheduled = (ObjectNode) validAlternatives.get(0).deepCopy();
    scheduled.put("alternativeId", "alternative-2");
    schedule((ObjectNode) scheduled.path("assignments").get(0), "ALL_DAY", "date-1", null, null);
    validAlternatives.add(scheduled);
    assertThatCode(() -> integrity.validate(regression, challenge, validAmbiguity))
        .doesNotThrowAnyException();

    ArrayNode sameInstantRegression = (ArrayNode) regression.deepCopy();
    convertConflictingDatesToTimedEvents(sameInstantRegression);
    ((ObjectNode)
            findCase(sameInstantRegression, "conflicting-dates")
                .at("/expectedDates/mentions/1/acceptedInterpretations/0"))
        .put("value", "2026-11-20T14:00:00+09:00");
    ObjectNode sameInstantAlternatives = overlay(sameInstantRegression, challenge);
    ObjectNode sameInstantItemSet =
        itemSetBinding(sameInstantAlternatives, "conflicting-dates", "set-two-tasks");
    sameInstantItemSet.put("resolution", "USER_INPUT_NEEDED");
    ArrayNode alternatives =
        (ArrayNode) sameInstantItemSet.path("acceptableAssignmentAlternatives");
    schedule(
        assignment(sameInstantAlternatives, "conflicting-dates", "set-two-tasks", "item-1"),
        "TIMED",
        "date-1",
        null,
        null);
    ObjectNode equivalent = (ObjectNode) alternatives.get(0).deepCopy();
    equivalent.put("alternativeId", "alternative-2");
    for (JsonNode candidateAssignment : equivalent.path("assignments")) {
      if ("item-1".equals(candidateAssignment.path("itemGoldId").asText())) {
        schedule((ObjectNode) candidateAssignment, "TIMED", "date-2", null, null);
      }
    }
    alternatives.add(equivalent);
    assertThatThrownBy(
            () -> integrity.validate(sameInstantRegression, challenge, sameInstantAlternatives))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Semantically duplicate EVENT binding alternatives");
  }

  @Test
  void rejectsRawFieldsAndScheduleWithoutExplicitEnd() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode raw = overlay(regression, challenge);
    ((ObjectNode) raw.path("cases").get(0)).put("content", "not allowed");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");

    ObjectNode inferredEnd = overlay(regression, challenge);
    ObjectNode eventAssignment =
        assignment(inferredEnd, "challenge-relative-event", "set-1", "item-1");
    eventAssignment.putObject("schedule").put("mode", "TIMED").put("startDateGoldId", "date-1");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, inferredEnd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");
  }

  @Test
  void eventOverlayContractResourceMatchesRepositoryCopy() throws Exception {
    JsonNode resource = readResource(OVERLAY_SCHEMA_RESOURCE);
    JsonNode repository =
        json.readTree(
            Files.readString(
                repositoryPath("contracts/korean-memo-event-temporal-binding-overlay.schema.json"),
                StandardCharsets.UTF_8));
    assertThat(resource).isEqualTo(repository);
  }

  private ObjectNode overlay(JsonNode regression, JsonNode challenge) {
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode overlay =
        json.createObjectNode()
            .put("overlaySchemaVersion", "1")
            .put("datasetVersion", "4")
            .put("overlayKind", "PUBLIC_EVENT_TEMPORAL_BINDING")
            .put("bindingLabelPolicyVersion", "test-only-event-binding-policy");
    overlay
        .putObject("baseDataset")
        .put("datasetVersion", "2")
        .put("releaseId", "public-v2-test-release")
        .put("releaseDigestSha256", digest)
        .put("adjudicationPolicyVersion", "test-only-v2-review-policy");
    ArrayNode cases = overlay.putArray("cases");
    for (JsonNode fixtures : List.of(regression, challenge)) {
      for (JsonNode fixture : fixtures) {
        ObjectNode caseOverlay = cases.addObject().put("caseId", fixture.path("id").asText());
        ArrayNode itemSetBindings = caseOverlay.putArray("itemSetBindings");
        for (JsonNode itemSet : fixture.path("expectedItems").path("acceptableSets")) {
          ObjectNode binding =
              itemSetBindings
                  .addObject()
                  .put("itemSetId", itemSet.path("setId").asText())
                  .put("resolution", "RESOLVED");
          ArrayNode assignments =
              binding
                  .putArray("acceptableAssignmentAlternatives")
                  .addObject()
                  .put("alternativeId", "alternative-1")
                  .putArray("assignments");
          for (JsonNode emittedId : itemSet.path("emittedItemGoldIds")) {
            JsonNode item = itemById(itemSet, emittedId.asText());
            if ("EVENT".equals(item.path("kind").asText())) {
              assignments.addObject().put("itemGoldId", emittedId.asText()).putNull("schedule");
            }
          }
        }
      }
    }
    return overlay;
  }

  private void convertConflictingDatesToTimedEvents(ArrayNode regression) {
    ObjectNode fixture = (ObjectNode) findCase(regression, "conflicting-dates");
    ArrayNode expectedTypes = (ArrayNode) fixture.path("expectedTypes");
    expectedTypes.removeAll().add("EVENT");
    for (JsonNode mention : fixture.path("expectedDates").path("mentions")) {
      ObjectNode interpretation = (ObjectNode) mention.path("acceptedInterpretations").get(0);
      if ("date-1".equals(mention.path("goldId").asText())) {
        interpretation
            .put("precision", "EXACT_TIME")
            .put("value", "2026-11-20T14:00:00+09:00")
            .put("timeSpecified", true);
      } else {
        interpretation
            .put("precision", "RELATIVE_EXACT")
            .put("value", "2026-11-20T15:00:00+09:00")
            .put("timeSpecified", true);
      }
    }
    for (JsonNode itemSet : fixture.path("expectedItems").path("acceptableSets")) {
      for (JsonNode item : itemSet.path("allItems")) {
        ((ObjectNode) item).put("kind", "EVENT");
      }
    }
  }

  private void schedule(
      ObjectNode assignment,
      String mode,
      String startDateGoldId,
      String endDateGoldId,
      String boundary) {
    ObjectNode schedule =
        assignment.putObject("schedule").put("mode", mode).put("startDateGoldId", startDateGoldId);
    if (endDateGoldId == null) {
      schedule.putNull("end");
    } else {
      schedule.putObject("end").put("dateGoldId", endDateGoldId).put("boundary", boundary);
    }
  }

  private ObjectNode assignment(
      ObjectNode overlay, String caseId, String itemSetId, String itemGoldId) {
    for (JsonNode assignment : assignments(overlay, caseId, itemSetId)) {
      if (itemGoldId.equals(assignment.path("itemGoldId").asText())) {
        return (ObjectNode) assignment;
      }
    }
    throw new IllegalArgumentException("Unknown test EVENT ID: " + itemGoldId);
  }

  private ArrayNode assignments(ObjectNode overlay, String caseId, String itemSetId) {
    return (ArrayNode)
        itemSetBinding(overlay, caseId, itemSetId)
            .path("acceptableAssignmentAlternatives")
            .get(0)
            .path("assignments");
  }

  private ObjectNode itemSetBinding(ObjectNode overlay, String caseId, String itemSetId) {
    JsonNode caseBinding = caseBinding(overlay, caseId);
    for (JsonNode binding : caseBinding.path("itemSetBindings")) {
      if (itemSetId.equals(binding.path("itemSetId").asText())) {
        return (ObjectNode) binding;
      }
    }
    throw new IllegalArgumentException("Unknown test item-set ID: " + itemSetId);
  }

  private JsonNode caseBinding(ObjectNode overlay, String caseId) {
    for (JsonNode caseBinding : overlay.path("cases")) {
      if (caseId.equals(caseBinding.path("caseId").asText())) {
        return caseBinding;
      }
    }
    throw new IllegalArgumentException("Unknown test case ID: " + caseId);
  }

  private JsonNode itemById(JsonNode itemSet, String itemId) {
    for (JsonNode item : itemSet.path("allItems")) {
      if (itemId.equals(item.path("goldId").asText())) {
        return item;
      }
    }
    throw new IllegalArgumentException("Unknown test item ID: " + itemId);
  }

  private JsonNode findCase(JsonNode fixtures, String caseId) {
    for (JsonNode fixture : fixtures) {
      if (caseId.equals(fixture.path("id").asText())) {
        return fixture;
      }
    }
    throw new IllegalArgumentException("Unknown test case ID: " + caseId);
  }

  private JsonNode fixtures(String resource) throws Exception {
    JsonNode value = readResource(resource);
    assertThat(value.isArray()).isTrue();
    return value;
  }

  private Set<String> allFieldNames(JsonNode value) {
    Set<String> names = new HashSet<>();
    collectFieldNames(value, names);
    return names;
  }

  private void collectFieldNames(JsonNode value, Set<String> names) {
    if (value.isObject()) {
      names.addAll(value.propertyNames());
      for (JsonNode child : value) {
        collectFieldNames(child, names);
      }
    } else if (value.isArray()) {
      for (JsonNode child : value) {
        collectFieldNames(child, names);
      }
    }
  }

  private JsonNode readResource(String resource) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Test resource is missing: " + resource);
      }
      return json.readTree(input);
    }
  }

  private Path repositoryPath(String relativePath) {
    Path fromBackend = Path.of("..", relativePath);
    if (Files.exists(fromBackend)) {
      return fromBackend;
    }
    Path fromRoot = Path.of(relativePath);
    if (Files.exists(fromRoot)) {
      return fromRoot;
    }
    throw new IllegalStateException("Repository file is missing: " + relativePath);
  }
}
