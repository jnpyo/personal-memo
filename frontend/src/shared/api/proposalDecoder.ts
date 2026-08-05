import type {
  DatePrecision,
  ItemKind,
  Proposal,
  ProposalDateCandidate,
  ProposalItemCandidate,
  ProposalSummary,
  ProposalTagCandidate,
  RelationCandidate,
  SemanticType,
} from './types';
import { isValidIsoDate, isValidOffsetDateTime } from '../validation/dateTime';

const MAX_INT = 2_147_483_647;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ITEM_KINDS = new Set<ItemKind>(['TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD']);
const SEMANTIC_TYPES = new Set<SemanticType>([...ITEM_KINDS, 'UNKNOWN']);
const DATE_PRECISIONS = new Set<DatePrecision>([
  'EXACT_TIME',
  'DATE_ONLY',
  'RELATIVE_EXACT',
  'APPROXIMATE',
  'UNKNOWN',
]);
const AMBIGUITY_REASONS = new Set([
  'LOW_TYPE_MARGIN',
  'LOW_TAG_SIMILARITY',
  'TAG_CONFLICT',
  'NEW_TOPIC',
  'MISSING_YEAR',
  'MISSING_TIME',
  'IMPRECISE_DATE',
  'CONFLICTING_DATES',
  'UNRESOLVED_REFERENCE',
  'MISSING_ACTION',
  'MISSING_OBJECT',
  'MULTI_INTENT',
  'CANDIDATE_LIMIT_EXCEEDED',
  'LOCAL_CLOUD_CONFLICT',
]);
const RELATION_TARGET_TYPES = new Set<RelationCandidate['targetType']>(['MEMO', 'TAG']);
const RELATION_TYPES = new Set<RelationCandidate['relationType']>([
  'RELATED_TO',
  'CONTINUES',
  'DEPENDS_ON',
  'REFERENCES',
]);
const RECOVERY_STATUSES = new Set<ProposalSummary['status']>([
  'REVIEW_REQUIRED',
  'POSTPONED',
]);

export type ExpectedProposalIdentity = {
  memoId: string;
  memoRevision: number;
};

export class ProposalContractError extends Error {
  constructor(readonly field: string) {
    super(`Unsupported analysis proposal at ${field}.`);
    this.name = 'ProposalContractError';
  }
}

function fail(field: string): never {
  throw new ProposalContractError(field);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function record(value: unknown, field: string): Record<string, unknown> {
  return isRecord(value) ? value : fail(field);
}

function closedRecord(
  value: unknown,
  field: string,
  allowedKeys: readonly string[],
): Record<string, unknown> {
  const result = record(value, field);
  const allowed = new Set(allowedKeys);
  const unexpected = Object.keys(result).find((key) => !allowed.has(key));
  if (unexpected) fail(`${field}.${unexpected}`);
  return result;
}

function array(value: unknown, field: string, maximum: number, minimum = 0): unknown[] {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) fail(field);
  return value;
}

function codePointLength(value: string): number {
  return [...value].length;
}

function text(value: unknown, field: string, maximum: number, allowEmpty = false): string {
  if (
    typeof value !== 'string' ||
    (!allowEmpty && !value.trim()) ||
    codePointLength(value) > maximum
  ) {
    fail(field);
  }
  return value;
}

function nullableText(
  value: unknown,
  field: string,
  maximum: number,
  allowEmpty = false,
): string | null {
  return value === null ? null : text(value, field, maximum, allowEmpty);
}

function score(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) {
    fail(field);
  }
  return value;
}

function boolean(value: unknown, field: string): boolean {
  return typeof value === 'boolean' ? value : fail(field);
}

function integer(value: unknown, field: string, minimum = 0): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > MAX_INT) {
    fail(field);
  }
  return value as number;
}

function uuid(value: unknown, field: string): string {
  const result = text(value, field, 36);
  return UUID.test(result) ? result : fail(field);
}

function nullableUuid(value: unknown, field: string): string | null {
  return value === null ? null : uuid(value, field);
}

function ambiguityReasonArray(value: unknown, field: string): string[] {
  const entries = array(value, field, AMBIGUITY_REASONS.size);
  const result = entries.map((entry, index) => {
    if (typeof entry !== 'string' || !AMBIGUITY_REASONS.has(entry)) fail(`${field}[${index}]`);
    return entry;
  });
  if (new Set(result).size !== result.length) fail(field);
  return result;
}

