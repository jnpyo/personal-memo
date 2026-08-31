package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.analysis.domain.ApprovedCorrectionContext;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudProviderRequestToken;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.LocalModelInput;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class OllamaLocalAnalysisGatewayTest {
  private static final UUID MEMO_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID TAG_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
  private static final String MODEL = "hf.co/LiquidAI/LFM2.5:Q8_0";
  private static final String DIGEST = "a".repeat(64);
  private static final String MEMO = "6시 디스코드 접속하기";
  private static final CloudProviderRequestToken TOKEN =
      CloudProviderRequestToken.issue(
          UUID.fromString("00000000-0000-0000-0000-000000000103"),
          "ANALYSIS_START",
          "local-model-test",
          "b".repeat(64));

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void mapsOnlyTheBoundedSemanticPatchAndPreservesProtectedProposalFields() throws Exception {
    ScriptedTransport transport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("stop", validPatch(), false)),
            jsonResponse(installedModels(DIGEST)));
    OllamaLocalAnalysisGateway gateway = gateway(transport);
    var binding = gateway.bind();
    ObjectNode local = validProposal(true);

    CloudAnalysisResult result = binding.execute(request(local, binding.descriptor()));

    assertThat(result).isInstanceOf(CloudAnalysisResult.Success.class);
    ObjectNode mapped = ((CloudAnalysisResult.Success) result).proposal();
    assertThat(mapped.path("memoId").asText()).isEqualTo(MEMO_ID.toString());
    assertThat(mapped.path("memoRevision").asInt()).isEqualTo(1);
    assertThat(mapped.at("/suggestedTitle/value").asText()).isEqualTo(MEMO);
    assertThat(mapped.at("/suggestedTitle/confidence").asDouble()).isEqualTo(0.99);
    assertThat(mapped.at("/suggestedTitle/needsConfirmation").asBoolean()).isTrue();
    assertThat(mapped.path("tagCandidates")).isEqualTo(local.path("tagCandidates"));
    assertThat(mapped.path("relationCandidates")).isEqualTo(local.path("relationCandidates"));
    assertThat(mapped.at("/itemCandidates/0/candidateId").asText()).isEqualTo("item-1");
    assertThat(mapped.at("/itemCandidates/0/title").asText()).isEqualTo(MEMO);
    assertThat(mapped.at("/itemCandidates/0/sourceSpan"))
        .isEqualTo(local.at("/itemCandidates/0/sourceSpan"));
    assertThat(mapped.at("/itemCandidates/0/kind").asText()).isEqualTo("TASK");
    assertThat(mapped.at("/itemCandidates/0/action").asText()).isEqualTo("접속하기");
    assertThat(mapped.at("/itemCandidates/0/object").asText()).isEqualTo("디스코드");
    assertThat(mapped.at("/dateCandidates/0/surfaceText").asText()).isEqualTo("6시");
    assertThat(mapped.at("/dateCandidates/0/precision").asText()).isEqualTo("UNKNOWN");
    assertThat(mapped.at("/itemCandidates/0/dueDateCandidateId").isNull()).isTrue();
    assertThat(textValues(mapped.path("ambiguityReasons")))
        .containsExactly("LOW_TYPE_MARGIN", "IMPRECISE_DATE", "LOCAL_CLOUD_CONFLICT");
    assertThat(mapped.path("providerMetadata")).isEqualTo(local.path("providerMetadata"));

    assertThat(transport.requests).hasSize(3);
    assertThat(transport.requests)
        .extracting(OllamaTransportRequest::method)
        .containsExactly("GET", "POST", "GET");
    ObjectNode chatBody = (ObjectNode) json.readTree(transport.requests.get(1).body());
    assertThat(chatBody.path("model").asText()).isEqualTo(MODEL);
    assertThat(chatBody.path("stream").asBoolean()).isFalse();
    assertThat(chatBody.path("think").asBoolean()).isFalse();
    assertThat(chatBody.path("truncate").isBoolean()).isTrue();
    assertThat(chatBody.path("truncate").asBoolean()).isFalse();
    assertThat(chatBody.path("shift").isBoolean()).isTrue();
    assertThat(chatBody.path("shift").asBoolean()).isFalse();
    assertThat(chatBody.path("keep_alive").asInt()).isZero();
    assertThat(chatBody.path("tools").isMissingNode()).isTrue();
    assertThat(chatBody.path("format").path("additionalProperties").asBoolean()).isFalse();
    assertThat(chatBody.at("/format/properties/version/const").asText()).isEqualTo("2");
    assertThat(textValues(chatBody.at("/format/properties/decision/enum")))
        .containsExactly("KEEP", "PATCH");
    assertThat(chatBody.at("/options/temperature").asInt()).isZero();
    assertThat(chatBody.at("/options/num_predict").asInt()).isEqualTo(1024);
    assertThat(chatBody.at("/messages/0/content").asText())
        .contains("{\"version\":\"2\",\"decision\":\"KEEP\"}", "decision PATCH");
    String serialized = chatBody.toString();
    assertThat(serialized)
        .contains(
            MEMO, "untrusted input", "7시 팀 채팅 접속하기", "approvedCorrectionHints", "접속하기", "TASK");
    assertThat(serialized).doesNotContain("responseSchema", "referenceInstant", "timeZone");
    assertThat(serialized).doesNotContain(TOKEN.value(), MEMO_ID.toString(), TAG_ID.toString());
  }

  @Test
  void keepReturnsTheValidatedLocalProposalAsAnUnchangedSuccess() throws Exception {
    ScriptedTransport transport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("stop", keepDecision(), false)),
            jsonResponse(installedModels(DIGEST)));
    OllamaLocalAnalysisGateway gateway = gateway(transport);
    var binding = gateway.bind();
    ObjectNode local = validProposal(true);

    CloudAnalysisResult result = binding.execute(request(local, binding.descriptor()));

    assertThat(result).isInstanceOf(CloudAnalysisResult.Success.class);
    assertThat(((CloudAnalysisResult.Success) result).proposal()).isEqualTo(local);
    assertThat(transport.requests).hasSize(3);
  }

  @Test
  void acceptsAndIgnoresBoundedTextualThinking() {
    ObjectNode response = chatResponse("stop", validPatch(), false);
    ((ObjectNode) response.path("message")).put("thinking", "bounded provider reasoning");
    ScriptedTransport transport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(response),
            jsonResponse(installedModels(DIGEST)));
    OllamaLocalAnalysisGateway gateway = gateway(transport);
    var binding = gateway.bind();

    CloudAnalysisResult result =
        binding.execute(request(validProposal(true), binding.descriptor()));

    assertThat(result).isInstanceOf(CloudAnalysisResult.Success.class);
    assertThat(((CloudAnalysisResult.Success) result).proposal().toString())
        .doesNotContain("bounded provider reasoning", "thinking");
    assertThat(transport.requests).hasSize(3);
  }

  @Test
  void rejectsKeepWhenItCarriesPatchFields() {
    ObjectNode invalidKeep = keepDecision().put("itemIndex", 0);
    ScriptedTransport transport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("stop", invalidKeep, false)));
    OllamaLocalAnalysisGateway gateway = gateway(transport);
    var binding = gateway.bind();

    CloudAnalysisResult result =
        binding.execute(request(validProposal(true), binding.descriptor()));

    assertThat(result)
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));
    assertThat(transport.requests).hasSize(2);
  }

  @Test
  void failsClosedBeforeAnyTransportCallWhenNoExistingItemCanBePatched() {
    ScriptedTransport transport = new ScriptedTransport();
    OllamaLocalAnalysisGateway gateway = gateway(transport);
    var binding = gateway.bind();

    CloudAnalysisResult result =
        binding.execute(request(validProposal(false), binding.descriptor()));

    assertThat(result)
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNEXPECTED_FAILURE));
    assertThat(transport.requests).isEmpty();
  }

  @Test
  void rejectsLengthTerminationAndToolCallsWithoutReturningProviderText() {
    ScriptedTransport lengthTransport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("length", validPatch(), false)));
    OllamaLocalAnalysisGateway lengthGateway = gateway(lengthTransport);
    var lengthBinding = lengthGateway.bind();

    CloudAnalysisResult lengthResult =
        lengthBinding.execute(request(validProposal(true), lengthBinding.descriptor()));

    assertThat(lengthResult)
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));

    ScriptedTransport toolTransport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("stop", validPatch(), true)));
    OllamaLocalAnalysisGateway toolGateway = gateway(toolTransport);
    var toolBinding = toolGateway.bind();

    CloudAnalysisResult toolResult =
        toolBinding.execute(request(validProposal(true), toolBinding.descriptor()));

    assertThat(toolResult)
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));
    assertThat(toolResult.toString()).doesNotContain(MEMO, validPatch().toString());
  }

  @Test
  void rejectsNonTextOversizedOrUnexpectedThinkingFields() {
    ObjectNode nonTextResponse = chatResponse("stop", validPatch(), false);
    ((ObjectNode) nonTextResponse.path("message")).putObject("thinking");
    ScriptedTransport nonTextTransport =
        new ScriptedTransport(jsonResponse(installedModels(DIGEST)), jsonResponse(nonTextResponse));
    OllamaLocalAnalysisGateway nonTextGateway = gateway(nonTextTransport);
    var nonTextBinding = nonTextGateway.bind();

    assertThat(nonTextBinding.execute(request(validProposal(true), nonTextBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));

    ObjectNode oversizedResponse = chatResponse("stop", validPatch(), false);
    ((ObjectNode) oversizedResponse.path("message"))
        .put("thinking", "x".repeat(properties().getMaxModelOutputBytes() + 1));
    ScriptedTransport oversizedTransport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)), jsonResponse(oversizedResponse));
    OllamaLocalAnalysisGateway oversizedGateway = gateway(oversizedTransport);
    var oversizedBinding = oversizedGateway.bind();

    assertThat(
            oversizedBinding.execute(request(validProposal(true), oversizedBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));

    ObjectNode unexpectedResponse = chatResponse("stop", validPatch(), false);
    ((ObjectNode) unexpectedResponse.path("message"))
        .put("thinking", "bounded")
        .put("unexpected", true);
    ScriptedTransport unexpectedTransport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)), jsonResponse(unexpectedResponse));
    OllamaLocalAnalysisGateway unexpectedGateway = gateway(unexpectedTransport);
    var unexpectedBinding = unexpectedGateway.bind();

    assertThat(
            unexpectedBinding.execute(request(validProposal(true), unexpectedBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));
  }

  @Test
  void rejectsWrongDigestAndUngroundedSelections() {
    ScriptedTransport digestTransport =
        new ScriptedTransport(jsonResponse(installedModels("c".repeat(64))));
    OllamaLocalAnalysisGateway digestGateway = gateway(digestTransport);
    var digestBinding = digestGateway.bind();

    assertThat(digestBinding.execute(request(validProposal(true), digestBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));
    assertThat(digestTransport.requests).hasSize(1);

    ObjectNode inventedPatch = validPatch().put("actionText", "존재하지 않는 행동");
    ScriptedTransport inventedTransport =
        new ScriptedTransport(
            jsonResponse(installedModels(DIGEST)),
            jsonResponse(chatResponse("stop", inventedPatch, false)),
            jsonResponse(installedModels(DIGEST)));
    OllamaLocalAnalysisGateway inventedGateway = gateway(inventedTransport);
    var inventedBinding = inventedGateway.bind();

    assertThat(inventedBinding.execute(request(validProposal(true), inventedBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.PROVIDER_ERROR));
  }

  @Test
  void mapsTransportIoAndTimeoutToTypedFallbacks() {
    OllamaTransport unavailable =
        request -> {
          throw new java.io.IOException("private provider detail");
        };
    OllamaLocalAnalysisGateway unavailableGateway = gateway(unavailable);
    var unavailableBinding = unavailableGateway.bind();
    assertThat(
            unavailableBinding.execute(
                request(validProposal(true), unavailableBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.UNAVAILABLE));

    OllamaTransport timeout =
        request -> {
          throw new java.net.http.HttpTimeoutException("private provider timeout");
        };
    OllamaLocalAnalysisGateway timeoutGateway = gateway(timeout);
    var timeoutBinding = timeoutGateway.bind();
    assertThat(timeoutBinding.execute(request(validProposal(true), timeoutBinding.descriptor())))
        .isEqualTo(CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
  }

  @Test
  void bindingDeclaresTheExactMachineLocalDescriptorWithoutCallingTransport() {
    ScriptedTransport transport = new ScriptedTransport();
    var descriptor = gateway(transport).bind().descriptor();

    assertThat(descriptor.gatewayVersion()).isEqualTo(OllamaLocalAnalysisGateway.GATEWAY_VERSION);
    assertThat(descriptor.gatewayVersion())
        .isEqualTo(
            OllamaLocalAnalysisGateway.ADAPTER_VERSION
                + "+"
                + OllamaLocalAnalysisGateway.PROMPT_CONTRACT_VERSION);
    assertThat(descriptor.providerId())
        .isEqualTo(OllamaLocalModelProperties.DESCRIPTOR_PROVIDER_PREFIX + MODEL);
    assertThat(descriptor.modelVersion()).isEqualTo(DIGEST);
    assertThat(descriptor.consentPolicyVersion())
        .isEqualTo(OllamaLocalAnalysisGateway.CONSENT_POLICY_VERSION);
    assertThat(descriptor.transferMode()).isEqualTo(CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT);
    assertThat(transport.requests).isEmpty();
  }

  @Test
  void bindingIdentityChangesWithAdapterExactTagDigestOrPromptContractWithoutProviderIo() {
    ScriptedTransport transport = new ScriptedTransport();
    var baseline = gateway(transport).bind();
    var changedAdapter =
        new CloudGatewayBinding(
            OllamaLocalAnalysisGateway.descriptorFor(
                "ollama-local-gateway-v3",
                OllamaLocalAnalysisGateway.PROMPT_CONTRACT_VERSION,
                MODEL,
                DIGEST),
            request -> CloudAnalysisResult.success(request.validatedLocalProposal()));
    var changedTag =
        new CloudGatewayBinding(
            OllamaLocalAnalysisGateway.descriptorFor("hf.co/LiquidAI/LFM2.5:Q6_K", DIGEST),
            request -> CloudAnalysisResult.success(request.validatedLocalProposal()));
    var changedDigest =
        new CloudGatewayBinding(
            OllamaLocalAnalysisGateway.descriptorFor(MODEL, "b".repeat(64)),
            request -> CloudAnalysisResult.success(request.validatedLocalProposal()));
    var changedPrompt =
        new CloudGatewayBinding(
            OllamaLocalAnalysisGateway.descriptorFor(
                OllamaLocalAnalysisGateway.ADAPTER_VERSION,
                "local-semantic-patch-v3",
                MODEL,
                DIGEST),
            request -> CloudAnalysisResult.success(request.validatedLocalProposal()));

    assertThat(
            List.of(
                baseline.bindingId(),
                changedAdapter.bindingId(),
                changedTag.bindingId(),
                changedDigest.bindingId(),
                changedPrompt.bindingId()))
        .doesNotHaveDuplicates();
    assertThat(baseline.descriptor().gatewayVersion())
        .isEqualTo("ollama-local-gateway-v2+local-semantic-patch-v2");
    assertThat(changedAdapter.descriptor().gatewayVersion()).startsWith("ollama-local-gateway-v3+");
    assertThat(changedTag.descriptor().providerId()).contains("Q6_K");
    assertThat(changedDigest.descriptor().modelVersion()).isEqualTo("b".repeat(64));
    assertThat(baseline.descriptor().gatewayVersion()).endsWith("+local-semantic-patch-v2");
    assertThat(changedPrompt.descriptor().gatewayVersion()).endsWith("+local-semantic-patch-v3");
    assertThat(transport.requests).isEmpty();
  }

  private OllamaLocalAnalysisGateway gateway(OllamaTransport transport) {
    return new OllamaLocalAnalysisGateway(
        json,
        properties(),
        transport,
        new Draft202012AnalysisProposalSchemaValidator(),
        new AnalysisProposalValidator());
  }

  private OllamaLocalModelProperties properties() {
    OllamaLocalModelProperties properties = new OllamaLocalModelProperties();
    properties.setEnabled(true);
    properties.setModel(MODEL);
    properties.setModelDigest(DIGEST);
    return properties;
  }

  private CloudAnalysisRequest request(
      ObjectNode proposal, local.personalmemo.analysis.domain.CloudGatewayDescriptor descriptor) {
    return new CloudAnalysisRequest(
        proposal,
        List.of(AmbiguityReason.LOW_TYPE_MARGIN),
        "field-policy-v1",
        descriptor,
        Optional.empty(),
        Optional.empty(),
        TOKEN,
        Optional.empty(),
        Optional.of(
            new LocalModelInput(
                MEMO,
                Instant.parse("2026-08-21T09:00:00Z"),
                "Asia/Seoul",
                List.of(new ApprovedCorrectionContext.Hint("접속하기", "TASK")))));
  }

  private ObjectNode validProposal(boolean withItem) {
    ObjectNode proposal =
        json.createObjectNode()
            .put("schemaVersion", "2")
            .put("memoId", MEMO_ID.toString())
            .put("memoRevision", 1);
    proposal
        .putObject("suggestedTitle")
        .put("value", MEMO)
        .put("confidence", 0.99)
        .put("needsConfirmation", false);
    if (withItem) {
      proposal.putArray("typeCandidates").addObject().put("value", "RECORD").put("score", 0.99);
    } else {
      proposal.putArray("typeCandidates").addObject().put("value", "UNKNOWN").put("score", 1.0);
    }
    proposal.putArray("dateCandidates");
    proposal
        .putArray("tagCandidates")
        .addObject()
        .put("existingTagId", TAG_ID.toString())
        .put("canonicalName", "디스코드")
        .putNull("matchedAlias")
        .put("score", 0.9)
        .put("isNewProposal", false);
    ArrayNode items = proposal.putArray("itemCandidates");
    if (withItem) {
      items
          .addObject()
          .put("candidateId", "item-1")
          .putNull("dueDateCandidateId")
          .put("kind", "RECORD")
          .put("title", MEMO)
          .putObject("sourceSpan")
          .put("start", 0)
          .put("end", MEMO.length());
      ObjectNode item = (ObjectNode) items.get(0);
      item.putNull("action").putNull("object").put("confidence", 0.99);
    }
    ArrayNode relations = proposal.putArray("relationCandidates");
    if (withItem) {
      relations
          .addObject()
          .put("sourceCandidateId", "item-1")
          .put("targetType", "TAG")
          .put("targetId", TAG_ID.toString())
          .put("relationType", "RELATED_TO")
          .put("score", 0.8);
      proposal.putArray("ambiguityReasons");
    } else {
      proposal.putArray("ambiguityReasons").add("MISSING_ACTION");
    }
    proposal
        .putObject("providerMetadata")
        .put("analyzerVersion", "fake-v6")
        .put("promptVersion", "none")
        .put("localModelVersion", "none")
        .put("embeddingModelVersion", "none")
        .put("routingPolicyVersion", "field-policy-v1")
        .put("toolCalls", 0);
    return proposal;
  }

  private ObjectNode validPatch() {
    return json.createObjectNode()
        .put("version", "2")
        .put("decision", "PATCH")
        .put("itemIndex", 0)
        .put("kind", "TASK")
        .put("actionText", "접속하기")
        .put("objectText", "디스코드")
        .put("timeText", "6시");
  }

  private ObjectNode keepDecision() {
    return json.createObjectNode().put("version", "2").put("decision", "KEEP");
  }

  private ObjectNode installedModels(String digest) {
    ObjectNode response = json.createObjectNode();
    response
        .putArray("models")
        .addObject()
        .put("name", MODEL)
        .put("model", MODEL)
        .put("digest", digest);
    return response;
  }

  private ObjectNode chatResponse(String doneReason, ObjectNode patch, boolean toolCalls) {
    ObjectNode response =
        json.createObjectNode()
            .put("model", MODEL)
            .put("done", true)
            .put("done_reason", doneReason);
    ObjectNode message =
        response.putObject("message").put("role", "assistant").put("content", patch.toString());
    if (toolCalls) {
      message.putArray("tool_calls").addObject().put("name", "forbidden");
    }
    return response;
  }

  private OllamaTransportResponse jsonResponse(ObjectNode value) {
    return new OllamaTransportResponse(
        200, "application/json; charset=utf-8", value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private List<String> textValues(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }

  private static final class ScriptedTransport implements OllamaTransport {
    private final Deque<OllamaTransportResponse> responses = new ArrayDeque<>();
    private final List<OllamaTransportRequest> requests = new ArrayList<>();

    private ScriptedTransport(OllamaTransportResponse... responses) {
      this.responses.addAll(List.of(responses));
    }

    @Override
    public OllamaTransportResponse exchange(OllamaTransportRequest request) {
      requests.add(request);
      if (responses.isEmpty()) {
        throw new AssertionError("Unexpected local-model transport call.");
      }
      return responses.removeFirst();
    }
  }
}
