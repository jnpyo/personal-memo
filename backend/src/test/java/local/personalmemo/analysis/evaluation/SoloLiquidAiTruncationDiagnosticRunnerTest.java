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

class SoloLiquidAiTruncationDiagnosticRunnerTest {
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void configurationRequiresTheDedicatedV7AOptInAndPinnedV6Model() {
    SoloLiquidAiTruncationDiagnosticCore.DiagnosticConfiguration configuration =
        SoloLiquidAiTruncationDiagnosticCore.DiagnosticConfiguration.from(validEnvironment());

    assertThat(configuration.model()).isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
    assertThat(configuration.modelDigest())
        .isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
    Map<String, String> missingOptIn = new HashMap<>(validEnvironment());
    missingOptIn.remove(SoloLiquidAiTruncationDiagnosticCore.OPT_IN_ENV);
    assertThatThrownBy(
            () -> SoloLiquidAiTruncationDiagnosticCore.DiagnosticConfiguration.from(missingOptIn))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact v7-A opt-in");
  }

  @Test
  void reportsTwentyFourWireStopsAndSeparateValidationAcceptanceAndFallbackCounts()
      throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7a-all-stop.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.stopEvalCount = 80;
    SoloLiquidAiTruncationDiagnosticCore runner =
        new SoloLiquidAiTruncationDiagnosticCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.path("reportVersion").asText())
        .isEqualTo("solo-liquidai-truncation-diagnostic-v7a");
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
    assertThat(report.at("/predecessorArtifactPins/reportSha256").asText())
        .isEqualTo("a761cd89276ebecbed8a09f2aa6b37d041f16944bbf8491fd87d1f1201a0b35f");
    assertThat(report.at("/predecessorArtifactPins/attestationSha256").asText())
        .isEqualTo("e19e72232e9a5780fb22c8d9c7a80ed228da37a5593b000221eb2a7f1f300fb5");
    assertThat(report.at("/training/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/training/loraDecision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/restoration/restored").asBoolean()).isTrue();
    assertThat(api.diagnoseCalls).isEqualTo(24);
    assertThat(api.unloadCalls).isEqualTo(1);
    assertThat(reportPath).exists();
    assertThat(Files.size(reportPath)).isLessThanOrEqualTo(512L * 1024L);
  }

  @Test
  void lengthAtTheNewCapIsRejectedDiagnosedAndFallsBackWithoutParsing() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v7a-one-length.json");
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.lengthCall = 0;
    SoloLiquidAiTruncationDiagnosticCore runner =
        new SoloLiquidAiTruncationDiagnosticCore(validEnvironment(), api, reportPath);

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
    SoloLiquidAiTruncationDiagnosticCore runner =
        new SoloLiquidAiTruncationDiagnosticCore(
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
    Path reportPath = temporaryDirectory.resolve("v7a-cleanup-failure.json");
    Path temporaryPath = temporaryDirectory.resolve("v7a-cleanup-failure.json.tmp");
    Files.writeString(reportPath, "stale", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeDiagnosticApi api = new FakeDiagnosticApi();
    api.cleanupFails = true;
    SoloLiquidAiTruncationDiagnosticCore runner =
        new SoloLiquidAiTruncationDiagnosticCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Ollama model state restoration failed before reporting.");
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> values = new HashMap<>();
    values.put(
        SoloLiquidAiTruncationDiagnosticCore.OPT_IN_ENV,
        SoloLiquidAiTruncationDiagnosticCore.OPT_IN_VALUE);
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
    private long stopEvalCount = 80;
    private boolean cleanupFails;
    private int diagnoseCalls;
    private int unloadCalls;
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
      selection.putArray("topicObjectOrdinals");
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
