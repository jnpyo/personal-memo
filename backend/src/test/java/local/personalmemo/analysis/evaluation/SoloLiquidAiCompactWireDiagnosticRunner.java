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

/** Explicit aggregate-only v8-A diagnostic for the pre-registered compact-wire treatment. */
class SoloLiquidAiCompactWireDiagnosticCore {
  static final String OPT_IN_ENV = "PERSONAL_MEMO_SOLO_LIQUIDAI_COMPACT_WIRE_DIAGNOSTIC";
  static final String OPT_IN_VALUE = "SOLO_PROVISIONAL_REPORT_ONLY";

  private static final String REPORT_VERSION = "solo-liquidai-compact-wire-diagnostic-v8a";
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("target", "evaluation", "solo-liquidai-compact-wire-diagnostic-v8a.json");
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
  private static final String COMPACT_WIRE_SCHEMA_RESOURCE =
      "/contracts/solo-liquidai-compact-wire-selection.schema.json";
  private static final String EXPECTED_COMPACT_WIRE_SCHEMA_SHA256 =
      "24b47cc72405320dd4dff795b0c97f0dc1a8aee37cc8e84a81c10034a87e890e";
  private static final int EXPECTED_SPLIT_SIZE = 12;
  private static final int EXPECTED_CASE_COUNT = 24;
  private static final int MEMO_REVISION = 1;
  private static final int MAX_RESOURCE_BYTES = 512 * 1024;
  private static final int MAX_REPORT_BYTES = 512 * 1024;
  private static final String MEMO_ID_PREFIX = "personal-memo:liquidai-compact-wire-v8a:";
  private static final String V7B_REPORT_FILE =
      "solo-liquidai-overhead-reduction-diagnostic-v7b.json";
  private static final long V7B_REPORT_BYTES = 7_081L;
  private static final String V7B_REPORT_SHA256 =
      "c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37";
  private static final String V7B_ATTESTATION_FILE =
      "solo-liquidai-overhead-reduction-diagnostic-v7b-attestation.json";
  private static final long V7B_ATTESTATION_BYTES = 9_743L;
  private static final String V7B_ATTESTATION_SHA256 =
      "ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d";
  private static final int V7B_PROMPT_EVAL_SAMPLE_COUNT = 24;
  private static final long V7B_PROMPT_EVAL_SUM = 5_973L;
  private static final long V7B_PROMPT_EVAL_MINIMUM = 214L;
  private static final long V7B_PROMPT_EVAL_MAXIMUM = 314L;
  private static final double V7B_PROMPT_EVAL_MEAN = 248.875d;
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
          "compactWireOutput",
          "mappedSelectionOutput",
          "rawModelOutput",
          "prompt",
          "skillEvidence",
          "defaultTitle",
          "objectValue",
          "primaryItemOrdinal",
          "topicObjectOrdinals",
          "v",
          "p",
          "t",
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

