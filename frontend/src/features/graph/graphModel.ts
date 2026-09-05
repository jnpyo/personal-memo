import type { Edge, Node } from '@xyflow/react';
import type { GraphEdge, GraphNode, GraphProjection } from '../../shared/api/types';

export type GraphNodeEmphasis = 'DEFAULT' | 'SELECTED' | 'NEIGHBOR' | 'DIMMED';
export type GraphNodeTone =
  | 'memo'
  | 'task'
  | 'event'
  | 'idea'
  | 'information'
  | 'tag';

export type MemoGraphNodeData = {
  label: string;
  kind: 'MEMO' | 'TAG';
  detail: string;
  tone: GraphNodeTone;
  overdue: boolean;
  pinned: boolean;
  emphasis: GraphNodeEmphasis;
};

export type FlowElements = {
  nodes: Node<MemoGraphNodeData>[];
  edges: Edge[];
};

const ROW_GAP = 96;
const NODE_HEIGHT = 64;
const MEMO_NODE_WIDTH = 208;
const TAG_NODE_WIDTH = 172;

export type VisibleGraphNeighborhood = {
  node: GraphNode | null;
  neighbors: GraphNode[];
  edges: GraphEdge[];
};

function uniqueGraphNodes(projection: GraphProjection): GraphNode[] {
  const seenNodeIds = new Set<string>();
  return projection.nodes.filter((node) => {
    if (seenNodeIds.has(node.id)) return false;
    seenNodeIds.add(node.id);
    return true;
  });
}

function visibleGraphEdges(nodes: GraphNode[], projection: GraphProjection): GraphEdge[] {
  const knownNodeIds = new Set(nodes.map((node) => node.id));
  return [
    ...new Map(
      projection.edges
        .filter((edge) => knownNodeIds.has(edge.source) && knownNodeIds.has(edge.target))
        .map((edge) => [edge.id, edge]),
    ).values(),
  ];
}

export function graphNodeEntityId(node: Pick<GraphNode, 'id' | 'kind'>): string {
  const prefix = `${node.kind.toLowerCase()}:`;
  return node.id.startsWith(prefix) && node.id.length > prefix.length
    ? node.id.slice(prefix.length)
    : node.id;
}

export function selectedNodeForProjection(
  projection: GraphProjection,
  selectedNode: GraphNode | null,
  refreshPending: boolean,
  selectionProjectionVersion: string | null,
  protectEditor = false,
): GraphNode | null {
  if (!selectedNode) return null;
  return projection.nodes.find((node) => node.id === selectedNode.id) ??
    (protectEditor || (refreshPending && projection.projectionVersion === selectionProjectionVersion)
      ? selectedNode
      : null);
}

export function visibleGraphNeighborhood(
  projection: GraphProjection,
  selectedNodeId: string | null,
): VisibleGraphNeighborhood {
  const nodes = uniqueGraphNodes(projection);
  const edges = visibleGraphEdges(nodes, projection);
  const node = selectedNodeId
    ? nodes.find((candidate) => candidate.id === selectedNodeId) ?? null
    : null;
  if (!node) return { node: null, neighbors: [], edges: [] };

  const neighborhoodEdges = edges.filter(
    (edge) => edge.source === node.id || edge.target === node.id,
  );
  const neighborIds = new Set(
    neighborhoodEdges.map((edge) => edge.source === node.id ? edge.target : edge.source),
  );
  return {
    node,
    neighbors: nodes.filter((candidate) => neighborIds.has(candidate.id)),
    edges: neighborhoodEdges,
  };
}

function detailFor(node: GraphProjection['nodes'][number]): string {
  if (node.kind === 'TAG') return '태그';
  if (node.memoType === 'TASK') {
    if (node.overdue) return '할 일 · 기한 지남';
    if (node.taskState === 'DONE') return '할 일 · 완료';
    if (node.taskState === 'CANCELLED') return '할 일 · 취소';
    return '할 일';
  }
  if (node.memoType === 'EVENT') return '일정';
  if (node.memoType === 'IDEA') return '아이디어';
  if (node.memoType === 'INFORMATION') return '정보';
  return '메모';
}

