import { describe, expect, it } from 'vitest';
import type { GraphProjection } from '../../shared/api/types';
import {
  buildFlowElements,
  graphNodeEntityId,
  selectedNodeForProjection,
  visibleGraphNeighborhood,
} from './graphModel';

const projection: GraphProjection = {
  projectionVersion: 'test-v1',
  truncated: false,
  nodes: [
    { id: 'memo:1', kind: 'MEMO', label: '운영체제 과제', pinned: true, memoType: 'TASK', taskState: 'TODO' },
    { id: 'memo:1', kind: 'MEMO', label: '중복 메모', pinned: false },
    { id: 'tag:1', kind: 'TAG', label: '운영체제', pinned: false },
  ],
  edges: [
    { id: 'edge:1', source: 'memo:1', target: 'tag:1', kind: 'MEMO_TAG' },
    { id: 'dangling', source: 'memo:missing', target: 'tag:1', kind: 'MEMO_TAG' },
  ],
};

describe('buildFlowElements', () => {
  it('deduplicates projected nodes and removes dangling edges', () => {
    const result = buildFlowElements(projection);

    expect(result.nodes.map((node) => node.id)).toEqual(['memo:1', 'tag:1']);
    expect(result.edges.map((edge) => edge.id)).toEqual(['edge:1']);
  });

  it('keeps system type and task state as memo metadata', () => {
    const result = buildFlowElements(projection);
    const memo = result.nodes.find((node) => node.id === 'memo:1');

    expect(memo?.data.kind).toBe('MEMO');
    expect(memo?.data.detail).toBe('TASK · TODO');
    expect(memo?.data.pinned).toBe(true);
    expect(memo).toMatchObject({
      initialWidth: 210,
      initialHeight: 56,
      style: { pointerEvents: 'all' },
    });
    expect(result.nodes.find((node) => node.id === 'tag:1')).toMatchObject({
      initialWidth: 176,
      initialHeight: 56,
    });
    expect(result.nodes.some((node) => node.id === 'TASK')).toBe(false);
  });

  it('highlights only the selected node, its one-hop neighbors, and connecting edges', () => {
    const extended: GraphProjection = {
      ...projection,
      nodes: [
        ...projection.nodes,
        { id: 'memo:2', kind: 'MEMO', label: '관계없는 메모', pinned: false },
        { id: 'tag:2', kind: 'TAG', label: '관계없는 태그', pinned: false },
      ],
      edges: [
        ...projection.edges,
        { id: 'edge:2', source: 'memo:2', target: 'tag:2', kind: 'MEMO_TAG' },
      ],
    };

    const result = buildFlowElements(extended, 'memo:1');

    expect(result.nodes.find((node) => node.id === 'memo:1')?.data.emphasis).toBe('SELECTED');
    expect(result.nodes.find((node) => node.id === 'tag:1')?.data.emphasis).toBe('NEIGHBOR');
    expect(result.nodes.find((node) => node.id === 'memo:2')?.data.emphasis).toBe('DIMMED');
    expect(result.edges[0]?.className).toBe('graph-edge graph-edge--selected');
    expect(result.edges[1]?.className).toBe('graph-edge graph-edge--dimmed');
    expect(result.edges[1]?.style).toMatchObject({ opacity: 0.18 });
  });

  it('derives bounded visible neighbors and canonical entity ids from the snapshot', () => {
    const neighborhood = visibleGraphNeighborhood(projection, 'tag:1');

    expect(neighborhood.node?.label).toBe('운영체제');
    expect(neighborhood.neighbors.map((node) => node.id)).toEqual(['memo:1']);
    expect(neighborhood.edges.map((edge) => edge.id)).toEqual(['edge:1']);
    expect(graphNodeEntityId(projection.nodes[0])).toBe('1');
  });

  it('keeps a selection during refresh but removes its actions after a successful missing projection', () => {
    const selected = projection.nodes[0];
    const withoutSelected: GraphProjection = { ...projection, nodes: projection.nodes.slice(2) };

    expect(selectedNodeForProjection(withoutSelected, selected, true, 'test-v1')).toBe(selected);
    expect(selectedNodeForProjection(withoutSelected, selected, false, 'test-v1')).toBeNull();
    expect(selectedNodeForProjection(
      { ...withoutSelected, projectionVersion: 'test-v2' },
      selected,
      true,
      'test-v1',
    )).toBeNull();
  });
});
