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

class SoloLiquidAiDeterministicSkillRunnerTest {
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void configurationRequiresTheExactV6OptInPinnedModelAndBoundedHardware() {
    SoloLiquidAiDeterministicSkillCore.ShadowConfiguration configuration =
        SoloLiquidAiDeterministicSkillCore.ShadowConfiguration.from(validEnvironment());

    assertThat(configuration.model()).isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_MODEL);
    assertThat(configuration.modelDigest())
        .isEqualTo(SoloLiquidAiDeterministicSkillCore.EXPECTED_DIGEST);
    assertThat(configuration.gpuTotalMiB()).isEqualTo(12_288);

    Map<String, String> missingOptIn = new HashMap<>(validEnvironment());
    missingOptIn.remove(SoloLiquidAiDeterministicSkillCore.OPT_IN_ENV);
    assertThatThrownBy(
            () -> SoloLiquidAiDeterministicSkillCore.ShadowConfiguration.from(missingOptIn))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact v6 opt-in");
  }

  @Test
  void writesThreeAggregateArmsWithSeparateBoundaryAndModelContributionAxes() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v6-valid.json");
    FakeSkillApi api = new FakeSkillApi();
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/publicSyntheticCaseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRequestCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionDomainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isZero();
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isZero();
    assertThat(report.at("/quality/fake/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/skillOnly/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/liquidAiGuardedBySkill/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/protectedProposalInvariant/fakeToSkillDeepMismatchCaseCount").asInt())
        .isZero();
    assertThat(report.at("/protectedProposalInvariant/modelProtectedMutationCaseCount").asInt())
        .isZero();
    assertThat(report.at("/attribution/topicOrdinalsMutateProposalFields").asBoolean()).isFalse();
    assertThat(report.at("/attribution/invalidTopicOrdinalsRejectWholeEnvelope").asBoolean())
        .isTrue();
    assertThat(report.at("/attribution/topicOrdinalControlFlowPolicy").asText())
        .isEqualTo("DO_NOT_MUTATE_PROPOSAL_FIELDS_BUT_CAN_REJECT_MODEL_ENVELOPE");
    assertThat(report.at("/training/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/training/loraDecision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/guardedSystemAcceptance/status").asText()).isIn("MET", "NOT_MET");
    assertThat(report.at("/modelContributionAcceptance/status").asText()).isIn("MET", "NOT_MET");
    assertThat(report.at("/developmentAcceptance/status").asText()).isIn("MET", "NOT_MET");
    assertThat(report.path("cases").isMissingNode()).isTrue();
    assertThat(report.findValue("memoId")).isNull();
    assertThat(report.findValue("selectionOutput")).isNull();
    assertThat(report.findValue("rawModelOutput")).isNull();
    assertThat(report.findValues("title")).allMatch(JsonNode::isObject);
    assertThat(report.findValues("action")).allMatch(JsonNode::isObject);
    assertThat(report.findValues("object")).allMatch(JsonNode::isObject);
    assertThat(report.findValues("sourceSpan")).allMatch(JsonNode::isObject);
    assertThat(report.findValues("suggestedTitle")).allMatch(JsonNode::isObject);
    assertThat(report.at("/restoration/restored").asBoolean()).isTrue();
    assertThat(api.selectCalls).isEqualTo(24);
    assertThat(api.unloadCalls).isEqualTo(1);
    assertThat(reportPath).exists();
    assertThat(Files.size(reportPath)).isLessThanOrEqualTo(512L * 1024L);
  }

  @Test
  void invalidSelectionUsesSafeFallbackButCannotMakeModelContributionOrOverallMet()
      throws Exception {
    String canary = "MODEL_OUTPUT_CANARY_MUST_NOT_LEAK_17c9";
    Path reportPath = temporaryDirectory.resolve("v6-fallback.json");
    FakeSkillApi api = new FakeSkillApi();
    api.invalidCall = 0;
    api.canary = canary;
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();
    String serialized = Files.readString(reportPath, StandardCharsets.UTF_8);

    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(23);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/skillFallbackCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/selectionRejectionCounts/total").asInt()).isEqualTo(1);
    assertThat(
            report
                .at("/execution/selectionRejectionCounts/byReason/SELECTION_SCHEMA_INVALID")
                .asInt())
        .isEqualTo(1);
    assertThat(report.at("/quality/liquidAiGuardedBySkill/all/schemaValidCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/quality/liquidAiGuardedBySkill/all/domainValidCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/modelContributionAcceptance/status").asText()).isEqualTo("NOT_MET");
    assertThat(report.at("/developmentAcceptance/status").asText()).isEqualTo("NOT_MET");
    assertThat(report.at("/attribution/fallbackValidityAttributedToLiquidAi").asBoolean())
        .isFalse();
    assertThat(serialized).doesNotContain(canary);
  }

  @Test
  void cachedPinnedSelectionSchemaIsDefensivelyCopiedForEveryModelCall() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v6-schema-copy.json");
    FakeSkillApi api = new FakeSkillApi();
    api.mutateSelectionSchema = true;
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(api.selectCalls).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionAcceptedCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/modelSelectionRejectedCount").asInt()).isZero();
  }

  @Test
  void cleanupFailurePublishesNoReportOrTemporaryArtifact() throws Exception {
    Path reportPath = temporaryDirectory.resolve("v6-cleanup-failure.json");
    Path temporaryPath = temporaryDirectory.resolve("v6-cleanup-failure.json.tmp");
    Files.writeString(reportPath, "stale", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeSkillApi api = new FakeSkillApi();
    api.cleanupFails = true;
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Ollama model state restoration failed before reporting.");
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  @Test
  void initiallyLoadedModelFailsBeforeWarmupAndRunnerStaysOutsideDefaultTestPatterns() {
    Path reportPath = temporaryDirectory.resolve("v6-preloaded.json");
    FakeSkillApi api = new FakeSkillApi();
    api.initiallyLoaded = true;
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("v6 requires a clean unloaded-model prestate");
    assertThat(api.warmupCalls).isZero();
    assertThat(api.selectCalls).isZero();
    assertThat(SoloLiquidAiDeterministicSkillRunner.class.getSimpleName())
        .doesNotEndWith("Test")
        .doesNotEndWith("Tests")
        .doesNotStartWith("Test");
  }

  @Test
  void contributionAcceptanceRequiresIncrementalImprovementNoRegressionAndFastSelector() {
    assertThat(
            SoloLiquidAiDeterministicSkillCore.modelContributionMet(
                24, 24, 24, 24, 24, 0, 0, true, 0, 1, 0, true))
        .isTrue();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.modelContributionMet(
                24, 24, 24, 24, 24, 0, 0, true, 0, 0, 0, true))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.modelContributionMet(
                24, 24, 24, 24, 24, 0, 0, true, 0, 1, 1, true))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.modelContributionMet(
                24, 24, 24, 24, 24, 0, 0, true, 0, 1, 0, false))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.modelContributionMet(
                24, 23, 23, 23, 23, 1, 1, true, 0, 1, 0, true))
        .isFalse();
  }

  @Test
  void splitNonDegradationRequiresBetterTitleCountsStableCompletenessAndZeroSafetyErrors() {
    EvaluationFieldCounts skillTitle = new EvaluationFieldCounts(2, 2, 2);
    EvaluationFieldCounts improvedTitle = new EvaluationFieldCounts(3, 1, 1);

    assertThat(
            SoloLiquidAiDeterministicSkillCore.titleAndSafetyNonDegrading(
                skillTitle, improvedTitle, 8, 8, 0, 0))
        .isTrue();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.titleAndSafetyNonDegrading(
                skillTitle, new EvaluationFieldCounts(3, 3, 1), 8, 8, 0, 0))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.titleAndSafetyNonDegrading(
                skillTitle, improvedTitle, 8, 7, 0, 0))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.titleAndSafetyNonDegrading(
                skillTitle, improvedTitle, 8, 8, 1, 0))
        .isFalse();
    assertThat(
            SoloLiquidAiDeterministicSkillCore.titleAndSafetyNonDegrading(
                skillTitle, improvedTitle, 8, 8, 0, 1))
        .isFalse();
  }

  @Test
  void reportShapeGuardRejectsTopLevelAndNestedModelEvidenceCanariesWithoutEchoingThem() {
    String canary = "EVIDENCE_CANARY_MUST_NOT_LEAK_e452";
    SoloLiquidAiDeterministicSkillCore runner =
        new SoloLiquidAiDeterministicSkillCore(
            validEnvironment(),
            new FakeSkillApi(),
            temporaryDirectory.resolve("unused-report.json"));
    ObjectNode topLevel = json.createObjectNode().put("defaultTitle", canary);
    ObjectNode nested = json.createObjectNode();
    nested
        .putObject("nested")
        .put("objectValue", canary)
        .putObject("deeper")
        .put("primaryItemOrdinal", 0);

    assertThatThrownBy(() -> runner.assertNoForbiddenReportFields(topLevel))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("report contains a forbidden field")
        .hasMessageNotContaining(canary);
    assertThatThrownBy(() -> runner.assertNoForbiddenReportFields(nested))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("report contains a forbidden field")
        .hasMessageNotContaining(canary);
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> values = new HashMap<>();
    values.put(
        SoloLiquidAiDeterministicSkillCore.OPT_IN_ENV,
        SoloLiquidAiDeterministicSkillCore.OPT_IN_VALUE);
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

  private final class FakeSkillApi implements OllamaSkillShadowApi {
    private boolean initiallyLoaded;
    private boolean cleanupFails;
    private int invalidCall = -1;
    private String canary = "unused";
    private int warmupCalls;
    private int selectCalls;
    private int unloadCalls;
    private boolean warmed;
    private boolean unloaded;
    private boolean mutateSelectionSchema;

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
          initiallyLoaded,
          initiallyLoaded ? 1 : 0);
    }

    @Override
    public OllamaWarmupResult warmup() {
      warmupCalls++;
      warmed = true;
      unloaded = false;
      return new OllamaWarmupResult(1_000_000L, metrics());
    }

    @Override
    public OllamaSkillSelectionResult select(ObjectNode skillEvidence, ObjectNode selectionSchema) {
      int call = selectCalls++;
      if (mutateSelectionSchema) {
        selectionSchema.remove("required");
      }
      ObjectNode selection =
          json.createObjectNode().put("schemaVersion", ShadowDeterministicSkill.SELECTION_VERSION);
      JsonNode items = skillEvidence.path("items");
      selection.put("primaryItemOrdinal", items.isEmpty() ? -1 : 0);
      selection.putArray("topicObjectOrdinals");
      if (call == invalidCall) {
        selection.put("unexpectedCanary", canary);
      }
      return new OllamaSkillSelectionResult(
          selection,
          2_000_000L,
          metrics(),
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

    private OllamaApiMetrics metrics() {
      return new OllamaApiMetrics(2_000_000L, 0L, 30L, 500_000L, 8L, 1_000_000L);
    }
  }
}
