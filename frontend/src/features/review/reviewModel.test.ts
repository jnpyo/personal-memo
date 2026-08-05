import { describe, expect, it } from 'vitest';
import type { Proposal } from '../../shared/api/types';
import {
  addManualItem,
  buildApplyRequest,
  changeItemDue,
  changeItemDueValue,
  changeItemTitle,
  changeSelectedType,
  changeReviewTitle,
  createCustomDateOnly,
  createReviewDraft,
  isValidDue,
  isValidIsoDate,
  isValidOffsetDateTime,
  isValidReviewDraft,
  removeReviewItem,
} from './reviewModel';

function proposalItem(
  candidateId: string,
  kind: Proposal['itemCandidates'][number]['kind'],
  title: string,
): Proposal['itemCandidates'][number] {
  return {
    candidateId,
    kind,
    title,
    sourceSpan: null,
    action: null,
    object: null,
    confidence: 0.9,
  };
}

const proposal: Proposal = {
  schemaVersion: '1',
  memoId: 'memo-1',
  memoRevision: 2,
  suggestedTitle: { value: '운영체제 과제', confidence: 0.9, needsConfirmation: true },
  typeCandidates: [{ value: 'TASK', score: 0.9 }],
  dateCandidates: [
    {
      surfaceText: '오늘 오후 6시',
      value: '2026-08-05T18:00:00+09:00',
      precision: 'EXACT_TIME',
      timeSpecified: true,
      confidence: 0.9,
      ambiguityReasons: [],
    },
    {
      surfaceText: '11.25',
      value: '2026-11-25',
      precision: 'DATE_ONLY',
      timeSpecified: false,
      confidence: 0.91,
      ambiguityReasons: ['YEAR_INFERRED'],
    },
  ],
  tagCandidates: [
    {
      existingTagId: null,
      canonicalName: ' 운영체제 ',
      matchedAlias: null,
      score: 0.9,
      isNewProposal: true,
    },
  ],
  itemCandidates: [
    proposalItem('item-1', 'TASK', ' 과제 제출 '),
    proposalItem('item-2', 'TASK', ' 발표 준비 '),
  ],
  relationCandidates: [],
  ambiguityReasons: [],
  providerMetadata: {},
};

