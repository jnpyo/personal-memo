package local.personalmemo.analysis.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.Draft202012AnalysisProposalSchemaValidator;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit aggregate-only v7-A diagnostic for the v6 length-termination hypothesis. */
class SoloLiquidAiTruncationDiagnosticCore {
  static final String OPT_IN_ENV = "PERSONAL_MEMO_SOLO_LIQUIDAI_TRUNCATION_DIAGNOSTIC";
  static final String OPT_IN_VALUE = "SOLO_PROVISIONAL_REPORT_ONLY";

  private static final String REPORT_VERSION = "solo-liquidai-truncation-diagnostic-v7a";
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("target", "evaluation", "solo-liquidai-truncation-diagnostic-v7a.json");
  private static final String REGRESSION_RESOURCE = "/fixtures/korean-memo-cases.json";
  private static final String CHALLENGE_RESOURCE = "/fixtures/korean-memo-challenge-cases.json";
  private static final String CASE_SCHEMA_RESOURCE =
      "/contracts/korean-memo-evaluation-case.schema.json";
  private static final String CANONICAL_SCHEMA_RESOURCE =
      "/contracts/analysis-proposal.schema.json";
  private static final String EVIDENCE_SCHEMA_RESOURCE =
      "/contracts/solo-liquidai-shadow-skill-evidence.schema.json";
  private static final String SELECTION_SCHEMA_RESOURCE =
      "/contracts/solo-liquidai-shadow-skill-selection.schema.json";
  private static final int EXPECTED_SPLIT_SIZE = 12;
  private static final int EXPECTED_CASE_COUNT = 24;
  private static final int MEMO_REVISION = 1;
  private static final int MAX_RESOURCE_BYTES = 512 * 1024;
  private static final int MAX_REPORT_BYTES = 512 * 1024;
  private static final String MEMO_ID_PREFIX = "personal-memo:liquidai-truncation-v7a:";
  private static final String V6_REPORT_FILE = "solo-liquidai-deterministic-skill-v6.json";
  private static final long V6_REPORT_BYTES = 45_708L;
  private static final String V6_REPORT_SHA256 =
      "a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f";
  private static final String V6_ATTESTATION_FILE =
      "solo-liquidai-deterministic-skill-v6-attestation.json";
  private static final long V6_ATTESTATION_BYTES = 6_913L;
  private static final String V6_ATTESTATION_SHA256 =
      "e19e72232e9a5780fb22c8d9c7a80ed228da37a5593b000221eb2a7f1f300fb5";
  private static final Set<String> FORBIDDEN_REPORT_FIELDS =
      Set.of(
          "cases",
          "caseId",
          "memoId",
          "memoText",
          "content",
          "notes",
          "expectedTypes",
          "expectedDates",
          "expectedItems",
          "selectionOutput",
          "rawModelOutput",
          "prompt",
          "skillEvidence",
          "defaultTitle",
          "objectValue",
          "primaryItemOrdinal",
          "topicObjectOrdinals",
          "value",
          "surfaceText");
  private static final Pattern GPU_TEXT = Pattern.compile("[A-Za-z0-9 ._()/-]{1,120}");
  private static final Pattern DRIVER_TEXT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final ObjectMapper json;
  private final DiagnosticConfiguration configuration;
  private final OllamaTruncationDiagnosticApi ollama;
  private final Path reportPath;
  private final FakeAnalyzer fakeAnalyzer;
  private final ShadowDeterministicSkill skill;
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final Draft202012AnalysisProposalSchemaValidator canonicalSchemaValidator =
      new Draft202012AnalysisProposalSchemaValidator();
  private final AnalysisProposalValidator canonicalDomainValidator =
      new AnalysisProposalValidator();

