package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
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

/** Cross-field constraints for an ID-only v3 TASK-due binding overlay over immutable v2 gold. */
final class EvaluationV3BindingGoldIntegrity {
  private static final String OVERLAY_SCHEMA_RESOURCE =
      "/contracts/korean-memo-binding-overlay.schema.json";

  private final ObjectMapper json;
  private final Schema overlaySchema;

  EvaluationV3BindingGoldIntegrity(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
    this.overlaySchema = loadOverlaySchema();
  }

  void validate(
      JsonNode regressionFixtures, JsonNode visibleChallengeFixtures, JsonNode bindingOverlay) {
    try {
      if (!overlaySchema.validate(bindingOverlay).isEmpty()) {
        fail("The binding overlay does not satisfy the strict version-3 schema.");
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "The binding overlay could not be schema-validated.", exception);
    }

    PublicEvaluationRelease release =
        PublicEvaluationRelease.from(json, regressionFixtures, visibleChallengeFixtures);
    require(
        release
            .digestSha256()
            .equals(bindingOverlay.path("baseDataset").path("releaseDigestSha256").asText()),
        "The binding overlay does not pin the exact public version-2 release.");

    Map<String, EvaluationCaseGold> goldByCase = new LinkedHashMap<>();
    addCases(regressionFixtures, goldByCase);
    addCases(visibleChallengeFixtures, goldByCase);

    Map<String, JsonNode> overlayByCase = new LinkedHashMap<>();
    for (JsonNode caseOverlay : bindingOverlay.path("cases")) {
      String caseId = caseOverlay.path("caseId").asText();
      require(
          overlayByCase.put(caseId, caseOverlay) == null,
          "The binding overlay contains a duplicate case ID.");
    }
    require(
        overlayByCase.keySet().equals(goldByCase.keySet()),
        "The binding overlay must cover the exact public case universe.");

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
    Set<String> emittedPreciseDateIds =
        gold.dates().emitted().stream()
            .filter(
                date ->
                    date.acceptedInterpretations().stream()
                        .anyMatch(EvaluationDateSemantic::precise))
            .map(EvaluationDateGold::goldId)
            .collect(Collectors.toSet());

    Map<String, JsonNode> bindingsByItemSet = new LinkedHashMap<>();
    for (JsonNode itemSetBinding : caseOverlay.path("itemSetBindings")) {
      String itemSetId = itemSetBinding.path("itemSetId").asText();
      require(
          bindingsByItemSet.put(itemSetId, itemSetBinding) == null,
          "A case contains a duplicate item-set binding.");
    }
    require(
        bindingsByItemSet.keySet().equals(itemSets.keySet()),
        "Every acceptable item set must have exactly one binding section.");

    for (Map.Entry<String, EvaluationItemGoldSet> entry : itemSets.entrySet()) {
      validateItemSet(
          entry.getValue(), bindingsByItemSet.get(entry.getKey()), emittedPreciseDateIds);
    }
  }

  private void validateItemSet(
      EvaluationItemGoldSet itemSet, JsonNode itemSetBinding, Set<String> emittedPreciseDateIds) {
    Set<String> emittedTaskIds =
        itemSet.emitted().stream()
            .filter(item -> "TASK".equals(item.kind()))
            .map(EvaluationItemGold::goldId)
            .collect(Collectors.toSet());

    JsonNode alternatives = itemSetBinding.path("acceptableAssignmentAlternatives");
    String resolution = itemSetBinding.path("resolution").asText();
    require(
        !("RESOLVED".equals(resolution) && alternatives.size() != 1),
        "RESOLVED binding gold requires exactly one complete assignment alternative.");
    require(
        !("USER_INPUT_NEEDED".equals(resolution) && alternatives.size() < 2),
        "USER_INPUT_NEEDED binding gold requires at least two complete alternatives.");

    Set<String> alternativeIds = new HashSet<>();
    Set<String> semanticAlternatives = new HashSet<>();
    for (JsonNode alternative : alternatives) {
      require(
          alternativeIds.add(alternative.path("alternativeId").asText()),
          "Binding alternative IDs must be unique within an item set.");
      Map<String, String> assignments = new HashMap<>();
      for (JsonNode assignment : alternative.path("assignments")) {
        String itemGoldId = assignment.path("itemGoldId").asText();
        JsonNode dueDate = assignment.path("dueDateGoldId");
        String dueDateGoldId = dueDate.isNull() ? null : dueDate.asText();
        require(
            !assignments.containsKey(itemGoldId),
            "A binding alternative contains a duplicate TASK assignment.");
        assignments.put(itemGoldId, dueDateGoldId);
        require(
            emittedTaskIds.contains(itemGoldId),
            "A binding assignment must reference an emitted TASK in its own item set.");
        require(
            dueDateGoldId == null || emittedPreciseDateIds.contains(dueDateGoldId),
            "A non-null due binding must reference an emitted date with a precise interpretation.");
      }
      require(
          assignments.keySet().equals(emittedTaskIds),
          "Each binding alternative must cover every emitted TASK exactly once.");
      require(
          semanticAlternatives.add(semanticKey(assignments)),
          "Semantically duplicate binding alternatives are not allowed.");
    }
  }

  private String semanticKey(Map<String, String> assignments) {
    List<String> values = new ArrayList<>();
    for (String itemId : new TreeSet<>(assignments.keySet())) {
      String dueDateId = assignments.get(itemId);
      values.add(itemId + "=" + (dueDateId == null ? "<null>" : dueDateId));
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
        throw new IllegalStateException("The version-3 binding overlay schema is missing.");
      }
      Schema schema = registry.getSchema(input);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "The version-3 binding overlay schema could not be loaded.", exception);
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
