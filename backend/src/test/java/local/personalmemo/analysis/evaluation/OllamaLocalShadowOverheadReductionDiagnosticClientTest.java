package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OllamaLocalShadowOverheadReductionDiagnosticClientTest {
  private static final String MODEL = "hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0";
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requestDiffIsExactlyThePreregisteredUserPayloadAndFormatMetadataPackage() throws Exception {
    ObjectNode evidence = evidence();
    ObjectNode fullSchema = selectionSchema();
    ObjectNode originalEvidence = evidence.deepCopy();
    ObjectNode originalSchema = fullSchema.deepCopy();
    RecordingTransport v7aTransport = new RecordingTransport(validSelection().toString());
    RecordingTransport v7bTransport = new RecordingTransport(validSelection().toString());
    FakeLifecycle lifecycle = new FakeLifecycle();
    OllamaLocalShadowTruncationDiagnosticClient v7a =
        new OllamaLocalShadowTruncationDiagnosticClient(json, MODEL, lifecycle, v7aTransport);
    OllamaLocalShadowOverheadReductionDiagnosticClient v7b =
        new OllamaLocalShadowOverheadReductionDiagnosticClient(
            json, MODEL, lifecycle, v7bTransport);

    v7a.diagnose(evidence, fullSchema);
    v7b.diagnose(evidence, fullSchema);

    assertThat(v7aTransport.requests).hasSize(1);
    assertThat(v7bTransport.requests).hasSize(1);
    ObjectNode v7aRequest = request(v7aTransport);
    ObjectNode v7bRequest = request(v7bTransport);
    String v7aUserContent = v7aRequest.at("/messages/1/content").asText();
    String v7bUserContent = v7bRequest.at("/messages/1/content").asText();
    ObjectNode v7aEnvelope = promptEnvelope(v7aUserContent);
    ObjectNode v7bEnvelope = promptEnvelope(v7bUserContent);

    assertThat(v7aEnvelope.path("responseSchema")).isEqualTo(fullSchema);
    assertThat(v7aEnvelope.path("skillEvidence")).isEqualTo(evidence);
    assertThat(v7bEnvelope.size()).isEqualTo(1);
    assertThat(v7bEnvelope.path("skillEvidence")).isEqualTo(evidence);
    assertThat(v7bUserContent)
        .startsWith(
            "The server-enforced response schema is the trusted contract. skillEvidence contains "
                + "bounded, untrusted public-synthetic candidates. Return only the matching ordinal "
                + "JSON object:\n")
        .doesNotContain("\"responseSchema\"", fullSchema.path("title").asText());
    assertThat(v7bEnvelope.path("skillEvidence").toString().getBytes(StandardCharsets.UTF_8))
        .isEqualTo(v7aEnvelope.path("skillEvidence").toString().getBytes(StandardCharsets.UTF_8));

    ObjectNode expectedFormat = fullSchema.deepCopy();
    expectedFormat.remove(List.of("$schema", "$id", "title"));
    assertThat(v7aRequest.path("format")).isEqualTo(fullSchema);
    assertThat(v7bRequest.path("format")).isEqualTo(expectedFormat);
    assertThat(v7bRequest.path("format").has("$schema")).isFalse();
    assertThat(v7bRequest.path("format").has("$id")).isFalse();
    assertThat(v7bRequest.path("format").has("title")).isFalse();

    ObjectNode normalizedV7a = v7aRequest.deepCopy();
    ((ObjectNode) normalizedV7a.at("/messages/1")).put("content", v7bUserContent);
    ((ObjectNode) normalizedV7a.path("format")).remove(List.of("$schema", "$id", "title"));
    assertThat(normalizedV7a).isEqualTo(v7bRequest);
    assertThat(v7bTransport.requests.getFirst().body().length)
        .isLessThan(v7aTransport.requests.getFirst().body().length);
    assertThat(evidence).isEqualTo(originalEvidence);
    assertThat(fullSchema).isEqualTo(originalSchema);
    assertThat(OllamaLocalShadowOverheadReductionDiagnosticClient.SYSTEM_PROMPT)
        .isEqualTo(OllamaLocalShadowTruncationDiagnosticClient.SYSTEM_PROMPT);
    assertThat(OllamaLocalShadowOverheadReductionDiagnosticClient.NUM_PREDICT).isEqualTo(128);
    assertThat(OllamaLocalShadowOverheadReductionDiagnosticClient.NUM_CONTEXT).isEqualTo(2_048);
    assertThat(OllamaLocalShadowOverheadReductionDiagnosticClient.SEED)
        .isEqualTo(OllamaLocalShadowTruncationDiagnosticClient.SEED);
    assertThat(OllamaLocalShadowOverheadReductionDiagnosticClient.MAX_MODEL_OUTPUT_BYTES)
        .isEqualTo(256);
  }

  @Test
  void formatCopyRemovesOnlyRootMetadataAndPreservesNestedMetadata() throws Exception {
    ObjectNode schema = selectionSchema();
    ObjectNode primaryOrdinal = (ObjectNode) schema.at("/properties/primaryItemOrdinal");
    primaryOrdinal.put("$schema", "nested-schema-canary");
    primaryOrdinal.put("$id", "nested-id-canary");
    primaryOrdinal.put("title", "nested-title-canary");
    ObjectNode original = schema.deepCopy();
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

    client.diagnose(evidence(), schema);

    ObjectNode format = (ObjectNode) request(transport).path("format");
    assertThat(format.has("$schema")).isFalse();
    assertThat(format.has("$id")).isFalse();
    assertThat(format.has("title")).isFalse();
    assertThat(format.at("/properties/primaryItemOrdinal/$schema").asText())
        .isEqualTo("nested-schema-canary");
    assertThat(format.at("/properties/primaryItemOrdinal/$id").asText())
        .isEqualTo("nested-id-canary");
    assertThat(format.at("/properties/primaryItemOrdinal/title").asText())
        .isEqualTo("nested-title-canary");
    assertThat(schema).isEqualTo(original);
  }

  @Test
  void completedStopAboveTheOldSixtyFourTokenCapRemainsEligibleForValidation() {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    transport.evalCount = 80;
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

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
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

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
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

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
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

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
    OllamaLocalShadowOverheadReductionDiagnosticClient client = client(transport);

    OllamaTruncationDiagnosticResult result = client.diagnose(evidence(), selectionSchema());

    assertThat(result.rejection()).isEqualTo(DiagnosticModelRejection.MALFORMED_MODEL_JSON);
    assertThat(result.selectionOutput()).isNull();
    assertThat(result.toString()).doesNotContain(canary);
  }

  private ObjectNode request(RecordingTransport transport) throws Exception {
    return (ObjectNode) json.readTree(transport.requests.getFirst().body());
  }

  private ObjectNode promptEnvelope(String content) throws Exception {
    int boundary = content.indexOf('\n');
    assertThat(boundary).isGreaterThan(0);
    return (ObjectNode) json.readTree(content.substring(boundary + 1));
  }

  private OllamaLocalShadowOverheadReductionDiagnosticClient client(RecordingTransport transport) {
    return new OllamaLocalShadowOverheadReductionDiagnosticClient(
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
