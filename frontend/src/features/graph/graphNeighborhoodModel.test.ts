import { describe, expect, it } from 'vitest';
import type { GraphNeighborhoodPage, GraphNode } from '../../shared/api/types';
import {
  GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS,
  GRAPH_NEIGHBORHOOD_MAX_PAGES,
  graphNeighborhoodRetryRequest,
  GraphNeighborhoodMergeError,
  mergeGraphNeighborhoodPage,
  reconcileGraphNeighborhoodAfterMemoPin,
} from './graphNeighborhoodModel';

const center: GraphNode = {
  id: 'tag:10000000-0000-0000-0000-000000000001',
  kind: 'TAG',
  label: '운영체제',
  memoType: null,
  taskState: null,
  overdue: false,
  pinned: false,
};

function memo(index: number): GraphNode {
  return {
    id: `memo:20000000-0000-0000-0000-${index.toString().padStart(12, '0')}`,
    kind: 'MEMO',
    label: `메모 ${index}`,
    memoType: 'INFORMATION',
    taskState: 'NONE',
    overdue: false,
    pinned: false,
  };
}

function page(
  start: number,
  count: number,
  nextCursor: string | null,
): GraphNeighborhoodPage {
  const neighbors = Array.from({ length: count }, (_, index) => memo(start + index));
  return {
    center,
    neighbors,
    edges: neighbors.map((neighbor) => ({
      id: `memo-tag:${neighbor.id}`,
      source: neighbor.id,
      target: center.id,
      kind: 'MEMO_TAG',
    })),
    truncated: nextCursor !== null,
    nextCursor,
  };
}

describe('bounded graph neighborhood pagination', () => {
  it('appends pages while deduplicating node and edge identities', () => {
    const first = mergeGraphNeighborhoodPage(null, page(1, 2, 'page-2'), null);
    const repeated = page(2, 2, null);
    repeated.edges[0] = first.edges[1];
    const merged = mergeGraphNeighborhoodPage(first, repeated, 'page-2');

    expect(merged.neighbors.map((node) => node.label)).toEqual([
      '메모 1',
      '메모 2',
      '메모 3',
    ]);
    expect(merged.edges).toHaveLength(3);
    expect(merged.pagesLoaded).toBe(2);
    expect(merged.nextCursor).toBeNull();
    expect(merged.browserTruncated).toBe(false);
  });

  it('stops at five pages and one hundred neighbors without keeping a server cursor', () => {
    let collection = mergeGraphNeighborhoodPage(null, page(1, 20, 'page-2'), null);
    for (let pageNumber = 2; pageNumber <= GRAPH_NEIGHBORHOOD_MAX_PAGES; pageNumber += 1) {
      collection = mergeGraphNeighborhoodPage(
        collection,
        page((pageNumber - 1) * 20 + 1, 20, `page-${pageNumber + 1}`),
        `page-${pageNumber}`,
      );
    }

    expect(collection.pagesLoaded).toBe(GRAPH_NEIGHBORHOOD_MAX_PAGES);
    expect(collection.neighbors).toHaveLength(GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS);
    expect(collection.nextCursor).toBeNull();
    expect(collection.browserTruncated).toBe(true);
  });

  it('rejects a repeated cursor so the caller can restart from the first page', () => {
    const first = mergeGraphNeighborhoodPage(null, page(1, 1, 'page-2'), null);

    expect(() =>
      mergeGraphNeighborhoodPage(first, page(2, 1, 'page-2'), 'page-2'),
    ).toThrowError(GraphNeighborhoodMergeError);
  });

  it('rejects a page that belongs to another center', () => {
    const first = mergeGraphNeighborhoodPage(null, page(1, 1, 'page-2'), null);
    const wrongCenter = {
      ...page(2, 1, null),
      center: { ...center, id: 'tag:10000000-0000-0000-0000-000000000099' },
    };

    expect(() => mergeGraphNeighborhoodPage(first, wrongCenter, 'page-2')).toThrowError(
      GraphNeighborhoodMergeError,
    );
  });

  it('discards accumulated tag pages and their cursor after a neighbor pin changes ordering', () => {
    const accumulated = mergeGraphNeighborhoodPage(
      mergeGraphNeighborhoodPage(null, page(1, 2, 'old-page-2'), null),
      page(3, 1, 'old-page-3'),
      'old-page-2',
    );

    const reconciled = reconcileGraphNeighborhoodAfterMemoPin(
      accumulated,
      '20000000-0000-0000-0000-000000000002',
      true,
    );

    expect(reconciled.collection).toBeNull();
    expect(reconciled.reloadCenter).toEqual(center);
    expect(reconciled.collection?.nextCursor).toBeUndefined();
  });

  it('patches memo-center metadata without invalidating stable tag pagination', () => {
    const memoCenter = memo(9);
    const memoPage: GraphNeighborhoodPage = {
      center: memoCenter,
      neighbors: [],
      edges: [],
      truncated: false,
      nextCursor: null,
    };
    const current = mergeGraphNeighborhoodPage(null, memoPage, null);

    const reconciled = reconcileGraphNeighborhoodAfterMemoPin(
      current,
      '20000000-0000-0000-0000-000000000009',
      true,
    );

    expect(reconciled.reloadCenter).toBeNull();
    expect(reconciled.collection?.center.pinned).toBe(true);
  });

  it('replaces an expired append cursor retry with a clean first-page request', () => {
    expect(graphNeighborhoodRetryRequest(center, 'expired-page-2', true, true)).toEqual({
      node: center,
      cursor: null,
      append: false,
    });
  });

  it('preserves the exact append identity for a transient network retry', () => {
    expect(graphNeighborhoodRetryRequest(center, 'page-2', true, false)).toEqual({
      node: center,
      cursor: 'page-2',
      append: true,
    });
  });
});
