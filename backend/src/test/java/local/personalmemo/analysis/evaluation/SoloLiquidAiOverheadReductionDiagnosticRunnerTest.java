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

class SoloLiquidAiOverheadReductionDiagnosticRunnerTest {
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void configurationRequiresTheDedicatedV7BOptInAndPinnedV6Model() {
    SoloLiquidAiOverheadReductionDiagnosticCore.DiagnosticConfiguration configuration =
        SoloLiquidAiOverheadReductionDiagnosticCore.DiagnosticConfiguration.from(
            validEnvironment());

    assertThat(configuration.model()).isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
    assertThat(configuration.modelDigest())
        .isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
    Map<String, String> missingOptIn = new HashMap<>(validEnvironment());
    missingOptIn.remove(SoloLiquidAiOverheadReductionDiagnosticCore.OPT_IN_ENV);
    assertThatThrownBy(
            () ->
                SoloLiquidAiOverheadReductionDiagnosticCore.DiagnosticConfiguration.from(
                    missingOptIn))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact v7-B opt-in");
  }

  @Test
  void reportsTwentyFourWireStopsAndSeparateValidationAcceptanceAndFallbackCounts()
      throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7b-all-stop.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.stopEvalCount = 80;
    SoloLiquidAiOverheadReductionDiagnosticCore runner =
        new SoloLiquidAiOverheadReductionDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.path("reportVersion").asText())
        .isEqualTo("solo-liquidai-overhead-reduction-diagnostic-v7b");
    assertThat(report.path("evaluationStatus").asText()).isEqualTo("SOLO_PROVISIONAL");
    assertThat(report.path("useRestriction").asText()).isEqualTo("REPORT_ONLY");
    assertThat(report.at("/execution/publicSyntheticCaseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRequestCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isZero();
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isZero();
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/terminationCounts/stop").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/terminationCounts/length").asInt()).isZero();
    assertThat(report.at("/terminationDiagnostics/evalTokens/sampleCount").asInt()).isEqualTo(24);
    assertThat(report.at("/terminationDiagnostics/evalTokens/minimum").asInt()).isEqualTo(80);
    assertThat(report.at("/terminationDiagnostics/promptEvalTokens/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(
            report
                .at("/terminationDiagnostics/modelContentBytes/bucketCounts/sixtyFiveTo128")
                .asInt())
        .isEqualTo(24);
    assertThat(report.at("/terminationDiagnostics/modelContentBytes/overCapCount").asInt())
        .isZero();
    assertThat(report.at("/performance/configuredCaps/numPredict").asInt()).isEqualTo(128);
    assertThat(report.at("/performance/configuredCaps/numContext").asInt()).isEqualTo(2_048);
    assertThat(report.at("/treatment/status").asText())
        .isEqualTo("PRE_REGISTERED_OVERHEAD_REDUCTION");
    assertThat(report.at("/treatment/userMessageResponseSchemaIncluded").asBoolean()).isFalse();
    assertThat(report.at("/treatment/userMessagePayload").asText())
        .isEqualTo("SKILL_EVIDENCE_ONLY");
    assertThat(report.at("/treatment/formatSchemaRootMetadataRemoved").toString())
        .isEqualTo("[\"$schema\",\"$id\",\"title\"]");
    assertThat(report.at("/treatment/formatSchemaStructuralSemantics").asText())
        .isEqualTo("UNCHANGED");
    assertThat(report.at("/treatment/localOutputValidation").asText())
        .isEqualTo("FULL_FROZEN_V6_SELECTION_SCHEMA");
    assertThat(report.at("/treatment/requestDeltaCount").asInt()).isEqualTo(2);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7aReference/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7aReference/sum").asInt())
        .isEqualTo(9_765);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7aReference/minimum").asInt())
        .isEqualTo(372);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7aReference/maximum").asInt())
        .isEqualTo(472);
    assertThat(report.at("/treatment/promptEvalTokenComparison/v7aReference/mean").asDouble())
        .isEqualTo(406.875d);
    assertThat(report.at("/treatment/promptEvalTokenComparison/observedSum").asInt())
        .isEqualTo(720);
    assertThat(report.at("/treatment/promptEvalTokenComparison/sumDeltaFromV7a").asInt())
        .isEqualTo(-9_045);
    assertThat(report.at("/treatment/diagnosticOutcome").asText())
        .isEqualTo("OVERHEAD_REDUCTION_OBSERVED");
    assertThat(report.at("/predecessorArtifactPins/reportSha256").asText())
        .isEqualTo("5b6a578b2b2222fc6180a4f70af7718526ccce2e127b070a404477a30c19d20f");
    assertThat(report.at("/predecessorArtifactPins/reportFileName").asText())
        .isEqualTo("solo-liquidai-truncation-diagnostic-v7a.json");
    assertThat(report.at("/predecessorArtifactPins/reportBytes").asLong()).isEqualTo(5_925L);
    assertThat(report.at("/predecessorArtifactPins/attestationSha256").asText())
        .isEqualTo("bccc6a0856ea9055f199d381e7be28e0e8587373687ab1d148f3617e69c4c617");
    assertThat(report.at("/predecessorArtifactPins/attestationFileName").asText())
        .isEqualTo("solo-liquidai-truncation-diagnostic-v7a-attestation.json");
    assertThat(report.at("/predecessorArtifactPins/attestationBytes").asLong()).isEqualTo(7_874L);
    assertThat(report.at("/training/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/training/loraDecision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/diagnosticInterpretation/status").asText()).isEqualTo("REPORT_ONLY");
    assertThat(report.at("/diagnosticInterpretation/preRegisteredTreatmentPackage").asText())
        .isEqualTo("REQUEST_OVERHEAD_REDUCTION_V7B");
    assertThat(report.at("/diagnosticInterpretation/qualityOrProviderReadinessClaimed").asBoolean())
        .isFalse();
    assertThat(report.at("/developmentAcceptance/status").asText()).isEqualTo("NOT_MET");
    assertThat(report.at("/developmentAcceptance/reason").asText())
        .isEqualTo("OVERHEAD_REDUCTION_DIAGNOSTIC_ONLY");
    assertThat(report.path("diagnosticInterpretation").has("singleVariableChanged")).isFalse();
    assertThat(report.at("/restoration/restored").asBoolean()).isTrue();
    assertThat(api.fullSchemaCalls).isEqualTo(24);
    assertThat(api.diagnoseCalls).isEqualTo(24);
    assertThat(api.unloadCalls).isEqualTo(1);
    assertThat(reportPath).exists();
    assertThat(Files.size(reportPath)).isLessThanOrEqualTo(512L * 1024L);
  }

  @Test
  void fullFrozenSelectionSchemaStillRejectsACompletedStopWithMissingRequiredField()
      throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7b-schema-invalid.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.schemaInvalidCall = 0;
    SoloLiquidAiOverheadReductionDiagnosticCore runner =
        new SoloLiquidAiOverheadReductionDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionSchemaValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionDomainValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(1);
    assertThat(
            report
                .at("/execution/selectionRejectionCounts/byReason/SELECTION_SCHEMA_INVALID")
                .asInt())
        .isEqualTo(1);
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
    assertThat(api.fullSchemaCalls).isEqualTo(24);
  }

  @Test
  void lengthAtTheNewCapIsRejectedDiagnosedAndFallsBackWithoutParsing() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7b-one-length.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.lengthCall = 0;
    SoloLiquidAiOverheadReductionDiagnosticCore runner =
        new SoloLiquidAiOverheadReductionDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();
    String serialized = Files.readString(reportPath, StandardCharsets.UTF_8);

    assertThat(report.at("/execution/modelWireResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/completedStopCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/terminationCounts/stop").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/terminationCounts/length").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/modelSelectionSchemaValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionDomainValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(1);
    assertThat(
            report
                .at("/execution/selectionRejectionCounts/byReason/" + "MODEL_TERMINATION_LENGTH")
                .asInt())
        .isEqualTo(1);
    assertThat(report.at("/terminationDiagnostics/evalCountEqualConfiguredCapCount").asInt())
        .isEqualTo(1);
    assertThat(report.at("/terminationDiagnostics/lengthContentParsed").asBoolean()).isFalse();
    assertThat(report.at("/terminationDiagnostics/lengthContentStored").asBoolean()).isFalse();
    assertThat(report.at("/terminationDiagnostics/lengthContentReported").asBoolean()).isFalse();
    assertThat(report.at("/execution/guardedCanonicalSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/guardedDomainValidCount").asInt()).isEqualTo(24);
    assertThat(serialized)
        .doesNotContain(
            "\"selectionOutput\"", "\"rawModelOutput\"", "\"skillEvidence\"", "\"memoText\"");
  }

  @Test
  void reportShapeGuardRejectsCanaryContentWithoutEchoingIt() {
    String canary = "REPORT_CONTENT_CANARY_MUST_NOT_LEAK_88ad";
    SoloLiquidAiOverheadReductionDiagnosticCore runner =
        new SoloLiquidAiOverheadReductionDiagnosticCore(
            validEnvironment(),
            new FakeDiagnosticApi(),
            temporaryDirectory.resolve("unused-report.json"));
    ObjectNode allowed = json.createObjectNode();
    allowed.putObject("resourceIntegrity").put("skillEvidenceSchemaSha256", "a".repeat(64));
    ObjectNode forbidden = json.createObjectNode();
    forbidden.putObject("nested").putObject("skillEvidence").put("defaultTitle", canary);

    runner.assertNoForbiddenReportFields(allowed);

    assertThatThrownBy(() -> runner.assertNoForbiddenReportFields(forbidden))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("report contains a forbidden field")
        .hasMessageNotContaining(canary);
  }

  @Test
  void cleanupFailurePublishesNoDiagnosticOrTemporaryArtifact() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7b-cleanup-failure.json");
    Path temporaryPath = temporaryDirectory.resolve("v7b-cleanup-failure.json.tmp");
    Files.writeString(reportPath, "stale", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.cleanupFails = true;
    SoloLiquidAiOverheadReductionDiagnosticCore runner =
        new SoloLiquidAiOverheadReductionDiagnosticCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Ollama model state restoration failed before reporting.");
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> values = new HashMap<>();
    values.put(
        SoloLiquidAiOverheadReductionDiagnosticCore.OPT_IN_ENV,
        SoloLiquidAiOverheadReductionDiagnosticCore.OPT_IN_VALUE);
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
    private int schemaInvalidCall = -1;
    private long stopEvalCount = 80;
    private boolean cleanupFails;
    private int diagnoseCalls;
    private int unloadCalls;
    private int fullSchemaCalls;
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
        ObjectNode skillEvidence, ObjectNode selectionSchema) {
      int call = diagnoseCalls++;
      if (selectionSchema.has("$schema")
          && selectionSchema.has("$id")
          && selectionSchema.has("title")) {
        fullSchemaCalls++;
      }
      if (call == lengthCall) {
        return OllamaTruncationDiagnosticResult.rejected(
            DiagnosticTermination.LENGTH,
            DiagnosticModelRejection.TERMINATION_LENGTH,
            2_000_000L,
            metrics(128),
            120);
      }
      ObjectNode selection =
          json.createObjectNode().put("schemaVersion", ShadowDeterministicSkill.SELECTION_VERSION);
      JsonNode items = skillEvidence.path("items");
      selection.put("primaryItemOrdinal", items.isEmpty() ? -1 : 0);
      if (call != schemaInvalidCall) {
        selection.putArray("topicObjectOrdinals");
      }
      return OllamaTruncationDiagnosticResult.completedStop(
          selection,
          2_000_000L,
          metrics(stopEvalCount),
          selection.toString().getBytes(StandardCharsets.UTF_8).length);
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
