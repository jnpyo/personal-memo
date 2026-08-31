package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import local.personalmemo.analysis.domain.AnalysisProposalValidator;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisProposalSchemaDomainMutationParityTest {
  private static final UUID MEMO_ID = UUID.fromString("b63849c0-c7f7-499f-909d-0d07f4f6cad8");
  private static final int MEMO_REVISION = 1;
  private static final String CONTENT = "11.25 OS과제 제출";
  private static final Instant BASE_INSTANT = Instant.parse("2026-08-28T00:00:00Z");
  private static final String TIME_ZONE = "Asia/Seoul";

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final Draft202012AnalysisProposalSchemaValidator schemaValidator =
      new Draft202012AnalysisProposalSchemaValidator();
  private final AnalysisProposalValidator domainValidator = new AnalysisProposalValidator();

  @Test
  void acceptsTheUnmodifiedPublicSyntheticFakeProposalAtBothLayers() {
    ObjectNode proposal = validProposal(CONTENT);

    assertAcceptedBySchema(proposal);
    assertAcceptedByDomain(proposal, CONTENT);
  }

  @TestFactory
  Stream<DynamicTest> pinsTheExpectedSchemaAndDomainMutationPartition() {
    return mutationCases()
        .map(
            mutation ->
                DynamicTest.dynamicTest(
                    mutation.name(),
                    () -> {
                      ObjectNode proposal = validProposal(CONTENT);
                      mutation.mutate().accept(proposal);

                      assertLayerOutcome(
                          mutation.schemaAccepted(),
                          () -> schemaValidator.validate(proposal),
                          proposal,
                          CONTENT);
                      assertLayerOutcome(
                          mutation.domainAccepted(),
                          () -> validateDomain(proposal, CONTENT),
                          proposal,
                          CONTENT);
                      assertThat(mutation.schemaAccepted() && mutation.domainAccepted())
                          .as("Every mutant must be rejected by at least one validation layer")
                          .isFalse();
                    }));
  }

  @Test
  void keepsUtf16SurrogateBoundaryChecksInTheDomainLayer() {
    String content = "😀 과제 제출";
    ObjectNode valid = validProposal(content);
    assertAcceptedBySchema(valid);
    assertAcceptedByDomain(valid, content);

    ObjectNode splitSurrogate = valid.deepCopy();
    ((ObjectNode) splitSurrogate.at("/itemCandidates/0/sourceSpan"))
        .put("start", 1)
        .put("end", content.length());

    assertAcceptedBySchema(splitSurrogate);
    assertRejected(() -> validateDomain(splitSurrogate, content), splitSurrogate, content);
  }

  @Test
  void keepsVersionThreeNonNullScheduleSuggestionsClosedAtTheDomainLayer() {
    String content = "오늘 오후 6시 연구 모임";
    ObjectNode proposal = validProposal(content).put("schemaVersion", "3");
    ObjectNode item = (ObjectNode) proposal.at("/itemCandidates/0");
    assertThat(item.path("kind").asText()).isEqualTo("EVENT");
    item.putArray("eventScheduleCandidates")
        .add(
            json.createObjectNode()
                .put("candidateId", "event-schedule-1")
                .put("mode", "TIMED")
                .put("startDateCandidateId", "date-1")
                .putNull("end")
                .put("score", 0.9));
    item.putNull("suggestedEventScheduleCandidateId");

    assertAcceptedBySchema(proposal);
    assertAcceptedByDomain(proposal, content);

    item.put("suggestedEventScheduleCandidateId", "event-schedule-1");

    assertAcceptedBySchema(proposal);
    assertRejected(() -> validateDomain(proposal, content), proposal, content);
  }

  private Stream<MutationCase> mutationCases() {
    return Stream.of(
        new MutationCase(
            "unknown top-level field is rejected by both layers",
            proposal -> proposal.put("unexpected", true),
            false,
            false),
        new MutationCase(
            "invalid memo UUID is rejected by both layers",
            proposal -> proposal.put("memoId", "not-a-uuid"),
            false,
            false),
        new MutationCase(
            "impossible calendar date is rejected by both layers",
            proposal -> ((ObjectNode) proposal.at("/dateCandidates/0")).put("value", "2026-02-30"),
            false,
            false),
        new MutationCase(
            "missing required provenance is rejected by both layers",
            proposal -> ((ObjectNode) proposal.path("providerMetadata")).remove("promptVersion"),
            false,
            false),
        new MutationCase(
            "metadata byte bound remains a schema-wrapper responsibility",
            proposal ->
                ((ObjectNode) proposal.path("providerMetadata"))
                    .put(
                        "opaque",
                        "x"
                            .repeat(
                                Draft202012AnalysisProposalSchemaValidator
                                    .MAX_PROVIDER_METADATA_JSON_BYTES)),
            false,
            true),
        new MutationCase(
            "duplicate type values remain a domain responsibility",
            proposal ->
                ((ArrayNode) proposal.path("typeCandidates"))
                    .add(proposal.at("/typeCandidates/0").deepCopy()),
            true,
            false),
        new MutationCase(
            "duplicate date IDs remain a domain responsibility",
            proposal ->
                ((ArrayNode) proposal.path("dateCandidates"))
                    .add(proposal.at("/dateCandidates/0").deepCopy()),
            true,
            false),
        new MutationCase(
            "dangling task due reference remains a domain responsibility",
            proposal ->
                ((ObjectNode) proposal.at("/itemCandidates/0"))
                    .put("dueDateCandidateId", "date-missing"),
            true,
            false),
        new MutationCase(
            "non-task due reference remains a domain responsibility",
            proposal -> ((ObjectNode) proposal.at("/itemCandidates/0")).put("kind", "EVENT"),
            true,
            false),
        new MutationCase(
            "imprecise task due reference remains a domain responsibility",
            this::makeBoundDateImprecise,
            true,
            false),
        new MutationCase(
            "empty source span remains a domain responsibility",
            proposal ->
                ((ObjectNode) proposal.at("/itemCandidates/0/sourceSpan"))
                    .put("start", 2)
                    .put("end", 2),
            true,
            false),
        new MutationCase(
            "out-of-bounds source span remains a domain responsibility",
            proposal ->
                ((ObjectNode) proposal.at("/itemCandidates/0/sourceSpan"))
                    .put("end", CONTENT.length() + 1),
            true,
            false),
        new MutationCase(
            "new-tag identity coherence remains a domain responsibility",
            proposal -> ((ObjectNode) proposal.at("/tagCandidates/0")).put("isNewProposal", false),
            true,
            false),
        new MutationCase(
            "nested ambiguity summary coherence remains a domain responsibility",
            proposal -> ((ArrayNode) proposal.path("ambiguityReasons")).removeAll(),
            true,
            false),
        new MutationCase(
            "dangling relation source remains a domain responsibility",
            proposal ->
                ((ArrayNode) proposal.path("relationCandidates"))
                    .add(
                        json.createObjectNode()
                            .put("sourceCandidateId", "item-missing")
                            .put("targetType", "MEMO")
                            .put("targetId", "88ce8ddb-389c-4ad3-9869-27592b56160f")
                            .put("relationType", "RELATED_TO")
                            .put("score", 0.8)),
            true,
            false));
  }

  private void makeBoundDateImprecise(ObjectNode proposal) {
    ObjectNode date = (ObjectNode) proposal.at("/dateCandidates/0");
    date.putNull("value").put("precision", "APPROXIMATE").put("timeSpecified", false);
    ArrayNode dateReasons = (ArrayNode) date.path("ambiguityReasons");
    if (!contains(dateReasons, "IMPRECISE_DATE")) {
      dateReasons.add("IMPRECISE_DATE");
    }
    ArrayNode proposalReasons = (ArrayNode) proposal.path("ambiguityReasons");
    if (!contains(proposalReasons, "IMPRECISE_DATE")) {
      proposalReasons.add("IMPRECISE_DATE");
    }
  }

  private boolean contains(ArrayNode values, String expected) {
    for (var value : values) {
      if (expected.equals(value.asText())) {
        return true;
      }
    }
    return false;
  }

  private ObjectNode validProposal(String content) {
    return analyzer.analyze(MEMO_ID, MEMO_REVISION, content, BASE_INSTANT, TIME_ZONE);
  }

  private void assertAcceptedBySchema(ObjectNode proposal) {
    assertThatCode(() -> schemaValidator.validate(proposal)).doesNotThrowAnyException();
  }

  private void assertAcceptedByDomain(ObjectNode proposal, String content) {
    assertThatCode(() -> validateDomain(proposal, content)).doesNotThrowAnyException();
  }

  private void validateDomain(ObjectNode proposal, String content) {
    domainValidator.validate(
        proposal,
        MEMO_ID,
        MEMO_REVISION,
        content,
        analyzer.provenance(),
        proposal.path("providerMetadata").path("routingPolicyVersion").asText());
  }

  private void assertLayerOutcome(
      boolean accepted,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      ObjectNode proposal,
      String content) {
    if (accepted) {
      assertThatCode(action).doesNotThrowAnyException();
    } else {
      assertRejected(action, proposal, content);
    }
  }

  private void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
      ObjectNode proposal,
      String content) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
              assertThat(exception.status().value()).isEqualTo(422);
              assertThat(exception.getMessage())
                  .doesNotContain(content)
                  .doesNotContain(proposal.toString());
            });
  }

  private record MutationCase(
      String name, Consumer<ObjectNode> mutate, boolean schemaAccepted, boolean domainAccepted) {}
}