  SoloLiquidAiCompactWireDiagnosticCore() {
    this.json = new ObjectMapper();
    this.configuration = DiagnosticConfiguration.from(System.getenv());
    this.ollama =
        new OllamaLocalShadowCompactWireDiagnosticClient(
            json, configuration.model(), configuration.modelDigest());
    this.reportPath = DEFAULT_REPORT_PATH;
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  SoloLiquidAiCompactWireDiagnosticCore(
      Map<String, String> environment, OllamaTruncationDiagnosticApi ollama, Path reportPath) {
    this.json = new ObjectMapper();
    this.configuration = DiagnosticConfiguration.from(environment);
    this.ollama = Objects.requireNonNull(ollama, "ollama");
    this.reportPath = Objects.requireNonNull(reportPath, "reportPath");
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  @Test
  void writeAggregateOnlyCompactWireDiagnosticReport() throws Exception {
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
              resources.compactWireSchema(),
              resources.selectionSchema(),
              resources.compactWireSchemaNode());
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
    require(warmup != null && run != null, "v8-A execution did not complete");

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
    require(encoded.length <= MAX_REPORT_BYTES, "v8-A report exceeds 512 KiB");
    assertAggregateOnly(report, encoded, fixtures);
    publishAtomically(temporaryPath, encoded);
    require(!Files.exists(temporaryPath), "v8-A temporary report remains");
    return report;
  }

  private DiagnosticRun evaluate(
      List<DiagnosticFixture> fixtures,
      Schema evidenceSchema,
      Schema compactWireSchema,
      Schema selectionSchema,
      ObjectNode compactWireSchemaNode) {
    List<Long> fakeAnalyzerLatency = new ArrayList<>();
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
    int compactWireObjectParsedCount = 0;
    int compactWireSchemaValidCount = 0;
    int compactWireMappedCount = 0;
    int mappedFullSelectionSchemaValidCount = 0;
    int mappedSelectionDomainValidCount = 0;
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
      long fakeAnalyzerStarted = System.nanoTime();
      ObjectNode fakeProposal =
          fakeAnalyzer.analyze(memoId, MEMO_REVISION, memoText, baseInstant, timeZone);
      fakeAnalyzerLatency.add(System.nanoTime() - fakeAnalyzerStarted);
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
          ollama.diagnose(projection.evidenceJson(), compactWireSchemaNode.deepCopy());
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
          (long) OllamaLocalShadowCompactWireDiagnosticClient.NUM_PREDICT)) {
        evalCountEqualConfiguredCapCount++;
      }

