import { describe, expect, it } from 'vitest';
import type { Proposal } from '../../shared/api/types';
import {
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
  isValidReviewDraft,
} from './reviewModel';

const proposal: Proposal = {
  memoId: 'memo-1',
  memoRevision: 2,
  suggestedTitle: { value: '운영체제 과제' },
  typeCandidates: [{ value: 'TASK' }],
  dateCandidates: [
    {
      surfaceText: '오늘 오후 6시',
      value: '2026-08-05T18:00:00+09:00',
      precision: 'EXACT_TIME',
      timeSpecified: true,
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
    { existingTagId: null, canonicalName: ' 운영체제 ', matchedAlias: null },
  ],
  itemCandidates: [
    { candidateId: 'item-1', kind: 'TASK', title: ' 과제 제출 ' },
    { candidateId: 'item-2', kind: 'TASK', title: ' 발표 준비 ' },
  ],
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
});
