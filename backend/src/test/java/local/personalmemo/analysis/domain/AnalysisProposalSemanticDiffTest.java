package local.personalmemo.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import local.personalmemo.common.error.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisProposalSemanticDiffTest {
  private static final String PRIVATE_TEXT = "private memo title";

  private final ObjectMapper json = new ObjectMapper();
  private final AnalysisProposalSemanticDiff diff = new AnalysisProposalSemanticDiff();

  @Test
  void providerMetadataOnlyChangesDoNotCountAsModelContribution() {
    ObjectNode local = proposal();
    ObjectNode model = local.deepCopy();
    ((ObjectNode) model.path("providerMetadata"))
        .put("analyzerVersion", "ollama-local-v1")
        .put("model", "liquidai-local");

    assertThat(diff.changedFields(local, model)).isEmpty();
  }

  @Test
  void reportsOnlyChangedSemanticFamiliesInStableOrder() {
    ObjectNode local = proposal();
    ObjectNode model = local.deepCopy();
    ((ObjectNode) model.path("suggestedTitle")).put("value", "changed");
    model.putArray("itemCandidates").add(json.createObjectNode().put("kind", "TASK"));
    model.putArray("ambiguityReasons").add("LOW_TYPE_MARGIN");

    assertThat(diff.changedFields(local, model))
        .containsExactly(
            AnalysisProposalChangedField.SUGGESTED_TITLE,
            AnalysisProposalChangedField.ITEM_CANDIDATES,
            AnalysisProposalChangedField.AMBIGUITY_REASONS);
  }

  @Test
  void treatsNestedVersionThreeEventScheduleChangesAsItemCandidateContribution() {
    ObjectNode local = proposal().put("schemaVersion", "3");
    ObjectNode item =
        local
            .putArray("itemCandidates")
            .addObject()
            .put("candidateId", "item-1")
            .put("kind", "EVENT")
            .putNull("suggestedEventScheduleCandidateId");
    item.putArray("eventScheduleCandidates")
        .addObject()
        .put("candidateId", "event-schedule-1")
        .put("score", 0.9);
    ObjectNode model = local.deepCopy();
    ((ObjectNode) model.at("/itemCandidates/0/eventScheduleCandidates/0")).put("score", 0.8);

    assertThat(diff.changedFields(local, model))
        .containsExactly(AnalysisProposalChangedField.ITEM_CANDIDATES);
  }

  @Test
  void rejectsContextMismatchWithoutLeakingProposalContent() {
    ObjectNode local = proposal();
    ObjectNode model = local.deepCopy().put("memoId", UUID.randomUUID().toString());

    assertThatThrownBy(() -> diff.changedFields(local, model))
        .isInstanceOfSatisfying(
            DomainException.class,
            exception ->
                assertThat(exception.getMessage())
                    .isEqualTo("The proposals cannot be compared safely.")
                    .doesNotContain(PRIVATE_TEXT)
                    .doesNotContain(local.toString()));
  }

  private ObjectNode proposal() {
    ObjectNode proposal = json.createObjectNode();
    proposal.put("schemaVersion", "2");
    proposal.put("memoId", "b95c206f-a948-47d5-8387-47d134705e7c");
    proposal.put("memoRevision", 1);
    proposal
        .putObject("suggestedTitle")
        .put("value", PRIVATE_TEXT)
        .put("confidence", 0.9)
        .put("needsConfirmation", true);
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "RECORD").put("score", 0.9));
    proposal.putArray("dateCandidates");
    proposal.putArray("tagCandidates");
    proposal.putArray("itemCandidates");
    proposal.putArray("relationCandidates");
    proposal.putArray("ambiguityReasons");
    proposal.putObject("providerMetadata").put("analyzerVersion", "fake-v7");
    return proposal;
  }
}
