package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisProposalContractBoundaryTest {
  private static final String CONTENT = "11.25 OS과제 제출";
  private static final UUID MEMO_ID = UUID.fromString("8dd29246-4ec2-4e7f-bbf9-a3ff316acdd4");
  private static final String SHORT_UUID = "1-1-1-1-1";
  private static final UUID EXPANDED_SHORT_UUID =
      UUID.fromString("00000001-0001-0001-0001-000000000001");

  private final ObjectMapper json = new ObjectMapper();
  private final FakeAnalyzer analyzer = new FakeAnalyzer(json);
  private final AnalysisProposalValidator validator = new AnalysisProposalValidator();

  @TestFactory
  Stream<DynamicTest> rejectsUnknownPropertiesAtEveryClosedObjectLevel() {
    return Stream.of(
        unknownPropertyCase("proposal", proposal -> proposal.put("unexpected", true)),
        unknownPropertyCase(
            "suggestedTitle",
            proposal -> ((ObjectNode) proposal.path("suggestedTitle")).put("unexpected", true)),
        unknownPropertyCase(
            "type candidate",
            proposal -> ((ObjectNode) proposal.at("/typeCandidates/0")).put("unexpected", true)),
        unknownPropertyCase(
            "date candidate",
            proposal -> ((ObjectNode) proposal.at("/dateCandidates/0")).put("unexpected", true)),
        unknownPropertyCase(
            "tag candidate",
            proposal -> ((ObjectNode) proposal.at("/tagCandidates/0")).put("unexpected", true)),
        unknownPropertyCase(
            "item candidate",
            proposal -> ((ObjectNode) proposal.at("/itemCandidates/0")).put("unexpected", true)),
        unknownPropertyCase(
            "source span",
            proposal ->
                ((ObjectNode) proposal.at("/itemCandidates/0/sourceSpan")).put("unexpected", true)),
        unknownPropertyCase(
            "relation candidate",
            proposal ->
                ((ObjectNode) proposal.at("/relationCandidates/0")).put("unexpected", true)));
  }

  @Test
  void providerMetadataRemainsAnExplicitlyOpenObject() {
    ObjectNode proposal = richProposal();
    ((ObjectNode) proposal.path("providerMetadata"))
        .put("providerSpecificText", "opaque")
        .set("providerSpecificObject", json.createObjectNode().put("attempt", 1));

    assertThatCode(() -> validate(proposal)).doesNotThrowAnyException();
  }

  @Test
  void requiresBoundedProviderVersionsAndToolCount() {
    ObjectNode missingVersion = richProposal();
    ((ObjectNode) missingVersion.path("providerMetadata")).remove("promptVersion");
    ObjectNode oversizedVersion = richProposal();
    ((ObjectNode) oversizedVersion.path("providerMetadata"))
        .put("localModelVersion", "v".repeat(65));
    ObjectNode excessiveToolCalls = richProposal();
    ((ObjectNode) excessiveToolCalls.path("providerMetadata")).put("toolCalls", 101);

    assertInvalid(() -> validate(missingVersion));
    assertInvalid(() -> validate(oversizedVersion));
    assertInvalid(() -> validate(excessiveToolCalls));
  }

  @Test
  void rejectsProviderVersionsThatContradictServerOwnedProvenance() {
    ObjectNode proposal = richProposal();
    ((ObjectNode) proposal.path("providerMetadata")).put("embeddingModelVersion", "other");

    assertInvalid(
        () -> validator.validate(proposal, MEMO_ID, 1, CONTENT.length(), analyzer.provenance()));
  }

  @Test
  void rejectsRoutingVersionThatContradictsServerOwnedPolicy() {
    ObjectNode proposal = richProposal();

    assertInvalid(
        () ->
            validator.validate(
                proposal, MEMO_ID, 1, CONTENT.length(), analyzer.provenance(), "field-policy-v1"));
  }

  @Test
  void boundsMatchedAliasByUnicodeCodePoint() {
    ObjectNode oneHundredAliases = richProposal();
    ((ObjectNode) oneHundredAliases.at("/tagCandidates/0")).put("matchedAlias", "😀".repeat(100));
    ObjectNode oneHundredOneAliases = richProposal();
    ((ObjectNode) oneHundredOneAliases.at("/tagCandidates/0"))
        .put("matchedAlias", "😀".repeat(101));

    assertThatCode(() -> validate(oneHundredAliases)).doesNotThrowAnyException();
    assertInvalid(() -> validate(oneHundredOneAliases));
  }

  @Test
  void rejectsIntegralNumbersThatCannotBeRepresentedAsThirtyTwoBitOffsets() {
    ObjectNode revisionOverflow = richProposal().put("memoRevision", 4_294_967_297L);
    ObjectNode spanOverflow = richProposal();
    ((ObjectNode) spanOverflow.at("/itemCandidates/0/sourceSpan")).put("end", 4_294_967_296L);

    assertInvalid(() -> validate(revisionOverflow));
    assertInvalid(() -> validate(spanOverflow));
  }

  @Test
  void rejectsUuidTextThatParsesButIsNotCanonical() {
    ObjectNode memoId = richProposal().put("memoId", SHORT_UUID);
    ObjectNode tagId = richProposal();
    ((ObjectNode) tagId.at("/tagCandidates/0")).put("existingTagId", SHORT_UUID);
    ObjectNode relationTarget = richProposal();
    ((ObjectNode) relationTarget.at("/relationCandidates/0")).put("targetId", SHORT_UUID);

    assertInvalid(
        () ->
            validator.validate(
                memoId, EXPANDED_SHORT_UUID, 1, CONTENT.codePointCount(0, CONTENT.length())));
    assertInvalid(() -> validate(tagId));
    assertInvalid(() -> validate(relationTarget));
  }

  @Test
  void appliesStringBoundsByUnicodeCodePointRatherThanUtf16CodeUnit() {
    ObjectNode twoHundredEmoji = richProposal();
    ((ObjectNode) twoHundredEmoji.path("suggestedTitle")).put("value", "😀".repeat(200));
    ObjectNode twoHundredOneEmoji = richProposal();
    ((ObjectNode) twoHundredOneEmoji.path("suggestedTitle")).put("value", "😀".repeat(201));

    assertThatCode(() -> validate(twoHundredEmoji)).doesNotThrowAnyException();
    assertInvalid(() -> validate(twoHundredOneEmoji));
  }

  @ParameterizedTest(name = "valid {0}, value={1}, timeSpecified={2}")
  @MethodSource("validDateCombinations")
  void acceptsOnlyCoherentDateCombinations(String precision, String value, boolean timeSpecified) {
    ObjectNode proposal = proposalWithDate(precision, value, timeSpecified);

    assertThatCode(() -> validate(proposal)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "invalid {0}, value={1}, timeSpecified={2}")
  @MethodSource("invalidDateCombinations")
  void rejectsIncoherentDateCombinations(String precision, String value, boolean timeSpecified) {
    ObjectNode proposal = proposalWithDate(precision, value, timeSpecified);

    assertInvalid(() -> validate(proposal));
  }

  @Test
  void rejectsNestedDateAmbiguityMissingFromTheTopLevelSummary() {
    ObjectNode proposal = richProposal();
    ((ArrayNode) proposal.path("ambiguityReasons")).removeAll();

    assertInvalid(() -> validate(proposal));
  }

  @Test
  void rejectsStructuralCriticalSignalsHiddenFromTheSummary() {
    ObjectNode newTag = richProposal();
    ObjectNode tag = (ObjectNode) newTag.at("/tagCandidates/0");
    tag.putNull("existingTagId").put("isNewProposal", true);
    removeAmbiguityReason(newTag, "NEW_TOPIC");

    ObjectNode unknownType = richProposal();
    ((ObjectNode) unknownType.at("/typeCandidates/0")).put("value", "UNKNOWN");

    ObjectNode missingAction = richProposal();
    ((ObjectNode) missingAction.at("/itemCandidates/0")).putNull("action");

    assertInvalid(() -> validate(newTag));
    assertInvalid(() -> validate(unknownType));
    assertInvalid(() -> validate(missingAction));
  }

  private void removeAmbiguityReason(ObjectNode proposal, String reason) {
    ArrayNode reasons = (ArrayNode) proposal.path("ambiguityReasons");
    for (int index = reasons.size() - 1; index >= 0; index--) {
      if (reason.equals(reasons.get(index).asText())) {
        reasons.remove(index);
      }
    }
  }

  @Test
  void rejectsDuplicateTypesBlankTaskFieldsAndTypeItemConflicts() {
    ObjectNode duplicateType = richProposal();
    ((ArrayNode) duplicateType.path("typeCandidates"))
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.4));

    ObjectNode taskWithoutItems = richProposal();
    ((ArrayNode) taskWithoutItems.path("itemCandidates")).removeAll();
    ((ArrayNode) taskWithoutItems.path("relationCandidates")).removeAll();

    ObjectNode conflictingKind = richProposal();
    ((ObjectNode) conflictingKind.at("/itemCandidates/0")).put("kind", "INFORMATION");

    ObjectNode blankAction = richProposal();
    ((ObjectNode) blankAction.at("/itemCandidates/0")).put("action", " ");

    ObjectNode blankObject = richProposal();
    ((ObjectNode) blankObject.at("/itemCandidates/0")).put("object", "");

    assertInvalid(() -> validate(duplicateType));
    assertInvalid(() -> validate(taskWithoutItems));
    assertInvalid(() -> validate(conflictingKind));
    assertInvalid(() -> validate(blankAction));
    assertInvalid(() -> validate(blankObject));
  }

  private DynamicTest unknownPropertyCase(String name, Consumer<ObjectNode> insertUnknownProperty) {
    return DynamicTest.dynamicTest(
        name,
        () -> {
          ObjectNode proposal = richProposal();
          insertUnknownProperty.accept(proposal);
          assertInvalid(() -> validate(proposal));
        });
  }

  private static Stream<Arguments> validDateCombinations() {
    return Stream.of(
        Arguments.of("DATE_ONLY", "2026-11-25", false),
        Arguments.of("EXACT_TIME", "2026-11-25T18:00:00+09:00", true),
        Arguments.of("RELATIVE_EXACT", "2026-11-25T18:00:00+09:00", true),
        Arguments.of("APPROXIMATE", null, false),
        Arguments.of("UNKNOWN", null, false));
  }

  private static Stream<Arguments> invalidDateCombinations() {
    return Stream.of(
        Arguments.of("DATE_ONLY", null, false),
        Arguments.of("DATE_ONLY", "2026-11-25", true),
        Arguments.of("EXACT_TIME", null, true),
        Arguments.of("EXACT_TIME", "2026-11-25T18:00:00+09:00", false),
        Arguments.of("RELATIVE_EXACT", null, true),
        Arguments.of("RELATIVE_EXACT", "2026-11-25T18:00:00+09:00", false),
        Arguments.of("APPROXIMATE", "2026-11-25", false),
        Arguments.of("APPROXIMATE", null, true),
        Arguments.of("UNKNOWN", "2026-11-25", false),
        Arguments.of("UNKNOWN", null, true));
  }

  private ObjectNode proposalWithDate(String precision, String value, boolean timeSpecified) {
    ObjectNode proposal = richProposal();
    ObjectNode date = (ObjectNode) proposal.at("/dateCandidates/0");
    date.put("precision", precision).put("timeSpecified", timeSpecified);
    if (value == null) {
      date.putNull("value");
    } else {
      date.put("value", value);
    }
    if (Set.of("APPROXIMATE", "UNKNOWN").contains(precision)) {
      date.putArray("ambiguityReasons").add("IMPRECISE_DATE");
      ((ObjectNode) proposal.at("/itemCandidates/0")).putNull("dueDateCandidateId");
      ArrayNode summary = (ArrayNode) proposal.path("ambiguityReasons");
      summary.add("IMPRECISE_DATE");
    }
    return proposal;
  }

  private ObjectNode richProposal() {
    ObjectNode proposal =
        analyzer.analyze(MEMO_ID, 1, CONTENT, Instant.parse("2026-08-05T02:00:00Z"), "Asia/Seoul");
    ((ObjectNode) proposal.at("/itemCandidates/0"))
        .set("sourceSpan", json.createObjectNode().put("start", 0).put("end", 1));
    ArrayNode relations = (ArrayNode) proposal.path("relationCandidates");
    relations.add(
        json.createObjectNode()
            .put("sourceCandidateId", "item-1")
            .put("targetType", "MEMO")
            .put("targetId", UUID.fromString("44be2557-6531-4f13-ae30-fcaf56297075").toString())
            .put("relationType", "RELATED_TO")
            .put("score", 0.8));
    return proposal;
  }

  private void validate(ObjectNode proposal) {
    validator.validate(proposal, MEMO_ID, 1, CONTENT.length());
  }

  private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
              assertThat(exception.status().value()).isEqualTo(422);
              assertThat(exception.getMessage()).doesNotContain(CONTENT);
            });
  }
}
