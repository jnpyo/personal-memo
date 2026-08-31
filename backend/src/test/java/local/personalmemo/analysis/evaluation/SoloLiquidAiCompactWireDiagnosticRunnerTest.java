package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SoloLiquidAiCompactWireDiagnosticRunnerTest {
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void configurationRequiresTheDedicatedV8AOptInAndPinnedV6Model() {
    SoloLiquidAiCompactWireDiagnosticCore.DiagnosticConfiguration configuration =
        SoloLiquidAiCompactWireDiagnosticCore.DiagnosticConfiguration.from(validEnvironment());

    assertThat(configuration.model()).isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
    assertThat(configuration.modelDigest())
        .isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
    Map<String, String> missingOptIn = new HashMap<>(validEnvironment());
    missingOptIn.remove(SoloLiquidAiCompactWireDiagnosticCore.OPT_IN_ENV);
    assertThatThrownBy(
            () -> SoloLiquidAiCompactWireDiagnosticCore.DiagnosticConfiguration.from(missingOptIn))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact v8-A opt-in");
  }

  @Test
  void reportsTwentyFourParsedValidatedMappedAndDomainAcceptedCompactStops() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v8a-all-stop.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.stopEvalCount = 80;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.path("reportVersion").asText())
        .isEqualTo("solo-liquidai-compact-wire-diagnostic-v8a");
    assertThat(report.path("evaluationStatus").asText()).isEqualTo("SOLO_PROVISIONAL");
    assertThat(report.path("useRestriction").asText()).isEqualTo("REPORT_ONLY");
    assertThat(report.at("/execution/publicSyntheticCaseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRequestCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireObjectParsedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireMappedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/mappedFullSelectionSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/mappedSelectionDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isZero();
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isZero();
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/terminationCounts/stop").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/terminationCounts/length").asInt()).isZero();
    assertThat(report.at("/terminationDiagnostics/evalTokens/minimum").asInt()).isEqualTo(80);
    assertThat(report.at("/terminationDiagnostics/promptEvalTokens/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/terminationDiagnostics/modelContentBytes/bucketCounts/zeroTo64").asInt())
        .isEqualTo(24);
    assertThat(report.at("/terminationDiagnostics/modelContentBytes/overCapCount").asInt())
        .isZero();

    assertThat(report.at("/treatment/status").asText()).isEqualTo("PRE_REGISTERED_COMPACT_WIRE");
    assertThat(report.at("/treatment/predecessorTreatmentPreserved").asText())
        .isEqualTo("REQUEST_OVERHEAD_REDUCTION_V7B");
    assertThat(report.at("/treatment/wireSchemaVersion").asText()).isEqualTo("1");
    assertThat(report.at("/treatment/wireKeys").toString()).isEqualTo("[\"v\",\"p\",\"t\"]");
    assertThat(report.at("/treatment/wireAdditionalPropertiesAllowed").asBoolean()).isFalse();
    assertThat(report.at("/treatment/wireAllFieldsRequired").asBoolean()).isTrue();
    assertThat(report.at("/treatment/userMessageResponseSchemaIncluded").asBoolean()).isFalse();
    assertThat(report.at("/treatment/userMessagePayload").asText())
        .isEqualTo("SKILL_EVIDENCE_ONLY");
    assertThat(report.at("/treatment/rawOutputValidation").asText())
        .isEqualTo("FULL_PINNED_COMPACT_WIRE_SCHEMA");
    assertThat(report.at("/treatment/mappingPolicy").asText())
        .isEqualTo("STRICT_NO_REPAIR_OR_CLAMP");
    assertThat(report.at("/treatment/mappedOutputValidation").asText())
        .isEqualTo("FULL_FROZEN_V6_SELECTION_SCHEMA");
    assertThat(report.at("/treatment/dynamicDomainValidation").asText())
        .isEqualTo("IDENTICAL_FROZEN_V6_SKILL_DOMAIN");
    assertThat(report.at("/treatment/requestDeltaCount").asInt()).isEqualTo(2);
    assertThat(report.at("/treatment/requestDeltaJsonPointers").toString())
        .isEqualTo("[\"/messages/0/content\",\"/format\"]");
    assertThat(report.at("/treatment/formatSchemaRootMetadataRemoved").toString())
        .isEqualTo("[\"$schema\",\"$id\",\"title\"]");
    assertThat(report.at("/treatment/deterministicMappingInvariant/passed").asBoolean()).isTrue();

    assertThat(report.at("/treatment/promptEvalTokenComparison/v7bReference/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7bReference/sum").asInt())
        .isEqualTo(5_973);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7bReference/minimum").asInt())
        .isEqualTo(214);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7bReference/maximum").asInt())
        .isEqualTo(314);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7bReference/mean").asDouble())
        .isEqualTo(248.875d);
    assertThat(report.at("/treatment/promptEvalTokenComparison/observedSum").asInt())
        .isEqualTo(720);
    assertThat(report.at("/treatment/promptEvalTokenComparison/sumDeltaFromV7b").asInt())
        .isEqualTo(-5_253);
    assertThat(report.at("/treatment/promptTokenOutcome").asText())
        .isEqualTo("COMPACT_WIRE_PROMPT_TOKEN_REDUCTION_OBSERVED");
    assertThat(report.at("/treatment/diagnosticOutcome").asText())
        .isEqualTo("COMPACT_WIRE_STOP_AND_ACCEPTANCE_OBSERVED");
    assertThat(report.at("/treatment/v7bAggregateReference/terminationCounts/length").asInt())
        .isEqualTo(24);
    assertThat(report.at("/treatment/v7bAggregateReference/evalTokens/sum").asInt())
        .isEqualTo(3_072);
    assertThat(report.at("/treatment/v7bAggregateReference/modelContentBytes/maximum").asInt())
        .isZero();
    assertThat(
            report
                .at(
                    "/treatment/v7bAggregateReference/modelSelectionAttemptWallLatency/meanMilliseconds")
                .asDouble())
        .isEqualTo(806.316d);

    assertThat(report.at("/predecessorArtifactPins/reportFileName").asText())
        .isEqualTo("solo-liquidai-overhead-reduction-diagnostic-v7b.json");
    assertThat(report.at("/predecessorArtifactPins/reportBytes").asLong()).isEqualTo(7_081L);
    assertThat(report.at("/predecessorArtifactPins/reportSha256").asText())
        .isEqualTo("c81939c516a002aef5b53f867d9bf9cb9f176a8204894e870e0134ccc66c6b37");
    assertThat(report.at("/predecessorArtifactPins/attestationFileName").asText())
        .isEqualTo("solo-liquidai-overhead-reduction-diagnostic-v7b-attestation.json");
    assertThat(report.at("/predecessorArtifactPins/attestationBytes").asLong()).isEqualTo(9_743L);
    assertThat(report.at("/predecessorArtifactPins/attestationSha256").asText())
        .isEqualTo("ff057509f5cc24dce0cbf25337a9d841f3d293821c1d73280b94dfdbccbe233d");
    assertThat(report.at("/resourceIntegrity/compactWireSchemaSha256").asText())
        .isEqualTo("24b47cc72405320dd4dff795b0c97f0dc1a8aee37cc8e84a81c10034a87e890e");
    assertThat(report.at("/performance/configuredCaps/numPredict").asInt()).isEqualTo(128);
    assertThat(report.at("/performance/configuredCaps/numContext").asInt()).isEqualTo(2_048);
    assertThat(report.at("/performance/fakeAnalyzerWallLatency/sampleCount").asInt()).isEqualTo(24);
    assertThat(report.at("/performance/modelSelectionAttemptWallLatency/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/performance/modelVsFakeAnalyzer/comparisonScope").asText())
        .isEqualTo("SAME_CASE_SAME_RUN");
    assertThat(report.at("/performance/modelVsFakeAnalyzer/fakeSampleCount").asInt()).isEqualTo(24);
    assertThat(report.at("/performance/modelVsFakeAnalyzer/modelSampleCount").asInt())
        .isEqualTo(24);
    assertThat(
            report.at("/performance/modelVsFakeAnalyzer/p95/modelSelectionMilliseconds").asDouble())
        .isEqualTo(2d);
    assertThat(report.at("/performance/modelVsFakeAnalyzer/p95/ratioStatus").asText())
        .isEqualTo("AVAILABLE");
    assertThat(report.at("/performance/modelVsFakeAnalyzer/p95/modelToFakeRatio").asDouble())
        .isPositive();
    assertThat(report.at("/compactWireDiagnosticDecision/policyVersion").asText())
        .isEqualTo("compact-wire-v8a-pre-registered-1");
    assertThat(
            report
                .at("/compactWireDiagnosticDecision/policyFrozenBeforeModelExecution")
                .asBoolean())
        .isTrue();
    assertThat(report.at("/compactWireDiagnosticDecision/safetyBoundaryMet").asBoolean()).isTrue();
    assertThat(report.at("/compactWireDiagnosticDecision/fullReliabilityMet").asBoolean()).isTrue();
    assertThat(report.at("/compactWireDiagnosticDecision/decision").asText())
        .isEqualTo("GO_TO_NEXT_NON_TRAINING_EVALUATION");
    assertThat(report.at("/compactWireDiagnosticDecision/productOrProviderDecision").asText())
        .isEqualTo("NO_GO");
    assertThat(report.at("/compactWireDiagnosticDecision/loraDecision").asText())
        .isEqualTo("NO_GO");
    assertThat(report.at("/training/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/training/loraDecision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/diagnosticInterpretation/status").asText()).isEqualTo("REPORT_ONLY");
    assertThat(report.at("/diagnosticInterpretation/preRegisteredTreatmentPackage").asText())
        .isEqualTo("COMPACT_WIRE_V8A");
    assertThat(
            report.at("/diagnosticInterpretation/fallbackValidityAttributedToLiquidAi").asBoolean())
        .isFalse();
    assertThat(report.at("/developmentAcceptance/status").asText()).isEqualTo("NOT_MET");
    assertThat(report.at("/developmentAcceptance/reason").asText())
        .isEqualTo("COMPACT_WIRE_DIAGNOSTIC_ONLY");
    assertThat(report.at("/restoration/restored").asBoolean()).isTrue();
    assertThat(api.fullCompactSchemaCalls).isEqualTo(24);
    assertThat(api.diagnoseCalls).isEqualTo(24);
    assertThat(api.unloadCalls).isEqualTo(1);
    assertThat(reportPath).exists();
    assertThat(Files.size(reportPath)).isLessThanOrEqualTo(512L * 1024L);
  }

  @Test
  void compactSchemaRejectsWholeEnvelopesBeforeMappingWithoutLeakingCanary() throws Exception {
    String canary = "INVALID_COMPACT_WIRE_CANARY_MUST_NOT_LEAK_73d2";
    Path reportPath = temporaryDirectory.resolve("v8a-compact-invalid.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.invalidCompactCallCount = 5;
    api.invalidCanary = canary;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();
    String serialized = Files.readString(reportPath, StandardCharsets.UTF_8);

    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireObjectParsedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireSchemaValidCount").asInt()).isEqualTo(19);
    assertThat(report.at("/execution/compactWireMappedCount").asInt()).isEqualTo(19);
    assertThat(report.at("/execution/mappedFullSelectionSchemaValidCount").asInt()).isEqualTo(19);
    assertThat(report.at("/execution/mappedSelectionDomainValidCount").asInt()).isEqualTo(19);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(19);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(5);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(5);
    assertThat(
            report
                .at("/execution/selectionRejectionCounts/byReason/COMPACT_WIRE_SCHEMA_INVALID")
                .asInt())
        .isEqualTo(5);
    assertThat(report.at("/treatment/deterministicMappingInvariant/passed").asBoolean()).isTrue();
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/compactWireDiagnosticDecision/decision").asText())
        .isEqualTo("GO_TO_NEXT_NON_TRAINING_EVALUATION");
    assertThat(report.at("/compactWireDiagnosticDecision/fullReliabilityMet").asBoolean())
        .isFalse();
    assertThat(serialized).doesNotContain(canary);
  }

  @Test
  void noAcceptedCompactSelectionIsPreRegisteredNoGo() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v8a-all-length.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.allLength = true;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/terminationCounts/length").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isZero();
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(24);
    assertThat(report.at("/compactWireDiagnosticDecision/safetyBoundaryMet").asBoolean()).isTrue();
    assertThat(report.at("/compactWireDiagnosticDecision/fullReliabilityMet").asBoolean())
        .isFalse();
    assertThat(report.at("/compactWireDiagnosticDecision/decision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/compactWireDiagnosticDecision/productOrProviderDecision").asText())
        .isEqualTo("NO_GO");
    assertThat(report.at("/compactWireDiagnosticDecision/trainingDecision").asText())
        .isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/compactWireDiagnosticDecision/loraDecision").asText())
        .isEqualTo("NO_GO");
  }

  @Test
  void structurallyValidCompactWireStillUsesTheFrozenDynamicDomain() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v8a-domain-invalid.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.domainInvalidCall = 0;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/compactWireObjectParsedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/compactWireMappedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/mappedFullSelectionSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/mappedSelectionDomainValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/selectionRejectionCounts/total").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
  }

  @Test
  void lengthAtTheCapIsRejectedAndFallsBackWithoutAParsedCompactObject() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v8a-one-length.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.lengthCall = 0;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();
    String serialized = Files.readString(reportPath, StandardCharsets.UTF_8);

    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/terminationCounts/stop").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/terminationCounts/length").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/compactWireObjectParsedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/compactWireSchemaValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/compactWireMappedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/mappedFullSelectionSchemaValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/mappedSelectionDomainValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(1);
    assertThat(
            report
                .at("/execution/selectionRejectionCounts/byReason/MODEL_TERMINATION_LENGTH")
                .asInt())
        .isEqualTo(1);
    assertThat(report.at("/terminationDiagnostics/evalCountEqualConfiguredCapCount").asInt())
        .isEqualTo(1);
    assertThat(report.at("/terminationDiagnostics/lengthContentParsed").asBoolean()).isFalse();
    assertThat(report.at("/terminationDiagnostics/lengthContentStored").asBoolean()).isFalse();
    assertThat(report.at("/terminationDiagnostics/lengthContentReported").asBoolean()).isFalse();
    assertThat(serialized)
        .doesNotContain(
            "\"selectionOutput\"",
            "\"compactWireOutput\"",
            "\"mappedSelectionOutput\"",
            "\"rawModelOutput\"",
            "\"skillEvidence\"",
            "\"memoText\"");
  }

  @Test
  void reportShapeGuardRejectsNestedRawCompactAndLongFormPropertiesWithoutEchoingCanary() {
    String canary = "REPORT_COMPACT_CANARY_MUST_NOT_LEAK_a492";
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(
            validEnvironment(),
            new FakeDiagnosticApi(),
            temporaryDirectory.resolve("unused-report.json"));
    ObjectNode allowed = json.createObjectNode();
    allowed
        .putObject("resourceIntegrity")
        .put("compactWireSchemaSha256", "a".repeat(64))
        .put("modelSelectionSchemaSha256", "b".repeat(64));
    ObjectNode rawCompact = json.createObjectNode();
    rawCompact.putObject("nested").put("v", canary);
    ObjectNode mappedLong = json.createObjectNode();
    mappedLong.putObject("nested").put("primaryItemOrdinal", canary);

    runner.assertNoForbiddenReportFields(allowed);

    assertThatThrownBy(() -> runner.assertNoForbiddenReportFields(rawCompact))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("report contains a forbidden field")
        .hasMessageNotContaining(canary);
    assertThatThrownBy(() -> runner.assertNoForbiddenReportFields(mappedLong))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("report contains a forbidden field")
        .hasMessageNotContaining(canary);
  }

  @Test
  void cleanupFailurePublishesNoDiagnosticOrTemporaryArtifact() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v8a-cleanup-failure.json");
    Path temporaryPath = temporaryDirectory.resolve("v8a-cleanup-failure.json.tmp");
    Files.writeString(reportPath, "stale", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.cleanupFails = true;
    SoloLiquidAiCompactWireDiagnosticCore runner =
        new SoloLiquidAiCompactWireDiagnosticCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Ollama model state restoration failed before reporting.");
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> values = new HashMap<>();
    values.put(
        SoloLiquidAiCompactWireDiagnosticCore.OPT_IN_ENV,
        SoloLiquidAiCompactWireDiagnosticCore.OPT_IN_VALUE);
    values.put(
        SoloLiquidAiDeterministicSkillCore.MODEL_ENV,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
    values.put(
        SoloLiquidAiDeterministicSkillCore.DIGEST_ENV,
        SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
    values.put(SoloLiquidAiDeterministicSkillCore.BASE_HEAD_ENV, "a".repeat(40));
    values.put(SoloLiquidAiDeterministicSkillCore.SOURCE_BUNDLE_ENV, "b".repeat(64));
    values.put(SoloLiquidAiDeterministicSkillCore.GPU_NAME_ENV, "NVIDIA Test GPU");
    values.put(SoloLiquidAiDeterministicSkillCore.GPU_DRIVER_ENV, "999.99");
    values.put(SoloLiquidAiDeterministicSkillCore.GPU_TOTAL_ENV, "12288");
    values.put(SoloLiquidAiDeterministicSkillCore.GPU_BASELINE_USED_ENV, "512");
    return Map.copyOf(values);
  }

  private final class FakeDiagnosticApi implements OllamaTruncationDiagnosticApi {
    private int lengthCall = -1;
    private int invalidCompactCallCount;
    private int domainInvalidCall = -1;
    private String invalidCanary = "unused";
    private long stopEvalCount = 80;
    private boolean allLength;
    private boolean cleanupFails;
    private int diagnoseCalls;
    private int unloadCalls;
    private int fullCompactSchemaCalls;
    private boolean warmed;
    private boolean unloaded;

    @Override
    public OllamaModelPreflight preflight() {
      return new OllamaModelPreflight(
          "0.32.7",
          SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL,
          SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST,
          2_874_790_997L,
          "gguf",
          "lfm2",
          "2.7B",
          "Q8_0",
          false,
          0);
    }

    @Override
    public OllamaWarmupResult warmup() {
      warmed = true;
      unloaded = false;
      return new OllamaWarmupResult(1_000_000L, metrics(1));
    }

    @Override
    public OllamaTruncationDiagnosticResult diagnose(
        ObjectNode skillEvidence, ObjectNode compactWireSchema) {
      int call = diagnoseCalls++;
      if (compactWireSchema.has("$schema")
          && compactWireSchema.has("$id")
          && compactWireSchema.has("title")
          && compactWireSchema.path("properties").size() == 3
          && compactWireSchema.at("/properties/v/enum/0").asText().equals("1")) {
        fullCompactSchemaCalls++;
      }
      if (allLength || call == lengthCall) {
        return OllamaTruncationDiagnosticResult.rejected(
            DiagnosticTermination.LENGTH,
            DiagnosticModelRejection.TERMINATION_LENGTH,
            2_000_000L,
            metrics(128),
            42);
      }

      JsonNode items = skillEvidence.path("items");
      ObjectNode compact = json.createObjectNode().put("v", "1");
      compact.put("p", items.isEmpty() ? -1 : 0);
      compact.putArray("t");
      if (call < invalidCompactCallCount) {
        makeCompactInvalid(compact, call, invalidCanary);
      } else if (call == domainInvalidCall) {
        compact.put("p", items.isEmpty() ? 0 : -1);
      }
      return OllamaTruncationDiagnosticResult.completedStop(
          compact,
          2_000_000L,
          metrics(stopEvalCount),
          compact.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    private void makeCompactInvalid(ObjectNode compact, int variant, String canary) {
      switch (variant) {
        case 0 -> compact.put("extra", canary);
        case 1 -> compact.put("v", "2");
        case 2 -> compact.put("p", 3);
        case 3 -> compact.putArray("t").add(0).add(0);
        case 4 -> compact.remove("t");
        default -> throw new IllegalArgumentException("unexpected invalid compact variant");
      }
    }

    @Override
    public OllamaObservedAllocation allocation() {
      if (unloaded && !cleanupFails) {
        return OllamaObservedAllocation.notLoaded();
      }
      return warmed
          ? new OllamaObservedAllocation(true, 3_100_000_000L, 3_000_000_000L, 2_048)
          : OllamaObservedAllocation.notLoaded();
    }

    @Override
    public void unload() {
      unloadCalls++;
      unloaded = true;
    }

    private OllamaApiMetrics metrics(long evalCount) {
      return new OllamaApiMetrics(2_000_000L, 0L, 30L, 500_000L, evalCount, 1_000_000L);
    }
  }
}
