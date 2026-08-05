import { describe, expect, it } from 'vitest';
import type { Proposal } from '../../shared/api/types';
import { buildApplyRequest, createReviewDraft } from './reviewModel';

const proposal: Proposal = {
  memoId: 'memo-1',
  memoRevision: 2,
  suggestedTitle: { value: '운영체제 과제' },
  typeCandidates: [{ value: 'TASK' }],
  dateCandidates: [
    { surfaceText: '11.25', value: '2026-11-25', precision: 'DATE_ONLY', timeSpecified: false },
  ],
  tagCandidates: [
    { existingTagId: null, canonicalName: ' 운영체제 ', matchedAlias: null },
  ],
  itemCandidates: [{ candidateId: 'item-1', kind: 'TASK', title: ' 과제 제출 ' }],
};

describe('review model', () => {
  it('creates an editable draft without mutating the proposal', () => {
    const draft = createReviewDraft('proposal-1', proposal);
    const changed = { ...draft, title: '수정된 제목' };

    expect(changed.title).toBe('수정된 제목');
    expect(proposal.suggestedTitle.value).toBe('운영체제 과제');
  });

  it('builds a trimmed apply request while retaining date provenance', () => {
    const request = buildApplyRequest(createReviewDraft('proposal-1', proposal), 'Asia/Seoul');

    expect(request.expectedMemoRevision).toBe(2);
    expect(request.selectedTags[0]).toEqual({ existingTagId: null, newCanonicalName: '운영체제' });
    expect(request.items[0]).toEqual({
      kind: 'TASK',
      title: '과제 제출',
      due: { ...proposal.dateCandidates[0], timeZone: 'Asia/Seoul' },
    });
  });
});
