import type {
  GraphEdge,
  GraphNeighborhoodPage,
  GraphNode,
  ItemKind,
  TaskStatus,
} from './types';

const ITEM_KINDS = new Set<ItemKind>([
  'TASK',
  'EVENT',
  'INFORMATION',
  'IDEA',
  'RECORD',
]);
const TASK_STATES = new Set<TaskStatus | 'NONE'>([
  'TODO',
  'DONE',
  'CANCELLED',
  'NONE',
]);
const PAGE_ITEM_LIMIT = 20;
const CURSOR_MAX_LENGTH = 1_024;
const CURSOR_PATTERN = /^[A-Za-z0-9_-]+$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ROOT_FIELDS = ['center', 'neighbors', 'edges', 'truncated', 'nextCursor'] as const;
const NODE_FIELDS = [
  'id',
  'kind',
  'label',
  'memoType',
  'taskState',
  'overdue',
  'pinned',
] as const;
const EDGE_FIELDS = ['id', 'source', 'target', 'kind'] as const;

export class GraphNeighborhoodContractError extends Error {
  constructor(readonly field: string) {
    super(`Invalid graph neighborhood response field: ${field}`);
    this.name = 'GraphNeighborhoodContractError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new GraphNeighborhoodContractError(field);
  }
  return value;
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
    throw new GraphNeighborhoodContractError(field);
  }
}

function requirePrefixedUuid(
  value: unknown,
  kind: GraphNode['kind'],
  field: string,
): string {
  const id = requireString(value, field);
  const prefix = `${kind.toLowerCase()}:`;
  if (!id.startsWith(prefix) || !UUID_PATTERN.test(id.slice(prefix.length))) {
    throw new GraphNeighborhoodContractError(field);
  }
  return id;
}

function decodeNode(value: unknown, field: string): GraphNode {
  if (!isRecord(value)) throw new GraphNeighborhoodContractError(field);
  requireExactFields(value, NODE_FIELDS, field);
  const label = requireString(value.label, `${field}.label`);
  if (value.kind !== 'MEMO' && value.kind !== 'TAG') {
    throw new GraphNeighborhoodContractError(`${field}.kind`);
  }
  const labelLimit = value.kind === 'TAG' ? 100 : 200;
  if (label.length > labelLimit) {
    throw new GraphNeighborhoodContractError(`${field}.label`);
  }
  const id = requirePrefixedUuid(value.id, value.kind, `${field}.id`);
  if (typeof value.pinned !== 'boolean') {
    throw new GraphNeighborhoodContractError(`${field}.pinned`);
  }
  if (typeof value.overdue !== 'boolean') {
    throw new GraphNeighborhoodContractError(`${field}.overdue`);
  }
  if (
    value.memoType !== null &&
    (typeof value.memoType !== 'string' || !ITEM_KINDS.has(value.memoType as ItemKind))
  ) {
    throw new GraphNeighborhoodContractError(`${field}.memoType`);
  }
  if (
    value.taskState !== null &&
    (typeof value.taskState !== 'string' ||
      !TASK_STATES.has(value.taskState as TaskStatus | 'NONE'))
  ) {
    throw new GraphNeighborhoodContractError(`${field}.taskState`);
  }
  if (
    value.kind === 'TAG' &&
    (value.memoType !== null ||
      value.taskState !== null ||
      value.overdue !== false ||
      value.pinned !== false)
  ) {
    throw new GraphNeighborhoodContractError(field);
  }
  if (value.kind === 'MEMO' && (value.memoType === null || value.taskState === null)) {
    throw new GraphNeighborhoodContractError(field);
  }

  return {
    id,
    kind: value.kind,
    label,
    pinned: value.pinned,
    memoType: value.memoType as ItemKind | null,
    taskState: value.taskState as TaskStatus | 'NONE' | null,
    overdue: value.overdue,
  };
}

function decodeEdge(value: unknown, field: string): GraphEdge {
  if (!isRecord(value)) throw new GraphNeighborhoodContractError(field);
  requireExactFields(value, EDGE_FIELDS, field);
  if (value.kind !== 'MEMO_TAG') {
    throw new GraphNeighborhoodContractError(`${field}.kind`);
  }
  return {
    id: requireString(value.id, `${field}.id`),
    source: requireString(value.source, `${field}.source`),
    target: requireString(value.target, `${field}.target`),
    kind: value.kind,
  };
}

export type ExpectedGraphNeighborhoodCenter = {
  kind: GraphNode['kind'];
  entityId: string;
};

