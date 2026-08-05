import { describe, expect, it } from 'vitest';
import type { Proposal, ProposalSummary } from '../../shared/api/types';
import { deriveCapturePolicy, deriveRecoveryState } from './recoveryModel';

const proposal: Proposal = {
  memoId: 'memo-1',
  memoRevision: 1,
  suggestedTitle: { value: '보류한 메모' },
  typeCandidates: [{ value: 'RECORD' }],
  dateCandidates: [],
  tagCandidates: [],
  itemCandidates: [{ kind: 'RECORD', title: '보류한 메모' }],
};

const postponed: ProposalSummary = {
  proposalId: 'proposal-1',
  status: 'POSTPONED',
  createdAt: '2026-08-05T01:00:00Z',
  proposal,
};

describe('recovery model', () => {
  it('keeps raw memo capture available when only recovery has failed', () => {
    expect(deriveCapturePolicy(true, null)).toBe('LOCKED');
    expect(deriveCapturePolicy(false, '복구 실패')).toBe('RAW_ONLY');
    expect(deriveCapturePolicy(false, null)).toBe('ANALYZE');
  });

  it('restores an applied application and the newest postponed proposal from server state', () => {
    const recovered = deriveRecoveryState(
      { applicationId: 'application-1', status: 'APPLIED' },
      [postponed],
    );

    expect(recovered.applicationId).toBe('application-1');
    expect(recovered.review).toBeNull();
    expect(recovered.postponedReview?.proposalId).toBe('proposal-1');
    expect(recovered.postponedReview?.title).toBe('보류한 메모');
  });

  it('restores the newest review-required proposal directly into the editor', () => {
    const recovered = deriveRecoveryState(
      { applicationId: null, status: 'NONE' },
      [
        postponed,
        {
          ...postponed,
          proposalId: 'proposal-2',
          status: 'REVIEW_REQUIRED',
          createdAt: '2026-08-05T02:00:00Z',
        },
      ],
    );

    expect(recovered.review?.proposalId).toBe('proposal-2');
    expect(recovered.postponedReview).toBeNull();
  });

  it.each(['NONE', 'UNDONE'] as const)('does not expose undo for %s server state', (status) => {
    const recovered = deriveRecoveryState(
      { applicationId: status === 'UNDONE' ? 'application-1' : null, status },
      [],
    );

    expect(recovered).toEqual({ applicationId: null, review: null, postponedReview: null });
  });
});
