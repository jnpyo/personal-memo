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
      dueDateCandidateId: null,
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

const noNetworkCloudEvidence = {
  routingPolicyVersion: 'field-policy-v1',
  cloudTransferMode: 'NO_NETWORK',
  cloudGatewayVersion: 'fake-cloud-v2',
  cloudProviderId: 'fake',
  cloudModelVersion: 'none',
  cloudConsentPolicyVersion: 'no-network-v1',
  cloudToolCalls: 0,
  cloudMutationCalls: 0,
  cloudResolvedFields: [],
  receivedRoutingPolicyVersion: 'field-policy-v1',
  receivedRoutingReasons: ['IMPRECISE_DATE'],
};

const externalCloudEvidence = {
  routingPolicyVersion: 'field-policy-v1',
  cloudTransferMode: 'EXTERNAL_MEMO_CONTENT',
  cloudGatewayVersion: 'external-test-v1',
  cloudProviderId: 'external-test',
  cloudModelVersion: 'external-model-v1',
  cloudConsentPolicyVersion: 'external-policy-v1',
  cloudToolCalls: 0,
  cloudMutationCalls: 0,
  cloudResolvedFields: [],
  receivedRoutingPolicyVersion: 'field-policy-v1',
  receivedRoutingReasons: ['IMPRECISE_DATE'],
};

