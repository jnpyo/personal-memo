package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OllamaLocalShadowTruncationDiagnosticClientTest {
  private static final String MODEL = "hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0";
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void selectionRequestBytesMatchV6ExactlyExceptForNumPredict() {
    RecordingTransport v6Transport = new RecordingTransport(validSelection().toString());
    RecordingTransport v7Transport = new RecordingTransport(validSelection().toString());
    FakeLifecycle lifecycle = new FakeLifecycle();
    OllamaLocalShadowSkillClient v6 =
        new OllamaLocalShadowSkillClient(json, MODEL, lifecycle, v6Transport);
    OllamaLocalShadowTruncationDiagnosticClient v7 =
        new OllamaLocalShadowTruncationDiagnosticClient(json, MODEL, lifecycle, v7Transport);

    v6.select(evidence(), selectionSchema());
    v7.diagnose(evidence(), selectionSchema());

    assertThat(v6Transport.requests).hasSize(1);
    assertThat(v7Transport.requests).hasSize(1);
    String v6Bytes = new String(v6Transport.requests.getFirst().body(), StandardCharsets.UTF_8);
    String v7Bytes = new String(v7Transport.requests.getFirst().body(), StandardCharsets.UTF_8);
    assertThat(v6Bytes).contains("\"num_predict\":64").doesNotContain("\"num_predict\":128");
    assertThat(v7Bytes).contains("\"num_predict\":128").doesNotContain("\"num_predict\":64");
    assertThat(v7Bytes.replace("\"num_predict\":128", "\"num_predict\":64")).isEqualTo(v6Bytes);
    assertThat(OllamaLocalShadowTruncationDiagnosticClient.SYSTEM_PROMPT)
        .isEqualTo(OllamaLocalShadowSkillClient.SYSTEM_PROMPT);
    assertThat(OllamaLocalShadowTruncationDiagnosticClient.NUM_CONTEXT).isEqualTo(2_048);
    assertThat(OllamaLocalShadowTruncationDiagnosticClient.SEED)
        .isEqualTo(OllamaLocalShadowSkillClient.SEED);
    assertThat(OllamaLocalShadowTruncationDiagnosticClient.MAX_MODEL_OUTPUT_BYTES).isEqualTo(256);
  }

  @Test
  void completedStopAboveTheOldSixtyFourTokenCapRemainsEligibleForValidation() {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    transport.evalCount = 80;
    OllamaLocalShadowTruncationDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.completedStop()).isTrue();
    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.NONE);
    assertThat(result.selectionAvailable()).isTrue();
    assertThat(result.selectionOutput()).isEqualTo(validSelection());
    assertThat(result.metrics().evalCount()).isEqualTo(80);
  }

  @Test
  void lengthAtOneHundredTwentyEightTokensIsRejectedAndRetainsOnlyDiagnostics() {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    transport.doneReason = "length";
    transport.evalCount = 128;
    OllamaLocalShadowTruncationDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.termination()).isEqualTo(DiagnosticTermination.LENGTH);
    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.TERMINATION_LENGTH);
    assertThat(result.completedStop()).isFalse();
    assertThat(result.selectionAvailable()).isFalse();
    assertThat(result.selectionOutput()).isNull();
    assertThat(result.metrics().promptEvalCount()).isEqualTo(30);
    assertThat(result.metrics().evalCount()).isEqualTo(128);
    assertThat(result.modelOutputBytes())
        .isEqualTo(validSelection().toString().getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void parseableLengthBodyIsNeverRetainedOrExposed() {
    String canary = "PARSEABLE_LENGTH_CANARY_MUST_NOT_LEAK_b461";
    ObjectNode parseable = validSelection().put("unexpectedCanary", canary);
    RecordingTransport transport = new RecordingTransport(parseable.toString());
    transport.doneReason = "length";
    transport.evalCount = 128;
    OllamaLocalShadowTruncationDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.TERMINATION_LENGTH);
    assertThat(result.selectionOutput()).isNull();
    assertThat(result.toString()).doesNotContain(canary);
  }

  @Test
  void stopBodyBeyondTwoHundredFiftySixBytesIsRejectedWithoutParsing() {
    String canary = "OVER_CAP_CANARY_MUST_NOT_LEAK_49f3";
    RecordingTransport transport =
        new RecordingTransport("{\"value\":\"" + canary + "x".repeat(280) + "\"}");
    transport.evalCount = 90;
    OllamaLocalShadowTruncationDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.termination()).isEqualTo(DiagnosticTermination.STOP);
    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.MODEL_OUTPUT_TOO_LARGE);
    assertThat(result.selectionOutput()).isNull();
    assertThat(result.atOrBelowModelOutputCap()).isFalse();
    assertThat(result.modelOutputBytes()).isGreaterThan(256);
    assertThat(result.toString()).doesNotContain(canary);
  }

  @Test
  void malformedStopCanaryIsReducedToAnEnumAndAggregateMetrics() {
    String canary = "MALFORMED_STOP_CANARY_MUST_NOT_LEAK_23ad";
    RecordingTransport transport = new RecordingTransport("{" + canary);
    OllamaLocalShadowTruncationDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.MALFORMED_MODEL_JSON);
    assertThat(result.selectionOutput()).isNull();
    assertThat(result.toString()).doesNotContain(canary);
  }

  private OllamaLocalShadowTruncationDiagnosticClient client(RecordingTransport transport) {
    return new OllamaLocalShadowTruncationDiagnosticClient(
        json, MODEL, new FakeLifecycle(), transport);
  }

  private ObjectNode evidence() {
    ObjectNode evidence =
        json.createObjectNode()
            .put("schemaVersion", ShadowDeterministicSkill.EVIDENCE_VERSION)
            .put("defaultTitle", "공개 합성 기본 제목");
    evidence
        .putArray("items")
        .addObject()
        .put("ordinal", 0)
        .put("kind", "TASK")
        .put("title", "공개 합성 작업")
        .put("objectValue", "공개 대상");
    return evidence;
  }

  private ObjectNode validSelection() {
    ObjectNode selection =
        json.createObjectNode()
            .put("schemaVersion", ShadowDeterministicSkill.SELECTION_VERSION)
            .put("primaryItemOrdinal", 0);
    selection.putArray("topicObjectOrdinals").add(0);
    return selection;
  }

  private ObjectNode selectionSchema() {
    try (var input =
        getClass()
            .getResourceAsStream("/contracts/solo-liquidai-shadow-skill-selection.schema.json")) {
      if (input == null) {
        throw new IllegalStateException("Missing selection schema.");
      }
      return (ObjectNode) json.readTree(input);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not read selection schema.", exception);
    }
  }

  private final class RecordingTransport implements OllamaTransport {
    private final String modelContent;
    private final List<OllamaWireRequest> requests = new ArrayList<>();
    private String doneReason = "stop";
    private long evalCount = 8;

    private RecordingTransport(String modelContent) {
      this.modelContent = modelContent;
    }

    @Override
    public OllamaWireResponse exchange(OllamaWireRequest request, int maxResponseBytes) {
      requests.add(request);
      ObjectNode response =
          json.createObjectNode()
              .put("model", MODEL)
              .put("done", true)
              .put("done_reason", doneReason)
              .put("total_duration", 4_000_000L)
              .put("prompt_eval_count", 30)
              .put("prompt_eval_duration", 1_000_000L)
              .put("eval_count", evalCount)
              .put("eval_duration", 2_000_000L);
      response.set(
          "message", json.createObjectNode().put("role", "assistant").put("content", modelContent));
      return new OllamaWireResponse(
          200,
          "application/json; charset=utf-8",
          response.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private final class FakeLifecycle implements OllamaShadowApi {
    @Override
    public OllamaModelPreflight preflight() {
      return new OllamaModelPreflight(
          "0.32.7", MODEL, "a".repeat(64), 1, "gguf", "lfm2", "2.7B", "Q8_0", false, 0);
    }

    @Override
    public OllamaWarmupResult warmup() {
      return new OllamaWarmupResult(1, metrics());
    }

    @Override
    public OllamaShadowResult analyze(
        String memoText, String baseInstant, String timeZone, ObjectNode outputSchema) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OllamaObservedAllocation allocation() {
      return OllamaObservedAllocation.notLoaded();
    }

    @Override
    public void unload() {}

    private OllamaApiMetrics metrics() {
      return new OllamaApiMetrics(1L, 0L, 0L, 0L, 0L, 0L);
    }
  }
}
