package local.personalmemo.analysis.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** Cross-field constraints that JSON Schema cannot express for the v2 evaluation gold. */
final class EvaluationV2GoldIntegrity {
  private EvaluationV2GoldIntegrity() {}

  static EvaluationCaseGold validate(JsonNode fixture) {
    EvaluationCaseGold gold = EvaluationV2GoldParser.parse(fixture);
    validate(gold);
    validateRawBoundarySignals(fixture);
    return gold;
  }

  static void validate(EvaluationCaseGold gold) {
    validateDates(gold);
    validateItems(gold);
  }

  private static void validateDates(EvaluationCaseGold gold) {
    EvaluationDateGoldSet dates = gold.dates();
    requireUnique(
        dates.mentions().stream().map(EvaluationDateGold::goldId).toList(), "date goldId");
    requireUnique(dates.emittedCandidateGoldIds(), "date emitted reference");

    Set<String> mentionIds =
        dates.mentions().stream().map(EvaluationDateGold::goldId).collect(Collectors.toSet());
    requireReferences(dates.emittedCandidateGoldIds(), mentionIds, "date emitted reference");

    List<EvaluationDateGold> primaries =
        dates.mentions().stream().filter(EvaluationDateGold::primary).toList();
    if (dates.primaryGoldId() == null) {
      require(primaries.isEmpty(), "Date primary flags require primaryGoldId.");
    } else {
      require(mentionIds.contains(dates.primaryGoldId()), "Date primaryGoldId is dangling.");
      require(primaries.size() == 1, "Date primaryGoldId requires exactly one primary flag.");
      require(
          dates.primaryGoldId().equals(primaries.getFirst().goldId()),
          "Date primaryGoldId and primary flag disagree.");
      require(
          dates.emittedCandidateGoldIds().contains(dates.primaryGoldId()),
          "Date primary gold must be emitted.");
    }

    for (EvaluationDateGold mention : dates.mentions()) {
      validateSpan(mention.sourceSpan(), gold.content(), "date source span");
      require(
          gold.content()
              .substring(mention.sourceSpan().start(), mention.sourceSpan().end())
              .equals(mention.surfaceText()),
          "Date source span must select surfaceText exactly.");
      requireUnique(mention.acceptedInterpretations(), "normalized date interpretation");
      require(
          gold.expectedSignals().containsAll(mention.ambiguityReasons()),
          "Date ambiguity reasons must be included in boundary signals.");
      if (mention.acceptedInterpretations().size() > 1) {
        require(
            !mention.ambiguityReasons().isEmpty(),
            "Alternative date interpretations require an ambiguity reason.");
        requireCloud(gold, "Alternative date interpretations");
      }
    }

    if (dates.mentions().size() <= 5) {
      require(
          Set.copyOf(dates.emittedCandidateGoldIds()).equals(mentionIds),
          "Non-overflow dates must emit every mention.");
    } else {
      require(
          dates.emittedCandidateGoldIds().size() <= 5,
          "Overflow dates may emit at most five candidates.");
      requireCloudCandidateLimit(gold, "Date overflow");
    }
  }

  private static void validateItems(EvaluationCaseGold gold) {
    EvaluationItemGoldOptions items = gold.items();
    requireUnique(
        items.acceptableSets().stream().map(EvaluationItemGoldSet::setId).toList(), "item setId");
    requireUnique(
        items.acceptableSets().stream().map(EvaluationV2GoldIntegrity::semanticSetKey).toList(),
        "normalized acceptable item set");

    boolean hasUnresolved = false;
    boolean hasEmptyAlternative = false;
    for (EvaluationItemGoldSet set : items.acceptableSets()) {
      requireUnique(
          set.allItems().stream().map(EvaluationItemGold::goldId).toList(), "item goldId");
      Set<String> allIds =
          set.allItems().stream().map(EvaluationItemGold::goldId).collect(Collectors.toSet());
      requireUnique(set.emittedItemGoldIds(), "item emitted reference");
      requireReferences(set.emittedItemGoldIds(), allIds, "item emitted reference");
      if (set.primaryItemGoldId() != null) {
        require(allIds.contains(set.primaryItemGoldId()), "Item primaryItemGoldId is dangling.");
        require(
            set.emittedItemGoldIds().contains(set.primaryItemGoldId()),
            "Item primary gold must be emitted.");
      }

      hasEmptyAlternative |= set.allItems().isEmpty();
      hasUnresolved |= unresolved(set.suggestedTitle());
      for (EvaluationItemGold item : set.allItems()) {
        hasUnresolved |=
            unresolved(item.title()) || unresolved(item.action()) || unresolved(item.object());
        requireUnique(item.sourceSpan().acceptedSpans(), "accepted item source span");
        for (EvaluationSpan span : item.sourceSpan().acceptedSpans()) {
          validateSpan(span, gold.content(), "item source span");
        }
      }

      if (!"OVERFLOW".equals(items.resolution())) {
        require(
            Set.copyOf(set.emittedItemGoldIds()).equals(allIds),
            "Non-overflow items must emit every gold item.");
      }
    }

    switch (items.resolution()) {
      case "RESOLVED" -> {
        require(!hasUnresolved, "RESOLVED items cannot contain UNRESOLVED text fields.");
      }
      case "USER_INPUT_NEEDED" -> {
        requireCloud(gold, "USER_INPUT_NEEDED");
        require(
            items.acceptableSets().size() > 1 || hasUnresolved || hasEmptyAlternative,
            "USER_INPUT_NEEDED requires alternatives or an unresolved/empty item decision.");
      }
      case "OVERFLOW" -> {
        requireCloudCandidateLimit(gold, "Item overflow");
        for (EvaluationItemGoldSet set : items.acceptableSets()) {
          require(set.allItems().size() > 3, "OVERFLOW requires more than three gold items.");
          require(
              set.emittedItemGoldIds().size() <= 3,
              "OVERFLOW may emit at most three item candidates.");
        }
      }
      default -> throw new IllegalArgumentException("Unknown item resolution.");
    }
  }

