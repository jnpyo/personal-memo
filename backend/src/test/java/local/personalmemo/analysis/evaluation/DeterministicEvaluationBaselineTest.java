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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DeterministicEvaluationBaselineTest {
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
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
  private final EvaluationV2Evaluator evaluator =
      new EvaluationV2Evaluator(json, analyzer.provenance(), ambiguityGate.version());
  private final Schema caseSchema = loadCaseSchema();

  @Test
  void fixturesAreVersionedValidSeparatedAndSynchronized() throws Exception {
    JsonNode regression = fixtures(REGRESSION_RESOURCE);
    JsonNode challenge = fixtures(CHALLENGE_RESOURCE);

    assertThat(regression).hasSize(12);
    assertThat(challenge).hasSize(12);
    validateCases(regression, "REGRESSION");
    validateCases(challenge, "VISIBLE_CHALLENGE");
    assertUniqueIdsAndContent(regression, challenge);
    assertChallengeAvoidsFixtureSpecificBranches(challenge);
    assertRuleSourcesDoNotCopyChallengePhrases(challenge);

    assertResourceMatchesRepositoryCopy(REGRESSION_RESOURCE, "fixtures/korean-memo-cases.json");
    assertResourceMatchesRepositoryCopy(
        CHALLENGE_RESOURCE, "fixtures/korean-memo-challenge-cases.json");
    assertResourceMatchesRepositoryCopy(
        CASE_SCHEMA_RESOURCE, "contracts/korean-memo-evaluation-case.schema.json");
  }

  @Test
  void writesContentFreeV2BaselineAndEnforcesOnlyReviewedSafetyGates() throws Exception {
    Files.deleteIfExists(REPORT_PATH);
    List<JsonNode> fixtures = new ArrayList<>();
    fixtures(REGRESSION_RESOURCE).forEach(fixtures::add);
    fixtures(CHALLENGE_RESOURCE).forEach(fixtures::add);

    List<CaseEvaluation> results = fixtures.stream().map(this::evaluate).toList();
    AggregateEvaluation regression =
        EvaluationV2Metrics.aggregate(
            results.stream().filter(result -> result.isSplit("REGRESSION")).toList());
    AggregateEvaluation challenge =
        EvaluationV2Metrics.aggregate(
            results.stream().filter(result -> result.isSplit("VISIBLE_CHALLENGE")).toList());
    AggregateEvaluation all = EvaluationV2Metrics.aggregate(results);
    assertAggregateArithmetic(regression, challenge, all);

    Map<String, AggregateEvaluation> splits = new LinkedHashMap<>();
    splits.put("regression", regression);
    splits.put("visibleChallenge", challenge);
    splits.put("all", all);
    EvaluationReportMetadata metadata =
        new EvaluationReportMetadata(
            "2",
            "2",
            analyzer.version(),
            analyzer.deterministicRulesVersion(),
            ambiguityGate.version(),
            "NOT_SUPPORTED_BY_PROPOSAL_V1");
    ObjectNode report = EvaluationV2Report.withPublicCases(json, metadata, splits, results);
    report.set("gates", EvaluationV2Report.gates(json, regression, challenge));
    String serialized = json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";

    assertThat(regression.schemaValidCount()).isEqualTo(regression.caseCount());
    assertThat(regression.domainValidCount()).isEqualTo(regression.caseCount());
    assertThat(regression.legacyWrongLocalCount()).isZero();
    assertThat(regression.inventedPreciseDateCaseCount()).isZero();
    assertThat(regression.localOverflowCount()).isZero();
    assertThat(regression.missingOverflowSignalCount()).isZero();
    assertThat(regression.unresolvedFieldHallucinationCount()).isZero();
    assertThat(regression.regressionHardGatePassed()).isTrue();
    assertThat(report.at("/gates/regression/passed").asBoolean()).isTrue();
    assertThat(report.at("/gates/regression/semanticFalseConfidentLocalEnforced").asBoolean())
        .isFalse();
    assertThat(report.at("/gates/visibleChallenge/enforced").asBoolean()).isFalse();
    assertThat(report.at("/capabilities/dateItemDueBinding").asText())
        .isEqualTo("NOT_SUPPORTED_BY_PROPOSAL_V1");
    assertThat(report.at("/splits/regression/type/actualCandidateCount").canConvertToInt())
        .isTrue();
    assertThat(report.at("/splits/regression/type/matchedCandidatePrecision").isNumber()).isTrue();
    assertThat(challenge.caseCount()).isEqualTo(12);

    assertContentFreeReport(report, serialized, fixtures);
    Files.createDirectories(REPORT_PATH.getParent());
    Files.writeString(REPORT_PATH, serialized, StandardCharsets.UTF_8);
  }

  private CaseEvaluation evaluate(JsonNode fixture) {
    UUID memoId = UUID.randomUUID();
    int revision = 1;
    String content = fixture.path("content").asText();
    ObjectNode proposal =
        analyzer.analyze(
            memoId,
            revision,
            content,
            Instant.parse(fixture.path("baseInstant").asText()),
            fixture.path("timeZone").asText());
    return evaluator.evaluate(fixture, proposal, memoId, revision, content);
  }

  private void assertContentFreeReport(
      ObjectNode report, String serialized, List<JsonNode> fixtures) {
    for (String forbiddenField :
        List.of(
            "content",
            "rawMemo",
            "memoBody",
            "notes",
            "surfaceText",
            "contentHash",
            "ownerId",
            "userId",
            "memoId")) {
      assertThat(report.findValue(forbiddenField)).isNull();
    }
    for (JsonNode fixture : fixtures) {
      assertThat(serialized).doesNotContain(fixture.path("content").asText());
      assertThat(serialized).doesNotContain(fixture.path("notes").asText());
      for (JsonNode date : fixture.at("/expectedDates/mentions")) {
        assertThat(serialized).doesNotContain(date.path("surfaceText").asText());
      }
      for (JsonNode set : fixture.at("/expectedItems/acceptableSets")) {
        assertTextExpectationAbsent(serialized, set.path("suggestedTitle"));
        for (JsonNode item : set.path("allItems")) {
          assertTextExpectationAbsent(serialized, item.path("title"));
          assertTextExpectationAbsent(serialized, item.path("action"));
          assertTextExpectationAbsent(serialized, item.path("object"));
        }
      }
    }
  }

  private void assertTextExpectationAbsent(String serialized, JsonNode expectation) {
    if (expectation.path("value").isTextual()) {
      assertThat(serialized).doesNotContain(expectation.path("value").asText());
    }
  }

  private void assertAggregateArithmetic(
      AggregateEvaluation regression, AggregateEvaluation challenge, AggregateEvaluation all) {
    assertThat(all.caseCount()).isEqualTo(regression.caseCount() + challenge.caseCount());
    assertThat(all.schemaValidCount())
        .isEqualTo(regression.schemaValidCount() + challenge.schemaValidCount());
    assertThat(all.domainValidCount())
        .isEqualTo(regression.domainValidCount() + challenge.domainValidCount());
    assertThat(all.legacyWrongLocalCount())
        .isEqualTo(regression.legacyWrongLocalCount() + challenge.legacyWrongLocalCount());
    assertThat(all.inventedPreciseDateCaseCount())
        .isEqualTo(
            regression.inventedPreciseDateCaseCount() + challenge.inventedPreciseDateCaseCount());
    assertThat(all.localOverflowCount())
        .isEqualTo(regression.localOverflowCount() + challenge.localOverflowCount());
    assertThat(all.missingOverflowSignalCount())
        .isEqualTo(
            regression.missingOverflowSignalCount() + challenge.missingOverflowSignalCount());
    assertThat(all.unresolvedFieldHallucinationCount())
        .isEqualTo(
            regression.unresolvedFieldHallucinationCount()
                + challenge.unresolvedFieldHallucinationCount());
    assertThat(all.dateSemantics().truePositive())
        .isEqualTo(
            regression.dateSemantics().truePositive() + challenge.dateSemantics().truePositive());
    assertThat(all.items().falseNegative())
        .isEqualTo(regression.items().falseNegative() + challenge.items().falseNegative());
  }

  private void validateCases(JsonNode fixtures, String expectedSplit) {
    for (JsonNode fixture : fixtures) {
      assertThat(caseSchema.validate(fixture))
          .as("fixture contract for %s", fixture.path("id").asText())
          .isEmpty();
      assertThat(fixture.path("datasetVersion").asText()).isEqualTo("2");
      assertThat(fixture.path("split").asText()).isEqualTo(expectedSplit);
      EvaluationCaseGold parsed = EvaluationV2GoldIntegrity.validate(fixture);
      assertThat(parsed.content()).isEqualTo(fixture.path("content").asText());
    }
  }

  private void assertUniqueIdsAndContent(JsonNode regression, JsonNode challenge) {
    Set<String> ids = new HashSet<>();
    Set<String> contents = new HashSet<>();
    for (JsonNode fixtures : List.of(regression, challenge)) {
      for (JsonNode fixture : fixtures) {
        assertThat(ids.add(fixture.path("id").asText())).isTrue();
        assertThat(contents.add(fixture.path("content").asText())).isTrue();
      }
    }
  }

  private void assertChallengeAvoidsFixtureSpecificBranches(JsonNode challenge) {
    for (JsonNode fixture : challenge) {
      String content = fixture.path("content").asText();
      assertThat(FIXTURE_SPECIFIC_BRANCH_MARKERS)
          .as(
              "visible challenge %s must not reuse FakeAnalyzer fixture branches",
              fixture.path("id").asText())
          .noneMatch(content::contains);
      assertThat(content.contains("어제") && content.contains("봤음")).isFalse();
      assertThat(content).doesNotMatch(".*(?:\\d{1,2}\\.\\d{1,2}).*(?:운영체제|OS)\\s*과제\\s*$");
    }
  }

  private void assertRuleSourcesDoNotCopyChallengePhrases(JsonNode challenge) throws Exception {
    Path analysisSources = backendPath("src/main/java/local/personalmemo/analysis");
    StringBuilder productionSources = new StringBuilder();
    try (var paths = Files.walk(analysisSources)) {
      for (Path source :
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".java"))
              .sorted()
              .toList()) {
        productionSources.append(Files.readString(source));
      }
    }
    String ruleSources = productionSources.toString();
    for (JsonNode fixture : challenge) {
      String content = fixture.path("content").asText().replaceAll("\\s+", " ").strip();
      assertThat(ruleSources)
          .as(
              "analyzer must not copy the full visible challenge case %s",
              fixture.path("id").asText())
          .doesNotContain(content);
      String[] words = content.split(" ");
      for (int index = 0; index + 2 < words.length; index++) {
        String phrase = String.join(" ", words[index], words[index + 1], words[index + 2]);
        assertThat(ruleSources)
            .as(
                "analyzer must use general rules, not a three-token branch for %s",
                fixture.path("id").asText())
            .doesNotContain(phrase);
      }
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

  private Path backendPath(String relativePath) {
    Path fromBackend = Path.of(relativePath);
    if (Files.exists(fromBackend)) {
      return fromBackend;
    }
    Path fromRoot = Path.of("backend", relativePath);
    if (Files.exists(fromRoot)) {
      return fromRoot;
    }
    throw new IllegalStateException("Backend file is missing: " + relativePath);
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
}
