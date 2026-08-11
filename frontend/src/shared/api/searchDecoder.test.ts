import { describe, expect, it } from 'vitest';
import type { MemoSearchRequest } from './types';
import { decodeMemoSearchPage, MemoSearchContractError } from './searchDecoder';

const request: MemoSearchRequest = {
  query: '운영체제',
  lifecycleStatus: 'ACTIVE',
  limit: 20,
};

function item(overrides: Record<string, unknown> = {}) {
  return {
    memoId: '11111111-1111-4111-8111-111111111111',
    currentRevision: 2,
    canonicalRevision: 1,
    title: '운영체제 과제',
    preview: '운영체제 과제 원문',
    lifecycleStatus: 'ACTIVE',
    canonicalTags: [
      { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: '운영체제' },
    ],
    taskState: 'TODO',
    overdue: true,
    pinned: false,
    revisedAt: '2026-08-11T03:00:00Z',
    matchedFields: ['TITLE', 'BODY', 'TAG'],
    ...overrides,
  };
}

function page(overrides: Record<string, unknown> = {}) {
  return {
    items: [item()],
    nextCursor: null,
    truncated: false,
    ...overrides,
  };
}

function rejects(payload: unknown, expectedRequest = request) {
  expect(() => decodeMemoSearchPage(payload, expectedRequest)).toThrow(MemoSearchContractError);
}