function toneFor(node: GraphProjection['nodes'][number]): GraphNodeTone {
  if (node.kind === 'TAG') return 'tag';
  if (node.memoType === 'TASK') return 'task';
  if (node.memoType === 'EVENT') return 'event';
  if (node.memoType === 'IDEA') return 'idea';
  if (node.memoType === 'INFORMATION') return 'information';
  return 'memo';
}

export function buildFlowElements(
  projection: GraphProjection,
  selectedNodeId: string | null = null,
): FlowElements {
  const uniqueNodes = uniqueGraphNodes(projection);
  const uniqueEdges = visibleGraphEdges(uniqueNodes, projection);
  const memoNodes = uniqueNodes.filter((node) => node.kind === 'MEMO');
  const tagNodes = uniqueNodes.filter((node) => node.kind === 'TAG');
  const selectedExists = selectedNodeId !== null &&
    uniqueNodes.some((node) => node.id === selectedNodeId);
  const selectedEdges = selectedExists
    ? uniqueEdges.filter(
        (edge) => edge.source === selectedNodeId || edge.target === selectedNodeId,
      )
    : [];
  const neighborIds = new Set(
    selectedEdges.map((edge) => edge.source === selectedNodeId ? edge.target : edge.source),
  );

  const nodes: Node<MemoGraphNodeData>[] = [...memoNodes, ...tagNodes].map((node, index) => {
    const kindIndex = node.kind === 'MEMO' ? memoNodes.indexOf(node) : tagNodes.indexOf(node);
    const emphasis: GraphNodeEmphasis = !selectedExists
      ? 'DEFAULT'
      : node.id === selectedNodeId
        ? 'SELECTED'
        : neighborIds.has(node.id)
          ? 'NEIGHBOR'
          : 'DIMMED';
    return {
      id: node.id,
      position: {
        x: node.kind === 'MEMO' ? 24 : 304,
        y: 24 + kindIndex * ROW_GAP + (node.kind === 'TAG' ? 36 : 0),
      },
      data: {
        label: node.label,
        kind: node.kind,
        detail: detailFor(node),
        tone: toneFor(node),
        overdue: Boolean(node.overdue),
        pinned: Boolean(node.pinned),
        emphasis,
      },
      type: 'memoGraphNode',
      initialWidth: node.kind === 'MEMO' ? MEMO_NODE_WIDTH : TAG_NODE_WIDTH,
      initialHeight: NODE_HEIGHT,
      style: { pointerEvents: 'all' },
      className: [
        'graph-node',
        `graph-node--${node.kind.toLowerCase()}`,
        `graph-node--tone-${toneFor(node)}`,
        node.overdue ? 'graph-node--overdue' : '',
        node.pinned ? 'graph-node--pinned' : '',
        emphasis !== 'DEFAULT' ? `graph-node--${emphasis.toLowerCase()}` : '',
      ].filter(Boolean).join(' '),
      draggable: false,
      selectable: false,
      zIndex: uniqueNodes.length - index,
    };
  });

  const edges: Edge[] = uniqueEdges.map((edge) => {
    const highlighted = selectedExists &&
      (edge.source === selectedNodeId || edge.target === selectedNodeId);
    return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'smoothstep',
    animated: false,
    selectable: false,
      className: highlighted
        ? 'graph-edge graph-edge--selected'
        : selectedExists
          ? 'graph-edge graph-edge--dimmed'
          : 'graph-edge',
      style: {
        stroke: highlighted ? '#176a62' : '#66766f',
        strokeWidth: highlighted ? 3 : 1.5,
        opacity: selectedExists && !highlighted ? 0.18 : 1,
      },
    };
  });

  return { nodes, edges };
}
