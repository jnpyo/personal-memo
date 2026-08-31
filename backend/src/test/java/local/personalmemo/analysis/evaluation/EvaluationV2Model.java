package local.personalmemo.analysis.evaluation;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

final class EvaluationTextNormalizer {
  private EvaluationTextNormalizer() {}

  static String normalize(String value) {
    Objects.requireNonNull(value, "value");
    String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
    StringBuilder normalized = new StringBuilder(nfc.length());
    boolean pendingSpace = false;
    for (int offset = 0; offset < nfc.length(); ) {
      int codePoint = nfc.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isEvaluationWhitespace(codePoint)) {
        if (!normalized.isEmpty()) {
          pendingSpace = true;
        }
        continue;
      }
      if (pendingSpace) {
        normalized.append(' ');
        pendingSpace = false;
      }
      normalized.appendCodePoint(codePoint);
    }
    return normalized.toString();
  }

  private static boolean isEvaluationWhitespace(int codePoint) {
    return codePoint == 0x0009
        || codePoint == 0x000A
        || codePoint == 0x000B
        || codePoint == 0x000C
        || codePoint == 0x000D
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0xFEFF
        || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
  }
}

record EvaluationSpan(int start, int end) {
  EvaluationSpan {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("Evaluation span is invalid.");
    }
  }

  boolean exact(EvaluationSpan other) {
    return other != null && start == other.start && end == other.end;
  }

  int overlap(EvaluationSpan other) {
    return other == null ? 0 : Math.max(0, Math.min(end, other.end) - Math.max(start, other.start));
  }
}

record EvaluationDateSemantic(String precision, String normalizedValue, boolean timeSpecified) {
  private static final Set<String> PRECISE = Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT");