describe('memo search response decoder', () => {
  it('decodes the exact bounded response contract', () => {
    expect(decodeMemoSearchPage(page(), request)).toEqual(page());
    expect(decodeMemoSearchPage(page({
      items: [item({
        canonicalRevision: null,
        title: null,
        canonicalTags: [],
        taskState: 'NONE',
        overdue: false,
        matchedFields: ['BODY'],
      })],
    }), request).items[0]).toMatchObject({ title: null, taskState: 'NONE' });
  });

  it('rejects missing or additional fields at every level', () => {
    const missingRoot = page();
    delete (missingRoot as Record<string, unknown>).truncated;
    rejects(missingRoot);
    rejects({ ...page(), extra: true });

    const missingItem = item();
    delete (missingItem as Record<string, unknown>).preview;
    rejects(page({ items: [missingItem] }));
    rejects(page({ items: [item({ extra: true })] }));
    rejects(page({ items: [item({ canonicalTags: [{ ...item().canonicalTags[0], extra: true }] })] }));
  });

  it('enforces identifiers, revisions, enums, booleans and instants', () => {
    rejects(page({ items: [item({ memoId: 'MEMO-1' })] }));
    rejects(page({ items: [item({ currentRevision: 0 })] }));
    rejects(page({ items: [item({ canonicalRevision: 3 })] }));
    rejects(page({ items: [item({ canonicalRevision: null })] }));
    rejects(page({ items: [item({ title: null })] }));
    rejects(page({ items: [item({ lifecycleStatus: 'DELETED' })] }));
    rejects(page({ items: [item({ taskState: null })] }));
    rejects(page({ items: [item({ overdue: 'true' })] }));
    rejects(page({ items: [item({ taskState: 'DONE', overdue: true })] }));
    rejects(page({ items: [item({ pinned: 0 })] }));
    rejects(page({ items: [item({ revisedAt: '2026-02-30T00:00:00Z' })] }));
  });

  it('uses UTF-16 title bounds and Unicode code-point preview bounds', () => {
    rejects(page({ items: [item({ title: '😀'.repeat(101) })] }));
    expect(() => decodeMemoSearchPage(
      page({ items: [item({ preview: '😀'.repeat(240) })] }),
      request,
    )).not.toThrow();
    rejects(page({ items: [item({ preview: '😀'.repeat(241) })] }));
    rejects(page({ items: [item({ preview: '\ud800' })] }));
    rejects(page({ items: [item({ title: '\udc00' })] }));
    rejects(page({ items: [item({ canonicalTags: [{
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: '가'.repeat(101),
    }] })] }));
    expect(() => decodeMemoSearchPage(page({ items: [item({ canonicalTags: [{
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: '😀'.repeat(100),
    }] })] }), request)).not.toThrow();
    rejects(page({ items: [item({ canonicalTags: [{
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: '😀'.repeat(101),
    }] })] }));
    rejects(page({ items: [item({ canonicalTags: [{
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      name: '',
    }] })] }));
  });

  it('rejects oversized or duplicate collections and invalid match ordering', () => {
    rejects(page({ items: Array.from({ length: 21 }, (_, index) => item({
      memoId: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
    })) }));
    rejects(page({ items: [item(), item()] }));
    rejects(page({ items: [item({ canonicalTags: [item().canonicalTags[0], item().canonicalTags[0]] })] }));
    rejects(page({ items: [item({ canonicalTags: Array.from({ length: 9 }, (_, index) => ({
      id: `aaaaaaaa-aaaa-4aaa-8aaa-${String(index).padStart(12, '0')}`,
      name: `태그${index}`,
    })) })] }));
    rejects(page({ items: [item({ matchedFields: [] })] }));
    rejects(page({ items: [item({ matchedFields: ['BODY', 'TITLE'] })] }));
    rejects(page({ items: [item({ matchedFields: ['BODY', 'BODY'] })] }));
    rejects(page({ items: [item({ matchedFields: ['UNKNOWN'] })] }));
    rejects(page({ items: [item({ canonicalTags: [], matchedFields: ['TAG'] })] }));
    rejects(page({ items: [item({ canonicalTags: [], matchedFields: ['ALIAS'] })] }));
  });

  it('requires cursor/truncated consistency and a bounded opaque cursor', () => {
    rejects(page({ nextCursor: 'next', truncated: false }));
    rejects(page({ nextCursor: null, truncated: true }));
    rejects(page({ nextCursor: 'bad+cursor', truncated: true }));
    rejects(page({ nextCursor: 'a'.repeat(1025), truncated: true }));
    expect(decodeMemoSearchPage(page({ nextCursor: 'abc_DEF-123', truncated: true }), request))
      .toMatchObject({ nextCursor: 'abc_DEF-123', truncated: true });
    rejects(page(), { ...request, limit: 0 });
    rejects(page(), { ...request, limit: 51 });
  });

  it('requires deterministic revisedAt-desc and memoId-asc ordering', () => {
    const earlier = item({
      memoId: '22222222-2222-4222-8222-222222222222',
      revisedAt: '2026-08-10T03:00:00Z',
    });
    expect(() => decodeMemoSearchPage(page({ items: [item(), earlier] }), request)).not.toThrow();
    rejects(page({ items: [earlier, item()] }));

    const lowerId = item({ memoId: '00000000-0000-4000-8000-000000000000' });
    rejects(page({ items: [item(), lowerId] }));
    expect(() => decodeMemoSearchPage(page({ items: [lowerId, item()] }), request)).not.toThrow();

    const laterMicrosecond = item({
      memoId: '33333333-3333-4333-8333-333333333333',
      revisedAt: '2026-08-11T03:00:00.000002Z',
    });
    const earlierMicrosecond = item({
      memoId: '44444444-4444-4444-8444-444444444444',
      revisedAt: '2026-08-11T03:00:00.000001Z',
    });
    expect(() => decodeMemoSearchPage(
      page({ items: [laterMicrosecond, earlierMicrosecond] }),
      request,
    )).not.toThrow();
    rejects(page({ items: [earlierMicrosecond, laterMicrosecond] }));
  });

  it('fails closed when an item escapes the submitted filters', () => {
    rejects(page({ items: [item({ lifecycleStatus: 'TRASHED' })] }));
    rejects(page({ items: [item({ taskState: 'DONE', overdue: false })] }), {
      ...request,
      taskState: 'TODO',
    });
    expect(() => decodeMemoSearchPage(page({
      items: [item({ taskState: 'NONE', overdue: false })],
    }), {
      ...request,
      taskState: 'NONE',
    })).not.toThrow();
    rejects(page({ items: [item({ taskState: 'TODO', overdue: false })] }), {
      ...request,
      taskState: 'NONE',
    });
    rejects(page({ items: [item({ overdue: false })] }), { ...request, overdue: true });
    rejects(page({ items: [item({ revisedAt: '2026-08-10T23:59:59Z' })] }), {
      ...request,
      revisedFrom: '2026-08-11T00:00:00Z',
    });
    rejects(page({ items: [item({ revisedAt: '2026-08-12T00:00:00Z' })] }), {
      ...request,
      revisedBefore: '2026-08-12T00:00:00Z',
    });
  });
});
