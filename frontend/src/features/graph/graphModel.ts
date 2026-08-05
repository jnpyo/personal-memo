import type { Edge, Node } from '@xyflow/react';
import type { GraphProjection } from '../../shared/api/types';

export type MemoGraphNodeData = {
  label: string;
  kind: 'MEMO' | 'TAG';
  detail: string;
  overdue: boolean;
};

export type FlowElements = {
  nodes: Node<MemoGraphNodeData>[];
  edges: Edge[];
};

const ROW_GAP = 96;

function detailFor(node: GraphProjection['nodes'][number]): string {
  if (node.kind === 'TAG') {
    return '태그';
  }

  const taskState = node.taskState && node.taskState !== 'NONE' ? ` · ${node.taskState}` : '';
  return `${node.memoType ?? 'MEMO'}${taskState}`;
}

export function buildFlowElements(projection: GraphProjection): FlowElements {
  const seenNodeIds = new Set<string>();
  const uniqueNodes = projection.nodes.filter((node) => {
    if (seenNodeIds.has(node.id)) return false;
    seenNodeIds.add(node.id);
    return true;
  });
  const memoNodes = uniqueNodes.filter((node) => node.kind === 'MEMO');
  const tagNodes = uniqueNodes.filter((node) => node.kind === 'TAG');

  const nodes: Node<MemoGraphNodeData>[] = [...memoNodes, ...tagNodes].map((node, index) => {
    const kindIndex = node.kind === 'MEMO' ? memoNodes.indexOf(node) : tagNodes.indexOf(node);
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
        overdue: Boolean(node.overdue),
      },
      type: 'memoGraphNode',
      className: `graph-node graph-node--${node.kind.toLowerCase()}${node.overdue ? ' graph-node--overdue' : ''}`,
      draggable: false,
      selectable: false,
      zIndex: uniqueNodes.length - index,
    };
  });

  const knownNodeIds = new Set(nodes.map((node) => node.id));
  const edges: Edge[] = [
    ...new Map(
      projection.edges
        .filter((edge) => knownNodeIds.has(edge.source) && knownNodeIds.has(edge.target))
        .map((edge) => [edge.id, edge]),
    ).values(),
  ].map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    type: 'smoothstep',
    animated: false,
    selectable: false,
    style: { stroke: '#829187', strokeWidth: 1.5 },
  }));

  return { nodes, edges };
}
