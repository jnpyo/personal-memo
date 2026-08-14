import { describe, expect, it } from 'vitest';
import type { Proposal, RelationReviewCandidate } from '../../shared/api/types';
import {
  addManualItem,
  buildApplyRequest,
  changeItemDue,
  changeItemDueValue,
  changeItemTitle,
  changeRelationSelection,
  changeSelectedType,
  changeReviewTitle,
  createCustomDateOnly,
  createReviewDraft,
  isValidDue,
  isValidIsoDate,
  isValidOffsetDateTime,
  isValidReviewDraft,
  isRelationSelectionReady,
  preferredItemKind,
  removeReviewItem,
  requiresExplicitDateMapping,
  usableDateCandidates,
} from './reviewModel';

function proposalItem(
  candidateId: string,
  kind: Proposal['itemCandidates'][number]['kind'],
  title: string,
): Proposal['itemCandidates'][number] {
  return {
    candidateId,
    dueDateCandidateId: null,
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
      candidateId: null,
      surfaceText: '오늘 오후 6시',
      value: '2026-08-05T18:00:00+09:00',
      precision: 'EXACT_TIME',
      timeSpecified: true,
      confidence: 0.9,
      ambiguityReasons: [],
    },
    {
      candidateId: null,
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

function explicitBindingProposal(): Proposal {
  return {
    ...proposal,
    schemaVersion: '2',
    suggestedTitle: { value: '보고서 제출', confidence: 0.94, needsConfirmation: true },
    dateCandidates: [
      {
        candidateId: 'date-report',
        surfaceText: '11월 20일',
        value: '2026-11-20',
        precision: 'DATE_ONLY',
        timeSpecified: false,
        confidence: 0.93,
        ambiguityReasons: [],
      },
      {
        candidateId: 'date-presentation',
        surfaceText: '11월 25일 오후 6시',
        value: '2026-11-25T18:00:00+09:00',
        precision: 'EXACT_TIME',
        timeSpecified: true,
        confidence: 0.92,
        ambiguityReasons: [],
      },
    ],
    itemCandidates: [
      {
        ...proposalItem('item-report', 'TASK', '보고서 제출'),
        dueDateCandidateId: 'date-report',
      },
      {
        ...proposalItem('item-presentation', 'TASK', '발표 준비'),
        dueDateCandidateId: 'date-presentation',
      },
    ],
  };
}

describe('review model', () => {
  it('clones editable data without guessing between multiple task and date candidates', () => {
    const draft = createReviewDraft('proposal-1', proposal);

    expect(draft.items[0].due).toBeNull();
    expect(draft.items[1].due).toBeNull();

    draft.tags[0].canonicalName = '수정';
    draft.items[0].title = '수정';
    expect(proposal.tagCandidates[0].canonicalName).toBe(' 운영체제 ');
    expect(proposal.itemCandidates[0].title).toBe(' 과제 제출 ');
  });

  it('automatically assigns a due only when one task and one usable date are unambiguous', () => {
    const singleTaskAndDate: Proposal = {
      ...proposal,
      dateCandidates: [proposal.dateCandidates[1]],
      itemCandidates: [proposal.itemCandidates[0]],
    };

    const draft = createReviewDraft('proposal-unambiguous-due', singleTaskAndDate);

    expect(draft.items[0].due).toMatchObject({
      value: '2026-11-25',
      precision: 'DATE_ONLY',
    });
  });

  it('projects explicit v2 dates across multiple task candidates without guessing by order', () => {
    const draft = createReviewDraft('proposal-explicit-dates', explicitBindingProposal());

    expect(draft.items.map((item) => [item.candidateId, item.due?.candidateId])).toEqual([
      ['item-report', 'date-report'],
      ['item-presentation', 'date-presentation'],
    ]);
    expect(draft.items.map((item) => item.due?.value)).toEqual([
      '2026-11-20',
      '2026-11-25T18:00:00+09:00',
    ]);
    expect(requiresExplicitDateMapping(draft)).toBe(false);
  });

  it('allows an explicit v2 date to be shared by multiple tasks', () => {
    const explicit = explicitBindingProposal();
    const shared: Proposal = {
      ...explicit,
      dateCandidates: [explicit.dateCandidates[0]],
      itemCandidates: explicit.itemCandidates.map((item) => ({
        ...item,
        dueDateCandidateId: 'date-report',
      })),
    };

    const draft = createReviewDraft('proposal-shared-date', shared);

    expect(draft.items.map((item) => item.due?.candidateId)).toEqual([
      'date-report',
      'date-report',
    ]);
    expect(requiresExplicitDateMapping(draft)).toBe(false);
  });

  it('forces detailed review for an unbound precise v2 date', () => {
    const explicit = explicitBindingProposal();
    const unbound: Proposal = {
      ...explicit,
      itemCandidates: explicit.itemCandidates.map((item) => ({
        ...item,
        dueDateCandidateId: null,
      })),
    };

    const draft = createReviewDraft('proposal-unbound-date', unbound);

    expect(draft.items.every((item) => item.due === null)).toBe(true);
    expect(requiresExplicitDateMapping(draft)).toBe(true);
  });

  it('forces detailed review for approximate v2 dates and never turns them into a due', () => {
    const approximate: Proposal = {
      ...explicitBindingProposal(),
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
      itemCandidates: [
        {
          ...proposalItem('item-appointment', 'TASK', '병원 예약 잡기'),
          dueDateCandidateId: null,
        },
      ],
    };

    const draft = createReviewDraft('proposal-approximate-v2', approximate);

    expect(draft.items[0].due).toBeNull();
    expect(usableDateCandidates(approximate)).toEqual([]);
    expect(requiresExplicitDateMapping(draft)).toBe(true);
  });

  it('keeps an explicit v2 no-date proposal eligible for concise confirmation', () => {
    const noDate: Proposal = {
      ...explicitBindingProposal(),
      dateCandidates: [],
      itemCandidates: [
        {
          ...proposalItem('item-no-date', 'TASK', '자료 정리'),
          dueDateCandidateId: null,
        },
      ],
    };

    const draft = createReviewDraft('proposal-no-date', noDate);

    expect(draft.items[0].due).toBeNull();
    expect(requiresExplicitDateMapping(draft)).toBe(false);
  });

  it('projects the preferred type before deciding whether the sole task gets a due', () => {
    const conflictProposal: Proposal = {
      ...proposal,
      typeCandidates: [{ value: 'TASK', score: 0.9 }],
      dateCandidates: [proposal.dateCandidates[1]],
      itemCandidates: [proposalItem('item-conflict', 'INFORMATION', '검토할 자료')],
    };

    const draft = createReviewDraft('proposal-conflict', conflictProposal);

    expect(draft.items).toHaveLength(1);
    expect(draft.items[0].kind).toBe('TASK');
    expect(draft.items[0].due).toMatchObject({ value: '2026-11-25', precision: 'DATE_ONLY' });
  });

  it('does not bind one date when another item is present or the date is imprecise', () => {
    const mixedProposal: Proposal = {
      ...proposal,
      dateCandidates: [proposal.dateCandidates[1]],
      itemCandidates: [
        proposalItem('item-task', 'TASK', '발표 준비'),
        proposalItem('item-event', 'EVENT', '회의'),
      ],
    };
    const approximateProposal: Proposal = {
      ...proposal,
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
      itemCandidates: [proposalItem('item-task', 'TASK', '병원 예약 잡기')],
    };

    expect(createReviewDraft('proposal-mixed', mixedProposal).items[0].due).toBeNull();
    expect(createReviewDraft('proposal-approximate', approximateProposal).items[0].due).toBeNull();
    expect(usableDateCandidates(approximateProposal)).toEqual([]);
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
      { proposalCandidateId: 'item-1', kind: 'TASK', title: '대표 제목', due: null },
      {
        proposalCandidateId: 'item-2',
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

  it('preserves only the exact proposal item source identity in the apply body', () => {
    const draft = createReviewDraft('proposal-explicit-apply', explicitBindingProposal());

    const request = buildApplyRequest(draft, 'Asia/Seoul');
    const serialized = JSON.stringify(request);

    expect(request.items).toEqual([
      {
        proposalCandidateId: 'item-report',
        kind: 'TASK',
        title: '보고서 제출',
        due: {
          surfaceText: '11월 20일',
          value: '2026-11-20',
          precision: 'DATE_ONLY',
          timeZone: 'Asia/Seoul',
          timeSpecified: false,
        },
      },
      {
        proposalCandidateId: 'item-presentation',
        kind: 'TASK',
        title: '발표 준비',
        due: {
          surfaceText: '11월 25일 오후 6시',
          value: '2026-11-25T18:00:00+09:00',
          precision: 'EXACT_TIME',
          timeZone: 'Asia/Seoul',
          timeSpecified: true,
        },
      },
    ]);
    expect(serialized).toContain('proposalCandidateId');
    expect(serialized).toContain('item-report');
    expect(serialized).toContain('item-presentation');
    expect(serialized).not.toContain('date-report');
    expect(serialized).not.toContain('date-presentation');
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
      items: [{ proposalCandidateId: null, kind: 'TASK', title: '운영체제 과제', due: null }],
      selectedRelations: [],
    });
  });

  it('uses the highest-scored type even when candidates are not sorted', () => {
    const unsortedProposal: Proposal = {
      ...proposal,
      typeCandidates: [
        { value: 'UNKNOWN', score: 0.2 },
        { value: 'TASK', score: 0.9 },
      ],
    };

    expect(preferredItemKind(unsortedProposal)).toBe('TASK');
    expect(createReviewDraft('proposal-unsorted', unsortedProposal).selectedType).toBe('TASK');

    const unknownFirstByScore: Proposal = {
      ...proposal,
      typeCandidates: [
        { value: 'TASK', score: 0.2 },
        { value: 'UNKNOWN', score: 0.9 },
      ],
    };
    expect(preferredItemKind(unknownFirstByScore)).toBeNull();
    expect(createReviewDraft('proposal-unknown-top', unknownFirstByScore).selectedType).toBeNull();
  });

  it('requires an explicit choice when different type candidates tie for the top score', () => {
    const tiedProposal: Proposal = {
      ...proposal,
      typeCandidates: [
        { value: 'TASK', score: 0.9 },
        { value: 'EVENT', score: 0.9 },
      ],
    };

    expect(preferredItemKind(tiedProposal)).toBeNull();
    expect(createReviewDraft('proposal-tied-types', tiedProposal).selectedType).toBeNull();
  });

  it('supports partial apply by removing existing proposal items', () => {
    const draft = removeReviewItem(createReviewDraft('proposal-1', proposal), 1);

    expect(isValidReviewDraft(draft)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul').items).toEqual([
      {
        proposalCandidateId: 'item-1',
        kind: 'TASK',
        title: '운영체제 과제',
        due: null,
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
      {
        proposalCandidateId: 'info-1',
        kind: 'INFORMATION',
        title: '시험 메모와 과제',
        due: null,
      },
    ]);
  });

  it('defaults every relation to unchecked and maps only explicit selections', () => {
    const exactSourceId = '  source-😀  ';
    const related: Proposal = {
      ...proposal,
      itemCandidates: [
        proposalItem(exactSourceId, 'TASK', '연결할 과제'),
        proposalItem('keep-item', 'INFORMATION', '남길 메모'),
      ],
      relationCandidates: [
        {
          sourceCandidateId: exactSourceId,
          targetType: 'TAG',
          targetId: '2472db10-4f83-4ec5-aebc-42ef81894eaf',
          relationType: 'RELATED_TO',
          score: 0.81,
        },
      ],
    };
    const candidates: RelationReviewCandidate[] = [
      {
        proposalIndex: 0,
        targetType: 'TAG',
        targetId: '2472db10-4f83-4ec5-aebc-42ef81894eaf',
        targetLabel: '운영체제',
        available: true,
      },
    ];

    let draft = createReviewDraft('proposal-relations', related);
    expect(draft.selectedRelationIndexes).toEqual([]);
    expect(draft.items[0]?.proposalCandidateId).toBe(exactSourceId);
    expect(isRelationSelectionReady(draft, candidates)).toBe(true);
    expect(buildApplyRequest(draft, 'Asia/Seoul').selectedRelations).toEqual([]);

    draft = changeRelationSelection(draft, 0, true);
    const request = buildApplyRequest(draft, 'Asia/Seoul');
    expect(request.items[0]?.proposalCandidateId).toBe(exactSourceId);
    expect(request.selectedRelations).toEqual([{ proposalIndex: 0 }]);
    expect(request.selectedTags).toEqual([
      { existingTagId: null, newCanonicalName: '운영체제' },
    ]);

    draft = removeReviewItem(draft, 0);
    expect(draft.selectedRelationIndexes).toEqual([]);
  });

  it('keeps a 100-supplementary-code-point proposal identity exact and blocks unavailable selections', () => {
    const boundaryIdentity = '😀'.repeat(100);
    const related: Proposal = {
      ...proposal,
      itemCandidates: [proposalItem(boundaryIdentity, 'TASK', '경계 항목')],
      relationCandidates: [
        {
          sourceCandidateId: boundaryIdentity,
          targetType: 'MEMO',
          targetId: 'd4b26246-0bbb-403a-98e0-424920514df7',
          relationType: 'REFERENCES',
          score: 0.7,
        },
      ],
    };
    const unavailable: RelationReviewCandidate[] = [
      {
        proposalIndex: 0,
        targetType: 'MEMO',
        targetId: 'd4b26246-0bbb-403a-98e0-424920514df7',
        targetLabel: null,
        available: false,
      },
    ];
    let draft = createReviewDraft('proposal-boundary-relation', related);
    draft = changeRelationSelection(draft, 0, true);

    expect(buildApplyRequest(draft, 'Asia/Seoul').items[0]?.proposalCandidateId)
      .toBe(boundaryIdentity);
    expect(isRelationSelectionReady(draft, unavailable)).toBe(false);
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
