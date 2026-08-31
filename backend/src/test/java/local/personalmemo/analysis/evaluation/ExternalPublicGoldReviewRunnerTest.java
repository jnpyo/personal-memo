package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ExternalPublicGoldReviewRunnerTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String ZERO_COMMIT = "0".repeat(40);
  private static final String ONE_COMMIT = "1".repeat(40);

  @TempDir Path temporaryDirectory;

  private final ObjectMapper json = ExternalPublicGoldReviewRunner.strictJsonMapper();

  @Test
  void runnerClassIsExcludedFromDefaultSurefireNamePatterns() {
    String className = ExternalPublicGoldReviewRunner.class.getSimpleName();

    assertThat(className)
        .doesNotStartWith("Test")
        .doesNotEndWith("Test")
        .doesNotEndWith("TestCase");
  }

  @Test
  void configurationRequiresBothManifestPathsAndAnExactLowercaseCommit() {
    Map<String, String> valid =
        Map.of(
            ExternalPublicGoldReviewRunner.FIRST_MANIFEST_ENVIRONMENT,
            temporaryDirectory.resolve("a.json").toString(),
            ExternalPublicGoldReviewRunner.SECOND_MANIFEST_ENVIRONMENT,
            temporaryDirectory.resolve("b.json").toString(),
            ExternalPublicGoldReviewRunner.COMMIT_ENVIRONMENT,
            ZERO_COMMIT);

    assertThatCode(() -> ExternalPublicGoldReviewRunner.RunnerConfiguration.from(valid))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.RunnerConfiguration.from(
                    Map.of(
                        ExternalPublicGoldReviewRunner.FIRST_MANIFEST_ENVIRONMENT,
                        temporaryDirectory.resolve("a.json").toString(),
                        ExternalPublicGoldReviewRunner.COMMIT_ENVIRONMENT,
                        ZERO_COMMIT)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Required public-review runner environment is missing.");

    Map<String, String> uppercaseCommit = new java.util.HashMap<>(valid);
    uppercaseCommit.put(ExternalPublicGoldReviewRunner.COMMIT_ENVIRONMENT, "A".repeat(40));
    assertThatThrownBy(
            () -> ExternalPublicGoldReviewRunner.RunnerConfiguration.from(uppercaseCommit))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The candidate commit is invalid.");
  }

  @Test
  void acceptsTwoDistinctRegularExternalFiles() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path first = Files.writeString(temporaryDirectory.resolve("first.json"), "{}");
    Path second = Files.writeString(temporaryDirectory.resolve("second.json"), "{}");

    ExternalPublicGoldReviewRunner.ManifestPaths paths =
        ExternalPublicGoldReviewRunner.validateExternalManifestPaths(
            repository, first.toAbsolutePath(), second.toAbsolutePath());

    assertThat(paths.first()).isEqualTo(first.toRealPath());
    assertThat(paths.second()).isEqualTo(second.toRealPath());
  }

  @Test
  void rejectsRelativeInsideRepositoryEmptyOversizedAndNonRegularManifestPaths() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path valid = Files.writeString(temporaryDirectory.resolve("valid.json"), "{}");
    Path relative = Path.of("relative-review.json");
    Path internal = Files.writeString(repository.resolve("internal.json"), "{}");
    Path empty = Files.createFile(temporaryDirectory.resolve("empty.json"));
    Path oversized = temporaryDirectory.resolve("oversized.json");
    try (var channel =
        Files.newByteChannel(
            oversized,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
      channel.position(ExternalPublicGoldReviewRunner.MAXIMUM_MANIFEST_BYTES);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
    }
    Path directory = Files.createDirectory(temporaryDirectory.resolve("directory.json"));

    for (Path rejected : Set.of(relative, internal, empty, oversized, directory)) {
      assertThatThrownBy(
              () ->
                  ExternalPublicGoldReviewRunner.validateExternalManifestPaths(
                      repository, rejected, valid.toAbsolutePath()))
          .isInstanceOf(IllegalStateException.class)
          .satisfies(
              exception -> {
                assertThat(exception.getMessage()).doesNotContain(rejected.toString());
                assertThat(exception.getMessage()).doesNotContain(valid.toString());
              });
    }
  }

  @Test
  void rejectsTheSameFileAndHardLinkAliases() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path first = Files.writeString(temporaryDirectory.resolve("first.json"), "{}");

    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.validateExternalManifestPaths(
                    repository, first.toAbsolutePath(), first.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review manifests must be distinct files.");

    Path hardLink = temporaryDirectory.resolve("hard-link.json");
    try {
      Files.createLink(hardLink, first);
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("Hard links are unavailable on this test filesystem.");
    }
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.validateExternalManifestPaths(
                    repository, first.toAbsolutePath(), hardLink.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review manifests must be distinct files.");
  }

  @Test
  void rejectsSymbolicLinkPathComponentsWithoutEchoingThePath() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path realDirectory = Files.createDirectory(temporaryDirectory.resolve("real"));
    Path realManifest = Files.writeString(realDirectory.resolve("review.json"), "{}");
    Path valid = Files.writeString(temporaryDirectory.resolve("valid.json"), "{}");
    Path linkedDirectory = temporaryDirectory.resolve("linked");
    try {
      Files.createSymbolicLink(linkedDirectory, realDirectory);
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("Symbolic links are unavailable on this test filesystem.");
    }
    Path linkedManifest = linkedDirectory.resolve(realManifest.getFileName());

    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.validateExternalManifestPaths(
                    repository, linkedManifest.toAbsolutePath(), valid.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Symbolic or reparse links are not allowed in public review paths.")
        .satisfies(
            exception ->
                assertThat(exception.getMessage()).doesNotContain(linkedManifest.toString()));
  }

  @Test
  void strictReaderAcceptsUtf8AndRejectsEveryBomMalformedUtf8DuplicateKeysAndTrailingTokens()
      throws Exception {
    Path valid =
        Files.writeString(
            temporaryDirectory.resolve("valid.json"),
            "{\"memo\":\"한글🙂\"}",
            StandardCharsets.UTF_8);
    assertThat(
            ExternalPublicGoldReviewRunner.readStrictJson(
                valid, ExternalPublicGoldReviewRunner.MAXIMUM_MANIFEST_BYTES))
        .isEqualTo(json.readTree("{\"memo\":\"한글🙂\"}"));

    byte[][] byteOrderMarks = {
      {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'},
      {(byte) 0xfe, (byte) 0xff, 0, '{', 0, '}'},
      {(byte) 0xff, (byte) 0xfe, '{', 0, '}', 0},
      {0, 0, (byte) 0xfe, (byte) 0xff, 0, 0, 0, '{'},
      {(byte) 0xff, (byte) 0xfe, 0, 0, '{', 0, 0, 0}
    };
    for (int index = 0; index < byteOrderMarks.length; index++) {
      Path bom =
          Files.write(temporaryDirectory.resolve("bom-" + index + ".json"), byteOrderMarks[index]);
      assertGenericStrictReadFailure(bom);
    }

    Path malformed =
        Files.write(
            temporaryDirectory.resolve("malformed.json"),
            new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'});
    Path duplicate =
        Files.writeString(temporaryDirectory.resolve("duplicate.json"), "{\"x\":1,\"x\":2}");
    Path trailing = Files.writeString(temporaryDirectory.resolve("trailing.json"), "{} {\"x\":1}");
    assertGenericStrictReadFailure(malformed);
    assertGenericStrictReadFailure(duplicate);
    assertGenericStrictReadFailure(trailing);
  }

  @Test
  void pinCheckRejectsMismatchDirtyStateAndMalformedHead() {
    assertThatCode(
            () ->
                ExternalPublicGoldReviewRunner.assertPinnedCleanCommit(
                    ZERO_COMMIT, ZERO_COMMIT, new byte[0]))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.assertPinnedCleanCommit(
                    ZERO_COMMIT, ONE_COMMIT, new byte[0]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The candidate commit does not match the exact current HEAD.");
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.assertPinnedCleanCommit(
                    ZERO_COMMIT, ZERO_COMMIT, " M source.java\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The candidate worktree must be clean.");
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.assertPinnedCleanCommit(
                    ZERO_COMMIT, "not-a-commit", new byte[0]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The candidate commit does not match the exact current HEAD.");
  }

  @Test
  void synchronizationChecksFailClosedForEachRequiredResourceKind() throws Exception {
    JsonNode expected = json.readTree("{\"version\":1}");
    JsonNode drifted = json.readTree("{\"version\":2}");

    for (String message :
        Set.of(
            "The regression fixture resource is not synchronized.",
            "The visible-challenge fixture resource is not synchronized.",
            "The evaluation case schema resource is not synchronized.",
            "The evaluation review schema resource is not synchronized.")) {
      assertThatThrownBy(
              () -> ExternalPublicGoldReviewRunner.requireSynchronized(expected, drifted, message))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(message);
    }
  }

  @Test
  void repositoryResourceReaderRejectsMutableLinkedBytesWithoutEchoingPaths() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path contracts = Files.createDirectory(repository.resolve("contracts"));
    Path regular = Files.writeString(contracts.resolve("regular.json"), "{\"version\":1}");
    assertThat(
            ExternalPublicGoldReviewRunner.readStrictRepositoryJson(
                repository, Path.of("contracts/regular.json")))
        .isEqualTo(json.readTree("{\"version\":1}"));

    Path external =
        Files.writeString(temporaryDirectory.resolve("mutable.json"), "{\"version\":2}");
    Path linked = contracts.resolve("linked.json");
    try {
      Files.createSymbolicLink(linked, external);
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("Symbolic links are unavailable on this test filesystem.");
    }

    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.readStrictRepositoryJson(
                    repository, Path.of("contracts/linked.json")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("A synchronized repository review resource could not be read.")
        .satisfies(
            exception ->
                assertThat(exception.getMessage())
                    .doesNotContain(regular.toString())
                    .doesNotContain(linked.toString())
                    .doesNotContain(external.toString()));
  }

  @Test
  void verifierSummaryKeepsTheExactAggregateAllowlistAndRejectsClaimsOrSensitiveValues()
      throws Exception {
    JsonNode regression = resource(REGRESSION_RESOURCE);
    JsonNode challenge = resource(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("Reviewer-AA", digest, regression, challenge);
    ObjectNode second = review("Reviewer-BB", digest, regression, challenge);
    ObjectNode summary =
        new PublicGoldAdjudicationVerifier(json).verify(regression, challenge, first, second);

    assertThatCode(() -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(summary, 24))
        .doesNotThrowAnyException();
    assertThat(summary.propertyNames())
        .containsExactlyInAnyOrder(
            "summaryVersion",
            "status",
            "containsRawMemoContent",
            "reviewerCount",
            "caseCount",
            "fieldComparisonCount",
            "agreementCount",
            "disagreementCount",
            "acceptedByBothCount",
            "changeRequiredByEitherCount",
            "automaticResolutionApplied");

    ObjectNode extraField = summary.deepCopy();
    extraField.put("candidateCommit", ZERO_COMMIT);
    assertThatThrownBy(
            () -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(extraField, 24))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review summary shape is invalid.");

    for (String unauthorized :
        Set.of("PASS", "ADJUDICATED", "BINDING", "PROVIDER_READY", "Reviewer-AA")) {
      ObjectNode altered = summary.deepCopy();
      altered.put("status", unauthorized);
      assertThatThrownBy(
              () -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(altered, 24))
          .isInstanceOf(IllegalStateException.class)
          .satisfies(
              exception ->
                  assertThat(exception.getMessage())
                      .doesNotContain(unauthorized)
                      .doesNotContain("Reviewer-AA"));
    }
  }

  @Test
  void fixedSummaryTextDoesNotRejectSchemaValidOpaqueValuesThatCoincideWithStatusText()
      throws Exception {
    JsonNode regression = resource(REGRESSION_RESOURCE);
    JsonNode challenge = resource(CHALLENGE_RESOURCE);
    String digest = PublicEvaluationRelease.from(json, regression, challenge).digestSha256();
    ObjectNode first = review("CONSENSUS_ACCEPTED", digest, regression, challenge);
    ObjectNode second = review("NEEDS_HUMAN_RESOLUTION", digest, regression, challenge);
    first.put("releaseId", "CONSENSUS_ACCEPTED");
    second.put("releaseId", "CONSENSUS_ACCEPTED");
    first.put("labelPolicyVersion", "NEEDS_HUMAN_RESOLUTION");
    second.put("labelPolicyVersion", "NEEDS_HUMAN_RESOLUTION");

    ObjectNode summary =
        new PublicGoldAdjudicationVerifier(json).verify(regression, challenge, first, second);

    assertThatCode(() -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(summary, 24))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsHumanResolutionAggregatesAndRejectsStatusCountContradictions() {
    ObjectNode needsResolution = minimalSummary();
    needsResolution.put("status", "NEEDS_HUMAN_RESOLUTION");
    needsResolution.put("agreementCount", 71);
    needsResolution.put("disagreementCount", 1);
    needsResolution.put("acceptedByBothCount", 71);
    needsResolution.put("changeRequiredByEitherCount", 1);

    assertThatCode(
            () -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(needsResolution, 24))
        .doesNotThrowAnyException();

    ObjectNode falseConsensus = minimalSummary();
    falseConsensus.put("acceptedByBothCount", 71);
    falseConsensus.put("changeRequiredByEitherCount", 1);
    assertThatThrownBy(
            () -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(falseConsensus, 24))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review summary status is inconsistent with its aggregate counts.");

    ObjectNode falseResolution = minimalSummary();
    falseResolution.put("status", "NEEDS_HUMAN_RESOLUTION");
    assertThatThrownBy(
            () -> ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(falseResolution, 24))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review summary status is inconsistent with its aggregate counts.");

    ObjectNode truncatedOversizedCount = minimalSummary();
    truncatedOversizedCount.put("fieldComparisonCount", (1L << 32) + 72);
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(
                    truncatedOversizedCount, 24))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review summary boundary is invalid.");

    ObjectNode numericSummaryVersion = minimalSummary();
    numericSummaryVersion.put("summaryVersion", 1);
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.assertAggregateOnlySummary(
                    numericSummaryVersion, 24))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The public review summary boundary is invalid.");
  }

  @Test
  void atomicWriterIsDeterministicAndFailureCleanupRemovesFinalAndTemporaryFiles()
      throws Throwable {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Files.createDirectory(repository.resolve("backend"));
    Path report = repository.resolve("backend/target/evaluation/public-v2-review-summary.json");
    Path temporaryReport = report.resolveSibling("public-v2-review-summary.json.tmp");
    ObjectNode summary = minimalSummary();

    ExternalPublicGoldReviewRunner.runWithOutputCleanup(
        repository,
        report,
        temporaryReport,
        () ->
            ExternalPublicGoldReviewRunner.writeAtomically(
                repository, report, temporaryReport, summary, json));
    byte[] first = Files.readAllBytes(report);
    String serialized = new String(first, StandardCharsets.UTF_8);
    assertThat(serialized).doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");

    ExternalPublicGoldReviewRunner.runWithOutputCleanup(
        repository,
        report,
        temporaryReport,
        () ->
            ExternalPublicGoldReviewRunner.writeAtomically(
                repository, report, temporaryReport, summary, json));
    assertThat(Files.readAllBytes(report)).isEqualTo(first);
    assertThat(Files.exists(temporaryReport)).isFalse();

    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.runWithOutputCleanup(
                    repository,
                    report,
                    temporaryReport,
                    () -> {
                      Files.createDirectories(report.getParent());
                      Files.writeString(report, "stale-final");
                      Files.writeString(temporaryReport, "stale-temporary");
                      throw new IllegalStateException("synthetic failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic failure");
    assertThat(Files.exists(report)).isFalse();
    assertThat(Files.exists(temporaryReport)).isFalse();
  }

  @Test
  void outputBoundaryRejectsPoisonedParentLinksBeforeDeletingOrWriting() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path backend = Files.createDirectory(repository.resolve("backend"));
    Path external = Files.createDirectory(temporaryDirectory.resolve("external-target"));
    Path sentinel = Files.writeString(external.resolve("public-v2-review-summary.json"), "keep");
    try {
      Files.createSymbolicLink(backend.resolve("target"), external);
    } catch (IOException | UnsupportedOperationException | SecurityException exception) {
      Assumptions.abort("Symbolic links are unavailable on this test filesystem.");
    }
    Path report = backend.resolve("target/evaluation/public-v2-review-summary.json");
    Path temporaryReport = report.resolveSibling("public-v2-review-summary.json.tmp");

    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.runWithOutputCleanup(
                    repository, report, temporaryReport, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("A stale public review summary could not be removed.");
    assertThat(Files.readString(sentinel)).isEqualTo("keep");
  }

  private void assertGenericStrictReadFailure(Path path) {
    assertThatThrownBy(
            () ->
                ExternalPublicGoldReviewRunner.readStrictJson(
                    path, ExternalPublicGoldReviewRunner.MAXIMUM_MANIFEST_BYTES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("A public review manifest could not be read as strict UTF-8 JSON.")
        .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(path.toString()));
  }

  private ObjectNode review(
      String reviewerToken, String digest, JsonNode regression, JsonNode challenge) {
    ObjectNode review =
        json.createObjectNode()
            .put("reviewSchemaVersion", "1")
            .put("reviewKind", "PUBLIC_V2_DATE_ITEM_GOLD")
            .put("datasetVersion", "2")
            .put("releaseId", "public-v2-test-release")
            .put("releaseDigestSha256", digest)
            .put("labelPolicyVersion", "v2-date-item-gold-test-policy")
            .put("protocolVersion", "public-v2-gold-review-v1")
            .put("reviewerToken", reviewerToken);
    review
        .putArray("reviewScope")
        .add("DATE_MENTION_GOLD")
        .add("ITEM_GOLD")
        .add("ITEM_SOURCE_SPAN_GOLD");
    review
        .putObject("attestations")
        .put("humanReviewer", true)
        .put("independentReview", true)
        .put("analyzerOutputHidden", true)
        .put("otherReviewHidden", true)
        .put("publicSyntheticDataOnly", true);
    ArrayNode caseReviews = review.putArray("caseReviews");
    for (JsonNode fixtures : java.util.List.of(regression, challenge)) {
      for (JsonNode fixture : fixtures) {
        caseReviews
            .addObject()
            .put("caseId", fixture.path("id").asText())
            .put("dateMentionGold", "ACCEPT")
            .put("itemGold", "ACCEPT")
            .put("itemSourceSpanGold", "ACCEPT");
      }
    }
    return review;
  }

  private ObjectNode minimalSummary() {
    return json.createObjectNode()
        .put("summaryVersion", "1")
        .put("status", "CONSENSUS_ACCEPTED")
        .put("containsRawMemoContent", false)
        .put("reviewerCount", 2)
        .put("caseCount", 24)
        .put("fieldComparisonCount", 72)
        .put("agreementCount", 72)
        .put("disagreementCount", 0)
        .put("acceptedByBothCount", 72)
        .put("changeRequiredByEitherCount", 0)
        .put("automaticResolutionApplied", false);
  }

  private JsonNode resource(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("Test resource is missing.");
      }
      return json.readTree(input);
    }
  }
}
