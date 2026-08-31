package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisRoute;
import local.personalmemo.analysis.domain.DeterministicAmbiguityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SoloLiquidAiShadowBaselineRunnerTest {
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void configurationFailsClosedUnlessEveryExactOptInAndBoundedHardwareValueIsPresent() {
    Map<String, String> valid = validEnvironment();
    SoloLiquidAiShadowBaselineRunner.ShadowConfiguration configuration =
        SoloLiquidAiShadowBaselineRunner.ShadowConfiguration.from(valid);

    assertThat(configuration.model()).isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_MODEL);
    assertThat(configuration.modelDigest())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_DIGEST);
    assertThat(configuration.gpuTotalMiB()).isEqualTo(12_288);
    assertThat(configuration.gpuBaselineUsedMiB()).isEqualTo(512);

    for (String required : valid.keySet()) {
      Map<String, String> missing = new HashMap<>(valid);
      missing.remove(required);
      assertThatThrownBy(() -> SoloLiquidAiShadowBaselineRunner.ShadowConfiguration.from(missing))
          .isInstanceOf(IllegalArgumentException.class);
    }

    Map<String, String> wrongOptIn = new HashMap<>(valid);
    wrongOptIn.put(SoloLiquidAiShadowBaselineRunner.OPT_IN_ENV, "true");
    assertThatThrownBy(() -> SoloLiquidAiShadowBaselineRunner.ShadowConfiguration.from(wrongOptIn))
        .isInstanceOf(IllegalArgumentException.class);

    Map<String, String> impossibleGpu = new HashMap<>(valid);
    impossibleGpu.put(SoloLiquidAiShadowBaselineRunner.GPU_BASELINE_USED_ENV, "20000");
    assertThatThrownBy(
            () -> SoloLiquidAiShadowBaselineRunner.ShadowConfiguration.from(impossibleGpu))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void modelOutputDiagnosticsUseOnlyFixedBoundedByteBuckets() {
    assertThat(ModelOutputSizeBucket.forBytes(0)).isEqualTo(ModelOutputSizeBucket.BYTES_0_TO_1024);
    assertThat(ModelOutputSizeBucket.forBytes(1_024))
        .isEqualTo(ModelOutputSizeBucket.BYTES_0_TO_1024);
    assertThat(ModelOutputSizeBucket.forBytes(1_025))
        .isEqualTo(ModelOutputSizeBucket.BYTES_1025_TO_4096);
    assertThat(ModelOutputSizeBucket.forBytes(4_097))
        .isEqualTo(ModelOutputSizeBucket.BYTES_4097_TO_16384);
    assertThat(ModelOutputSizeBucket.forBytes(16_385))
        .isEqualTo(ModelOutputSizeBucket.BYTES_16385_TO_65536);
    assertThatThrownBy(() -> ModelOutputSizeBucket.forBytes(65_537))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void v5AssemblerOwnsEnvelopeAndDerivesConsistentTypesReasonsAndNullOnlyFields() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-v5.json"));
    ObjectNode semantic = semanticOutput();
    semantic.put("title", "합성 복합 제안");
    semantic.withArray("topicLabels").add("합성 주제");
    addItem(semantic, "TASK", "첫 작업", "VALUE:확인", "UNRESOLVED");
    addItem(semantic, "INFORMATION", "둘째 정보", "ABSENT", "VALUE:합성 정보");
    addItem(semantic, "RECORD", "셋째 기록", "ABSENT", "VALUE:합성 기록");
    addDate(semantic, "다음 시기쯤", "APPROXIMATE");
    ((ObjectNode) semantic.path("reviewFlags")).put("lowTypeMargin", true);

    ObjectNode proposal =
        runner.assembleProposal(semantic, UUID.fromString("00000000-0000-0000-0000-000000000001"));

    assertThat(proposal.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(proposal.path("memoId").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(proposal.at("/suggestedTitle/confidence").asDouble()).isEqualTo(0.85);
    assertThat(proposal.at("/suggestedTitle/needsConfirmation").asBoolean()).isTrue();
    assertThat(proposal.at("/typeCandidates/0/value").asText()).isEqualTo("TASK");
    assertThat(proposal.at("/typeCandidates/0/score").asDouble()).isEqualTo(0.90);
    assertThat(proposal.at("/typeCandidates/1/value").asText()).isEqualTo("INFORMATION");
    assertThat(proposal.at("/typeCandidates/2/value").asText()).isEqualTo("RECORD");
    assertThat(proposal.at("/itemCandidates/0/sourceSpan").isNull()).isTrue();
    assertThat(proposal.at("/itemCandidates/0/dueDateCandidateId").isNull()).isTrue();
    assertThat(proposal.at("/itemCandidates/0/object").isNull()).isTrue();
    assertThat(proposal.at("/dateCandidates/0/value").isNull()).isTrue();
    assertThat(proposal.at("/dateCandidates/0/timeSpecified").asBoolean()).isFalse();
    assertThat(proposal.path("relationCandidates")).isEmpty();
    assertThat(proposal.path("ambiguityReasons").toString())
        .isEqualTo(
            "[\"LOW_TYPE_MARGIN\",\"NEW_TOPIC\",\"IMPRECISE_DATE\","
                + "\"UNRESOLVED_REFERENCE\",\"MULTI_INTENT\"]");
    assertThat(proposal.at("/providerMetadata/analyzerVersion").asText())
        .isEqualTo("ollama-shadow-v5");
    assertThat(proposal.at("/providerMetadata/toolCalls").asInt()).isZero();
  }

  @Test
  void v5AssemblerDistinguishesAbsentFromUnresolvedTaskSlots() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-state-v5.json"));
    ObjectNode unresolved = semanticOutput();
    addItem(unresolved, "TASK", "미확정 작업", "UNRESOLVED", "UNRESOLVED");

    ObjectNode unresolvedProposal =
        runner.assembleProposal(
            unresolved, UUID.fromString("00000000-0000-0000-0000-000000000008"));

    assertThat(unresolvedProposal.path("ambiguityReasons").toString())
        .isEqualTo("[\"UNRESOLVED_REFERENCE\"]");

    ObjectNode absent = semanticOutput();
    addItem(absent, "TASK", "누락 작업", "ABSENT", "ABSENT");

    ObjectNode absentProposal =
        runner.assembleProposal(absent, UUID.fromString("00000000-0000-0000-0000-000000000009"));

    assertThat(absentProposal.path("ambiguityReasons").toString())
        .isEqualTo("[\"MISSING_ACTION\",\"MISSING_OBJECT\"]");
  }

  @Test
  void v5AssemblerTreatsValueSentinelTextLiterallyAndRejectsInvalidSlotEncodings() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-slot-encoding-v5.json"));
    ObjectNode literalSentinel = semanticOutput();
    addItem(literalSentinel, "TASK", "리터럴 값", "VALUE:ABSENT", "VALUE:UNRESOLVED");

    ObjectNode proposal =
        runner.assembleProposal(
            literalSentinel, UUID.fromString("00000000-0000-0000-0000-000000000010"));

    assertThat(proposal.at("/itemCandidates/0/action").asText()).isEqualTo("ABSENT");
    assertThat(proposal.at("/itemCandidates/0/object").asText()).isEqualTo("UNRESOLVED");
    assertThat(proposal.path("ambiguityReasons")).isEmpty();

    for (String invalid :
        new String[] {
          "", "VALUE:", "bare value", " ABSENT", "ABSENT ", "VALUE: 앞 공백", "VALUE:뒤 공백 "
        }) {
      ObjectNode invalidSlot = taskSemantic();
      ((ObjectNode) invalidSlot.at("/items/0")).put("actionSlot", invalid);
      assertSemanticIrInvalid(runner, invalidSlot, SemanticIrFailureCode.SLOT_ENCODING_INVALID);
    }

    ObjectNode overlong = taskSemantic();
    ((ObjectNode) overlong.at("/items/0")).put("actionSlot", "VALUE:" + "가".repeat(201));
    assertSemanticIrInvalid(runner, overlong, SemanticIrFailureCode.SLOT_ENCODING_INVALID);
  }

  @Test
  void v5AssemblerDerivesTypesFromFirstSeenItemKindsWithDedupeAndUnknownFallback() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-types-v5.json"));
    ObjectNode semantic = semanticOutput();
    addItem(semantic, "INFORMATION", "첫 정보", "ABSENT", "VALUE:정보");
    addItem(semantic, "TASK", "둘째 작업", "VALUE:확인", "VALUE:대상");
    addItem(semantic, "INFORMATION", "셋째 정보", "ABSENT", "VALUE:다른 정보");

    ObjectNode proposal =
        runner.assembleProposal(semantic, UUID.fromString("00000000-0000-0000-0000-000000000011"));

    assertThat(proposal.path("typeCandidates")).hasSize(2);
    assertThat(proposal.at("/typeCandidates/0/value").asText()).isEqualTo("INFORMATION");
    assertThat(proposal.at("/typeCandidates/0/score").asDouble()).isEqualTo(0.90);
    assertThat(proposal.at("/typeCandidates/1/value").asText()).isEqualTo("TASK");
    assertThat(proposal.at("/typeCandidates/1/score").asDouble()).isEqualTo(0.70);

    ObjectNode emptyProposal =
        runner.assembleProposal(
            semanticOutput(), UUID.fromString("00000000-0000-0000-0000-000000000012"));
    assertThat(emptyProposal.path("typeCandidates")).hasSize(1);
    assertThat(emptyProposal.at("/typeCandidates/0/value").asText()).isEqualTo("UNKNOWN");
    assertThat(emptyProposal.at("/typeCandidates/0/score").asDouble()).isEqualTo(1.0);
  }

  @Test
  void v5AssemblerParsesEveryAtomicDateVariantWithoutRepair() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-dates-v5.json"));
    ObjectNode semantic = semanticOutput();
    addDate(semantic, "합성 날짜", "DATE_ONLY:2026-08-15|COMPLETE");
    addDate(semantic, "합성 정확 시각", "EXACT_TIME:2026-08-15T09:30:00+09:00|COMPLETE");
    addDate(semantic, "합성 상대 시각", "RELATIVE_EXACT:2026-08-16T10:00:00+09:00|COMPLETE");
    addDate(semantic, "합성 무렵", "APPROXIMATE");
    addDate(semantic, "합성 미확정 시점", "UNKNOWN");

    ObjectNode proposal =
        runner.assembleProposal(semantic, UUID.fromString("00000000-0000-0000-0000-000000000004"));

    assertThat(proposal.at("/dateCandidates/0/value").asText()).isEqualTo("2026-08-15");
    assertThat(proposal.at("/dateCandidates/0/timeSpecified").asBoolean()).isFalse();
    assertThat(proposal.at("/dateCandidates/0/ambiguityReasons")).isEmpty();
    assertThat(proposal.at("/dateCandidates/1/precision").asText()).isEqualTo("EXACT_TIME");
    assertThat(proposal.at("/dateCandidates/1/value").asText())
        .isEqualTo("2026-08-15T09:30:00+09:00");
    assertThat(proposal.at("/dateCandidates/1/timeSpecified").asBoolean()).isTrue();
    assertThat(proposal.at("/dateCandidates/2/precision").asText()).isEqualTo("RELATIVE_EXACT");
    assertThat(proposal.at("/dateCandidates/2/value").asText())
        .isEqualTo("2026-08-16T10:00:00+09:00");
    assertThat(proposal.at("/dateCandidates/2/timeSpecified").asBoolean()).isTrue();
    assertThat(proposal.at("/dateCandidates/3/value").isNull()).isTrue();
    assertThat(proposal.at("/dateCandidates/3/ambiguityReasons").toString())
        .contains("IMPRECISE_DATE");
    assertThat(proposal.at("/dateCandidates/4/value").isNull()).isTrue();
    assertThat(proposal.at("/dateCandidates/4/ambiguityReasons").toString())
        .contains("IMPRECISE_DATE", "UNRESOLVED_REFERENCE");
    assertThat(proposal.path("ambiguityReasons").toString())
        .contains("IMPRECISE_DATE", "UNRESOLVED_REFERENCE");
  }

  @Test
  void v5DateOnlyComponentStatusPreservesNestedAndRootReasonsAndLocalReviewRoute() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-date-component-status-v5.json"));
    String[] statuses = {"COMPLETE", "MISSING_YEAR", "MISSING_TIME", "MISSING_YEAR_AND_TIME"};
    String[] expectedReasons = {
      "[]", "[\"MISSING_YEAR\"]", "[\"MISSING_TIME\"]", "[\"MISSING_YEAR\",\"MISSING_TIME\"]"
    };
    ObjectNode bothMissingProposal = null;

    for (int index = 0; index < statuses.length; index++) {
      ObjectNode semantic = semanticOutput();
      addItem(semantic, "EVENT", "합성 행사", "ABSENT", "VALUE:합성 행사");
      addDate(semantic, "연도와 시간 없는 합성 날짜", "DATE_ONLY:2026-11-04|" + statuses[index]);

      ObjectNode proposal =
          runner.assembleProposal(
              semantic, UUID.fromString("00000000-0000-0000-0000-000000000013"));

      assertThat(proposal.at("/dateCandidates/0/ambiguityReasons").toString())
          .isEqualTo(expectedReasons[index]);
      assertThat(proposal.path("ambiguityReasons").toString()).isEqualTo(expectedReasons[index]);
      if ("MISSING_YEAR_AND_TIME".equals(statuses[index])) {
        bothMissingProposal = proposal;
      }
    }

    assertThat(bothMissingProposal).isNotNull();
    DeterministicAmbiguityGate gate = new DeterministicAmbiguityGate();
    assertThat(gate.routingSignals(bothMissingProposal))
        .containsExactly(AmbiguityReason.MISSING_YEAR, AmbiguityReason.MISSING_TIME);
    assertThat(gate.route(gate.routingSignals(bothMissingProposal)))
        .isEqualTo(AnalysisRoute.LOCAL_REVIEW);
  }

  @Test
  void v5AssemblerRejectsCoverageAndSemanticContradictionsInsteadOfRepairingThem() {
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            new FakeShadowApi(false, -1),
            temporaryDirectory.resolve("unused-invalid-v5.json"));

    ObjectNode validOverflow = taskSemantic();
    addItem(validOverflow, "TASK", "둘", "VALUE:확인", "VALUE:둘");
    addItem(validOverflow, "TASK", "셋", "VALUE:확인", "VALUE:셋");
    validOverflow.put("itemCoverage", "MORE_THAN_THREE");
    assertThat(
            runner
                .assembleProposal(
                    validOverflow, UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .path("ambiguityReasons")
                .toString())
        .contains("CANDIDATE_LIMIT_EXCEEDED", "MULTI_INTENT");

    ObjectNode overflowContradiction = taskSemantic();
    overflowContradiction.put("itemCoverage", "MORE_THAN_THREE");
    assertSemanticIrInvalid(
        runner, overflowContradiction, SemanticIrFailureCode.ITEM_COVERAGE_CONTRADICTION);

    ObjectNode completeAtCap = validOverflow.deepCopy();
    completeAtCap.put("itemCoverage", "COMPLETE");
    assertThat(
            runner
                .assembleProposal(
                    completeAtCap, UUID.fromString("00000000-0000-0000-0000-000000000006"))
                .path("ambiguityReasons")
                .toString())
        .contains("MULTI_INTENT")
        .doesNotContain("CANDIDATE_LIMIT_EXCEEDED");

    ObjectNode overfull = taskSemantic();
    addItem(overfull, "TASK", "둘", "VALUE:확인", "VALUE:둘");
    addItem(overfull, "TASK", "셋", "VALUE:확인", "VALUE:셋");
    addItem(overfull, "TASK", "넷", "VALUE:확인", "VALUE:넷");
    overfull.put("itemCoverage", "MORE_THAN_THREE");
    assertSemanticIrInvalid(runner, overfull, SemanticIrFailureCode.ARRAY_BOUND_EXCEEDED);

    ObjectNode dateCoverageOverflow = semanticOutput();
    for (int index = 0; index < 5; index++) {
      addDate(
          dateCoverageOverflow,
          "합성 날짜 " + index,
          "DATE_ONLY:2026-08-" + (20 + index) + "|COMPLETE");
    }
    dateCoverageOverflow.put("dateCoverage", "MORE_THAN_FIVE");
    assertThat(
            runner
                .assembleProposal(
                    dateCoverageOverflow, UUID.fromString("00000000-0000-0000-0000-000000000007"))
                .path("ambiguityReasons")
                .toString())
        .contains("CANDIDATE_LIMIT_EXCEEDED");
    ObjectNode dateCoverageContradiction = dateCoverageOverflow.deepCopy();
    dateCoverageContradiction.withArray("dates").remove(4);
    assertSemanticIrInvalid(
        runner, dateCoverageContradiction, SemanticIrFailureCode.DATE_COVERAGE_CONTRADICTION);

    ObjectNode duplicateTopic = semanticOutput();
    duplicateTopic.withArray("topicLabels").add("중복").add("중복");
    assertSemanticIrInvalid(runner, duplicateTopic, SemanticIrFailureCode.TOPIC_CONTRADICTION);

    ObjectNode emptyValue = taskSemantic();
    ((ObjectNode) emptyValue.at("/items/0")).put("actionSlot", "VALUE:");
    assertSemanticIrInvalid(runner, emptyValue, SemanticIrFailureCode.SLOT_ENCODING_INVALID);

    ObjectNode nonemptyAbsent = taskSemantic();
    ((ObjectNode) nonemptyAbsent.at("/items/0")).put("objectSlot", "ABSENT:금지된 값");
    assertSemanticIrInvalid(runner, nonemptyAbsent, SemanticIrFailureCode.SLOT_ENCODING_INVALID);

    ObjectNode nonTaskAction = taskSemantic();
    ((ObjectNode) nonTaskAction.at("/items/0")).put("kind", "RECORD");
    assertSemanticIrInvalid(
        runner, nonTaskAction, SemanticIrFailureCode.NON_TASK_ACTION_CONTRADICTION);

    ObjectNode impreciseValue = semanticOutput();
    addDate(impreciseValue, "모호한 때", "APPROXIMATE:2026-08-14");
    assertSemanticIrInvalid(runner, impreciseValue, SemanticIrFailureCode.DATE_CONTRADICTION);

    ObjectNode missingOffset = semanticOutput();
    addDate(missingOffset, "합성 시각", "EXACT_TIME:2026-08-14T09:00:00|COMPLETE");
    assertSemanticIrInvalid(runner, missingOffset, SemanticIrFailureCode.DATE_CONTRADICTION);

    for (String invalid :
        new String[] {
          "",
          "DATE_ONLY",
          "DATE_ONLY:2026-08-14",
          "DATE_ONLY:2026-08-14|GUESSED",
          " DATE_ONLY:2026-08-14|COMPLETE",
          "DATE_ONLY:2026-08-14|COMPLETE ",
          "EXACT_TIME:2026-08-14T09:00:00+09:00|MISSING_TIME",
          "RELATIVE_EXACT:2026-08-14T09:00:00+09:00|MISSING_YEAR"
        }) {
      ObjectNode invalidDate = semanticOutput();
      addDate(invalidDate, "합성 날짜", invalid);
      assertSemanticIrInvalid(runner, invalidDate, SemanticIrFailureCode.DATE_CONTRADICTION);
    }

    ObjectNode overlongDate = semanticOutput();
    addDate(overlongDate, "합성 날짜", "RELATIVE_EXACT:" + "0".repeat(210));
    assertSemanticIrInvalid(runner, overlongDate, SemanticIrFailureCode.DATE_CONTRADICTION);

    ObjectNode lowTypeWithoutAlternative = taskSemantic();
    ((ObjectNode) lowTypeWithoutAlternative.path("reviewFlags")).put("lowTypeMargin", true);
    assertSemanticIrInvalid(
        runner, lowTypeWithoutAlternative, SemanticIrFailureCode.REVIEW_FLAG_CONTRADICTION);

    ObjectNode conflictWithoutTwoDates = semanticOutput();
    ((ObjectNode) conflictWithoutTwoDates.path("reviewFlags")).put("conflictingDates", true);
    assertSemanticIrInvalid(
        runner, conflictWithoutTwoDates, SemanticIrFailureCode.REVIEW_FLAG_CONTRADICTION);

    ObjectNode firstViolationWins = semanticOutput().put("title", "  ");
    firstViolationWins.put("itemCoverage", "MORE_THAN_THREE");
    assertSemanticIrInvalid(runner, firstViolationWins, SemanticIrFailureCode.TEXT_INVALID);
  }

  @Test
  void writesV5AggregateOnlyReportPreservesV1ThroughV4AndLabelsDevelopmentEvidence()
      throws Exception {
    Path v1ReportPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline.json");
    Path v2ReportPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline-v2.json");
    Path v3ReportPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline-v3.json");
    Path v4ReportPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline-v4.json");
    Path reportPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline-v5.json");
    Path temporaryPath = temporaryDirectory.resolve("solo-liquidai-shadow-baseline-v5.json.tmp");
    Files.writeString(v1ReportPath, "preserved-v1", StandardCharsets.UTF_8);
    Files.writeString(v2ReportPath, "preserved-v2", StandardCharsets.UTF_8);
    Files.writeString(v3ReportPath, "preserved-v3", StandardCharsets.UTF_8);
    Files.writeString(v4ReportPath, "preserved-v4", StandardCharsets.UTF_8);
    Files.writeString(reportPath, "stale", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeShadowApi api = new FakeShadowApi(false, -1);
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(api.warmupCount).isEqualTo(1);
    assertThat(api.analyzeCount).isEqualTo(24);
    assertThat(api.unloadCount).isEqualTo(1);
    assertThat(api.allocationCount).isEqualTo(3);
    assertThat(report.at("/reportVersion").asText()).isEqualTo("5");
    assertThat(report.at("/status").asText()).isEqualTo("SOLO_PROVISIONAL");
    assertThat(report.at("/decisionUse").asText()).isEqualTo("REPORT_ONLY");
    assertThat(report.at("/metricGateStatus").asText()).isEqualTo("NOT_CONFIGURED");
    assertThat(report.at("/providerAuthorization").asText()).isEqualTo("NOT_AUTHORIZED");
    assertThat(report.at("/trainingStatus").asText()).isEqualTo("NOT_PERFORMED");
    assertThat(report.at("/fineTuning/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/fineTuning/loraDecision").asText()).isEqualTo("NO_GO");
    assertThat(report.at("/dataset/developmentSetRole").asText())
        .isEqualTo("PUBLIC_VISIBLE_PROMPT_SCHEMA_DEVELOPMENT_ONLY");
    assertThat(report.at("/model/analyzerVersion").asText()).isEqualTo("ollama-shadow-v5");
    assertThat(report.at("/model/promptVersion").asText())
        .isEqualTo("solo-liquidai-shadow-prompt-v5");
    assertThat(report.at("/comparisonReferences/v1/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.V1_REPORT_SHA256);
    assertThat(report.at("/comparisonReferences/v2/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.V2_REPORT_SHA256);
    assertThat(report.at("/comparisonReferences/v3/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.V3_REPORT_SHA256);
    assertThat(report.at("/comparisonReferences/v3/artifactName").asText())
        .isEqualTo("solo-liquidai-shadow-baseline-v3.json");
    assertThat(report.at("/comparisonReferences/v4/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.V4_REPORT_SHA256)
        .isEqualTo("ce95d1c3a765ffd6805a1062b8cfa26e476f0f1c8dc3cf843407b856a17741f5");
    assertThat(report.at("/comparisonReferences/v4/artifactName").asText())
        .isEqualTo("solo-liquidai-shadow-baseline-v4.json");
    assertThat(report.at("/comparisonReferences/v2/use").asText())
        .isEqualTo("DESCRIPTIVE_ONLY_NOT_AN_ACCEPTANCE_GATE");
    assertThat(report.at("/resourceIntegrity/regressionFixture/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_REGRESSION_SHA256);
    assertThat(report.at("/resourceIntegrity/visibleChallengeFixture/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_CHALLENGE_SHA256);
    assertThat(report.at("/resourceIntegrity/evaluationCaseSchema/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_CASE_SCHEMA_SHA256);
    assertThat(report.at("/resourceIntegrity/canonicalProposalSchema/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_CANONICAL_PROPOSAL_SCHEMA_SHA256);
    assertThat(report.at("/resourceIntegrity/inferenceOutputSchema/sha256").asText())
        .isEqualTo(SoloLiquidAiShadowBaselineRunner.EXPECTED_OUTPUT_SCHEMA_SHA256);
    assertThat(report.at("/capabilities/dateItemDueBinding").asText())
        .isEqualTo("DISABLED_NULL_ONLY_IN_SHADOW_V5");
    assertThat(report.at("/capabilities/itemSourceSpan").asText())
        .isEqualTo("DISABLED_NULL_ONLY_IN_SHADOW_V5");
    assertThat(report.at("/capabilities/confidenceScores").asText())
        .isEqualTo("NOT_MODEL_CALIBRATED");
    assertThat(report.at("/execution/warmupRequestCount").asInt()).isEqualTo(1);
    assertThat(report.at("/execution/scoredRequestCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/scoredResponseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/inferenceSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/semanticIRValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/fake/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/liquidAi/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/liquidAi/all/schemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/liquidAi/all/domainValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/failureCounts/total").asInt()).isZero();
    assertThat(report.at("/failureCounts/uniqueFailedCaseCount").asInt()).isZero();
    assertThat(report.at("/failureCounts/overlappingFailureObservationCount").asInt()).isZero();
    assertThat(report.at("/failureCounts/semanticIrFirstViolation/sampleCount").asInt()).isZero();
    assertThat(report.at("/failureCounts/semanticIrFirstViolation/byCode").size())
        .isEqualTo(SemanticIrFailureCode.values().length);
    assertThat(report.at("/performance/liquidAiAllAttemptWallLatency/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/performance/liquidAiSuccessfulResponseWallLatency/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/performance/modelOutputSizeBuckets/sampleCount").asInt()).isEqualTo(24);
    assertThat(sumObjectValues(report.at("/performance/modelOutputSizeBuckets/byBucket")))
        .isEqualTo(24);
    assertThat(report.at("/hardware/gpu/peakUsedStatus").asText()).isEqualTo("NOT_AVAILABLE");
    assertThat(report.at("/hardware/gpu/peakUsedMiB").isNull()).isTrue();
    assertThat(report.at("/hardware/gpu/utilizationStatus").asText()).isEqualTo("NOT_AVAILABLE");
    assertThat(report.at("/hardware/ollamaObservedAllocation/sizeVramBytes").asLong())
        .isEqualTo(3_000_000_000L);
    assertThat(report.at("/hardware/ollamaObservedAllocation/contextLength").asLong())
        .isEqualTo(8_192);
    assertThat(report.at("/restoration/status").asText()).isEqualTo("RESTORED");
    assertThat(report.at("/restoration/scopedRunnerTemporaryArtifactRemaining").asBoolean())
        .isFalse();
    assertThat(report.at("/developmentAcceptance/status").asText()).isEqualTo("NOT_MET");
    assertThat(report.at("/developmentAcceptance/status").asText()).isNotEqualTo("PASS");
    assertThat(report.has("cases")).isFalse();
    assertThat(report.findValue("path")).isNull();
    assertThat(report.findValue("caseId")).isNull();
    assertThat(report.findValue("memoId")).isNull();
    assertThat(Files.size(reportPath)).isLessThanOrEqualTo(512L * 1024L);
    assertThat(temporaryPath).doesNotExist();
    assertThat(Files.readString(v1ReportPath, StandardCharsets.UTF_8)).isEqualTo("preserved-v1");
    assertThat(Files.readString(v2ReportPath, StandardCharsets.UTF_8)).isEqualTo("preserved-v2");
    assertThat(Files.readString(v3ReportPath, StandardCharsets.UTF_8)).isEqualTo("preserved-v3");
    assertThat(Files.readString(v4ReportPath, StandardCharsets.UTF_8)).isEqualTo("preserved-v4");
    assertReportContainsNoFixturePayload(reportPath);
  }

  @Test
  void keepsTwentyFourAttemptLatencyDenominatorWhenOneCallFailsAndSeparatesSuccessLatency()
      throws Exception {
    Path reportPath = temporaryDirectory.resolve("partial-report.json");
    FakeShadowApi api = new FakeShadowApi(false, 0);
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/scoredRequestCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/scoredResponseCount").asInt()).isEqualTo(23);
    assertThat(report.at("/quality/liquidAi/all/caseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/quality/liquidAi/all/schemaValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/quality/liquidAi/all/domainValidCount").asInt()).isEqualTo(23);
    assertThat(report.at("/performance/liquidAiAllAttemptWallLatency/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(report.at("/performance/liquidAiSuccessfulResponseWallLatency/sampleCount").asInt())
        .isEqualTo(23);
    assertThat(report.at("/performance/modelOutputSizeBuckets/sampleCount").asInt()).isEqualTo(23);
    assertThat(sumObjectValues(report.at("/performance/modelOutputSizeBuckets/byBucket")))
        .isEqualTo(23);
    assertThat(report.at("/failureCounts/byReason/TIMEOUT").asInt()).isEqualTo(1);
    assertThat(report.at("/failureCounts/byReason/CANONICAL_SCHEMA_INVALID").asInt()).isEqualTo(1);
    assertThat(report.at("/failureCounts/byReason/DOMAIN_INVALID").asInt()).isEqualTo(1);
    assertThat(report.at("/failureCounts/total").asInt()).isEqualTo(3);
    assertThat(report.at("/failureCounts/uniqueFailedCaseCount").asInt()).isEqualTo(1);
    assertThat(report.at("/failureCounts/overlappingFailureObservationCount").asInt()).isEqualTo(2);
    assertThat(report.at("/fineTuning/decision").asText()).isEqualTo("NO_GO_FOR_TRAINING");
    assertThat(report.at("/fineTuning/promptSchemaIterationRecommendation").asText())
        .isEqualTo("RECOMMENDED");
    assertThat(report.at("/restoration/restored").asBoolean()).isTrue();
    assertThat(report.at("/developmentAcceptance/status").asText()).isEqualTo("NOT_MET");
  }

  @Test
  void reportsSemanticIrFailuresAsAggregateOverlappingObservationsWithoutRawOutput()
      throws Exception {
    Path reportPath = temporaryDirectory.resolve("semantic-ir-failure-v5.json");
    FakeShadowApi api = new FakeShadowApi(false, -1);
    api.semanticOutputOverride = semanticOutput().put("itemCoverage", "MORE_THAN_THREE");
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    ObjectNode report = runner.execute();

    assertThat(report.at("/execution/inferenceSchemaValidCount").asInt()).isEqualTo(24);
    assertThat(report.at("/execution/semanticIRValidCount").asInt()).isZero();
    assertThat(report.at("/failureCounts/byReason/SEMANTIC_IR_INVALID").asInt()).isEqualTo(24);
    assertThat(report.at("/failureCounts/byReason/CANONICAL_SCHEMA_INVALID").asInt()).isEqualTo(24);
    assertThat(report.at("/failureCounts/byReason/DOMAIN_INVALID").asInt()).isEqualTo(24);
    assertThat(report.at("/failureCounts/failureObservationCount").asInt()).isEqualTo(72);
    assertThat(report.at("/failureCounts/uniqueFailedCaseCount").asInt()).isEqualTo(24);
    assertThat(report.at("/failureCounts/overlappingFailureObservationCount").asInt())
        .isEqualTo(48);
    assertThat(report.at("/failureCounts/semanticIrFirstViolation/sampleCount").asInt())
        .isEqualTo(24);
    assertThat(
            report
                .at(
                    "/failureCounts/semanticIrFirstViolation/byCode/"
                        + SemanticIrFailureCode.ITEM_COVERAGE_CONTRADICTION.name())
                .asInt())
        .isEqualTo(24);
    assertThat(sumObjectValues(report.at("/failureCounts/semanticIrFirstViolation/byCode")))
        .isEqualTo(24);
    assertThat(report.toString())
        .doesNotContain("itemCoverage", "MORE_THAN_THREE", "semanticOutput", "합성 제안", "message");
    assertReportContainsNoFixturePayload(reportPath);
  }

  @Test
  void rejectsAnyInitiallyLoadedModelBeforeWarmupAndRemainsOutsideDefaultTestNamePatterns() {
    FakeShadowApi api = new FakeShadowApi(true, -1);
    Path reportPath = temporaryDirectory.resolve("preloaded-report.json");
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("clean unloaded-model prestate");

    assertThat(api.warmupCount).isZero();
    assertThat(api.unloadCount).isZero();
    assertThat(reportPath).doesNotExist();
    assertThat(SoloLiquidAiShadowBaselineRunner.class.getSimpleName())
        .doesNotEndWith("Test")
        .doesNotEndWith("Tests")
        .doesNotEndWith("TestCase")
        .doesNotStartWith("Test");
  }

  @Test
  void cleanupFailurePublishesNeitherV5ReportNorScopedTemporaryArtifact() throws Exception {
    Path reportPath = temporaryDirectory.resolve("cleanup-failure-v5.json");
    Path temporaryPath = temporaryDirectory.resolve("cleanup-failure-v5.json.tmp");
    Files.writeString(reportPath, "stale-v5", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeShadowApi api = new FakeShadowApi(false, -1, true);
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restoration failed before reporting");

    assertThat(api.analyzeCount).isEqualTo(24);
    assertThat(api.unloadCount).isEqualTo(1);
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  @Test
  void structuralOllamaFailureStopsAfterFirstCallAndPublishesNoReport() throws Exception {
    Path reportPath = temporaryDirectory.resolve("structural-failure-v5.json");
    Path temporaryPath = temporaryDirectory.resolve("structural-failure-v5.json.tmp");
    Files.writeString(reportPath, "stale-v5", StandardCharsets.UTF_8);
    Files.writeString(temporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeShadowApi api = new FakeShadowApi(false, 0, false, OllamaShadowFailure.HTTP_STATUS);
    SoloLiquidAiShadowBaselineCore runner =
        new SoloLiquidAiShadowBaselineCore(validEnvironment(), api, reportPath);

    assertThatThrownBy(runner::execute)
        .isInstanceOf(OllamaShadowException.class)
        .hasMessage("Ollama shadow request failed: HTTP_STATUS");

    assertThat(api.analyzeCount).isEqualTo(1);
    assertThat(api.unloadCount).isEqualTo(1);
    assertThat(reportPath).doesNotExist();
    assertThat(temporaryPath).doesNotExist();
  }

  @Test
  void rejectsPinnedResourceDriftBeforePreflightAndUsesStrictDevelopmentAcceptance()
      throws Exception {
    assertThatThrownBy(
            () ->
                SoloLiquidAiShadowBaselineRunner.verifyPinnedSha256(
                    "regression fixture",
                    "drift".getBytes(StandardCharsets.UTF_8),
                    SoloLiquidAiShadowBaselineRunner.EXPECTED_REGRESSION_SHA256))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Pinned SHA-256 changed");

    Path driftReportPath = temporaryDirectory.resolve("resource-drift-v5.json");
    Path driftTemporaryPath = temporaryDirectory.resolve("resource-drift-v5.json.tmp");
    Files.writeString(driftReportPath, "stale-v5", StandardCharsets.UTF_8);
    Files.writeString(driftTemporaryPath, "stale-temp", StandardCharsets.UTF_8);
    FakeShadowApi driftApi = new FakeShadowApi(false, -1);
    SoloLiquidAiShadowBaselineCore driftRunner =
        new SoloLiquidAiShadowBaselineCore(
            validEnvironment(),
            driftApi,
            driftReportPath,
            Map.of(
                "/contracts/solo-liquidai-shadow-output.schema.json",
                "{}".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(driftRunner::execute)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Pinned SHA-256 changed for inference output schema");
    assertThat(driftApi.preflightCount).isZero();
    assertThat(driftApi.warmupCount).isZero();
    assertThat(driftApi.analyzeCount).isZero();
    assertThat(driftReportPath).doesNotExist();
    assertThat(driftTemporaryPath).doesNotExist();

    assertThat(
            SoloLiquidAiShadowBaselineRunner.developmentAcceptanceMet(
                24, 24, 24, 24, 24, 24, 0, 0, 0, 0, 0, 0, true, true))
        .isTrue();
    assertThat(
            SoloLiquidAiShadowBaselineRunner.developmentAcceptanceMet(
                24, 23, 24, 24, 24, 24, 0, 0, 0, 0, 0, 0, true, true))
        .isFalse();
    assertThat(
            SoloLiquidAiShadowBaselineRunner.developmentAcceptanceMet(
                24, 24, 24, 24, 24, 24, 0, 1, 0, 0, 0, 0, true, true))
        .isFalse();
    assertThat(
            SoloLiquidAiShadowBaselineRunner.developmentAcceptanceMet(
                24, 24, 24, 24, 24, 24, 0, 0, 0, 0, 0, 0, false, true))
        .isFalse();
    assertThat(
            SoloLiquidAiShadowBaselineRunner.developmentAcceptanceMet(
                24, 24, 24, 24, 24, 24, 0, 0, 0, 0, 0, 0, true, false))
        .isFalse();
  }

  private void assertReportContainsNoFixturePayload(Path reportPath) throws Exception {
    String report = Files.readString(reportPath, StandardCharsets.UTF_8);
    for (String resource :
        new String[] {
          "/fixtures/korean-memo-cases.json", "/fixtures/korean-memo-challenge-cases.json"
        }) {
      JsonNode fixtures = readResource(resource);
      for (JsonNode fixture : fixtures) {
        assertThat(report).doesNotContain(fixture.path("id").asText());
        assertThat(report).doesNotContain(fixture.path("content").asText());
        if (fixture.path("notes").isTextual()) {
          assertThat(report).doesNotContain(fixture.path("notes").asText());
        }
        for (JsonNode date : fixture.at("/expectedDates/mentions")) {
          assertThat(report).doesNotContain(date.path("surfaceText").asText());
        }
      }
    }
  }

  private int sumObjectValues(JsonNode object) {
    int total = 0;
    for (JsonNode value : object) {
      total += value.asInt();
    }
    return total;
  }

  private JsonNode readResource(String resource) throws Exception {
    try (InputStream input = getClass().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Missing fixture resource.");
      }
      return json.readTree(input);
    }
  }

  private Map<String, String> validEnvironment() {
    Map<String, String> values = new HashMap<>();
    values.put(
        SoloLiquidAiShadowBaselineRunner.OPT_IN_ENV, SoloLiquidAiShadowBaselineRunner.OPT_IN_VALUE);
    values.put(
        SoloLiquidAiShadowBaselineRunner.MODEL_ENV,
        SoloLiquidAiShadowBaselineRunner.EXPECTED_MODEL);
    values.put(
        SoloLiquidAiShadowBaselineRunner.DIGEST_ENV,
        SoloLiquidAiShadowBaselineRunner.EXPECTED_DIGEST);
    values.put(SoloLiquidAiShadowBaselineRunner.BASE_HEAD_ENV, "a".repeat(40));
    values.put(SoloLiquidAiShadowBaselineRunner.SOURCE_BUNDLE_ENV, "b".repeat(64));
    values.put(SoloLiquidAiShadowBaselineRunner.GPU_NAME_ENV, "NVIDIA Test GPU");
    values.put(SoloLiquidAiShadowBaselineRunner.GPU_DRIVER_ENV, "999.99");
    values.put(SoloLiquidAiShadowBaselineRunner.GPU_TOTAL_ENV, "12288");
    values.put(SoloLiquidAiShadowBaselineRunner.GPU_BASELINE_USED_ENV, "512");
    return Map.copyOf(values);
  }

  private void assertSemanticIrInvalid(
      SoloLiquidAiShadowBaselineCore runner, ObjectNode semanticOutput) {
    assertSemanticIrInvalid(runner, semanticOutput, null);
  }

  private void assertSemanticIrInvalid(
      SoloLiquidAiShadowBaselineCore runner,
      ObjectNode semanticOutput,
      SemanticIrFailureCode expectedCode) {
    assertThatThrownBy(
            () ->
                runner.assembleProposal(
                    semanticOutput, UUID.fromString("00000000-0000-0000-0000-000000000002")))
        .isInstanceOfSatisfying(
            SemanticIrException.class,
            exception -> {
              assertThat(exception.getMessage()).isEqualTo("Semantic IR validation failed.");
              assertThat(exception.getCause()).isNull();
              if (expectedCode != null) {
                assertThat(exception.code()).isEqualTo(expectedCode);
              }
              assertThat(exception.getMessage()).doesNotContain(semanticOutput.toString());
            });
  }

  private ObjectNode taskSemantic() {
    ObjectNode semantic = semanticOutput();
    addItem(semantic, "TASK", "첫 작업", "VALUE:확인", "VALUE:대상");
    return semantic;
  }

  private void addItem(
      ObjectNode semantic, String kind, String title, String actionSlot, String objectSlot) {
    semantic
        .withArray("items")
        .addObject()
        .put("kind", kind)
        .put("title", title)
        .put("actionSlot", actionSlot)
        .put("objectSlot", objectSlot);
  }

  private void addDate(ObjectNode semantic, String surfaceText, String interpretation) {
    semantic
        .withArray("dates")
        .addObject()
        .put("surfaceText", surfaceText)
        .put("interpretation", interpretation);
  }

  private ObjectNode semanticOutput() {
    ObjectNode value = json.createObjectNode();
    value.put("title", "합성 제안");
    value.putArray("topicLabels");
    value.put("itemCoverage", "COMPLETE");
    value.putArray("items");
    value.put("dateCoverage", "COMPLETE");
    value.putArray("dates");
    value.putObject("reviewFlags").put("lowTypeMargin", false).put("conflictingDates", false);
    return value;
  }

  private final class FakeShadowApi implements OllamaShadowApi {
    private final boolean initiallyLoaded;
    private final int failingCall;
    private final boolean cleanupFails;
    private final OllamaShadowFailure failingFailure;
    private int warmupCount;
    private int preflightCount;
    private int analyzeCount;
    private int allocationCount;
    private int unloadCount;
    private boolean unloaded;
    private ObjectNode semanticOutputOverride;

    private FakeShadowApi(boolean initiallyLoaded, int failingCall) {
      this(initiallyLoaded, failingCall, false, OllamaShadowFailure.TIMEOUT);
    }

    private FakeShadowApi(boolean initiallyLoaded, int failingCall, boolean cleanupFails) {
      this(initiallyLoaded, failingCall, cleanupFails, OllamaShadowFailure.TIMEOUT);
    }

    private FakeShadowApi(
        boolean initiallyLoaded,
        int failingCall,
        boolean cleanupFails,
        OllamaShadowFailure failingFailure) {
      this.initiallyLoaded = initiallyLoaded;
      this.failingCall = failingCall;
      this.cleanupFails = cleanupFails;
      this.failingFailure = failingFailure;
    }

    @Override
    public OllamaModelPreflight preflight() {
      preflightCount++;
      return new OllamaModelPreflight(
          "0.32.7",
          SoloLiquidAiShadowBaselineRunner.EXPECTED_MODEL,
          SoloLiquidAiShadowBaselineRunner.EXPECTED_DIGEST,
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
      warmupCount++;
      unloaded = false;
      return new OllamaWarmupResult(10_000_000L, metrics());
    }

    @Override
    public OllamaShadowResult analyze(
        String memoText, String baseInstant, String timeZone, ObjectNode outputSchema) {
      int call = analyzeCount++;
      if (call == failingCall) {
        throw new OllamaShadowException(failingFailure, null);
      }
      ObjectNode output =
          semanticOutputOverride == null ? semanticOutput() : semanticOutputOverride.deepCopy();
      return new OllamaShadowResult(
          output, 5_000_000L, metrics(), output.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    @Override
    public OllamaObservedAllocation allocation() {
      allocationCount++;
      return unloaded && !cleanupFails
          ? OllamaObservedAllocation.notLoaded()
          : new OllamaObservedAllocation(true, 3_100_000_000L, 3_000_000_000L, 8_192);
    }

    @Override
    public void unload() {
      unloadCount++;
      unloaded = true;
    }

    private OllamaApiMetrics metrics() {
      return new OllamaApiMetrics(4_000_000L, 0L, 100L, 1_000_000L, 20L, 3_000_000L);
    }
  }
}
