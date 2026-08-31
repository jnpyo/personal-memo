package local.personalmemo.analysis.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OllamaLocalShadowSkillClientTest {
  private static final String MODEL = "hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0";
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void sendsOneStrictOrdinalRequestWithV6CapsAndNoRawFixtureMetadata() throws Exception {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    FakeLifecycle lifecycle = new FakeLifecycle();
    OllamaLocalShadowSkillClient client =
        new OllamaLocalShadowSkillClient(json, MODEL, lifecycle, transport);

    OllamaSkillSelectionResult result = client.select(evidence(), selectionSchema());

    assertThat(result.selectionOutput()).isEqualTo(validSelection());
    assertThat(result.modelOutputBytes()).isLessThanOrEqualTo(256);
    assertThat(transport.requests).hasSize(1);
    OllamaWireRequest wire = transport.requests.getFirst();
    assertThat(wire.method()).isEqualTo("POST");
    assertThat(wire.uri()).isEqualTo(OllamaLocalShadowSkillClient.CHAT_URI);
    ObjectNode request = (ObjectNode) json.readTree(wire.body());
    assertThat(request.at("/options/num_ctx").asInt()).isEqualTo(2_048);
    assertThat(request.at("/options/num_predict").asInt()).isEqualTo(64);
    assertThat(request.at("/options/temperature").asInt()).isZero();
    assertThat(request.path("tools").isMissingNode()).isTrue();
    assertThat(request.path("format")).isEqualTo(selectionSchema());
    String encoded = request.toString();
    assertThat(encoded)
        .doesNotContain("caseId", "expectedTypes", "expectedDates", "notes", "memoText")
        .contains("skillEvidence", "primaryItemOrdinal", "topicObjectOrdinals");
  }

  @Test
  void warmupAndUnloadUseTheSameV6CapsInsteadOfTheV5LifecycleOptions() throws Exception {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    FakeLifecycle lifecycle = new FakeLifecycle();
    OllamaLocalShadowSkillClient client =
        new OllamaLocalShadowSkillClient(json, MODEL, lifecycle, transport);

    client.warmup();
    client.unload();

    assertThat(lifecycle.warmupCalls).isZero();
    assertThat(lifecycle.unloadCalls).isZero();
    assertThat(transport.requests).hasSize(2);
    for (OllamaWireRequest wire : transport.requests) {
      JsonNode request = json.readTree(wire.body());
      assertThat(request.at("/options/num_ctx").asInt()).isEqualTo(2_048);
      assertThat(request.at("/options/num_predict").asInt()).isEqualTo(64);
      assertThat(request.path("messages")).isEmpty();
    }
    assertThat(json.readTree(transport.requests.getFirst().body()).path("keep_alive").asText())
        .isEqualTo("5m");
    assertThat(json.readTree(transport.requests.getLast().body()).path("keep_alive").asInt())
        .isZero();
  }

  @Test
  void rejectsComplexModelFacingGrammarBeforeNetworkUse() {
    RecordingTransport transport = new RecordingTransport(validSelection().toString());
    ObjectNode complex = selectionSchema();
    complex.putArray("oneOf").addObject().put("type", "object");
    OllamaLocalShadowSkillClient client =
        new OllamaLocalShadowSkillClient(json, MODEL, new FakeLifecycle(), transport);

    assertThatThrownBy(() -> client.select(evidence(), complex))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Selection schema uses a forbidden grammar keyword.");
    assertThat(transport.requests).isEmpty();
  }

  @Test
  void malformedOutputAndCanaryNeverAppearInTheSanitizedException() {
    String canary = "MODEL_OUTPUT_CANARY_MUST_NOT_LEAK_6f4c";
    RecordingTransport transport = new RecordingTransport("{" + canary);
    OllamaLocalShadowSkillClient client =
        new OllamaLocalShadowSkillClient(json, MODEL, new FakeLifecycle(), transport);

    assertThatThrownBy(() -> client.select(evidence(), selectionSchema()))
        .isInstanceOf(OllamaShadowException.class)
        .hasMessage("Ollama shadow request failed: MALFORMED_MODEL_JSON")
        .hasMessageNotContaining(canary);
  }

  @Test
  void rejectsToolCallsAndOutputBeyondTheTwoHundredFiftySixByteBoundary() {
    RecordingTransport toolTransport = new RecordingTransport(validSelection().toString());
    toolTransport.includeToolCall = true;
    OllamaLocalShadowSkillClient toolClient =
        new OllamaLocalShadowSkillClient(json, MODEL, new FakeLifecycle(), toolTransport);
    assertThatThrownBy(() -> toolClient.select(evidence(), selectionSchema()))
        .isInstanceOf(OllamaShadowException.class)
        .hasMessage("Ollama shadow request failed: TOOL_CALL_REJECTED");

    RecordingTransport largeTransport = new RecordingTransport("x".repeat(257));
    OllamaLocalShadowSkillClient largeClient =
        new OllamaLocalShadowSkillClient(json, MODEL, new FakeLifecycle(), largeTransport);
    assertThatThrownBy(() -> largeClient.select(evidence(), selectionSchema()))
        .isInstanceOf(OllamaShadowException.class)
        .hasMessage("Ollama shadow request failed: MODEL_OUTPUT_TOO_LARGE");
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
    private boolean includeToolCall;

    private RecordingTransport(String modelContent) {
      this.modelContent = modelContent;
    }

    @Override
    public OllamaWireResponse exchange(OllamaWireRequest request, int maxResponseBytes) {
      requests.add(request);
      ObjectNode message =
          json.createObjectNode().put("role", "assistant").put("content", modelContent);
      if (includeToolCall) {
        message.putArray("tool_calls").addObject().put("name", "forbidden");
      }
      ObjectNode response =
          json.createObjectNode()
              .put("model", MODEL)
              .put("done", true)
              .put("done_reason", "stop")
              .put("total_duration", 4_000_000L)
              .put("prompt_eval_count", 20)
              .put("eval_count", 8);
      response.set("message", message);
      return new OllamaWireResponse(
          200,
          "application/json; charset=utf-8",
          response.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private final class FakeLifecycle implements OllamaShadowApi {
    private int warmupCalls;
    private int unloadCalls;

    @Override
    public OllamaModelPreflight preflight() {
      return new OllamaModelPreflight(
          "0.32.7", MODEL, "a".repeat(64), 1, "gguf", "lfm2", "2.7B", "Q8_0", false, 0);
    }

    @Override
    public OllamaWarmupResult warmup() {
      warmupCalls++;
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
    public void unload() {
      unloadCalls++;
    }

    private OllamaApiMetrics metrics() {
      return new OllamaApiMetrics(1L, 0L, 0L, 0L, 0L, 0L);
    }
  }
}
