package local.personalmemo.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class Draft202012AnalysisProposalSchemaValidatorTest {
  private static final String PRIVATE_TEXT = "private memo text must stay private";

  private final ObjectMapper json = new ObjectMapper();
  private final Draft202012AnalysisProposalSchemaValidator validator =
      new Draft202012AnalysisProposalSchemaValidator();

  @Test
  void loadsTheCanonicalContractFromTheClasspathAndAcceptsAValidProposal() {
    assertThat(
            Draft202012AnalysisProposalSchemaValidator.class.getResource(
                Draft202012AnalysisProposalSchemaValidator.SCHEMA_RESOURCE))
        .isNotNull();
    assertThatCode(() -> validator.validate(validProposal())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(validVersionTwoProposal())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(validVersionThreeProposal()))
        .doesNotThrowAnyException();
  }

  @Test
  void separatesLegacyAndBindingAwareFieldsBySchemaVersion() {
    ObjectNode versionTwoMissingDateId = validVersionTwoProposal();
    ((ObjectNode) versionTwoMissingDateId.at("/dateCandidates/0")).remove("candidateId");
    ObjectNode versionTwoMissingBinding = validVersionTwoProposal();
    ((ObjectNode) versionTwoMissingBinding.at("/itemCandidates/0")).remove("dueDateCandidateId");
    ObjectNode versionOneWithBinding = validProposal();
    versionOneWithBinding
        .putArray("itemCandidates")
        .add(
            versionOneWithBinding
                .objectNode()
                .put("candidateId", "item-1")
                .putNull("dueDateCandidateId")
                .put("kind", "RECORD")
                .put("title", "legacy")
                .putNull("sourceSpan")
                .putNull("action")
                .putNull("object")
                .put("confidence", 0.9));
    ObjectNode versionThreeMissingEventFields = validVersionThreeProposal();
    ((ObjectNode) versionThreeMissingEventFields.at("/itemCandidates/0"))
        .remove("eventScheduleCandidates");
    ObjectNode versionTwoWithEventFields = validVersionTwoProposal();
    ((ObjectNode) versionTwoWithEventFields.at("/itemCandidates/0"))
        .putArray("eventScheduleCandidates");
    ((ObjectNode) versionTwoWithEventFields.at("/itemCandidates/0"))
        .putNull("suggestedEventScheduleCandidateId");

    assertInvalidWithoutDataLeak(versionTwoMissingDateId);
    assertInvalidWithoutDataLeak(versionTwoMissingBinding);
    assertInvalidWithoutDataLeak(versionOneWithBinding);
    assertInvalidWithoutDataLeak(versionThreeMissingEventFields);
    assertInvalidWithoutDataLeak(versionTwoWithEventFields);
  }

  @ParameterizedTest
  @MethodSource("invalidFormatCases")
  void enforcesDraft202012Formats(Consumer<ObjectNode> makeInvalid) {
    ObjectNode proposal = validProposal();
    makeInvalid.accept(proposal);

    assertInvalidWithoutDataLeak(proposal);
  }

  @Test
  void rejectsUnknownPropertiesWithoutExposingTheProposal() {
    ObjectNode proposal = validProposal().put("unexpected", PRIVATE_TEXT);

    assertInvalidWithoutDataLeak(proposal);
  }

  @Test
  void requiresBoundedVersionMetadataAndToolCount() {
    ObjectNode missing = validProposal();
    ((ObjectNode) missing.path("providerMetadata")).remove("promptVersion");
    ObjectNode blank = validProposal();
    ((ObjectNode) blank.path("providerMetadata")).put("localModelVersion", "   ");
    ObjectNode oversized = validProposal();
    ((ObjectNode) oversized.path("providerMetadata")).put("embeddingModelVersion", "v".repeat(65));
    ObjectNode excessiveTools = validProposal();
    ((ObjectNode) excessiveTools.path("providerMetadata")).put("toolCalls", 101);

    assertInvalidWithoutDataLeak(missing);
    assertInvalidWithoutDataLeak(blank);
    assertInvalidWithoutDataLeak(oversized);
    assertInvalidWithoutDataLeak(excessiveTools);
  }

  @Test
  void boundsProviderMetadataAndWholeProposalBySerializedUtf8Size() {
    ObjectNode oversizedMetadata = validProposal();
    ((ObjectNode) oversizedMetadata.path("providerMetadata"))
        .put(
            "opaque",
            "x"
                .repeat(
                    Draft202012AnalysisProposalSchemaValidator.MAX_PROVIDER_METADATA_JSON_BYTES));
    ObjectNode oversizedProposal = validProposal();
    ((ObjectNode) oversizedProposal.path("providerMetadata"))
        .put(
            "opaque",
            "x".repeat(Draft202012AnalysisProposalSchemaValidator.MAX_PROPOSAL_JSON_BYTES));

    assertInvalidWithoutDataLeak(oversizedMetadata);
    assertInvalidWithoutDataLeak(oversizedProposal);
  }

  @Test
  void enforcesMatchedAliasCodePointLimit() {
    ObjectNode valid = validProposal();
    valid.putArray("tagCandidates").add(tagCandidate(valid, "😀".repeat(100)));
    ObjectNode invalid = validProposal();
    invalid.putArray("tagCandidates").add(tagCandidate(invalid, "😀".repeat(101)));

    assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();
    assertInvalidWithoutDataLeak(invalid);
  }

  private static Stream<Consumer<ObjectNode>> invalidFormatCases() {
    return Stream.of(
        proposal -> proposal.put("memoId", "not-a-uuid"),
        proposal ->
            proposal
                .putArray("dateCandidates")
                .add(dateCandidate(proposal, "2026-02-30", "DATE_ONLY", false)),
        proposal ->
            proposal
                .putArray("dateCandidates")
                .add(dateCandidate(proposal, "2026-08-05T10:00:00", "EXACT_TIME", true)));
  }

  private static ObjectNode dateCandidate(
      ObjectNode proposal, String value, String precision, boolean timeSpecified) {
    ObjectNode candidate = proposal.objectNode();
    candidate.put("surfaceText", PRIVATE_TEXT);
    candidate.put("value", value);
    candidate.put("precision", precision);
    candidate.put("timeSpecified", timeSpecified);
    candidate.put("confidence", 0.9);
    candidate.putArray("ambiguityReasons");
    return candidate;
  }

  private static ObjectNode tagCandidate(ObjectNode proposal, String matchedAlias) {
    ObjectNode candidate = proposal.objectNode();
    candidate.putNull("existingTagId");
    candidate.put("canonicalName", "candidate");
    candidate.put("matchedAlias", matchedAlias);
    candidate.put("score", 0.9);
    candidate.put("isNewProposal", true);
    return candidate;
  }

  private ObjectNode validProposal() {
    ObjectNode proposal = json.createObjectNode();
    proposal.put("schemaVersion", "1");
    proposal.put("memoId", UUID.fromString("b95c206f-a948-47d5-8387-47d134705e7c").toString());
    proposal.put("memoRevision", 1);
    proposal
        .putObject("suggestedTitle")
        .put("value", "Valid title")
        .put("confidence", 0.9)
        .put("needsConfirmation", false);
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "RECORD").put("score", 0.9));
    proposal.putArray("dateCandidates");
    proposal.putArray("tagCandidates");
    proposal.putArray("itemCandidates");
    proposal.putArray("relationCandidates");
    proposal.putArray("ambiguityReasons");
    proposal
        .putObject("providerMetadata")
        .put("analyzerVersion", "fake-v2")
        .put("promptVersion", "none")
        .put("localModelVersion", "none")
        .put("embeddingModelVersion", "none")
        .put("routingPolicyVersion", "field-policy-v1")
        .put("toolCalls", 0);
    return proposal;
  }

  private ObjectNode validVersionTwoProposal() {
    ObjectNode proposal = validProposal().put("schemaVersion", "2");
    proposal
        .putArray("dateCandidates")
        .add(
            dateCandidate(proposal, "2026-11-25", "DATE_ONLY", false).put("candidateId", "date-1"));
    proposal
        .putArray("itemCandidates")
        .add(
            proposal
                .objectNode()
                .put("candidateId", "item-1")
                .put("dueDateCandidateId", "date-1")
                .put("kind", "TASK")
                .put("title", "Submit assignment")
                .putNull("sourceSpan")
                .put("action", "submit")
                .put("object", "assignment")
                .put("confidence", 0.9));
    return proposal;
  }

  private ObjectNode validVersionThreeProposal() {
    ObjectNode proposal = validProposal().put("schemaVersion", "3");
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "EVENT").put("score", 0.9));
    proposal
        .putArray("dateCandidates")
        .add(
            dateCandidate(proposal, "2026-11-25T18:00:00+09:00", "EXACT_TIME", true)
                .put("candidateId", "date-1"));
    ObjectNode item =
        proposal
            .objectNode()
            .put("candidateId", "item-1")
            .putNull("dueDateCandidateId")
            .put("kind", "EVENT")
            .put("title", "Attend event")
            .putNull("sourceSpan")
            .putNull("action")
            .putNull("object")
            .put("confidence", 0.9)
            .putNull("suggestedEventScheduleCandidateId");
    item.putArray("eventScheduleCandidates")
        .add(
            proposal
                .objectNode()
                .put("candidateId", "event-schedule-1")
                .put("mode", "TIMED")
                .put("startDateCandidateId", "date-1")
                .putNull("end")
                .put("score", 0.9));
    proposal.putArray("itemCandidates").add(item);
    return proposal;
  }

  private void assertInvalidWithoutDataLeak(ObjectNode proposal) {
    assertThatThrownBy(() -> validator.validate(proposal))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
              assertThat(exception.status().value()).isEqualTo(422);
              assertThat(exception.getMessage())
                  .isEqualTo("The analysis proposal does not match a supported schema version.")
                  .doesNotContain(PRIVATE_TEXT)
                  .doesNotContain(proposal.toString());
            });
  }
}
