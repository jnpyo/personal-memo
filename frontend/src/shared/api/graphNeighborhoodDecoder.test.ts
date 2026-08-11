import { describe, expect, it } from 'vitest';
import {
  decodeGraphNeighborhoodPage,
  GraphNeighborhoodContractError,
} from './graphNeighborhoodDecoder';

function page() {
  return {
    center: {
      id: 'tag:10000000-0000-0000-0000-000000000001',
      kind: 'TAG',
      label: '운영체제',
      pinned: false,
      overdue: false,
      memoType: null,
      taskState: null,
    },
    neighbors: [
      {
        id: 'memo:20000000-0000-0000-0000-000000000001',
        kind: 'MEMO',
        label: '오래된 xv6 메모',
        pinned: false,
        memoType: 'INFORMATION',
        taskState: 'NONE',
        overdue: false,
      },
    ],
    edges: [
      {
        id: 'memo-tag:20000000-0000-0000-0000-000000000001:10000000-0000-0000-0000-000000000001',
        source: 'memo:20000000-0000-0000-0000-000000000001',
        target: 'tag:10000000-0000-0000-0000-000000000001',
        kind: 'MEMO_TAG',
      },
    ],
    truncated: true,
    nextCursor: 'opaque-next-page',
  };
}

describe('graph neighborhood response decoder', () => {
  it('accepts the bounded full-corpus page contract', () => {
    expect(decodeGraphNeighborhoodPage(page(), {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toEqual(page());
  });

  it('accepts canonical lowercase PostgreSQL UUID text with zero version and variant bits', () => {
    expect(decodeGraphNeighborhoodPage(page(), {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    }).center.id).toBe('tag:10000000-0000-0000-0000-000000000001');
  });

  it('rejects uppercase and otherwise noncanonical center UUID text', () => {
    expect(() => decodeGraphNeighborhoodPage(page(), {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-00000000000A',
    })).toThrowError(/expectedCenter/);
  });

  it('rejects an unsupported node kind before it reaches drawer state', () => {
    const invalid = page();
    invalid.neighbors[0] = { ...invalid.neighbors[0], kind: 'SYSTEM' } as never;

    expect(() => decodeGraphNeighborhoodPage(invalid, {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toThrowError(
      GraphNeighborhoodContractError,
    );
    expect(() => decodeGraphNeighborhoodPage(invalid, {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toThrowError(/neighbors\[0\]\.kind/);
  });

  it('rejects a page larger than the browser/server page bound', () => {
    const invalid = page();
    invalid.neighbors = Array.from({ length: 21 }, (_, index) => ({
      ...invalid.neighbors[0],
      id: `memo:${index}`,
    }));

    expect(() => decodeGraphNeighborhoodPage(invalid, {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toThrowError(/neighbors/);
  });

  it('requires the explicit nullable cursor field', () => {
    const invalid = { ...page(), nextCursor: undefined };

    expect(() => decodeGraphNeighborhoodPage(invalid, {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toThrowError(/nextCursor/);
  });

  it.each([
    ['unknown root field', () => ({ ...page(), privateBody: 'must not pass' })],
    ['unknown node field', () => ({
      ...page(),
      center: { ...page().center, aliases: [] },
    })],
    ['unknown edge field', () => ({
      ...page(),
      edges: [{ ...page().edges[0], weight: 1 }],
    })],
    ['center identity mismatch', () => ({
      ...page(),
      center: { ...page().center, id: 'tag:10000000-0000-0000-0000-000000000099' },
    })],
    ['same-kind neighbor', () => ({
      ...page(),
      neighbors: [{ ...page().center, id: 'tag:10000000-0000-0000-0000-000000000002' }],
      edges: [{
        ...page().edges[0],
        source: 'tag:10000000-0000-0000-0000-000000000002',
      }],
    })],
    ['duplicate neighbor identity', () => ({
      ...page(),
      neighbors: [page().neighbors[0], page().neighbors[0]],
      edges: [page().edges[0], { ...page().edges[0], id: 'memo-tag:2' }],
    })],
    ['dangling edge', () => ({
      ...page(),
      edges: [{
        ...page().edges[0],
        source: 'memo:20000000-0000-0000-0000-000000000099',
      }],
    })],
    ['corrupt edge identity', () => ({
      ...page(),
      edges: [{ ...page().edges[0], id: 'memo-tag:corrupt' }],
    })],
    ['duplicate edge identity', () => ({
      ...page(),
      neighbors: [
        page().neighbors[0],
        {
          ...page().neighbors[0],
          id: 'memo:20000000-0000-0000-0000-000000000002',
        },
      ],
      edges: [
        page().edges[0],
        {
          ...page().edges[0],
          source: 'memo:20000000-0000-0000-0000-000000000002',
        },
      ],
    })],
    ['tag metadata expansion', () => ({
      ...page(),
      center: { ...page().center, pinned: true },
    })],
    ['memo metadata omission', () => ({
      ...page(),
      neighbors: [{ ...page().neighbors[0], taskState: null }],
    })],
    ['uppercase neighbor UUID', () => ({
      ...page(),
      neighbors: [{
        ...page().neighbors[0],
        id: 'memo:20000000-0000-0000-0000-00000000000A',
      }],
    })],
    ['oversized cursor', () => ({
      ...page(),
      nextCursor: 'x'.repeat(1_025),
    })],
    ['non URL-safe cursor', () => ({
      ...page(),
      nextCursor: 'opaque+cursor=',
    })],
    ['oversized tag label', () => ({
      ...page(),
      center: {
        ...page().center,
        label: 'x'.repeat(101),
      },
    })],
    ['cursor coherence mismatch', () => ({
      ...page(),
      truncated: false,
    })],
  ])('rejects poisoned relational data: %s', (_label, makeInvalid) => {
    expect(() => decodeGraphNeighborhoodPage(makeInvalid(), {
      kind: 'TAG',
      entityId: '10000000-0000-0000-0000-000000000001',
    })).toThrowError(GraphNeighborhoodContractError);
  });
});
