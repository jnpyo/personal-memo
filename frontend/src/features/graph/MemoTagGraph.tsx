import { Background, Controls, Handle, Position, ReactFlow } from '@xyflow/react';
import type { Node, NodeProps } from '@xyflow/react';
import type { GraphProjection } from '../../shared/api/types';
import { buildFlowElements, type MemoGraphNodeData } from './graphModel';

type Props = {
  projection: GraphProjection;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
};

type MemoGraphNode = Node<MemoGraphNodeData>;

function systemIcon(detail: string): string {
  if (detail.startsWith('TASK')) return '✓';
  if (detail.startsWith('EVENT')) return '◷';
  if (detail.startsWith('IDEA')) return '◇';
  if (detail.startsWith('INFORMATION')) return 'i';
  if (detail === '태그') return '#';
  return '•';
}

function GraphNodeCard({ data }: NodeProps<MemoGraphNode>) {
  return (
    <div className="graph-node__content">
      {data.kind === 'TAG' && <Handle type="target" position={Position.Left} isConnectable={false} />}
      <span className="graph-node__icon" aria-hidden="true">
        {systemIcon(data.detail)}
      </span>
      <span>
        <strong>{data.label}</strong>
        <small>{data.overdue ? '기한 초과 · ' : ''}{data.detail}</small>
      </span>
      {data.kind === 'MEMO' && <Handle type="source" position={Position.Right} isConnectable={false} />}
    </div>
  );
}

const nodeTypes = { memoGraphNode: GraphNodeCard };

export function MemoTagGraph({ projection, loading, error, onRetry }: Props) {
  const { nodes, edges } = buildFlowElements(projection);

  return (
    <section className="graph-section" aria-labelledby="graph-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">DERIVED VIEW</span>
          <h2 id="graph-title">메모와 태그</h2>
        </div>
        {projection.truncated && <span className="limit-badge">최근 100개만 표시</span>}
      </div>

      <div className="graph-canvas">
        {loading && <div className="panel-state">그래프를 불러오는 중…</div>}
        {!loading && error && (
          <div className="panel-state panel-state--error" role="alert">
            <p>{error}</p>
            <button type="button" className="secondary-button" onClick={onRetry}>
              다시 불러오기
            </button>
          </div>
        )}
        {!loading && !error && nodes.length === 0 && (
          <div className="panel-state">승인한 메모와 태그의 관계가 여기에 표시됩니다.</div>
        )}
        {!loading && !error && nodes.length > 0 && (
          <ReactFlow
            key={projection.projectionVersion}
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.24, minZoom: 0.6, maxZoom: 1.1 }}
            minZoom={0.45}
            maxZoom={1.6}
            nodesConnectable={false}
            nodesDraggable={false}
            elementsSelectable={false}
            zoomOnDoubleClick={false}
            aria-label={`메모 ${nodes.filter((node) => node.data.kind === 'MEMO').length}개와 태그 ${nodes.filter((node) => node.data.kind === 'TAG').length}개의 관계 그래프`}
          >
            <Background color="#c9c5b9" gap={24} size={1} />
            <Controls showInteractive={false} position="bottom-right" />
          </ReactFlow>
        )}
      </div>
    </section>
  );
}
