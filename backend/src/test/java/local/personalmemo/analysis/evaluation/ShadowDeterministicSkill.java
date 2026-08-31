package local.personalmemo.analysis.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test-only deterministic projection over an already validated FakeAnalyzer proposal.
 *
 * <p>The skill never reads fixture identifiers, expected values, notes, a database, or canonical
 * application state. The model can select only an existing item title. Every other proposal field
 * remains an exact deep copy of the authoritative deterministic proposal.
 */
final class ShadowDeterministicSkill {
  static final String EVIDENCE_VERSION = "solo-liquidai-shadow-skill-evidence-v1";
  static final String SELECTION_VERSION = "solo-liquidai-shadow-skill-selection-v1";
  static final int MAX_ITEMS = 3;
  static final int MAX_EVIDENCE_BYTES = 8 * 1024;

  private static final Set<String> SELECTION_FIELDS =
      Set.of("schemaVersion", "primaryItemOrdinal", "topicObjectOrdinals");

  private final ObjectMapper json;
  private final Draft202012AnalysisProposalSchemaValidator schemaValidator =
      new Draft202012AnalysisProposalSchemaValidator();
  private final AnalysisProposalValidator domainValidator = new AnalysisProposalValidator();

  ShadowDeterministicSkill(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  SkillProjection project(
      ObjectNode authoritativeProposal,
      UUID memoId,
      int memoRevision,
      String memoText,
      AnalysisProvenance authoritativeProvenance,
      String routingPolicyVersion) {
    Objects.requireNonNull(authoritativeProposal, "authoritativeProposal");
    Objects.requireNonNull(memoId, "memoId");
    Objects.requireNonNull(memoText, "memoText");
    Objects.requireNonNull(authoritativeProvenance, "authoritativeProvenance");
    Objects.requireNonNull(routingPolicyVersion, "routingPolicyVersion");

    schemaValidator.validate(authoritativeProposal);
    domainValidator.validate(
        authoritativeProposal,
        memoId,
        memoRevision,
        memoText,
        authoritativeProvenance,
        routingPolicyVersion);

    JsonNode candidates = authoritativeProposal.path("itemCandidates");
    require(candidates.isArray() && candidates.size() <= MAX_ITEMS, "invalid item boundary");
    List<SkillEvidenceItem> items = new ArrayList<>(candidates.size());
    for (int ordinal = 0; ordinal < candidates.size(); ordinal++) {
      JsonNode candidate = candidates.get(ordinal);
      String kind = requiredText(candidate, "kind", 32);
      String title = requiredText(candidate, "title", 200);
      JsonNode object = candidate.path("object");
      String objectValue = object.isTextual() ? object.asText() : null;
      require(object.isNull() || object.isTextual(), "invalid object boundary");
      items.add(new SkillEvidenceItem(ordinal, kind, title, objectValue));
    }
    String defaultTitle = requiredText(authoritativeProposal.path("suggestedTitle"), "value", 200);
    SkillEvidence evidence = new SkillEvidence(defaultTitle, List.copyOf(items));
    ObjectNode evidenceJson = toJson(evidence);
    require(
        evidenceJson.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_EVIDENCE_BYTES,
        "skill evidence exceeds its byte boundary");
    return new SkillProjection(authoritativeProposal.deepCopy(), evidence, evidenceJson);
  }

  ObjectNode toJson(SkillEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    ObjectNode value =
        json.createObjectNode()
            .put("schemaVersion", EVIDENCE_VERSION)
            .put("defaultTitle", evidence.defaultTitle());
    ArrayNode items = value.putArray("items");
    for (SkillEvidenceItem item : evidence.items()) {
      ObjectNode encoded =
          items
              .addObject()
              .put("ordinal", item.ordinal())
              .put("kind", item.kind())
              .put("title", item.title());
      if (item.objectValue() == null) {
        encoded.putNull("objectValue");
      } else {
        encoded.put("objectValue", item.objectValue());
      }
    }
    return value;
  }

  SkillSelection validateSelection(ObjectNode selection, SkillEvidence evidence) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(evidence, "evidence");
    if (!SELECTION_FIELDS.equals(new HashSet<>(selection.propertyNames()))) {
      throw rejected(SkillSelectionRejection.SHAPE_INVALID);
    }
    if (!SELECTION_VERSION.equals(selection.path("schemaVersion").asText(null))) {
      throw rejected(SkillSelectionRejection.SHAPE_INVALID);
    }
    JsonNode primaryNode = selection.path("primaryItemOrdinal");
    if (!primaryNode.isIntegralNumber() || !primaryNode.canConvertToInt()) {
      throw rejected(SkillSelectionRejection.SHAPE_INVALID);
    }
    int primary = primaryNode.asInt();
    if (evidence.items().isEmpty()) {
      if (primary != -1) {
        throw rejected(SkillSelectionRejection.PRIMARY_EMPTY_CONTRADICTION);
      }
    } else if (primary < 0 || primary >= evidence.items().size()) {
      throw rejected(SkillSelectionRejection.PRIMARY_OUT_OF_RANGE);
    }

    JsonNode topicsNode = selection.path("topicObjectOrdinals");
    if (!topicsNode.isArray() || topicsNode.size() > MAX_ITEMS) {
      throw rejected(SkillSelectionRejection.SHAPE_INVALID);
    }
    List<Integer> topics = new ArrayList<>(topicsNode.size());
    Set<Integer> seenOrdinals = new HashSet<>();
    Set<String> seenValues = new HashSet<>();
    for (JsonNode topicNode : topicsNode) {
      if (!topicNode.isIntegralNumber() || !topicNode.canConvertToInt()) {
        throw rejected(SkillSelectionRejection.SHAPE_INVALID);
      }
      int ordinal = topicNode.asInt();
      if (!seenOrdinals.add(ordinal)) {
        throw rejected(SkillSelectionRejection.TOPIC_DUPLICATE);
      }
      if (ordinal < 0 || ordinal >= evidence.items().size()) {
        throw rejected(SkillSelectionRejection.TOPIC_OUT_OF_RANGE);
      }
      String objectValue = evidence.items().get(ordinal).objectValue();
      if (objectValue == null
          || objectValue.isBlank()
          || objectValue.codePointCount(0, objectValue.length()) > 100) {
        throw rejected(SkillSelectionRejection.TOPIC_NOT_GROUNDED);
      }
      if (!seenValues.add(objectValue)) {
        throw rejected(SkillSelectionRejection.TOPIC_DUPLICATE);
      }
      topics.add(ordinal);
    }
    return new SkillSelection(primary, List.copyOf(topics));
  }

