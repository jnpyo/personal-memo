package local.personalmemo.analysis.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Renders the public v2 date/item gold as one deterministic, read-only offline HTML packet. */
final class PublicGoldReviewPacketRenderer {
  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none';"
          + " connect-src 'none'; media-src 'none'; object-src 'none'; child-src 'none';"
          + " worker-src 'none'; manifest-src 'none'; base-uri 'none'; form-action 'none';"
          + " frame-ancestors 'none'";

  private final ObjectMapper json;

  PublicGoldReviewPacketRenderer(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json").rebuild().build();
  }

  byte[] render(JsonNode regressionFixtures, JsonNode visibleChallengeFixtures) {
    PublicEvaluationRelease release =
        PublicEvaluationRelease.from(json, regressionFixtures, visibleChallengeFixtures);
    List<PacketCase> regression = packetCases(regressionFixtures);
    List<PacketCase> visibleChallenge = packetCases(visibleChallengeFixtures);
    require(
        regression.size() + visibleChallenge.size() == release.caseIds().size(),
        "The public release case count changed during packet rendering.");

    StringBuilder html = new StringBuilder(128 * 1024);
    appendHeader(html, release, regression.size(), visibleChallenge.size());
    appendSplit(html, "Regression", regression);
    appendSplit(html, "Visible challenge", visibleChallenge);
    html.append("</main>\n</body>\n</html>\n");
    return html.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static List<PacketCase> packetCases(JsonNode fixtures) {
    List<PacketCase> cases = new ArrayList<>();
    for (JsonNode fixture : fixtures) {
      cases.add(PacketCase.from(fixture));
    }
    return List.copyOf(cases);
  }

  private static void appendHeader(
      StringBuilder html,
      PublicEvaluationRelease release,
      int regressionCount,
      int visibleChallengeCount) {
    html.append("<!doctype html>\n")
        .append("<html lang=\"ko\">\n<head>\n")
        .append("<meta charset=\"utf-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        .append("<meta name=\"referrer\" content=\"no-referrer\">\n")
        .append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
        .append(CONTENT_SECURITY_POLICY)
        .append("\">\n")
        .append("<title>공개 v2 날짜·항목 gold 검토 패킷</title>\n")
        .append(
            "<style>\n"
                + ":root{color-scheme:light;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;line-height:1.55;background:#f4f6f8;color:#17212b}*{box-sizing:border-box}body{margin:0}main{max-width:1080px;margin:0 auto;padding:28px 18px 64px}h1,h2,h3,h4,h5{line-height:1.25}h1{font-size:1.8rem;margin:0 0 12px}h2{margin-top:42px;border-bottom:2px solid #52616b;padding-bottom:8px}.summary,.case-meta,.gold-meta{display:grid;grid-template-columns:minmax(150px,220px) 1fr;gap:6px 14px}.summary dt,.case-meta dt,.gold-meta dt{font-weight:700}.summary dd,.case-meta dd,.gold-meta dd{margin:0;min-width:0}.warning{padding:14px 16px;border:2px solid #8b5e00;background:#fff5d6;border-radius:10px}.case{margin:24px 0;padding:20px;background:#fff;border:1px solid #cbd3da;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.04)}.content,.span-view{display:block;white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f8fa;border:1px solid #d8dee4;border-radius:8px;padding:12px}.gold-block{margin:14px 0;padding:14px;border-left:4px solid #546e7a;background:#f8fafb}.gold-set{margin:18px 0;padding:14px;border:1px solid #bcc7cf;border-radius:8px}.gold-item{margin:12px 0;padding:12px;background:#f8fafb;border-radius:8px}.badge{display:inline-block;margin-left:6px;padding:2px 8px;border-radius:999px;background:#d9e8f5;font-size:.78rem;font-weight:700}.not-emitted{background:#eceff1}.empty{color:#52616b;font-style:italic}code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}mark{background:#ffe082;color:#111;padding:0 1px;outline:2px solid #7a5700}ul{padding-left:22px}@media(max-width:600px){main{padding:20px 10px 48px}.case{padding:14px}.summary,.case-meta,.gold-meta{grid-template-columns:1fr}.summary dd,.case-meta dd,.gold-meta dd{margin:0 0 8px}}\n"
                + "</style>\n")
        .append("</head>\n<body>\n<main>\n")
        .append("<header>\n<h1>공개 v2 날짜·항목 gold 검토 패킷</h1>\n")
        .append(
            "<p class=\"warning\"><strong>로컬 전용 정적 파일:</strong> 공개 합성 문장과 날짜·항목 gold만 읽기 전용으로 보여 줍니다. 외부 자원을 불러오지 않으며 입력·저장 기능이나 판정 생성 기능이 없습니다.</p>\n")
        .append(
            "<p>source span은 정확한 문장의 UTF-16 code unit 기준 반열림 구간 <code>[start, end)</code>입니다. 숫자 범위와 강조 표시를 함께 확인하세요.</p>\n")
        .append("<dl class=\"summary\">\n")
        .append("<dt>Dataset version</dt><dd><code>2</code></dd>\n")
        .append("<dt>Canonical release SHA-256</dt><dd><code>");
    appendEscaped(html, release.digestSha256());
    html.append("</code></dd>\n")
        .append("<dt>Total cases</dt><dd>")
        .append(release.caseIds().size())
        .append("</dd>\n")
        .append("<dt>Regression cases</dt><dd>")
        .append(regressionCount)
        .append("</dd>\n")
        .append("<dt>Visible challenge cases</dt><dd>")
        .append(visibleChallengeCount)
        .append("</dd>\n</dl>\n</header>\n");
  }

  private static void appendSplit(StringBuilder html, String heading, List<PacketCase> cases) {
    html.append("<section>\n<h2>");
    appendEscaped(html, heading);
    html.append("</h2>\n");
    for (PacketCase packetCase : cases) {
      appendCase(html, packetCase);
    }
    html.append("</section>\n");
  }

  private static void appendCase(StringBuilder html, PacketCase packetCase) {
    html.append("<article class=\"case\">\n<h3><code>");
    appendEscaped(html, packetCase.id());
    html.append("</code></h3>\n<dl class=\"case-meta\">\n<dt>Split</dt><dd><code>");
    appendEscaped(html, packetCase.split());
    html.append("</code></dd>\n<dt>Base instant</dt><dd><code>");
    appendEscaped(html, packetCase.baseInstant());
    html.append("</code></dd>\n<dt>Time zone</dt><dd><code>");
    appendEscaped(html, packetCase.timeZone());
    html.append(
        "</code></dd>\n</dl>\n<h4>Exact public synthetic content</h4>\n<pre class=\"content\">");
    appendEscaped(html, packetCase.content());
    html.append("</pre>\n");
    appendDates(html, packetCase);
    appendItems(html, packetCase);
    html.append("</article>\n");
  }

  private static void appendDates(StringBuilder html, PacketCase packetCase) {
    html.append("<section>\n<h4>Date gold</h4>\n<p>Emitted date IDs: ");
    appendIdList(html, packetCase.dates().emittedGoldIds());
    html.append("</p>\n");
    if (packetCase.dates().mentions().isEmpty()) {
      html.append("<p class=\"empty\">No annotated date mention.</p>\n</section>\n");
      return;
    }
    Set<String> emitted = new HashSet<>(packetCase.dates().emittedGoldIds());
    for (PacketDate date : packetCase.dates().mentions()) {
      html.append("<article class=\"gold-block\">\n<h5><code>");
      appendEscaped(html, date.goldId());
      html.append("</code>");
      if (date.primary()) {
        html.append("<span class=\"badge\">Primary</span>");
      }
      html.append(
          emitted.contains(date.goldId())
              ? "<span class=\"badge\">Emitted</span>"
              : "<span class=\"badge not-emitted\">Not emitted</span>");
      html.append("</h5>\n<dl class=\"gold-meta\">\n<dt>Surface</dt><dd>");
      appendEscaped(html, date.surfaceText());
      html.append("</dd>\n<dt>Source span</dt><dd><code>");
      appendSpanLabel(html, date.sourceSpan());
      html.append("</code></dd>\n</dl>\n");
      appendSpanView(html, packetCase.content(), date.sourceSpan());
      html.append("<h5>Accepted interpretations</h5>\n<ul>\n");
      for (PacketDateInterpretation interpretation : date.acceptedInterpretations()) {
        html.append("<li><code>precision=");
        appendEscaped(html, interpretation.precision());
        html.append("; value=");
        appendEscaped(html, interpretation.value() == null ? "null" : interpretation.value());
        html.append("; timeSpecified=")
            .append(interpretation.timeSpecified())
            .append("</code></li>\n");
      }
      html.append("</ul>\n</article>\n");
    }
    html.append("</section>\n");
  }

  private static void appendItems(StringBuilder html, PacketCase packetCase) {
    PacketItems items = packetCase.items();
    html.append(
        "<section>\n<h4>Item gold</h4>\n<dl class=\"gold-meta\">\n<dt>Resolution</dt><dd><code>");
    appendEscaped(html, items.resolution());
    html.append("</code></dd>\n</dl>\n");
    for (PacketItemSet set : items.acceptableSets()) {
      html.append("<article class=\"gold-set\">\n<h5>Acceptable set <code>");
      appendEscaped(html, set.setId());
      html.append("</code></h5>\n<dl class=\"gold-meta\">\n<dt>Suggested title</dt><dd>");
      appendExpectation(html, set.suggestedTitle());
      html.append("</dd>\n<dt>Primary item ID</dt><dd>");
      if (set.primaryItemGoldId() == null) {
        html.append("<code>null</code>");
      } else {
        html.append("<code>");
        appendEscaped(html, set.primaryItemGoldId());
        html.append("</code>");
      }
      html.append("</dd>\n<dt>Emitted item IDs</dt><dd>");
      appendIdList(html, set.emittedItemGoldIds());
      html.append("</dd>\n</dl>\n");
      if (set.allItems().isEmpty()) {
        html.append("<p class=\"empty\">This acceptable set contains no item.</p>\n");
      }
      Set<String> emitted = new HashSet<>(set.emittedItemGoldIds());
      for (PacketItem item : set.allItems()) {
        appendItem(html, packetCase.content(), item, emitted.contains(item.goldId()));
      }
      html.append("</article>\n");
    }
    html.append("</section>\n");
  }

  private static void appendItem(
      StringBuilder html, String content, PacketItem item, boolean emitted) {
    html.append("<article class=\"gold-item\">\n<h5><code>");
    appendEscaped(html, item.goldId());
    html.append("</code>")
        .append(
            emitted
                ? "<span class=\"badge\">Emitted</span>"
                : "<span class=\"badge not-emitted\">Not emitted</span>")
        .append("</h5>\n<dl class=\"gold-meta\">\n<dt>Kind</dt><dd><code>");
    appendEscaped(html, item.kind());
    html.append("</code></dd>\n<dt>Title</dt><dd>");
    appendExpectation(html, item.title());
    html.append("</dd>\n<dt>Action</dt><dd>");
    appendExpectation(html, item.action());
    html.append("</dd>\n<dt>Object</dt><dd>");
    appendExpectation(html, item.object());
    html.append("</dd>\n<dt>Source span requirement</dt><dd><code>");
    appendEscaped(html, item.sourceSpan().requirement());
    html.append("</code></dd>\n</dl>\n");
    if (item.sourceSpan().acceptedSpans().isEmpty()) {
      html.append("<p class=\"empty\">No accepted source span.</p>\n");
    }
    for (PacketSpan span : item.sourceSpan().acceptedSpans()) {
      html.append("<p><code>");
      appendSpanLabel(html, span);
      html.append("</code></p>\n");
      appendSpanView(html, content, span);
    }
    html.append("</article>\n");
  }

  private static void appendExpectation(StringBuilder html, PacketTextExpectation expectation) {
    html.append("<code>");
    appendEscaped(html, expectation.state());
    html.append("</code>");
    if (expectation.value() != null) {
      html.append(" — ");
      appendEscaped(html, expectation.value());
    }
  }

  private static void appendIdList(StringBuilder html, List<String> ids) {
    if (ids.isEmpty()) {
      html.append("<code>none</code>");
      return;
    }
    for (int index = 0; index < ids.size(); index++) {
      if (index > 0) {
        html.append(", ");
      }
      html.append("<code>");
      appendEscaped(html, ids.get(index));
      html.append("</code>");
    }
  }

  private static void appendSpanLabel(StringBuilder html, PacketSpan span) {
    html.append("UTF16_CODE_UNIT [")
        .append(span.start())
        .append(", ")
        .append(span.end())
        .append(")");
  }

  private static void appendSpanView(StringBuilder html, String content, PacketSpan span) {
    validateSpan(content, span);
    html.append("<code class=\"span-view\">");
    appendEscaped(html, content.substring(0, span.start()));
    html.append("<mark>");
    appendEscaped(html, content.substring(span.start(), span.end()));
    html.append("</mark>");
    appendEscaped(html, content.substring(span.end()));
    html.append("</code>\n");
  }

  private static void appendEscaped(StringBuilder html, String value) {
    Objects.requireNonNull(value, "value");
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        require(
            index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)),
            "A rendered value contains malformed UTF-16.");
        html.append(current).append(value.charAt(++index));
        continue;
      }
      require(!Character.isLowSurrogate(current), "A rendered value contains malformed UTF-16.");
      require(
          !isUnsupportedControl(current),
          "A rendered value contains an unsupported control character.");
      switch (current) {
        case '\0' -> throw new IllegalArgumentException("A rendered value contains U+0000.");
        case '\r' -> html.append("&#13;");
        case '&' -> html.append("&amp;");
        case '<' -> html.append("&lt;");
        case '>' -> html.append("&gt;");
        case '"' -> html.append("&quot;");
        case '\'' -> html.append("&#39;");
        default -> html.append(current);
      }
    }
  }

  private static boolean isUnsupportedControl(char value) {
    return (value > '\0' && value < ' ' && value != '\t' && value != '\n' && value != '\r')
        || (value >= '\u007f' && value <= '\u009f');
  }

  private static void validateSpan(String content, PacketSpan span) {
    require(span.start() >= 0, "A source span start is negative.");
    require(span.end() > span.start(), "A source span is empty or reversed.");
    require(span.end() <= content.length(), "A source span exceeds UTF-16 content bounds.");
    require(
        !splitsSurrogatePair(content, span.start()) && !splitsSurrogatePair(content, span.end()),
        "A source span splits a UTF-16 surrogate pair.");
  }

  private static boolean splitsSurrogatePair(String content, int offset) {
    return offset > 0
        && offset < content.length()
        && Character.isHighSurrogate(content.charAt(offset - 1))
        && Character.isLowSurrogate(content.charAt(offset));
  }

  private static String requiredText(JsonNode node, String field) {
    require(node.isTextual() && !node.asText().isBlank(), field + " is invalid.");
    return node.asText();
  }

  private static int requiredInt(JsonNode node, String field) {
    require(node.isIntegralNumber() && node.canConvertToInt(), field + " is invalid.");
    return node.asInt();
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    require(node.isBoolean(), field + " is invalid.");
    return node.asBoolean();
  }

  private static List<String> textList(JsonNode values, String field) {
    require(values.isArray(), field + " is invalid.");
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      result.add(requiredText(value, field));
    }
    return List.copyOf(result);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private record PacketCase(
      String id,
      String split,
      String content,
      String baseInstant,
      String timeZone,
      PacketDates dates,
      PacketItems items) {
    static PacketCase from(JsonNode fixture) {
      String content = requiredText(fixture.path("content"), "content");
      return new PacketCase(
          requiredText(fixture.path("id"), "id"),
          requiredText(fixture.path("split"), "split"),
          content,
          requiredText(fixture.path("baseInstant"), "baseInstant"),
          requiredText(fixture.path("timeZone"), "timeZone"),
          PacketDates.from(fixture.path("expectedDates"), content),
          PacketItems.from(fixture.path("expectedItems"), content));
    }
  }

  private record PacketDates(List<PacketDate> mentions, List<String> emittedGoldIds) {
    PacketDates {
      mentions = List.copyOf(mentions);
      emittedGoldIds = List.copyOf(emittedGoldIds);
    }

    static PacketDates from(JsonNode node, String content) {
      List<PacketDate> mentions = new ArrayList<>();
      for (JsonNode mention : node.path("mentions")) {
        mentions.add(PacketDate.from(mention, content));
      }
      return new PacketDates(
          mentions, textList(node.path("emittedCandidateGoldIds"), "emitted date IDs"));
    }
  }

  private record PacketDate(
      String goldId,
      PacketSpan sourceSpan,
      String surfaceText,
      List<PacketDateInterpretation> acceptedInterpretations,
      boolean primary) {
    PacketDate {
      acceptedInterpretations = List.copyOf(acceptedInterpretations);
    }

    static PacketDate from(JsonNode node, String content) {
      PacketSpan span = PacketSpan.from(node.path("sourceSpan"), content);
      String surface = requiredText(node.path("surfaceText"), "date surfaceText");
      require(
          content.substring(span.start(), span.end()).equals(surface),
          "A date source span does not select its exact surface text.");
      List<PacketDateInterpretation> interpretations = new ArrayList<>();
      for (JsonNode interpretation : node.path("acceptedInterpretations")) {
        interpretations.add(PacketDateInterpretation.from(interpretation));
      }
      return new PacketDate(
          requiredText(node.path("goldId"), "date goldId"),
          span,
          surface,
          interpretations,
          requiredBoolean(node.path("primary"), "date primary"));
    }
  }

  private record PacketDateInterpretation(String precision, String value, boolean timeSpecified) {
    static PacketDateInterpretation from(JsonNode node) {
      JsonNode value = node.path("value");
      require(value.isNull() || value.isTextual(), "A date interpretation value is invalid.");
      return new PacketDateInterpretation(
          requiredText(node.path("precision"), "date precision"),
          value.isNull() ? null : value.asText(),
          requiredBoolean(node.path("timeSpecified"), "date timeSpecified"));
    }
  }

  private record PacketItems(String resolution, List<PacketItemSet> acceptableSets) {
    PacketItems {
      acceptableSets = List.copyOf(acceptableSets);
    }

    static PacketItems from(JsonNode node, String content) {
      List<PacketItemSet> sets = new ArrayList<>();
      for (JsonNode set : node.path("acceptableSets")) {
        sets.add(PacketItemSet.from(set, content));
      }
      return new PacketItems(requiredText(node.path("resolution"), "item resolution"), sets);
    }
  }

  private record PacketItemSet(
      String setId,
      PacketTextExpectation suggestedTitle,
      String primaryItemGoldId,
      List<PacketItem> allItems,
      List<String> emittedItemGoldIds) {
    PacketItemSet {
      allItems = List.copyOf(allItems);
      emittedItemGoldIds = List.copyOf(emittedItemGoldIds);
    }

    static PacketItemSet from(JsonNode node, String content) {
      JsonNode primary = node.path("primaryItemGoldId");
      require(primary.isNull() || primary.isTextual(), "The primary item ID is invalid.");
      List<PacketItem> allItems = new ArrayList<>();
      for (JsonNode item : node.path("allItems")) {
        allItems.add(PacketItem.from(item, content));
      }
      return new PacketItemSet(
          requiredText(node.path("setId"), "item setId"),
          PacketTextExpectation.from(node.path("suggestedTitle")),
          primary.isNull() ? null : requiredText(primary, "primary item ID"),
          allItems,
          textList(node.path("emittedItemGoldIds"), "emitted item IDs"));
    }
  }

  private record PacketItem(
      String goldId,
      String kind,
      PacketTextExpectation title,
      PacketTextExpectation action,
      PacketTextExpectation object,
      PacketSpanExpectation sourceSpan) {
    static PacketItem from(JsonNode node, String content) {
      return new PacketItem(
          requiredText(node.path("goldId"), "item goldId"),
          requiredText(node.path("kind"), "item kind"),
          PacketTextExpectation.from(node.path("title")),
          PacketTextExpectation.from(node.path("action")),
          PacketTextExpectation.from(node.path("object")),
          PacketSpanExpectation.from(node.path("sourceSpan"), content));
    }
  }

  private record PacketTextExpectation(String state, String value) {
    static PacketTextExpectation from(JsonNode node) {
      String state = requiredText(node.path("state"), "text expectation state");
      JsonNode value = node.path("value");
      if ("VALUE".equals(state)) {
        return new PacketTextExpectation(state, requiredText(value, "text expectation value"));
      }
      require(value.isMissingNode(), "A non-value text expectation contains a value.");
      return new PacketTextExpectation(state, null);
    }
  }

  private record PacketSpanExpectation(String requirement, List<PacketSpan> acceptedSpans) {
    PacketSpanExpectation {
      acceptedSpans = List.copyOf(acceptedSpans);
    }

    static PacketSpanExpectation from(JsonNode node, String content) {
      List<PacketSpan> spans = new ArrayList<>();
      for (JsonNode span : node.path("acceptedSpans")) {
        spans.add(PacketSpan.from(span, content));
      }
      return new PacketSpanExpectation(
          requiredText(node.path("requirement"), "source span requirement"), spans);
    }
  }

  private record PacketSpan(int start, int end) {
    static PacketSpan from(JsonNode node, String content) {
      require(
          "UTF16_CODE_UNIT".equals(requiredText(node.path("unit"), "source span unit")),
          "A source span unit is unsupported.");
      PacketSpan span =
          new PacketSpan(
              requiredInt(node.path("start"), "source span start"),
              requiredInt(node.path("end"), "source span end"));
      validateSpan(content, span);
      return span;
    }
  }
}
