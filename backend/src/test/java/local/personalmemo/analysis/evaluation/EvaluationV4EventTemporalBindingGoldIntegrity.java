package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Structural checks for an ID-only EVENT temporal-binding overlay over immutable v2 gold. */
final class EvaluationV4EventTemporalBindingGoldIntegrity {
  private static final String OVERLAY_SCHEMA_RESOURCE =
      "/contracts/korean-memo-event-temporal-binding-overlay.schema.json";
  private static final String TIMED = "TIMED";
  private static final String ALL_DAY = "ALL_DAY";
  private static final String EXCLUSIVE_AT_VALUE = "EXCLUSIVE_AT_VALUE";
  private static final String INCLUSIVE_THROUGH_VALUE = "INCLUSIVE_THROUGH_VALUE";

  private final ObjectMapper json;
  private final Schema overlaySchema;

  EvaluationV4EventTemporalBindingGoldIntegrity(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
    this.overlaySchema = loadOverlaySchema();
  }

  void validate(
      JsonNode regressionFixtures, JsonNode visibleChallengeFixtures, JsonNode bindingOverlay) {
    try {
      if (!overlaySchema.validate(bindingOverlay).isEmpty()) {
        fail("The EVENT temporal-binding overlay does not satisfy the strict schema.");
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "The EVENT temporal-binding overlay could not be schema-validated.", exception);
    }

    PublicEvaluationRelease release =
        PublicEvaluationRelease.from(json, regressionFixtures, visibleChallengeFixtures);
    require(
        release
            .digestSha256()
            .equals(bindingOverlay.path("baseDataset").path("releaseDigestSha256").asText()),
        "The EVENT temporal-binding overlay does not pin the exact public version-2 release.");

    Map<String, EvaluationCaseGold> goldByCase = new LinkedHashMap<>();
    addCases(regressionFixtures, goldByCase);
    addCases(visibleChallengeFixtures, goldByCase);

    Map<String, JsonNode> overlayByCase = new LinkedHashMap<>();
    for (JsonNode caseOverlay : bindingOverlay.path("cases")) {
      String caseId = caseOverlay.path("caseId").asText();
      require(
          overlayByCase.put(caseId, caseOverlay) == null,
          "The EVENT temporal-binding overlay contains a duplicate case ID.");
    }
    require(
        overlayByCase.keySet().equals(goldByCase.keySet()),
        "The EVENT temporal-binding overlay must cover the exact public case universe.");

    for (Map.Entry<String, EvaluationCaseGold> entry : goldByCase.entrySet()) {
      validateCase(entry.getValue(), overlayByCase.get(entry.getKey()));
    }
  }

  private void addCases(JsonNode fixtures, Map<String, EvaluationCaseGold> goldByCase) {
    for (JsonNode fixture : fixtures) {
      EvaluationCaseGold gold = EvaluationV2GoldIntegrity.validate(fixture);
      require(
          goldByCase.put(gold.id(), gold) == null,
          "The public release contains a duplicate case ID.");
    }
  }

