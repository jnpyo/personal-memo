package local.personalmemo.analysis.domain;

import java.util.ArrayList;
import java.util.List;
import local.personalmemo.common.error.DomainException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Compares proposal semantics while deliberately excluding provider-owned metadata. */
@Component
public final class AnalysisProposalSemanticDiff {
  private static final List<String> CONTEXT_FIELDS =
      List.of("schemaVersion", "memoId", "memoRevision");

  public List<AnalysisProposalChangedField> changedFields(
      JsonNode localProposal, JsonNode modelProposal) {
    requireComparable(localProposal, modelProposal);
    List<AnalysisProposalChangedField> changed = new ArrayList<>();
    for (AnalysisProposalChangedField field : AnalysisProposalChangedField.values()) {
      if (!localProposal
          .path(field.proposalField())
          .equals(modelProposal.path(field.proposalField()))) {
        changed.add(field);
      }
    }
    return List.copyOf(changed);
  }

  private void requireComparable(JsonNode localProposal, JsonNode modelProposal) {
    if (localProposal == null
        || modelProposal == null
        || !localProposal.isObject()
        || !modelProposal.isObject()) {
      fail();
    }
    for (String contextField : CONTEXT_FIELDS) {
      JsonNode local = localProposal.get(contextField);
      JsonNode model = modelProposal.get(contextField);
      if (local == null || model == null || !local.equals(model)) {
        fail();
      }
    }
    for (AnalysisProposalChangedField field : AnalysisProposalChangedField.values()) {
      if (!localProposal.has(field.proposalField()) || !modelProposal.has(field.proposalField())) {
        fail();
      }
    }
  }

  private static void fail() {
    throw DomainException.invalid(
        "INVALID_ANALYSIS_PROPOSAL_DIFF", "The proposals cannot be compared safely.");
  }
}