      SkillSelection acceptedSelection = null;
      if (result.rejection() != DiagnosticModelRejection.NONE) {
        increment(rejectionCounts, "MODEL_" + result.rejection().name());
      } else {
        ObjectNode compactWire = result.selectionOutput();
        require(compactWire != null, "eligible stop omitted its compact wire object");
        compactWireObjectParsedCount++;
        if (!compactWireSchema.validate(compactWire).isEmpty()) {
          increment(rejectionCounts, "COMPACT_WIRE_SCHEMA_INVALID");
        } else {
          compactWireSchemaValidCount++;
          ObjectNode mappedSelection = mapCompactWire(compactWire);
          compactWireMappedCount++;
          require(
              selectionSchema.validate(mappedSelection).isEmpty(),
              "strict compact mapping violated the frozen full selection schema");
          mappedFullSelectionSchemaValidCount++;
          try {
            acceptedSelection = skill.validateSelection(mappedSelection, projection.evidence());
            mappedSelectionDomainValidCount++;
            acceptedCount++;
          } catch (SkillSelectionRejectedException exception) {
            increment(rejectionCounts, "MAPPED_SELECTION_DOMAIN_" + exception.reason().name());
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

    require(requestCount == EXPECTED_CASE_COUNT, "v8-A did not issue exactly 24 selections");
    require(wireResponseCount == EXPECTED_CASE_COUNT, "v8-A did not receive 24 wire responses");
    require(
        terminationCounts.values().stream().mapToInt(Integer::intValue).sum()
            == EXPECTED_CASE_COUNT,
        "v8-A termination accounting is inconsistent");
    require(
        acceptedCount + fallbackCount == EXPECTED_CASE_COUNT,
        "v8-A selection/fallback accounting is inconsistent");
    require(
        rejectionCounts.values().stream().mapToInt(Integer::intValue).sum()
            == requestCount - acceptedCount,
        "v8-A rejection accounting is inconsistent");
    require(apiMetrics.size() == EXPECTED_CASE_COUNT, "v8-A API metric accounting changed");
    require(outputBytes.size() == EXPECTED_CASE_COUNT, "v8-A output byte accounting changed");
    require(
        fakeAnalyzerLatency.size() == EXPECTED_CASE_COUNT,
        "v8-A FakeAnalyzer latency accounting changed");
    require(
        compactWireSchemaValidCount == compactWireMappedCount
            && compactWireMappedCount == mappedFullSelectionSchemaValidCount,
        "v8-A compact-to-full deterministic mapping invariant changed");
    return new DiagnosticRun(
        List.copyOf(fakeAnalyzerLatency),
        List.copyOf(selectorLatency),
        List.copyOf(apiMetrics),
        List.copyOf(outputBytes),
        Map.copyOf(terminationCounts),
        Map.copyOf(rejectionCounts),
        requestCount,
        wireResponseCount,
        completedStopCount,
        compactWireObjectParsedCount,
        compactWireSchemaValidCount,
        compactWireMappedCount,
        mappedFullSelectionSchemaValidCount,
        mappedSelectionDomainValidCount,
        acceptedCount,
        requestCount - acceptedCount,
        fallbackCount,
        guardedSchemaValidCount,
        guardedDomainValidCount,
        protectedMutationCount,
        fakeSkillMismatchCount,
        evalCountEqualConfiguredCapCount);
  }

  private ObjectNode mapCompactWire(ObjectNode compactWire) {
    require(
        compactWire.size() == 3
            && compactWire.has("v")
            && compactWire.has("p")
            && compactWire.has("t"),
        "validated compact wire shape changed before mapping");
    ObjectNode mapped =
        json.createObjectNode()
            .put("schemaVersion", ShadowDeterministicSkill.SELECTION_VERSION)
            .put("primaryItemOrdinal", compactWire.path("p").asInt());
    mapped.set("topicObjectOrdinals", compactWire.path("t").deepCopy());
    return mapped;
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

    ObjectNode treatment = report.putObject("treatment");
    treatment
        .put("status", "PRE_REGISTERED_COMPACT_WIRE")
        .put("predecessorTreatmentPreserved", "REQUEST_OVERHEAD_REDUCTION_V7B")
        .put("promptVersion", OllamaLocalShadowCompactWireDiagnosticClient.PROMPT_VERSION)
        .put("wireSchemaVersion", "1")
        .put("wireAdditionalPropertiesAllowed", false)
        .put("wireAllFieldsRequired", true)
        .put("userMessageResponseSchemaIncluded", false)
        .put("userMessagePayload", "SKILL_EVIDENCE_ONLY")
        .put("rawOutputValidation", "FULL_PINNED_COMPACT_WIRE_SCHEMA")
        .put("mappingPolicy", "STRICT_NO_REPAIR_OR_CLAMP")
        .put("mappedOutputValidation", "FULL_FROZEN_V6_SELECTION_SCHEMA")
        .put("dynamicDomainValidation", "IDENTICAL_FROZEN_V6_SKILL_DOMAIN")
        .put("requestDeltaCount", 2);
    treatment.putArray("wireKeys").add("v").add("p").add("t");
    treatment.putArray("requestDeltaJsonPointers").add("/messages/0/content").add("/format");
    treatment.putArray("formatSchemaRootMetadataRemoved").add("$schema").add("$id").add("title");
    var strictFieldMapping = treatment.putArray("strictFieldMapping");
    strictFieldMapping
        .addObject()
        .put("wireKey", "v")
        .put("mappedField", "schemaVersion")
        .put("mappingRule", "1_TO_FROZEN_SELECTION_VERSION");
    strictFieldMapping
        .addObject()
        .put("wireKey", "p")
        .put("mappedField", "primaryItemOrdinal")
        .put("mappingRule", "EXACT_INTEGER");
    strictFieldMapping
        .addObject()
        .put("wireKey", "t")
        .put("mappedField", "topicObjectOrdinals")
        .put("mappingRule", "EXACT_ARRAY_ORDER_AND_VALUES");

    ObjectNode lineage = report.putObject("predecessorArtifactPins");
    lineage
        .put("preservationPolicy", "READ_ONLY_UNCHANGED")
        .put("reportFileName", V7B_REPORT_FILE)
        .put("reportBytes", V7B_REPORT_BYTES)
        .put("reportSha256", V7B_REPORT_SHA256)
        .put("attestationFileName", V7B_ATTESTATION_FILE)
        .put("attestationBytes", V7B_ATTESTATION_BYTES)
        .put("attestationSha256", V7B_ATTESTATION_SHA256);

    report
        .putObject("resourceIntegrity")
        .put("regressionFixtureSha256", integrity.regressionSha256())
        .put("visibleChallengeFixtureSha256", integrity.challengeSha256())
        .put("evaluationCaseSchemaSha256", integrity.caseSchemaSha256())
        .put("canonicalProposalSchemaSha256", integrity.canonicalSchemaSha256())
        .put("skillEvidenceSchemaSha256", integrity.evidenceSchemaSha256())
        .put("modelSelectionSchemaSha256", integrity.selectionSchemaSha256())
        .put("compactWireSchemaSha256", integrity.compactWireSchemaSha256())
        .put("baseHead", configuration.baseHead())
        .put("sourceBundleSha256", configuration.sourceBundleSha256());

    ObjectNode execution = report.putObject("execution");
    execution
        .put("publicSyntheticCaseCount", EXPECTED_CASE_COUNT)
        .put("modelSelectionRequestCount", run.requestCount())
        .put("modelWireResponseCount", run.wireResponseCount())
        .put("completedStopCount", run.completedStopCount())
        .put("compactWireObjectParsedCount", run.compactWireObjectParsedCount())
        .put("compactWireSchemaValidCount", run.compactWireSchemaValidCount())
        .put("compactWireMappedCount", run.compactWireMappedCount())
        .put("mappedFullSelectionSchemaValidCount", run.mappedFullSelectionSchemaValidCount())
        .put("mappedSelectionDomainValidCount", run.mappedSelectionDomainValidCount())
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
        .put("configuredEvalTokenCap", OllamaLocalShadowCompactWireDiagnosticClient.NUM_PREDICT)
        .put("evalCountEqualConfiguredCapCount", run.evalCountEqualConfiguredCapCount());
    ObjectNode promptEvalTokens = tokenDistribution(run.apiMetrics(), true);
    diagnostics.set("promptEvalTokens", promptEvalTokens);
    ObjectNode promptComparison = promptEvalTokenComparison(promptEvalTokens);
    treatment.set("promptEvalTokenComparison", promptComparison);
    treatment.put("promptTokenOutcome", promptComparison.path("outcome").asText());
    treatment.put("diagnosticOutcome", diagnosticOutcome(run));
    diagnostics.set("evalTokens", tokenDistribution(run.apiMetrics(), false));
    diagnostics.set("modelContentBytes", outputByteDistribution(run.outputBytes()));
    treatment.set("v7bAggregateReference", predecessorAggregateReference());
    treatment
        .putObject("deterministicMappingInvariant")
        .put("compactSchemaValidCount", run.compactWireSchemaValidCount())
        .put("mappedCount", run.compactWireMappedCount())
        .put("fullSchemaValidCount", run.mappedFullSelectionSchemaValidCount())
        .put("passed", true);

    report
        .putObject("protectedProposalInvariant")
        .put("onlyMutablePointer", "/suggestedTitle/value")
        .put("fakeToSkillDeepMismatchCaseCount", run.fakeSkillMismatchCount())
        .put("modelProtectedMutationCaseCount", run.protectedMutationCount())
        .put("passed", run.fakeSkillMismatchCount() == 0 && run.protectedMutationCount() == 0);

    ObjectNode performance = report.putObject("performance");
    performance.set("fakeAnalyzerWallLatency", latency(run.fakeAnalyzerLatencyNanos()));
    performance.set("modelSelectionAttemptWallLatency", latency(run.selectorLatencyNanos()));
    performance.set(
        "modelVsFakeAnalyzer",
        modelVsFakeAnalyzer(run.fakeAnalyzerLatencyNanos(), run.selectorLatencyNanos()));
    performance
        .putObject("configuredCaps")
        .put("numContext", OllamaLocalShadowCompactWireDiagnosticClient.NUM_CONTEXT)
        .put("numPredict", OllamaLocalShadowCompactWireDiagnosticClient.NUM_PREDICT)
        .put("temperature", 0)
        .put("seed", OllamaLocalShadowCompactWireDiagnosticClient.SEED)
        .put(
            "modelOutputBytes", OllamaLocalShadowCompactWireDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
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
        .put("preRegisteredTreatmentPackage", "COMPACT_WIRE_V8A")
        .put("diagnosticOutcome", treatment.path("diagnosticOutcome").asText())
        .put("qualityOrProviderReadinessClaimed", false)
        .put("fallbackValidityAttributedToLiquidAi", false);
    report.set("compactWireDiagnosticDecision", compactWireDiagnosticDecision(run, cleanup));
    report
        .putObject("developmentAcceptance")
        .put("status", "NOT_MET")
        .put("reason", "COMPACT_WIRE_DIAGNOSTIC_ONLY")
        .put("blindProviderPassClaimed", false);
    report
        .putObject("training")
        .put("decision", "NO_GO_FOR_TRAINING")
        .put("fineTuningPerformed", false)
        .put("loraDecision", "NO_GO")
        .put("loraPerformed", false);
    return report;
  }

  private String diagnosticOutcome(DiagnosticRun run) {
    if (run.acceptedCount() > 0) {
      return "COMPACT_WIRE_STOP_AND_ACCEPTANCE_OBSERVED";
    }
    if (run.terminationCounts().get(DiagnosticTermination.STOP) > 0) {
      return "COMPACT_WIRE_STOP_WITHOUT_ACCEPTANCE_OBSERVED";
    }
    return "NO_COMPLETED_STOP_OBSERVED";
  }

  private ObjectNode compactWireDiagnosticDecision(DiagnosticRun run, CleanupResult cleanup) {
    boolean safetyBoundaryMet =
        cleanup.restored()
            && run.protectedMutationCount() == 0
            && run.fakeSkillMismatchCount() == 0
            && run.guardedSchemaValidCount() == EXPECTED_CASE_COUNT
            && run.guardedDomainValidCount() == EXPECTED_CASE_COUNT
            && run.compactWireSchemaValidCount() == run.compactWireMappedCount()
            && run.compactWireMappedCount() == run.mappedFullSelectionSchemaValidCount()
            && run.mappedSelectionDomainValidCount() == run.acceptedCount();
    boolean advanceToNextNonTrainingEvaluation =
        safetyBoundaryMet
            && run.terminationCounts().get(DiagnosticTermination.STOP) > 0
            && run.acceptedCount() > 0;
    boolean fullReliabilityMet =
        safetyBoundaryMet
            && run.completedStopCount() == EXPECTED_CASE_COUNT
            && run.compactWireSchemaValidCount() == EXPECTED_CASE_COUNT
            && run.mappedSelectionDomainValidCount() == EXPECTED_CASE_COUNT
            && run.acceptedCount() == EXPECTED_CASE_COUNT
            && run.rejectedCount() == 0
            && run.fallbackCount() == 0;
    return json.createObjectNode()
        .put("policyVersion", "compact-wire-v8a-pre-registered-1")
        .put("policyFrozenBeforeModelExecution", true)
        .put(
            "advanceCondition",
            "STOP_AND_ACCEPTED_COUNT_GT_ZERO_WITH_ALL_SAFETY_AND_MAPPING_INVARIANTS")
        .put(
            "fullReliabilityCondition",
            "24_OF_24_STOP_SCHEMA_DOMAIN_ACCEPTED_WITH_ZERO_REJECTION_OR_FALLBACK")
        .put("safetyBoundaryMet", safetyBoundaryMet)
        .put("fullReliabilityMet", fullReliabilityMet)
        .put(
            "decision",
            advanceToNextNonTrainingEvaluation ? "GO_TO_NEXT_NON_TRAINING_EVALUATION" : "NO_GO")
        .put("productOrProviderDecision", "NO_GO")
        .put("trainingDecision", "NO_GO_FOR_TRAINING")
        .put("loraDecision", "NO_GO");
  }

  private ObjectNode promptEvalTokenComparison(ObjectNode observed) {
    ObjectNode comparison = json.createObjectNode();
    comparison
        .putObject("v7bReference")
        .put("reportVersion", "solo-liquidai-overhead-reduction-diagnostic-v7b")
        .put("reportSha256", V7B_REPORT_SHA256)
        .put("sampleCount", V7B_PROMPT_EVAL_SAMPLE_COUNT)
        .put("sum", V7B_PROMPT_EVAL_SUM)
        .put("minimum", V7B_PROMPT_EVAL_MINIMUM)
        .put("maximum", V7B_PROMPT_EVAL_MAXIMUM)
        .put("mean", V7B_PROMPT_EVAL_MEAN);
    comparison.put("observedSampleCount", observed.path("sampleCount").asInt());
    JsonNode observedSum = observed.path("sum");
    JsonNode observedMinimum = observed.path("minimum");
    JsonNode observedMaximum = observed.path("maximum");
    JsonNode observedMean = observed.path("mean");
    boolean comparable =
        observed.path("sampleCount").asInt() == V7B_PROMPT_EVAL_SAMPLE_COUNT
            && observedSum.isIntegralNumber()
            && observedMinimum.isIntegralNumber()
            && observedMaximum.isIntegralNumber()
            && observedMean.isNumber();
    if (!comparable) {
      comparison
          .putNull("observedSum")
          .putNull("sumDeltaFromV7b")
          .putNull("minimumDeltaFromV7b")
          .putNull("maximumDeltaFromV7b")
          .putNull("meanDeltaFromV7b")
          .put("outcome", "PROMPT_TOKEN_COMPARISON_UNAVAILABLE");
      return comparison;
    }
    long sum = observedSum.asLong();
    comparison
        .put("observedSum", sum)
        .put("sumDeltaFromV7b", sum - V7B_PROMPT_EVAL_SUM)
        .put("minimumDeltaFromV7b", observedMinimum.asLong() - V7B_PROMPT_EVAL_MINIMUM)
        .put("maximumDeltaFromV7b", observedMaximum.asLong() - V7B_PROMPT_EVAL_MAXIMUM)
        .put("meanDeltaFromV7b", rounded(observedMean.asDouble() - V7B_PROMPT_EVAL_MEAN))
        .put(
            "outcome",
            sum < V7B_PROMPT_EVAL_SUM
                ? "COMPACT_WIRE_PROMPT_TOKEN_REDUCTION_OBSERVED"
                : sum == V7B_PROMPT_EVAL_SUM
                    ? "NO_PROMPT_TOKEN_CHANGE_OBSERVED"
                    : "PROMPT_TOKEN_INCREASE_OBSERVED");
    return comparison;
  }

  private ObjectNode predecessorAggregateReference() {
    ObjectNode reference =
        json.createObjectNode()
            .put("reportVersion", "solo-liquidai-overhead-reduction-diagnostic-v7b")
            .put("reportSha256", V7B_REPORT_SHA256);
    reference.putObject("terminationCounts").put("stop", 0).put("length", 24).put("other", 0);
    reference
        .putObject("promptEvalTokens")
        .put("sampleCount", V7B_PROMPT_EVAL_SAMPLE_COUNT)
        .put("sum", V7B_PROMPT_EVAL_SUM)
        .put("minimum", V7B_PROMPT_EVAL_MINIMUM)
        .put("maximum", V7B_PROMPT_EVAL_MAXIMUM)
        .put("mean", V7B_PROMPT_EVAL_MEAN);
    reference
        .putObject("evalTokens")
        .put("sampleCount", 24)
        .put("sum", 3_072)
        .put("minimum", 128)
        .put("maximum", 128)
        .put("mean", 128.0d);
    reference
        .putObject("modelContentBytes")
        .put("sampleCount", 24)
        .put("minimum", 0)
        .put("maximum", 0)
        .put("mean", 0.0d);
    reference
        .putObject("modelSelectionAttemptWallLatency")
        .put("sampleCount", 24)
        .put("p50Milliseconds", 805.337d)
        .put("p95Milliseconds", 823.686d)
        .put("maximumMilliseconds", 830.989d)
        .put("meanMilliseconds", 806.316d);
    return reference;
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
                        value
                            <= OllamaLocalShadowCompactWireDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
                .count();
    int equal =
        (int)
            values.stream()
                .filter(
                    value ->
                        value
                            == OllamaLocalShadowCompactWireDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
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
            OllamaLocalShadowCompactWireDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
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

  private ObjectNode modelVsFakeAnalyzer(
      List<Long> fakeAnalyzerSamples, List<Long> modelSelectionSamples) {
    require(
        !fakeAnalyzerSamples.isEmpty()
            && fakeAnalyzerSamples.size() == modelSelectionSamples.size(),
        "v8-A same-run latency comparison samples are inconsistent");
    List<Long> sortedFake = fakeAnalyzerSamples.stream().sorted().toList();
    List<Long> sortedModel = modelSelectionSamples.stream().sorted().toList();
    ObjectNode comparison =
        json.createObjectNode()
            .put("comparisonScope", "SAME_CASE_SAME_RUN")
            .put("fakeSampleCount", fakeAnalyzerSamples.size())
            .put("modelSampleCount", modelSelectionSamples.size())
            .put("fakeAnalyzerScope", "DETERMINISTIC_PROPOSAL_GENERATION")
            .put("modelSelectionScope", "OLLAMA_COMPACT_SELECTION_REQUEST");
    comparison.set("p50", latencyPair(percentile(sortedFake, 0.50), percentile(sortedModel, 0.50)));
    comparison.set("p95", latencyPair(percentile(sortedFake, 0.95), percentile(sortedModel, 0.95)));
    comparison.set(
        "mean",
        latencyPair(
            fakeAnalyzerSamples.stream().mapToLong(Long::longValue).average().orElseThrow(),
            modelSelectionSamples.stream().mapToLong(Long::longValue).average().orElseThrow()));
    return comparison;
  }

  private ObjectNode latencyPair(double fakeNanos, double modelNanos) {
    ObjectNode pair =
        json.createObjectNode()
            .put("fakeAnalyzerMilliseconds", milliseconds(fakeNanos))
            .put("modelSelectionMilliseconds", milliseconds(modelNanos))
            .put("modelMinusFakeMilliseconds", milliseconds(modelNanos - fakeNanos));
    if (fakeNanos == 0d) {
      pair.put("ratioStatus", "UNAVAILABLE_ZERO_FAKE_BASELINE");
      pair.putNull("modelToFakeRatio");
    } else {
      pair.put("ratioStatus", "AVAILABLE");
      pair.put("modelToFakeRatio", rounded(modelNanos / fakeNanos));
    }
    return pair;
  }

  private Resources verifyResources() {
    byte[] regression = readResource(REGRESSION_RESOURCE);
    byte[] challenge = readResource(CHALLENGE_RESOURCE);
    byte[] caseSchema = readResource(CASE_SCHEMA_RESOURCE);
    byte[] canonicalSchema = readResource(CANONICAL_SCHEMA_RESOURCE);
    byte[] evidenceSchema = readResource(EVIDENCE_SCHEMA_RESOURCE);
    byte[] selectionSchema = readResource(SELECTION_SCHEMA_RESOURCE);
    byte[] compactWireSchema = readResource(COMPACT_WIRE_SCHEMA_RESOURCE);
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
    verifySha(compactWireSchema, EXPECTED_COMPACT_WIRE_SCHEMA_SHA256, "compact wire schema");
    ObjectNode selectionNode = readObject(selectionSchema);
    ObjectNode compactWireNode = readObject(compactWireSchema);
    return new Resources(
        readNode(regression),
        readNode(challenge),
        loadSchema(readObject(caseSchema)),
        loadSchema(readObject(evidenceSchema)),
        loadSchema(compactWireNode),
        loadSchema(selectionNode),
        compactWireNode.deepCopy(),
        new ResourceIntegrity(
            SoloLiquidAiDeterministicSkillCore.EXPECTED_REGRESSION_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CHALLENGE_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CASE_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_CANONICAL_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_EVIDENCE_SCHEMA_SHA256,
            SoloLiquidAiDeterministicSkillCore.EXPECTED_SELECTION_SCHEMA_SHA256,
            EXPECTED_COMPACT_WIRE_SCHEMA_SHA256));
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
    require(fixtures.size() == EXPECTED_CASE_COUNT, "v8-A fixture count changed");
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
      require(input != null, "required v8-A resource is missing");
      byte[] encoded = input.readNBytes(MAX_RESOURCE_BYTES + 1);
      require(encoded.length <= MAX_RESOURCE_BYTES, "v8-A resource is too large");
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException("Required v8-A resource could not be read.", exception);
    }
  }

  private JsonNode readNode(byte[] encoded) {
    JsonNode value = json.readTree(encoded);
    require(value != null, "required v8-A JSON resource is invalid");
    return value;
  }

  private ObjectNode readObject(byte[] encoded) {
    JsonNode value = readNode(encoded);
    require(value.isObject(), "required v8-A object resource is invalid");
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
    require(parent != null, "v8-A report parent is missing");
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
    require(!value.initiallyLoaded(), "v8-A requires a clean unloaded-model prestate");
    require(value.initiallyLoadedModelCount() == 0, "v8-A requires zero initially loaded models");
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
      throw new IllegalStateException("v8-A output cleanup failed.", exception);
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
      Schema compactWireSchema,
      Schema selectionSchema,
      ObjectNode compactWireSchemaNode,
      ResourceIntegrity integrity) {
    Resources {
      Objects.requireNonNull(compactWireSchemaNode, "compactWireSchemaNode");
      compactWireSchemaNode = compactWireSchemaNode.deepCopy();
    }

    @Override
    public ObjectNode compactWireSchemaNode() {
      return compactWireSchemaNode.deepCopy();
    }
  }

  private record ResourceIntegrity(
      String regressionSha256,
      String challengeSha256,
      String caseSchemaSha256,
      String canonicalSchemaSha256,
      String evidenceSchemaSha256,
      String selectionSchemaSha256,
      String compactWireSchemaSha256) {}

  private record DiagnosticRun(
      List<Long> fakeAnalyzerLatencyNanos,
      List<Long> selectorLatencyNanos,
      List<OllamaApiMetrics> apiMetrics,
      List<Integer> outputBytes,
      Map<DiagnosticTermination, Integer> terminationCounts,
      Map<String, Integer> rejectionCounts,
      int requestCount,
      int wireResponseCount,
      int completedStopCount,
      int compactWireObjectParsedCount,
      int compactWireSchemaValidCount,
      int compactWireMappedCount,
      int mappedFullSelectionSchemaValidCount,
      int mappedSelectionDomainValidCount,
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
        throw new IllegalArgumentException(name + " must contain the exact v8-A opt-in value.");
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
class SoloLiquidAiCompactWireDiagnosticRunner extends SoloLiquidAiCompactWireDiagnosticCore {
  SoloLiquidAiCompactWireDiagnosticRunner() {
    super();
  }
}
