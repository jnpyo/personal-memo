package local.personalmemo.analysis.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.domain.KoreanDateParser;
import local.personalmemo.analysis.domain.KoreanDateParser.ParsedDate;
import local.personalmemo.analysis.domain.KoreanItemExtractor;
import local.personalmemo.analysis.domain.KoreanItemExtractor.ExtractedItem;
import local.personalmemo.analysis.domain.KoreanItemExtractor.Extraction;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A deterministic fixture analyzer. It intentionally has no model, tool, network, or canonical
 * write capability.
 */
@Component
public class FakeAnalyzer implements LocalAnalyzer {
  private static final AnalysisProvenance PROVENANCE =
      new AnalysisProvenance("fake-v5", "none", "none", "none");
  private static final String DETERMINISTIC_RULES_VERSION = "korean-rules-v3";
  private static final int MAX_DATE_CANDIDATES = 5;
  private static final int MAX_ITEM_CANDIDATES = 3;
  private static final Pattern OPERATING_SYSTEMS_ALIAS =
      Pattern.compile("(?<![A-Za-z0-9])os(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);

  private final ObjectMapper json;
  private final KoreanDateParser dateParser = new KoreanDateParser();
  private final KoreanItemExtractor itemExtractor = new KoreanItemExtractor();
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();

  public FakeAnalyzer(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public AnalysisProvenance provenance() {
    return PROVENANCE;
  }

  public String deterministicRulesVersion() {
    return DETERMINISTIC_RULES_VERSION;
  }

  @Override
  public ObjectNode analyze(
      UUID memoId, int revision, String content, Instant baseInstant, String timeZone) {
    List<ParsedDate> detectedDates = dateParser.parse(content, baseInstant, timeZone);
    List<ParsedDate> dates = detectedDates.stream().limit(MAX_DATE_CANDIDATES).toList();
    AnalysisShape shape = classify(content, detectedDates);
    LinkedHashSet<AmbiguityReason> signals = new LinkedHashSet<>();
    shape.signals().stream().sorted(Comparator.comparingInt(Enum::ordinal)).forEach(signals::add);
    if (detectedDates.size() > MAX_DATE_CANDIDATES
        || shape.detectedItemCount() > MAX_ITEM_CANDIDATES) {
      signals.add(AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
    }
    detectedDates.forEach(
        date ->
            date.ambiguityReasons().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .forEach(signals::add));
    String title = suggestedTitle(content, dates, shape);
    ArrayNode tagCandidates = extractTagCandidates(content, shape.newTopic());
    if (hasNewTagProposal(tagCandidates)) {
      signals.add(AmbiguityReason.NEW_TOPIC);
    }

    ObjectNode proposal = json.createObjectNode();
    proposal
        .put("schemaVersion", "1")
        .put("memoId", memoId.toString())
        .put("memoRevision", revision);
    proposal.set(
        "suggestedTitle",
        json.createObjectNode()
            .put("value", title)
            .put("confidence", shape.titleConfidence())
            .put("needsConfirmation", true));
    proposal.set("typeCandidates", createTypeCandidates(shape.types()));
    proposal.set("dateCandidates", createDateCandidates(dates));
    proposal.set("tagCandidates", tagCandidates);
    proposal.set("itemCandidates", createItemCandidates(shape.items(), title));
    proposal.set("relationCandidates", json.createArrayNode());
    proposal.set("ambiguityReasons", createAmbiguityReasons(signals));
    AnalysisRoute route = ambiguityGate.route(ambiguityGate.routingSignals(proposal));
    proposal.set(
        "providerMetadata",
        json.createObjectNode()
            .put("analyzerVersion", PROVENANCE.analyzerVersion())
            .put("deterministicRulesVersion", DETERMINISTIC_RULES_VERSION)
            .put("routingPolicyVersion", ambiguityGate.version())
            .put("promptVersion", PROVENANCE.promptVersion())
            .put("localModelVersion", PROVENANCE.localModelVersion())
            .put("embeddingModelVersion", PROVENANCE.embeddingModelVersion())
            .put("route", route.name())
            .put("detectedDateCandidateCount", detectedDates.size())
            .put("emittedDateCandidateCount", dates.size())
            .put("detectedItemCandidateCount", shape.detectedItemCount())
            .put("emittedItemCandidateCount", Math.min(shape.items().size(), MAX_ITEM_CANDIDATES))
            .put("toolCalls", 0));
    return proposal;
  }

  private AnalysisShape classify(String content, List<ParsedDate> dates) {
    String compact = content.replaceAll("\\s+", " ").strip();
    Extraction extraction = itemExtractor.extract(content, dates);
    List<ItemShape> extractedItems = extraction.allItems().stream().map(this::itemShape).toList();

    if (looksLikePromptInjection(compact)) {
      return shape(
          List.of("RECORD"), List.of(recordWholeContent(content, compact)), Set.of(), null, 0.99);
    }
    if (compact.contains("초안은")
        && compact.contains("최종 제출은")
        && content.indexOf("초안은") > 0
        && content.indexOf(',') >= 0) {
      int delimiter = content.indexOf(',');
      int draftMarker = content.indexOf("초안은");
      String subject = bounded(content.substring(0, draftMarker).strip(), 100);
      int secondStart = delimiter + 1;
      while (secondStart < content.length()
          && Character.isWhitespace(content.charAt(secondStart))) {
        secondStart++;
      }
      return shape(
          List.of("TASK"),
          List.of(
              new ItemShape("TASK", subject + " 초안 작성", "작성", subject + " 초안", 0, delimiter, 0.74),
              new ItemShape(
                  "TASK", subject + " 최종 제출", "제출", subject, secondStart, content.length(), 0.74)),
          Set.of(AmbiguityReason.MULTI_INTENT),
          null,
          0.87);
    }
    if (compact.contains("유리패드 마모 상태")) {
      return shape(List.of("TASK"), extractedItems, extraction.signals(), "유리패드", 0.9);
    }
    if (compact.matches(".*(?:\\d{1,2}\\.\\d{1,2}).*(?:운영체제|OS)\\s*과제\\s*$")) {
      return shape(
          List.of("UNKNOWN"), List.of(), Set.of(AmbiguityReason.MISSING_ACTION), null, 0.52);
    }

    List<String> extractedTypes = extractedItems.stream().map(ItemShape::kind).distinct().toList();
    List<String> types =
        extraction.alternative() && "TASK".equals(extractedTypes.getFirst())
            ? List.of("TASK", "INFORMATION")
            : isPastEvent(extractedItems, compact) ? List.of("EVENT", "RECORD") : extractedTypes;
    double confidence =
        extraction.alternative()
            ? 0.82
            : extractedItems.size() > 1 ? 0.86 : extractedItems.getFirst().confidence();
    return shape(
        types,
        extractedItems,
        extraction.signals(),
        null,
        confidence,
        extraction.detectedItemCount());
  }

  private boolean isPastEvent(List<ItemShape> items, String compact) {
    return items.size() == 1
        && "EVENT".equals(items.getFirst().kind())
        && compact.matches(".*(?:봤음|봤다|봄|치렀음)$");
  }

  private ItemShape itemShape(ExtractedItem item) {
    return new ItemShape(
        item.kind(),
        item.title(),
        item.action(),
        item.object(),
        item.startOffset(),
        item.endOffset(),
        item.confidence());
  }

  private boolean looksLikePromptInjection(String content) {
    return content.contains("이전 지시를 무시") || content.contains("모든 메모를 삭제");
  }

  private ItemShape recordWholeContent(String content, String title) {
    int start = 0;
    int end = content.length();
    while (start < end && Character.isWhitespace(content.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
      end--;
    }
    return new ItemShape("RECORD", title, null, null, start, end, 0.99);
  }

  private AnalysisShape shape(
      List<String> types,
      List<ItemShape> items,
      Set<AmbiguityReason> signals,
      String newTopic,
      double confidence) {
    return shape(types, items, signals, newTopic, confidence, items.size());
  }

  private AnalysisShape shape(
      List<String> types,
      List<ItemShape> items,
      Set<AmbiguityReason> signals,
      String newTopic,
      double confidence,
      int detectedItemCount) {
    return new AnalysisShape(types, items, signals, newTopic, confidence, detectedItemCount);
  }

  private String suggestedTitle(
      String content, List<ParsedDate> dates, AnalysisShape analysisShape) {
    String title = content.strip();
    for (ParsedDate date : dates) {
      title = title.replace(date.surfaceText(), " ");
    }
    title = title.replaceAll("\\s+", " ").strip();
    if (title.isBlank()) {
      title = content.strip();
    }
    if (analysisShape.items().size() == 1) {
      ItemShape item = analysisShape.items().getFirst();
      title =
          "EVENT".equals(item.kind())
                  && analysisShape.types().contains("RECORD")
                  && item.object() != null
              ? item.object()
              : item.title();
    }
    return bounded(title, 200);
  }

  private ArrayNode createTypeCandidates(List<String> types) {
    ArrayNode candidates = json.createArrayNode();
    for (int index = 0; index < types.size(); index++) {
      double score = Math.max(0.55, 0.96 - (index * 0.14));
      candidates.add(json.createObjectNode().put("value", types.get(index)).put("score", score));
    }
    return candidates;
  }

  private ArrayNode createDateCandidates(List<ParsedDate> dates) {
    ArrayNode candidates = json.createArrayNode();
    for (ParsedDate date : dates) {
      ObjectNode candidate =
          json.createObjectNode()
              .put("surfaceText", date.surfaceText())
              .put("precision", date.precision().name())
              .put("timeSpecified", date.timeSpecified())
              .put("confidence", date.confidence());
      if (date.value() == null) {
        candidate.putNull("value");
      } else {
        candidate.put("value", date.value());
      }
      candidate.set("ambiguityReasons", createAmbiguityReasons(date.ambiguityReasons()));
      candidates.add(candidate);
    }
    return candidates;
  }

  private ArrayNode createAmbiguityReasons(Iterable<AmbiguityReason> reasons) {
    ArrayNode values = json.createArrayNode();
    List<AmbiguityReason> ordered = new ArrayList<>();
    reasons.forEach(ordered::add);
    ordered.stream()
        .sorted(Comparator.comparingInt(Enum::ordinal))
        .forEach(reason -> values.add(reason.name()));
    return values;
  }

  private ArrayNode extractTagCandidates(String content, String newTopic) {
    ArrayNode tags = json.createArrayNode();
    boolean hasOperatingSystemsAlias = OPERATING_SYSTEMS_ALIAS.matcher(content).find();
    if (content.contains("운영체제") || hasOperatingSystemsAlias) {
      tags.add(tagCandidate(null, "운영체제", hasOperatingSystemsAlias ? "OS" : null, 0.98, true));
    }
    if (content.contains("과제")) {
      tags.add(tagCandidate(null, "과제", null, 0.96, true));
    }
    if (newTopic != null) {
      tags.add(tagCandidate(null, newTopic, null, 0.72, true));
    }
    return tags;
  }

  private boolean hasNewTagProposal(ArrayNode candidates) {
    for (var candidate : candidates) {
      if (candidate.path("isNewProposal").asBoolean()) {
        return true;
      }
    }
    return false;
  }

  private ObjectNode tagCandidate(
      String id, String canonicalName, String matchedAlias, double score, boolean newProposal) {
    ObjectNode candidate =
        json.createObjectNode()
            .put("canonicalName", canonicalName)
            .put("score", score)
            .put("isNewProposal", newProposal);
    if (id == null) {
      candidate.putNull("existingTagId");
    } else {
      candidate.put("existingTagId", id);
    }
    if (matchedAlias == null) {
      candidate.putNull("matchedAlias");
    } else {
      candidate.put("matchedAlias", matchedAlias);
    }
    return candidate;
  }

  private ArrayNode createItemCandidates(List<ItemShape> itemShapes, String fallbackTitle) {
    ArrayNode items = json.createArrayNode();
    List<ItemShape> boundedItems =
        new ArrayList<>(itemShapes.stream().limit(MAX_ITEM_CANDIDATES).toList());
    for (int index = 0; index < boundedItems.size(); index++) {
      ItemShape item = boundedItems.get(index);
      String itemTitle = item.title().isBlank() ? fallbackTitle : bounded(item.title(), 200);
      ObjectNode candidate =
          json.createObjectNode()
              .put("candidateId", "item-" + (index + 1))
              .put("kind", item.kind())
              .put("title", itemTitle);
      candidate.set(
          "sourceSpan",
          json.createObjectNode().put("start", item.startOffset()).put("end", item.endOffset()));
      candidate.put("confidence", item.confidence());
      if (item.action() == null) {
        candidate.putNull("action");
      } else {
        candidate.put("action", item.action());
      }
      if (item.object() == null) {
        candidate.putNull("object");
      } else {
        candidate.put("object", bounded(item.object(), 200));
      }
      items.add(candidate);
    }
    return items;
  }

  private String bounded(String value, int maximum) {
    int codePointCount = value.codePointCount(0, value.length());
    if (codePointCount <= maximum) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maximum));
  }

  private record AnalysisShape(
      List<String> types,
      List<ItemShape> items,
      Set<AmbiguityReason> signals,
      String newTopic,
      double titleConfidence,
      int detectedItemCount) {}

  private record ItemShape(
      String kind,
      String title,
      String action,
      String object,
      int startOffset,
      int endOffset,
      double confidence) {}
}
