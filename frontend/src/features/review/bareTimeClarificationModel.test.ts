import { describe, expect, it } from 'vitest';
import type { Proposal } from '../../shared/api/types';
import {
  addManualItem,
  buildApplyRequest,
  createReviewDraft,
  changeItemDue,
  changeItemEventSchedule,
  changeItemKind,
} from './reviewModel';
import {
  captureDateInTimeZone,
  createUserResolvedExactDue,
  dismissBareTimeClarification,
  findBareTimeClarification,
  isBareTimeCandidate,
  requiresReviewSourceTimeZone,
  resolveWallClockInTimeZone,
  toTwentyFourHour,
} from './bareTimeClarificationModel';

function bareTimeProposal(surfaceText = '6시'): Proposal {
  return {
    schemaVersion: '2',
    memoId: '00000000-0000-0000-0000-000000000101',
    memoRevision: 1,
    suggestedTitle: {
      value: '디스코드 접속하기',
      confidence: 0.9,
      needsConfirmation: true,
    },
    typeCandidates: [{ value: 'TASK', score: 0.91 }],
    dateCandidates: [{
      candidateId: 'date-bare-time',
      surfaceText,
      value: null,
      precision: 'UNKNOWN',
      timeSpecified: false,
      confidence: 0.7,
      ambiguityReasons: ['IMPRECISE_DATE'],
    }],
    tagCandidates: [],
    itemCandidates: [{
      candidateId: 'item-discord',
      dueDateCandidateId: null,
      eventScheduleCandidates: [],
      suggestedEventScheduleCandidateId: null,
      kind: 'TASK',
      title: '디스코드 접속하기',
      sourceSpan: { start: 3, end: 12 },
      action: '접속하기',
      object: '디스코드',
      confidence: 0.9,
    }],
    relationCandidates: [],
    ambiguityReasons: ['IMPRECISE_DATE'],
    providerMetadata: {},
  };
}

