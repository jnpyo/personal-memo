import { compareMemoSearchItems } from '../../shared/api/searchDecoder';
import type {
  MemoSearchItem,
  MemoSearchPage,
  MemoSearchRequest,
  MemoSearchTaskState,
  MemoStatus,
} from '../../shared/api/types';
import { isValidIsoDate } from '../../shared/validation/dateTime';
import { isWellFormedUtf16 } from '../../shared/validation/text';

export const MEMO_SEARCH_PAGE_LIMIT = 20;
export const MEMO_SEARCH_MAX_PAGES = 5;
export const MEMO_SEARCH_MAX_ITEMS = MEMO_SEARCH_PAGE_LIMIT * MEMO_SEARCH_MAX_PAGES;
const FIRST_PAGE_CURSOR = '__FIRST_PAGE__';

export type MemoSearchDraft = {
  query: string;
  lifecycleStatus: MemoStatus;
  taskState: MemoSearchTaskState | '';
  overdueOnly: boolean;
  revisedFromDate: string;
  revisedThroughDate: string;
};

export type MemoSearchCollection = {
  scopeKey: string;
  items: MemoSearchItem[];
  nextCursor: string | null;
  truncated: boolean;
  pagesLoaded: number;
  browserTruncated: boolean;
  loadedCursors: string[];
};

export class MemoSearchValidationError extends Error {
  constructor(readonly field: keyof MemoSearchDraft, message: string) {
    super(message);
    this.name = 'MemoSearchValidationError';
  }
}

export class MemoSearchMergeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MemoSearchMergeError';
  }
}

export function emptyMemoSearchDraft(): MemoSearchDraft {
  return {
    query: '',
    lifecycleStatus: 'ACTIVE',
    taskState: '',
    overdueOnly: false,
    revisedFromDate: '',
    revisedThroughDate: '',
  };
}

function localMidnight(date: string, addDays: number): string {
  if (!isValidIsoDate(date)) {
    throw new MemoSearchValidationError(
      addDays === 0 ? 'revisedFromDate' : 'revisedThroughDate',
      '올바른 수정일을 입력해 주세요.',
    );
  }
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  const local = new Date(year, month - 1, day + addDays, 0, 0, 0, 0);
  if (Number.isNaN(local.getTime())) {
    throw new MemoSearchValidationError(
      addDays === 0 ? 'revisedFromDate' : 'revisedThroughDate',
      '이 기기 시간대에서 사용할 수 없는 수정일입니다.',
    );
  }
  if (
    addDays === 0 &&
    (local.getFullYear() !== year || local.getMonth() !== month - 1 || local.getDate() !== day)
  ) {
    throw new MemoSearchValidationError(
      'revisedFromDate',
      '이 기기 시간대에서 사용할 수 없는 수정일입니다.',
    );
  }
  return local.toISOString();
}

export function buildMemoSearchRequest(draft: MemoSearchDraft): MemoSearchRequest {
  const query = draft.query.trim();
  if (query.length === 0) {
    throw new MemoSearchValidationError('query', '검색어를 입력해 주세요.');
  }
  if (query.length > 200) {
    throw new MemoSearchValidationError('query', '검색어는 200자 이하여야 합니다.');
  }
  if (!isWellFormedUtf16(query)) {
    throw new MemoSearchValidationError('query', '검색어에 올바르지 않은 문자가 포함되어 있습니다.');
  }
  const normalizedQuery = query.normalize('NFKC').toLowerCase();
  if (normalizedQuery.length > 200 || Array.from(normalizedQuery).length > 200) {
    throw new MemoSearchValidationError('query', '정규화한 검색어는 200자 이하여야 합니다.');
  }
  if (draft.overdueOnly && draft.taskState !== '' && draft.taskState !== 'TODO') {
    throw new MemoSearchValidationError(
      'overdueOnly',
      '기한 지난 미완료 검색은 TODO 또는 전체 작업 상태에서만 사용할 수 있습니다.',
    );
  }

  const revisedFrom = draft.revisedFromDate
    ? localMidnight(draft.revisedFromDate, 0)
    : undefined;
  const revisedBefore = draft.revisedThroughDate
    ? localMidnight(draft.revisedThroughDate, 1)
    : undefined;
  if (revisedFrom && revisedBefore && Date.parse(revisedFrom) >= Date.parse(revisedBefore)) {
    throw new MemoSearchValidationError(
      'revisedThroughDate',
      '수정일 끝은 시작과 같거나 뒤여야 합니다.',
    );
  }

  return {
    query,
    lifecycleStatus: draft.lifecycleStatus,
    ...(draft.taskState ? { taskState: draft.taskState } : {}),
    ...(draft.overdueOnly ? { overdue: true } : {}),
    ...(revisedFrom ? { revisedFrom } : {}),
    ...(revisedBefore ? { revisedBefore } : {}),
    limit: MEMO_SEARCH_PAGE_LIMIT,
  };
}

