import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { Proposal } from '../../shared/api/types';
import { createReviewDraft } from './reviewModel';
import { ProposalReview } from './ProposalReview';

const taskProposal: Proposal = {
  schemaVersion: '1',
  memoId: 'memo-1',
  memoRevision: 1,
  suggestedTitle: {
    value: '운영체제 과제 제출',
    confidence: 0.92,
    needsConfirmation: true,
  },
  typeCandidates: [
    { value: 'TASK', score: 0.9 },
    { value: 'EVENT', score: 0.4 },
  ],
  dateCandidates: [],
  tagCandidates: [
    {
      existingTagId: null,
      canonicalName: '운영체제',
      matchedAlias: null,
      score: 0.8,
      isNewProposal: true,
    },
  ],
  itemCandidates: [
    {
      candidateId: 'item-1',
      kind: 'TASK',
      title: '운영체제 과제 제출',
      sourceSpan: null,
      action: '제출',
      object: '운영체제 과제',
      confidence: 0.9,
    },
  ],
  relationCandidates: [],
  ambiguityReasons: [],
  providerMetadata: {},
};

function renderProposal(
  proposal: Proposal,
  options: { busy?: boolean; retryScope?: string } = {},
): string {
  return renderToStaticMarkup(
    <ProposalReview
      review={createReviewDraft('proposal-1', proposal)}
      busy={options.busy ?? false}
      onChange={vi.fn()}
      onApply={vi.fn()}
      onPostpone={vi.fn()}
      onReject={vi.fn()}
      onTransientDirtyChange={vi.fn()}
      feedback={options.retryScope ? { kind: 'error', message: '적용 실패' } : null}
      retryScope={options.retryScope}
      retryLabel="승인 다시 시도"
      onRetry={vi.fn()}
      onDismissFeedback={vi.fn()}
    />,
  );
}

describe('proposal review dialog', () => {
  it('starts with a concise yes-or-no summary without exposing the full editor', () => {
    const markup = renderProposal(taskProposal);

    expect(markup).toContain('<dialog');
    expect(markup).toContain('AI는 이렇게 이해했어요.');
    expect(markup).toContain('예, 이대로 적용');
    expect(markup).toContain('아니오, 다른 경우 보기');
    expect(markup).toContain('운영체제 과제 제출');
    expect(markup).not.toContain('대표 제목');
  });

  it('starts an UNKNOWN proposal with explicit possible types and no yes action', () => {
    const markup = renderProposal({
      ...taskProposal,
      typeCandidates: [{ value: 'UNKNOWN', score: 0.4 }],
      itemCandidates: [],
    });

    expect(markup).toContain('AI가 유형을 확정하지 못했어요.');
    expect(markup).toContain('aria-label="할 일 유형 선택"');
    expect(markup).toContain('aria-label="일정 유형 선택"');
    expect(markup).not.toContain('예, 이대로 적용');
    expect(markup).not.toContain('대표 제목');
  });

  it('does not offer an invalid return to the summary when a known type has no item', () => {
    const markup = renderProposal({
      ...taskProposal,
      itemCandidates: [],
    });

    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).toContain('유형은 맞아요');
    expect(markup).not.toContain('AI 추천으로 돌아가기');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('requires detailed review when dates cannot be mapped to one task unambiguously', () => {
    const markup = renderProposal({
      ...taskProposal,
      dateCandidates: [
        {
          surfaceText: '11월 20일',
          value: '2026-11-20',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          confidence: 0.9,
          ambiguityReasons: ['MISSING_YEAR', 'MISSING_TIME'],
        },
        {
          surfaceText: '11월 25일',
          value: '2026-11-25',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          confidence: 0.9,
          ambiguityReasons: ['MISSING_YEAR', 'MISSING_TIME'],
        },
      ],
    });

    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).toContain('유형은 맞아요');
    expect(markup).not.toContain('AI 추천으로 돌아가기');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('requires detailed review for a mixed item set or an imprecise date', () => {
    const exactDate: Proposal['dateCandidates'][number] = {
      surfaceText: '11월 25일',
      value: '2026-11-25',
      precision: 'DATE_ONLY' as const,
      timeSpecified: false,
      confidence: 0.9,
      ambiguityReasons: ['MISSING_YEAR'],
    };
    const mixedMarkup = renderProposal({
      ...taskProposal,
      dateCandidates: [exactDate],
      itemCandidates: [
        taskProposal.itemCandidates[0],
        { ...taskProposal.itemCandidates[0], candidateId: 'event-1', kind: 'EVENT', title: '회의' },
      ],
    });
    const approximateMarkup = renderProposal({
      ...taskProposal,
      dateCandidates: [
        {
          surfaceText: '주말쯤',
          value: null,
          precision: 'APPROXIMATE',
          timeSpecified: false,
          confidence: 0.5,
          ambiguityReasons: ['IMPRECISE_DATE'],
        },
      ],
    });

    expect(mixedMarkup).toContain('어떤 부분이 다른가요?');
    expect(mixedMarkup).not.toContain('예, 이대로 적용');
    expect(approximateMarkup).toContain('어떤 부분이 다른가요?');
    expect(approximateMarkup).not.toContain('예, 이대로 적용');
  });

  it('does not expose a retry while another proposal operation is in flight', () => {
    const markup = renderProposal(taskProposal, {
      busy: true,
      retryScope: 'apply:proposal-1',
    });

    expect(markup).toContain('적용 실패');
    expect(markup).not.toContain('승인 다시 시도');
  });
});