describe('bare time clarification model', () => {
  it('recognizes a stored or capture-day-exhausted 6시 fallback without choosing a date or period', () => {
    const review = createReviewDraft('proposal-bare-time', bareTimeProposal());
    const clarification = findBareTimeClarification(review);

    expect(clarification).toMatchObject({
      candidateId: 'date-bare-time',
      itemIndex: 0,
      hour12: 6,
      minute: 0,
      fixedHour24: null,
      fixedPeriod: null,
    });
    expect(review.items[0].kind).toBe('TASK');
    expect(review.items[0].due).toBeNull();
    expect(review.items[0].eventSchedule).toBeNull();
  });

  it.each([
    ['오전 6시', 6, 0, 'AM'],
    ['오후 6시 5분에', 18, 5, 'PM'],
    ['0시', 0, 0, null],
    ['00시 7분', 0, 7, null],
    ['07시', 7, 0, null],
    ['09시 5분', 9, 5, null],
    ['13시', 13, 0, null],
    ['23시 59분에', 23, 59, null],
    ['06:30', 6, 30, null],
    ['18:45에', 18, 45, null],
  ] as const)(
    'preserves the explicit clock meaning for %s',
    (surfaceText, fixedHour24, minute, fixedPeriod) => {
      const clarification = findBareTimeClarification(
        createReviewDraft('proposal', bareTimeProposal(surfaceText)),
      );

      expect(clarification).toMatchObject({ fixedHour24, minute, fixedPeriod });
    },
  );

  it.each([
    '오늘 6시',
    '6시 반',
    '6시쯤',
    '주말 6시',
    '6시 이전',
    '오후 6시 이후',
    '18시 안에',
    '18:00 전',
    '18:00 후',
    '18시 초',
    '18시 경',
    '18시 무렵',
    '18시 정도',
    '18시 전후',
  ])('does not use the compact flow for %s', (surfaceText) => {
    const proposal = bareTimeProposal(surfaceText);
    expect(isBareTimeCandidate(proposal.dateCandidates[0])).toBe(false);
    expect(findBareTimeClarification(createReviewDraft('proposal', proposal))).toBeNull();
  });

  it('accepts bounded minute forms and only uses directional particles for compatible item kinds', () => {
    expect(isBareTimeCandidate(bareTimeProposal('6시 5분에').dateCandidates[0])).toBe(true);
    expect(isBareTimeCandidate(bareTimeProposal('12시59분부터').dateCandidates[0])).toBe(true);

    const taskFrom = createReviewDraft('task-from', bareTimeProposal('6시부터'));
    const taskUntil = createReviewDraft('task-until', bareTimeProposal('6시까지'));
    const eventFrom = changeItemKind(taskFrom, 0, 'EVENT');
    const eventUntil = changeItemKind(taskUntil, 0, 'EVENT');
    expect(findBareTimeClarification(taskFrom)).toBeNull();
    expect(findBareTimeClarification(taskUntil)).toMatchObject({ hour12: 6 });
    expect(findBareTimeClarification(eventFrom)).toMatchObject({ hour12: 6 });
    expect(findBareTimeClarification(eventUntil)).toBeNull();

    const explicitTaskFrom = createReviewDraft('explicit-task-from', bareTimeProposal('18시부터'));
    const explicitEventFrom = changeItemKind(explicitTaskFrom, 0, 'EVENT');
    expect(findBareTimeClarification(explicitTaskFrom)).toBeNull();
    expect(findBareTimeClarification(explicitEventFrom)).toMatchObject({ fixedHour24: 18 });
  });

  it('requires an explicit resolution or dismissal before the compact question is complete', () => {
    const review = createReviewDraft('proposal-bare-time', bareTimeProposal());
    const dismissed = dismissBareTimeClarification(review, 'date-bare-time');

    expect(findBareTimeClarification(dismissed)).toBeNull();
    expect(dismissed.items[0].due).toBeNull();
    expect(dismissed.proposal.dateCandidates[0]).toEqual(review.proposal.dateCandidates[0]);
  });

  it('builds a no-time Apply request only after the explicit dismissal', () => {
    const review = createReviewDraft('proposal-bare-time', bareTimeProposal());
    const dismissed = dismissBareTimeClarification(review, 'date-bare-time');
    const request = buildApplyRequest(dismissed, 'Asia/Seoul');

    expect(findBareTimeClarification(dismissed)).toBeNull();
    expect(requiresReviewSourceTimeZone(dismissed)).toBe(false);
    expect(request.items[0]).toMatchObject({
      proposalCandidateId: 'item-discord',
      kind: 'TASK',
      due: null,
    });
    expect(request.selectionSchemaVersion).toBeUndefined();
  });

  it('does not let an added manual item bypass the unresolved original time', () => {
    const review = addManualItem(createReviewDraft('proposal-bare-time', bareTimeProposal()));

    expect(review.items).toHaveLength(2);
    expect(findBareTimeClarification(review)).toMatchObject({
      candidateId: 'date-bare-time',
      itemIndex: 0,
    });
    expect(requiresReviewSourceTimeZone(review)).toBe(true);
  });

  it('creates a user-resolved exact due without inventing proposal candidate provenance', () => {
    const candidate = bareTimeProposal().dateCandidates[0];
    const due = createUserResolvedExactDue(candidate, '2026-09-02T18:00:00+09:00');

    expect(due).toEqual({
      surfaceText: '6시',
      value: '2026-09-02T18:00:00+09:00',
      precision: 'EXACT_TIME',
      timeSpecified: true,
    });
    expect(due).not.toHaveProperty('candidateId');
  });

  it('builds the existing explicit Apply contract only after the user resolves the task time', () => {
    const proposal = bareTimeProposal();
    let review = createReviewDraft('proposal-bare-time', proposal);
    review = changeItemDue(
      review,
      0,
      createUserResolvedExactDue(
        proposal.dateCandidates[0],
        '2026-09-02T18:00:00+09:00',
      ),
    );

    const request = buildApplyRequest(review, 'Asia/Seoul');
    expect(request.selectionSchemaVersion).toBeUndefined();
    expect(request.items[0]).toEqual({
      proposalCandidateId: 'item-discord',
      kind: 'TASK',
      title: '디스코드 접속하기',
      due: {
        surfaceText: '6시',
        value: '2026-09-02T18:00:00+09:00',
        precision: 'EXACT_TIME',
        timeZone: 'Asia/Seoul',
        timeSpecified: true,
      },
    });
    expect(proposal.dateCandidates[0]).toMatchObject({
      value: null,
      precision: 'UNKNOWN',
      timeSpecified: false,
    });
  });

  it('builds the existing explicit EVENT schedule contract after a user-resolved start', () => {
    let review = createReviewDraft('proposal-bare-time-event', bareTimeProposal());
    review = changeItemKind(review, 0, 'EVENT');
    expect(findBareTimeClarification(review)).toMatchObject({ itemIndex: 0, hour12: 6 });

    review = changeItemEventSchedule(review, 0, {
      mode: 'TIMED',
      start: '2026-09-02T18:00:00+09:00',
      end: '',
    });
    const request = buildApplyRequest(review, 'Asia/Seoul');

    expect(request.selectionSchemaVersion).toBe('2');
    expect(request.items[0]).toMatchObject({
      proposalCandidateId: 'item-discord',
      kind: 'EVENT',
      due: null,
      eventSchedule: {
        mode: 'TIMED',
        start: '2026-09-02T18:00:00+09:00',
        end: null,
        timeZone: 'Asia/Seoul',
      },
    });
  });

  it('requires the immutable revision time zone for unresolved or exact time selections', () => {
    let task = createReviewDraft('proposal-bare-time', bareTimeProposal());
    expect(requiresReviewSourceTimeZone(task)).toBe(true);

    task = dismissBareTimeClarification(task, 'date-bare-time');
    expect(requiresReviewSourceTimeZone(task)).toBe(false);

    task = changeItemDue(
      task,
      0,
      createUserResolvedExactDue(
        task.proposal.dateCandidates[0],
        '2026-09-02T18:00:00+09:00',
      ),
    );
    expect(requiresReviewSourceTimeZone(task)).toBe(true);

    const event = changeItemEventSchedule(
      { ...task, items: [{ ...task.items[0], kind: 'EVENT', due: null }], selectedType: 'EVENT' },
      0,
      { mode: 'TIMED', start: '2026-09-02T18:00:00+09:00', end: '' },
    );
    expect(requiresReviewSourceTimeZone(event)).toBe(true);
  });

  it('converts 12-hour choices without guessing AM or PM', () => {
    expect(toTwentyFourHour(12, 'AM')).toBe(0);
    expect(toTwentyFourHour(12, 'PM')).toBe(12);
    expect(toTwentyFourHour(6, 'AM')).toBe(6);
    expect(toTwentyFourHour(6, 'PM')).toBe(18);
  });

  it('resolves a unique Asia/Seoul wall clock to a whole-second offset value', () => {
    expect(resolveWallClockInTimeZone('2026-09-02', 18, 0, 'Asia/Seoul')).toEqual({
      status: 'UNIQUE',
      option: {
        value: '2026-09-02T18:00:00+09:00',
        offset: '+09:00',
      },
    });
  });

  it('fails closed for a DST gap and requires a choice for a DST overlap', () => {
    expect(resolveWallClockInTimeZone('2026-03-08', 2, 30, 'America/New_York')).toEqual({
      status: 'GAP',
    });

    const overlap = resolveWallClockInTimeZone(
      '2026-11-01',
      1,
      30,
      'America/New_York',
    );
    expect(overlap.status).toBe('OVERLAP');
    if (overlap.status === 'OVERLAP') {
      expect(overlap.options).toEqual([
        { value: '2026-11-01T01:30:00-04:00', offset: '-04:00' },
        { value: '2026-11-01T01:30:00-05:00', offset: '-05:00' },
      ]);
    }
  });

  it('derives the shortcut date from immutable capture time even when review happens later', () => {
    const capturedAt = '2026-09-01T15:30:00Z';

    expect(captureDateInTimeZone(capturedAt, 'Asia/Seoul')).toBe('2026-09-02');
    expect(captureDateInTimeZone(capturedAt, 'America/New_York')).toBe('2026-09-01');
    expect(captureDateInTimeZone('not-an-instant', 'Asia/Seoul')).toBeNull();
    expect(captureDateInTimeZone(capturedAt, 'Not/AZone')).toBeNull();
  });
});
