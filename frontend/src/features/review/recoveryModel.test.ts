import { describe, expect, it } from 'vitest';
import type { Proposal, ProposalSummary } from '../../shared/api/types';
import { deriveCapturePolicy, deriveRecoveryState } from './recoveryModel';

const proposal: Proposal = {
  schemaVersion: '1',
  memoId: 'memo-1',
  memoRevision: 1,
  suggestedTitle: { value: '보류한 메모', confidence: 0.9, needsConfirmation: true },
  typeCandidates: [{ value: 'RECORD', score: 0.9 }],
  dateCandidates: [],
  tagCandidates: [],
  itemCandidates: [
    {
      candidateId: 'item-1',
      dueDateCandidateId: null,
      eventScheduleCandidates: [],
      suggestedEventScheduleCandidateId: null,
      kind: 'RECORD',
      title: '보류한 메모',
      sourceSpan: null,
      action: null,
      object: null,
      confidence: 0.9,
    },
  ],
  relationCandidates: [],
  ambiguityReasons: [],
  providerMetadata: {},
};

const postponed: ProposalSummary = {
  proposalId: 'proposal-1',
  status: 'POSTPONED',
  createdAt: '2026-08-05T01:00:00Z',
  proposal,
};

describe('recovery model', () => {
  it('keeps raw memo capture available when only recovery has failed', () => {
    expect(deriveCapturePolicy(true, null)).toBe('LOCKED');
    expect(deriveCapturePolicy(false, '복구 실패')).toBe('RAW_ONLY');
    expect(deriveCapturePolicy(false, null)).toBe('ANALYZE');
  });

  it('restores an applied application and the newest postponed proposal from server state', () => {
    const recovered = deriveRecoveryState(
      { applicationId: 'application-1', status: 'APPLIED' },
      [postponed],
    );

    expect(recovered.applicationId).toBe('application-1');
    expect(recovered.review).toBeNull();
    expect(recovered.postponedReview?.proposalId).toBe('proposal-1');
    expect(recovered.postponedReview?.title).toBe('보류한 메모');
  });

  it('restores the newest review-required proposal directly into the editor', () => {
    const recovered = deriveRecoveryState(
      { applicationId: null, status: 'NONE' },
      [
        postponed,
        {
          ...postponed,
          proposalId: 'proposal-2',
          status: 'REVIEW_REQUIRED',
          createdAt: '2026-08-05T02:00:00Z',
        },
      ],
    );

    expect(recovered.review?.proposalId).toBe('proposal-2');
    expect(recovered.postponedReview).toBeNull();
  });

  it('restores an explicit v2 due binding without falling back to array order', () => {
    const explicit: Proposal = {
      ...proposal,
      schemaVersion: '2',
      suggestedTitle: { value: '발표 준비', confidence: 0.9, needsConfirmation: true },
      typeCandidates: [{ value: 'TASK', score: 0.9 }],
      dateCandidates: [
        {
          candidateId: 'date-1',
          surfaceText: '11월 25일',
          value: '2026-11-25',
          precision: 'DATE_ONLY',
          timeSpecified: false,
          confidence: 0.9,
          ambiguityReasons: [],
        },
      ],
      itemCandidates: [
        {
          candidateId: 'item-task',
          dueDateCandidateId: 'date-1',
          eventScheduleCandidates: [],
          suggestedEventScheduleCandidateId: null,
          kind: 'TASK',
          title: '발표 준비',
          sourceSpan: null,
          action: '준비',
          object: '발표',
          confidence: 0.9,
        },
      ],
    };

    const recovered = deriveRecoveryState(
      { applicationId: null, status: 'NONE' },
      [{ ...postponed, proposalId: 'proposal-v2', status: 'REVIEW_REQUIRED', proposal: explicit }],
    );

    expect(recovered.review?.items[0].due).toMatchObject({
      candidateId: 'date-1',
      value: '2026-11-25',
    });
  });

  it('restores v3 EVENT alternatives without selecting the suggested schedule', () => {
    const eventProposal: Proposal = {
      ...proposal,
      schemaVersion: '3',
      suggestedTitle: { value: '디스코드 접속하기', confidence: 0.9, needsConfirmation: true },
      typeCandidates: [{ value: 'EVENT', score: 0.9 }],
      dateCandidates: [
        {
          candidateId: 'date-start',
          surfaceText: '오늘 오후 6시',
          value: '2026-08-05T18:00:00+09:00',
          precision: 'EXACT_TIME',
          timeSpecified: true,
          confidence: 0.9,
          ambiguityReasons: [],
        },
      ],
      itemCandidates: [
        {
          candidateId: 'item-event',
          dueDateCandidateId: null,
          eventScheduleCandidates: [
            {
              candidateId: 'schedule-1',
              mode: 'TIMED',
              startDateCandidateId: 'date-start',
              end: null,
              score: 0.85,
            },
          ],
          suggestedEventScheduleCandidateId: 'schedule-1',
          kind: 'EVENT',
          title: '디스코드 접속하기',
          sourceSpan: null,
          action: '접속',
          object: '디스코드',
          confidence: 0.9,
        },
      ],
    };

    const recovered = deriveRecoveryState(
      { applicationId: null, status: 'NONE' },
      [{
        ...postponed,
        proposalId: 'proposal-v3',
        status: 'REVIEW_REQUIRED',
        proposal: eventProposal,
      }],
    );

    expect(recovered.review?.items[0].eventScheduleCandidates).toHaveLength(1);
    expect(recovered.review?.items[0].suggestedEventScheduleCandidateId).toBe('schedule-1');
    expect(recovered.review?.items[0].eventSchedule).toBeNull();
    expect(recovered.review?.items[0].eventScheduleProposalCandidateId).toBeNull();
  });

  it.each(['NONE', 'UNDONE'] as const)('does not expose undo for %s server state', (status) => {
    const recovered = deriveRecoveryState(
      { applicationId: status === 'UNDONE' ? 'application-1' : null, status },
      [],
    );

    expect(recovered).toEqual({ applicationId: null, review: null, postponedReview: null });
  });
});
