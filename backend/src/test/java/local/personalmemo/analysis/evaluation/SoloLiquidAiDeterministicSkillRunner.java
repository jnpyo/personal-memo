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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit, aggregate-only v6 public-synthetic guarded-selection runner. */
class SoloLiquidAiDeterministicSkillCore {
  static final String OPT_IN_ENV = "PERSONAL_MEMO_SOLO_LIQUIDAI_SKILL_SHADOW";
  static final String OPT_IN_VALUE = "SOLO_PROVISIONAL_REPORT_ONLY";
  static final String MODEL_ENV = "PERSONAL_MEMO_SOLO_OLLAMA_MODEL";
  static final String DIGEST_ENV = "PERSONAL_MEMO_SOLO_OLLAMA_DIGEST";
  static final String BASE_HEAD_ENV = "PERSONAL_MEMO_SOLO_BASE_HEAD";
  static final String SOURCE_BUNDLE_ENV = "PERSONAL_MEMO_SOLO_SOURCE_BUNDLE_SHA256";
  static final String GPU_NAME_ENV = "PERSONAL_MEMO_SOLO_GPU_NAME";
  static final String GPU_DRIVER_ENV = "PERSONAL_MEMO_SOLO_GPU_DRIVER";
  static final String GPU_TOTAL_ENV = "PERSONAL_MEMO_SOLO_GPU_TOTAL_MIB";
  static final String GPU_BASELINE_USED_ENV = "PERSONAL_MEMO_SOLO_GPU_BASELINE_USED_MIB";

  static final String EXPECTED_MODEL = "hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0";
  static final String EXPECTED_DIGEST =
      "677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822";
  static final String EXPECTED_REGRESSION_SHA256 =
      "1fb50ef1591659582ea779378d8d699d33d1c98a0522baff92d6cd506c35c524";
  static final String EXPECTED_CHALLENGE_SHA256 =
      "cf43ac1f79eea7e5b88f0a0f5623e82a30b468a25024976de8fbb552ed7c1fba";
  static final String EXPECTED_CASE_SCHEMA_SHA256 =
      "029189fec1e3d8f31c52783bcf444a41be6048724627b093d3bd42732c45f2a4";
  static final String EXPECTED_CANONICAL_SCHEMA_SHA256 =
      "13aac5622442ed6f7ce5ca57541cdbc015bd6abba3d3e50e57d1b83bb84cbab0";
  static final String EXPECTED_EVIDENCE_SCHEMA_SHA256 =
      "4f205cee082b2ab6da8eb294b234c1d98f959cd67f3a567dffb5516093654ae0";
  static final String EXPECTED_SELECTION_SCHEMA_SHA256 =
      "570684f4c721cb98aed79e0b2cdfb4d59f5aefc8a451b6acd340dc7d75330773";

