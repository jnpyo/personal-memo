import { isWellFormedUtf16 } from '../validation/text';
import type { Proposal, RelationReviewCandidate } from './types';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const FIELDS = [
  'proposalIndex',
  'targetType',
  'targetId',
  'targetLabel',
  'available',
] as const;

export class RelationReviewContractError extends Error {
  constructor(readonly field: string) {
    super(`Invalid relation review response field: ${field}`);
    this.name = 'RelationReviewContractError';
  }
}

function fail(field: string): never {
  throw new RelationReviewContractError(field);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function exactRecord(value: unknown, field: string): Record<string, unknown> {
  if (!isRecord(value)) fail(field);
  const actual = Object.keys(value).sort();
  const expected = [...FIELDS].sort();
  if (
    actual.length !== expected.length ||
    actual.some((key, index) => key !== expected[index])
  ) {
    fail(field);
  }
  return value;
}

function targetLabel(
  value: unknown,
  field: string,
  targetType: RelationReviewCandidate['targetType'],
): string | null {
  if (value === null) return null;
  const maximum = targetType === 'MEMO' ? 240 : 100;
  if (
    typeof value !== 'string' ||
    !isWellFormedUtf16(value) ||
    value.trim().length === 0 ||
    [...value].length > maximum
  ) {
    fail(field);
  }
  return value;
}

export function decodeRelationReviewCandidates(
  value: unknown,
  proposal: Proposal,
): RelationReviewCandidate[] {
  if (
    !Array.isArray(value) ||
    value.length > 10 ||
    value.length !== proposal.relationCandidates.length
  ) {
    fail('relationReviewCandidates');
  }

  return value.map((entry, index) => {
    const field = `relationReviewCandidates[${index}]`;
    const candidate = exactRecord(entry, field);
    const proposed = proposal.relationCandidates[index];
    if (!Number.isSafeInteger(candidate.proposalIndex) || candidate.proposalIndex !== index) {
      fail(`${field}.proposalIndex`);
    }
    if (candidate.targetType !== proposed?.targetType) fail(`${field}.targetType`);
    if (
      typeof candidate.targetId !== 'string' ||
      !UUID_PATTERN.test(candidate.targetId) ||
      candidate.targetId !== proposed.targetId.toLowerCase()
    ) {
      fail(`${field}.targetId`);
    }
    if (typeof candidate.available !== 'boolean') fail(`${field}.available`);
    const label = targetLabel(
      candidate.targetLabel,
      `${field}.targetLabel`,
      proposed.targetType,
    );
    if (candidate.available !== (label !== null)) fail(`${field}.targetLabel`);

    return {
      proposalIndex: index,
      targetType: proposed.targetType,
      targetId: candidate.targetId,
      targetLabel: label,
      available: candidate.available,
    };
  });
}
