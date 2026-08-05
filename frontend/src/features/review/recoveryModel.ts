import type { LatestApplication, ProposalSummary } from '../../shared/api/types';
import { createReviewDraft, type ReviewDraft } from './reviewModel';

export type RecoveryState = {
  applicationId: string | null;
  review: ReviewDraft | null;
  postponedReview: ReviewDraft | null;
};

export type CapturePolicy = 'LOCKED' | 'RAW_ONLY' | 'ANALYZE';

export function deriveCapturePolicy(loading: boolean, error: string | null): CapturePolicy {
  if (loading) return 'LOCKED';
  return error ? 'RAW_ONLY' : 'ANALYZE';
}

export function deriveRecoveryState(
  latestApplication: LatestApplication,
  proposals: ProposalSummary[],
): RecoveryState {
  const pending = [...proposals].sort((left, right) => {
    const byCreatedAt = Date.parse(right.createdAt) - Date.parse(left.createdAt);
    return byCreatedAt || right.proposalId.localeCompare(left.proposalId);
  })[0];
  const draft = pending ? createReviewDraft(pending.proposalId, pending.proposal) : null;
  return {
    applicationId:
      latestApplication.status === 'APPLIED' ? latestApplication.applicationId : null,
    review: pending?.status === 'REVIEW_REQUIRED' ? draft : null,
    postponedReview: pending?.status === 'POSTPONED' ? draft : null,
  };
}
