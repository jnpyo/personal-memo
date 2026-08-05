import { describe, expect, it } from 'vitest';
import type { GraphProjection } from '../../shared/api/types';
import { buildFlowElements } from './graphModel';

const projection: GraphProjection = {
  projectionVersion: 'test-v1',
  truncated: false,
  nodes: [
    { id: 'memo:1', kind: 'MEMO', label: '운영체제 과제', memoType: 'TASK', taskState: 'TODO' },
    { id: 'memo:1', kind: 'MEMO', label: '중복 메모' },
    { id: 'tag:1', kind: 'TAG', label: '운영체제' },
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
    expect(result.nodes.some((node) => node.id === 'TASK')).toBe(false);
  });
});
