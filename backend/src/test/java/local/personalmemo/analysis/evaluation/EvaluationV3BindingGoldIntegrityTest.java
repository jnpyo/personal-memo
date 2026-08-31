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

class EvaluationV3BindingGoldIntegrityTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String OVERLAY_SCHEMA_RESOURCE =
      "/contracts/korean-memo-binding-overlay.schema.json";

  private final ObjectMapper json = new ObjectMapper();
  private final EvaluationV3BindingGoldIntegrity integrity =
      new EvaluationV3BindingGoldIntegrity(json);

  @Test
  void acceptsACompleteIdOnlyOverlayWithNullPreciseAndSharedDueAssignments() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode overlay = overlay(regression, challenge);

    assignment(overlay, "clear-explicit-task", "set-1", "item-1").put("dueDateGoldId", "date-1");
    assignment(overlay, "conflicting-dates", "set-two-tasks", "item-1")
        .put("dueDateGoldId", "date-1");
    assignment(overlay, "conflicting-dates", "set-two-tasks", "item-2")
        .put("dueDateGoldId", "date-1");

    assertThatCode(() -> integrity.validate(regression, challenge, overlay))
        .doesNotThrowAnyException();
    assertThat(allFieldNames(overlay))
        .doesNotContain("content", "notes", "surfaceText", "title", "action", "object");
  }

  @Test
  void rejectsDigestDriftAndIncompleteCaseCoverage() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode wrongDigest = overlay(regression, challenge);
    ((ObjectNode) wrongDigest.path("baseDataset")).put("releaseDigestSha256", "0".repeat(64));
    assertThatThrownBy(() -> integrity.validate(regression, challenge, wrongDigest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public version-2 release");

    ObjectNode incomplete = overlay(regression, challenge);
    ((ArrayNode) incomplete.path("cases")).remove(0);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, incomplete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact public case universe");
  }

  @Test
  void rejectsMissingDuplicateAndCrossSetTaskAssignments() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode missingTask = overlay(regression, challenge);
    ArrayNode longAssignments = assignments(missingTask, "long-ambiguous-note", "set-1");
    longAssignments.remove(longAssignments.size() - 1);
    assertThatThrownBy(() -> integrity.validate(regression, challenge, missingTask))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover every emitted TASK");

    ObjectNode duplicateTask = overlay(regression, challenge);
    ArrayNode explicitAssignments = assignments(duplicateTask, "clear-explicit-task", "set-1");
    explicitAssignments.add(explicitAssignments.get(0).deepCopy());
    assertThatThrownBy(() -> integrity.validate(regression, challenge, duplicateTask))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate TASK assignment");

    ObjectNode crossSet = overlay(regression, challenge);
    ArrayNode milestoneAssignments =
        assignments(crossSet, "conflicting-dates", "set-one-milestone");
    ((ObjectNode) milestoneAssignments.get(0)).put("itemGoldId", "item-1");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, crossSet))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("own item set");
  }

  @Test
  void rejectsNonTaskAndImpreciseDateReferences() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    ObjectNode nonTask = overlay(regression, challenge);
    assignments(nonTask, "information-only", "set-1")
        .addObject()
        .put("itemGoldId", "item-1")
        .putNull("dueDateGoldId");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, nonTask))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("emitted TASK");

    ObjectNode imprecise = overlay(regression, challenge);
    assignment(imprecise, "new-topic", "set-1", "item-1").put("dueDateGoldId", "date-1");
    assertThatThrownBy(() -> integrity.validate(regression, challenge, imprecise))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("emitted date with a precise interpretation");
  }

  @Test
  void rejectsSemanticallyDuplicateWholeAssignmentAlternatives() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode overlay = overlay(regression, challenge);
    ObjectNode itemSet = itemSetBinding(overlay, "clear-explicit-task", "set-1");
    itemSet.put("resolution", "USER_INPUT_NEEDED");
    ArrayNode alternatives = (ArrayNode) itemSet.path("acceptableAssignmentAlternatives");
    ObjectNode duplicate = (ObjectNode) alternatives.get(0).deepCopy();
    duplicate.put("alternativeId", "alternative-2");
    alternatives.add(duplicate);

    assertThatThrownBy(() -> integrity.validate(regression, challenge, overlay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Semantically duplicate");
  }

  @Test
  void rejectsRawFieldsAtTheStrictOverlayContractBoundary() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);
    ObjectNode overlay = overlay(regression, challenge);
    ((ObjectNode) overlay.path("cases").get(0)).put("content", "not allowed");

    assertThatThrownBy(() -> integrity.validate(regression, challenge, overlay))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("could not be schema-validated");
  }

  @Test
  void overlayContractResourceMatchesRepositoryCopy() throws Exception {
    JsonNode resource = readResource(OVERLAY_SCHEMA_RESOURCE);
    JsonNode repository =
        json.readTree(
            Files.readString(
                repositoryPath("contracts/korean-memo-binding-overlay.schema.json"),
                StandardCharsets.UTF_8));
    assertThat(resource).isEqualTo(repository);
  }

  private ObjectNode overlay(JsonNode regression, JsonNode challenge) {
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode overlay =
        json.createObjectNode()
            .put("overlaySchemaVersion", "1")
            .put("datasetVersion", "3")
            .put("overlayKind", "PUBLIC_TASK_DUE_BINDING")
            .put("bindingLabelPolicyVersion", "test-only-binding-policy");
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
            if ("TASK".equals(item.path("kind").asText())) {
              assignments
                  .addObject()
                  .put("itemGoldId", emittedId.asText())
                  .putNull("dueDateGoldId");
            }
          }
        }
      }
    }
    return overlay;
  }

  private JsonNode itemById(JsonNode itemSet, String itemId) {
    for (JsonNode item : itemSet.path("allItems")) {
      if (itemId.equals(item.path("goldId").asText())) {
        return item;
      }
    }
    throw new IllegalArgumentException("Unknown test item ID: " + itemId);
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

  private ArrayNode assignments(ObjectNode overlay, String caseId, String itemSetId) {
    return (ArrayNode)
        itemSetBinding(overlay, caseId, itemSetId)
            .path("acceptableAssignmentAlternatives")
            .get(0)
            .path("assignments");
  }

  private ObjectNode assignment(
      ObjectNode overlay, String caseId, String itemSetId, String itemGoldId) {
    for (JsonNode assignment : assignments(overlay, caseId, itemSetId)) {
      if (itemGoldId.equals(assignment.path("itemGoldId").asText())) {
        return (ObjectNode) assignment;
      }
    }
    throw new IllegalArgumentException("Unknown test TASK ID: " + itemGoldId);
  }

  private JsonNode caseBinding(ObjectNode overlay, String caseId) {
    for (JsonNode caseBinding : overlay.path("cases")) {
      if (caseId.equals(caseBinding.path("caseId").asText())) {
        return caseBinding;
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
