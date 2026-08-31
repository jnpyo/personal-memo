package local.personalmemo.analysis.domain;

/** Proposal families that may contain a semantic model contribution. */
public enum AnalysisProposalChangedField {
  SUGGESTED_TITLE("suggestedTitle"),
  TYPE_CANDIDATES("typeCandidates"),
  DATE_CANDIDATES("dateCandidates"),
  TAG_CANDIDATES("tagCandidates"),
  ITEM_CANDIDATES("itemCandidates"),
  RELATION_CANDIDATES("relationCandidates"),
  AMBIGUITY_REASONS("ambiguityReasons");

  private final String proposalField;

  AnalysisProposalChangedField(String proposalField) {
    this.proposalField = proposalField;
  }

  public String proposalField() {
    return proposalField;
  }
}
