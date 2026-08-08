package local.personalmemo.analysis.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.domain.KoreanDateParser;
import local.personalmemo.analysis.domain.KoreanDateParser.ParsedDate;
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
      new AnalysisProvenance("fake-v4", "none", "none", "none");
  private static final String DETERMINISTIC_RULES_VERSION = "korean-rules-v2";
  private static final int MAX_DATE_CANDIDATES = 5;
  private static final int MAX_ITEM_CANDIDATES = 3;
  private static final Pattern OPERATING_SYSTEMS_ALIAS =
      Pattern.compile("(?<![A-Za-z0-9])os(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
  private static final Pattern TECHNICAL_CONTEXT =
      Pattern.compile(
          "(?:API|DB|SQL|HTTP|캐시|서버|클라이언트|데이터|응답|요청|세션|토큰|권한|사용자별|인증|암호화|인터페이스|모듈|스키마)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TECHNICAL_DECLARATION =
      Pattern.compile(".*(?:해야\\s*함|되어야\\s*함|필요함|원칙임|규칙임)\\s*$");
  private static final Pattern EVENT_CONTEXT =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:회의|동창회|약속|행사|수업|시험|면접|공연|모임|진료|예약)(?=$|\\s|[은는이가을를에의와과도,.;!?])");
  private static final Pattern UNRESOLVED_REFERENCE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:그거|그것|그걸|저거|저것|저걸|이걸|그때|(?:그|저)\\s+(?:문서|파일|자료|내용|건|일|것|거)|저번\\s*(?:것|거|자료|문서)|말한\\s+(?:거|것|그\\s+[가-힣A-Za-z0-9]+))(?=$|\\s|[은는이가을를에의와과도,.;!?])");
  private static final Pattern ALTERNATIVE_CONNECTOR =
      Pattern.compile("(?:^|\\s)(?:또는|혹은|아니면)(?:\\s|$)");
  private static final List<ActionRule> ACTION_RULES =
      List.of(
          actionRule("장보기", "장보기(?=\\s*[:：]|\\s*$)"),
          actionRule("전화하기", "전화\\s*하기"),
          actionRule("찾아보기", "찾아보(?:기|고)"),
          actionRule("다시 보기", "다시\\s*보기"),
          actionRule("올리기", "올리기"),
          actionRule("만들기", "만들기"),
          actionRule("잡기", "잡기"),
          actionRule("마무리", "마무리(?:하기|해야\\s*함)?(?=\\s*$)"),
          actionRule("제출", "제출(?:하기|하고|해야\\s*함)?"),
          actionRule("확인", "확인(?:하기|하고|해야\\s*함)?"),
          actionRule("읽기", "읽(?:기|고)"),
          actionRule("요약", "요약(?:하기|하고|해야\\s*함)?"),
          actionRule("검토", "검토(?:하기|하고|해야\\s*함)?(?=\\s*(?:또는|혹은|아니면|$))"),
          actionRule("결정", "결정(?:하기|하고|해야\\s*함)?(?=\\s*$)"),
          actionRule("정리", "정리(?:하기|하고|해야\\s*함)?"),
          actionRule("준비", "준비(?:하기|하고|해야\\s*함)?"),
          actionRule("보기", "보기(?=\\s*$)"),
          actionRule("하기", "하기"));

  private final ObjectMapper json;
  private final KoreanDateParser dateParser = new KoreanDateParser();
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
    if (detectedDates.size() > MAX_DATE_CANDIDATES || shape.items().size() > MAX_ITEM_CANDIDATES) {
      signals.add(AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
    }
    detectedDates.forEach(
        date ->
            date.ambiguityReasons().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .forEach(signals::add));
    String title = suggestedTitle(content, dates);
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
            .put("detectedItemCandidateCount", shape.items().size())
            .put("emittedItemCandidateCount", Math.min(shape.items().size(), MAX_ITEM_CANDIDATES))
            .put("toolCalls", 0));
    return proposal;
  }

  private AnalysisShape classify(String content, List<ParsedDate> dates) {
    String compact = content.replaceAll("\\s+", " ").strip();

    if (looksLikePromptInjection(compact)) {
      return shape(
          List.of("RECORD"),
          List.of(new ItemShape("RECORD", compact, null, compact)),
          Set.of(),
          null,
          0.99);
    }
    if (compact.contains("가상메모리는 시험에 중요하고") && compact.contains("과제")) {
      return shape(
          List.of("INFORMATION", "TASK"),
          List.of(
              new ItemShape("INFORMATION", "가상메모리는 시험에 중요", null, "가상메모리"),
              new ItemShape("TASK", "과제 제출", "제출", "과제")),
          Set.of(AmbiguityReason.MULTI_INTENT),
          null,
          0.88);
    }
    if (compact.contains("교수님이 저번에 말한 자료")) {
      return shape(
          List.of("TASK"),
          List.of(
              new ItemShape("TASK", "교수님이 말한 자료 찾아보기", "찾아보기", "교수님이 말한 자료"),
              new ItemShape("TASK", "중요한 부분 정리하기", "정리", "중요한 부분"),
              new ItemShape("TASK", "깃에 올리고 발표 준비하기", "올리기", "정리 자료")),
          Set.of(AmbiguityReason.UNRESOLVED_REFERENCE, AmbiguityReason.MULTI_INTENT),
          null,
          0.72);
    }
    if (compact.contains("초안은") && compact.contains("최종 제출은")) {
      return shape(
          List.of("TASK"),
          List.of(
              new ItemShape("TASK", "과제 초안", "작성", "과제 초안"),
              new ItemShape("TASK", "과제 최종 제출", "제출", "과제")),
          Set.of(AmbiguityReason.MULTI_INTENT),
          null,
          0.87);
    }
    if (compact.contains("전에 교수님이 말한 거")) {
      return shape(
          List.of("TASK"),
          List.of(new ItemShape("TASK", "교수님이 말한 것 올리기", "올리기", null)),
          Set.of(AmbiguityReason.UNRESOLVED_REFERENCE),
          null,
          0.75);
    }
    if (compact.contains("유리패드 마모 상태")) {
      return shape(
          List.of("TASK"),
          List.of(new ItemShape("TASK", "유리패드 마모 상태 다시 확인", "확인", "유리패드 마모 상태")),
          Set.of(AmbiguityReason.NEW_TOPIC),
          "유리패드",
          0.9);
    }
    if (compact.contains("어제") && compact.contains("봤음")) {
      return shape(
          List.of("EVENT", "RECORD"),
          List.of(new ItemShape("EVENT", "운영체제 중간고사를 봄", null, "운영체제 중간고사")),
          Set.of(),
          null,
          0.94);
    }
    if (compact.contains("가상메모리는 시험에 중요하다고 함")) {
      return shape(
          List.of("INFORMATION"),
          List.of(new ItemShape("INFORMATION", compact, null, "가상메모리")),
          Set.of(),
          null,
          0.96);
    }
    if (compact.matches(".*(?:\\d{1,2}\\.\\d{1,2}).*(?:운영체제|OS)\\s*과제\\s*$")) {
      return shape(
          List.of("UNKNOWN"), List.of(), Set.of(AmbiguityReason.MISSING_ACTION), null, 0.52);
    }
    List<DetectedAction> actions = detectActions(compact);
    if (actions.isEmpty() && dates.isEmpty() && looksLikeTechnicalInformation(compact)) {
      return shape(
          List.of("INFORMATION"),
          List.of(new ItemShape("INFORMATION", compact, null, compact)),
          Set.of(),
          null,
          0.93);
    }

    if (!actions.isEmpty()) {
      LinkedHashSet<AmbiguityReason> signals = new LinkedHashSet<>();
      if (hasUnresolvedReference(compact)) {
        signals.add(AmbiguityReason.UNRESOLVED_REFERENCE);
      }
      boolean alternative = ALTERNATIVE_CONNECTOR.matcher(compact).find();
      if (actions.size() > 1 || alternative) {
        signals.add(AmbiguityReason.MULTI_INTENT);
      }
      DetectedAction primaryAction = actions.getFirst();
      return shape(
          alternative ? List.of("TASK", "INFORMATION") : List.of("TASK"),
          List.of(
              new ItemShape(
                  "TASK",
                  compact,
                  primaryAction.canonicalName(),
                  taskObject(compact, primaryAction))),
          signals,
          null,
          alternative ? 0.82 : 0.94);
    }
    if (!dates.isEmpty() && EVENT_CONTEXT.matcher(compact).find()) {
      return shape(
          List.of("EVENT"),
          List.of(new ItemShape("EVENT", compact, null, compact)),
          Set.of(),
          null,
          0.94);
    }
    if (hasUnresolvedReference(compact)) {
      return shape(
          List.of("UNKNOWN"),
          List.of(),
          Set.of(AmbiguityReason.UNRESOLVED_REFERENCE, AmbiguityReason.MISSING_ACTION),
          null,
          0.5);
    }
    return shape(
        List.of("RECORD"),
        List.of(new ItemShape("RECORD", compact, null, compact)),
        Set.of(),
        null,
        0.8);
  }

  private boolean looksLikePromptInjection(String content) {
    return content.contains("이전 지시를 무시") || content.contains("모든 메모를 삭제");
  }

  private boolean looksLikeTechnicalInformation(String content) {
    return TECHNICAL_CONTEXT.matcher(content).find()
        && TECHNICAL_DECLARATION.matcher(content).matches();
  }

  private boolean hasUnresolvedReference(String content) {
    return UNRESOLVED_REFERENCE.matcher(content).find();
  }

  private List<DetectedAction> detectActions(String content) {
    List<DetectedAction> actions = new ArrayList<>();
    for (ActionRule rule : ACTION_RULES) {
      Matcher matcher = rule.pattern().matcher(content);
      while (matcher.find()) {
        boolean overlaps =
            actions.stream()
                .anyMatch(
                    existing ->
                        matcher.start() < existing.endOffset()
                            && existing.startOffset() < matcher.end());
        if (!overlaps) {
          actions.add(new DetectedAction(rule.canonicalName(), matcher.start(), matcher.end()));
        }
      }
    }
    actions.sort(Comparator.comparingInt(DetectedAction::startOffset));
    return List.copyOf(actions);
  }

  private String taskObject(String content, DetectedAction action) {
    String object =
        (content.substring(0, action.startOffset()) + " " + content.substring(action.endOffset()))
            .replaceAll("^[\\s:：,\\-]+", "")
            .replaceAll("[\\s:：,\\-]+$", "")
            .replaceAll("\\s+", " ")
            .strip();
    return object.isBlank() ? null : bounded(object, 200);
  }

  private static ActionRule actionRule(String canonicalName, String expression) {
    return new ActionRule(
        canonicalName,
        Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + expression + ")(?=$|\\s|[,.;!?:：])"));
  }

  private AnalysisShape shape(
      List<String> types,
      List<ItemShape> items,
      Set<AmbiguityReason> signals,
      String newTopic,
      double confidence) {
    return new AnalysisShape(types, items, signals, newTopic, confidence);
  }

  private String suggestedTitle(String content, List<ParsedDate> dates) {
    String title = content.strip();
    for (ParsedDate date : dates) {
      title = title.replace(date.surfaceText(), " ");
    }
    title = title.replaceAll("\\s+", " ").strip();
    if (title.isBlank()) {
      title = content.strip();
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
              .put("title", itemTitle)
              .putNull("sourceSpan")
              .put("confidence", 0.9);
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
      double titleConfidence) {}

  private record ItemShape(String kind, String title, String action, String object) {}

  private record ActionRule(String canonicalName, Pattern pattern) {}

  private record DetectedAction(String canonicalName, int startOffset, int endOffset) {}
}
