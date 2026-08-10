import { describe, expect, it } from 'vitest';
import {
  decodeProposal,
  decodeProposalSummaries,
  ProposalContractError,
} from './proposalDecoder';

function proposalPayload(): Record<string, unknown> {
  return {
    schemaVersion: '1',
    memoId: '8dd29246-4ec2-4e7f-bbf9-a3ff316acdd4',
    memoRevision: 2,
    suggestedTitle: {
      value: '운영체제 과제',
      confidence: 0.9,
      needsConfirmation: true,
    },
    typeCandidates: [{ value: 'TASK', score: 0.9 }],
    dateCandidates: [
      {
        surfaceText: '11.25',
        value: '2026-11-25',
        precision: 'DATE_ONLY',
        timeSpecified: false,
        confidence: 0.9,
        ambiguityReasons: ['MISSING_YEAR', 'MISSING_TIME'],
      },
    ],
    tagCandidates: [
      {
        existingTagId: null,
        canonicalName: '운영체제',
        matchedAlias: null,
        score: 0.9,
        isNewProposal: true,
      },
    ],
    itemCandidates: [
      {
        candidateId: 'item-1',
        kind: 'TASK',
        title: '운영체제 과제',
        sourceSpan: null,
        action: '제출',
        object: '과제',
        confidence: 0.9,
      },
    ],
    relationCandidates: [],
    ambiguityReasons: ['MISSING_YEAR', 'MISSING_TIME'],
    providerMetadata: { analyzerVersion: 'deterministic-fake-v2' },
  };
}

function proposalV2Payload(): Record<string, unknown> {
  const payload = structuredClone(proposalPayload());
  payload.schemaVersion = '2';
  const dates = payload.dateCandidates as Record<string, unknown>[];
  dates[0]!.candidateId = 'date-1';
  const items = payload.itemCandidates as Record<string, unknown>[];
  items[0]!.dueDateCandidateId = 'date-1';
  return payload;
}