function isItemKind(value: unknown): value is ItemKind {
  return typeof value === 'string' && ITEM_KINDS.has(value as ItemKind);
}

function isSemanticType(value: unknown): value is SemanticType {
  return typeof value === 'string' && SEMANTIC_TYPES.has(value as SemanticType);
}

function isDatePrecision(value: unknown): value is DatePrecision {
  return typeof value === 'string' && DATE_PRECISIONS.has(value as DatePrecision);
}

function decodeDateCandidate(value: unknown, index: number): ProposalDateCandidate {
  const field = `dateCandidates[${index}]`;
  const candidate = closedRecord(value, field, [
    'surfaceText',
    'value',
    'precision',
    'timeSpecified',
    'confidence',
    'ambiguityReasons',
  ]);
  const precision = candidate.precision;
  if (!isDatePrecision(precision)) fail(`${field}.precision`);
  const timeSpecified = boolean(candidate.timeSpecified, `${field}.timeSpecified`);
  const rawValue = candidate.value;

  if (precision === 'DATE_ONLY') {
    if (timeSpecified || typeof rawValue !== 'string' || !isValidIsoDate(rawValue)) {
      fail(`${field}.value`);
    }
  } else if (precision === 'EXACT_TIME' || precision === 'RELATIVE_EXACT') {
    if (!timeSpecified || typeof rawValue !== 'string' || !isValidOffsetDateTime(rawValue)) {
      fail(`${field}.value`);
    }
  } else if (timeSpecified || rawValue !== null) {
    fail(`${field}.value`);
  }

  return {
    surfaceText: text(candidate.surfaceText, `${field}.surfaceText`, 200),
    value: rawValue as string | null,
    precision,
    timeSpecified,
    confidence: score(candidate.confidence, `${field}.confidence`),
    ambiguityReasons: ambiguityReasonArray(
      candidate.ambiguityReasons,
      `${field}.ambiguityReasons`,
    ),
  };
}

function decodeTagCandidate(value: unknown, index: number): ProposalTagCandidate {
  const field = `tagCandidates[${index}]`;
  const candidate = closedRecord(value, field, [
    'existingTagId',
    'canonicalName',
    'matchedAlias',
    'score',
    'isNewProposal',
  ]);
  return {
    existingTagId: nullableUuid(candidate.existingTagId, `${field}.existingTagId`),
    canonicalName: text(candidate.canonicalName, `${field}.canonicalName`, 100),
    matchedAlias: nullableText(candidate.matchedAlias, `${field}.matchedAlias`, 100),
    score: score(candidate.score, `${field}.score`),
    isNewProposal: boolean(candidate.isNewProposal, `${field}.isNewProposal`),
  };
}

function decodeSourceSpan(
  value: unknown,
  field: string,
): { start: number; end: number } | null {
  if (value === null) return null;
  const span = closedRecord(value, field, ['start', 'end']);
  const start = integer(span.start, `${field}.start`);
  const end = integer(span.end, `${field}.end`);
  if (end < start) fail(field);
  return { start, end };
}

function decodeItemCandidate(value: unknown, index: number): ProposalItemCandidate {
  const field = `itemCandidates[${index}]`;
  const candidate = closedRecord(value, field, [
    'candidateId',
    'kind',
    'title',
    'sourceSpan',
    'action',
    'object',
    'confidence',
  ]);
  if (!isItemKind(candidate.kind)) fail(`${field}.kind`);

  return {
    candidateId: text(candidate.candidateId, `${field}.candidateId`, 100),
    kind: candidate.kind,
    title: text(candidate.title, `${field}.title`, 200),
    sourceSpan: decodeSourceSpan(candidate.sourceSpan, `${field}.sourceSpan`),
    action: nullableText(candidate.action, `${field}.action`, 200),
    object: nullableText(candidate.object, `${field}.object`, 200),
    confidence: score(candidate.confidence, `${field}.confidence`),
  };
}

