package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import local.personalmemo.analysis.infrastructure.Draft202012LocalDecisionEvidenceSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LocalDecisionEvidenceProjectorTest {
  private static final String PRIVATE_TITLE = "6시에 디스코드 접속하기";
  private static final String PRIVATE_SURFACE = "오늘 오후 6시";
  private static final String PRIVATE_TAXONOMY = "개인 일정";
  private static final String PRIVATE_VERB = "접속하기";
  private static final String PRIVATE_REFERENT = "비공개 서버";
  private static final String MEMO_ID = "b95c206f-a948-47d5-8387-47d134705e7c";

  private final ObjectMapper json = new ObjectMapper();
  private final LocalDecisionEvidenceValidator validator =
      new LocalDecisionEvidenceValidator(new Draft202012LocalDecisionEvidenceSchemaValidator());
  private final LocalDecisionEvidenceProjector projector =
      new LocalDecisionEvidenceProjector(json, validator);

  @Test
  void projectsOnlyBoundedStructuralFactsAndStrictServerOwnedReasons() {
    LocalDecisionEvidenceProjection projection = projector.project(fallbackProposal());

    assertThat(projection.evidence().at("/typeSummary/leader").asText()).isEqualTo("UNKNOWN");
    assertThat(projection.evidence().at("/temporalSummary/impreciseCount").asInt()).isOne();
    assertThat(projection.evidence().at("/taxonomySummary/newProposalCount").asInt()).isOne();
    assertThat(projection.evidence().at("/itemSummary/candidateCount").asInt()).isZero();
    assertThat(projection.fallbackReasonCodes())
        .contains(
            FallbackReasonCode.DEFAULT_RECORD_FALLBACK,
            FallbackReasonCode.UNPARSED_TEMPORAL_CUE,
            FallbackReasonCode.UNRECOGNIZED_ACTION_CUE,
            FallbackReasonCode.LOW_TYPE_MARGIN,
            FallbackReasonCode.TAG_UNCERTAINTY,
            FallbackReasonCode.DATE_UNCERTAINTY,
            FallbackReasonCode.INCOMPLETE_TASK);
    validator.validate(projection.evidence());

    String serialized = projection.evidence().toString();
    assertThat(serialized)
        .doesNotContain(
            PRIVATE_TITLE,
            PRIVATE_SURFACE,
            PRIVATE_TAXONOMY,
            PRIVATE_VERB,
            PRIVATE_REFERENT,
            MEMO_ID,
            "memoId",
            "suggestedTitle",
            "surfaceText",
            "canonicalName",
            "candidateId",
            "existingTagId",
            "providerMetadata");
  }

  @Test
  void countsTaskStructureWithoutCopyingTaskTextOrIdentifiers() {
    LocalDecisionEvidenceProjection projection = projector.project(taskProposal());

    assertThat(projection.evidence().at("/itemSummary/candidateCount").asInt()).isOne();
    assertThat(projection.evidence().at("/itemSummary/taskCount").asInt()).isOne();
    assertThat(projection.evidence().at("/itemSummary/verbPresentCount").asInt()).isOne();
    assertThat(projection.evidence().at("/itemSummary/referentPresentCount").asInt()).isOne();
    assertThat(projection.evidence().at("/itemSummary/dueBindingCount").asInt()).isOne();
    assertThat(projection.fallbackReasonCodes())
        .containsExactly(FallbackReasonCode.LOW_TYPE_MARGIN);
    assertThat(projection.evidence().toString())
        .doesNotContain(PRIVATE_TITLE, PRIVATE_VERB, PRIVATE_REFERENT, MEMO_ID, "item-1", "date-1");
  }

  @Test
  void preservesAnEmptySemanticFallbackSetForAiPreferredInvocationEvidence() {
    ObjectNode proposal = taskProposal();
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.95));
    proposal.putArray("ambiguityReasons");

    LocalDecisionEvidenceProjection projection = projector.project(proposal);

    assertThat(projection.fallbackReasonCodes()).isEmpty();
    validator.validate(projection.evidence());
  }

  private ObjectNode fallbackProposal() {
    ObjectNode proposal = baseProposal();
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "UNKNOWN").put("score", 0.52))
        .add(json.createObjectNode().put("value", "RECORD").put("score", 0.50));
    proposal
        .putArray("dateCandidates")
        .add(
            json.createObjectNode()
                .put("candidateId", "date-private")
                .put("surfaceText", PRIVATE_SURFACE)
                .putNull("value")
                .put("precision", "UNKNOWN")
                .put("timeSpecified", false)
                .put("confidence", 0.4)
                .set(
                    "ambiguityReasons",
                    json.createArrayNode().add("MISSING_TIME").add("IMPRECISE_DATE")));
    proposal
        .putArray("tagCandidates")
        .add(
            json.createObjectNode()
                .putNull("existingTagId")
                .put("canonicalName", PRIVATE_TAXONOMY)
                .putNull("matchedAlias")
                .put("score", 0.4)
                .put("isNewProposal", true));
    proposal.putArray("itemCandidates");
    proposal.putArray("ambiguityReasons").add("MISSING_ACTION").add("NEW_TOPIC");
    ((ObjectNode) proposal.path("providerMetadata"))
        .put("classificationBasis", "DEFAULT_FALLBACK")
        .put("unparsedTemporalCueCount", 1)
        .put("unrecognizedActionCueCount", 1);
    return proposal;
  }

  private ObjectNode taskProposal() {
    ObjectNode proposal = baseProposal();
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "TASK").put("score", 0.80))
        .add(json.createObjectNode().put("value", "INFORMATION").put("score", 0.75));
    proposal
        .putArray("dateCandidates")
        .add(
            json.createObjectNode()
                .put("candidateId", "date-1")
                .put("surfaceText", PRIVATE_SURFACE)
                .put("value", "2026-08-21T18:00:00+09:00")
                .put("precision", "EXACT_TIME")
                .put("timeSpecified", true)
                .put("confidence", 0.9)
                .set("ambiguityReasons", json.createArrayNode()));
    proposal.putArray("tagCandidates");
    proposal
        .putArray("itemCandidates")
        .add(
            json.createObjectNode()
                .put("candidateId", "item-1")
                .put("dueDateCandidateId", "date-1")
                .put("kind", "TASK")
                .put("title", PRIVATE_TITLE)
                .putNull("sourceSpan")
                .put("action", PRIVATE_VERB)
                .put("object", PRIVATE_REFERENT)
                .put("confidence", 0.9));
    proposal.putArray("ambiguityReasons").add("LOW_TYPE_MARGIN");
    return proposal;
  }

  private ObjectNode baseProposal() {
    ObjectNode proposal = json.createObjectNode();
    proposal.put("schemaVersion", "2");
    proposal.put("memoId", UUID.fromString(MEMO_ID).toString());
    proposal.put("memoRevision", 1);
    proposal
        .putObject("suggestedTitle")
        .put("value", PRIVATE_TITLE)
        .put("confidence", 0.8)
        .put("needsConfirmation", true);
    proposal.putArray("typeCandidates");
    proposal.putArray("dateCandidates");
    proposal.putArray("tagCandidates");
    proposal.putArray("itemCandidates");
    proposal.putArray("relationCandidates");
    proposal.putArray("ambiguityReasons");
    proposal
        .putObject("providerMetadata")
        .put("analyzerVersion", "fake-v7")
        .put("promptVersion", "none")
        .put("localModelVersion", "none")
        .put("embeddingModelVersion", "none")
        .put("routingPolicyVersion", "field-policy-v2")
        .put("classificationBasis", "EXPLICIT_RULE")
        .put("unparsedTemporalCueCount", 0)
        .put("unrecognizedActionCueCount", 0)
        .put("toolCalls", 0);
    return proposal;
  }
}
