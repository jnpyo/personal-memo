package local.personalmemo.analysis.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.KoreanDateParser.ParsedDate;

/**
 * Deterministic, side-effect-free item extraction over the original memo text.
 *
 * <p>All offsets are half-open Java {@link String} offsets. Java and JavaScript both expose these
 * offsets as UTF-16 code units, so callers must not normalize or compact the source before using a
 * returned span.
 */
public final class KoreanItemExtractor {
  public static final int REVIEW_ITEM_LIMIT = 3;

  private static final Set<String> ITEM_KINDS =
      Set.of("TASK", "EVENT", "INFORMATION", "IDEA", "RECORD");
  private static final String KOREAN_PARTICLE_ATOM =
      "(?:에서부터|으로부터|로부터|에게서|한테서|으로서|로서|으로써|로써|에다가|에다|"
          + "에서|에게|한테|께서|까지|부터|마다|조차|마저|처럼|같이|보다|만큼|대로|밖에|"
          + "뿐만|으로|하고|(?:이)?라도|(?:이)?랑|은|는|이|가|을|를|에|께|의|와|과|도|만|로|뿐)";
  private static final String KOREAN_PARTICLE_SUFFIX = "(?:" + KOREAN_PARTICLE_ATOM + "){0,3}";
  private static final Pattern ALTERNATIVE_CONNECTOR =
      Pattern.compile("(?<![\\p{L}\\p{N}])(?:또는|혹은|아니면)(?=$|\\s|[,.;!?:：])");
  private static final Pattern UNRESOLVED_REFERENCE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:"
              + "(?:(?:그거|그것|그걸|저거|저것|저걸|이거|이것|이걸|그때)"
              + "|(?:그|저|이)\\s+(?:문서|파일|자료|내용|건|일|것|거)"
              + "|저번\\s*(?:것|거|자료|문서)"
              + "|말한\\s+(?:거|것|자료|문서|파일|내용))"
              + KOREAN_PARTICLE_SUFFIX
              + "|말한\\s+그\\s+[가-힣A-Za-z0-9]+"
              + ")(?=$|\\s|[,.;!?])");
  private static final Pattern EVENT_CONTEXT =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:회의|동창회|약속|행사|수업|시험|면접|공연|모임|진료|예약|(?:중간|기말)?고사)(?=$|\\s|[은는이가을를에의와과도,.;!?])");
  private static final Pattern PAST_EVENT_END =
      Pattern.compile("(?<![\\p{L}\\p{N}])(?:봤음|봤다|치렀음|봄)\\s*$");
  private static final Pattern TECHNICAL_CONTEXT =
      Pattern.compile(
          "(?:API|DB|SQL|HTTP|캐시|서버|클라이언트|데이터|응답|요청|세션|토큰|권한|사용자별|인증|암호화|인터페이스|모듈|스키마)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INFORMATION_DECLARATION =
      Pattern.compile(
          ".*(?:해야\\s*함|되어야\\s*함|필요(?:함|하다|하다고\\s*함|하고|하며|하지만)|중요(?:함|하다|하다고\\s*함|하고|하며|하지만)|원칙임|규칙임|사실임)\\s*$");
  private static final Pattern INFORMATION_COORDINATION =
      Pattern.compile("(?:(?:중요|필요)(하고|하며|하지만)|(?:사실|원칙|규칙)(이고|이며|이지만))\\s+");
  private static final Pattern TOPIC_PREFIX = Pattern.compile("^(.{1,100}?)(?:은|는|이|가)(?=$|\\s)");
  private static final Pattern RECORD_PREFIX =
      Pattern.compile("^(?:(?:운동|식사|수면|독서|지출)\\s+)?기록\\s*[:：]?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NON_COMMAND_CONTINUATION =
      Pattern.compile("^\\s*(?:싶(?:은|다|어서|지만|음|었던)|좋(?:은|다|아서)|쉬운|어려운|편한|위한)(?=$|\\s|[,.;!?:：])");
  private static final Pattern LEADING_COORDINATOR =
      Pattern.compile("^(?:그리고|그다음|그 다음|이어서|이후에|다음으로)\\s+");
  private static final Pattern BARE_NEXT_COORDINATOR = Pattern.compile("^다음\\s+");
  private static final Pattern TRAILING_NEXT_CONNECTOR =
      Pattern.compile("^\\s+다음(?=$|\\s|[,.;!?:：])");
  private static final Pattern LEADING_CONDITION =
      Pattern.compile("^(?:(?:시간|여유)(?:이)?\\s*(?:되면|나면|있으면)|(?:가능|괜찮)(?:하면|으면))\\s+");
  private static final Pattern DESTINATION_SUFFIX =
      Pattern.compile(
          "(?:^|\\s)(?:깃|깃허브|git|github|구글\\s*드라이브|드라이브|공유\\s*폴더|폴더|저장소|리포지토리|서버|클라우드)(?:에|으로|로)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TRAILING_CASE_PARTICLE = Pattern.compile("(?:에게|한테|께|을|를|은|는|이|가)$");

  private static final List<ActionRule> ACTION_RULES =
      List.of(
          actionRule("장보기", "장보기", "장보기(?=\\s*[:：]|\\s*$)"),
          actionRule("전화하기", "전화하기", "전화\\s*하기"),
          actionRule("찾아보기", "찾아보기", "찾아보(?:기|고)"),
          actionRule("다시 보기", "다시 보기", "다시\\s*보기"),
          actionRule("올리기", "올리기", "올리(?:기|고)"),
          actionRule("만들기", "만들기", "만들(?:기|고)"),
          actionRule("잡기", "잡기", "잡(?:기|고)"),
          actionRule("마무리", "마무리", "마무리(?:하기|하고|해야\\s*함)?"),
          actionRule("제출", "제출", "제출(?:하기|하고|해야\\s*함)?"),
          actionRule("확인", "확인", "확인(?:하기|하고|해야\\s*함)?"),
          actionRule("읽기", "읽기", "읽(?:기|고)"),
          actionRule("요약", "요약하기", "요약(?:하기|하고|해야\\s*함)?"),
          actionRule("검토", "검토", "검토(?:하기|하고|해야\\s*함)?"),
          actionRule("결정", "결정", "결정(?:하기|하고|해야\\s*함)?"),
          actionRule("정리", "정리하기", "정리(?:하기|하고|한|해야\\s*함)?"),
          actionRule("준비", "준비하기", "준비(?:하기|하고|도\\s*하기|해야\\s*함)?"),
          actionRule("보기", "보기", "보기(?=\\s*$)"),
          actionRule("하기", "하기", "하기"));

  public Extraction extract(String content, List<ParsedDate> dates) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(dates, "dates");
    validateDates(content, dates);

    Span whole = trimmedSpan(content, 0, content.length());
    if (whole == null) {
      return new Extraction(List.of(), 0, Set.of(), false);
    }

    List<ActionMatch> actions = detectActions(content);
    int alternativeBranchCount = alternativeBranchCount(content);
    Alternative alternative = findAlternative(content, actions);
    EnumSet<AmbiguityReason> signals = EnumSet.noneOf(AmbiguityReason.class);
    List<ExtractedItem> items = new ArrayList<>();

    if (actions.isEmpty()) {
      items.add(classifyNonAction(content, dates, whole));
      boolean hasAlternative = alternativeBranchCount > 0;
      if (hasAlternative) {
        signals.add(AmbiguityReason.MULTI_INTENT);
      }
      return extraction(
          items, hasAlternative ? alternativeBranchCount : items.size(), signals, hasAlternative);
    }

    InformationPrefix informationPrefix = findInformationPrefix(content, actions.getFirst());
    int taskRegionStart = whole.start();
    if (informationPrefix != null) {
      items.add(informationItem(content, informationPrefix));
      taskRegionStart = informationPrefix.taskStart();
    }

    List<ActionMatch> emittedActions =
        alternative.present() ? List.of(alternative.primaryAction()) : actions;
    if (alternative.present() || actions.size() > 1 || informationPrefix != null) {
      signals.add(AmbiguityReason.MULTI_INTENT);
    }

    ActionMatch previousAction = null;
    boolean chainHasUnresolvedObject = false;
    for (int actionIndex = 0; actionIndex < emittedActions.size(); actionIndex++) {
      ActionMatch action = emittedActions.get(actionIndex);
      int candidateStart =
          previousAction == null
              ? taskRegionStart
              : Math.max(taskRegionStart, previousAction.end());
      Span span =
          actionSpan(
              content,
              candidateStart,
              action,
              dates,
              previousAction != null,
              actionIndex + 1 < emittedActions.size());
      boolean unresolved = containsUnresolvedReference(content, span);
      String object = unresolved ? null : taskObject(content, span, action, dates);
      boolean unresolvedByChain = object == null && chainHasUnresolvedObject;
      if (unresolved || unresolvedByChain) {
        signals.add(AmbiguityReason.UNRESOLVED_REFERENCE);
      } else if (object == null) {
        signals.add(AmbiguityReason.MISSING_OBJECT);
      }
      items.add(
          taskItem(
              content,
              span,
              action,
              object,
              unresolved || unresolvedByChain,
              alternative.present(),
              dates));
      chainHasUnresolvedObject = chainHasUnresolvedObject || unresolved || unresolvedByChain;
      previousAction = action;
    }

    int detectedItemCount =
        Math.max(actions.size(), alternativeBranchCount) + (informationPrefix == null ? 0 : 1);
    return extraction(items, detectedItemCount, signals, alternative.present());
  }

  private Extraction extraction(
      List<ExtractedItem> items,
      int detectedItemCount,
      EnumSet<AmbiguityReason> signals,
      boolean alternative) {
    if (items.size() > 1) {
      signals.add(AmbiguityReason.MULTI_INTENT);
    }
    if (detectedItemCount > REVIEW_ITEM_LIMIT) {
      signals.add(AmbiguityReason.CANDIDATE_LIMIT_EXCEEDED);
    }
    return new Extraction(items, detectedItemCount, signals, alternative);
  }

  private ExtractedItem classifyNonAction(String content, List<ParsedDate> dates, Span whole) {
    Matcher event = EVENT_CONTEXT.matcher(content);
    boolean hasEventContext = event.find();
    Matcher pastEvent = PAST_EVENT_END.matcher(content);
    if (pastEvent.find() && (!dates.isEmpty() || hasEventContext)) {
      String object = withoutDates(content, whole.start(), pastEvent.start(), dates);
      object = TRAILING_CASE_PARTICLE.matcher(object).replaceFirst("").strip();
      if (!object.isBlank()) {
        object = bounded(normalize(object), 200);
        String title = bounded(withObjectParticle(object) + " 봄", 200);
        return item("EVENT", title, null, object, whole, 0.9);
      }
    }
    if (!dates.isEmpty() && hasEventContext) {
      Span span = new Span(event.start(), event.end());
      String title = content.substring(span.start(), span.end());
      return item("EVENT", title, null, title, span, 0.9);
    }
    if (looksLikeInformation(content)) {
      String title = bounded(normalize(content.substring(whole.start(), whole.end())), 200);
      return item("INFORMATION", title, null, topicObject(title), whole, 0.86);
    }

    String title = bounded(normalize(content.substring(whole.start(), whole.end())), 200);
    Matcher record = RECORD_PREFIX.matcher(title);
    String object = record.matches() ? bounded(record.group(1).strip(), 200) : null;
    return item("RECORD", title, null, object, whole, 0.8);
  }

  private InformationPrefix findInformationPrefix(String content, ActionMatch firstAction) {
    Matcher matcher = INFORMATION_COORDINATION.matcher(content);
    InformationPrefix found = null;
    while (matcher.find() && matcher.end() <= firstAction.start()) {
      int connectorStart = matcher.start(1) >= 0 ? matcher.start(1) : matcher.start(2);
      Span informationSpan = trimmedSpan(content, 0, connectorStart);
      if (informationSpan != null
          && looksLikeInformation(content.substring(informationSpan.start(), matcher.end()))) {
        found = new InformationPrefix(informationSpan, skipDecorations(content, matcher.end()));
      }
    }
    return found;
  }

  private ExtractedItem informationItem(String content, InformationPrefix prefix) {
    String title =
        bounded(normalize(content.substring(prefix.span().start(), prefix.span().end())), 200);
    return item("INFORMATION", title, null, topicObject(title), prefix.span(), 0.82);
  }

  private String topicObject(String title) {
    Matcher topic = TOPIC_PREFIX.matcher(title);
    return topic.find() ? bounded(topic.group(1).strip(), 200) : null;
  }

  private boolean looksLikeInformation(String content) {
    String normalized = normalize(content);
    return INFORMATION_DECLARATION.matcher(normalized).matches()
        || (TECHNICAL_CONTEXT.matcher(normalized).find()
            && normalized.matches(".*(?:함|한다|된다|이다|임)$"));
  }

  private List<ActionMatch> detectActions(String content) {
    List<ActionMatch> matches = new ArrayList<>();
    for (int priority = 0; priority < ACTION_RULES.size(); priority++) {
      ActionRule rule = ACTION_RULES.get(priority);
      Matcher matcher = rule.pattern().matcher(content);
      while (matcher.find()) {
        if (!isNonCommandContinuation(content, matcher.end())) {
          matches.add(new ActionMatch(rule, matcher.start(), matcher.end(), priority));
        }
      }
    }
    matches.sort(
        Comparator.comparingInt(ActionMatch::start)
            .thenComparing(Comparator.comparingInt(ActionMatch::length).reversed())
            .thenComparingInt(ActionMatch::priority));

    List<ActionMatch> accepted = new ArrayList<>();
    for (ActionMatch candidate : matches) {
      boolean overlaps =
          accepted.stream()
              .anyMatch(
                  existing ->
                      candidate.start() < existing.end() && existing.start() < candidate.end());
      if (!overlaps) {
        accepted.add(candidate);
      }
    }
    accepted.sort(Comparator.comparingInt(ActionMatch::start));
    return List.copyOf(accepted);
  }

  private boolean isNonCommandContinuation(String content, int actionEnd) {
    return NON_COMMAND_CONTINUATION.matcher(content.substring(actionEnd)).find();
  }

  private Alternative findAlternative(String content, List<ActionMatch> actions) {
    Matcher connector = ALTERNATIVE_CONNECTOR.matcher(content);
    if (!connector.find() || actions.isEmpty()) {
      return Alternative.absent();
    }
    ActionMatch primary =
        actions.stream()
            .filter(action -> action.end() <= connector.start())
            .findFirst()
            .orElse(actions.getFirst());
    return new Alternative(true, primary);
  }

  private int alternativeBranchCount(String content) {
    Matcher connector = ALTERNATIVE_CONNECTOR.matcher(content);
    int connectorCount = 0;
    while (connector.find()) {
      connectorCount++;
    }
    return connectorCount == 0 ? 0 : connectorCount + 1;
  }

  private Span actionSpan(
      String content,
      int candidateStart,
      ActionMatch action,
      List<ParsedDate> dates,
      boolean sequential,
      boolean hasFollowingAction) {
    int start = skipDecorations(content, Math.min(candidateStart, action.start()));
    start = stripLeadingCoordinator(content, start, action.start(), sequential);
    int end = taskEnd(content, action);
    if (hasFollowingAction && end == action.end()) {
      Matcher connector = TRAILING_NEXT_CONNECTOR.matcher(content.substring(action.end()));
      if (connector.find()) {
        end = action.end() + connector.end();
      }
    }
    Span initial =
        Objects.requireNonNull(
            trimmedSpan(content, start, end),
            "An action match must produce a non-empty source span.");
    return trimLeadingDates(content, initial, action.start(), dates);
  }

  private int taskEnd(String content, ActionMatch action) {
    int cursor = action.end();
    while (cursor < content.length() && Character.isWhitespace(content.charAt(cursor))) {
      cursor++;
    }
    if (cursor < content.length()
        && (content.charAt(cursor) == ':' || content.charAt(cursor) == '：')) {
      int end = content.length();
      for (int index = cursor + 1; index < content.length(); index++) {
        char character = content.charAt(index);
        if (character == '\n' || character == ';' || character == '!' || character == '?') {
          end = index;
          break;
        }
      }
      return end;
    }
    return action.end();
  }

  private Span trimLeadingDates(
      String content, Span original, int evidenceEnd, List<ParsedDate> dates) {
    int start = original.start();
    boolean changed;
    do {
      changed = false;
      for (ParsedDate date : dates) {
        if (date.startOffset() >= start
            && date.endOffset() <= evidenceEnd
            && onlyDecorations(content, start, date.startOffset())) {
          start = skipDecorations(content, date.endOffset());
          changed = true;
          break;
        }
      }
    } while (changed);
    Span trimmed = trimmedSpan(content, start, original.end());
    return trimmed == null ? original : trimmed;
  }

  private ExtractedItem taskItem(
      String content,
      Span span,
      ActionMatch action,
      String object,
      boolean unresolved,
      boolean alternative,
      List<ParsedDate> dates) {
    String title = taskTitle(content, span, action, dates);
    double confidence = unresolved || alternative || object == null ? 0.68 : 0.9;
    return item("TASK", title, action.rule().canonicalAction(), object, span, confidence);
  }

  private String taskTitle(String content, Span span, ActionMatch action, List<ParsedDate> dates) {
    if (taskEnd(content, action) > action.end()) {
      return action.rule().titleAction();
    }
    String before = withoutDates(content, span.start(), action.start(), dates);
    String after =
        TRAILING_NEXT_CONNECTOR
            .matcher(content.substring(action.end(), span.end()))
            .replaceFirst("");
    String prefix = before.isBlank() ? "" : before + " ";
    return bounded(normalize(prefix + action.rule().titleAction() + after), 200);
  }

  private String taskObject(String content, Span span, ActionMatch action, List<ParsedDate> dates) {
    int taskEnd = taskEnd(content, action);
    if (taskEnd > action.end()) {
      String tail = content.substring(action.end(), taskEnd).replaceFirst("^[\\s:：]+", "");
      String normalizedTail = normalize(tail);
      return normalizedTail.isBlank() ? null : bounded(normalizedTail, 200);
    }

    String object = withoutDates(content, span.start(), action.start(), dates);
    object =
        object
            .replaceFirst("^(?:그리고|그다음|그 다음|이어서|이후에|다음으로)\\s+", "")
            .replaceAll("(?<![\\p{L}\\p{N}])(?:까지|부터|에)(?![\\p{L}\\p{N}])", " ")
            .replaceAll("^[\\s:：,;\\-]+", "")
            .replaceAll("[\\s:：,;\\-]+$", "")
            .strip();
    object = TRAILING_CASE_PARTICLE.matcher(object).replaceFirst("").strip();
    object = LEADING_CONDITION.matcher(object).replaceFirst("").strip();
    Matcher destination = DESTINATION_SUFFIX.matcher(object);
    if (destination.find()) {
      object = object.substring(0, destination.start()).strip();
    }
    return object.isBlank() ? null : bounded(normalize(object), 200);
  }

  private String withoutDates(String content, int start, int end, List<ParsedDate> dates) {
    StringBuilder value = new StringBuilder();
    int cursor = start;
    List<ParsedDate> ordered =
        dates.stream()
            .filter(date -> date.startOffset() >= start && date.endOffset() <= end)
            .sorted(Comparator.comparingInt(ParsedDate::startOffset))
            .toList();
    for (ParsedDate date : ordered) {
      value.append(content, cursor, date.startOffset()).append(' ');
      cursor = date.endOffset();
    }
    value.append(content, cursor, end);
    return normalize(value.toString());
  }

  private boolean containsUnresolvedReference(String content, Span span) {
    return UNRESOLVED_REFERENCE.matcher(content.substring(span.start(), span.end())).find();
  }

  private ExtractedItem item(
      String kind, String title, String action, String object, Span span, double confidence) {
    return new ExtractedItem(kind, title, action, object, span.start(), span.end(), confidence);
  }

  private int stripLeadingCoordinator(String content, int start, int maximum, boolean sequential) {
    if (start >= maximum) {
      return start;
    }
    String prefix = content.substring(start, maximum);
    Matcher matcher = LEADING_COORDINATOR.matcher(prefix);
    if (matcher.find()) {
      return start + matcher.end();
    }
    Matcher bareNext = BARE_NEXT_COORDINATOR.matcher(prefix);
    return sequential && bareNext.find() ? start + bareNext.end() : start;
  }

  private String withObjectParticle(String object) {
    int codePoint = object.codePointBefore(object.length());
    boolean hasFinalConsonant =
        codePoint >= 0xAC00 && codePoint <= 0xD7A3 && (codePoint - 0xAC00) % 28 != 0;
    return object + (hasFinalConsonant ? "을" : "를");
  }

  private int skipDecorations(String content, int start) {
    int cursor = start;
    while (cursor < content.length()) {
      char character = content.charAt(cursor);
      if (!Character.isWhitespace(character)
          && character != ','
          && character != ';'
          && character != ':'
          && character != '：'
          && character != '-') {
        break;
      }
      cursor++;
    }
    return cursor;
  }

  private boolean onlyDecorations(String content, int start, int end) {
    for (int index = start; index < end; index++) {
      char character = content.charAt(index);
      if (!Character.isWhitespace(character)
          && character != ','
          && character != ';'
          && character != ':'
          && character != '：'
          && character != '-') {
        return false;
      }
    }
    return true;
  }

  private Span trimmedSpan(String content, int start, int end) {
    int first = Math.max(0, start);
    int last = Math.min(content.length(), end);
    while (first < last && Character.isWhitespace(content.charAt(first))) {
      first++;
    }
    while (last > first && Character.isWhitespace(content.charAt(last - 1))) {
      last--;
    }
    return first < last ? new Span(first, last) : null;
  }

  private void validateDates(String content, List<ParsedDate> dates) {
    for (ParsedDate date : dates) {
      Objects.requireNonNull(date, "dates must not contain null");
      if (date.startOffset() < 0
          || date.endOffset() <= date.startOffset()
          || date.endOffset() > content.length()
          || !content.substring(date.startOffset(), date.endOffset()).equals(date.surfaceText())) {
        throw new IllegalArgumentException("Date offsets must reference the original memo text.");
      }
    }
  }

  private static ActionRule actionRule(
      String canonicalAction, String titleAction, String expression) {
    Pattern pattern =
        Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + expression + ")(?=$|\\s|[,.;!?:：])");
    return new ActionRule(canonicalAction, titleAction, pattern);
  }

  private String normalize(String value) {
    return value.replaceAll("\\s+", " ").strip();
  }

  private String bounded(String value, int maximum) {
    int codePointCount = value.codePointCount(0, value.length());
    if (codePointCount <= maximum) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maximum));
  }

  public record Extraction(
      List<ExtractedItem> detectedItems,
      int detectedItemCount,
      Set<AmbiguityReason> signals,
      boolean alternative) {
    public Extraction {
      Objects.requireNonNull(detectedItems, "detectedItems");
      Objects.requireNonNull(signals, "signals");
      if (detectedItemCount < detectedItems.size()) {
        throw new IllegalArgumentException(
            "Detected item count cannot be smaller than the emitted review items.");
      }
      detectedItems = List.copyOf(detectedItems);
      signals = Set.copyOf(signals);
    }

    public List<ExtractedItem> allItems() {
      return detectedItems;
    }
  }

  public record ExtractedItem(
      String kind,
      String title,
      String action,
      String object,
      int startOffset,
      int endOffset,
      double confidence) {
    public ExtractedItem {
      if (!ITEM_KINDS.contains(kind)) {
        throw new IllegalArgumentException("Unsupported item kind.");
      }
      if (title == null || title.isBlank()) {
        throw new IllegalArgumentException("An extracted item requires a title.");
      }
      if (startOffset < 0 || endOffset <= startOffset) {
        throw new IllegalArgumentException("An extracted item requires a non-empty source span.");
      }
      if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
        throw new IllegalArgumentException("Item confidence must be between zero and one.");
      }
    }
  }

  private record ActionRule(String canonicalAction, String titleAction, Pattern pattern) {}

  private record ActionMatch(ActionRule rule, int start, int end, int priority) {
    int length() {
      return end - start;
    }
  }

  private record Alternative(boolean present, ActionMatch primaryAction) {
    static Alternative absent() {
      return new Alternative(false, null);
    }
  }

  private record InformationPrefix(Span span, int taskStart) {}

  private record Span(int start, int end) {}
}
