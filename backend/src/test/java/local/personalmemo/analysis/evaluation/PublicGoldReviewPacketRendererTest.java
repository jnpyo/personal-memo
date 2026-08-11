package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PublicGoldReviewPacketRendererTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final String EXACT_CSP =
      "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none';"
          + " connect-src 'none'; media-src 'none'; object-src 'none'; child-src 'none';"
          + " worker-src 'none'; manifest-src 'none'; base-uri 'none'; form-action 'none';"
          + " frame-ancestors 'none'";

  private final ObjectMapper json = new ObjectMapper();
  private final PublicGoldReviewPacketRenderer renderer = new PublicGoldReviewPacketRenderer(json);

  @Test
  void rendersActualPublicReleaseDeterministicallyWithOnlyOfflineStaticControls() throws Exception {
    JsonNode regression = resource(REGRESSION_RESOURCE);
    JsonNode challenge = resource(CHALLENGE_RESOURCE);

    byte[] first = renderer.render(regression, challenge);
    byte[] second = renderer.render(regression.deepCopy(), challenge.deepCopy());
    String html = new String(first, StandardCharsets.UTF_8);

    assertThat(first).isEqualTo(second);
    assertThat(html.getBytes(StandardCharsets.UTF_8)).isEqualTo(first);
    assertThat(html).startsWith("<!doctype html>\n").endsWith("</html>\n");
    assertThat(html).doesNotContain("\r");
    assertThat(html)
        .contains(
            "<meta charset=\"utf-8\">",
            "<meta http-equiv=\"Content-Security-Policy\" content=\"" + EXACT_CSP + "\">",
            "Canonical release SHA-256",
            "<dt>Total cases</dt><dd>24</dd>",
            "UTF16_CODE_UNIT [",
            "<mark>");
    assertThat(html.toLowerCase())
        .doesNotContain(
            "<script",
            "<form",
            "<input",
            "<button",
            "<link",
            "<iframe",
            " href=",
            " src=",
            "http://",
            "https://",
            "localstorage",
            "sessionstorage",
            "fetch(",
            "xmlhttprequest",
            "reviewertoken",
            "attestation",
            "manifest generation",
            "analyzerexpectedroute",
            "analyzerexpectedsignals",
            "expectedroute",
            "expectedsignals",
            "expectedtypes");

    assertForbiddenFixtureValuesAbsent(html, regression);
    assertForbiddenFixtureValuesAbsent(html, challenge);
  }

  @Test
  void manuallyAllowListsFieldsEscapesHtmlAndHighlightsWholeSupplementaryCharacters() {
    ObjectNode regression = packetFixture("packet-regression", "REGRESSION");
    regression.put("expectedRoute", "LOCAL_REVIEW");
    regression.put("analyzerExpectedRoute", "CLOUD_ENRICH");
    regression.putArray("expectedTypes").add("IDEA");
    regression.putArray("expectedSignals").add("TAG_CONFLICT");
    regression.putArray("analyzerExpectedSignals").add("TAG_CONFLICT").add("LOCAL_CLOUD_CONFLICT");
    ((ObjectNode) regression.at("/expectedDates/mentions/0"))
        .putArray("ambiguityReasons")
        .add("TAG_CONFLICT");
    regression.put(
        "notes",
        "poison-expected-tags-9ddf31 poison-analyzer-output-2c084a "
            + "poison-generated-report-3b17d0 poison-peer-review-73a4fe");
    ObjectNode challenge = packetFixture("packet-challenge", "VISIBLE_CHALLENGE");

    String html =
        new String(renderer.render(array(regression), array(challenge)), StandardCharsets.UTF_8);

    assertThat(html)
        .contains(
            "😀&lt;&gt;&amp;&quot;&#39; 내일",
            "&lt;item&gt;&amp;",
            "UTF16_CODE_UNIT [0, 2)",
            "<mark>😀</mark>",
            "UTF16_CODE_UNIT [8, 10)",
            "<mark>내일</mark>")
        .doesNotContain(
            "😀<>&\"' 내일",
            "<item>&",
            "poison-expected-tags-9ddf31",
            "poison-analyzer-output-2c084a",
            "poison-generated-report-3b17d0",
            "poison-peer-review-73a4fe",
            "LOCAL_REVIEW",
            "CLOUD_ENRICH",
            "TAG_CONFLICT",
            "LOCAL_CLOUD_CONFLICT",
            "IDEA");
  }

  @Test
  void rejectsBothStartAndEndOffsetsThatSplitAnEmojiSurrogatePair() {
    ObjectNode validRegression = packetFixture("packet-regression", "REGRESSION");
    ObjectNode challenge = packetFixture("packet-challenge", "VISIBLE_CHALLENGE");

    ObjectNode splitAtStart = validRegression.deepCopy();
    ObjectNode startSpan =
        (ObjectNode)
            splitAtStart.at(
                "/expectedItems/acceptableSets/0/allItems/0/sourceSpan/acceptedSpans/0");
    startSpan.put("start", 1).put("end", 2);
    assertThatThrownBy(() -> renderer.render(array(splitAtStart), array(challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("item source span must not split a UTF-16 surrogate pair.");

    ObjectNode splitAtEnd = validRegression.deepCopy();
    ObjectNode endSpan =
        (ObjectNode)
            splitAtEnd.at("/expectedItems/acceptableSets/0/allItems/0/sourceSpan/acceptedSpans/0");
    endSpan.put("start", 0).put("end", 1);
    assertThatThrownBy(() -> renderer.render(array(splitAtEnd), array(challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("item source span must not split a UTF-16 surrogate pair.");
  }

  @Test
  void rejectsLoneHighAndLowSurrogatesInRenderedScopedValues() {
    ObjectNode challenge = packetFixture("packet-challenge", "VISIBLE_CHALLENGE");

    ObjectNode loneHighInContent = packetFixture("packet-regression", "REGRESSION");
    String validContent = loneHighInContent.path("content").asText();
    loneHighInContent.put(
        "content", validContent.substring(0, 2) + '\ud800' + validContent.substring(3));
    assertThatThrownBy(() -> renderer.render(array(loneHighInContent), array(challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed UTF-16");

    ObjectNode loneLowInGold = packetFixture("packet-regression", "REGRESSION");
    ((ObjectNode) loneLowInGold.at("/expectedItems/acceptableSets/0/suggestedTitle"))
        .put("value", "\udc00");
    ((ObjectNode) loneLowInGold.at("/expectedItems/acceptableSets/0/allItems/0/title"))
        .put("value", "\udc00");
    assertThatThrownBy(() -> renderer.render(array(loneLowInGold), array(challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed UTF-16");
  }

  @Test
  void rejectsNullCharactersAndPreservesCarriageReturnsAsCharacterReferences() {
    ObjectNode challenge = packetFixture("packet-challenge", "VISIBLE_CHALLENGE");
    ObjectNode withNull = packetFixture("packet-regression", "REGRESSION");
    ((ObjectNode) withNull.at("/expectedItems/acceptableSets/0/suggestedTitle"))
        .put("value", "unsafe\0value");
    ((ObjectNode) withNull.at("/expectedItems/acceptableSets/0/allItems/0/title"))
        .put("value", "unsafe\0value");
    assertThatThrownBy(() -> renderer.render(array(withNull), array(challenge)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("U+0000");

    ObjectNode withCarriageReturn = packetFixture("packet-regression", "REGRESSION");
    ((ObjectNode) withCarriageReturn.at("/expectedItems/acceptableSets/0/suggestedTitle"))
        .put("value", "line-1\rline-2");
    ((ObjectNode) withCarriageReturn.at("/expectedItems/acceptableSets/0/allItems/0/title"))
        .put("value", "line-1\rline-2");
    String html =
        new String(
            renderer.render(array(withCarriageReturn), array(challenge)), StandardCharsets.UTF_8);
    assertThat(html).contains("line-1&#13;line-2").doesNotContain("\r");
  }

  @Test
  void rejectsParserAndPresentationUnsafeControlCharactersInScopedGold() {
    ObjectNode challenge = packetFixture("packet-challenge", "VISIBLE_CHALLENGE");

    for (char control : new char[] {'\u0001', '\u000b', '\u000c', '\u007f', '\u0085'}) {
      ObjectNode regression = packetFixture("packet-regression", "REGRESSION");
      String poison = "before" + control + "after";
      ((ObjectNode) regression.at("/expectedItems/acceptableSets/0/suggestedTitle"))
          .put("value", poison);
      ((ObjectNode) regression.at("/expectedItems/acceptableSets/0/allItems/0/title"))
          .put("value", poison);

      assertThatThrownBy(() -> renderer.render(array(regression), array(challenge)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported control character");
    }
  }

  @Test
  void strictPublicContractRejectsArbitraryAnalyzerReportPeerAndTagFields() {
    ObjectNode regression = packetFixture("packet-regression", "REGRESSION");
    regression.putObject("expectedTags").put("poison", "poison-expected-tags-field-84ad");
    regression.putObject("analyzerOutput").put("poison", "poison-analyzer-field-84ad");
    regression.putObject("generatedReport").put("poison", "poison-report-field-84ad");
    regression.putObject("peerReview").put("poison", "poison-peer-field-84ad");

    assertThatThrownBy(
            () ->
                renderer.render(
                    array(regression),
                    array(packetFixture("packet-challenge", "VISIBLE_CHALLENGE"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid version-2 gold");
  }

  @Test
  void explicitRunnerReaderRejectsMalformedUtf8BomAndLinkedInput(@TempDir Path temporary)
      throws Exception {
    Path regular = temporary.resolve("regular.json");
    Files.write(regular, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.readRegularUtf8(
                    temporary, Path.of("regular.json"), 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");

    Files.write(regular, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'});
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.readRegularUtf8(
                    temporary, Path.of("regular.json"), 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BOM");

    Path outside = temporary.resolve("outside.json");
    Files.writeString(outside, "{}", StandardCharsets.UTF_8);
    Path linked = temporary.resolve("linked.json");
    createSymbolicLinkOrAbort(linked, outside);
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.readRegularUtf8(
                    temporary, Path.of("linked.json"), 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("link or reparse point");
  }

  @Test
  void explicitRunnerParserRejectsDuplicateKeysAndTrailingRootTokens() {
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.parseStrictJson(
                    "{\"split\":\"REGRESSION\",\"split\":\"VISIBLE_CHALLENGE\"}"
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not valid JSON");

    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.parseStrictJson(
                    "{} {}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not valid JSON");
  }

  @Test
  void explicitRunnerNameMissesEveryDefaultSurefirePattern() {
    String runnerName = PublicGoldReviewPacketRunner.class.getSimpleName();

    assertThat(runnerName)
        .doesNotStartWith("Test")
        .doesNotEndWith("Test")
        .doesNotEndWith("Tests")
        .doesNotEndWith("TestCase");
  }

  @Test
  void explicitRunnerRejectsSourceOrBundledMirrorDriftForEveryPublicInput() throws Exception {
    for (String resource : List.of(REGRESSION_RESOURCE, CHALLENGE_RESOURCE, CASE_SCHEMA_RESOURCE)) {
      JsonNode repository = resource(resource);
      ObjectNode sourceDrift = json.createObjectNode().put("poison", "source-drift");
      ObjectNode bundledDrift = json.createObjectNode().put("poison", "bundled-drift");

      assertThatThrownBy(
              () -> PublicGoldReviewPacketRunner.requireMirror(repository, sourceDrift, repository))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("differs from the checked-out source");
      assertThatThrownBy(
              () ->
                  PublicGoldReviewPacketRunner.requireMirror(repository, repository, bundledDrift))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("differs from the checked-out source");
    }
  }

  @Test
  void explicitRunnerRejectsMalformedPinHeadChangeAndDirtyWorktree() {
    String pinned = "a".repeat(40);

    PublicGoldReviewPacketRunner.assertPinnedCleanCommit(pinned, pinned, new byte[0]);
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.assertPinnedCleanCommit(
                    "not-a-commit", pinned, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact current HEAD");
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.assertPinnedCleanCommit(
                    pinned, "b".repeat(40), new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact current HEAD");
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.assertPinnedCleanCommit(
                    pinned, pinned, "dirty-sentinel".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("worktree must be clean");
  }

  @Test
  void explicitRunnerRevalidatesAfterWritingTempAndBeforePublishing(@TempDir Path temporary)
      throws Exception {
    Path backend = temporary.resolve("backend");
    Files.createDirectories(backend);
    byte[] packet = "deterministic-packet\n".getBytes(StandardCharsets.UTF_8);
    AtomicBoolean revalidated = new AtomicBoolean();
    AtomicReference<byte[]> observedTemporaryBytes = new AtomicReference<>();

    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.writePacket(
                    backend,
                    packet,
                    () -> {
                      revalidated.set(true);
                      try (var paths = Files.list(backend.resolve("target/evaluation"))) {
                        Path staged =
                            paths
                                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                                .findFirst()
                                .orElseThrow();
                        observedTemporaryBytes.set(Files.readAllBytes(staged));
                      } catch (IOException exception) {
                        throw new IllegalStateException("Could not inspect staged packet.");
                      }
                      throw new IllegalArgumentException("second pin check failed");
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("second pin check failed");

    assertThat(revalidated).isTrue();
    assertThat(observedTemporaryBytes.get()).isEqualTo(packet);
    assertThat(backend.resolve("target/evaluation/public-v2-review-packet.html")).doesNotExist();
    try (var paths = Files.list(backend.resolve("target/evaluation"))) {
      assertThat(paths.toList()).isEmpty();
    }
  }

  @Test
  void explicitRunnerAtomicallyPublishesExactBytesAndLeavesNoTemporaryFile(@TempDir Path temporary)
      throws Exception {
    Path backend = temporary.resolve("backend");
    Files.createDirectories(backend);
    byte[] first = "first-packet\n".getBytes(StandardCharsets.UTF_8);
    byte[] replacement = "replacement-packet-😀\n".getBytes(StandardCharsets.UTF_8);
    AtomicBoolean firstRevalidated = new AtomicBoolean();
    AtomicBoolean replacementRevalidated = new AtomicBoolean();

    PublicGoldReviewPacketRunner.writePacket(backend, first, () -> firstRevalidated.set(true));
    PublicGoldReviewPacketRunner.writePacket(
        backend, replacement, () -> replacementRevalidated.set(true));

    Path output = backend.resolve("target/evaluation/public-v2-review-packet.html");
    assertThat(firstRevalidated).isTrue();
    assertThat(replacementRevalidated).isTrue();
    assertThat(Files.readAllBytes(output)).isEqualTo(replacement);
    try (var paths = Files.list(output.getParent())) {
      assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList())
          .isEmpty();
    }
  }

  @Test
  void explicitRunnerRemovesSafeStalePacketBeforeInitialVerification(@TempDir Path temporary)
      throws Exception {
    Path backend = temporary.resolve("backend");
    Path output = backend.resolve("target/evaluation/public-v2-review-packet.html");
    Files.createDirectories(output.getParent());
    Files.writeString(output, "stale-packet", StandardCharsets.UTF_8);
    AtomicBoolean stalePresentDuringVerification = new AtomicBoolean(true);

    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.prepareRun(
                    backend,
                    () -> {
                      stalePresentDuringVerification.set(Files.exists(output));
                      throw new IllegalArgumentException("initial pin check failed");
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initial pin check failed");

    assertThat(stalePresentDuringVerification).isFalse();
    assertThat(output).doesNotExist();
  }

  @Test
  void explicitRunnerRejectsLinkedOutputParent(@TempDir Path temporary) throws Exception {
    Path targetLinkedBackend = temporary.resolve("target-linked-backend");
    Path evaluationLinkedBackend = temporary.resolve("evaluation-linked-backend");
    Path outside = temporary.resolve("outside-directory");
    Files.createDirectories(targetLinkedBackend);
    Files.createDirectories(evaluationLinkedBackend.resolve("target"));
    Files.createDirectories(outside);
    createSymbolicLinkOrAbort(targetLinkedBackend.resolve("target"), outside);

    assertThatThrownBy(
            () -> PublicGoldReviewPacketRunner.requireSafeOutputDirectory(targetLinkedBackend))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("link or reparse point");

    createSymbolicLinkOrAbort(evaluationLinkedBackend.resolve("target/evaluation"), outside);
    assertThatThrownBy(
            () -> PublicGoldReviewPacketRunner.requireSafeOutputDirectory(evaluationLinkedBackend))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("link or reparse point");
  }

  @Test
  void explicitRunnerRejectsLinkedFinalAndTemporaryFilesWithoutTouchingTarget(
      @TempDir Path temporary) throws Exception {
    Path backend = temporary.resolve("backend");
    Path evaluation = backend.resolve("target/evaluation");
    Files.createDirectories(evaluation);
    Path outside = temporary.resolve("outside.txt");
    Files.writeString(outside, "outside-safe", StandardCharsets.UTF_8);

    Path finalOutput = evaluation.resolve("public-v2-review-packet.html");
    createSymbolicLinkOrAbort(finalOutput, outside);
    AtomicBoolean revalidated = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                PublicGoldReviewPacketRunner.writePacket(
                    backend, new byte[] {1, 2, 3}, () -> revalidated.set(true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a safe regular file");
    assertThat(revalidated).isFalse();
    assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("outside-safe");

    Files.delete(finalOutput);
    Path temporaryLink = evaluation.resolve("public-v2-review-packet-poison.tmp");
    createSymbolicLinkOrAbort(temporaryLink, outside);
    assertThatThrownBy(() -> PublicGoldReviewPacketRunner.requireSafeTemporaryFile(temporaryLink))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a safe regular file");
    assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("outside-safe");
  }

  private void assertForbiddenFixtureValuesAbsent(String html, JsonNode fixtures) {
    for (JsonNode fixture : fixtures) {
      assertThat(html).doesNotContain(fixture.path("notes").asText());
      for (String routeField : List.of("expectedRoute", "analyzerExpectedRoute")) {
        if (fixture.path(routeField).isTextual()) {
          assertThat(html).doesNotContain(fixture.path(routeField).asText());
        }
      }
      List<String> signals = new ArrayList<>();
      for (String signalField : List.of("expectedSignals", "analyzerExpectedSignals")) {
        for (JsonNode signal : fixture.path(signalField)) {
          signals.add(signal.asText());
        }
      }
      for (JsonNode mention : fixture.path("expectedDates").path("mentions")) {
        for (JsonNode signal : mention.path("ambiguityReasons")) {
          signals.add(signal.asText());
        }
      }
      for (String signal : signals) {
        assertThat(html).doesNotContain(signal);
      }
    }
  }

  private ObjectNode packetFixture(String id, String split) {
    String content = "😀<>&\"' 내일";
    int dateStart = content.indexOf("내일");
    ObjectNode fixture = json.createObjectNode();
    fixture.put("id", id);
    fixture.put("datasetVersion", "2");
    fixture.put("split", split);
    fixture.put("content", content);
    fixture.put("baseInstant", "2026-08-05T02:00:00Z");
    fixture.put("timeZone", "Asia/Seoul");
    fixture.put("expectedRoute", "LOCAL_REVIEW");
    fixture.putArray("expectedTypes").add("RECORD");
    fixture.putArray("expectedSignals");
    if ("REGRESSION".equals(split)) {
      fixture.put("analyzerExpectedRoute", "LOCAL_REVIEW");
      fixture.putArray("analyzerExpectedSignals");
    }

    ObjectNode dates = fixture.putObject("expectedDates");
    ObjectNode mention = dates.putArray("mentions").addObject();
    mention.put("goldId", "date-1");
    mention
        .putObject("sourceSpan")
        .put("start", dateStart)
        .put("end", dateStart + "내일".length())
        .put("unit", "UTF16_CODE_UNIT");
    mention.put("surfaceText", "내일");
    mention
        .putArray("acceptedInterpretations")
        .addObject()
        .put("precision", "DATE_ONLY")
        .put("value", "2026-08-06")
        .put("timeSpecified", false);
    mention.putArray("ambiguityReasons");
    mention.put("primary", true);
    dates.putArray("emittedCandidateGoldIds").add("date-1");
    dates.put("primaryGoldId", "date-1");

    ObjectNode expectedItems = fixture.putObject("expectedItems");
    expectedItems.put("resolution", "RESOLVED");
    ObjectNode set = expectedItems.putArray("acceptableSets").addObject();
    set.put("setId", "set-1");
    set.putObject("suggestedTitle").put("state", "VALUE").put("value", "<item>&");
    set.put("primaryItemGoldId", "item-1");
    ObjectNode item = set.putArray("allItems").addObject();
    item.put("goldId", "item-1");
    item.put("kind", "RECORD");
    item.putObject("title").put("state", "VALUE").put("value", "<item>&");
    item.putObject("action").put("state", "ABSENT");
    item.putObject("object").put("state", "VALUE").put("value", "😀");
    ObjectNode span = item.putObject("sourceSpan");
    span.put("requirement", "REQUIRED");
    span.putArray("acceptedSpans")
        .addObject()
        .put("start", 0)
        .put("end", 2)
        .put("unit", "UTF16_CODE_UNIT");
    set.putArray("emittedItemGoldIds").add("item-1");
    fixture.put("notes", "test-only packet fixture");
    return fixture;
  }

  private ArrayNode array(JsonNode value) {
    return json.createArrayNode().add(value);
  }

  private static void createSymbolicLinkOrAbort(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("Symbolic links are unavailable in this test environment.");
    }
  }

  private JsonNode resource(String resource) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Required test resource is missing.");
      }
      return json.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
