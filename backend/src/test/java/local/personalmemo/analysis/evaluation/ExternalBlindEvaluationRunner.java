package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit, local-only runner for an independently held blind release. The class name deliberately
 * does not match Surefire's default test patterns.
 */
class ExternalBlindEvaluationRunner {
  private static final String DATASET_ENVIRONMENT = "PERSONAL_MEMO_BLIND_DATASET";
  private static final String COMMIT_ENVIRONMENT = "PERSONAL_MEMO_CANDIDATE_COMMIT";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final List<String> PUBLIC_FIXTURE_RESOURCES =
      List.of("/fixtures/korean-memo-cases.json", "/fixtures/korean-memo-challenge-cases.json");
  private static final int MINIMUM_CASE_COUNT = 50;
  private static final int MAXIMUM_CASE_COUNT = 10_000;
  private static final long MAXIMUM_DATASET_BYTES = 32L * 1024 * 1024;
  private static final int MAXIMUM_PROCESS_OUTPUT_BYTES = 2 * 1024 * 1024;
  private static final Pattern OPAQUE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern HASH_LIKE = Pattern.compile("(?i)[0-9a-f]{32,128}");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("datasetVersion", "releaseId", "labelPolicyVersion", "sourcePolicy", "cases");
  private static final Set<String> REPORT_FIELDS =
      Set.of(
          "reportVersion",
          "evaluationKind",
          "datasetVersion",
          "releaseId",
          "labelPolicyVersion",
          "sourcePolicy",
          "candidateCommit",
          "analyzerVersion",
          "deterministicRulesVersion",
          "promptVersion",
          "localModelVersion",
          "embeddingModelVersion",
          "routingPolicyVersion",
          "containsRawMemoContent",
          "capabilities",
          "metricGate",
          "metrics");
  private static final Set<String> METRIC_FIELDS =
      Set.of(
          "caseCount",
          "schemaValidCount",
          "domainValidCount",
          "schemaValidRate",
          "domainValidRate",
          "routeConfusion",
          "expectedLocalActualLocal",
          "expectedLocalActualCloud",
          "expectedCloudActualLocal",
          "expectedCloudActualCloud",
          "invalidActualRoute",
          "accuracy",
          "wrongLocal",
          "count",
          "rateAmongLocal",
          "rateOverall",
          "type",
          "top1CorrectCount",
          "top1Accuracy",
          "candidateSetExactCount",
          "candidateSetExactRate",
          "expectedCandidateCount",
          "actualCandidateCount",
          "matchedCandidateCount",
          "matchedCandidatePrecision",
          "expectedCandidateRecall",
          "signals",
          "truePositive",
          "falsePositive",
          "falseNegative",
          "precision",
          "recall",
          "f1",
          "exactCaseCount",
          "exactCaseRate",
          "dates",
          "mentions",
          "semantic",
          "exactSetCount",
          "exactSetRate",
          "inventedPreciseDateCaseCount",
          "top1EligibleCount",
          "items",
          "exactCandidates",
          "kind",
          "title",
          "action",
          "object",
          "sourceSpan",
          "suggestedTitle",
          "cardinalityExactCount",
          "cardinalityExactRate",
          "completeSetExactCount",
          "completeSetExactRate",
          "overflowOmittedGoldCount",
          "goldResolutionCaseCounts",
          "resolved",
          "userInputNeeded",
          "overflow",
          "safety",
          "semanticFalseConfidentLocalCount",
          "semanticFalseConfidentLocalEnforced",
          "localOverflowCount");
  private static final Set<String> SENSITIVE_VALUE_FIELDS =
      Set.of("id", "goldId", "setId", "content", "notes", "surfaceText", "value");
  private static final Set<String> IDENTIFIER_VALUE_FIELDS = Set.of("id", "goldId", "setId");

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();

