package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DeterministicEvaluationBaselineTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String HOLDOUT_RESOURCE = "/fixtures/korean-memo-holdout-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final Path REPORT_PATH =
      Path.of("target", "evaluation", "deterministic-baseline.json");
  private static final List<String> FIXTURE_SPECIFIC_BRANCH_MARKERS =
      List.of(
          "가상메모리는 시험에 중요하고",
          "교수님이 저번에 말한 자료",
          "초안은",
          "최종 제출은",
          "전에 교수님이 말한 거",
          "유리패드 마모 상태",
          "가상메모리는 시험에 중요하다고 함",
          "이전 지시를 무시",
          "모든 메모를 삭제");

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final AnalysisProposalSchemaValidator proposalSchemaValidator =
      new Draft202012AnalysisProposalSchemaValidator();
  private final Schema caseSchema = loadCaseSchema();

  @Test
  void fixturesAreVersionedValidSeparatedAndSynchronized() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode holdout = fixtures(HOLDOUT_RESOURCE);

    assertThat(regression).hasSize(12);
    assertThat(holdout).hasSize(12);
    validateCases(regression, "REGRESSION");
    validateCases(holdout, "HOLDOUT");
    assertUniqueIdsAndContent(regression, holdout);
    assertHoldoutAvoidsFixtureSpecificBranches(holdout);

    assertResourceMatchesRepositoryCopy(REGRESSION_RESOURCE, "fixtures/korean-memo-cases.json");
    assertResourceMatchesRepositoryCopy(
        HOLDOUT_RESOURCE, "fixtures/korean-memo-holdout-cases.json");
    assertResourceMatchesRepositoryCopy(
        CASE_SCHEMA_RESOURCE, "contracts/korean-memo-evaluation-case.schema.json");
  }

  @Test
  void writesContentFreeBaselineAndEnforcesOnlyTheRegressionSafetyGate() throws Exception {
    Files.deleteIfExists(REPORT_PATH);
    List<JsonNode> fixtures = new ArrayList<>();
    fixtures(REGRESSION_RESOURCE).forEach(fixtures::add);
    fixtures(HOLDOUT_RESOURCE).forEach(fixtures::add);

    List<CaseResult> results = fixtures.stream().map(this::evaluate).toList();
    Metrics regression =
        Metrics.from(results.stream().filter(result -> result.isSplit("REGRESSION")).toList());
    Metrics holdout =
        Metrics.from(results.stream().filter(result -> result.isSplit("HOLDOUT")).toList());
    Metrics all = Metrics.from(results);
    regression.assertInternallyConsistent();
    holdout.assertInternallyConsistent();
    all.assertInternallyConsistent();
    assertThat(all.caseCount()).isEqualTo(regression.caseCount() + holdout.caseCount());
    assertThat(all.wrongLocalCount())
        .isEqualTo(regression.wrongLocalCount() + holdout.wrongLocalCount());
    assertThat(ratio(1, 2)).isEqualTo(0.5);
    assertThat(ratio(0, 0)).isZero();
    ObjectNode report = report(results, regression, holdout, all);

    String serialized = json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";

    assertThat(regression.schemaValidCount()).isEqualTo(regression.caseCount());
    assertThat(regression.wrongLocalCount()).isZero();
    assertThat(report.at("/gates/regression/passed").asBoolean()).isTrue();
    assertThat(report.at("/gates/holdout/enforced").asBoolean()).isFalse();
    assertThat(holdout.caseCount()).isEqualTo(12);
    for (String forbiddenField :
        List.of(
            "content",
            "rawMemo",
            "memoBody",
            "title",
            "notes",
            "contentHash",
            "ownerId",
            "userId")) {
      assertThat(report.findValue(forbiddenField)).isNull();
    }
    for (JsonNode fixture : fixtures) {
      assertThat(serialized).doesNotContain(fixture.path("content").asText());
    }
    Files.createDirectories(REPORT_PATH.getParent());
    Files.writeString(REPORT_PATH, serialized, StandardCharsets.UTF_8);
  }

  private CaseResult evaluate(JsonNode fixture) {
    String content = fixture.path("content").asText();
    ObjectNode proposal =
        analyzer.analyze(
            UUID.randomUUID(),
            1,
            content,
            Instant.parse(fixture.path("baseInstant").asText()),
            fixture.path("timeZone").asText());
    boolean schemaValid = isProposalSchemaValid(proposal);
    List<String> actualTypes = textValues(proposal.path("typeCandidates"), "value");
    Set<String> actualSignals =
        ambiguityGate.routingSignals(proposal).stream()
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    String actualRoute = ambiguityGate.route(ambiguityGate.routingSignals(proposal)).name();
    String expectedRoute = evaluationRoute(fixture);
    List<String> expectedTypes = textValues(fixture.path("expectedTypes"), null);
    Set<String> expectedSignals = new LinkedHashSet<>(evaluationSignals(fixture));
    boolean topTypeCorrect =
        !actualTypes.isEmpty()
            && !expectedTypes.isEmpty()
            && actualTypes.getFirst().equals(expectedTypes.getFirst());
    boolean signalsExact = actualSignals.equals(expectedSignals);
    boolean routeCorrect = actualRoute.equals(expectedRoute);
    boolean wrongLocal =
        "LOCAL_REVIEW".equals(actualRoute)
            && (!schemaValid || !routeCorrect || !topTypeCorrect || !signalsExact);

    return new CaseResult(
        fixture.path("id").asText(),
        fixture.path("split").asText(),
        schemaValid,
        expectedRoute,
        actualRoute,
        expectedTypes,
        actualTypes,
        expectedSignals,
        actualSignals,
        topTypeCorrect,
        signalsExact,
        wrongLocal);
  }

  private boolean isProposalSchemaValid(ObjectNode proposal) {
    try {
      proposalSchemaValidator.validate(proposal);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private String evaluationRoute(JsonNode fixture) {
    JsonNode analyzerExpectedRoute = fixture.path("analyzerExpectedRoute");
    return analyzerExpectedRoute.isTextual()
        ? analyzerExpectedRoute.asText()
        : fixture.path("expectedRoute").asText();
  }

  private List<String> evaluationSignals(JsonNode fixture) {
    JsonNode analyzerExpectedSignals = fixture.path("analyzerExpectedSignals");
    return analyzerExpectedSignals.isArray()
        ? textValues(analyzerExpectedSignals, null)
        : textValues(fixture.path("expectedSignals"), null);
  }

  private ObjectNode report(
      List<CaseResult> results, Metrics regression, Metrics holdout, Metrics all) {
    ObjectNode report =
        json.createObjectNode()
            .put("reportVersion", "1")
            .put("datasetVersion", "1")
            .put("analyzerVersion", analyzer.version())
            .put("routingPolicyVersion", ambiguityGate.version())
            .put("containsRawMemoContent", false);
    ObjectNode splits = report.putObject("splits");
    splits.set("regression", regression.toJson(json));
    splits.set("holdout", holdout.toJson(json));
    splits.set("all", all.toJson(json));

    ObjectNode gates = report.putObject("gates");
    gates
        .putObject("regression")
        .put("wrongLocalMaximum", 0)
        .put("actualWrongLocal", regression.wrongLocalCount())
        .put(
            "passed",
            regression.wrongLocalCount() == 0
                && regression.schemaValidCount() == regression.caseCount());
    gates
        .putObject("holdout")
        .put("enforced", false)
        .put("actualWrongLocal", holdout.wrongLocalCount())
        .put(
            "reason",
            "Visible synthetic challenge (HOLDOUT split name) is report-only until general rules are improved.");

    ArrayNode cases = report.putArray("cases");
    results.stream().map(result -> result.toJson(json)).forEach(cases::add);
    return report;
  }

  private void validateCases(JsonNode fixtures, String expectedSplit) {
    for (JsonNode fixture : fixtures) {
      assertThat(caseSchema.validate(fixture))
          .as("fixture contract for %s", fixture.path("id").asText())
          .isEmpty();
      assertThat(fixture.path("split").asText()).isEqualTo(expectedSplit);
    }
  }

  private void assertUniqueIdsAndContent(JsonNode regression, JsonNode holdout) {
    Set<String> ids = new HashSet<>();
    Set<String> contents = new HashSet<>();
    for (JsonNode fixtures : List.of(regression, holdout)) {
      for (JsonNode fixture : fixtures) {
        assertThat(ids.add(fixture.path("id").asText())).isTrue();
        assertThat(contents.add(fixture.path("content").asText())).isTrue();
      }
    }
  }

  private void assertHoldoutAvoidsFixtureSpecificBranches(JsonNode holdout) {
    for (JsonNode fixture : holdout) {
      String content = fixture.path("content").asText();
      assertThat(FIXTURE_SPECIFIC_BRANCH_MARKERS)
          .as(
              "holdout %s must not reuse FakeAnalyzer fixture branches",
              fixture.path("id").asText())
          .noneMatch(content::contains);
      assertThat(content.contains("어제") && content.contains("봤음")).isFalse();
      assertThat(content).doesNotMatch(".*(?:\\d{1,2}\\.\\d{1,2}).*(?:운영체제|OS)\\s*과제\\s*$");
    }
  }

  private void assertResourceMatchesRepositoryCopy(String resource, String relativePath)
      throws Exception {
    JsonNode bundled = readResource(resource);
    JsonNode repository = json.readTree(Files.readString(repositoryPath(relativePath)));
    assertThat(bundled).as("resource copy of %s", relativePath).isEqualTo(repository);
  }

  private Path repositoryPath(String relativePath) {
    Path fromBackend = Path.of("..", relativePath);
    if (Files.exists(fromBackend)) {
      return fromBackend;
    }
    Path fromRoot = Path.of(relativePath);
    if (Files.exists(fromRoot)) {
      return fromRoot;
    }
    throw new IllegalStateException("Repository file is missing: " + relativePath);
  }

  private JsonNode fixtures(String resource) throws Exception {
    JsonNode value = readResource(resource);
    assertThat(value.isArray()).isTrue();
    return value;
  }

  private JsonNode readResource(String resource) throws Exception {
    try (InputStream stream =
        DeterministicEvaluationBaselineTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Evaluation resource is missing: " + resource);
      }
      return json.readTree(stream);
    }
  }

  private Schema loadCaseSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try (InputStream stream =
        DeterministicEvaluationBaselineTest.class.getResourceAsStream(CASE_SCHEMA_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Evaluation case schema is missing.");
      }
      Schema schema = registry.getSchema(stream);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Evaluation case schema could not be loaded.", exception);
    }
  }

  private List<String> textValues(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(field == null ? value.asText() : value.path(field).asText());
    }
    return List.copyOf(values);
  }

  private static double ratio(long numerator, long denominator) {
    if (denominator == 0) {
      return 0;
    }
    return Math.round(((double) numerator / denominator) * 1_000_000d) / 1_000_000d;
  }

  private record CaseResult(
      String id,
      String split,
      boolean schemaValid,
      String expectedRoute,
      String actualRoute,
      List<String> expectedTypes,
      List<String> actualTypes,
      Set<String> expectedSignals,
      Set<String> actualSignals,
      boolean topTypeCorrect,
      boolean signalsExact,
      boolean wrongLocal) {
    private boolean isSplit(String expected) {
      return split.equals(expected);
    }

    private ObjectNode toJson(ObjectMapper json) {
      return json.createObjectNode()
          .put("id", id)
          .put("split", split)
          .put("schemaValid", schemaValid)
          .put("expectedRoute", expectedRoute)
          .put("actualRoute", actualRoute)
          .put("topTypeCorrect", topTypeCorrect)
          .put("signalsExact", signalsExact)
          .put("wrongLocal", wrongLocal);
    }
  }

  private record Metrics(
      int caseCount,
      int schemaValidCount,
      int expectedLocalActualLocal,
      int expectedLocalActualCloud,
      int expectedCloudActualLocal,
      int expectedCloudActualCloud,
      int wrongLocalCount,
      int topTypeCorrectCount,
      int expectedTypeCandidateCount,
      int matchedTypeCandidateCount,
      int signalTruePositive,
      int signalFalsePositive,
      int signalFalseNegative,
      int signalExactCaseCount) {
    private static Metrics from(List<CaseResult> results) {
      int schemaValid = 0;
      int expectedLocalActualLocal = 0;
      int expectedLocalActualCloud = 0;
      int expectedCloudActualLocal = 0;
      int expectedCloudActualCloud = 0;
      int wrongLocal = 0;
      int topTypeCorrect = 0;
      int expectedTypeCandidates = 0;
      int matchedTypeCandidates = 0;
      int signalTruePositive = 0;
      int signalFalsePositive = 0;
      int signalFalseNegative = 0;
      int signalExactCases = 0;

      for (CaseResult result : results) {
        if (result.schemaValid()) schemaValid++;
        if (result.wrongLocal()) wrongLocal++;
        if (result.topTypeCorrect()) topTypeCorrect++;
        if (result.signalsExact()) signalExactCases++;

        boolean expectedLocal = "LOCAL_REVIEW".equals(result.expectedRoute());
        boolean actualLocal = "LOCAL_REVIEW".equals(result.actualRoute());
        if (expectedLocal && actualLocal) expectedLocalActualLocal++;
        if (expectedLocal && !actualLocal) expectedLocalActualCloud++;
        if (!expectedLocal && actualLocal) expectedCloudActualLocal++;
        if (!expectedLocal && !actualLocal) expectedCloudActualCloud++;

        expectedTypeCandidates += result.expectedTypes().size();
        Set<String> actualTypeSet = new HashSet<>(result.actualTypes());
        matchedTypeCandidates +=
            result.expectedTypes().stream().filter(actualTypeSet::contains).count();

        Set<String> expectedSignals = result.expectedSignals();
        Set<String> actualSignals = result.actualSignals();
        signalTruePositive += actualSignals.stream().filter(expectedSignals::contains).count();
        signalFalsePositive +=
            actualSignals.stream().filter(signal -> !expectedSignals.contains(signal)).count();
        signalFalseNegative +=
            expectedSignals.stream().filter(signal -> !actualSignals.contains(signal)).count();
      }

      return new Metrics(
          results.size(),
          schemaValid,
          expectedLocalActualLocal,
          expectedLocalActualCloud,
          expectedCloudActualLocal,
          expectedCloudActualCloud,
          wrongLocal,
          topTypeCorrect,
          expectedTypeCandidates,
          matchedTypeCandidates,
          signalTruePositive,
          signalFalsePositive,
          signalFalseNegative,
          signalExactCases);
    }

    private ObjectNode toJson(ObjectMapper json) {
      ObjectNode value =
          json.createObjectNode()
              .put("caseCount", caseCount)
              .put("schemaValidCount", schemaValidCount)
              .put("schemaValidRate", ratio(schemaValidCount, caseCount));
      value
          .putObject("routeConfusion")
          .put("expectedLocalActualLocal", expectedLocalActualLocal)
          .put("expectedLocalActualCloud", expectedLocalActualCloud)
          .put("expectedCloudActualLocal", expectedCloudActualLocal)
          .put("expectedCloudActualCloud", expectedCloudActualCloud)
          .put("accuracy", ratio(expectedLocalActualLocal + expectedCloudActualCloud, caseCount));
      int actualLocalCount = expectedLocalActualLocal + expectedCloudActualLocal;
      value
          .putObject("wrongLocal")
          .put("count", wrongLocalCount)
          .put("rateAmongLocal", ratio(wrongLocalCount, actualLocalCount))
          .put("rateOverall", ratio(wrongLocalCount, caseCount));
      value
          .putObject("type")
          .put("top1CorrectCount", topTypeCorrectCount)
          .put("top1Accuracy", ratio(topTypeCorrectCount, caseCount))
          .put("expectedCandidateCount", expectedTypeCandidateCount)
          .put("matchedCandidateCount", matchedTypeCandidateCount)
          .put(
              "expectedCandidateRecall",
              ratio(matchedTypeCandidateCount, expectedTypeCandidateCount));
      long signalPrecisionDenominator = signalTruePositive + signalFalsePositive;
      long signalRecallDenominator = signalTruePositive + signalFalseNegative;
      double precision = ratio(signalTruePositive, signalPrecisionDenominator);
      double recall = ratio(signalTruePositive, signalRecallDenominator);
      value
          .putObject("signals")
          .put("truePositive", signalTruePositive)
          .put("falsePositive", signalFalsePositive)
          .put("falseNegative", signalFalseNegative)
          .put("precision", precision)
          .put("recall", recall)
          .put(
              "f1",
              precision + recall == 0 ? 0 : ratioDouble(2 * precision * recall, precision + recall))
          .put("exactCaseCount", signalExactCaseCount)
          .put("exactCaseRate", ratio(signalExactCaseCount, caseCount));
      return value;
    }

    private void assertInternallyConsistent() {
      assertThat(caseCount).isPositive();
      assertThat(schemaValidCount).isBetween(0, caseCount);
      assertThat(
              expectedLocalActualLocal
                  + expectedLocalActualCloud
                  + expectedCloudActualLocal
                  + expectedCloudActualCloud)
          .isEqualTo(caseCount);
      assertThat(wrongLocalCount).isBetween(0, expectedLocalActualLocal + expectedCloudActualLocal);
      assertThat(topTypeCorrectCount).isBetween(0, caseCount);
      assertThat(expectedTypeCandidateCount).isPositive();
      assertThat(matchedTypeCandidateCount).isBetween(0, expectedTypeCandidateCount);
      assertThat(signalTruePositive).isNotNegative();
      assertThat(signalFalsePositive).isNotNegative();
      assertThat(signalFalseNegative).isNotNegative();
      assertThat(signalExactCaseCount).isBetween(0, caseCount);
      assertThat(ratio(schemaValidCount, caseCount)).isBetween(0d, 1d);
      assertThat(ratio(wrongLocalCount, caseCount)).isBetween(0d, 1d);
      assertThat(ratio(topTypeCorrectCount, caseCount)).isBetween(0d, 1d);
      assertThat(ratio(signalExactCaseCount, caseCount)).isBetween(0d, 1d);
    }

    private static double ratioDouble(double numerator, double denominator) {
      return Math.round((numerator / denominator) * 1_000_000d) / 1_000_000d;
    }
  }
}
