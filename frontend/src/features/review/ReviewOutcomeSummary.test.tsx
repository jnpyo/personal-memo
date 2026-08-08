import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AnalysisReviewOutcomeSummary } from '../../shared/api/types';
import { ReviewOutcomeSummary } from './ReviewOutcomeSummary';

function summary(total = 24): AnalysisReviewOutcomeSummary {
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
    proposals: {
      total,
      withApplication: total === 0 ? 0 : 10,
      currentStates: {
        queued: total === 0 ? 0 : 1,
        running: total === 0 ? 0 : 1,
        reviewRequired: total === 0 ? 0 : 5,
        currentPostponed: total === 0 ? 0 : 3,
        failed: total === 0 ? 0 : 1,
        stale: total === 0 ? 0 : 2,
        applied: total === 0 ? 0 : 10,
        rejected: total === 0 ? 0 : 1,
        other: 0,
      },
    },
    latestApplications: {
      none: total === 0 ? 0 : 14,
      applied: total === 0 ? 0 : 8,
      undone: total === 0 ? 0 : 2,
    },
    outcomes: {
      exact: total === 0 ? 0 : 4,
      corrected: total === 0 ? 0 : 3,
      userResolved: total === 0 ? 0 : 2,
      unclassifiable: total === 0 ? 0 : 1,
      correctedFields: { type: 1, title: 2, tags: 1, items: 1, due: 1 },
    },
    byAnalysisVersion: total === 0
      ? []
      : [{
          route: 'LOCAL',
          analyzerVersion: 'must-not-be-visible',
          promptVersion: 'none',
          localModelVersion: 'none',
          embeddingModelVersion: 'none',
          routingPolicyVersion: 'field-policy-v1',
          proposals: {
            total: 24,
            withApplication: 10,
            currentStates: {
              queued: 1,
              running: 1,
              reviewRequired: 5,
              currentPostponed: 3,
              failed: 1,
              stale: 2,
              applied: 10,
              rejected: 1,
              other: 0,
            },
          },
          latestApplications: { none: 14, applied: 8, undone: 2 },
          outcomes: {
            exact: 4,
            corrected: 3,
            userResolved: 2,
            unclassifiable: 1,
            correctedFields: { type: 1, title: 2, tags: 1, items: 1, due: 1 },
          },
        }],
  };
}

describe('review outcome summary', () => {
  it('separates semantic comparison from lifecycle counts without calling exact an AI answer', () => {
    const markup = renderToStaticMarkup(
      <ReviewOutcomeSummary summary={summary()} loading={false} error={null} onRetry={vi.fn()} />,
    );

    expect(markup).toContain('최근 14일 제안 검토');
    expect(markup).toContain('최근 생성된 제안과 사용자의 검토·적용 상태입니다.');
    expect(markup).toContain('AI의 정답률이나 정확도를 뜻하지');
    expect(markup).toContain('제안 그대로 적용');
    expect(markup).toContain('수정 후 적용');
    expect(markup).toContain('사용자가 보완해 적용');
    expect(markup).toContain('현재 비교 규칙으로 판정할 수 없음');
    expect(markup).toContain('현재 제안 상태');
    expect(markup).toContain('최신 적용 상태');
    expect(markup).toContain('현재 제안 상태와 중복될 수 있는 별도 기준');
    expect(markup).toContain('적용 없음');
    expect(markup).not.toContain('AI 정답</dt>');
    expect(markup).not.toContain('must-not-be-visible');
  });

  it('renders an owner-local empty state without inventing rates', () => {
    const markup = renderToStaticMarkup(
      <ReviewOutcomeSummary summary={summary(0)} loading={false} error={null} onRetry={vi.fn()} />,
    );

    expect(markup).toContain('제안 총 0개');
    expect(markup).toContain('아직 집계할 제안 검토 기록이 없습니다.');
    expect(markup).not.toContain('%');
  });

  it('keeps loading and retry feedback local to the card', () => {
    const loading = renderToStaticMarkup(
      <ReviewOutcomeSummary summary={null} loading error={null} onRetry={vi.fn()} />,
    );
    const failed = renderToStaticMarkup(
      <ReviewOutcomeSummary
        summary={null}
        loading={false}
        error="집계 서버에 연결하지 못했습니다."
        onRetry={vi.fn()}
      />,
    );

    expect(loading).toContain('role="status"');
    expect(loading).toContain('제안 검토 기록을 불러오는 중');
    expect(failed).toContain('role="alert"');
    expect(failed).toContain('집계 다시 불러오기');
  });
});