  @Test
  void writesAggregateOnlySummaryForPinnedExternalBlindRelease() throws Throwable {
    Path report = reportPath();
    Path temporaryReport = temporaryReportPath();
    deleteOutputs(report, temporaryReport);
    try {
      runEvaluation(report, temporaryReport);
    } catch (Throwable failure) {
      try {
        deleteOutputs(report, temporaryReport);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private void runEvaluation(Path report, Path temporaryReport) {
    String configuredDataset = requiredEnvironment(DATASET_ENVIRONMENT);
    String candidateCommit = requiredEnvironment(COMMIT_ENVIRONMENT).toLowerCase(Locale.ROOT);
    if (!COMMIT.matcher(candidateCommit).matches()) {
      fail("The candidate commit is invalid.");
    }

    Path repository = repositoryRoot();
    verifyPinnedCleanCommit(repository, candidateCommit);
    Path datasetPath = validateExternalDatasetPath(repository, path(configuredDataset));
    Schema caseSchema = loadSynchronizedCaseSchema(repository);
    BlindEnvelope envelope = readAndValidateEnvelope(datasetPath, caseSchema);
    assertNoPublicFixtureOverlap(envelope);

    EvaluationV2Evaluator evaluator = new EvaluationV2Evaluator(json);
    List<CaseEvaluation> results = new ArrayList<>(envelope.cases().size());
    for (int index = 0; index < envelope.cases().size(); index++) {
      JsonNode fixture = envelope.cases().get(index);
      String content = fixture.path("content").asText();
      UUID memoId = new UUID(0x424c494e44000000L, index + 1L);
      try {
        ObjectNode proposal =
            analyzer.analyze(
                memoId,
                1,
                content,
                Instant.parse(fixture.path("baseInstant").asText()),
                fixture.path("timeZone").asText());
        results.add(evaluator.evaluate(fixture, proposal, memoId, 1, content.length()));
      } catch (RuntimeException exception) {
        fail("A blind case could not be evaluated safely.");
      }
    }

    AggregateEvaluation aggregate = EvaluationV2Metrics.aggregate(results);
    if (aggregate.caseCount() != envelope.cases().size()) {
      fail("The blind aggregate is incomplete.");
    }
    verifyPinnedCleanCommit(repository, candidateCommit);
    EvaluationReportMetadata metadata =
        new EvaluationReportMetadata(
            "1",
            "2",
            analyzer.version(),
            analyzer.deterministicRulesVersion(),
            ambiguityGate.version(),
            "NOT_SUPPORTED_BY_PROPOSAL_V1");
    ObjectNode aggregateReport =
        EvaluationV2Report.aggregateOnly(json, metadata, Map.of("blind", aggregate));
    ObjectNode summary =
        createSummary(candidateCommit, envelope, aggregateReport.path("splits").path("blind"));
    Set<String> sensitiveValues = new HashSet<>(envelope.sensitiveValues());
    sensitiveValues.add(datasetPath.toString());
    assertAggregateOnlyReport(summary, envelope.cases().size(), sensitiveValues);
    writeAtomically(report, temporaryReport, summary);
  }

  private ObjectNode createSummary(
      String candidateCommit, BlindEnvelope envelope, JsonNode aggregateMetrics) {
    ObjectNode summary =
        json.createObjectNode()
            .put("reportVersion", "1")
            .put("evaluationKind", "EXTERNAL_BLIND")
            .put("datasetVersion", "2")
            .put("releaseId", envelope.releaseId())
            .put("labelPolicyVersion", envelope.labelPolicyVersion())
            .put("sourcePolicy", "INDEPENDENT_HUMAN_CURATED")
            .put("candidateCommit", candidateCommit)
            .put("analyzerVersion", analyzer.version())
            .put("deterministicRulesVersion", analyzer.deterministicRulesVersion())
            .put("promptVersion", analyzer.provenance().promptVersion())
            .put("localModelVersion", analyzer.provenance().localModelVersion())
            .put("embeddingModelVersion", analyzer.provenance().embeddingModelVersion())
            .put("routingPolicyVersion", ambiguityGate.version())
            .put("containsRawMemoContent", false);
    summary
        .putObject("capabilities")
        .put("dateItemGold", "SCORED")
        .put("dateItemDueBinding", "NOT_SUPPORTED_BY_PROPOSAL_V1");
    summary
        .putObject("metricGate")
        .put("status", "NOT_CONFIGURED")
        .put("enforced", false)
        .put("reason", "PRE_REGISTERED_POLICY_REQUIRED");
    summary.set("metrics", aggregateMetrics);
    return summary;
  }

  private BlindEnvelope readAndValidateEnvelope(Path datasetPath, Schema caseSchema) {
    JsonNode root;
    try (InputStream input = Files.newInputStream(datasetPath)) {
      root = json.readTree(input);
    } catch (Exception exception) {
      throw failure("The external blind dataset could not be parsed.");
    }
    assertExactFields(root, ENVELOPE_FIELDS, "The blind envelope is invalid.");
    if (!"2".equals(root.path("datasetVersion").asText())
        || !"INDEPENDENT_HUMAN_CURATED".equals(root.path("sourcePolicy").asText())) {
      fail("The blind envelope provenance is invalid.");
    }
    String releaseId = opaqueVersion(root.path("releaseId"));
    String labelPolicyVersion = opaqueVersion(root.path("labelPolicyVersion"));
    JsonNode cases = root.path("cases");
    if (!cases.isArray()
        || cases.size() < MINIMUM_CASE_COUNT
        || cases.size() > MAXIMUM_CASE_COUNT) {
      fail("The blind release case count is outside the approved boundary.");
    }

    Set<String> ids = new HashSet<>();
    Set<String> normalizedContents = new HashSet<>();
    Set<String> sensitiveValues = new HashSet<>();
    Set<String> identifiers = new HashSet<>();
    List<JsonNode> validatedCases = new ArrayList<>(cases.size());
    for (JsonNode fixture : cases) {
      try {
        if (!caseSchema.validate(fixture).isEmpty()) {
          fail("A blind case does not satisfy the version-2 schema.");
        }
      } catch (RuntimeException exception) {
        throw failure("A blind case could not be schema-validated.");
      }
      if (!"2".equals(fixture.path("datasetVersion").asText())
          || !"BLIND".equals(fixture.path("split").asText())
          || !"INDEPENDENT_HUMAN_CURATED".equals(fixture.path("sourcePolicy").asText())) {
        fail("A blind case has invalid provenance.");
      }
      String id = fixture.path("id").asText();
      String content = fixture.path("content").asText();
      if (!ids.add(id) || !normalizedContents.add(EvaluationTextNormalizer.normalize(content))) {
        fail("The blind release contains a duplicate case.");
      }
      validateGoldIntegrity(fixture);
      collectSensitiveValues(fixture, null, false, sensitiveValues);
      collectIdentifierValues(fixture, null, false, identifiers);
      validatedCases.add(fixture);
    }
    if (identifiers.stream().anyMatch(value -> value.length() < 4)) {
      fail("Blind case-level identifiers must contain at least four characters.");
    }
    if (containsSensitiveSubstring(releaseId, sensitiveValues)
        || containsSensitiveSubstring(labelPolicyVersion, sensitiveValues)) {
      fail("Dataset-level provenance collides with case-level information.");
    }
    return new BlindEnvelope(
        releaseId,
        labelPolicyVersion,
        List.copyOf(validatedCases),
        Set.copyOf(sensitiveValues),
        Set.copyOf(ids),
        Set.copyOf(normalizedContents));
  }

  private void validateGoldIntegrity(JsonNode fixture) {
    try {
      Instant.parse(fixture.path("baseInstant").asText());
      ZoneId.of(fixture.path("timeZone").asText());
      EvaluationV2GoldIntegrity.validate(fixture);
    } catch (RuntimeException exception) {
      fail("A blind case has invalid evaluation semantics.");
    }
  }

  private void assertNoPublicFixtureOverlap(BlindEnvelope envelope) {
    Set<String> publicIds = new HashSet<>();
    Set<String> publicContents = new HashSet<>();
    for (String resource : PUBLIC_FIXTURE_RESOURCES) {
      JsonNode fixtures = readResource(resource);
      if (!fixtures.isArray()) {
        fail("A public evaluation fixture is invalid.");
      }
      for (JsonNode fixture : fixtures) {
        publicIds.add(fixture.path("id").asText());
        publicContents.add(EvaluationTextNormalizer.normalize(fixture.path("content").asText()));
      }
    }
    if (envelope.caseIds().stream().anyMatch(publicIds::contains)
        || envelope.normalizedContents().stream().anyMatch(publicContents::contains)) {
      fail("The external release overlaps public evaluation data.");
    }
  }

  static Path validateExternalDatasetPath(Path repository, Path configured) {
    try {
      if (!configured.isAbsolute()) {
        fail("The blind dataset path must be absolute.");
      }
      Path normalized = configured.normalize();
      Path cursor = normalized.getRoot();
      for (Path part : normalized) {
        cursor = cursor.resolve(part);
        if (Files.isSymbolicLink(cursor)) {
          fail("Symbolic links are not allowed in the blind dataset path.");
        }
      }
      Path realRepository = repository.toRealPath();
      Path realDataset = normalized.toRealPath();
      if (!Files.isRegularFile(realDataset, LinkOption.NOFOLLOW_LINKS)
          || realDataset.startsWith(realRepository)
          || Files.size(realDataset) <= 0
          || Files.size(realDataset) > MAXIMUM_DATASET_BYTES) {
        fail("The blind dataset path is outside the allowed boundary.");
      }
      return realDataset;
    } catch (IOException | SecurityException exception) {
      throw failure("The blind dataset path could not be validated.");
    }
  }

  static void assertAggregateOnlyReport(
      JsonNode report, int expectedCaseCount, Set<String> sensitiveValues) {
    assertExactFields(report, REPORT_FIELDS, "The blind summary shape is invalid.");
    assertExactFields(
        report.path("capabilities"),
        Set.of("dateItemGold", "dateItemDueBinding"),
        "The blind summary capabilities are invalid.");
    assertExactFields(
        report.path("metricGate"),
        Set.of("status", "enforced", "reason"),
        "The blind summary metric-gate shape is invalid.");
    if (!"NOT_CONFIGURED".equals(report.path("metricGate").path("status").asText())
        || !"PRE_REGISTERED_POLICY_REQUIRED"
            .equals(report.path("metricGate").path("reason").asText())
        || report.path("metricGate").path("enforced").asBoolean(true)
        || report.path("metrics").path("caseCount").asInt(-1) != expectedCaseCount
        || report.path("containsRawMemoContent").asBoolean(true)
        || !"1".equals(report.path("reportVersion").asText())
        || !"EXTERNAL_BLIND".equals(report.path("evaluationKind").asText())
        || !"2".equals(report.path("datasetVersion").asText())
        || !"INDEPENDENT_HUMAN_CURATED".equals(report.path("sourcePolicy").asText())
        || !COMMIT.matcher(report.path("candidateCommit").asText()).matches()
        || !"SCORED".equals(report.path("capabilities").path("dateItemGold").asText())
        || !"NOT_SUPPORTED_BY_PROPOSAL_V1"
            .equals(report.path("capabilities").path("dateItemDueBinding").asText())) {
      fail("The blind summary boundary is invalid.");
    }
    assertAggregateMetricShape(report.path("metrics"));
    assertMetricAllowlist(report.path("metrics"));
    Set<String> reportTexts = new HashSet<>();
    collectTextValues(report, reportTexts);
    if (reportTexts.stream().anyMatch(text -> containsSensitiveSubstring(text, sensitiveValues))) {
      fail("The blind summary contains case-level information.");
    }
  }

  private static void assertAggregateMetricShape(JsonNode metrics) {
    assertExactFields(
        metrics,
        Set.of(
            "caseCount",
            "schemaValidCount",
            "domainValidCount",
            "schemaValidRate",
            "domainValidRate",
            "routeConfusion",
            "wrongLocal",
            "type",
            "signals",
            "dates",
            "items",
            "safety"),
        "The blind aggregate metric shape is invalid.");
    assertExactFields(
        metrics.path("routeConfusion"),
        Set.of(
            "expectedLocalActualLocal",
            "expectedLocalActualCloud",
            "expectedCloudActualLocal",
            "expectedCloudActualCloud",
            "invalidActualRoute",
            "accuracy"),
        "The blind route metric shape is invalid.");
    assertExactFields(
        metrics.path("wrongLocal"),
        Set.of("count", "rateAmongLocal", "rateOverall"),
        "The blind safety metric shape is invalid.");
    assertExactFields(
        metrics.path("type"),
        Set.of(
            "top1CorrectCount",
            "top1Accuracy",
            "candidateSetExactCount",
            "expectedCandidateCount",
            "actualCandidateCount",
            "matchedCandidateCount",
            "candidateSetExactRate",
            "matchedCandidatePrecision",
            "expectedCandidateRecall"),
        "The blind type metric shape is invalid.");
    assertFieldMetricShape(metrics.path("signals"), true);
    assertExactFields(
        metrics.path("dates"),
        Set.of(
            "mentions",
            "semantic",
            "exactSetCount",
            "exactSetRate",
            "inventedPreciseDateCaseCount",
            "top1EligibleCount",
            "top1CorrectCount",
            "top1Accuracy"),
        "The blind date metric shape is invalid.");
    assertFieldMetricShape(metrics.path("dates").path("mentions"), false);
    assertFieldMetricShape(metrics.path("dates").path("semantic"), false);
    assertExactFields(
        metrics.path("items"),
        Set.of(
            "exactCandidates",
            "kind",
            "title",
            "action",
            "object",
            "sourceSpan",
            "suggestedTitle",
            "cardinalityExactCount",
            "cardinalityExactRate",
            "completeSetExactCount",
            "completeSetExactRate",
            "top1EligibleCount",
            "top1CorrectCount",
            "top1Accuracy",
            "overflowOmittedGoldCount",
            "goldResolutionCaseCounts"),
        "The blind item metric shape is invalid.");
    for (String field :
        List.of(
            "exactCandidates",
            "kind",
            "title",
            "action",
            "object",
            "sourceSpan",
            "suggestedTitle")) {
      assertFieldMetricShape(metrics.path("items").path(field), false);
    }
    assertExactFields(
        metrics.path("items").path("goldResolutionCaseCounts"),
        Set.of("resolved", "userInputNeeded", "overflow"),
        "The blind item-resolution metric shape is invalid.");
    assertExactFields(
        metrics.path("safety"),
        Set.of(
            "semanticFalseConfidentLocalCount",
            "semanticFalseConfidentLocalEnforced",
            "localOverflowCount"),
        "The blind aggregate safety shape is invalid.");
  }

  private static void assertFieldMetricShape(JsonNode metric, boolean caseExactFields) {
    Set<String> fields =
        new HashSet<>(
            Set.of("truePositive", "falsePositive", "falseNegative", "precision", "recall", "f1"));
    if (caseExactFields) {
      fields.add("exactCaseCount");
      fields.add("exactCaseRate");
    }
    assertExactFields(metric, fields, "The blind field metric shape is invalid.");
  }

  private static void assertMetricAllowlist(JsonNode value) {
    if (!value.isObject()) {
      fail("The blind aggregate metrics are invalid.");
    }
    for (var property : value.properties()) {
      if (!METRIC_FIELDS.contains(property.getKey())) {
        fail("The blind aggregate metrics contain an unapproved field.");
      }
      JsonNode child = property.getValue();
      if (child.isObject()) {
        assertMetricAllowlist(child);
      } else if (child.isArray() || child.isTextual() || child.isBinary()) {
        fail("The blind aggregate metrics contain case-level data.");
      }
    }
  }

  private Schema loadSynchronizedCaseSchema(Path repository) {
    JsonNode bundled = readResource(CASE_SCHEMA_RESOURCE);
    Path contract =
        repository.resolve("contracts").resolve("korean-memo-evaluation-case.schema.json");
    try (InputStream input = Files.newInputStream(contract)) {
      JsonNode repositorySchema = json.readTree(input);
      if (!bundled.equals(repositorySchema)) {
        fail("The evaluation schema resource is not synchronized.");
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("The evaluation schema could not be verified.");
    }

    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    try (InputStream input = Files.newInputStream(contract)) {
      Schema schema = registry.getSchema(input);
      schema.initializeValidators();
      return schema;
    } catch (IOException | RuntimeException exception) {
      throw failure("The evaluation schema could not be loaded.");
    }
  }

  private void verifyPinnedCleanCommit(Path repository, String candidateCommit) {
    String head =
        new String(
                runGit(repository, "rev-parse", "--verify", "HEAD^{commit}"),
                StandardCharsets.UTF_8)
            .strip();
    if (!COMMIT.matcher(head).matches() || !head.equals(candidateCommit)) {
      fail("The candidate commit does not match HEAD.");
    }
    if (runGit(
                repository,
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--ignore-submodules=none")
            .length
        != 0) {
      fail("The candidate worktree must be clean.");
    }
  }

  private byte[] runGit(Path repository, String... arguments) {
    Process process = null;
    ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    Future<byte[]> outputFuture = null;
    try {
      String[] command = new String[arguments.length + 3];
      command[0] = "git";
      command[1] = "-C";
      command[2] = repository.toString();
      System.arraycopy(arguments, 0, command, 3, arguments.length);
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      Process startedProcess = process;
      outputFuture = readerExecutor.submit(() -> readBounded(startedProcess.getInputStream()));
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        fail("Git state could not be verified.");
      }
      byte[] output = outputFuture.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        fail("Git state could not be verified.");
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure("Git state verification was interrupted.");
    } catch (ExecutionException | TimeoutException | IOException | RuntimeException exception) {
      throw failure("Git state could not be verified.");
    } finally {
      if (outputFuture != null) {
        outputFuture.cancel(true);
      }
      readerExecutor.shutdownNow();
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > MAXIMUM_PROCESS_OUTPUT_BYTES) {
        throw new IOException("Process output limit exceeded.");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private Path repositoryRoot() {
    Path current = Path.of(System.getProperty("basedir", "")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve(".git"))
          && Files.isRegularFile(current.resolve("backend").resolve("pom.xml"))) {
        try {
          return current.toRealPath();
        } catch (IOException exception) {
          fail("The repository path could not be resolved.");
        }
      }
      current = current.getParent();
    }
    throw failure("The repository root could not be found.");
  }

  private JsonNode readResource(String resource) {
    try (InputStream input = ExternalBlindEvaluationRunner.class.getResourceAsStream(resource)) {
      if (input == null) {
        fail("An evaluation resource is missing.");
      }
      return json.readTree(input);
    } catch (IOException | RuntimeException exception) {
      throw failure("An evaluation resource could not be read.");
    }
  }

  private static void writeAtomically(Path report, Path temporaryReport, JsonNode value) {
    try {
      Files.createDirectories(report.getParent());
      String serialized =
          new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
      Files.writeString(
          temporaryReport,
          serialized,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      try {
        Files.move(temporaryReport, report, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporaryReport, report);
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("The blind summary could not be written.");
    }
  }

  private static void deleteOutputs(Path... paths) {
    try {
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | RuntimeException exception) {
      throw failure("A stale blind summary could not be removed.");
    }
  }

  private static Path reportPath() {
    return Path.of(System.getProperty("basedir", ""))
        .toAbsolutePath()
        .normalize()
        .resolve("target")
        .resolve("evaluation")
        .resolve("blind-summary.json");
  }

  private static Path temporaryReportPath() {
    return reportPath().resolveSibling("blind-summary.json.tmp");
  }

  private static Path path(String value) {
    try {
      return Path.of(value);
    } catch (RuntimeException exception) {
      throw failure("The blind dataset path is invalid.");
    }
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      fail("Required blind-runner environment is missing.");
    }
    return value.strip();
  }

  static String opaqueVersion(JsonNode value) {
    String text = value.isTextual() ? value.asText() : "";
    if (!OPAQUE_VERSION.matcher(text).matches() || HASH_LIKE.matcher(text).find()) {
      fail("Blind release provenance must be an opaque version label.");
    }
    return text;
  }

  private static void assertExactFields(JsonNode value, Set<String> expected, String message) {
    if (!value.isObject()) {
      fail(message);
    }
    Set<String> actual = new HashSet<>();
    for (var property : value.properties()) {
      actual.add(property.getKey());
    }
    if (!actual.equals(expected)) {
      fail(message);
    }
  }

  private static void collectSensitiveValues(
      JsonNode value, String field, boolean sensitiveContext, Set<String> values) {
    boolean sensitive =
        sensitiveContext
            || (field != null
                && (SENSITIVE_VALUE_FIELDS.contains(field) || field.contains("GoldId")));
    if (value.isTextual()) {
      if (sensitive && !value.asText().isBlank()) {
        values.add(value.asText());
      }
      return;
    }
    if (value.isObject()) {
      for (var property : value.properties()) {
        collectSensitiveValues(property.getValue(), property.getKey(), sensitive, values);
      }
    } else if (value.isArray()) {
      for (JsonNode child : value) {
        collectSensitiveValues(child, field, sensitive, values);
      }
    }
  }

  private static void collectIdentifierValues(
      JsonNode value, String field, boolean identifierContext, Set<String> values) {
    boolean identifier =
        identifierContext
            || (field != null
                && (IDENTIFIER_VALUE_FIELDS.contains(field) || field.contains("GoldId")));
    if (value.isTextual()) {
      if (identifier && !value.asText().isBlank()) {
        values.add(value.asText());
      }
      return;
    }
    if (value.isObject()) {
      for (var property : value.properties()) {
        collectIdentifierValues(property.getValue(), property.getKey(), identifier, values);
      }
    } else if (value.isArray()) {
      for (JsonNode child : value) {
        collectIdentifierValues(child, field, identifier, values);
      }
    }
  }

  private static void collectTextValues(JsonNode value, Set<String> values) {
    if (value.isTextual()) {
      values.add(value.asText());
    } else if (value.isObject() || value.isArray()) {
      for (JsonNode child : value) {
        collectTextValues(child, values);
      }
    }
  }

  private static boolean containsSensitiveSubstring(
      String reportValue, Set<String> sensitiveValues) {
    String normalizedReportValue = reportValue.toLowerCase(Locale.ROOT);
    return sensitiveValues.stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(
            value ->
                normalizedReportValue.equals(value)
                    || (value.length() >= 4 && normalizedReportValue.contains(value)));
  }

  private static IllegalStateException failure(String message) {
    return new IllegalStateException(message);
  }

  private static void fail(String message) {
    throw failure(message);
  }

  private record BlindEnvelope(
      String releaseId,
      String labelPolicyVersion,
      List<JsonNode> cases,
      Set<String> sensitiveValues,
      Set<String> caseIds,
      Set<String> normalizedContents) {}
}