  private static final String REPORT_VERSION = "solo-liquidai-deterministic-skill-v6";
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
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("target", "evaluation", "solo-liquidai-deterministic-skill-v6.json");
  private static final int EXPECTED_SPLIT_SIZE = 12;
  private static final int EXPECTED_CASE_COUNT = 24;
  private static final int MEMO_REVISION = 1;
  private static final int MAX_RESOURCE_BYTES = 512 * 1024;
  private static final int MAX_REPORT_BYTES = 512 * 1024;
  private static final String MEMO_ID_PREFIX = "personal-memo:liquidai-skill-v6:";
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
          "semanticOutput",
          "rawModelOutput",
          "prompt",
          "skillEvidence",
          "defaultTitle",
          "objectValue",
          "primaryItemOrdinal",
          "topicObjectOrdinals",
          "value",
          "surfaceText");
  private static final Set<String> AGGREGATE_METRIC_FIELD_NAMES =
      Set.of("title", "action", "object", "sourceSpan", "suggestedTitle");
  private static final Pattern GPU_TEXT = Pattern.compile("[A-Za-z0-9 ._()/-]{1,120}");
  private static final Pattern DRIVER_TEXT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final ObjectMapper json;
  private final ShadowConfiguration configuration;
  private final OllamaSkillShadowApi ollama;
  private final Path reportPath;
  private final FakeAnalyzer fakeAnalyzer;
  private final DeterministicAmbiguityGate ambiguityGate = new DeterministicAmbiguityGate();
  private final ShadowDeterministicSkill skill;

  SoloLiquidAiDeterministicSkillCore() {
    this.json = new ObjectMapper();
    this.configuration = ShadowConfiguration.from(System.getenv());
    this.ollama =
        new OllamaLocalShadowSkillClient(json, configuration.model(), configuration.modelDigest());
    this.reportPath = DEFAULT_REPORT_PATH;
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  SoloLiquidAiDeterministicSkillCore(
      Map<String, String> environment, OllamaSkillShadowApi ollama, Path reportPath) {
    this.json = new ObjectMapper();
    this.configuration = ShadowConfiguration.from(environment);
    this.ollama = Objects.requireNonNull(ollama, "ollama");
    this.reportPath = Objects.requireNonNull(reportPath, "reportPath");
    this.fakeAnalyzer = new FakeAnalyzer(json);
    this.skill = new ShadowDeterministicSkill(json);
  }

  @Test
  void writeAggregateOnlyGuardedSkillReport() throws Exception {
    execute();
  }

  ObjectNode execute() throws Exception {
    Path temporaryPath = temporaryPath();
    deleteOutput(reportPath);
    deleteOutput(temporaryPath);

    Resources resources = verifyResources();
    List<ShadowFixture> fixtures = loadFixtures(resources);
    OllamaModelPreflight preflight = ollama.preflight();
    verifyPreflight(preflight);

    OllamaWarmupResult warmup = null;
    OllamaObservedAllocation warmAllocation = OllamaObservedAllocation.notLoaded();
    OllamaObservedAllocation finalAllocation = OllamaObservedAllocation.notLoaded();
    EvaluationRun evaluation = null;
    RuntimeException executionFailure = null;
    CleanupResult cleanup;
    try {
      warmup = ollama.warmup();
      warmAllocation = ollama.allocation();
      evaluation =
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
    require(warmup != null && evaluation != null, "v6 execution did not complete");

    OllamaObservedAllocation observed = maximumAllocation(warmAllocation, finalAllocation);
    ObjectNode report =
        buildReport(preflight, warmup, observed, cleanup, evaluation, resources.integrity());
    byte[] encoded =
        (json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    require(encoded.length <= MAX_REPORT_BYTES, "v6 report exceeds 512 KiB");
    assertAggregateOnly(report, encoded, fixtures);
    publishAtomically(temporaryPath, encoded);
    require(!Files.exists(temporaryPath), "v6 temporary report remains");
    return report;
  }

  private EvaluationRun evaluate(
      List<ShadowFixture> fixtures,
      Schema evidenceSchema,
      Schema selectionSchema,
      ObjectNode selectionSchemaNode) {
    EvaluationV2Evaluator evaluator =
        new EvaluationV2Evaluator(json, fakeAnalyzer.provenance(), ambiguityGate.version());
    List<CaseEvaluation> fakeCases = new ArrayList<>();
    List<CaseEvaluation> skillCases = new ArrayList<>();
    List<CaseEvaluation> guardedCases = new ArrayList<>();
    List<Long> fakeLatency = new ArrayList<>();
    List<Long> skillLatency = new ArrayList<>();
    List<Long> modelAttemptLatency = new ArrayList<>();
    List<Long> guardedLatency = new ArrayList<>();
    List<Long> totalLatency = new ArrayList<>();
    List<OllamaApiMetrics> apiMetrics = new ArrayList<>();
    List<Integer> outputBytes = new ArrayList<>();
    Map<String, Integer> rejectionReasons = new TreeMap<>();
    int requestCount = 0;
    int responseCount = 0;
    int selectionSchemaValidCount = 0;
    int selectionDomainValidCount = 0;
    int acceptedCount = 0;
    int fallbackCount = 0;
    int primaryTitleChangedCount = 0;
    int diagnosticTopicOrdinalCount = 0;
    int protectedMutationCount = 0;
    int fakeSkillMismatchCount = 0;
    int improvedTitleCaseCount = 0;
    int regressedTitleCaseCount = 0;
    int unchangedTitleCaseCount = 0;

    for (ShadowFixture fixture : fixtures) {
      long totalStarted = System.nanoTime();
      String memoText = fixture.node().path("content").asText();
      Instant baseInstant = Instant.parse(fixture.node().path("baseInstant").asText());
      String timeZone = fixture.node().path("timeZone").asText();
      UUID memoId = deterministicMemoId(fixture.split(), fixture.ordinal());

      long fakeStarted = System.nanoTime();
      ObjectNode fakeProposal =
          fakeAnalyzer.analyze(memoId, MEMO_REVISION, memoText, baseInstant, timeZone);
      fakeLatency.add(System.nanoTime() - fakeStarted);

      long skillStarted = System.nanoTime();
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
      if (!fakeProposal.equals(skillProposal)) {
        fakeSkillMismatchCount++;
      }
      skillLatency.add(System.nanoTime() - skillStarted);

      requestCount++;
      SkillSelection acceptedSelection = null;
      long modelStarted = System.nanoTime();
      try {
        OllamaSkillSelectionResult response =
            ollama.select(projection.evidenceJson(), selectionSchemaNode.deepCopy());
        responseCount++;
        apiMetrics.add(response.metrics());
        outputBytes.add(response.modelOutputBytes());
        ObjectNode selectionOutput = response.selectionOutput();
        if (!selectionSchema.validate(selectionOutput).isEmpty()) {
          increment(rejectionReasons, "SELECTION_SCHEMA_INVALID");
        } else {
          selectionSchemaValidCount++;
          try {
            acceptedSelection = skill.validateSelection(selectionOutput, projection.evidence());
            selectionDomainValidCount++;
            acceptedCount++;
          } catch (SkillSelectionRejectedException exception) {
            increment(rejectionReasons, "SELECTION_DOMAIN_" + exception.reason().name());
          }
        }
      } catch (OllamaShadowException exception) {
        if (isStructuralFailure(exception.failure())) {
          throw exception;
        }
        increment(rejectionReasons, "MODEL_" + exception.failure().name());
      } catch (RuntimeException exception) {
        increment(rejectionReasons, "MODEL_UNEXPECTED_REJECTION");
      } finally {
        modelAttemptLatency.add(System.nanoTime() - modelStarted);
      }

      long guardedStarted = System.nanoTime();
      ObjectNode guardedProposal;
      if (acceptedSelection == null) {
        fallbackCount++;
        guardedProposal = skill.fallbackProposal(projection);
      } else {
        guardedProposal = skill.guardedProposal(projection, acceptedSelection);
        diagnosticTopicOrdinalCount += acceptedSelection.topicObjectOrdinals().size();
        if (!guardedProposal
            .at("/suggestedTitle/value")
            .equals(skillProposal.at("/suggestedTitle/value"))) {
          primaryTitleChangedCount++;
        }
      }
      try {
        ShadowDeterministicSkill.assertOnlySuggestedTitleValueChanged(
            skillProposal, guardedProposal);
      } catch (RuntimeException exception) {
        protectedMutationCount++;
        throw exception;
      }

      CaseEvaluation fakeCase =
          evaluator.evaluate(fixture.node(), fakeProposal, memoId, MEMO_REVISION, memoText);
      CaseEvaluation skillCase =
          evaluator.evaluate(fixture.node(), skillProposal, memoId, MEMO_REVISION, memoText);
      CaseEvaluation guardedCase =
          evaluator.evaluate(fixture.node(), guardedProposal, memoId, MEMO_REVISION, memoText);
      fakeCases.add(fakeCase);
      skillCases.add(skillCase);
      guardedCases.add(guardedCase);
      boolean skillTitleCorrect = titleCorrect(skillCase.items().suggestedTitle());
      boolean guardedTitleCorrect = titleCorrect(guardedCase.items().suggestedTitle());
      if (guardedTitleCorrect && !skillTitleCorrect) {
        improvedTitleCaseCount++;
      } else if (skillTitleCorrect && !guardedTitleCorrect) {
        regressedTitleCaseCount++;
      } else {
        unchangedTitleCaseCount++;
      }
      guardedLatency.add(System.nanoTime() - guardedStarted);
      totalLatency.add(System.nanoTime() - totalStarted);
    }

    require(requestCount == EXPECTED_CASE_COUNT, "v6 did not issue exactly 24 selections");
    require(
        acceptedCount + fallbackCount == EXPECTED_CASE_COUNT,
        "v6 selection/fallback accounting is inconsistent");
    return new EvaluationRun(
        List.copyOf(fakeCases),
        List.copyOf(skillCases),
        List.copyOf(guardedCases),
        List.copyOf(fakeLatency),
        List.copyOf(skillLatency),
        List.copyOf(modelAttemptLatency),
        List.copyOf(guardedLatency),
        List.copyOf(totalLatency),
        List.copyOf(apiMetrics),
        List.copyOf(outputBytes),
        Map.copyOf(rejectionReasons),
        requestCount,
        responseCount,
        selectionSchemaValidCount,
        selectionDomainValidCount,
        acceptedCount,
        requestCount - acceptedCount,
        fallbackCount,
        primaryTitleChangedCount,
        diagnosticTopicOrdinalCount,
        protectedMutationCount,
        fakeSkillMismatchCount,
        improvedTitleCaseCount,
        regressedTitleCaseCount,
        unchangedTitleCaseCount);
  }

  private ObjectNode buildReport(
      OllamaModelPreflight preflight,
      OllamaWarmupResult warmup,
      OllamaObservedAllocation allocation,
      CleanupResult cleanup,
      EvaluationRun run,
      ResourceIntegrity integrity) {
    AggregateEvaluation fakeAll = EvaluationV2Metrics.aggregate(run.fakeCases());
    AggregateEvaluation skillAll = EvaluationV2Metrics.aggregate(run.skillCases());
    AggregateEvaluation guardedAll = EvaluationV2Metrics.aggregate(run.guardedCases());
    boolean guardedNonDegradation = guardedNonDegradation(run);
    double fakeP95Milliseconds = p95Milliseconds(run.fakeLatencyNanos());
    double skillP95Milliseconds = p95Milliseconds(run.skillLatencyNanos());
    double skillP95ThresholdMilliseconds = Math.max(50d, fakeP95Milliseconds * 2d);
    boolean skillLatencyPassed = skillP95Milliseconds <= skillP95ThresholdMilliseconds;
    double selectorP95Milliseconds = p95Milliseconds(run.modelAttemptLatencyNanos());
    boolean selectorLatencyPassed = selectorP95Milliseconds < 1_000d;
    boolean combinedSafetyGatePassed =
        guardedAll.regressionHardGatePassed() && guardedAll.semanticFalseConfidentLocalCount() == 0;
    boolean boundaryMet =
        guardedBoundaryMet(
            guardedAll,
            run.protectedMutationCount(),
            run.fakeSkillMismatchCount(),
            cleanup.restored(),
            skillLatencyPassed);
    boolean contributionMet =
        modelContributionMet(
            run.requestCount(),
            run.responseCount(),
            run.selectionSchemaValidCount(),
            run.selectionDomainValidCount(),
            run.acceptedCount(),
            run.rejectedCount(),
            run.fallbackCount(),
            guardedNonDegradation,
            run.protectedMutationCount(),
            run.improvedTitleCaseCount(),
            run.regressedTitleCaseCount(),
            selectorLatencyPassed);
    boolean overallMet = boundaryMet && contributionMet;
    require(
        !boundaryMet || combinedSafetyGatePassed,
        "guarded boundary status is inconsistent with the combined safety gate");

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
        .put("modelSelectionResponseCount", run.responseCount())
        .put("modelSelectionSchemaValidCount", run.selectionSchemaValidCount())
        .put("modelSelectionDomainValidCount", run.selectionDomainValidCount())
        .put("modelSelectionAcceptedCount", run.acceptedCount())
        .put("modelSelectionRejectedCount", run.rejectedCount())
        .put("skillFallbackCount", run.fallbackCount())
        .put("primaryTitleChangedCount", run.primaryTitleChangedCount())
        .put("improvedTitleCaseCount", run.improvedTitleCaseCount())
        .put("regressedTitleCaseCount", run.regressedTitleCaseCount())
        .put("unchangedTitleCaseCount", run.unchangedTitleCaseCount())
        .put("diagnosticTopicOrdinalSelectionCount", run.diagnosticTopicOrdinalCount())
        .put("retryCount", 0)
        .put("toolCallCount", 0);
    ObjectNode rejection = execution.putObject("selectionRejectionCounts");
    rejection.put("total", run.rejectedCount());
    ObjectNode rejectionByReason = rejection.putObject("byReason");
    run.rejectionReasons().forEach(rejectionByReason::put);

    ObjectNode attribution = report.putObject("attribution");
    attribution
        .put("authoritativeProposalProducer", "FAKE_ANALYZER_DETERMINISTIC")
        .put("skillRole", "VALIDATED_PROJECTION_ONLY")
        .put("modelRole", "OPTIONAL_EXISTING_TITLE_ORDINAL_SELECTION_ONLY")
        .put("topicOrdinalsMutateProposalFields", false)
        .put("invalidTopicOrdinalsRejectWholeEnvelope", true)
        .put(
            "topicOrdinalControlFlowPolicy",
            "DO_NOT_MUTATE_PROPOSAL_FIELDS_BUT_CAN_REJECT_MODEL_ENVELOPE")
        .put("fallbackValidityAttributedToLiquidAi", false)
        .put("guardedValidityAttribution", "FAKE_ANALYZER_AND_DETERMINISTIC_SKILL_BOUNDARY");

    ObjectNode invariant = report.putObject("protectedProposalInvariant");
    invariant
        .put("onlyMutablePointer", "/suggestedTitle/value")
        .put("fakeToSkillDeepMismatchCaseCount", run.fakeSkillMismatchCount())
        .put("modelProtectedMutationCaseCount", run.protectedMutationCount())
        .put("topicOrdinalsDiagnosticOnly", true)
        .put("invalidTopicOrdinalsTriggerFullFallback", true)
        .put("passed", run.fakeSkillMismatchCount() == 0 && run.protectedMutationCount() == 0);

    ObjectNode quality = report.putObject("quality");
    quality.set("fake", quality(run.fakeCases()));
    quality.set("skillOnly", quality(run.skillCases()));
    quality.set("liquidAiGuardedBySkill", quality(run.guardedCases()));
    quality.put("guardedNonDegradation", guardedNonDegradation);

    ObjectNode performance = report.putObject("performance");
    performance.set("fakeAnalyzerWallLatency", latency(run.fakeLatencyNanos()));
    performance.set("skillProjectionWallLatency", latency(run.skillLatencyNanos()));
    performance.set("modelSelectionAttemptWallLatency", latency(run.modelAttemptLatencyNanos()));
    performance.set(
        "guardedCompositionAndEvaluationWallLatency", latency(run.guardedLatencyNanos()));
    performance.set("endToEndWallLatency", latency(run.totalLatencyNanos()));
    performance.set("modelOutputBytes", integerDistribution(run.modelOutputBytes()));
    performance.set("ollamaApi", apiMetrics(run.apiMetrics()));
    performance
        .putObject("latencyGates")
        .put("skillP95ThresholdMilliseconds", rounded(skillP95ThresholdMilliseconds))
        .put("skillObservedP95Milliseconds", rounded(skillP95Milliseconds))
        .put("skillPassed", skillLatencyPassed)
        .put("selectorP95ThresholdMilliseconds", 1_000)
        .put("selectorThresholdComparison", "STRICTLY_LESS_THAN")
        .put("selectorObservedP95Milliseconds", rounded(selectorP95Milliseconds))
        .put("selectorPassed", selectorLatencyPassed);
    performance
        .putObject("configuredCaps")
        .put("numContext", OllamaLocalShadowSkillClient.NUM_CONTEXT)
        .put("numPredict", OllamaLocalShadowSkillClient.NUM_PREDICT)
        .put("modelOutputBytes", OllamaLocalShadowSkillClient.MAX_MODEL_OUTPUT_BYTES)
        .put("retryCount", 0);

    ObjectNode hardware = report.putObject("hardware");
    hardware
        .putObject("gpu")
        .put("name", configuration.gpuName())
        .put("driver", configuration.gpuDriver())
        .put("totalMiB", configuration.gpuTotalMiB())
        .put("baselineUsedMiB", configuration.gpuBaselineUsedMiB())
        .put("measurementScope", "DEVICE_WIDE_NON_EXCLUSIVE")
        .put("peakUsedStatus", "NOT_AVAILABLE")
        .putNull("peakUsedMiB");
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
        .putObject("guardedSystemAcceptance")
        .put("status", boundaryMet ? "MET" : "NOT_MET")
        .put("fallbackMayContributeToValidity", true)
        .put("liquidAiCreditForFallback", false)
        .put("protectedBoundaryPassed", run.protectedMutationCount() == 0)
        .put("fakeSkillProjectionPassed", run.fakeSkillMismatchCount() == 0)
        .put("skillLatencyPassed", skillLatencyPassed)
        .put(
            "semanticFalseConfidentLocalPassed", guardedAll.semanticFalseConfidentLocalCount() == 0)
        .put("safetyGatePassed", combinedSafetyGatePassed);
    report
        .putObject("modelContributionAcceptance")
        .put("status", contributionMet ? "MET" : "NOT_MET")
        .put("requiresZeroSelectionFailures", true)
        .put("requiresZeroFallbacks", true)
        .put("requiresIncrementalTitleImprovement", true)
        .put("actualImprovedTitleCaseCount", run.improvedTitleCaseCount())
        .put("actualRegressedTitleCaseCount", run.regressedTitleCaseCount())
        .put("selectorLatencyPassed", selectorLatencyPassed)
        .put("guardedNonDegradation", guardedNonDegradation)
        .put("baseValidityAttributedToModel", false);
    report
        .putObject("developmentAcceptance")
        .put("status", overallMet ? "MET" : "NOT_MET")
        .put("requiresGuardedSystemAndModelContribution", true)
        .put("blindProviderPassClaimed", false);
    report
        .putObject("training")
        .put("decision", "NO_GO_FOR_TRAINING")
        .put("fineTuningPerformed", false)
        .put("loraDecision", "NO_GO")
        .put("loraPerformed", false);
    return report;
  }

  private ObjectNode quality(List<CaseEvaluation> cases) {
    ObjectNode value = json.createObjectNode();
    value.set(
        "regression",
        EvaluationV2Metrics.aggregate(cases.stream().filter(c -> c.isSplit("REGRESSION")).toList())
            .toJson(json));
    value.set(
        "visibleChallenge",
        EvaluationV2Metrics.aggregate(
                cases.stream().filter(c -> c.isSplit("VISIBLE_CHALLENGE")).toList())
            .toJson(json));
    value.set("all", EvaluationV2Metrics.aggregate(cases).toJson(json));
    return value;
  }

  static boolean guardedBoundaryMet(
      AggregateEvaluation guarded,
      int protectedMutationCount,
      int fakeSkillMismatchCount,
      boolean restored,
      boolean skillLatencyPassed) {
    return guarded.caseCount() == EXPECTED_CASE_COUNT
        && guarded.schemaValidCount() == EXPECTED_CASE_COUNT
        && guarded.domainValidCount() == EXPECTED_CASE_COUNT
        && guarded.legacyWrongLocalCount() == 0
        && guarded.inventedPreciseDateCaseCount() == 0
        && guarded.localOverflowCount() == 0
        && guarded.missingOverflowSignalCount() == 0
        && guarded.unresolvedFieldHallucinationCount() == 0
        && guarded.semanticFalseConfidentLocalCount() == 0
        && protectedMutationCount == 0
        && fakeSkillMismatchCount == 0
        && restored
        && skillLatencyPassed;
  }

  static boolean modelContributionMet(
      int requestCount,
      int responseCount,
      int schemaValidCount,
      int domainValidCount,
      int acceptedCount,
      int rejectedCount,
      int fallbackCount,
      boolean nonDegradation,
      int protectedMutationCount,
      int improvedTitleCaseCount,
      int regressedTitleCaseCount,
      boolean selectorLatencyPassed) {
    return requestCount == EXPECTED_CASE_COUNT
        && responseCount == EXPECTED_CASE_COUNT
        && schemaValidCount == EXPECTED_CASE_COUNT
        && domainValidCount == EXPECTED_CASE_COUNT
        && acceptedCount == EXPECTED_CASE_COUNT
        && rejectedCount == 0
        && fallbackCount == 0
        && nonDegradation
        && protectedMutationCount == 0
        && improvedTitleCaseCount > 0
        && regressedTitleCaseCount == 0
        && selectorLatencyPassed;
  }

  private boolean titleCorrect(EvaluationFieldCounts counts) {
    return counts.truePositive() > 0 && counts.falsePositive() == 0 && counts.falseNegative() == 0;
  }

  private boolean guardedNonDegradation(EvaluationRun run) {
    return run.regressedTitleCaseCount() == 0
        && splitNonDegradation(run.skillCases(), run.guardedCases(), "REGRESSION")
        && splitNonDegradation(run.skillCases(), run.guardedCases(), "VISIBLE_CHALLENGE");
  }

  private boolean splitNonDegradation(
      List<CaseEvaluation> skillCases, List<CaseEvaluation> guardedCases, String split) {
    AggregateEvaluation skill =
        EvaluationV2Metrics.aggregate(
            skillCases.stream().filter(value -> value.isSplit(split)).toList());
    AggregateEvaluation guarded =
        EvaluationV2Metrics.aggregate(
            guardedCases.stream().filter(value -> value.isSplit(split)).toList());
    return titleAndSafetyNonDegrading(
        skill.suggestedTitle(),
        guarded.suggestedTitle(),
        skill.itemCompleteSetExactCount(),
        guarded.itemCompleteSetExactCount(),
        guarded.semanticFalseConfidentLocalCount(),
        0);
  }

  static boolean titleAndSafetyNonDegrading(
      EvaluationFieldCounts skillTitle,
      EvaluationFieldCounts guardedTitle,
      int skillCompleteSetCount,
      int guardedCompleteSetCount,
      int guardedSemanticFalseConfidentLocalCount,
      int regressedTitleCaseCount) {
    return guardedTitle.truePositive() >= skillTitle.truePositive()
        && guardedTitle.falsePositive() <= skillTitle.falsePositive()
        && guardedTitle.falseNegative() <= skillTitle.falseNegative()
        && guardedCompleteSetCount >= skillCompleteSetCount
        && guardedSemanticFalseConfidentLocalCount == 0
        && regressedTitleCaseCount == 0;
  }

  private ObjectNode latency(List<Long> samples) {
    ObjectNode value = json.createObjectNode().put("sampleCount", samples.size());
    if (samples.isEmpty()) {
      return value.putNull("p50Milliseconds").putNull("p95Milliseconds").putNull("maxMilliseconds");
    }
    List<Long> sorted = samples.stream().sorted().toList();
    long sum = samples.stream().mapToLong(Long::longValue).sum();
    return value
        .put("p50Milliseconds", milliseconds(percentile(sorted, 0.50)))
        .put("p95Milliseconds", milliseconds(percentile(sorted, 0.95)))
        .put("maxMilliseconds", milliseconds(sorted.getLast()))
        .put("meanMilliseconds", milliseconds((double) sum / samples.size()));
  }

  private ObjectNode integerDistribution(List<Integer> samples) {
    ObjectNode value = json.createObjectNode().put("sampleCount", samples.size());
    if (samples.isEmpty()) {
      return value.putNull("minimum").putNull("maximum").putNull("mean");
    }
    int minimum = samples.stream().mapToInt(Integer::intValue).min().orElseThrow();
    int maximum = samples.stream().mapToInt(Integer::intValue).max().orElseThrow();
    double mean = samples.stream().mapToInt(Integer::intValue).average().orElseThrow();
    return value.put("minimum", minimum).put("maximum", maximum).put("mean", rounded(mean));
  }

  private ObjectNode apiMetrics(List<OllamaApiMetrics> metrics) {
    ObjectNode value = json.createObjectNode().put("sampleCount", metrics.size());
    putLongSum(
        value,
        "promptTokenCount",
        metrics.stream().map(OllamaApiMetrics::promptEvalCount).toList());
    putLongSum(
        value, "generatedTokenCount", metrics.stream().map(OllamaApiMetrics::evalCount).toList());
    putLongSum(
        value,
        "totalDurationNanos",
        metrics.stream().map(OllamaApiMetrics::totalDurationNanos).toList());
    return value;
  }

  private void putLongSum(ObjectNode value, String field, List<Long> samples) {
    List<Long> present = samples.stream().filter(Objects::nonNull).toList();
    value.put(field + "SampleCount", present.size());
    if (present.isEmpty()) {
      value.putNull(field);
    } else {
      value.put(field, present.stream().mapToLong(Long::longValue).sum());
    }
  }

  private Resources verifyResources() {
    byte[] regression = readResource(REGRESSION_RESOURCE);
    byte[] challenge = readResource(CHALLENGE_RESOURCE);
    byte[] caseSchema = readResource(CASE_SCHEMA_RESOURCE);
    byte[] canonicalSchema = readResource(CANONICAL_SCHEMA_RESOURCE);
    byte[] evidenceSchema = readResource(EVIDENCE_SCHEMA_RESOURCE);
    byte[] selectionSchema = readResource(SELECTION_SCHEMA_RESOURCE);
    verifySha(regression, EXPECTED_REGRESSION_SHA256, "regression fixture");
    verifySha(challenge, EXPECTED_CHALLENGE_SHA256, "visible challenge fixture");
    verifySha(caseSchema, EXPECTED_CASE_SCHEMA_SHA256, "evaluation case schema");
    verifySha(canonicalSchema, EXPECTED_CANONICAL_SCHEMA_SHA256, "canonical proposal schema");
    verifySha(evidenceSchema, EXPECTED_EVIDENCE_SCHEMA_SHA256, "skill evidence schema");
    verifySha(selectionSchema, EXPECTED_SELECTION_SCHEMA_SHA256, "model selection schema");
    ObjectNode evidenceNode = readObject(evidenceSchema);
    ObjectNode selectionNode = readObject(selectionSchema);
    verifyRepositoryCanonicalCopy(canonicalSchema);
    return new Resources(
        readNode(regression),
        readNode(challenge),
        loadSchema(readObject(caseSchema)),
        loadSchema(evidenceNode),
        loadSchema(selectionNode),
        selectionNode.deepCopy(),
        new ResourceIntegrity(
            EXPECTED_REGRESSION_SHA256,
            EXPECTED_CHALLENGE_SHA256,
            EXPECTED_CASE_SCHEMA_SHA256,
            EXPECTED_CANONICAL_SCHEMA_SHA256,
            EXPECTED_EVIDENCE_SCHEMA_SHA256,
            EXPECTED_SELECTION_SCHEMA_SHA256));
  }

  private List<ShadowFixture> loadFixtures(Resources resources) {
    require(resources.regression().size() == EXPECTED_SPLIT_SIZE, "regression count changed");
    require(resources.challenge().size() == EXPECTED_SPLIT_SIZE, "challenge count changed");
    List<ShadowFixture> fixtures = new ArrayList<>();
    addFixtures(fixtures, resources.regression(), "REGRESSION", resources.caseSchema());
    addFixtures(fixtures, resources.challenge(), "VISIBLE_CHALLENGE", resources.caseSchema());
    Set<String> ids = new HashSet<>();
    Set<String> memoTexts = new HashSet<>();
    for (ShadowFixture fixture : fixtures) {
      require(ids.add(fixture.node().path("id").asText()), "fixture IDs are not unique");
      require(
          memoTexts.add(fixture.node().path("content").asText()), "fixture memos are not unique");
    }
    require(fixtures.size() == EXPECTED_CASE_COUNT, "v6 fixture count changed");
    return List.copyOf(fixtures);
  }

  private void addFixtures(
      List<ShadowFixture> target, JsonNode values, String split, Schema schema) {
    for (int ordinal = 0; ordinal < values.size(); ordinal++) {
      JsonNode fixture = values.get(ordinal);
      require(schema.validate(fixture).isEmpty(), "fixture schema validation failed");
      require("2".equals(fixture.path("datasetVersion").asText()), "dataset version changed");
      require(split.equals(fixture.path("split").asText()), "fixture split changed");
      EvaluationV2GoldIntegrity.validate(fixture);
      target.add(new ShadowFixture(fixture, split, ordinal));
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
      require(input != null, "required v6 resource is missing");
      byte[] encoded = input.readNBytes(MAX_RESOURCE_BYTES + 1);
      require(encoded.length <= MAX_RESOURCE_BYTES, "v6 resource is too large");
      return encoded;
    } catch (IOException exception) {
      throw new IllegalStateException("Required v6 resource could not be read.", exception);
    }
  }

  private JsonNode readNode(byte[] encoded) {
    JsonNode value = json.readTree(encoded);
    require(value != null, "required v6 JSON resource is invalid");
    return value;
  }

  private ObjectNode readObject(byte[] encoded) {
    JsonNode value = readNode(encoded);
    require(value.isObject(), "required v6 object resource is invalid");
    return (ObjectNode) value;
  }

  private void verifySha(byte[] encoded, String expected, String label) {
    require(expected.equals(sha256(encoded)), "Pinned SHA-256 changed for " + label + ".");
  }

  static String sha256(byte[] encoded) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private void assertAggregateOnly(
      ObjectNode report, byte[] encoded, List<ShadowFixture> fixtures) {
    assertNoForbiddenReportFields(report);
    String serialized = new String(encoded, StandardCharsets.UTF_8);
    for (ShadowFixture fixture : fixtures) {
      require(
          !serialized.contains(fixture.node().path("id").asText()), "report contains a case ID");
      require(
          !serialized.contains(fixture.node().path("content").asText()),
          "report contains memo text");
      JsonNode notes = fixture.node().path("notes");
      if (notes.isTextual() && !notes.asText().isBlank()) {
        require(!serialized.contains(notes.asText()), "report contains fixture notes");
      }
      for (JsonNode mention : fixture.node().at("/expectedDates/mentions")) {
        require(
            !serialized.contains(mention.path("surfaceText").asText()),
            "report contains expected date text");
      }
      Set<String> textualLeaves = new HashSet<>();
      collectTextualLeaves(fixture.node(), textualLeaves);
      for (String leaf : textualLeaves) {
        if (!leaf.isBlank() && leaf.codePointCount(0, leaf.length()) >= 8) {
          require(!serialized.contains(leaf), "report contains a fixture textual leaf");
        }
      }
    }
  }

  void assertNoForbiddenReportFields(ObjectNode report) {
    for (String forbidden : FORBIDDEN_REPORT_FIELDS) {
      require(report.findValue(forbidden) == null, "report contains a forbidden field");
    }
    assertPathAwareAggregateMetricFields(report, "");
  }

  private void assertPathAwareAggregateMetricFields(JsonNode value, String pointer) {
    if (value.isArray()) {
      for (int index = 0; index < value.size(); index++) {
        assertPathAwareAggregateMetricFields(value.get(index), pointer + "/" + index);
      }
      return;
    }
    if (!value.isObject()) {
      return;
    }
    for (var property : value.properties()) {
      String childPointer = pointer + "/" + escapePointerToken(property.getKey());
      if (AGGREGATE_METRIC_FIELD_NAMES.contains(property.getKey())) {
        require(
            isAllowedAggregateMetricPointer(childPointer),
            "report contains a metric-named field outside its aggregate path");
        require(
            containsOnlyAggregateMetricValues(property.getValue()),
            "report contains a non-aggregate value under a metric field");
      }
      assertPathAwareAggregateMetricFields(property.getValue(), childPointer);
    }
  }

  private boolean isAllowedAggregateMetricPointer(String pointer) {
    return pointer.matches(
        "/quality/(?:fake|skillOnly|liquidAiGuardedBySkill)/"
            + "(?:regression|visibleChallenge|all)/items/"
            + "(?:title|action|object|sourceSpan|suggestedTitle)");
  }

  private String escapePointerToken(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private boolean containsOnlyAggregateMetricValues(JsonNode value) {
    if (value.isNumber() || value.isNull()) {
      return true;
    }
    if (!value.isObject()) {
      return false;
    }
    for (var property : value.properties()) {
      if (!containsOnlyAggregateMetricValues(property.getValue())) {
        return false;
      }
    }
    return true;
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

  private void verifyRepositoryCanonicalCopy(byte[] classpathBytes) {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; depth < 3 && candidate != null; depth++) {
      Path repositoryCopy = candidate.resolve("contracts/analysis-proposal.schema.json");
      if (Files.isRegularFile(repositoryCopy)) {
        try {
          byte[] repositoryBytes = Files.readAllBytes(repositoryCopy);
          verifySha(
              repositoryBytes, EXPECTED_CANONICAL_SCHEMA_SHA256, "repository canonical schema");
          require(
              java.util.Arrays.equals(classpathBytes, repositoryBytes),
              "classpath and repository canonical schema differ");
          return;
        } catch (IOException exception) {
          throw new IllegalStateException(
              "Repository canonical schema could not be verified.", exception);
        }
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Personal Memo repository root could not be located.");
  }

  private void publishAtomically(Path temporary, byte[] encoded) throws IOException {
    Path parent = reportPath.getParent();
    require(parent != null, "v6 report parent is missing");
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
    require(!value.initiallyLoaded(), "v6 requires a clean unloaded-model prestate");
    require(value.initiallyLoadedModelCount() == 0, "v6 requires zero initially loaded models");
  }

  private boolean isStructuralFailure(OllamaShadowFailure failure) {
    return switch (failure) {
      case ENDPOINT_REJECTED,
          REQUEST_TOO_LARGE,
          RESPONSE_TOO_LARGE,
          INTERRUPTED,
          IO_FAILURE,
          HTTP_STATUS,
          CONTENT_TYPE,
          MALFORMED_RESPONSE,
          INVALID_WRAPPER,
          TOOL_CALL_REJECTED,
          VERSION_MISMATCH,
          MODEL_MISMATCH ->
          true;
      case MODEL_OUTPUT_TOO_LARGE,
          TIMEOUT,
          MALFORMED_MODEL_JSON,
          INCOMPLETE_RESPONSE,
          TRUNCATED_RESPONSE ->
          false;
    };
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

  private double p95Milliseconds(List<Long> samples) {
    require(!samples.isEmpty(), "latency gate has no samples");
    return milliseconds(percentile(samples.stream().sorted().toList(), 0.95));
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

  private void increment(Map<String, Integer> values, String reason) {
    values.merge(reason, 1, Integer::sum);
  }

  private Path temporaryPath() {
    return reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
  }

  private void deleteOutput(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("v6 output cleanup failed.", exception);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record ShadowFixture(JsonNode node, String split, int ordinal) {}

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

  private record EvaluationRun(
      List<CaseEvaluation> fakeCases,
      List<CaseEvaluation> skillCases,
      List<CaseEvaluation> guardedCases,
      List<Long> fakeLatencyNanos,
      List<Long> skillLatencyNanos,
      List<Long> modelAttemptLatencyNanos,
      List<Long> guardedLatencyNanos,
      List<Long> totalLatencyNanos,
      List<OllamaApiMetrics> apiMetrics,
      List<Integer> modelOutputBytes,
      Map<String, Integer> rejectionReasons,
      int requestCount,
      int responseCount,
      int selectionSchemaValidCount,
      int selectionDomainValidCount,
      int acceptedCount,
      int rejectedCount,
      int fallbackCount,
      int primaryTitleChangedCount,
      int diagnosticTopicOrdinalCount,
      int protectedMutationCount,
      int fakeSkillMismatchCount,
      int improvedTitleCaseCount,
      int regressedTitleCaseCount,
      int unchangedTitleCaseCount) {}

  private record CleanupResult(boolean restored, String status) {}

  record ShadowConfiguration(
      String model,
      String modelDigest,
      String baseHead,
      String sourceBundleSha256,
      String gpuName,
      String gpuDriver,
      long gpuTotalMiB,
      long gpuBaselineUsedMiB) {
    static ShadowConfiguration from(Map<String, String> environment) {
      Objects.requireNonNull(environment, "environment");
      requireExact(environment, OPT_IN_ENV, OPT_IN_VALUE);
      requireExact(environment, MODEL_ENV, EXPECTED_MODEL);
      requireExact(environment, DIGEST_ENV, EXPECTED_DIGEST);
      return new ShadowConfiguration(
          EXPECTED_MODEL,
          EXPECTED_DIGEST,
          requirePattern(environment, BASE_HEAD_ENV, "[0-9a-f]{40}|[0-9a-f]{64}"),
          requirePattern(environment, SOURCE_BUNDLE_ENV, "[0-9a-f]{64}"),
          requirePattern(environment, GPU_NAME_ENV, GPU_TEXT.pattern()),
          requirePattern(environment, GPU_DRIVER_ENV, DRIVER_TEXT.pattern()),
          requireMiB(environment, GPU_TOTAL_ENV, false),
          requireMiB(environment, GPU_BASELINE_USED_ENV, true));
    }

    private static void requireExact(
        Map<String, String> environment, String name, String expected) {
      if (!expected.equals(environment.get(name))) {
        throw new IllegalArgumentException(name + " must contain the exact v6 opt-in value.");
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
class SoloLiquidAiDeterministicSkillRunner extends SoloLiquidAiDeterministicSkillCore {
  SoloLiquidAiDeterministicSkillRunner() {
    super();
  }
}
