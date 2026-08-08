import { describe, expect, it } from 'vitest';
import {
  decodeReviewOutcomeSummary,
  ReviewOutcomeContractError,
} from './reviewOutcomeDecoder';

function validSummary(): Record<string, unknown> {
  const currentStates = {
    queued: 0,
    running: 0,
    reviewRequired: 0,
    currentPostponed: 0,
    failed: 0,
    stale: 0,
    applied: 1,
    rejected: 0,
    other: 0,
  };
  const correctedFields = { type: 0, title: 0, tags: 0, items: 0, due: 0 };
  const proposals = { total: 1, withApplication: 1, currentStates };
  const latestApplications = { none: 0, applied: 1, undone: 0 };
  const outcomes = {
    exact: 1,
    corrected: 0,
    userResolved: 0,
    unclassifiable: 0,
    correctedFields,
  };
  return {
    schemaVersion: '1',
    comparisonPolicyVersion: 'review-default-v1',
    cohort: {
      basis: 'PROPOSAL_CREATED_AT',
      days: 14,
      fromInclusive: '2026-07-25T00:00:00Z',
      toExclusive: '2026-08-08T00:00:00Z',
      maxProposals: 1_000,
    },
    proposals,
    latestApplications,
    outcomes,
    byAnalysisVersion: [
      {
        route: 'LOCAL',
        analyzerVersion: 'fake-v4',
        promptVersion: 'none',
        localModelVersion: 'none',
        embeddingModelVersion: 'none',
        routingPolicyVersion: 'field-policy-v1',
        proposals,
        latestApplications,
        outcomes,
      },
    ],
  };
}

describe('review outcome decoder', () => {
  it('accepts the versioned aggregate and preserves only the closed contract', () => {
    const decoded = decodeReviewOutcomeSummary(validSummary());

    expect(decoded.schemaVersion).toBe('1');
    expect(decoded.proposals.total).toBe(1);
    expect(decoded.byAnalysisVersion[0]?.route).toBe('LOCAL');
  });

  it('rejects unsupported versions and unexpected private fields', () => {
    expect(() =>
      decodeReviewOutcomeSummary({ ...validSummary(), comparisonPolicyVersion: 'future-v2' }),
    ).toThrow(ReviewOutcomeContractError);
    expect(() =>
      decodeReviewOutcomeSummary({ ...validSummary(), memoContent: 'must-not-reach-the-card' }),
    ).toThrow(ReviewOutcomeContractError);
  });

  it('rejects negative counters and broken counter invariants', () => {
    const negative = structuredClone(validSummary());
    (negative.proposals as { total: number }).total = -1;
    expect(() => decodeReviewOutcomeSummary(negative)).toThrow(ReviewOutcomeContractError);

    const mismatchedVersions = structuredClone(validSummary());
    const versions = mismatchedVersions.byAnalysisVersion as Array<{
      outcomes: { exact: number };
    }>;
    versions[0]!.outcomes.exact = 0;
    expect(() => decodeReviewOutcomeSummary(mismatchedVersions)).toThrow(
      ReviewOutcomeContractError,
    );
  });
});
