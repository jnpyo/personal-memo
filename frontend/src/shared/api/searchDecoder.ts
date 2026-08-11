import { compareOffsetDateTimes, isValidOffsetDateTime } from '../validation/dateTime';
import { isWellFormedUtf16 } from '../validation/text';
import type {
  MemoSearchCanonicalTag,
  MemoSearchItem,
  MemoSearchMatchedField,
  MemoSearchPage,
  MemoSearchRequest,
  MemoStatus,
  TaskStatus,
} from './types';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CURSOR_PATTERN = /^[A-Za-z0-9_-]{1,1024}$/;
const SERVER_PAGE_ITEM_LIMIT = 50;
const TAG_LIMIT = 8;
const ROOT_FIELDS = ['items', 'nextCursor', 'truncated'] as const;
const ITEM_FIELDS = [
  'memoId',
  'currentRevision',
  'canonicalRevision',
  'title',
  'preview',
  'lifecycleStatus',
  'canonicalTags',
  'taskState',
  'overdue',
  'pinned',
  'revisedAt',
  'matchedFields',
] as const;
const TAG_FIELDS = ['id', 'name'] as const;
const LIFECYCLE_STATUSES = new Set<MemoStatus>(['ACTIVE', 'TRASHED']);
const TASK_STATES = new Set<TaskStatus | 'NONE'>(['TODO', 'DONE', 'CANCELLED', 'NONE']);
const MATCHED_FIELD_ORDER: readonly MemoSearchMatchedField[] = ['TITLE', 'BODY', 'TAG', 'ALIAS'];
const MATCHED_FIELDS = new Set(MATCHED_FIELD_ORDER);

export class MemoSearchContractError extends Error {
  constructor(readonly field: string) {
    super(`Invalid memo search response field: ${field}`);
    this.name = 'MemoSearchContractError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireExactFields(
  value: Record<string, unknown>,
  fields: readonly string[],
  field: string,
): void {
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (
    actual.length !== expected.length ||
    actual.some((key, index) => key !== expected[index])
  ) {
    throw new MemoSearchContractError(field);
  }
}

function requireUuid(value: unknown, field: string): string {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new MemoSearchContractError(field);
  }
  return value;
}

function requirePositiveInteger(value: unknown, field: string): number {
  if (!Number.isInteger(value) || (value as number) < 1) {
    throw new MemoSearchContractError(field);
  }
  return value as number;
}

function requireBoundedText(
  value: unknown,
  field: string,
  maxLength: number,
  allowEmpty = false,
): string {
  if (
    typeof value !== 'string' ||
    !isWellFormedUtf16(value) ||
    (!allowEmpty && value.length === 0) ||
    value.length > maxLength
  ) {
    throw new MemoSearchContractError(field);
  }
  return value;
}

function requireCodePointBoundedText(
  value: unknown,
  field: string,
  maxLength: number,
): string {
  if (
    typeof value !== 'string' ||
    !isWellFormedUtf16(value) ||
    Array.from(value).length > maxLength
  ) {
    throw new MemoSearchContractError(field);
  }
  return value;
}

function decodeCanonicalTag(value: unknown, field: string): MemoSearchCanonicalTag {
  if (!isRecord(value)) throw new MemoSearchContractError(field);
  requireExactFields(value, TAG_FIELDS, field);
  const name = requireCodePointBoundedText(value.name, `${field}.name`, 100);
  if (name.length === 0) throw new MemoSearchContractError(`${field}.name`);
  return {
    id: requireUuid(value.id, `${field}.id`),
    name,
  };
}

function decodeMatchedFields(value: unknown, field: string): MemoSearchMatchedField[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > MATCHED_FIELD_ORDER.length) {
    throw new MemoSearchContractError(field);
  }

  const result = value.map((entry, index) => {
    if (typeof entry !== 'string' || !MATCHED_FIELDS.has(entry as MemoSearchMatchedField)) {
      throw new MemoSearchContractError(`${field}[${index}]`);
    }
    return entry as MemoSearchMatchedField;
  });
  const unique = new Set(result);
  if (unique.size !== result.length) throw new MemoSearchContractError(field);
  const indices = result.map((entry) => MATCHED_FIELD_ORDER.indexOf(entry));
  if (indices.some((entry, index) => index > 0 && entry <= indices[index - 1]!)) {
    throw new MemoSearchContractError(field);
  }
  return result;
}

