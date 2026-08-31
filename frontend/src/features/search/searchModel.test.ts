import { describe, expect, it } from 'vitest';
import type { MemoSearchItem, MemoSearchPage } from '../../shared/api/types';
import {
  buildMemoSearchRequest,
  emptyMemoSearchDraft,
  MEMO_SEARCH_MAX_ITEMS,
  MemoSearchMergeError,
  MemoSearchValidationError,
  memoSearchRetryRequest,
  mergeMemoSearchPage,
} from './searchModel';

function searchItem(index: number, revisedAt = `2026-08-11T03:${String(59 - index).padStart(2, '0')}:00Z`): MemoSearchItem {
  return {
    memoId: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
    currentRevision: 1,
    canonicalRevision: null,
    title: null,
    preview: `memo ${index}`,
    lifecycleStatus: 'ACTIVE',
    canonicalTags: [],
    taskState: 'NONE',
    overdue: false,
    pinned: false,
    revisedAt,
    matchedFields: ['BODY'],
  };
}

function searchPage(items: MemoSearchItem[], nextCursor: string | null): MemoSearchPage {
  return { items, nextCursor, truncated: nextCursor !== null };
}

describe('memo search request model', () => {
  it('builds an explicit first-page body and omits inactive optional filters', () => {
    expect(buildMemoSearchRequest({ ...emptyMemoSearchDraft(), query: '  운영체제  ' })).toEqual({
      query: '운영체제',
      lifecycleStatus: 'ACTIVE',
      limit: 20,
    });
  });

  it('enforces UTF-16 query bounds and overdue/task compatibility', () => {
    expect(() => buildMemoSearchRequest(emptyMemoSearchDraft())).toThrow(MemoSearchValidationError);
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '😀'.repeat(101),
    })).toThrow(MemoSearchValidationError);
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '\ud800',
    })).toThrow(MemoSearchValidationError);
    expect(buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '😀',
    }).query).toBe('😀');
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '\ufdfa'.repeat(12),
    })).toThrow(MemoSearchValidationError);
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '일정',
      taskState: 'DONE',
      overdueOnly: true,
    })).toThrow(MemoSearchValidationError);
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '일정',
      taskState: 'NONE',
      overdueOnly: true,
    })).toThrow(MemoSearchValidationError);
    expect(buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '작업 없는 기록',
      taskState: 'NONE',
    })).toEqual({
      query: '작업 없는 기록',
      lifecycleStatus: 'ACTIVE',
      taskState: 'NONE',
      limit: 20,
    });
  });

  it('turns inclusive device-local dates into half-open ISO instants', () => {
    const result = buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '일정',
      taskState: 'TODO',
      overdueOnly: true,
      revisedFromDate: '2026-08-10',
      revisedThroughDate: '2026-08-11',
    });
    const expectedStart = new Date(2026, 7, 10, 0, 0, 0, 0).toISOString();
    const expectedBefore = new Date(2026, 7, 12, 0, 0, 0, 0).toISOString();
    expect(result).toMatchObject({
      taskState: 'TODO',
      overdue: true,
      revisedFrom: expectedStart,
      revisedBefore: expectedBefore,
    });
    expect(() => buildMemoSearchRequest({
      ...emptyMemoSearchDraft(),
      query: '일정',
      revisedFromDate: '2026-08-12',
      revisedThroughDate: '2026-08-11',
    })).toThrow(MemoSearchValidationError);
  });
});

describe('memo search pagination model', () => {
  const request = buildMemoSearchRequest({ ...emptyMemoSearchDraft(), query: 'memo' });

  it('merges ordered pages and keeps the captured cursor retry exact', () => {
    const first = mergeMemoSearchPage(null, searchPage([searchItem(0)], 'cursor-1'), request);
    const secondRequest = { ...request, cursor: first.nextCursor! };
    const second = mergeMemoSearchPage(
      first,
      searchPage([searchItem(1, '2026-08-11T02:00:00Z')], null),
      secondRequest,
    );
    expect(second.items.map((item) => item.preview)).toEqual(['memo 0', 'memo 1']);
    expect(second.pagesLoaded).toBe(2);
    expect(second.nextCursor).toBeNull();
    expect(memoSearchRetryRequest(secondRequest, false)).toEqual(secondRequest);
    expect(memoSearchRetryRequest(secondRequest, true)).toEqual(request);
  });

  it('fails closed on wrong, repeated, duplicate or reordered continuations', () => {
    const first = mergeMemoSearchPage(null, searchPage([searchItem(0)], 'cursor-1'), request);
    expect(() => mergeMemoSearchPage(first, searchPage([], null), {
      ...request,
      cursor: 'wrong-cursor',
    })).toThrow(MemoSearchMergeError);
    expect(() => mergeMemoSearchPage(first, searchPage([], 'cursor-1'), {
      ...request,
      cursor: 'cursor-1',
    })).toThrow(MemoSearchMergeError);
    expect(() => mergeMemoSearchPage(first, searchPage([searchItem(0)], null), {
      ...request,
      cursor: 'cursor-1',
    })).toThrow(MemoSearchMergeError);
    expect(() => mergeMemoSearchPage(first, searchPage([
      searchItem(2, '2026-08-12T00:00:00Z'),
    ], null), {
      ...request,
      cursor: 'cursor-1',
    })).toThrow(MemoSearchMergeError);
  });

  it('preserves sub-millisecond ordering across page boundaries', () => {
    const first = mergeMemoSearchPage(null, searchPage([
      searchItem(0, '2026-08-11T03:00:00.000002Z'),
    ], 'micro-cursor'), request);
    expect(() => mergeMemoSearchPage(first, searchPage([
      searchItem(1, '2026-08-11T03:00:00.000001Z'),
    ], null), { ...request, cursor: 'micro-cursor' })).not.toThrow();
    expect(() => mergeMemoSearchPage(first, searchPage([
      searchItem(2, '2026-08-11T03:00:00.000003Z'),
    ], null), { ...request, cursor: 'micro-cursor' })).toThrow(MemoSearchMergeError);
  });

  it('caps one browser traversal at five pages and 100 results', () => {
    let collection = null;
    let nextIndex = 0;
    for (let pageIndex = 0; pageIndex < 5; pageIndex += 1) {
      const cursor = pageIndex === 0 ? undefined : `cursor-${pageIndex}`;
      const items = Array.from({ length: 20 }, () => {
        const index = nextIndex;
        nextIndex += 1;
        return searchItem(index, new Date(Date.UTC(2026, 7, 11, 3, 0, -index)).toISOString());
      });
      collection = mergeMemoSearchPage(
        collection,
        searchPage(items, `cursor-${pageIndex + 1}`),
        { ...request, ...(cursor ? { cursor } : {}) },
      );
    }
    expect(collection).not.toBeNull();
    expect(collection!.items).toHaveLength(MEMO_SEARCH_MAX_ITEMS);
    expect(collection!.browserTruncated).toBe(true);
    expect(collection!.nextCursor).toBeNull();
  });
});
