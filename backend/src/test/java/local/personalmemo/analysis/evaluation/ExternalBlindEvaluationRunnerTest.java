package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ExternalBlindEvaluationRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void acceptsARegularExternalFileOutsideTheRepository() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path dataset = Files.writeString(temporaryDirectory.resolve("external.json"), "{}");

    assertThat(
            ExternalBlindEvaluationRunner.validateExternalDatasetPath(
                repository, dataset.toAbsolutePath()))
        .isEqualTo(dataset.toRealPath());
  }

  @Test
  void rejectsAFileInsideTheRepository() throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
    Path dataset = Files.writeString(repository.resolve("internal.json"), "{}");

    assertThatThrownBy(
            () ->
                ExternalBlindEvaluationRunner.validateExternalDatasetPath(
                    repository, dataset.toAbsolutePath()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The blind dataset path is outside the allowed boundary.");
  }

  @Test
  void aggregateOnlyPrivacyCheckRejectsCaseLevelText() throws Exception {
    ObjectNode report = validMinimalReport();
    report.set("metrics", currentAggregateMetrics());

    assertThatCode(
            () ->
                ExternalBlindEvaluationRunner.assertAggregateOnlyReport(
                    report, 1, Set.of("not-present")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                ExternalBlindEvaluationRunner.assertAggregateOnlyReport(
                    report, 1, Set.of("RELEASE")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The blind summary contains case-level information.");
    assertThatThrownBy(
            () -> ExternalBlindEvaluationRunner.assertAggregateOnlyReport(report, 1, Set.of("1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The blind summary contains case-level information.");
  }

  @Test
  void opaqueReleaseProvenanceRejectsEmbeddedHashMaterial() {
    ObjectMapper json = new ObjectMapper();
    String embeddedHash = "release-" + "a".repeat(64);

    assertThatThrownBy(
            () ->
                ExternalBlindEvaluationRunner.opaqueVersion(
                    json.getNodeFactory().textNode(embeddedHash)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Blind release provenance must be an opaque version label.");
  }

  @Test
  void aggregateAllowlistAcceptsTheCurrentMetricEngineShape() throws Exception {
    ObjectNode report = validMinimalReport();
    report.set("metrics", currentAggregateMetrics());

    assertThatCode(
            () ->
                ExternalBlindEvaluationRunner.assertAggregateOnlyReport(
                    report, 1, Set.of("not-present")))
        .doesNotThrowAnyException();
  }

  private ObjectNode currentAggregateMetrics() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode fixture;
    try (InputStream input = getClass().getResourceAsStream("/fixtures/korean-memo-cases.json")) {
      fixture = json.readTree(input).get(0);
    }
    UUID memoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String content = fixture.path("content").asText();
    ObjectNode proposal =
        new FakeAnalyzer(json)
            .analyze(
                memoId,
                1,
                content,
                Instant.parse(fixture.path("baseInstant").asText()),
                fixture.path("timeZone").asText());
    return EvaluationV2Metrics.aggregate(
            List.of(
                new EvaluationV2Evaluator(
                        json,
                        new FakeAnalyzer(json).provenance(),
                        new local.personalmemo.analysis.domain.DeterministicAmbiguityGate()
                            .version())
                    .evaluate(fixture, proposal, memoId, 1, content)))
        .toJson(json);
  }

  private ObjectNode validMinimalReport() {
    ObjectMapper json = new ObjectMapper();
    ObjectNode report =
        json.createObjectNode()
            .put("reportVersion", "1")
            .put("evaluationKind", "EXTERNAL_BLIND")
            .put("datasetVersion", "2")
            .put("releaseId", "curator-release")
            .put("labelPolicyVersion", "labels-v1")
            .put("sourcePolicy", "INDEPENDENT_HUMAN_CURATED")
            .put("candidateCommit", "0123456789012345678901234567890123456789")
            .put("analyzerVersion", "fake-v4")
            .put("deterministicRulesVersion", "korean-rules-v2")
            .put("promptVersion", "none")
            .put("localModelVersion", "none")
            .put("embeddingModelVersion", "none")
            .put("routingPolicyVersion", "field-policy-v1")
            .put("containsRawMemoContent", false);
    report
        .putObject("capabilities")
        .put("dateItemGold", "SCORED")
        .put("dateItemDueBinding", "NOT_SUPPORTED_BY_PROPOSAL_V1");
    report
        .putObject("metricGate")
        .put("status", "NOT_CONFIGURED")
        .put("enforced", false)
        .put("reason", "PRE_REGISTERED_POLICY_REQUIRED");
    report.putObject("metrics").put("caseCount", 50);
    return report;
  }
}