  private static void requireCloudCandidateLimit(EvaluationCaseGold gold, String subject) {
    requireCloud(gold, subject);
    require(
        gold.expectedSignals().contains("CANDIDATE_LIMIT_EXCEEDED"),
        subject + " requires CANDIDATE_LIMIT_EXCEEDED.");
  }

  private static void requireCloud(EvaluationCaseGold gold, String subject) {
    require("CLOUD_ENRICH".equals(gold.expectedRoute()), subject + " must route to cloud review.");
  }

  private static boolean unresolved(EvaluationTextExpectation value) {
    return value.state() == EvaluationTextState.UNRESOLVED;
  }

  private static void validateSpan(EvaluationSpan span, String content, String subject) {
    require(span.end() > span.start(), subject + " must not be empty.");
    require(span.end() <= content.length(), subject + " exceeds UTF-16 content bounds.");
  }

  private static void validateRawBoundarySignals(JsonNode fixture) {
    Set<String> boundarySignals = new HashSet<>();
    for (JsonNode signal : fixture.path("expectedSignals")) {
      if (signal.isTextual()) {
        boundarySignals.add(signal.asText());
      }
    }
    for (JsonNode mention : fixture.path("expectedDates").path("mentions")) {
      for (JsonNode reason : mention.path("ambiguityReasons")) {
        require(
            reason.isTextual() && boundarySignals.contains(reason.asText()),
            "Date ambiguity reasons must be included in product boundary signals.");
      }
      if (mention.path("acceptedInterpretations").size() > 1) {
        require(
            !mention.path("ambiguityReasons").isEmpty(),
            "Alternative date interpretations require an ambiguity reason.");
        require(
            "CLOUD_ENRICH".equals(fixture.path("expectedRoute").asText()),
            "Alternative date interpretations must use the product cloud boundary.");
      }
    }
  }

  private static String semanticSetKey(EvaluationItemGoldSet set) {
    return textKey(set.suggestedTitle())
        + "|"
        + set.allItems().stream().map(EvaluationV2GoldIntegrity::semanticItemKey).sorted().toList();
  }

  private static String semanticItemKey(EvaluationItemGold item) {
    return item.kind()
        + "|"
        + textKey(item.title())
        + "|"
        + textKey(item.action())
        + "|"
        + textKey(item.object())
        + "|"
        + item.sourceSpan().requirement()
        + "|"
        + item.sourceSpan().acceptedSpans().stream()
            .sorted(
                (a, b) -> {
                  int byStart = Integer.compare(a.start(), b.start());
                  return byStart != 0 ? byStart : Integer.compare(a.end(), b.end());
                })
            .toList();
  }

  private static String textKey(EvaluationTextExpectation value) {
    return value.state() + ":" + (value.normalizedValue() == null ? "" : value.normalizedValue());
  }

  private static void requireReferences(
      List<String> references, Set<String> targets, String subject) {
    require(targets.containsAll(references), subject + " contains a dangling ID.");
  }

  private static void requireUnique(List<?> values, String subject) {
    require(new HashSet<>(values).size() == values.size(), subject + " values must be unique.");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