  ObjectNode skillOnlyProposal(SkillProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return projection.authoritativeProposal().deepCopy();
  }

  ObjectNode guardedProposal(SkillProjection projection, SkillSelection selection) {
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(selection, "selection");
    assertEvidenceMatches(projection.authoritativeProposal(), projection.evidence());
    ObjectNode guarded = projection.authoritativeProposal().deepCopy();
    if (selection.primaryItemOrdinal() >= 0) {
      guarded
          .withObject("suggestedTitle")
          .put("value", projection.evidence().items().get(selection.primaryItemOrdinal()).title());
    }
    assertOnlySuggestedTitleValueChanged(projection.authoritativeProposal(), guarded);
    return guarded;
  }

  ObjectNode fallbackProposal(SkillProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return projection.authoritativeProposal().deepCopy();
  }

  static void assertOnlySuggestedTitleValueChanged(ObjectNode authoritative, ObjectNode guarded) {
    ObjectNode normalized = guarded.deepCopy();
    normalized
        .withObject("suggestedTitle")
        .set("value", authoritative.at("/suggestedTitle/value").deepCopy());
    require(authoritative.equals(normalized), "model selection changed a protected proposal field");
  }

  private void assertEvidenceMatches(ObjectNode proposal, SkillEvidence evidence) {
    require(
        evidence.defaultTitle().equals(proposal.at("/suggestedTitle/value").asText()),
        "stale skill title evidence");
    JsonNode items = proposal.path("itemCandidates");
    require(
        items.isArray() && items.size() == evidence.items().size(), "stale skill item evidence");
    for (SkillEvidenceItem item : evidence.items()) {
      JsonNode candidate = items.get(item.ordinal());
      String objectValue =
          candidate.path("object").isTextual() ? candidate.path("object").asText() : null;
      require(item.kind().equals(candidate.path("kind").asText()), "stale skill kind evidence");
      require(item.title().equals(candidate.path("title").asText()), "stale skill title evidence");
      require(Objects.equals(item.objectValue(), objectValue), "stale skill object evidence");
    }
  }

