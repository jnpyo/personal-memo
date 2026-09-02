import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { Proposal, RelationReviewCandidate } from '../../shared/api/types';
import {
  changeItemEventSchedule,
  changeItemKind,
  createReviewDraft,
  eventScheduleFromProposalCandidate,
} from './reviewModel';
import { ProposalReview, shouldFocusStepHeadingOnInitialOpen } from './ProposalReview';

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
      eventScheduleCandidates: [],
      suggestedEventScheduleCandidateId: null,
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

const localMachineCloudEvidence = {
  ...noNetworkCloudEvidence,
  cloudTransferMode: 'LOCAL_MACHINE_MEMO_CONTENT',
  cloudGatewayVersion: 'ollama-local-gateway-v1',
  cloudProviderId: 'ollama-local',
  cloudModelVersion: 'synthetic-local-model-v1',
  cloudConsentPolicyVersion: 'local-machine-memo-v1',
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

function eventProposalV3(): Proposal {
  return {
    ...taskProposal,
    schemaVersion: '3',
    suggestedTitle: { value: '디스코드 접속하기', confidence: 0.94, needsConfirmation: true },
    typeCandidates: [{ value: 'EVENT', score: 0.94 }],
    dateCandidates: [
      {
        candidateId: 'date-start',
        surfaceText: '오늘 오후 6시',
        value: '2026-08-24T18:00:00+09:00',
        precision: 'EXACT_TIME',
        timeSpecified: true,
        confidence: 0.93,
        ambiguityReasons: [],
      },
      {
        candidateId: 'date-end',
        surfaceText: '오늘 오후 7시',
        value: '2026-08-24T19:00:00+09:00',
        precision: 'EXACT_TIME',
        timeSpecified: true,
        confidence: 0.9,
        ambiguityReasons: [],
      },
    ],
    itemCandidates: [
      {
        ...taskProposal.itemCandidates[0],
        candidateId: 'item-event',
        dueDateCandidateId: null,
        eventScheduleCandidates: [
          {
            candidateId: 'schedule-1',
            mode: 'TIMED',
            startDateCandidateId: 'date-start',
            end: {
              dateCandidateId: 'date-end',
              boundary: 'EXCLUSIVE_AT_VALUE',
            },
            score: 0.91,
          },
        ],
        suggestedEventScheduleCandidateId: 'schedule-1',
        kind: 'EVENT',
        title: '디스코드 접속하기',
      },
    ],
  };
}

function bareTimeProposal(surfaceText = '6시'): Proposal {
  return {
    ...taskProposal,
    schemaVersion: '2',
    suggestedTitle: { value: '디스코드 접속하기', confidence: 0.93, needsConfirmation: true },
    typeCandidates: [{ value: 'TASK', score: 0.93 }],
    dateCandidates: [{
      candidateId: 'date-bare-time',
      surfaceText,
      value: null,
      precision: 'UNKNOWN',
      timeSpecified: false,
      confidence: 0.7,
      ambiguityReasons: ['IMPRECISE_DATE'],
    }],
    itemCandidates: [{
      ...taskProposal.itemCandidates[0],
      candidateId: 'item-discord',
      dueDateCandidateId: null,
      title: '디스코드 접속하기',
      action: '접속하기',
      object: '디스코드',
    }],
    ambiguityReasons: ['IMPRECISE_DATE'],
  };
}

function captureDayResolvedBareTimeProposal(): Proposal {
  const proposal = bareTimeProposal();
  return {
    ...proposal,
    dateCandidates: [{
      ...proposal.dateCandidates[0],
      value: '2026-09-02T18:00:00+09:00',
      precision: 'RELATIVE_EXACT',
      timeSpecified: true,
      ambiguityReasons: [],
    }],
    itemCandidates: [{
      ...proposal.itemCandidates[0],
      dueDateCandidateId: 'date-bare-time',
    }],
    ambiguityReasons: [],
    providerMetadata: {
      analyzerVersion: 'fake-v10',
      deterministicRulesVersion: 'korean-rules-v8',
    },
  };
}

function renderProposal(
  proposal: Proposal,
  options: {
    busy?: boolean;
    retryScope?: string;
    sourceTimeZone?: string | null;
    clientRecordedAt?: string | null;
    sourceTimeZoneError?: string | null;
    relationReviewCandidates?: RelationReviewCandidate[] | null;
    relationReviewLoading?: boolean;
    relationReviewError?: string | null;
  } = {},
): string {
  return renderToStaticMarkup(
    <ProposalReview
      review={createReviewDraft('proposal-1', proposal)}
      sourceTimeZone={options.sourceTimeZone === undefined ? 'Asia/Seoul' : options.sourceTimeZone}
      clientRecordedAt={
        options.clientRecordedAt === undefined
          ? '2026-09-02T04:00:00Z'
          : options.clientRecordedAt
      }
      sourceTimeZoneError={options.sourceTimeZoneError}
      relationReviewCandidates={
        options.relationReviewCandidates === undefined ? [] : options.relationReviewCandidates
      }
      relationReviewLoading={options.relationReviewLoading ?? false}
      relationReviewError={options.relationReviewError ?? null}
      busy={options.busy ?? false}
      onChange={vi.fn()}
      onApply={vi.fn()}
      onPostpone={vi.fn()}
      onReject={vi.fn()}
      onRetryRelationReview={vi.fn()}
      onRetrySourceTimeZone={vi.fn()}
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

  it('shows an explicitly reviewed EVENT schedule and immutable revision time zone', () => {
    let review = createReviewDraft('event-proposal', taskProposal);
    review = changeItemKind(review, 0, 'EVENT');
    review = changeItemEventSchedule(review, 0, {
      mode: 'TIMED',
      start: '2026-08-24T18:00:00+09:00',
      end: '',
    });
    const markup = renderToStaticMarkup(
      <ProposalReview
        review={review}
        sourceTimeZone="Asia/Seoul"
        relationReviewCandidates={[]}
        relationReviewLoading={false}
        relationReviewError={null}
        busy={false}
        onChange={vi.fn()}
        onApply={vi.fn()}
        onPostpone={vi.fn()}
        onReject={vi.fn()}
        onRetryRelationReview={vi.fn()}
        onTransientDirtyChange={vi.fn()}
        feedback={null}
        onDismissFeedback={vi.fn()}
      />,
    );

    expect(markup).toContain('일정 2026-08-24T18:00:00+09:00 · Asia/Seoul');
    expect(markup).toContain('예, 이대로 적용');
  });

  it('offers a retry when an EVENT source time zone cannot be restored', () => {
    let review = createReviewDraft('event-proposal', taskProposal);
    review = changeItemKind(review, 0, 'EVENT');
    review = changeItemEventSchedule(review, 0, {
      mode: 'TIMED',
      start: '2026-08-24T18:00:00+09:00',
      end: '',
    });
    const markup = renderToStaticMarkup(
      <ProposalReview
        review={review}
        sourceTimeZone={null}
        sourceTimeZoneError="temporary failure"
        relationReviewCandidates={[]}
        relationReviewLoading={false}
        relationReviewError={null}
        busy={false}
        onChange={vi.fn()}
        onApply={vi.fn()}
        onPostpone={vi.fn()}
        onReject={vi.fn()}
        onRetryRelationReview={vi.fn()}
        onRetrySourceTimeZone={vi.fn()}
        onTransientDirtyChange={vi.fn()}
        feedback={null}
        onDismissFeedback={vi.fn()}
      />,
    );

    expect(markup).toContain('시간대 다시 불러오기');
    expect(markup).not.toContain('temporary failure');
    expect(markup).toContain('class="approve-button" disabled=""');
  });

  it('keeps an exact TASK concise but disabled until its source time zone is restored', () => {
    const unavailableMarkup = renderProposal(explicitDateProposal(), {
      sourceTimeZone: null,
      sourceTimeZoneError: 'private transport detail',
    });
    const readyMarkup = renderProposal(explicitDateProposal(), {
      sourceTimeZone: 'Asia/Seoul',
    });

    expect(unavailableMarkup).toContain('AI는 이렇게 이해했어요.');
    expect(unavailableMarkup).toContain('시간대 다시 불러오기');
    expect(unavailableMarkup).not.toContain('private transport detail');
    expect(unavailableMarkup).toContain('class="approve-button" disabled=""');
    expect(readyMarkup).toContain('class="approve-button">예, 이대로 적용');
  });

  it('shows v3 EVENT alternatives as untrusted and keeps every schedule unselected', () => {
    const onApply = vi.fn();
    const eventProposal = eventProposalV3();
    const review = createReviewDraft('event-proposal-v3', eventProposal);
    const markup = renderToStaticMarkup(
      <ProposalReview
        review={review}
        sourceTimeZone="Asia/Seoul"
        relationReviewCandidates={[]}
        relationReviewLoading={false}
        relationReviewError={null}
        busy={false}
        onChange={vi.fn()}
        onApply={onApply}
        onPostpone={vi.fn()}
        onReject={vi.fn()}
        onRetryRelationReview={vi.fn()}
        onTransientDirtyChange={vi.fn()}
        feedback={null}
        onDismissFeedback={vi.fn()}
      />,
    );

    expect(review.items[0].eventSchedule).toBeNull();
    expect(markup).toContain('AI 일정 후보 · 아직 미적용');
    expect(markup).toContain('AI 추천 후보 · 신뢰하지 않은 제안');
    expect(markup).toContain('이 후보 사용');
    expect(markup).toContain('시간 미정으로 저장');
    expect(markup).toContain('수정한 내용 승인·적용');
    expect(markup).not.toContain('예, 이대로 적용');
    expect(markup).not.toContain('안전하게 연결하지 못해 자동 적용하지');
    expect(onApply).not.toHaveBeenCalled();
  });

  it('renders a v3 schedule only after the explicit candidate action has changed the draft', () => {
    const eventProposal = eventProposalV3();
    const candidate = eventProposal.itemCandidates[0].eventScheduleCandidates[0];
    let review = createReviewDraft('event-proposal-v3', eventProposal);
    review = changeItemEventSchedule(
      review,
      0,
      eventScheduleFromProposalCandidate(eventProposal, candidate),
      candidate.candidateId,
    );
    const markup = renderToStaticMarkup(
      <ProposalReview
        review={review}
        sourceTimeZone="Asia/Seoul"
        relationReviewCandidates={[]}
        relationReviewLoading={false}
        relationReviewError={null}
        busy={false}
        onChange={vi.fn()}
        onApply={vi.fn()}
        onPostpone={vi.fn()}
        onReject={vi.fn()}
        onRetryRelationReview={vi.fn()}
        onTransientDirtyChange={vi.fn()}
        feedback={null}
        onDismissFeedback={vi.fn()}
      />,
    );

    expect(markup).toContain('이 후보 사용 중');
    expect(markup).toContain('AI 일정 후보에서 선택됨');
    expect(markup).toContain('value="2026-08-24T18:00:00+09:00"');
    expect(markup).toContain('value="2026-08-24T19:00:00+09:00"');
    expect(markup).toContain('메모 revision의 시간대 Asia/Seoul로 저장합니다.');
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

  it('opens a stored or capture-day-exhausted bare time in compact explicit clarification', () => {
    const proposal = bareTimeProposal();
    const review = createReviewDraft('proposal-bare-time', proposal);
    const markup = renderProposal(proposal, { sourceTimeZone: 'Asia/Seoul' });

    expect(shouldFocusStepHeadingOnInitialOpen(review)).toBe(true);
    expect(review.items[0].due).toBeNull();
    expect(markup).toContain('‘6시’를 정확히 지정');
    expect(markup).toContain('날짜와 오전·오후를 선택하세요.');
    expect(markup).toContain('aria-label="6시 날짜"');
    expect(markup).toContain('>작성일</button>');
    expect(markup).not.toContain('>오늘</button>');
    expect(markup).toContain('value=""');
    expect(markup).toContain('aria-pressed="false"');
    expect(markup).toContain('오전 6:00');
    expect(markup).toContain('오후 6:00');
    expect(markup).toContain('시간 없이 두기');
    expect(markup).toContain('class="approve-button" disabled=""');
    expect(markup).not.toContain('안전하게 연결하지 못해 자동 적용하지');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('shows a capture-day resolved bare time as a normal proposal without auto-applying it', () => {
    const proposal = captureDayResolvedBareTimeProposal();
    const review = createReviewDraft('proposal-resolved-bare-time', proposal);
    const markup = renderProposal(proposal, { sourceTimeZone: 'Asia/Seoul' });

    expect(review.items[0].due).toMatchObject({
      surfaceText: '6시',
      value: '2026-09-02T18:00:00+09:00',
      precision: 'RELATIVE_EXACT',
    });
    expect(markup).toContain('마감 6시 → 2026-09-02T18:00:00+09:00');
    expect(markup).toContain('예, 이대로 적용');
    expect(markup).not.toContain('‘6시’를 정확히 지정');
  });

  it.each([
    ['오전 6시', '원문 시각 오전 6:00'],
    ['오후 6시', '원문 시각 오후 6:00'],
    ['07시', '원문 시각 07:00'],
    ['18시', '원문 시각 18:00'],
    ['18:30', '원문 시각 18:30'],
  ])('keeps %s fixed and asks only for an explicit date', (surfaceText, fixedLabel) => {
    const markup = renderProposal(bareTimeProposal(surfaceText), {
      sourceTimeZone: 'Asia/Seoul',
      clientRecordedAt: '2026-09-02T04:00:00Z',
    });

    expect(markup).toContain('날짜를 선택하세요.');
    expect(markup).toContain(fixedLabel);
    expect(markup).not.toContain('aria-label="오전 또는 오후"');
    expect(markup).toContain('class="approve-button" disabled=""');
  });

  it('offers the compact unresolved time as an explicit EVENT start without preselecting it', () => {
    const proposal = bareTimeProposal();
    proposal.typeCandidates = [{ value: 'EVENT', score: 0.93 }];
    proposal.itemCandidates = [{ ...proposal.itemCandidates[0], kind: 'EVENT' }];
    const markup = renderProposal(proposal, { sourceTimeZone: 'Asia/Seoul' });

    expect(markup).toContain('‘6시’를 정확히 지정');
    expect(markup).toContain('일정 시작으로 사용');
    expect(markup).toContain('aria-label="항목 1 일정 시작"');
    expect(markup).toContain('<option value="none" selected="">시간 미정으로 저장</option>');
    expect(markup).toContain('class="approve-button" disabled=""');
  });

  it('keeps bare-time confirmation disabled and offers time-zone retry when provenance is missing', () => {
    const markup = renderProposal(bareTimeProposal(), {
      sourceTimeZone: null,
      clientRecordedAt: null,
      sourceTimeZoneError: 'private transport detail',
    });

    expect(markup).toContain('시간대 다시 불러오기');
    expect(markup).toContain('메모를 작성한 시각과 시간대를 확인한 뒤 시각을 지정할 수 있습니다.');
    expect(markup).not.toContain('private transport detail');
    expect(markup).toContain('마감 시각으로 사용</button>');
    expect(markup).toContain('class="approve-button" disabled=""');
  });

  it('does not promote a general imprecise date into the bare-time control', () => {
    const markup = renderProposal(bareTimeProposal('주말쯤'), {
      sourceTimeZone: 'Asia/Seoul',
    });

    expect(markup).not.toContain('정확히 지정');
    expect(markup).toContain('AI가 다음 날짜를 특정 할 일의 마감이나 일정 후보로 안전하게 연결하지 못해');
    expect(markup).not.toContain('예, 이대로 적용');
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

  it('labels a successful localhost model assist without changing explicit approval', () => {
    const markup = renderProposal({
      ...taskProposal,
      providerMetadata: {
        ...localMachineCloudEvidence,
        cloudOutcome: 'SUCCESS',
      },
    });

    expect(markup).toContain('로컬 LLM 보조 제안');
    expect(markup).toContain('예, 이대로 적용');
    expect(markup).toContain('아직 제안일 뿐입니다.');
    expect(markup).not.toContain('보완 분석을 완료하지 못했습니다.');
  });

  it('keeps a failed localhost UNKNOWN result in detailed review without the success badge', () => {
    const markup = renderProposal({
      ...taskProposal,
      typeCandidates: [{ value: 'UNKNOWN', score: 0.4 }],
      itemCandidates: [],
      providerMetadata: {
        ...localMachineCloudEvidence,
        cloudOutcome: 'TIMEOUT',
      },
    });

    expect(markup).toContain('보완 분석을 완료하지 못했습니다.');
    expect(markup).toContain('AI가 유형을 확정하지 못했어요.');
    expect(markup).not.toContain('로컬 LLM 보조 제안');
    expect(markup).not.toContain('예, 이대로 적용');
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

  it('shows informed owner-visible relation labels unchecked and separate from tags', () => {
    const targetId = '71b13444-0122-42fd-a998-4df258e767af';
    const relatedProposal: Proposal = {
      ...taskProposal,
      relationCandidates: [{
        sourceCandidateId: 'item-1',
        targetType: 'TAG',
        targetId,
        relationType: 'DEPENDS_ON',
        score: 0.82,
      }],
    };
    const markup = renderProposal(relatedProposal, {
      relationReviewCandidates: [{
        proposalIndex: 0,
        targetType: 'TAG',
        targetId,
        targetLabel: '졸업 프로젝트',
        available: true,
      }],
    });

    expect(markup).toContain('연결 후보');
    expect(markup).toContain('운영체제 과제 제출');
    expect(markup).toContain('의존함 ·');
    expect(markup).toContain('태그 졸업 프로젝트');
    expect(markup).toContain('체크해도 메모 태그로 추가되지 않습니다.');
    expect(markup).toContain('type="checkbox"');
    expect(markup).not.toContain('type="checkbox" checked=""');
    expect(markup).not.toContain('예, 이대로 적용');
  });

  it('focuses the current step heading only when the first open goes directly to relation edit', () => {
    const relatedProposal: Proposal = {
      ...taskProposal,
      relationCandidates: [{
        sourceCandidateId: 'item-1',
        targetType: 'TAG',
        targetId: '71b13444-0122-42fd-a998-4df258e767af',
        relationType: 'DEPENDS_ON',
        score: 0.82,
      }],
    };

    expect(shouldFocusStepHeadingOnInitialOpen(createReviewDraft('concise', taskProposal)))
      .toBe(false);
    expect(shouldFocusStepHeadingOnInitialOpen(createReviewDraft('related', relatedProposal)))
      .toBe(true);
  });

  it('disables an unavailable relation and exposes an accessible label reload error', () => {
    const targetId = '0776a1a1-9567-4642-bc71-5e34801472f7';
    const relatedProposal: Proposal = {
      ...taskProposal,
      relationCandidates: [{
        sourceCandidateId: 'item-1',
        targetType: 'MEMO',
        targetId,
        relationType: 'REFERENCES',
        score: 0.7,
      }],
    };
    const unavailableMarkup = renderProposal(relatedProposal, {
      relationReviewCandidates: [{
        proposalIndex: 0,
        targetType: 'MEMO',
        targetId,
        targetLabel: null,
        available: false,
      }],
    });
    const errorMarkup = renderProposal(relatedProposal, {
      relationReviewCandidates: null,
      relationReviewError: '연결 정보를 확인하지 못했습니다.',
    });

    expect(unavailableMarkup).toMatch(/type="checkbox"[^>]*disabled=""/);
    expect(unavailableMarkup).toContain('이 연결 대상은 현재 사용할 수 없습니다.');
    expect(errorMarkup).toContain('role="alert"');
    expect(errorMarkup).toContain('연결 대상 다시 불러오기');
    expect(errorMarkup).toMatch(/수정한 내용 승인·적용<\/button>/);
    expect(errorMarkup).toMatch(/approve-button" disabled=""/);
  });
});
