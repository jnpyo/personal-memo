import type {
  GraphEdge,
  GraphNeighborhoodPage,
  GraphNode,
} from '../../shared/api/types';

export const GRAPH_NEIGHBORHOOD_PAGE_LIMIT = 20;
export const GRAPH_NEIGHBORHOOD_MAX_PAGES = 5;
export const GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS =
  GRAPH_NEIGHBORHOOD_PAGE_LIMIT * GRAPH_NEIGHBORHOOD_MAX_PAGES;
export const GRAPH_NEIGHBORHOOD_MAX_EDGES = GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS;

export type GraphNeighborhoodCollection = {
  center: GraphNode;
  neighbors: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
  nextCursor: string | null;
  pagesLoaded: number;
  browserTruncated: boolean;
  loadedCursors: string[];
};

export type GraphNeighborhoodPinReconciliation = {
  collection: GraphNeighborhoodCollection | null;
  reloadCenter: GraphNode | null;
};

export type GraphNeighborhoodRetryRequest = {
  node: GraphNode;
  cursor: string | null;
  append: boolean;
};

export class GraphNeighborhoodMergeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GraphNeighborhoodMergeError';
  }
}

function cursorIdentity(cursor: string | null): string {
  return cursor ?? '__FIRST_PAGE__';
}

function appendUniqueById<T extends { id: string }>(
  current: T[],
  incoming: T[],
  limit: number,
): T[] {
  const byId = new Map(current.map((item) => [item.id, item]));
  incoming.forEach((item) => {
    if (!byId.has(item.id) && byId.size < limit) byId.set(item.id, item);
  });
  return [...byId.values()];
}

export function reconcileGraphNeighborhoodAfterMemoPin(
  current: GraphNeighborhoodCollection | null,
  memoId: string,
  pinned: boolean,
): GraphNeighborhoodPinReconciliation {
  if (!current) return { collection: null, reloadCenter: null };

  const graphMemoId = `memo:${memoId}`;
  if (
    current.center.kind === 'TAG' &&
    current.neighbors.some((neighbor) => neighbor.id === graphMemoId)
  ) {
    return { collection: null, reloadCenter: current.center };
  }

  const updateNode = (node: GraphNode): GraphNode =>
    node.id === graphMemoId && node.kind === 'MEMO'
      ? { ...node, pinned }
      : node;
  return {
    collection: {
      ...current,
      center: updateNode(current.center),
      neighbors: current.neighbors.map(updateNode),
    },
    reloadCenter: null,
  };
}

export function graphNeighborhoodRetryRequest(
  node: GraphNode,
  cursor: string | null,
  append: boolean,
  restartFromFirstPage: boolean,
): GraphNeighborhoodRetryRequest {
  return restartFromFirstPage
    ? { node, cursor: null, append: false }
    : { node, cursor, append };
}

export function mergeGraphNeighborhoodPage(
  current: GraphNeighborhoodCollection | null,
  page: GraphNeighborhoodPage,
  requestedCursor: string | null,
): GraphNeighborhoodCollection {
  if (!current && requestedCursor !== null) {
    throw new GraphNeighborhoodMergeError('The first neighborhood page cannot use a cursor.');
  }
  if (
    current &&
    (current.center.id !== page.center.id || current.center.kind !== page.center.kind)
  ) {
    throw new GraphNeighborhoodMergeError('The neighborhood center changed between pages.');
  }
  if (current && current.nextCursor !== requestedCursor) {
    throw new GraphNeighborhoodMergeError('The neighborhood cursor changed before merge.');
  }

  const loadedCursor = cursorIdentity(requestedCursor);
  if (current?.loadedCursors.includes(loadedCursor)) {
    throw new GraphNeighborhoodMergeError('The neighborhood page cursor was already loaded.');
  }

  const pagesLoaded = (current?.pagesLoaded ?? 0) + 1;
  const neighbors = appendUniqueById(
    current?.neighbors ?? [],
    page.neighbors,
    GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS,
  );
  const edges = appendUniqueById(
    current?.edges ?? [],
    page.edges,
    GRAPH_NEIGHBORHOOD_MAX_EDGES,
  );
  const loadedCursors = [...(current?.loadedCursors ?? []), loadedCursor];
  const repeatsCursor = page.nextCursor !== null &&
    (page.nextCursor === requestedCursor ||
      loadedCursors.includes(cursorIdentity(page.nextCursor)));
  if (repeatsCursor) {
    throw new GraphNeighborhoodMergeError(
      'The neighborhood continuation cursor repeated before traversal completed.',
    );
  }
  const browserTruncated = page.nextCursor !== null && (
    pagesLoaded >= GRAPH_NEIGHBORHOOD_MAX_PAGES ||
    neighbors.length >= GRAPH_NEIGHBORHOOD_MAX_NEIGHBORS ||
    edges.length >= GRAPH_NEIGHBORHOOD_MAX_EDGES
  );

  return {
    center: current?.center ?? page.center,
    neighbors,
    edges,
    truncated: page.truncated,
    nextCursor: browserTruncated ? null : page.nextCursor,
    pagesLoaded,
    browserTruncated: Boolean(current?.browserTruncated) || browserTruncated,
    loadedCursors,
  };
}