  SoloLiquidAiTruncationDiagnosticCore() {
    this.json = new ObjectMapper();
    this.configuration = DiagnosticConfiguration.from(System.getenv());
    this.ollama =
        new OllamaLocalShadowTruncationDiagnosticClient(
            json, configuration.model(), configuration.modelDigest());
    this.reportPath = DEFAULT_REPORT_PATH;
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  SoloLiquidAiTruncationDiagnosticCore(
      Map<String, String> environment, OllamaTruncationDiagnosticApi ollama, Path reportPath) {
    this.json = new ObjectMapper();
    this.configuration = DiagnosticConfiguration.from(environment);
    this.ollama = Objects.requireNonNull(ollama, "ollama");
    this.reportPath = Objects.requireNonNull(reportPath, "reportPath");
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  @Test
  void writeAggregateOnlyTruncationDiagnosticReport() throws Exception {
    execute();
  }

  ObjectNode execute() throws Exception {
    Path temporaryPath = temporaryPath();
    deleteOutput(reportPath);
    deleteOutput(temporaryPath);

    Resources resources = verifyResources();
    List<DiagnosticFixture> fixtures = loadFixtures(resources);
    OllamaModelPreflight preflight = ollama.preflight();
    verifyPreflight(preflight);

    OllamaWarmupResult warmup = null;
    OllamaObservedAllocation warmAllocation = OllamaObservedAllocation.notLoaded();
    OllamaObservedAllocation finalAllocation = OllamaObservedAllocation.notLoaded();
    DiagnosticRun run = null;
    RuntimeException executionFailure = null;
    CleanupResult cleanup;
    try {
      warmup = ollama.warmup();
      warmAllocation = ollama.allocation();
      run =
          evaluate(
              fixtures,
              resources.evidenceSchema(),
              resources.selectionSchema(),
              resources.selectionSchemaNode());
      finalAllocation = ollama.allocation();
    } catch (RuntimeException exception) {
      executionFailure = exception;
    } finally {
      cleanup = restoreModelState();
    }

    if (!cleanup.restored()) {
      deleteOutput(reportPath);
      deleteOutput(temporaryPath);
      throw new IllegalStateException("Ollama model state restoration failed before reporting.");
    }
    if (executionFailure != null) {
      deleteOutput(reportPath);
      deleteOutput(temporaryPath);
      throw executionFailure;
    }
    require(warmup != null && run != null, "v7-A execution did not complete");

    ObjectNode report =
        buildReport(
            preflight,
            warmup,
            maximumAllocation(warmAllocation, finalAllocation),
            cleanup,
            run,
            resources.integrity());
    byte[] encoded =
        (json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    require(encoded.length <= MAX_REPORT_BYTES, "v7-A report exceeds 512 KiB");
    assertAggregateOnly(report, encoded, fixtures);
    publishAtomically(temporaryPath, encoded);
    require(!Files.exists(temporaryPath), "v7-A temporary report remains");
    return report;
  }

  private DiagnosticRun evaluate(
      List<DiagnosticFixture> fixtures,
      Schema evidenceSchema,
      Schema selectionSchema,
      ObjectNode selectionSchemaNode) {
    List<Long> selectorLatency = new ArrayList<>();
    List<OllamaApiMetrics> apiMetrics = new ArrayList<>();
    List<Integer> outputBytes = new ArrayList<>();
    Map<DiagnosticTermination, Integer> terminationCounts =
        new EnumMap<>(DiagnosticTermination.class);
    for (DiagnosticTermination termination : DiagnosticTermination.values()) {
      terminationCounts.put(termination, 0);
    }
    Map<String, Integer> rejectionCounts = new TreeMap<>();
    int requestCount = 0;
    int wireResponseCount = 0;
    int completedStopCount = 0;
    int selectionSchemaValidCount = 0;
    int selectionDomainValidCount = 0;
    int acceptedCount = 0;
    int fallbackCount = 0;
    int guardedSchemaValidCount = 0;
    int guardedDomainValidCount = 0;
    int protectedMutationCount = 0;
    int fakeSkillMismatchCount = 0;
    int evalCountEqualConfiguredCapCount = 0;

    for (DiagnosticFixture fixture : fixtures) {
      String memoText = fixture.node().path("content").asText();
      Instant baseInstant = Instant.parse(fixture.node().path("baseInstant").asText());
      String timeZone = fixture.node().path("timeZone").asText();
      UUID memoId = deterministicMemoId(fixture.split(), fixture.ordinal());
      ObjectNode fakeProposal =
          fakeAnalyzer.analyze(memoId, MEMO_REVISION, memoText, baseInstant, timeZone);
      SkillProjection projection =
          skill.project(
              fakeProposal,
              memoId,
              MEMO_REVISION,
              memoText,
              fakeAnalyzer.provenance(),
              ambiguityGate.version());
      require(
          evidenceSchema.validate(projection.evidenceJson()).isEmpty(),
          "deterministic skill evidence schema validation failed");
      ObjectNode skillProposal = skill.skillOnlyProposal(projection);
      if (!skillProposal.equals(fakeProposal)) {
        fakeSkillMismatchCount++;
      }

      requestCount++;
      OllamaTruncationDiagnosticResult result =
          ollama.diagnose(projection.evidenceJson(), selectionSchemaNode.deepCopy());
      wireResponseCount++;
      terminationCounts.merge(result.termination(), 1, Integer::sum);
      if (result.completedStop()) {
        completedStopCount++;
      }
      selectorLatency.add(result.wallDurationNanos());
      apiMetrics.add(result.metrics());
      outputBytes.add(result.modelOutputBytes());
      if (Objects.equals(
          result.metrics().evalCount(),
          (long) OllamaLocalShadowTruncationDiagnosticClient.NUM_PREDICT)) {
        evalCountEqualConfiguredCapCount++;
      }

      SkillSelection acceptedSelection = null;
      if (result.rejection() != DiagnosticModelRejection.NONE) {
        increment(rejectionCounts, "MODEL_" + result.rejection().name());
      } else {
        ObjectNode selectionOutput = result.selectionOutput();
        require(selectionOutput != null, "eligible stop omitted its selection output");
        if (!selectionSchema.validate(selectionOutput).isEmpty()) {
          increment(rejectionCounts, "SELECTION_SCHEMA_INVALID");
        } else {
          selectionSchemaValidCount++;
          try {
            acceptedSelection = skill.validateSelection(selectionOutput, projection.evidence());
            selectionDomainValidCount++;
            acceptedCount++;
          } catch (SkillSelectionRejectedException exception) {
            increment(rejectionCounts, "SELECTION_DOMAIN_" + exception.reason().name());
          }
        }
      }

      ObjectNode guardedProposal;
      if (acceptedSelection == null) {
        fallbackCount++;
        guardedProposal = skill.fallbackProposal(projection);
      } else {
        guardedProposal = skill.guardedProposal(projection, acceptedSelection);
      }
      try {
        ShadowDeterministicSkill.assertOnlySuggestedTitleValueChanged(
            skillProposal, guardedProposal);
      } catch (RuntimeException exception) {
        protectedMutationCount++;
        throw exception;
      }
      canonicalSchemaValidator.validate(guardedProposal);
      guardedSchemaValidCount++;
      canonicalDomainValidator.validate(
          guardedProposal,
          memoId,
          MEMO_REVISION,
          memoText,
          fakeAnalyzer.provenance(),
          ambiguityGate.version());
      guardedDomainValidCount++;
    }

    require(requestCount == EXPECTED_CASE_COUNT, "v7-A did not issue exactly 24 selections");
    require(wireResponseCount == EXPECTED_CASE_COUNT, "v7-A did not receive 24 wire responses");
    require(
        terminationCounts.values().stream().mapToInt(Integer::intValue).sum()
            == EXPECTED_CASE_COUNT,
        "v7-A termination accounting is inconsistent");
    require(
        acceptedCount + fallbackCount == EXPECTED_CASE_COUNT,
        "v7-A selection/fallback accounting is inconsistent");
    require(apiMetrics.size() == EXPECTED_CASE_COUNT, "v7-A API metric accounting changed");
    require(outputBytes.size() == EXPECTED_CASE_COUNT, "v7-A output byte accounting changed");
    return new DiagnosticRun(
        List.copyOf(selectorLatency),
        List.copyOf(apiMetrics),
        List.copyOf(outputBytes),
        Map.copyOf(terminationCounts),
        Map.copyOf(rejectionCounts),
        requestCount,
        wireResponseCount,
        completedStopCount,
        selectionSchemaValidCount,
        selectionDomainValidCount,
        acceptedCount,
        requestCount - acceptedCount,
        fallbackCount,
        guardedSchemaValidCount,
        guardedDomainValidCount,
        protectedMutationCount,
        fakeSkillMismatchCount,
        evalCountEqualConfiguredCapCount);
  }

  private ObjectNode buildReport(
      OllamaModelPreflight preflight,
      OllamaWarmupResult warmup,
      OllamaObservedAllocation allocation,
      CleanupResult cleanup,
      DiagnosticRun run,
      ResourceIntegrity integrity) {
    ObjectNode report =
        json.createObjectNode()
            .put("reportVersion", REPORT_VERSION)
            .put("datasetVersion", "2")
            .put("evaluationStatus", "SOLO_PROVISIONAL")
            .put("useRestriction", "REPORT_ONLY")
            .put("dataScope", "PUBLIC_VISIBLE_DEVELOPMENT_ONLY")
            .put("providerDecision", "NOT_CONFIGURED")
            .put("containsRawMemoContent", false)
            .put("containsRawModelOutput", false)
            .put("containsCaseIdentifiers", false)
            .put("automaticApply", false);

    report
        .putObject("scopeBoundary")
        .put("personalMemoRead", false)
        .put("personalPostgresqlReadOrWrite", false)
        .put("canonicalReadOrWrite", false)
        .put("productAdapterEnabled", false)
        .put("networkDestination", "LOOPBACK_ONLY")
        .put("fineTuningPerformed", false)
        .put("trainingToolInstalled", false)
        .put("ragUsed", false);

    ObjectNode lineage = report.putObject("predecessorArtifactPins");
    lineage
        .put("preservationPolicy", "READ_ONLY_UNCHANGED")
        .put("reportFileName", V6_REPORT_FILE)
        .put("reportBytes", V6_REPORT_BYTES)
        .put("reportSha256", V6_REPORT_SHA256)
        .put("attestationFileName", V6_ATTESTATION_FILE)
        .put("attestationBytes", V6_ATTESTATION_BYTES)
        .put("attestationSha256", V6_ATTESTATION_SHA256);

    report
        .putObject("resourceIntegrity")
        .put("regressionFixtureSha256", integrity.regressionSha256())
        .put("visibleChallengeFixtureSha256", integrity.challengeSha256())
        .put("evaluationCaseSchemaSha256", integrity.caseSchemaSha256())
        .put("canonicalProposalSchemaSha256", integrity.canonicalSchemaSha256())
        .put("skillEvidenceSchemaSha256", integrity.evidenceSchemaSha256())
        .put("modelSelectionSchemaSha256", integrity.selectionSchemaSha256())
        .put("baseHead", configuration.baseHead())
        .put("sourceBundleSha256", configuration.sourceBundleSha256());

    ObjectNode execution = report.putObject("execution");
    execution
        .put("publicSyntheticCaseCount", EXPECTED_CASE_COUNT)
        .put("modelSelectionRequestCount", run.requestCount())
        .put("modelWireResponseCount", run.wireResponseCount())
        .put("completedStopCount", run.completedStopCount())
        .put("modelSelectionSchemaValidCount", run.selectionSchemaValidCount())
        .put("modelSelectionDomainValidCount", run.selectionDomainValidCount())
        .put("modelSelectionAcceptedCount", run.acceptedCount())
        .put("modelSelectionRejectedCount", run.rejectedCount())
        .put("skillFallbackCount", run.fallbackCount())
        .put("guardedCanonicalSchemaValidCount", run.guardedSchemaValidCount())
        .put("guardedDomainValidCount", run.guardedDomainValidCount())
        .put("retryCount", 0)
        .put("toolCallCount", 0);
    ObjectNode terminations = execution.putObject("terminationCounts");
    terminations
        .put("stop", run.terminationCounts().get(DiagnosticTermination.STOP))
        .put("length", run.terminationCounts().get(DiagnosticTermination.LENGTH))
        .put("other", run.terminationCounts().get(DiagnosticTermination.OTHER));
    ObjectNode rejections = execution.putObject("selectionRejectionCounts");
    rejections.put("total", run.rejectedCount());
    ObjectNode byReason = rejections.putObject("byReason");
    run.rejectionCounts().forEach(byReason::put);

    ObjectNode diagnostics = report.putObject("terminationDiagnostics");
    diagnostics
        .put("lengthContentPolicy", "COUNT_BYTES_ONLY_NEVER_PARSE_STORE_OR_REPORT")
        .put("lengthContentParsed", false)
        .put("lengthContentStored", false)
        .put("lengthContentReported", false)
        .put("configuredEvalTokenCap", OllamaLocalShadowTruncationDiagnosticClient.NUM_PREDICT)
        .put("evalCountEqualConfiguredCapCount", run.evalCountEqualConfiguredCapCount());
    diagnostics.set("promptEvalTokens", tokenDistribution(run.apiMetrics(), true));
    diagnostics.set("evalTokens", tokenDistribution(run.apiMetrics(), false));
    diagnostics.set("modelContentBytes", outputByteDistribution(run.outputBytes()));

    report
        .putObject("protectedProposalInvariant")
        .put("onlyMutablePointer", "/suggestedTitle/value")
        .put("fakeToSkillDeepMismatchCaseCount", run.fakeSkillMismatchCount())
        .put("modelProtectedMutationCaseCount", run.protectedMutationCount())
        .put("passed", run.fakeSkillMismatchCount() == 0 && run.protectedMutationCount() == 0);

    ObjectNode performance = report.putObject("performance");
    performance.set("modelSelectionAttemptWallLatency", latency(run.selectorLatencyNanos()));
    performance
        .putObject("configuredCaps")
        .put("numContext", OllamaLocalShadowTruncationDiagnosticClient.NUM_CONTEXT)
        .put("numPredict", OllamaLocalShadowTruncationDiagnosticClient.NUM_PREDICT)
        .put("temperature", 0)
        .put("seed", OllamaLocalShadowTruncationDiagnosticClient.SEED)
        .put("modelOutputBytes", OllamaLocalShadowTruncationDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
        .put("requestBytes", 32 * 1024)
        .put("wireResponseBytes", 64 * 1024)
        .put("retryCount", 0);

    ObjectNode hardware = report.putObject("hardware");
    hardware
        .putObject("gpu")
        .put("name", configuration.gpuName())
        .put("driver", configuration.gpuDriver())
        .put("totalMiB", configuration.gpuTotalMiB())
        .put("baselineUsedMiB", configuration.gpuBaselineUsedMiB())
        .put("measurementScope", "DEVICE_WIDE_NON_EXCLUSIVE");
    hardware
        .putObject("ollama")
        .put("version", preflight.ollamaVersion())
        .put("model", preflight.model())
        .put("digest", preflight.digest())
        .put("format", preflight.format())
        .put("family", preflight.family())
        .put("parameterSize", preflight.parameterSize())
        .put("quantization", preflight.quantization())
        .put("initiallyLoaded", preflight.initiallyLoaded())
        .put("initiallyLoadedModelCount", preflight.initiallyLoadedModelCount())
        .put("warmupWallMilliseconds", milliseconds(warmup.wallDurationNanos()));
    hardware
        .putObject("ollamaObservedAllocation")
        .put("loadedDuringRun", allocation.loaded())
        .put("sizeBytes", allocation.sizeBytes())
        .put("sizeVramBytes", allocation.sizeVramBytes())
        .put("contextLength", allocation.contextLength());

    report
        .putObject("restoration")
        .put("status", cleanup.status())
        .put("restored", cleanup.restored())
        .put("scopedRunnerTemporaryArtifactRemaining", false);
    report
        .putObject("diagnosticInterpretation")
        .put("status", "REPORT_ONLY")
        .put("singleVariableChanged", "NUM_PREDICT_64_TO_128")
        .put("qualityOrProviderReadinessClaimed", false)
        .put("fallbackValidityAttributedToLiquidAi", false);
    report
        .putObject("developmentAcceptance")
        .put("status", "NOT_MET")
        .put("reason", "TRUNCATION_DIAGNOSTIC_ONLY")
        .put("blindProviderPassClaimed", false);
    report
        .putObject("training")
        .put("decision", "NO_GO_FOR_TRAINING")
        .put("fineTuningPerformed", false)
        .put("loraDecision", "NO_GO")
        .put("loraPerformed", false);
    return report;
  }

  private ObjectNode tokenDistribution(List<OllamaApiMetrics> metrics, boolean prompt) {
    List<Long> values =
        metrics.stream()
            .map(prompt ? OllamaApiMetrics::promptEvalCount : OllamaApiMetrics::evalCount)
            .filter(Objects::nonNull)
            .toList();
    return longDistribution(values, metrics.size());
  }

  private ObjectNode longDistribution(List<Long> values, int expectedSamples) {
    ObjectNode result =
        json.createObjectNode()
            .put("sampleCount", values.size())
            .put("expectedSampleCount", expectedSamples);
    if (values.isEmpty()) {
      return result.putNull("sum").putNull("minimum").putNull("maximum").putNull("mean");
    }
    long sum = values.stream().mapToLong(Long::longValue).sum();
    return result
        .put("sum", sum)
        .put("minimum", values.stream().mapToLong(Long::longValue).min().orElseThrow())
        .put("maximum", values.stream().mapToLong(Long::longValue).max().orElseThrow())
        .put("mean", rounded((double) sum / values.size()));
  }

  private ObjectNode outputByteDistribution(List<Integer> values) {
    ObjectNode result = json.createObjectNode();
    int minimum = values.stream().mapToInt(Integer::intValue).min().orElseThrow();
    int maximum = values.stream().mapToInt(Integer::intValue).max().orElseThrow();
    int atOrBelow =
        (int)
            values.stream()
                .filter(
                    value ->
                        value <= OllamaLocalShadowTruncationDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
                .count();
    int equal =
        (int)
            values.stream()
                .filter(
                    value ->
                        value == OllamaLocalShadowTruncationDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
                .count();
    ObjectNode buckets = result.putObject("bucketCounts");
    buckets
        .put("zeroTo64", countRange(values, 0, 64))
        .put("sixtyFiveTo128", countRange(values, 65, 128))
        .put("oneHundredTwentyNineTo256", countRange(values, 129, 256))
        .put("over256", values.size() - atOrBelow);
    return result
        .put("sampleCount", values.size())
        .put("minimum", minimum)
        .put("maximum", maximum)
        .put("mean", rounded(values.stream().mapToInt(Integer::intValue).average().orElseThrow()))
        .put(
            "configuredCapBytes",
            OllamaLocalShadowTruncationDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
        .put("atOrBelowCapCount", atOrBelow)
        .put("equalToCapCount", equal)
        .put("overCapCount", values.size() - atOrBelow);
  }

  private int countRange(List<Integer> values, int minimum, int maximum) {
    return (int) values.stream().filter(value -> value >= minimum && value <= maximum).count();
  }

  private ObjectNode latency(List<Long> samples) {
    List<Long> sorted = samples.stream().sorted().toList();
    double mean = samples.stream().mapToLong(Long::longValue).average().orElseThrow();
    return json.createObjectNode()
        .put("sampleCount", samples.size())
        .put("p50Milliseconds", milliseconds(percentile(sorted, 0.50)))
        .put("p95Milliseconds", milliseconds(percentile(sorted, 0.95)))
        .put("maximumMilliseconds", milliseconds(sorted.getLast()))
        .put("meanMilliseconds", milliseconds(mean));
  }

  private Resources verifyResources() {
    byte[] regression = readResource(REGRESSION_RESOURCE);
    byte[] challenge = readResource(CHALLENGE_RESOURCE);
    byte[] caseSchema = readResource(CASE_SCHEMA_RESOURCE);
    byte[] canonicalSchema = readResource(CANONICAL_SCHEMA_RESOURCE);
    byte[] evidenceSchema = readResource(EVIDENCE_SCHEMA_RESOURCE);
    byte[] selectionSchema = readResource(SELECTION_SCHEMA_RESOURCE);
    verifySha(
        regression,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_REGRESSION_SHA256,
        "regression fixture");
    verifySha(
        challenge,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_CHALLENGE_SHA256,
        "visible challenge fixture");
    verifySha(
        caseSchema,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_CASE_SCHEMA_SHA256,
        "evaluation case schema");
    verifySha(
        canonicalSchema,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_CANONICAL_SCHEMA_SHA256,
        "canonical proposal schema");
    verifySha(
        evidenceSchema,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_EVIDENCE_SCHEMA_SHA256,
        "skill evidence schema");
    verifySha(
        selectionSchema,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_SELECTION_SCHEMA_SHA256,
        "model selection schema");
    ObjectNode selectionNode = readObject(selectionSchema);
    return new Resources(
        readNode(regression),
        readNode(challenge),
        loadSchema(readObject(caseSchema)),
        loadSchema(readObject(evidenceSchema)),
        loadSchema(selectionNode),
        selectionNode.deepCopy(),
        new ResourceIntegrity(
            SoloLiquidAiDeterministicSkillCore.EXPECTED_REGRESSION_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CHALLENGE_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CASE_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CANONICAL_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_EVIDENCE_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_SELECTION_SCHEMA_SHA256));
  }

  private List<DiagnosticFixture> loadFixtures(Resources resources) {
    require(resources.regression().size() == EXPECTED_SPLIT_SIZE, "regression count changed");
    require(resources.challenge().size() == EXPECTED_SPLIT_SIZE, "challenge count changed");
    List<DiagnosticFixture> fixtures = new ArrayList<>();
    addFixtures(fixtures, resources.regression(), "REGRESSION", resources.caseSchema());
    addFixtures(fixtures, resources.challenge(), "VISIBLE_CHALLENGE", resources.caseSchema());
    Set<String> ids = new HashSet<>();
    Set<String> memoTexts = new HashSet<>();
    for (DiagnosticFixture fixture : fixtures) {
      require(ids.add(fixture.node().path("id").asText()), "fixture IDs are not unique");
      require(
          memoTexts.add(fixture.node().path("content").asText()), "fixture memos are not unique");
    }
    require(fixtures.size() == EXPECTED_CASE_COUNT, "v7-A fixture count changed");
    return List.copyOf(fixtures);
  }

  private void addFixtures(
      List<DiagnosticFixture> target, JsonNode values, String split, Schema schema) {
    for (int ordinal = 0; ordinal < values.size(); ordinal++) {
      JsonNode fixture = values.get(ordinal);
      require(schema.validate(fixture).isEmpty(), "fixture schema validation failed");
      require("2".equals(fixture.path("datasetVersion").asText()), "dataset version changed");
      require(split.equals(fixture.path("split").asText()), "fixture split changed");
      EvaluationV2GoldIntegrity.validate(fixture);
      target.add(new DiagnosticFixture(fixture, split, ordinal));
    }
  }

  private Schema loadSchema(ObjectNode schemaNode) {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).failFast(false).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config));
    Schema schema =
        registry.getSchema(
            new ByteArrayInputStream(schemaNode.toString().getBytes(StandardCharsets.UTF_8)));
    schema.initializeValidators();
    return schema;
  }

  private byte[] readResource(String resource) {
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      require(input != null, "required v7-A resource is missing");
      byte[] encoded = input.readNBytes(MAX_RESOURCE_BYTES + 1);
      require(encoded.length <= MAX_RESOURCE_BYTES, "v7-A resource is too large");
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException("Required v7-A resource could not be read.", exception);
    }
  }

  private JsonNode readNode(byte[] encoded) {
    JsonNode value = json.readTree(encoded);
    require(value != null, "required v7-A JSON resource is invalid");
    return value;
  }

  private ObjectNode readObject(byte[] encoded) {
    JsonNode value = readNode(encoded);
    require(value.isObject(), "required v7-A object resource is invalid");
    return (ObjectNode) value;
  }

  private void verifySha(byte[] encoded, String expected, String label) {
    require(
        expected.equals(SoloLiquidAiDeterministicSkillCore.sha256(encoded)),
        "Pinned SHA-256 changed for " + label + ".");
  }

  void assertNoForbiddenReportFields(ObjectNode report) {
    for (String forbidden : FORBIDDEN_REPORT_FIELDS) {
      require(report.findValue(forbidden) == null, "report contains a forbidden field");
    }
  }

  private void assertAggregateOnly(
      ObjectNode report, byte[] encoded, List<DiagnosticFixture> fixtures) {
    assertNoForbiddenReportFields(report);
    String serialized = new String(encoded, StandardCharsets.UTF_8);
    for (DiagnosticFixture fixture : fixtures) {
      require(
          !serialized.contains(fixture.node().path("id").asText()), "report contains a case ID");
      require(
          !serialized.contains(fixture.node().path("content").asText()),
          "report contains memo text");
      Set<String> textualLeaves = new HashSet<>();
      collectTextualLeaves(fixture.node(), textualLeaves);
      for (String leaf : textualLeaves) {
        if (!leaf.isBlank() && leaf.codePointCount(0, leaf.length()) >= 8) {
          require(!serialized.contains(leaf), "report contains a fixture textual leaf");
        }
      }
    }
  }

  private void collectTextualLeaves(JsonNode value, Set<String> target) {
    if (value.isTextual()) {
      target.add(value.asText());
    } else if (value.isArray()) {
      value.forEach(child -> collectTextualLeaves(child, target));
    } else if (value.isObject()) {
      value.properties().forEach(property -> collectTextualLeaves(property.getValue(), target));
    }
  }

  private void publishAtomically(Path temporary, byte[] encoded) throws IOException {
    Path parent = reportPath.getParent();
    require(parent != null, "v7-A report parent is missing");
    Files.createDirectories(parent);
    Files.write(temporary, encoded);
    Files.move(
        temporary, reportPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private CleanupResult restoreModelState() {
    try {
      ollama.unload();
      boolean restored = !ollama.allocation().loaded();
      return new CleanupResult(restored, restored ? "RESTORED" : "FAILED_MODEL_STILL_LOADED");
    } catch (RuntimeException exception) {
      return new CleanupResult(false, "FAILED_UNLOAD_OR_VERIFY");
    }
  }

  private void verifyPreflight(OllamaModelPreflight value) {
    require(
        OllamaLocalShadowClient.EXPECTED_OLLAMA_VERSION.equals(value.ollamaVersion()),
        "Ollama version changed");
    require(configuration.model().equals(value.model()), "Ollama model tag changed");
    require(configuration.modelDigest().equals(value.digest()), "Ollama model digest changed");
    require("gguf".equals(value.format()), "Ollama model is not GGUF");
    require("lfm2".equals(value.family()), "Ollama model is not LFM2");
    require("2.7B".equals(value.parameterSize()), "Ollama parameter size changed");
    require("Q8_0".equals(value.quantization()), "Ollama quantization changed");
    require(!value.initiallyLoaded(), "v7-A requires a clean unloaded-model prestate");
    require(value.initiallyLoadedModelCount() == 0, "v7-A requires zero initially loaded models");
  }

  private UUID deterministicMemoId(String split, int ordinal) {
    return UUID.nameUUIDFromBytes(
        (MEMO_ID_PREFIX + split + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  private OllamaObservedAllocation maximumAllocation(
      OllamaObservedAllocation first, OllamaObservedAllocation second) {
    return new OllamaObservedAllocation(
        first.loaded() || second.loaded(),
        Math.max(first.sizeBytes(), second.sizeBytes()),
        Math.max(first.sizeVramBytes(), second.sizeVramBytes()),
        Math.max(first.contextLength(), second.contextLength()));
  }

  private long percentile(List<Long> sorted, double percentile) {
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private double milliseconds(long nanos) {
    return milliseconds((double) nanos);
  }

  private double milliseconds(double nanos) {
    return rounded(nanos / 1_000_000d);
  }

  private double rounded(double value) {
    return Math.round(value * 1_000d) / 1_000d;
  }

  private void increment(Map<String, Integer> counts, String reason) {
    counts.merge(reason, 1, Integer::sum);
  }

  private Path temporaryPath() {
    return reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
  }

  private void deleteOutput(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("v7-A output cleanup failed.", exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record DiagnosticFixture(JsonNode node, String split, int ordinal) {}

  private record Resources(
      JsonNode regression,
      JsonNode challenge,
      Schema caseSchema,
      Schema evidenceSchema,
      Schema selectionSchema,
      ObjectNode selectionSchemaNode,
      ResourceIntegrity integrity) {
    Resources {
      Objects.requireNonNull(selectionSchemaNode, "selectionSchemaNode");
      selectionSchemaNode = selectionSchemaNode.deepCopy();
    }

    @Override
    public ObjectNode selectionSchemaNode() {
      return selectionSchemaNode.deepCopy();
    }
  }

  private record ResourceIntegrity(
      String regressionSha256,
      String challengeSha256,
      String caseSchemaSha256,
      String canonicalSchemaSha256,
      String evidenceSchemaSha256,
      String selectionSchemaSha256) {}

  private record DiagnosticRun(
      List<Long> selectorLatencyNanos,
      List<OllamaApiMetrics> apiMetrics,
      List<Integer> outputBytes,
      Map<DiagnosticTermination, Integer> terminationCounts,
      Map<String, Integer> rejectionCounts,
      int requestCount,
      int wireResponseCount,
      int completedStopCount,
      int selectionSchemaValidCount,
      int selectionDomainValidCount,
      int acceptedCount,
      int rejectedCount,
      int fallbackCount,
      int guardedSchemaValidCount,
      int guardedDomainValidCount,
      int protectedMutationCount,
      int fakeSkillMismatchCount,
      int evalCountEqualConfiguredCapCount) {}

  private record CleanupResult(boolean restored, String status) {}

  record DiagnosticConfiguration(
      String model,
      String modelDigest,
      String baseHead,
      String sourceBundleSha256,
      String gpuName,
      String gpuDriver,
      long gpuTotalMiB,
      long gpuBaselineUsedMiB) {
    static DiagnosticConfiguration from(Map<String, String> environment) {
      Objects.requireNonNull(environment, "environment");
      requireExact(environment, OPT_IN_ENV, OPT_IN_VALUE);
      requireExact(
          environment,
          SoloLiquidAiDeterministicSkillCore.MODEL_ENV,
          SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
      requireExact(
          environment,
          SoloLiquidAiDeterministicSkillCore.DIGEST_ENV,
          SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
      return new DiagnosticConfiguration(
          SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL,
          SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST,
          requirePattern(
              environment,
              SoloLiquidAiDeterministicSkillCore.BASE_HEAD_ENV,
              "[0-9a-f]{40}|[0-9a-f]{64}"),
          requirePattern(
              environment, SoloLiquidAiDeterministicSkillCore.SOURCE_BUNDLE_ENV, "[0-9a-f]{64}"),
          requirePattern(
              environment, SoloLiquidAiDeterministicSkillCore.GPU_NAME_ENV, GPU_TEXT.pattern()),
          requirePattern(
              environment,
              SoloLiquidAiDeterministicSkillCore.GPU_DRIVER_ENV,
              DRIVER_TEXT.pattern()),
          requireMiB(environment, SoloLiquidAiDeterministicSkillCore.GPU_TOTAL_ENV, false),
          requireMiB(environment, SoloLiquidAiDeterministicSkillCore.GPU_BASELINE_USED_ENV, true));
    }

    private static void requireExact(
        Map<String, String> environment, String name, String expected) {
      if (!expected.equals(environment.get(name))) {
        throw new IllegalArgumentException(name + " must contain the exact v7-A opt-in value.");
      }
    }

    private static String requirePattern(
        Map<String, String> environment, String name, String pattern) {
      String value = environment.get(name);
      if (value == null || !value.matches(pattern)) {
        throw new IllegalArgumentException(name + " is missing or outside its allowlist.");
      }
      return value;
    }

    private static long requireMiB(
        Map<String, String> environment, String name, boolean zeroAllowed) {
      String value = environment.get(name);
      if (value == null || !value.matches("[0-9]{1,7}")) {
        throw new IllegalArgumentException(name + " must be a bounded integer MiB value.");
      }
      long parsed = Long.parseLong(value);
      if (parsed < (zeroAllowed ? 0 : 1) || parsed > 1_048_576) {
        throw new IllegalArgumentException(name + " is outside the supported MiB range.");
      }
      return parsed;
    }
  }
}

/** Single-constructor JUnit entry point selected only by its explicit class name. */
class SoloLiquidAiTruncationDiagnosticRunner extends SoloLiquidAiTruncationDiagnosticCore {
  SoloLiquidAiTruncationDiagnosticRunner() {
    super();
  }
}
