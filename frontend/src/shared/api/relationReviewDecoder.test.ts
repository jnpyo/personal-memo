import { describe, expect, it } from 'vitest';
import type { Proposal } from './types';
import {
  decodeRelationReviewCandidates,
  RelationReviewContractError,
} from './relationReviewDecoder';

const TARGET_ID = 'A54E816B-6CAC-47AE-8109-E2960456A761';

function proposal(): Proposal {
  return {
    schemaVersion: '2',
    memoId: '24df3528-9c8d-438d-9cbb-ddd6e461a0c2',
    memoRevision: 1,
    suggestedTitle: { value: '연결 검토', confidence: 0.9, needsConfirmation: true },
    typeCandidates: [{ value: 'TASK', score: 0.9 }],
    dateCandidates: [],
    tagCandidates: [],
    itemCandidates: [
      {
        candidateId: ' source-1 ',
        dueDateCandidateId: null,
        kind: 'TASK',
        title: '출발 항목',
        sourceSpan: null,
        action: null,
        object: null,
        confidence: 0.9,
      },
    ],
    relationCandidates: [
      {
        sourceCandidateId: ' source-1 ',
        targetType: 'MEMO',
        targetId: TARGET_ID,
        relationType: 'REFERENCES',
        score: 0.8,
      },
    ],
    ambiguityReasons: [],
    providerMetadata: {},
  };
}

function response(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    proposalIndex: 0,
    targetType: 'MEMO',
    targetId: TARGET_ID.toLowerCase(),
    targetLabel: '내 메모 미리보기',
    available: true,
    ...overrides,
  };
}

describe('relation review candidate decoder', () => {
  it('accepts canonical backend UUID text for an uppercase validated proposal UUID', () => {
    expect(decodeRelationReviewCandidates([response()], proposal())).toEqual([
      {
        proposalIndex: 0,
        targetType: 'MEMO',
        targetId: TARGET_ID.toLowerCase(),
        targetLabel: '내 메모 미리보기',
        available: true,
      },
    ]);
  });

  it('rejects a different semantic UUID and non-canonical response UUID text', () => {
    expect(() => decodeRelationReviewCandidates([
      response({ targetId: 'b47b7a9c-426a-4b61-a8ed-98f152fe5c9c' }),
    ], proposal())).toThrowError(RelationReviewContractError);
    expect(() => decodeRelationReviewCandidates([
      response({ targetId: TARGET_ID }),
    ], proposal())).toThrowError(RelationReviewContractError);
  });

  it('requires a closed, ordered, proposal-aligned bare array', () => {
    expect(() => decodeRelationReviewCandidates({ items: [response()] }, proposal()))
      .toThrowError(RelationReviewContractError);
    expect(() => decodeRelationReviewCandidates([response({ proposalIndex: 1 })], proposal()))
      .toThrowError(RelationReviewContractError);
    expect(() => decodeRelationReviewCandidates([response({ extra: true })], proposal()))
      .toThrowError(RelationReviewContractError);
    expect(() => decodeRelationReviewCandidates([], proposal()))
      .toThrowError(RelationReviewContractError);
  });

  it('requires unavailable targets to have a null label and available targets to have one', () => {
    expect(decodeRelationReviewCandidates([
      response({ targetLabel: null, available: false }),
    ], proposal())[0]).toMatchObject({ targetLabel: null, available: false });
    expect(() => decodeRelationReviewCandidates([
      response({ targetLabel: null, available: true }),
    ], proposal())).toThrowError(RelationReviewContractError);
    expect(() => decodeRelationReviewCandidates([
      response({ targetLabel: '남은 이름', available: false }),
    ], proposal())).toThrowError(RelationReviewContractError);
  });

  it('enforces target-specific Unicode code-point label bounds', () => {
    expect(decodeRelationReviewCandidates([
      response({ targetLabel: '😀'.repeat(240) }),
    ], proposal())[0]?.targetLabel).toHaveLength(480);
    expect(() => decodeRelationReviewCandidates([
      response({ targetLabel: '😀'.repeat(241) }),
    ], proposal())).toThrowError(RelationReviewContractError);

    const tagProposal: Proposal = {
      ...proposal(),
      relationCandidates: [{
        ...proposal().relationCandidates[0],
        targetType: 'TAG',
      }],
    };
    expect(() => decodeRelationReviewCandidates([
      response({ targetType: 'TAG', targetLabel: '가'.repeat(101) }),
    ], tagProposal)).toThrowError(RelationReviewContractError);
  });
});
