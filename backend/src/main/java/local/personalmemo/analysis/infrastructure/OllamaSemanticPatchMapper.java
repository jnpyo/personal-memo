package local.personalmemo.analysis.infrastructure;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.LocalModelInput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class OllamaSemanticPatchMapper {
  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final Set<String> PRECISE_DATE_KINDS =
      Set.of("DATE_ONLY", "EXACT_TIME", "RELATIVE_EXACT");
  private static final double PATCHED_CONFIDENCE = 0.5;

  ObjectNode apply(ObjectNode validatedLocalProposal, LocalModelInput input, ObjectNode patch) {
    ObjectNode result = validatedLocalProposal.deepCopy();
    if (!"2".equals(result.path("schemaVersion").asText())) {
      throw new OllamaProtocolException();
    }
    if (!"2".equals(requiredText(patch, "version"))) {
      throw new OllamaProtocolException();
    }
    String decision = requiredText(patch, "decision");
    if ("KEEP".equals(decision)) {
      return result;
    }
    if (!"PATCH".equals(decision)) {
      throw new OllamaProtocolException();
    }
    ArrayNode items = requireArray(result.path("itemCandidates"));
    int itemIndex = requiredInteger(patch, "itemIndex");
    if (itemIndex < 0 || itemIndex >= items.size()) {
      throw new OllamaProtocolException();
    }
    ObjectNode item = requireObject(items.get(itemIndex));
    String oldKind = requiredText(item, "kind");
    String newKind = requiredText(patch, "kind");
    if (!ITEM_KINDS.contains(newKind)) {
      throw new OllamaProtocolException();
    }

    String memoContent = input.memoContent();
    ItemBounds itemBounds = itemBounds(item, memoContent.length());
    String action = exactUniqueSelection(patch, "actionText", memoContent, itemBounds);
    String object = exactUniqueSelection(patch, "objectText", memoContent, itemBounds);
    String time = exactUniqueSelection(patch, "timeText", memoContent, itemBounds);
    if (!"TASK".equals(newKind) && (action != null || object != null)) {
      throw new OllamaProtocolException();
    }
    if (!"TASK".equals(newKind) && !item.path("dueDateCandidateId").isNull()) {
      throw new OllamaProtocolException();
    }

    boolean changed = !newKind.equals(oldKind);
    changed |= !nullableText(item.path("action")).equals(java.util.Optional.ofNullable(action));
    changed |= !nullableText(item.path("object")).equals(java.util.Optional.ofNullable(object));
    item.put("kind", newKind).put("confidence", PATCHED_CONFIDENCE);
    putNullable(item, "action", action);
    putNullable(item, "object", object);

    EnumSet<AmbiguityReason> ambiguity = existingAmbiguity(result.path("ambiguityReasons"));
    ambiguity.add(AmbiguityReason.LOCAL_CLOUD_CONFLICT);
    if (!newKind.equals(oldKind)) {
      ambiguity.add(AmbiguityReason.LOW_TYPE_MARGIN);
      replaceTypeCandidates(result, newKind, oldKind);
    }

    if (time != null) {
      changed |= bindOrAddTime(result, item, newKind, time, ambiguity);
    }
    if (!changed) {
      throw new OllamaProtocolException();
    }

    recomputeMissingTaskFields(items, ambiguity);
    writeAmbiguity(result, ambiguity);
    ObjectNode title = requireObject(result.path("suggestedTitle"));
    title.put("needsConfirmation", true);
    return result;
  }

  private boolean bindOrAddTime(
      ObjectNode proposal,
      ObjectNode item,
      String itemKind,
      String selectedTime,
      EnumSet<AmbiguityReason> ambiguity) {
    ArrayNode dates = requireArray(proposal.path("dateCandidates"));
    ObjectNode matched = null;
    for (JsonNode candidate : dates) {
      ObjectNode object = requireObject(candidate);
      if (!selectedTime.equals(object.path("surfaceText").asText())) {
        continue;
      }
      if (matched != null) {
        throw new OllamaProtocolException();
      }
      matched = object;
    }

    boolean changed = false;
    if (matched == null) {
      if (dates.size() >= 5) {
        throw new OllamaProtocolException();
      }
      matched =
          dates
              .addObject()
              .put("candidateId", nextDateCandidateId(dates))
              .put("surfaceText", selectedTime)
              .putNull("value")
              .put("precision", "UNKNOWN")
              .put("timeSpecified", false)
              .put("confidence", PATCHED_CONFIDENCE);
      matched.putArray("ambiguityReasons").add(AmbiguityReason.IMPRECISE_DATE.name());
      ambiguity.add(AmbiguityReason.IMPRECISE_DATE);
      changed = true;
    }

    String precision = requiredText(matched, "precision");
    if (!PRECISE_DATE_KINDS.contains(precision)) {
      ambiguity.add(AmbiguityReason.IMPRECISE_DATE);
      return changed;
    }
    if (!"TASK".equals(itemKind)) {
      return changed;
    }
    String candidateId = requiredText(matched, "candidateId");
    String current =
        item.path("dueDateCandidateId").isTextual()
            ? item.path("dueDateCandidateId").asText()
            : null;
    if (!candidateId.equals(current)) {
      item.put("dueDateCandidateId", candidateId);
      changed = true;
    }
    return changed;
  }

  private String nextDateCandidateId(ArrayNode dates) {
    Set<String> existing = new LinkedHashSet<>();
    for (JsonNode candidate : dates) {
      if (candidate.path("candidateId").isTextual()) {
        existing.add(candidate.path("candidateId").asText());
      }
    }
    for (int suffix = 1; suffix <= 6; suffix++) {
      String candidate = "local-model-date-" + suffix;
      if (!existing.contains(candidate)) {
        return candidate;
      }
    }
    throw new OllamaProtocolException();
  }

  private void replaceTypeCandidates(ObjectNode proposal, String selectedKind, String oldKind) {
    ArrayNode previous = requireArray(proposal.path("typeCandidates"));
    LinkedHashSet<String> values = new LinkedHashSet<>();
    values.add(selectedKind);
    values.add(oldKind);
    for (JsonNode candidate : previous) {
      String value = requiredText(requireObject(candidate), "value");
      if (!selectedKind.equals(value)) {
        values.add(value);
      }
    }
    ArrayNode replacement = proposal.putArray("typeCandidates");
    int index = 0;
    for (String value : values) {
      if (index == 5) {
        break;
      }
      double score = index == 0 ? 0.51 : Math.max(0.1, 0.49 - ((index - 1) * 0.1));
      replacement.addObject().put("value", value).put("score", score);
      index++;
    }
  }

  private void recomputeMissingTaskFields(ArrayNode items, EnumSet<AmbiguityReason> ambiguity) {
    ambiguity.remove(AmbiguityReason.MISSING_ACTION);
    ambiguity.remove(AmbiguityReason.MISSING_OBJECT);
    for (JsonNode candidate : items) {
      ObjectNode item = requireObject(candidate);
      if (!"TASK".equals(item.path("kind").asText())) {
        continue;
      }
      if (!item.path("action").isTextual() || item.path("action").asText().isBlank()) {
        ambiguity.add(AmbiguityReason.MISSING_ACTION);
      }
      if (!item.path("object").isTextual() || item.path("object").asText().isBlank()) {
        ambiguity.add(AmbiguityReason.MISSING_OBJECT);
      }
    }
  }

  private EnumSet<AmbiguityReason> existingAmbiguity(JsonNode source) {
    ArrayNode values = requireArray(source);
    EnumSet<AmbiguityReason> result = EnumSet.noneOf(AmbiguityReason.class);
    try {
      for (JsonNode value : values) {
        if (!value.isTextual() || !result.add(AmbiguityReason.valueOf(value.asText()))) {
          throw new OllamaProtocolException();
        }
      }
      return result;
    } catch (IllegalArgumentException exception) {
      throw new OllamaProtocolException();
    }
  }

  private void writeAmbiguity(ObjectNode proposal, EnumSet<AmbiguityReason> reasons) {
    ArrayNode target = proposal.putArray("ambiguityReasons");
    for (AmbiguityReason reason : AmbiguityReason.values()) {
      if (reasons.contains(reason)) {
        target.add(reason.name());
      }
    }
  }

  private String exactUniqueSelection(
      ObjectNode patch, String field, String memoContent, ItemBounds itemBounds) {
    JsonNode value = patch.path(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new OllamaProtocolException();
    }
    String selection = value.asText();
    if (selection.isBlank()
        || !selection.equals(selection.strip())
        || selection.codePointCount(0, selection.length()) > 200) {
      throw new OllamaProtocolException();
    }
    int first = memoContent.indexOf(selection);
    if (first < 0
        || memoContent.indexOf(selection, first + selection.length()) >= 0
        || first < itemBounds.start()
        || first + selection.length() > itemBounds.end()) {
      throw new OllamaProtocolException();
    }
    return selection;
  }

  private ItemBounds itemBounds(ObjectNode item, int memoLength) {
    JsonNode sourceSpan = item.path("sourceSpan");
    if (sourceSpan.isNull()) {
      return new ItemBounds(0, memoLength);
    }
    ObjectNode span = requireObject(sourceSpan);
    int start = requiredInteger(span, "start");
    int end = requiredInteger(span, "end");
    if (start < 0 || end <= start || end > memoLength) {
      throw new OllamaProtocolException();
    }
    return new ItemBounds(start, end);
  }

  private java.util.Optional<String> nullableText(JsonNode value) {
    if (value.isNull()) {
      return java.util.Optional.empty();
    }
    if (!value.isTextual()) {
      throw new OllamaProtocolException();
    }
    return java.util.Optional.of(value.asText());
  }

  private void putNullable(ObjectNode target, String field, String value) {
    if (value == null) {
      target.putNull(field);
    } else {
      target.put(field, value);
    }
  }

  private int requiredInteger(JsonNode source, String field) {
    JsonNode value = source.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new OllamaProtocolException();
    }
    return value.asInt();
  }

  private String requiredText(JsonNode source, String field) {
    JsonNode value = source.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new OllamaProtocolException();
    }
    return value.asText();
  }

  private ArrayNode requireArray(JsonNode value) {
    if (!(value instanceof ArrayNode array)) {
      throw new OllamaProtocolException();
    }
    return array;
  }

  private ObjectNode requireObject(JsonNode value) {
    if (!(value instanceof ObjectNode object)) {
      throw new OllamaProtocolException();
    }
    return object;
  }

  private record ItemBounds(int start, int end) {}
}