  private String requiredText(JsonNode parent, String field, int maximumCodePoints) {
    JsonNode value = parent.path(field);
    require(value.isTextual(), "missing deterministic text");
    String text = value.asText();
    require(
        !text.isBlank() && text.codePointCount(0, text.length()) <= maximumCodePoints,
        "invalid deterministic text");
    return text;
  }

  private static SkillSelectionRejectedException rejected(SkillSelectionRejection reason) {
    return new SkillSelectionRejectedException(reason);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}

record SkillProjection(
    ObjectNode authoritativeProposal, SkillEvidence evidence, ObjectNode evidenceJson) {
  SkillProjection {
    Objects.requireNonNull(authoritativeProposal, "authoritativeProposal");
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(evidenceJson, "evidenceJson");
    authoritativeProposal = authoritativeProposal.deepCopy();
    evidenceJson = evidenceJson.deepCopy();
  }

  @Override
  public ObjectNode authoritativeProposal() {
    return authoritativeProposal.deepCopy();
  }

  @Override
  public ObjectNode evidenceJson() {
    return evidenceJson.deepCopy();
  }
}

record SkillEvidence(String defaultTitle, List<SkillEvidenceItem> items) {
  SkillEvidence {
    Objects.requireNonNull(defaultTitle, "defaultTitle");
    Objects.requireNonNull(items, "items");
    if (defaultTitle.isBlank() || defaultTitle.codePointCount(0, defaultTitle.length()) > 200) {
      throw new IllegalArgumentException("Default title is outside the skill boundary.");
    }
    if (items.size() > ShadowDeterministicSkill.MAX_ITEMS) {
      throw new IllegalArgumentException("Too many skill evidence items.");
    }
    items = List.copyOf(items);
    for (int ordinal = 0; ordinal < items.size(); ordinal++) {
      if (items.get(ordinal).ordinal() != ordinal) {
        throw new IllegalArgumentException("Skill evidence ordinals must be contiguous.");
      }
    }
  }
}

record SkillEvidenceItem(int ordinal, String kind, String title, String objectValue) {
  SkillEvidenceItem {
    if (ordinal < 0 || ordinal >= ShadowDeterministicSkill.MAX_ITEMS) {
      throw new IllegalArgumentException("Skill evidence ordinal is outside the boundary.");
    }
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(title, "title");
    if (title.isBlank() || title.codePointCount(0, title.length()) > 200) {
      throw new IllegalArgumentException("Skill evidence title is outside the boundary.");
    }
    if (objectValue != null
        && (objectValue.isBlank() || objectValue.codePointCount(0, objectValue.length()) > 200)) {
      throw new IllegalArgumentException("Skill evidence object is outside the boundary.");
    }
  }
}

record SkillSelection(int primaryItemOrdinal, List<Integer> topicObjectOrdinals) {
  SkillSelection {
    Objects.requireNonNull(topicObjectOrdinals, "topicObjectOrdinals");
    topicObjectOrdinals = List.copyOf(topicObjectOrdinals);
  }
}

enum SkillSelectionRejection {
  SHAPE_INVALID,
  PRIMARY_OUT_OF_RANGE,
  PRIMARY_EMPTY_CONTRADICTION,
  TOPIC_OUT_OF_RANGE,
  TOPIC_NOT_GROUNDED,
  TOPIC_DUPLICATE
}

final class SkillSelectionRejectedException extends IllegalArgumentException {
  private final SkillSelectionRejection reason;

  SkillSelectionRejectedException(SkillSelectionRejection reason) {
    super("Bounded model selection was rejected.", null);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  SkillSelectionRejection reason() {
    return reason;
  }
}
