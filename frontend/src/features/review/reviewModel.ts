import type {
  ApplyProposalRequest,
  ItemCandidate,
  ItemKind,
  Proposal,
  TagCandidate,
} from '../../shared/api/types';

export type ReviewDraft = {
  proposalId: string;
  proposal: Proposal;
  title: string;
  selectedType: ItemKind;
  tags: TagCandidate[];
  items: ItemCandidate[];
};

export function createReviewDraft(proposalId: string, proposal: Proposal): ReviewDraft {
  return {
    proposalId,
    proposal,
    title: proposal.suggestedTitle.value,
    selectedType: proposal.typeCandidates[0]?.value ?? 'RECORD',
    tags: proposal.tagCandidates,
    items: proposal.itemCandidates,
  };
}
export function buildApplyRequest(review: ReviewDraft, timeZone: string): ApplyProposalRequest {
  const due = review.proposal.dateCandidates[0];

  return {
    expectedMemoRevision: review.proposal.memoRevision,
    selectedType: review.selectedType,
    title: review.title.trim(),
    selectedTags: review.tags.map((tag) => ({
      existingTagId: tag.existingTagId,
      newCanonicalName: tag.existingTagId ? null : tag.canonicalName.trim(),
    })),
    items: review.items.map((item) => ({
      kind: item.kind,
      title: item.title.trim(),
      due: item.kind === 'TASK' && due ? { ...due, timeZone } : null,
    })),
  };
}