describe('review model', () => {
  it('clones editable data and assigns the preferred date to only one task', () => {
    const draft = createReviewDraft('proposal-1', proposal);

    expect(draft.items[0].due).toMatchObject({
      value: '2026-11-25',
      precision: 'DATE_ONLY',
    });
    expect(draft.items[1].due).toBeNull();

    draft.tags[0].canonicalName = '수정';
    draft.items[0].title = '수정';
    expect(proposal.tagCandidates[0].canonicalName).toBe(' 운영체제 ');
    expect(proposal.itemCandidates[0].title).toBe(' 과제 제출 ');
  });

  it('keeps the representative title and first canonical item in sync', () => {
    let changed = changeReviewTitle(createReviewDraft('proposal-1', proposal), '수정된 제목');
    changed = changeItemTitle(changed, 0, '최종 제목');
    const request = buildApplyRequest(changed, 'Asia/Seoul');

    expect(request.title).toBe('최종 제목');
    expect(request.items[0].title).toBe('최종 제목');
    expect(request.items[1].title).toBe('발표 준비');
  });

  it('keeps at least one item kind aligned when the representative type changes', () => {
    const changed = changeSelectedType(createReviewDraft('proposal-1', proposal), 'IDEA');

    expect(changed.selectedType).toBe('IDEA');
    expect(changed.items.some((item) => item.kind === 'IDEA')).toBe(true);
    expect(changed.items[0].due).toBeNull();
    expect(isValidReviewDraft(changed)).toBe(true);
  });

  it('builds the exact per-item dates edited by the user', () => {
    let draft = createReviewDraft('proposal-1', proposal);
    draft = changeItemDue(draft, 0, null);
    draft = changeItemDue(draft, 1, createCustomDateOnly());
    draft = changeItemDueValue(draft, 1, '2026-12-03');
    draft = changeReviewTitle(draft, ' 대표 제목 ');
    draft = changeItemTitle(draft, 1, ' 둘째 할 일 ');

    const request = buildApplyRequest(draft, 'Asia/Seoul');

    expect(request.title).toBe('대표 제목');
    expect(request.selectedTags[0]).toEqual({ existingTagId: null, newCanonicalName: '운영체제' });
    expect(request.items).toEqual([
      { kind: 'TASK', title: '대표 제목', due: null },
      {
        kind: 'TASK',
        title: '둘째 할 일',
        due: {
          surfaceText: '사용자 지정 날짜',
          value: '2026-12-03',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          timeZone: 'Asia/Seoul',
        },
      },
    ]);
  });

  it('rejects impossible DATE_ONLY values and exact timestamps without an offset', () => {
    expect(isValidIsoDate('2028-02-29')).toBe(true);
    expect(isValidIsoDate('2026-02-29')).toBe(false);
    expect(
      isValidDue({
        surfaceText: '저녁',
        value: '2026-08-05T18:00:00',
        precision: 'EXACT_TIME',
        timeSpecified: true,
      }),
    ).toBe(false);
  });

  it('strictly validates calendar, clock, fraction, and offset boundaries', () => {
    expect(isValidOffsetDateTime('2028-02-29T23:59:59.123456789+09:00')).toBe(true);
    expect(isValidOffsetDateTime('2026-08-05T18:00:00Z')).toBe(true);

    expect(isValidOffsetDateTime('2026-02-30T18:00:00+09:00')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T24:00:00+09:00')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T18:00:60+09:00')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T18:00:00+18:01')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T18:00:00.1234567890+09:00')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T18:00+09:00')).toBe(false);
    expect(isValidOffsetDateTime('2026-08-05T18:00:00')).toBe(false);
  });

  it('keeps an UNKNOWN proposal blocked until the user selects a type and adds an item', () => {
    const unknownProposal: Proposal = {
      ...proposal,
      suggestedTitle: { value: '운영체제 과제', confidence: 0.4, needsConfirmation: true },
      typeCandidates: [{ value: 'UNKNOWN', score: 0.4 }],
      itemCandidates: [],
    };
    let draft = createReviewDraft('proposal-unknown', unknownProposal);

    expect(draft.selectedType).toBeNull();
    expect(isValidReviewDraft(draft)).toBe(false);
    expect(() => buildApplyRequest(draft, 'Asia/Seoul')).toThrow();

    draft = changeSelectedType(draft, 'TASK');
    expect(isValidReviewDraft(draft)).toBe(false);
    draft = addManualItem(draft);

    expect(draft.items).toEqual([
      expect.objectContaining({
        candidateId: 'manual-1',
        kind: 'TASK',
        title: '운영체제 과제',
        due: null,
      }),
    ]);
    expect(isValidReviewDraft(draft)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul')).toMatchObject({
      selectedType: 'TASK',
      title: '운영체제 과제',
      items: [{ kind: 'TASK', title: '운영체제 과제', due: null }],
    });
  });

  it('supports partial apply by removing existing proposal items', () => {
    const draft = removeReviewItem(createReviewDraft('proposal-1', proposal), 1);

    expect(isValidReviewDraft(draft)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul').items).toEqual([
      {
        kind: 'TASK',
        title: '운영체제 과제',
        due: {
          surfaceText: '11.25',
          value: '2026-11-25',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          timeZone: 'Asia/Seoul',
        },
      },
    ]);
  });

  it('realigns the representative title and type when the first mixed item is removed', () => {
    const mixedProposal: Proposal = {
      ...proposal,
      suggestedTitle: {
        value: '가상메모리는 시험에 중요',
        confidence: 0.7,
        needsConfirmation: true,
      },
      typeCandidates: [
        { value: 'INFORMATION', score: 0.7 },
        { value: 'TASK', score: 0.3 },
      ],
      itemCandidates: [
        proposalItem('info-1', 'INFORMATION', '가상메모리는 시험에 중요'),
        proposalItem('task-1', 'TASK', '과제 제출'),
      ],
    };

    const draft = removeReviewItem(createReviewDraft('proposal-mixed', mixedProposal), 0);

    expect(draft.title).toBe('과제 제출');
    expect(draft.selectedType).toBe('TASK');
    expect(isValidReviewDraft(draft)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul')).toMatchObject({
      selectedType: 'TASK',
      title: '과제 제출',
      items: [{ kind: 'TASK', title: '과제 제출' }],
    });
  });

  it('blocks apply after the only item is removed', () => {
    const singleItemProposal: Proposal = {
      ...proposal,
      itemCandidates: [proposal.itemCandidates[0]],
    };

    const draft = removeReviewItem(createReviewDraft('proposal-single', singleItemProposal), 0);

    expect(draft.items).toEqual([]);
    expect(isValidReviewDraft(draft)).toBe(false);
    expect(() => buildApplyRequest(draft, 'Asia/Seoul')).toThrow();
  });

  it('falls back from a removed TASK representative to the remaining INFORMATION item', () => {
    const taskRepresentative: Proposal = {
      ...proposal,
      suggestedTitle: { value: '시험 메모와 과제', confidence: 0.7, needsConfirmation: true },
      typeCandidates: [
        { value: 'TASK', score: 0.6 },
        { value: 'INFORMATION', score: 0.4 },
      ],
      itemCandidates: [
        proposalItem('info-1', 'INFORMATION', '시험 메모'),
        proposalItem('task-1', 'TASK', '과제 제출'),
      ],
    };
    const original = createReviewDraft('proposal-task-representative', taskRepresentative);

    expect(original.selectedType).toBe('TASK');
    const draft = removeReviewItem(original, 1);

    expect(draft.selectedType).toBe('INFORMATION');
    expect(draft.title).toBe('시험 메모와 과제');
    expect(isValidReviewDraft(draft)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul').items).toEqual([
      { kind: 'INFORMATION', title: '시험 메모와 과제', due: null },
    ]);
  });

  it('caps manual items at three and allows manual items to be removed', () => {
    const unknownProposal: Proposal = {
      ...proposal,
      typeCandidates: [{ value: 'UNKNOWN', score: 0.4 }],
      itemCandidates: [],
    };
    let draft = changeSelectedType(createReviewDraft('proposal-unknown', unknownProposal), 'IDEA');
    draft = addManualItem(draft);
    draft = addManualItem(draft);
    draft = addManualItem(draft);
    draft = addManualItem(draft);

    expect(draft.items).toHaveLength(3);
    expect(draft.items.map((item) => item.candidateId)).toEqual([
      'manual-1',
      'manual-2',
      'manual-3',
    ]);

    draft = removeReviewItem(draft, 1);
    expect(draft.items.map((item) => item.candidateId)).toEqual(['manual-1', 'manual-3']);
  });

  it('blocks unsupported future semantic and item types from a recovered payload', () => {
    const futurePayload = {
      ...proposal,
      typeCandidates: [{ value: 'FUTURE_TYPE' }],
      itemCandidates: [{ ...proposal.itemCandidates[0], kind: 'FUTURE_TYPE' }],
    } as unknown as Proposal;

    const draft = createReviewDraft('proposal-future', futurePayload);

    expect(draft.selectedType).toBeNull();
    expect(draft.items).toEqual([]);
    expect(isValidReviewDraft(draft)).toBe(false);
  });
});