describe('analysis proposal decoder', () => {
  it('accepts the supported schema and preserves the core review fields', () => {
    const decoded = decodeProposal(proposalPayload());

    expect(decoded.schemaVersion).toBe('1');
    expect(decoded.typeCandidates[0]?.value).toBe('TASK');
    expect(decoded.dateCandidates[0]?.candidateId).toBeNull();
    expect(decoded.itemCandidates[0]).toMatchObject({
      kind: 'TASK',
      title: '운영체제 과제',
      dueDateCandidateId: null,
    });
  });

  it('accepts schema v2 with an explicit task-to-date binding', () => {
    const decoded = decodeProposal(proposalV2Payload());

    expect(decoded.schemaVersion).toBe('2');
    expect(decoded.dateCandidates[0]?.candidateId).toBe('date-1');
    expect(decoded.itemCandidates[0]?.dueDateCandidateId).toBe('date-1');
  });

  it('keeps schema v1 strict while preserving historical recovery', () => {
    const payload = proposalPayload();
    ((payload.dateCandidates as Record<string, unknown>[])[0]).candidateId = 'date-1';

    expect(() => decodeProposal(payload)).toThrowError(ProposalContractError);
  });

  it.each([undefined, '3'])('rejects an unsupported schemaVersion %s', (schemaVersion) => {
    const payload = { ...proposalPayload(), schemaVersion };

    expect(() => decodeProposal(payload)).toThrowError(ProposalContractError);
    try {
      decodeProposal(payload);
      expect.fail('unsupported schemaVersion should not be decoded');
    } catch (error) {
      expect(error).toMatchObject({ field: 'schemaVersion' });
    }
  });

  it('requires both v2 binding fields instead of silently applying the v1 heuristic', () => {
    const missingDateId = proposalV2Payload();
    delete ((missingDateId.dateCandidates as Record<string, unknown>[])[0]).candidateId;
    const missingDueReference = proposalV2Payload();
    delete ((missingDueReference.itemCandidates as Record<string, unknown>[])[0])
      .dueDateCandidateId;

    expect(() => decodeProposal(missingDateId)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingDueReference)).toThrowError(ProposalContractError);
  });

  it('rejects duplicate or dangling v2 date candidate references', () => {
    const duplicateDateIds = proposalV2Payload();
    (duplicateDateIds.dateCandidates as Record<string, unknown>[]).push({
      ...(duplicateDateIds.dateCandidates as Record<string, unknown>[])[0],
      surfaceText: '11.26',
      value: '2026-11-26',
    });
    const danglingReference = proposalV2Payload();
    ((danglingReference.itemCandidates as Record<string, unknown>[])[0]).dueDateCandidateId =
      'date-missing';

    expect(() => decodeProposal(duplicateDateIds)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(danglingReference)).toThrowError(ProposalContractError);
  });

  it('rejects a v2 due binding for non-task or imprecise dates', () => {
    const nonTask = proposalV2Payload();
    ((nonTask.itemCandidates as Record<string, unknown>[])[0]).kind = 'EVENT';
    const approximate = proposalV2Payload();
    Object.assign((approximate.dateCandidates as Record<string, unknown>[])[0], {
      surfaceText: '주말쯤',
      value: null,
      precision: 'APPROXIMATE',
      timeSpecified: false,
      ambiguityReasons: ['IMPRECISE_DATE'],
    });

    expect(() => decodeProposal(nonTask)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(approximate)).toThrowError(ProposalContractError);
  });

  it('rejects future semantic or item kinds and malformed collections at the API boundary', () => {
    const futureType = proposalPayload();
    futureType.typeCandidates = [{ value: 'FUTURE_TYPE', score: 0.9 }];
    const futureItem = proposalPayload();
    futureItem.itemCandidates = [{ kind: 'FUTURE_TYPE', title: '미래 항목' }];
    const missingTags = proposalPayload();
    delete missingTags.tagCandidates;

    expect(() => decodeProposal(futureType)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(futureItem)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingTags)).toThrowError(ProposalContractError);
  });

  it('validates recovery envelopes and the status requested by the client', () => {
    const summary = {
      proposalId: '7a47cd75-6e48-479f-b817-bb62a1802dd4',
      status: 'REVIEW_REQUIRED',
      createdAt: '2026-08-05T02:00:00Z',
      proposal: proposalPayload(),
    };

    expect(decodeProposalSummaries([summary], 'REVIEW_REQUIRED')).toHaveLength(1);
    expect(() => decodeProposalSummaries([summary], 'POSTPONED')).toThrowError(
      ProposalContractError,
    );
  });

  it('requires scored and confirmation fields declared mandatory by schema v1', () => {
    const missingConfidence = proposalPayload();
    delete (missingConfidence.suggestedTitle as Record<string, unknown>).confidence;
    const missingTypeScore = proposalPayload();
    delete ((missingTypeScore.typeCandidates as Record<string, unknown>[])[0]).score;
    const missingItemScore = proposalPayload();
    delete ((missingItemScore.itemCandidates as Record<string, unknown>[])[0]).confidence;
    const missingConfirmation = proposalPayload();
    delete (missingConfirmation.suggestedTitle as Record<string, unknown>).needsConfirmation;
    const missingNewTagFlag = proposalPayload();
    delete ((missingNewTagFlag.tagCandidates as Record<string, unknown>[])[0]).isNewProposal;

    expect(() => decodeProposal(missingConfidence)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingTypeScore)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingItemScore)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingConfirmation)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(missingNewTagFlag)).toThrowError(ProposalContractError);
  });

  it('rejects invalid UUIDs, excessive text, and invalid source spans', () => {
    const invalidMemo = proposalPayload();
    invalidMemo.memoId = 'not-a-uuid';
    const longTitle = proposalPayload();
    (longTitle.suggestedTitle as Record<string, unknown>).value = '가'.repeat(201);
    const reversedSpan = proposalPayload();
    ((reversedSpan.itemCandidates as Record<string, unknown>[])[0]).sourceSpan = {
      start: 9,
      end: 2,
    };

    expect(() => decodeProposal(invalidMemo)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(longTitle)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(reversedSpan)).toThrowError(ProposalContractError);
  });

  it('enforces ambiguity enums and date precision/value coherence', () => {
    const unknownReason = proposalPayload();
    unknownReason.ambiguityReasons = ['FUTURE_REASON'];
    const impossibleDate = proposalPayload();
    ((impossibleDate.dateCandidates as Record<string, unknown>[])[0]).value = '2026-02-30';
    const wrongTimeFlag = proposalPayload();
    ((wrongTimeFlag.dateCandidates as Record<string, unknown>[])[0]).timeSpecified = true;

    expect(() => decodeProposal(unknownReason)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(impossibleDate)).toThrowError(ProposalContractError);
    expect(() => decodeProposal(wrongTimeFlag)).toThrowError(ProposalContractError);
  });

  it('decodes only the supported relation shape', () => {
    const valid = proposalPayload();
    valid.relationCandidates = [
      {
        sourceCandidateId: 'item-1',
        targetType: 'MEMO',
        targetId: 'da5779b5-408b-45f7-a194-763ac96d64f0',
        relationType: 'RELATED_TO',
        score: 0.8,
      },
    ];
    expect(decodeProposal(valid).relationCandidates).toHaveLength(1);

    const invalid = proposalPayload();
    invalid.relationCandidates = [
      {
        sourceCandidateId: 'item-1',
        targetType: 'SYSTEM',
        targetId: 'not-a-uuid',
        relationType: 'MERGES',
        score: 0.8,
      },
    ];
    expect(() => decodeProposal(invalid)).toThrowError(ProposalContractError);
  });

  it('cross-checks proposal identity against its analysis run', () => {
    expect(() =>
      decodeProposal(proposalPayload(), {
        memoId: '9b070b16-c20c-494a-9b55-a38542538680',
        memoRevision: 2,
      }),
    ).toThrowError(ProposalContractError);
    expect(() =>
      decodeProposal(proposalPayload(), {
        memoId: '8dd29246-4ec2-4e7f-bbf9-a3ff316acdd4',
        memoRevision: 3,
      }),
    ).toThrowError(ProposalContractError);
  });
});