  static EvaluationDateSemantic from(JsonNode value) {
    if (value == null || !value.isObject()) {
      return null;
    }
    String precision = text(value.path("precision"));
    JsonNode timeSpecifiedNode = value.path("timeSpecified");
    if (precision == null || !timeSpecifiedNode.isBoolean()) {
      return null;
    }
    boolean timeSpecified = timeSpecifiedNode.asBoolean();
    JsonNode rawValue = value.path("value");
    try {
      return switch (precision) {
        case "DATE_ONLY" ->
            rawValue.isTextual() && !timeSpecified
                ? new EvaluationDateSemantic(
                    precision, LocalDate.parse(rawValue.asText()).toString(), false)
                : null;
        case "EXACT_TIME", "RELATIVE_EXACT" ->
            rawValue.isTextual() && timeSpecified
                ? new EvaluationDateSemantic(
                    precision, OffsetDateTime.parse(rawValue.asText()).toInstant().toString(), true)
                : null;
        case "APPROXIMATE", "UNKNOWN" ->
            rawValue.isNull() && !timeSpecified
                ? new EvaluationDateSemantic(precision, null, false)
                : null;
        default -> null;
      };
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  boolean precise() {
    return PRECISE.contains(precision) && normalizedValue != null;
  }

  private static String text(JsonNode value) {
    return value.isTextual() ? value.asText() : null;
  }
}

record EvaluationDateGold(
    String goldId,
    EvaluationSpan sourceSpan,
    String surfaceText,
    List<EvaluationDateSemantic> acceptedInterpretations,
    Set<String> ambiguityReasons,
    boolean primary) {
  EvaluationDateGold {
    acceptedInterpretations = List.copyOf(acceptedInterpretations);
    ambiguityReasons = Set.copyOf(ambiguityReasons);
  }

  boolean accepts(EvaluationDateSemantic actual) {
    return actual != null && acceptedInterpretations.contains(actual);
  }

  boolean acceptsPrecise(EvaluationDateSemantic actual) {
    return actual != null && actual.precise() && accepts(actual);
  }
}

record EvaluationDateGoldSet(
    List<EvaluationDateGold> mentions, List<String> emittedCandidateGoldIds, String primaryGoldId) {
  EvaluationDateGoldSet {
    mentions = List.copyOf(mentions);
    emittedCandidateGoldIds = List.copyOf(emittedCandidateGoldIds);
  }

  List<EvaluationDateGold> emitted() {
    Map<String, EvaluationDateGold> byId =
        mentions.stream()
            .collect(
                Collectors.toMap(
                    EvaluationDateGold::goldId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    return emittedCandidateGoldIds.stream()
        .map(
            goldId -> {
              EvaluationDateGold gold = byId.get(goldId);
              if (gold == null) {
                throw new IllegalArgumentException(
                    "Date emittedCandidateGoldIds contains a dangling reference: " + goldId);
              }
              return gold;
            })
        .toList();
  }

  EvaluationDateGold primary() {
    if (primaryGoldId == null) {
      return null;
    }
    return mentions.stream()
        .filter(candidate -> primaryGoldId.equals(candidate.goldId()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Date primaryGoldId contains a dangling reference: " + primaryGoldId));
  }

  boolean overflow() {
    return mentions.size() > emittedCandidateGoldIds.size();
  }
}

record EvaluationActualDate(
    String surfaceText,
    EvaluationDateSemantic semantic,
    boolean declaredPrecise,
    double confidence) {}

enum EvaluationTextState {
  VALUE,
  ABSENT,
  UNRESOLVED
}

record EvaluationTextExpectation(EvaluationTextState state, String normalizedValue) {
  boolean exact(String actual) {
    return switch (state) {
      case VALUE ->
          actual != null && normalizedValue.equals(EvaluationTextNormalizer.normalize(actual));
      case ABSENT -> actual == null;
      case UNRESOLVED -> false;
    };
  }

  boolean scorable() {
    return state != EvaluationTextState.UNRESOLVED;
  }
}

enum EvaluationSpanRequirement {
  REQUIRED,
  ABSENT,
  OPTIONAL
}

record EvaluationSpanExpectation(
    EvaluationSpanRequirement requirement, List<EvaluationSpan> acceptedSpans) {
  EvaluationSpanExpectation {
    acceptedSpans = List.copyOf(acceptedSpans);
  }

  boolean exact(EvaluationSpan actual) {
    return switch (requirement) {
      case REQUIRED ->
          actual != null && acceptedSpans.stream().anyMatch(span -> span.exact(actual));
      case ABSENT -> actual == null;
      case OPTIONAL ->
          actual == null || acceptedSpans.stream().anyMatch(span -> span.exact(actual));
    };
  }

  int bestOverlap(EvaluationSpan actual) {
    return acceptedSpans.stream().mapToInt(span -> span.overlap(actual)).max().orElse(0);
  }
}

record EvaluationItemGold(
    String goldId,
    String kind,
    EvaluationTextExpectation title,
    EvaluationTextExpectation action,
    EvaluationTextExpectation object,
    EvaluationSpanExpectation sourceSpan) {}

record EvaluationItemGoldSet(
    String setId,
    EvaluationTextExpectation suggestedTitle,
    String primaryItemGoldId,
    List<EvaluationItemGold> allItems,
    List<String> emittedItemGoldIds) {
  EvaluationItemGoldSet {
    allItems = List.copyOf(allItems);
    emittedItemGoldIds = List.copyOf(emittedItemGoldIds);
  }

  List<EvaluationItemGold> emitted() {
    Map<String, EvaluationItemGold> byId =
        allItems.stream()
            .collect(
                Collectors.toMap(
                    EvaluationItemGold::goldId,
                    value -> value,
                    (left, right) -> left,
                    LinkedHashMap::new));
    return emittedItemGoldIds.stream()
        .map(
            goldId -> {
              EvaluationItemGold gold = byId.get(goldId);
              if (gold == null) {
                throw new IllegalArgumentException(
                    "Item emittedItemGoldIds contains a dangling reference: " + goldId);
              }
              return gold;
            })
        .toList();
  }

  EvaluationItemGold primary() {
    if (primaryItemGoldId == null) {
      return null;
    }
    return allItems.stream()
        .filter(item -> primaryItemGoldId.equals(item.goldId()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Item primaryItemGoldId contains a dangling reference: " + primaryItemGoldId));
  }

  int overflowOmittedCount() {
    return Math.max(0, allItems.size() - emittedItemGoldIds.size());
  }
}

record EvaluationItemGoldOptions(String resolution, List<EvaluationItemGoldSet> acceptableSets) {
  EvaluationItemGoldOptions {
    acceptableSets = List.copyOf(acceptableSets);
  }

  boolean overflow() {
    return "OVERFLOW".equals(resolution)
        || acceptableSets.stream().anyMatch(set -> set.allItems().size() > 3);
  }

  boolean requiresUserInput() {
    return "USER_INPUT_NEEDED".equals(resolution);
  }
}

record EvaluationActualItem(
    String kind,
    String title,
    String action,
    String object,
    EvaluationSpan sourceSpan,
    double confidence) {}

record EvaluationCaseGold(
    String id,
    String split,
    String content,
    String expectedRoute,
    List<String> expectedTypes,
    Set<String> expectedSignals,
    EvaluationDateGoldSet dates,
    EvaluationItemGoldOptions items) {
  EvaluationCaseGold {
    expectedTypes = List.copyOf(expectedTypes);
    expectedSignals = Set.copyOf(expectedSignals);
  }
}

final class EvaluationV2GoldParser {
  private EvaluationV2GoldParser() {}

  static EvaluationCaseGold parse(JsonNode fixture) {
    String split = requiredText(fixture.path("split"), "split");
    return new EvaluationCaseGold(
        requiredText(fixture.path("id"), "id"),
        split,
        requiredText(fixture.path("content"), "content"),
        evaluationText(fixture, "analyzerExpectedRoute", "expectedRoute"),
        textList(fixture.path("expectedTypes")),
        Set.copyOf(evaluationTextList(fixture, "analyzerExpectedSignals", "expectedSignals")),
        dates(fixture.path("expectedDates")),
        items(fixture.path("expectedItems")));
  }

  static List<EvaluationActualDate> actualDates(JsonNode candidates) {
    List<EvaluationActualDate> actual = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      actual.add(
          new EvaluationActualDate(
              nullableText(candidate.path("surfaceText")),
              EvaluationDateSemantic.from(candidate),
              Set.of("EXACT_TIME", "DATE_ONLY", "RELATIVE_EXACT")
                  .contains(nullableText(candidate.path("precision"))),
              finiteConfidence(candidate.path("confidence"))));
    }
    return List.copyOf(actual);
  }

  static List<EvaluationActualItem> actualItems(JsonNode candidates) {
    List<EvaluationActualItem> actual = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      actual.add(
          new EvaluationActualItem(
              nullableText(candidate.path("kind")),
              nullableText(candidate.path("title")),
              nullableText(candidate.path("action")),
              nullableText(candidate.path("object")),
              actualSpan(candidate.path("sourceSpan")),
              finiteConfidence(candidate.path("confidence"))));
    }
    return List.copyOf(actual);
  }

  private static EvaluationDateGoldSet dates(JsonNode value) {
    List<EvaluationDateGold> mentions = new ArrayList<>();
    for (JsonNode mention : value.path("mentions")) {
      List<EvaluationDateSemantic> interpretations = new ArrayList<>();
      for (JsonNode interpretation : mention.path("acceptedInterpretations")) {
        EvaluationDateSemantic semantic = EvaluationDateSemantic.from(interpretation);
        if (semantic == null) {
          throw new IllegalArgumentException("Date gold contains an invalid interpretation.");
        }
        interpretations.add(semantic);
      }
      mentions.add(
          new EvaluationDateGold(
              requiredText(mention.path("goldId"), "date.goldId"),
              span(mention.path("sourceSpan")),
              requiredText(mention.path("surfaceText"), "date.surfaceText"),
              interpretations,
              Set.copyOf(textList(mention.path("ambiguityReasons"))),
              mention.path("primary").asBoolean(false)));
    }
    JsonNode primary = value.path("primaryGoldId");
    return new EvaluationDateGoldSet(
        mentions,
        textList(value.path("emittedCandidateGoldIds")),
        primary.isNull() ? null : requiredText(primary, "date.primaryGoldId"));
  }

  private static EvaluationItemGoldOptions items(JsonNode value) {
    List<EvaluationItemGoldSet> sets = new ArrayList<>();
    for (JsonNode set : value.path("acceptableSets")) {
      List<EvaluationItemGold> allItems = new ArrayList<>();
      for (JsonNode item : set.path("allItems")) {
        allItems.add(
            new EvaluationItemGold(
                requiredText(item.path("goldId"), "item.goldId"),
                requiredText(item.path("kind"), "item.kind"),
                textExpectation(item.path("title")),
                textExpectation(item.path("action")),
                textExpectation(item.path("object")),
                spanExpectation(item.path("sourceSpan"))));
      }
      JsonNode primary = set.path("primaryItemGoldId");
      sets.add(
          new EvaluationItemGoldSet(
              requiredText(set.path("setId"), "item.setId"),
              textExpectation(set.path("suggestedTitle")),
              primary.isNull() ? null : requiredText(primary, "item.primaryItemGoldId"),
              allItems,
              textList(set.path("emittedItemGoldIds"))));
    }
    return new EvaluationItemGoldOptions(
        requiredText(value.path("resolution"), "item.resolution"), sets);
  }

  private static EvaluationTextExpectation textExpectation(JsonNode value) {
    EvaluationTextState state =
        EvaluationTextState.valueOf(requiredText(value.path("state"), "text.state"));
    String normalized =
        state == EvaluationTextState.VALUE
            ? EvaluationTextNormalizer.normalize(requiredText(value.path("value"), "text.value"))
            : null;
    return new EvaluationTextExpectation(state, normalized);
  }

  private static EvaluationSpanExpectation spanExpectation(JsonNode value) {
    EvaluationSpanRequirement requirement =
        EvaluationSpanRequirement.valueOf(
            requiredText(value.path("requirement"), "span.requirement"));
    List<EvaluationSpan> accepted = new ArrayList<>();
    for (JsonNode span : value.path("acceptedSpans")) {
      accepted.add(span(span));
    }
    return new EvaluationSpanExpectation(requirement, accepted);
  }

  private static EvaluationSpan actualSpan(JsonNode value) {
    if (value.isNull() || !value.isObject()) {
      return null;
    }
    JsonNode start = value.path("start");
    JsonNode end = value.path("end");
    return start.canConvertToInt() && end.canConvertToInt()
        ? new EvaluationSpan(start.asInt(), end.asInt())
        : null;
  }

  private static EvaluationSpan span(JsonNode value) {
    if (!"UTF16_CODE_UNIT".equals(requiredText(value.path("unit"), "span.unit"))) {
      throw new IllegalArgumentException("Evaluation span unit is unsupported.");
    }
    JsonNode start = value.path("start");
    JsonNode end = value.path("end");
    if (!start.canConvertToInt() || !end.canConvertToInt()) {
      throw new IllegalArgumentException("Evaluation span offsets are invalid.");
    }
    return new EvaluationSpan(start.asInt(), end.asInt());
  }

  private static String evaluationText(JsonNode fixture, String override, String fallback) {
    return fixture.path(override).isTextual()
        ? fixture.path(override).asText()
        : requiredText(fixture.path(fallback), fallback);
  }

  private static List<String> evaluationTextList(
      JsonNode fixture, String override, String fallback) {
    return fixture.path(override).isArray()
        ? textList(fixture.path(override))
        : textList(fixture.path(fallback));
  }

  private static List<String> textList(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(requiredText(value, "array value"));
    }
    return List.copyOf(values);
  }

  private static String requiredText(JsonNode value, String field) {
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(field + " is invalid.");
    }
    return value.asText();
  }

  private static String nullableText(JsonNode value) {
    return value.isTextual() ? value.asText() : null;
  }

  private static double finiteConfidence(JsonNode value) {
    double confidence = value.isNumber() ? value.asDouble() : Double.NEGATIVE_INFINITY;
    return Double.isFinite(confidence) ? confidence : Double.NEGATIVE_INFINITY;
  }
}

record EvaluationFieldCounts(int truePositive, int falsePositive, int falseNegative) {
  EvaluationFieldCounts add(EvaluationFieldCounts other) {
    return new EvaluationFieldCounts(
        truePositive + other.truePositive,
        falsePositive + other.falsePositive,
        falseNegative + other.falseNegative);
  }

  static EvaluationFieldCounts zero() {
    return new EvaluationFieldCounts(0, 0, 0);
  }
}

record EvaluationCounts(int truePositive, int falsePositive, int falseNegative) {
  EvaluationCounts add(EvaluationCounts other) {
    return new EvaluationCounts(
        truePositive + other.truePositive,
        falsePositive + other.falsePositive,
        falseNegative + other.falseNegative);
  }
}

record EvaluationReportMetadata(
    String reportVersion,
    String datasetVersion,
    String analyzerVersion,
    String deterministicRulesVersion,
    String routingPolicyVersion,
    String dateItemCapability) {}