function explicitDateProposal(shared = false): Proposal {
  const dates: Proposal['dateCandidates'] = [
    {
      candidateId: 'date-1',
      surfaceText: '11월 20일',
      value: '2026-11-20',
      precision: 'DATE_ONLY',
      timeSpecified: false,
      confidence: 0.92,
      ambiguityReasons: [],
    },
    ...(shared
      ? []
      : [{
          candidateId: 'date-2',
          surfaceText: '11월 25일 오후 6시',
          value: '2026-11-25T18:00:00+09:00',
          precision: 'EXACT_TIME' as const,
          timeSpecified: true,
          confidence: 0.91,
          ambiguityReasons: [],
        }]),
  ];
  return {
    ...taskProposal,
    schemaVersion: '2',
    suggestedTitle: { value: '보고서 제출', confidence: 0.94, needsConfirmation: true },
    dateCandidates: dates,
    itemCandidates: [
      {
        ...taskProposal.itemCandidates[0],
        candidateId: 'item-1',
        dueDateCandidateId: 'date-1',
        title: '보고서 제출',
      },
      {
        ...taskProposal.itemCandidates[0],
        candidateId: 'item-2',
        dueDateCandidateId: shared ? 'date-1' : 'date-2',
        title: '발표 준비',
      },
    ],
  };
}

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
  it('keeps a legacy or local proposal with no cloud metadata concise', () => {
    const markup = renderProposal(taskProposal);

    expect(markup).toContain('<dialog');
    expect(markup).toContain('AI는 이렇게 이해했어요.');
    expect(markup).toContain('예, 이대로 적용');
    expect(markup).toContain('아니오, 다른 경우 보기');
    expect(markup).toContain('운영체제 과제 제출');
    expect(markup).not.toContain('대표 제목');
  });

  it('shows explicit v2 multi-task date bindings in the concise yes-or-no summary', () => {
    const markup = renderProposal(explicitDateProposal());

    expect(markup).toContain('AI는 이렇게 이해했어요.');
    expect(markup).toContain('예, 이대로 적용');
    expect(markup).toContain('마감 11월 20일 → 2026-11-20');
    expect(markup).toContain('마감 11월 25일 오후 6시 → 2026-11-25T18:00:00+09:00');
    expect(markup).not.toContain('(DATE_ONLY)');
    expect(markup).not.toContain('(EXACT_TIME)');
  });

  it('supports one explicit v2 date shared by multiple task candidates', () => {
    const markup = renderProposal(explicitDateProposal(true));

    expect(markup).toContain('예, 이대로 적용');
    expect(markup.match(/마감 11월 20일 → 2026-11-20/g)).toHaveLength(2);
  });

  it('states that a task has no due date before the user approves it', () => {
    const markup = renderProposal({
      ...taskProposal,
      schemaVersion: '2',
      dateCandidates: [],
      itemCandidates: [{ ...taskProposal.itemCandidates[0], dueDateCandidateId: null }],
    });

    expect(markup).toContain('예, 이대로 적용');
    expect(markup).toContain('마감 없음');
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
          candidateId: null,
          surfaceText: '11월 20일',
          value: '2026-11-20',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          confidence: 0.9,
          ambiguityReasons: ['MISSING_YEAR', 'MISSING_TIME'],
        },
        {
          candidateId: null,
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
      candidateId: null,
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
          candidateId: null,
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

  it('forces alternatives for an unbound precise v2 date', () => {
    const explicit = explicitDateProposal();
    const markup = renderProposal({
      ...explicit,
      itemCandidates: explicit.itemCandidates.map((item) => ({
        ...item,
        dueDateCandidateId: null,
      })),
    });

    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).toContain('날짜·내용 확인');
    expect(markup).toContain('안전하게 연결하지 못해 자동 적용하지');
    expect(markup).toContain('11월 20일');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('forces alternatives for an approximate v2 date', () => {
    const markup = renderProposal({
      ...taskProposal,
      schemaVersion: '2',
      dateCandidates: [
        {
          candidateId: 'date-weekend',
          surfaceText: '주말쯤',
          value: null,
          precision: 'APPROXIMATE',
          timeSpecified: false,
          confidence: 0.5,
          ambiguityReasons: ['IMPRECISE_DATE'],
        },
      ],
      itemCandidates: [{ ...taskProposal.itemCandidates[0], dueDateCandidateId: null }],
    });

    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).toContain('안전하게 연결하지 못해 자동 적용하지');
    expect(markup).toContain('주말쯤');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('forces detailed review when external enrichment was skipped for missing consent', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...externalCloudEvidence,
        cloudOutcome: 'CONSENT_REQUIRED',
      },
    });

    expect(markup).toContain('외부 보완 분석은 동의가 없어 실행하지 않았습니다.');
    expect(markup).toContain('검증된 로컬 제안만 표시됩니다.');
    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).not.toContain('예, 이대로 적용');
    expect(markup).not.toContain('AI 추천으로 돌아가기');
  });

  it('shows one generic fallback notice without exposing a provider failure category', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...noNetworkCloudEvidence,
        cloudOutcome: 'TIMEOUT',
        cloudFailureCategory: 'provider-secret-timeout-detail',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).toContain('원본 메모는 그대로 보존');
    expect(markup).not.toContain('provider-secret-timeout-detail');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('keeps the concise confirmation for a successful no-network enrichment', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...noNetworkCloudEvidence,
        cloudOutcome: 'SUCCESS',
      },
    });

    expect(markup).toContain('예, 이대로 적용');
    expect(markup).not.toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).not.toContain('검증된 로컬 제안만 표시됩니다.');
  });

  it('fails closed for partial external cloud evidence', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        cloudTransferMode: 'EXTERNAL_MEMO_CONTENT',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).not.toContain('외부 보완 분석은 동의가 없어 실행하지 않았습니다.');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('fails closed when a successful outcome is missing descriptor evidence', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        cloudTransferMode: 'NO_NETWORK',
        cloudGatewayVersion: 'fake-cloud-v2',
        cloudOutcome: 'SUCCESS',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('fails closed when a successful outcome is missing server safety evidence', () => {
    const incompleteEvidence: Record<string, unknown> = {
      ...noNetworkCloudEvidence,
      cloudOutcome: 'SUCCESS',
    };
    delete incompleteEvidence.receivedRoutingReasons;
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: incompleteEvidence,
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).toContain('어떤 부분이 다른가요?');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('keeps a complete coherent not-required evidence tuple concise', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        routingPolicyVersion: 'field-policy-v1',
        cloudTransferMode: 'NOT_REQUIRED',
        cloudGatewayVersion: 'none',
        cloudProviderId: 'none',
        cloudModelVersion: 'none',
        cloudConsentPolicyVersion: 'none',
        cloudOutcome: 'NOT_REQUIRED',
        cloudToolCalls: 0,
        cloudMutationCalls: 0,
        cloudResolvedFields: [],
        receivedRoutingPolicyVersion: 'field-policy-v1',
        receivedRoutingReasons: [],
      },
    });

    expect(markup).toContain('예, 이대로 적용');
    expect(markup).not.toContain('보완 분석을 완료하지 못했습니다.');
  });

  it('fails closed for an unknown cloud outcome', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...noNetworkCloudEvidence,
        cloudOutcome: 'FUTURE_SUCCESS',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('fails closed for an unknown cloud evidence field even with a successful outcome', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...noNetworkCloudEvidence,
        cloudOutcome: 'SUCCESS',
        cloudProviderDetail: 'must-not-be-trusted',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).not.toContain('must-not-be-trusted');
    expect(markup).not.toContain('예, 이대로 적용');
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