export function memoSearchScopeKey(request: MemoSearchRequest): string {
  return JSON.stringify({
    query: request.query,
    lifecycleStatus: request.lifecycleStatus,
    taskState: request.taskState ?? null,
    overdue: request.overdue ?? null,
    revisedFrom: request.revisedFrom ?? null,
    revisedBefore: request.revisedBefore ?? null,
    limit: request.limit,
  });
}

function cursorIdentity(cursor: string | undefined): string {
  return cursor ?? FIRST_PAGE_CURSOR;
}

export function withoutMemoSearchCursor(request: MemoSearchRequest): MemoSearchRequest {
  const { cursor: _cursor, ...firstPage } = request;
  void _cursor;
  return firstPage;
}

export function memoSearchRetryRequest(
  request: MemoSearchRequest,
  restartFromFirstPage: boolean,
): MemoSearchRequest {
  return restartFromFirstPage ? withoutMemoSearchCursor(request) : { ...request };
}

export function mergeMemoSearchPage(
  current: MemoSearchCollection | null,
  page: MemoSearchPage,
  request: MemoSearchRequest,
): MemoSearchCollection {
  const requestedCursor = request.cursor;
  const scopeKey = memoSearchScopeKey(request);
  if (!current && requestedCursor !== undefined) {
    throw new MemoSearchMergeError('The first memo search page cannot use a cursor.');
  }
  if (current && current.scopeKey !== scopeKey) {
    throw new MemoSearchMergeError('The memo search scope changed between pages.');
  }
  if (current && current.nextCursor !== requestedCursor) {
    throw new MemoSearchMergeError('The memo search cursor changed before merge.');
  }

  const loadedCursor = cursorIdentity(requestedCursor);
  if (current?.loadedCursors.includes(loadedCursor)) {
    throw new MemoSearchMergeError('The memo search page cursor was already loaded.');
  }
  const loadedCursors = [...(current?.loadedCursors ?? []), loadedCursor];
  if (
    page.nextCursor !== null &&
    (page.nextCursor === requestedCursor || loadedCursors.includes(page.nextCursor))
  ) {
    throw new MemoSearchMergeError('The memo search continuation cursor repeated.');
  }

  const currentIds = new Set((current?.items ?? []).map((item) => item.memoId));
  if (page.items.some((item) => currentIds.has(item.memoId))) {
    throw new MemoSearchMergeError('The memo search page repeated a memo.');
  }
  const previousLast = current?.items.at(-1);
  const nextFirst = page.items[0];
  if (previousLast && nextFirst && compareMemoSearchItems(previousLast, nextFirst) >= 0) {
    throw new MemoSearchMergeError('The memo search order changed between pages.');
  }

  const pagesLoaded = (current?.pagesLoaded ?? 0) + 1;
  const allItems = [...(current?.items ?? []), ...page.items];
  const items = allItems.slice(0, MEMO_SEARCH_MAX_ITEMS);
  const browserTruncated = page.nextCursor !== null && (
    pagesLoaded >= MEMO_SEARCH_MAX_PAGES || allItems.length >= MEMO_SEARCH_MAX_ITEMS
  );

  return {
    scopeKey,
    items,
    nextCursor: browserTruncated ? null : page.nextCursor,
    truncated: page.truncated,
    pagesLoaded,
    browserTruncated: Boolean(current?.browserTruncated) || browserTruncated,
    loadedCursors,
  };
}