  private void validateCase(EvaluationCaseGold gold, JsonNode caseOverlay) {
    Map<String, EvaluationItemGoldSet> itemSets =
        gold.items().acceptableSets().stream()
            .collect(
                Collectors.toMap(
                    EvaluationItemGoldSet::setId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, EvaluationDateGold> emittedDates =
        gold.dates().emitted().stream()
            .collect(
                Collectors.toMap(
                    EvaluationDateGold::goldId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));

    Map<String, JsonNode> bindingsByItemSet = new LinkedHashMap<>();
    for (JsonNode itemSetBinding : caseOverlay.path("itemSetBindings")) {
      String itemSetId = itemSetBinding.path("itemSetId").asText();
      require(
          bindingsByItemSet.put(itemSetId, itemSetBinding) == null,
          "A case contains a duplicate EVENT item-set binding.");
    }
    require(
        bindingsByItemSet.keySet().equals(itemSets.keySet()),
        "Every acceptable item set must have exactly one EVENT binding section.");

    for (Map.Entry<String, EvaluationItemGoldSet> entry : itemSets.entrySet()) {
      validateItemSet(entry.getValue(), bindingsByItemSet.get(entry.getKey()), emittedDates);
    }
  }

  private void validateItemSet(
      EvaluationItemGoldSet itemSet,
      JsonNode itemSetBinding,
      Map<String, EvaluationDateGold> emittedDates) {
    Set<String> emittedEventIds =
        itemSet.emitted().stream()
            .filter(item -> "EVENT".equals(item.kind()))
            .map(EvaluationItemGold::goldId)
            .collect(Collectors.toSet());

    JsonNode alternatives = itemSetBinding.path("acceptableAssignmentAlternatives");
    String resolution = itemSetBinding.path("resolution").asText();
    require(
        !("RESOLVED".equals(resolution) && alternatives.size() != 1),
        "RESOLVED EVENT binding gold requires exactly one complete assignment alternative.");
    require(
        !("USER_INPUT_NEEDED".equals(resolution) && alternatives.size() < 2),
        "USER_INPUT_NEEDED EVENT binding gold requires at least two complete alternatives.");

    Set<String> alternativeIds = new HashSet<>();
    Set<String> semanticAlternatives = new HashSet<>();
    for (JsonNode alternative : alternatives) {
      require(
          alternativeIds.add(alternative.path("alternativeId").asText()),
          "EVENT binding alternative IDs must be unique within an item set.");
      Map<String, String> assignments = new HashMap<>();
      for (JsonNode assignment : alternative.path("assignments")) {
        String itemGoldId = assignment.path("itemGoldId").asText();
        require(
            !assignments.containsKey(itemGoldId),
            "An EVENT binding alternative contains a duplicate EVENT assignment.");
        require(
            emittedEventIds.contains(itemGoldId),
            "An EVENT binding assignment must reference an emitted EVENT in its own item set.");
        assignments.put(itemGoldId, validateSchedule(assignment.path("schedule"), emittedDates));
      }
      require(
          assignments.keySet().equals(emittedEventIds),
          "Each EVENT binding alternative must cover every emitted EVENT exactly once.");
      require(
          semanticAlternatives.add(semanticKey(assignments)),
          "Semantically duplicate EVENT binding alternatives are not allowed.");
    }
  }

  private String validateSchedule(JsonNode schedule, Map<String, EvaluationDateGold> emittedDates) {
    if (schedule.isNull()) {
      return "<null>";
    }

    String mode = schedule.path("mode").asText();
    String startDateGoldId = schedule.path("startDateGoldId").asText();
    EvaluationDateGold startDate = emittedDates.get(startDateGoldId);
    require(
        startDate != null,
        "An EVENT schedule start must reference an emitted date in its own case.");

    JsonNode end = schedule.path("end");
    if (TIMED.equals(mode)) {
      require(
          timedInterpretationsOnly(startDate),
          "A TIMED EVENT start must reference only EXACT_TIME or RELATIVE_EXACT gold.");
      List<Instant> starts = timedValues(startDate);
      require(
          !starts.isEmpty(),
          "A TIMED EVENT start must reference EXACT_TIME or RELATIVE_EXACT gold.");
      if (end.isNull()) {
        return TIMED + "|start=" + instantSetKey(starts) + "|end=<null>";
      }

      require(
          EXCLUSIVE_AT_VALUE.equals(end.path("boundary").asText()),
          "A TIMED EVENT end must use EXCLUSIVE_AT_VALUE.");
      String endDateGoldId = end.path("dateGoldId").asText();
      EvaluationDateGold endDate = emittedDates.get(endDateGoldId);
      require(
          endDate != null, "An EVENT schedule end must reference an emitted date in its own case.");
      require(
          timedInterpretationsOnly(endDate),
          "A TIMED EVENT end must reference only EXACT_TIME or RELATIVE_EXACT gold.");
      List<Instant> ends = timedValues(endDate);
      require(
          !ends.isEmpty(), "A TIMED EVENT end must reference EXACT_TIME or RELATIVE_EXACT gold.");
      for (Instant start : starts) {
        for (Instant normalizedEnd : ends) {
          require(
              normalizedEnd.isAfter(start),
              "Every accepted TIMED EVENT end interpretation must be after every accepted start.");
        }
      }
      return TIMED + "|start=" + instantSetKey(starts) + "|end=" + instantSetKey(ends);
    }

    require(ALL_DAY.equals(mode), "An EVENT schedule uses an unsupported mode.");
    require(
        allDayInterpretationsOnly(startDate),
        "An ALL_DAY EVENT start must reference only DATE_ONLY gold.");
    List<LocalDate> starts = allDayValues(startDate);
    require(!starts.isEmpty(), "An ALL_DAY EVENT start must reference DATE_ONLY gold.");
    if (end.isNull()) {
      return ALL_DAY + "|start=" + localDateSetKey(starts) + "|end=<null>";
    }

    String boundary = end.path("boundary").asText();
    require(
        EXCLUSIVE_AT_VALUE.equals(boundary) || INCLUSIVE_THROUGH_VALUE.equals(boundary),
        "An ALL_DAY EVENT end uses an unsupported boundary.");
    String endDateGoldId = end.path("dateGoldId").asText();
    EvaluationDateGold endDate = emittedDates.get(endDateGoldId);
    require(
        endDate != null, "An EVENT schedule end must reference an emitted date in its own case.");
    require(
        allDayInterpretationsOnly(endDate),
        "An ALL_DAY EVENT end must reference only DATE_ONLY gold.");
    List<LocalDate> ends = allDayValues(endDate);
    require(!ends.isEmpty(), "An ALL_DAY EVENT end must reference DATE_ONLY gold.");
    List<LocalDate> normalizedEnds =
        ends.stream().map(value -> normalizeAllDayEnd(value, boundary)).toList();
    for (LocalDate start : starts) {
      for (LocalDate normalizedEnd : normalizedEnds) {
        require(
            normalizedEnd.isAfter(start),
            "Every normalized ALL_DAY EVENT end must be after every accepted start.");
      }
    }
    return ALL_DAY
        + "|start="
        + localDateSetKey(starts)
        + "|end="
        + localDateSetKey(normalizedEnds);
  }

  private String instantSetKey(List<Instant> values) {
    return values.stream()
        .distinct()
        .sorted()
        .map(Instant::toString)
        .collect(Collectors.joining(","));
  }

  private String localDateSetKey(List<LocalDate> values) {
    return values.stream()
        .distinct()
        .sorted()
        .map(LocalDate::toString)
        .collect(Collectors.joining(","));
  }

  private List<Instant> timedValues(EvaluationDateGold date) {
    List<Instant> values = new ArrayList<>();
    for (EvaluationDateSemantic interpretation : date.acceptedInterpretations()) {
      if (("EXACT_TIME".equals(interpretation.precision())
              || "RELATIVE_EXACT".equals(interpretation.precision()))
          && interpretation.normalizedValue() != null) {
        values.add(Instant.parse(interpretation.normalizedValue()));
      }
    }
    return List.copyOf(values);
  }

  private List<LocalDate> allDayValues(EvaluationDateGold date) {
    List<LocalDate> values = new ArrayList<>();
    for (EvaluationDateSemantic interpretation : date.acceptedInterpretations()) {
      if ("DATE_ONLY".equals(interpretation.precision())
          && interpretation.normalizedValue() != null) {
        values.add(LocalDate.parse(interpretation.normalizedValue()));
      }
    }
    return List.copyOf(values);
  }

  private boolean timedInterpretationsOnly(EvaluationDateGold date) {
    return date.acceptedInterpretations().stream()
        .allMatch(
            interpretation ->
                ("EXACT_TIME".equals(interpretation.precision())
                        || "RELATIVE_EXACT".equals(interpretation.precision()))
                    && interpretation.normalizedValue() != null);
  }

  private boolean allDayInterpretationsOnly(EvaluationDateGold date) {
    return date.acceptedInterpretations().stream()
        .allMatch(
            interpretation ->
                "DATE_ONLY".equals(interpretation.precision())
                    && interpretation.normalizedValue() != null);
  }

  private LocalDate normalizeAllDayEnd(LocalDate value, String boundary) {
    if (EXCLUSIVE_AT_VALUE.equals(boundary)) {
      return value;
    }
    try {
      LocalDate normalized = value.plusDays(1);
      require(
          normalized.getYear() >= 0 && normalized.getYear() <= 9999,
          "An inclusive ALL_DAY EVENT end overflows the ISO calendar-date boundary.");
      return normalized;
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException(
          "An inclusive ALL_DAY EVENT end overflows the ISO calendar-date boundary.", exception);
    }
  }

  private String semanticKey(Map<String, String> assignments) {
    List<String> values = new ArrayList<>();
    for (String itemId : new TreeSet<>(assignments.keySet())) {
      values.add(itemId + "=" + assignments.get(itemId));
    }
    return String.join("|", values);
  }

  private Schema loadOverlaySchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try (InputStream input = getClass().getResourceAsStream(OVERLAY_SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("The EVENT temporal-binding overlay schema is missing.");
      }
      Schema schema = registry.getSchema(input);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "The EVENT temporal-binding overlay schema could not be loaded.", exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      fail(message);
    }
  }

  private static void fail(String message) {
    throw new IllegalArgumentException(message);
  }
}