function decodeRelationCandidate(value: unknown, index: number): RelationCandidate {
  const field = `relationCandidates[${index}]`;
  const candidate = closedRecord(value, field, [
    'sourceCandidateId',
    'targetType',
    'targetId',
    'relationType',
    'score',
  ]);
  if (
    typeof candidate.targetType !== 'string' ||
    !RELATION_TARGET_TYPES.has(candidate.targetType as RelationCandidate['targetType'])
  ) {
    fail(`${field}.targetType`);
  }
  if (
    typeof candidate.relationType !== 'string' ||
    !RELATION_TYPES.has(candidate.relationType as RelationCandidate['relationType'])
  ) {
    fail(`${field}.relationType`);
  }
  return {
    sourceCandidateId: text(candidate.sourceCandidateId, `${field}.sourceCandidateId`, 100),
    targetType: candidate.targetType as RelationCandidate['targetType'],
    targetId: uuid(candidate.targetId, `${field}.targetId`),
    relationType: candidate.relationType as RelationCandidate['relationType'],
    score: score(candidate.score, `${field}.score`),
  };
}

export function decodeProposal(
  value: unknown,
  expectedIdentity?: ExpectedProposalIdentity,
): Proposal {
  const proposal = closedRecord(value, 'proposal', [
    'schemaVersion',
    'memoId',
    'memoRevision',
    'suggestedTitle',
    'typeCandidates',
    'dateCandidates',
    'tagCandidates',
    'itemCandidates',
    'relationCandidates',
    'ambiguityReasons',
    'providerMetadata',
  ]);
  if (proposal.schemaVersion !== '1') fail('schemaVersion');

  const memoId = uuid(proposal.memoId, 'memoId');
  const memoRevision = integer(proposal.memoRevision, 'memoRevision', 1);
  if (expectedIdentity?.memoId !== undefined && memoId !== expectedIdentity.memoId) {
    fail('memoId');
  }
  if (
    expectedIdentity?.memoRevision !== undefined &&
    memoRevision !== expectedIdentity.memoRevision
  ) {
    fail('memoRevision');
  }

  const suggestedTitle = closedRecord(proposal.suggestedTitle, 'suggestedTitle', [
    'value',
    'confidence',
    'needsConfirmation',
  ]);
  const typeCandidates = array(proposal.typeCandidates, 'typeCandidates', 5, 1).map(
    (value, index) => {
      const field = `typeCandidates[${index}]`;
      const candidate = closedRecord(value, field, ['value', 'score']);
      if (!isSemanticType(candidate.value)) fail(`${field}.value`);
      return { value: candidate.value, score: score(candidate.score, `${field}.score`) };
    },
  );

  return {
    schemaVersion: '1',
    memoId,
    memoRevision,
    suggestedTitle: {
      value: text(suggestedTitle.value, 'suggestedTitle.value', 200),
      confidence: score(suggestedTitle.confidence, 'suggestedTitle.confidence'),
      needsConfirmation: boolean(
        suggestedTitle.needsConfirmation,
        'suggestedTitle.needsConfirmation',
      ),
    },
    typeCandidates,
    dateCandidates: array(proposal.dateCandidates, 'dateCandidates', 5).map(
      decodeDateCandidate,
    ),
    tagCandidates: array(proposal.tagCandidates, 'tagCandidates', 10).map(
      decodeTagCandidate,
    ),
    itemCandidates: array(proposal.itemCandidates, 'itemCandidates', 3).map(
      decodeItemCandidate,
    ),
    relationCandidates: array(proposal.relationCandidates, 'relationCandidates', 10).map(
      decodeRelationCandidate,
    ),
    ambiguityReasons: ambiguityReasonArray(proposal.ambiguityReasons, 'ambiguityReasons'),
    providerMetadata: record(proposal.providerMetadata, 'providerMetadata'),
  };
}

export function decodeProposalSummaries(
  value: unknown,
  expectedStatus?: ProposalSummary['status'],
): ProposalSummary[] {
  return array(value, 'proposalSummaries', 50).map((entry, index) => {
    const field = `proposalSummaries[${index}]`;
    const summary = closedRecord(entry, field, ['proposalId', 'status', 'createdAt', 'proposal']);
    if (
      typeof summary.status !== 'string' ||
      !RECOVERY_STATUSES.has(summary.status as ProposalSummary['status']) ||
      (expectedStatus !== undefined && summary.status !== expectedStatus)
    ) {
      fail(`${field}.status`);
    }
    const createdAt = text(summary.createdAt, `${field}.createdAt`, 40);
    if (!isValidOffsetDateTime(createdAt)) fail(`${field}.createdAt`);
    return {
      proposalId: uuid(summary.proposalId, `${field}.proposalId`),
      status: summary.status as ProposalSummary['status'],
      createdAt,
      proposal: decodeProposal(summary.proposal),
    };
  });
}