function decodeItem(value: unknown, field: string): MemoSearchItem {
  if (!isRecord(value)) throw new MemoSearchContractError(field);
  requireExactFields(value, ITEM_FIELDS, field);

  const currentRevision = requirePositiveInteger(value.currentRevision, `${field}.currentRevision`);
  let canonicalRevision: number | null;
  if (value.canonicalRevision === null) {
    canonicalRevision = null;
  } else {
    canonicalRevision = requirePositiveInteger(value.canonicalRevision, `${field}.canonicalRevision`);
    if (canonicalRevision > currentRevision) {
      throw new MemoSearchContractError(`${field}.canonicalRevision`);
    }
  }

  let title: string | null;
  if (value.title === null) {
    title = null;
  } else {
    title = requireBoundedText(value.title, `${field}.title`, 200);
  }
  if ((title === null) !== (canonicalRevision === null)) {
    throw new MemoSearchContractError(`${field}.title`);
  }

  if (!LIFECYCLE_STATUSES.has(value.lifecycleStatus as MemoStatus)) {
    throw new MemoSearchContractError(`${field}.lifecycleStatus`);
  }
  if (!TASK_STATES.has(value.taskState as TaskStatus | 'NONE')) {
    throw new MemoSearchContractError(`${field}.taskState`);
  }
  if (typeof value.overdue !== 'boolean') {
    throw new MemoSearchContractError(`${field}.overdue`);
  }
  if (value.overdue && value.taskState !== 'TODO') {
    throw new MemoSearchContractError(`${field}.overdue`);
  }
  if (typeof value.pinned !== 'boolean') {
    throw new MemoSearchContractError(`${field}.pinned`);
  }
  if (typeof value.revisedAt !== 'string' || !isValidOffsetDateTime(value.revisedAt)) {
    throw new MemoSearchContractError(`${field}.revisedAt`);
  }
  if (!Array.isArray(value.canonicalTags) || value.canonicalTags.length > TAG_LIMIT) {
    throw new MemoSearchContractError(`${field}.canonicalTags`);
  }
  const canonicalTags = value.canonicalTags.map((tag, index) =>
    decodeCanonicalTag(tag, `${field}.canonicalTags[${index}]`),
  );
  if (new Set(canonicalTags.map((tag) => tag.id)).size !== canonicalTags.length) {
    throw new MemoSearchContractError(`${field}.canonicalTags`);
  }

  const matchedFields = decodeMatchedFields(value.matchedFields, `${field}.matchedFields`);
  if (
    (matchedFields.includes('TAG') || matchedFields.includes('ALIAS')) &&
    canonicalTags.length === 0
  ) {
    throw new MemoSearchContractError(`${field}.canonicalTags`);
  }

  return {
    memoId: requireUuid(value.memoId, `${field}.memoId`),
    currentRevision,
    canonicalRevision,
    title,
    preview: requireCodePointBoundedText(value.preview, `${field}.preview`, 240),
    lifecycleStatus: value.lifecycleStatus as MemoStatus,
    canonicalTags,
    taskState: value.taskState as TaskStatus | 'NONE',
    overdue: value.overdue,
    pinned: value.pinned,
    revisedAt: value.revisedAt,
    matchedFields,
  };
}

export function compareMemoSearchItems(left: MemoSearchItem, right: MemoSearchItem): number {
  const instantComparison = compareOffsetDateTimes(left.revisedAt, right.revisedAt);
  if (instantComparison !== 0) return -instantComparison;
  return left.memoId < right.memoId ? -1 : left.memoId > right.memoId ? 1 : 0;
}

function validateExpectedScope(item: MemoSearchItem, request: MemoSearchRequest, field: string): void {
  if (item.lifecycleStatus !== request.lifecycleStatus) {
    throw new MemoSearchContractError(`${field}.lifecycleStatus`);
  }
  if (request.taskState && item.taskState !== request.taskState) {
    throw new MemoSearchContractError(`${field}.taskState`);
  }
  if (request.overdue !== undefined && item.overdue !== request.overdue) {
    throw new MemoSearchContractError(`${field}.overdue`);
  }
  if (request.revisedFrom && compareOffsetDateTimes(item.revisedAt, request.revisedFrom) < 0) {
    throw new MemoSearchContractError(`${field}.revisedAt`);
  }
  if (request.revisedBefore && compareOffsetDateTimes(item.revisedAt, request.revisedBefore) >= 0) {
    throw new MemoSearchContractError(`${field}.revisedAt`);
  }
}

export function decodeMemoSearchPage(
  value: unknown,
  expectedRequest: MemoSearchRequest,
): MemoSearchPage {
  if (!isRecord(value)) throw new MemoSearchContractError('root');
  requireExactFields(value, ROOT_FIELDS, 'root');
  if (
    !Number.isInteger(expectedRequest.limit) ||
    expectedRequest.limit < 1 ||
    expectedRequest.limit > SERVER_PAGE_ITEM_LIMIT
  ) {
    throw new MemoSearchContractError('expectedRequest.limit');
  }
  const expectedLimit = expectedRequest.limit;
  if (!Array.isArray(value.items) || value.items.length > expectedLimit) {
    throw new MemoSearchContractError('items');
  }
  if (typeof value.truncated !== 'boolean') {
    throw new MemoSearchContractError('truncated');
  }
  if (value.nextCursor !== null && (
    typeof value.nextCursor !== 'string' || !CURSOR_PATTERN.test(value.nextCursor)
  )) {
    throw new MemoSearchContractError('nextCursor');
  }
  if (value.truncated !== (value.nextCursor !== null)) {
    throw new MemoSearchContractError('truncated');
  }

  const items = value.items.map((item, index) => decodeItem(item, `items[${index}]`));
  const ids = new Set<string>();
  items.forEach((item, index) => {
    if (ids.has(item.memoId)) throw new MemoSearchContractError(`items[${index}].memoId`);
    ids.add(item.memoId);
    validateExpectedScope(item, expectedRequest, `items[${index}]`);
    if (index > 0 && compareMemoSearchItems(items[index - 1]!, item) >= 0) {
      throw new MemoSearchContractError(`items[${index}]`);
    }
  });

  return {
    items,
    nextCursor: value.nextCursor as string | null,
    truncated: value.truncated,
  };
}