export function assertGraphNeighborhoodExpectedCenter(
  expectedCenter: ExpectedGraphNeighborhoodCenter,
): void {
  if (
    (expectedCenter.kind !== 'MEMO' && expectedCenter.kind !== 'TAG') ||
    !UUID_PATTERN.test(expectedCenter.entityId)
  ) {
    throw new GraphNeighborhoodContractError('expectedCenter');
  }
}

export function decodeGraphNeighborhoodPage(
  value: unknown,
  expectedCenter: ExpectedGraphNeighborhoodCenter,
): GraphNeighborhoodPage {
  assertGraphNeighborhoodExpectedCenter(expectedCenter);
  if (!isRecord(value)) throw new GraphNeighborhoodContractError('root');
  requireExactFields(value, ROOT_FIELDS, 'root');
  if (!Array.isArray(value.neighbors) || value.neighbors.length > PAGE_ITEM_LIMIT) {
    throw new GraphNeighborhoodContractError('neighbors');
  }
  if (!Array.isArray(value.edges) || value.edges.length > PAGE_ITEM_LIMIT) {
    throw new GraphNeighborhoodContractError('edges');
  }
  if (typeof value.truncated !== 'boolean') {
    throw new GraphNeighborhoodContractError('truncated');
  }
  if (value.nextCursor !== null && typeof value.nextCursor !== 'string') {
    throw new GraphNeighborhoodContractError('nextCursor');
  }
  if (
    typeof value.nextCursor === 'string' &&
    (value.nextCursor.length === 0 ||
      value.nextCursor.length > CURSOR_MAX_LENGTH ||
      !CURSOR_PATTERN.test(value.nextCursor))
  ) {
    throw new GraphNeighborhoodContractError('nextCursor');
  }
  if (value.truncated !== (value.nextCursor !== null)) {
    throw new GraphNeighborhoodContractError('truncated');
  }

  const center = decodeNode(value.center, 'center');
  const expectedCenterId = `${expectedCenter.kind.toLowerCase()}:${expectedCenter.entityId}`;
  if (center.kind !== expectedCenter.kind || center.id !== expectedCenterId) {
    throw new GraphNeighborhoodContractError('center');
  }
  const expectedNeighborKind = center.kind === 'MEMO' ? 'TAG' : 'MEMO';
  const neighbors = value.neighbors.map((node, index) =>
    decodeNode(node, `neighbors[${index}]`),
  );
  const neighborIds = new Set<string>();
  neighbors.forEach((neighbor, index) => {
    if (
      neighbor.kind !== expectedNeighborKind ||
      neighbor.id === center.id ||
      neighborIds.has(neighbor.id)
    ) {
      throw new GraphNeighborhoodContractError(`neighbors[${index}]`);
    }
    neighborIds.add(neighbor.id);
  });
  const edges = value.edges.map((edge, index) => decodeEdge(edge, `edges[${index}]`));
  const edgeIds = new Set<string>();
  const connectedNeighborIds = new Set<string>();
  edges.forEach((edge, index) => {
    if (edgeIds.has(edge.id)) {
      throw new GraphNeighborhoodContractError(`edges[${index}].id`);
    }
    edgeIds.add(edge.id);
    const centerIsSource = edge.source === center.id;
    const centerIsTarget = edge.target === center.id;
    if (centerIsSource === centerIsTarget) {
      throw new GraphNeighborhoodContractError(`edges[${index}]`);
    }
    const neighborId = centerIsSource ? edge.target : edge.source;
    if (!neighborIds.has(neighborId) || connectedNeighborIds.has(neighborId)) {
      throw new GraphNeighborhoodContractError(`edges[${index}]`);
    }
    const memoId = center.kind === 'MEMO' ? center.id : neighborId;
    const tagId = center.kind === 'TAG' ? center.id : neighborId;
    if (edge.source !== memoId || edge.target !== tagId) {
      throw new GraphNeighborhoodContractError(`edges[${index}]`);
    }
    const expectedEdgeId = `memo-tag:${memoId.slice('memo:'.length)}:${tagId.slice('tag:'.length)}`;
    if (edge.id !== expectedEdgeId) {
      throw new GraphNeighborhoodContractError(`edges[${index}].id`);
    }
    connectedNeighborIds.add(neighborId);
  });
  if (connectedNeighborIds.size !== neighborIds.size) {
    throw new GraphNeighborhoodContractError('edges');
  }

  return {
    center,
    neighbors,
    edges,
    truncated: value.truncated,
    nextCursor: value.nextCursor,
  };
}
