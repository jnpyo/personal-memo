import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
} from 'react';
import { Background, Controls, Handle, Position, ReactFlow } from '@xyflow/react';
import type { Node, NodeProps } from '@xyflow/react';
import type { GraphNode, GraphProjection, MemoView } from '../../shared/api/types';
import { CurrentMemoDetail } from '../memos/CurrentMemoDetail';
import {
  buildFlowElements,
  selectedNodeForProjection,
  type MemoGraphNodeData,
} from './graphModel';
import type { GraphNeighborhoodCollection } from './graphNeighborhoodModel';

type Props = {
  projection: GraphProjection;
  loading: boolean;
  error: string | null;
  selectedNode: GraphNode | null;
  selectionProjectionVersion: string | null;
  activeMemoNode: GraphNode | null;
  memoDetail: MemoView | null;
  detailLoading: boolean;
  detailError: string | null;
  neighborhood: GraphNeighborhoodCollection | null;
  neighborhoodLoading: boolean;
  neighborhoodLoadingMore: boolean;
  neighborhoodError: string | null;
  neighborhoodRestartRequired: boolean;
  pinPending: boolean;
  pinError: string | null;
  interactionDisabled: boolean;
  onRetry: () => void;
  onSelectNode: (node: GraphNode) => void;
  onCloseDetail: () => void;
  onRetryDetail: () => void;
  onRetryNeighborhood: () => void;
  onLoadMoreNeighborhood: () => void;
  onOpenNeighborhoodMemo: (node: GraphNode) => void;
  onBackToNeighborhood: () => void;
  onSetPinned: (memoId: string, pinned: boolean) => void;
  onRetryPin: () => void;
};

type MemoGraphNode = Node<MemoGraphNodeData>;

type GraphInteraction = {
  disabled: boolean;
  activate: (
    id: string,
    kind: GraphNode['kind'],
    opener: HTMLButtonElement,
  ) => void;
};

const GraphInteractionContext = createContext<GraphInteraction>({
  disabled: true,
  activate: () => undefined,
});

const MEMO_TYPE_LABEL: Record<NonNullable<GraphNode['memoType']>, string> = {
  TASK: '할 일',
  EVENT: '일정',
  INFORMATION: '정보',
  IDEA: '아이디어',
  RECORD: '기록',
};

const TASK_STATE_LABEL = {
  TODO: '미완료',
  DONE: '완료',
  CANCELLED: '취소',
  NONE: '할 일 없음',
} as const;

function systemIcon(detail: string): string {
  if (detail.startsWith('TASK')) return '✓';
  if (detail.startsWith('EVENT')) return '◷';
  if (detail.startsWith('IDEA')) return '◇';
  if (detail.startsWith('INFORMATION')) return 'i';
  if (detail === '태그') return '#';
  return '•';
}

function nodeKindLabel(kind: GraphNode['kind']): string {
  return kind === 'MEMO' ? '메모' : '태그';
}

function nodeControlLabel(data: MemoGraphNodeData): string {
  const state = [
    data.label,
    nodeKindLabel(data.kind),
    data.detail,
    data.pinned ? '고정됨' : null,
    data.overdue ? '기한 초과' : null,
  ].filter(Boolean).join(', ');
  return `${state}, 상세 열기`;
}

type GraphNodeButtonProps = {
  id: string;
  data: MemoGraphNodeData;
  disabled?: boolean;
  onActivate?: (opener: HTMLButtonElement) => void;
};

export function GraphNodeButton({
  id,
  data,
  disabled = false,
  onActivate = () => undefined,
}: GraphNodeButtonProps) {
  return (
    <button
      type="button"
      className="graph-node__content"
      aria-label={nodeControlLabel(data)}
      aria-pressed={data.emphasis === 'SELECTED'}
      data-graph-node-id={id}
      data-graph-node-kind={data.kind}
      disabled={disabled}
      onClick={(event) => onActivate(event.currentTarget)}
    >
      <span className="graph-node__icon" aria-hidden="true">
        {systemIcon(data.detail)}
      </span>
      <span>
        <strong>{data.label}</strong>
        <small>
          {data.pinned ? '고정됨 · ' : ''}
          {data.overdue ? '기한 초과 · ' : ''}
          {data.detail}
        </small>
      </span>
    </button>
  );
}

function GraphNodeCard({ data, id }: NodeProps<MemoGraphNode>) {
  const interaction = useContext(GraphInteractionContext);
  return (
    <div className="graph-node__frame">
      {data.kind === 'TAG' && (
        <Handle type="target" position={Position.Left} isConnectable={false} />
      )}
      <GraphNodeButton
        id={id}
        data={data}
        disabled={interaction.disabled}
        onActivate={(opener) => interaction.activate(id, data.kind, opener)}
      />
      {data.kind === 'MEMO' && (
        <Handle type="source" position={Position.Right} isConnectable={false} />
      )}
    </div>
  );
}

const nodeTypes = { memoGraphNode: GraphNodeCard };

export function focusGraphNode(
  nodeId: string,
  originalOpener: HTMLButtonElement | null,
): () => void {
  let cancelled = false;
  let frameId: number | null = null;
  let attempt = 0;
  let stablePreferredFrames = 0;

  const schedule = () => {
    frameId = window.requestAnimationFrame(restore);
  };

  const restore = () => {
    if (cancelled) return;
    const graphButtons = Array.from(
      document.querySelectorAll<HTMLButtonElement>('[data-graph-node-id]'),
    );
    const sameNode = graphButtons.find(
      (candidate) => candidate.dataset.graphNodeId === nodeId,
    );
    const firstEnabledNode = graphButtons.find((candidate) => !candidate.disabled);
    const graphHeading = document.getElementById('graph-title');
    const preferredTarget = originalOpener?.isConnected
      ? originalOpener
      : sameNode;
    const fallbackTarget = firstEnabledNode ?? graphHeading;
    const activeElement = document.activeElement;

    if (preferredTarget && !preferredTarget.disabled) {
      if (activeElement === preferredTarget) {
        stablePreferredFrames += 1;
        if (stablePreferredFrames >= 2) return;
      } else {
        if (
          attempt > 0 &&
          activeElement &&
          activeElement !== document.body &&
          activeElement !== graphHeading &&
          activeElement !== fallbackTarget
        ) return;
        preferredTarget.focus({ preventScroll: true });
        stablePreferredFrames = 0;
      }
    } else {
      stablePreferredFrames = 0;
      if (attempt === 0) fallbackTarget?.focus({ preventScroll: true });
      if (
        attempt > 0 &&
        activeElement &&
        activeElement !== document.body &&
        activeElement !== graphHeading &&
        activeElement !== fallbackTarget
      ) return;
    }

    if ((!preferredTarget && !fallbackTarget) || attempt >= 30) return;
    attempt += 1;
    schedule();
  };

  schedule();
  return () => {
    cancelled = true;
    if (frameId !== null) window.cancelAnimationFrame(frameId);
  };
}

export function shouldResumeGraphFocus(
  activeElement: Element | null,
  body: HTMLElement,
  graphHeading: HTMLElement | null,
): boolean {
  return activeElement === null || activeElement === body || activeElement === graphHeading;
}

export function focusNeighborhoodNode(
  nodeId: string,
  originalOpener: HTMLButtonElement | null,
): () => void {
  let cancelled = false;
  let frameId: number | null = null;
  let attempt = 0;

  const restore = () => {
    if (cancelled) return;
    const currentOpener = Array.from(
      document.querySelectorAll<HTMLButtonElement>('[data-neighborhood-node-id]'),
    ).find((candidate) => candidate.dataset.neighborhoodNodeId === nodeId);
    const preferredTarget = originalOpener?.isConnected ? originalOpener : currentOpener;
    const heading = document.getElementById('graph-neighborhood-title');
    const activeElement = document.activeElement;

    if (preferredTarget && !preferredTarget.disabled) {
      if (
        attempt > 0 &&
        activeElement &&
        activeElement !== document.body &&
        activeElement !== heading &&
        activeElement !== preferredTarget
      ) return;
      preferredTarget.focus({ preventScroll: true });
      return;
    }

    if (attempt === 0) heading?.focus({ preventScroll: true });
    if (
      attempt > 0 &&
      activeElement &&
      activeElement !== document.body &&
      activeElement !== heading
    ) return;
    if (attempt >= 30) return;
    attempt += 1;
    frameId = window.requestAnimationFrame(restore);
  };

  frameId = window.requestAnimationFrame(restore);
  return () => {
    cancelled = true;
    if (frameId !== null) window.cancelAnimationFrame(frameId);
  };
}

type GraphNodeDetailDrawerProps = {
  node: GraphNode;
  activeMemoNode: GraphNode | null;
  neighborhood: GraphNeighborhoodCollection | null;
  neighborhoodLoading: boolean;
  neighborhoodLoadingMore: boolean;
  neighborhoodError: string | null;
  neighborhoodRestartRequired: boolean;
  memoDetail: MemoView | null;
  loading: boolean;
  error: string | null;
  pinPending: boolean;
  pinError: string | null;
  interactionDisabled: boolean;
  onClose: () => void;
  onRetry: () => void;
  onRetryNeighborhood: () => void;
  onLoadMoreNeighborhood: () => void;
  onOpenNeighborhoodMemo: (node: GraphNode) => void;
  onBackToNeighborhood: () => void;
  onSetPinned: (memoId: string, pinned: boolean) => void;
  onRetryPin: () => void;
};

function memoMetadata(node: GraphNode): string[] {
  const metadata = [
    node.memoType ? MEMO_TYPE_LABEL[node.memoType] : '메모',
    node.taskState && node.taskState !== 'NONE'
      ? `할 일 상태 ${TASK_STATE_LABEL[node.taskState]}`
      : null,
    node.overdue ? '기한 초과' : null,
  ];
  return metadata.filter((value): value is string => value !== null);
}

export function shouldMoveGraphDetailFocus(
  previousMemoNodeId: string | null,
  nextMemoNodeId: string | null,
): boolean {
  return previousMemoNodeId !== nextMemoNodeId;
}

export function GraphNodeDetailDrawer({
  node,
  activeMemoNode,
  neighborhood,
  neighborhoodLoading,
  neighborhoodLoadingMore,
  neighborhoodError,
  neighborhoodRestartRequired,
  memoDetail,
  loading,
  error,
  pinPending,
  pinError,
  interactionDisabled,
  onClose,
  onRetry,
  onRetryNeighborhood,
  onLoadMoreNeighborhood,
  onOpenNeighborhoodMemo,
  onBackToNeighborhood,
  onSetPinned,
  onRetryPin,
}: GraphNodeDetailDrawerProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const scrollRef = useRef<HTMLElement>(null);
  const neighborOpenerRef = useRef<HTMLButtonElement | null>(null);
  const previousMemoNodeIdRef = useRef<string | null>(null);
  const neighborFocusCancelRef = useRef<(() => void) | null>(null);
  const pendingNeighborFocusIdRef = useRef<string | null>(null);
  const center = neighborhood?.center ?? node;
  const neighbors = neighborhood?.neighbors ?? [];
  const memoNeighbors = neighbors.filter((neighbor) => neighbor.kind === 'MEMO');
  const tagNeighbors = neighbors.filter((neighbor) => neighbor.kind === 'TAG');
  const isRootMemoDetail = activeMemoNode?.id === node.id;
  const activeMemoTags = activeMemoNode?.id === node.id
    ? tagNeighbors
    : node.kind === 'TAG'
      ? [center]
      : [];

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const frame = window.requestAnimationFrame(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = 0;
      headingRef.current?.focus({ preventScroll: true });
    });

    return () => {
      window.cancelAnimationFrame(frame);
      if (dialog.open) dialog.close();
    };
  }, [node.id]);

  useEffect(() => {
    const previousMemoNodeId = previousMemoNodeIdRef.current;
    const nextMemoNodeId = activeMemoNode?.id ?? null;
    if (!shouldMoveGraphDetailFocus(previousMemoNodeId, nextMemoNodeId)) return;
    previousMemoNodeIdRef.current = nextMemoNodeId;
    neighborFocusCancelRef.current?.();
    neighborFocusCancelRef.current = null;

    if (nextMemoNodeId) {
      pendingNeighborFocusIdRef.current = null;
      const frame = window.requestAnimationFrame(() => {
        if (scrollRef.current) scrollRef.current.scrollTop = 0;
        headingRef.current?.focus({ preventScroll: true });
      });
      neighborFocusCancelRef.current = () => window.cancelAnimationFrame(frame);
      return;
    }
    if (!previousMemoNodeId) return;

    pendingNeighborFocusIdRef.current = interactionDisabled ? previousMemoNodeId : null;
    neighborFocusCancelRef.current = focusNeighborhoodNode(
      previousMemoNodeId,
      neighborOpenerRef.current,
    );
  }, [activeMemoNode?.id, interactionDisabled]);

  useEffect(() => {
    if (interactionDisabled || activeMemoNode) return;
    const pendingNodeId = pendingNeighborFocusIdRef.current;
    if (!pendingNodeId) return;
    pendingNeighborFocusIdRef.current = null;
    if (
      !shouldResumeGraphFocus(
        document.activeElement,
        document.body,
        document.getElementById('graph-neighborhood-title'),
      )
    ) {
      neighborFocusCancelRef.current?.();
      neighborFocusCancelRef.current = null;
      return;
    }
    neighborFocusCancelRef.current?.();
    neighborFocusCancelRef.current = focusNeighborhoodNode(
      pendingNodeId,
      neighborOpenerRef.current,
    );
  }, [activeMemoNode, interactionDisabled]);

  useEffect(() => () => {
    neighborFocusCancelRef.current?.();
    neighborFocusCancelRef.current = null;
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="graph-detail-dialog"
      aria-labelledby="graph-neighborhood-title"
      aria-busy={loading || pinPending || neighborhoodLoading || neighborhoodLoadingMore}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <section ref={scrollRef} className="graph-detail-drawer">
        <header className="graph-detail-drawer__header">
          <div>
            <span className="eyebrow">
              {activeMemoNode ? 'CURRENT MEMO DETAIL' : 'FULL CORPUS NEIGHBORHOOD'}
            </span>
            <h2 id="graph-neighborhood-title" ref={headingRef} tabIndex={-1}>
              {activeMemoNode ? `${activeMemoNode.label} 상세` : `${center.label} 연결`}
            </h2>
          </div>
          <button
            type="button"
            className="graph-detail-drawer__close"
            aria-label="그래프 상세 닫기"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>

        {activeMemoNode ? (
          <>
            {!isRootMemoDetail && (
              <button
                type="button"
                className="secondary-button graph-detail-back"
                onClick={onBackToNeighborhood}
              >
                ← {center.label} 연결로 돌아가기
              </button>
            )}
            {!isRootMemoDetail && (
              <p className="graph-detail-drawer__scope">
                홈 그래프에 추가하지 않고 전체 연결에서 연 현재 메모입니다.
              </p>
            )}
            {isRootMemoDetail && (
              <p className="graph-detail-drawer__scope">
                현재 홈 그래프의 강조 범위는 바꾸지 않고, 전체 메모에서 직접 연결된 태그를 페이지당 20개씩 불러옵니다.
                브라우저에는 최대 100개까지만 유지합니다.
              </p>
            )}
            <dl className="graph-detail-metadata">
              <div>
                <dt>분류</dt>
                <dd>{memoMetadata(activeMemoNode).join(' · ')}</dd>
              </div>
              <div>
                <dt>그래프 고정</dt>
                <dd>{(memoDetail?.pinned ?? activeMemoNode.pinned) ? '고정됨' : '고정 안 됨'}</dd>
              </div>
            </dl>

            <CurrentMemoDetail
              memo={memoDetail}
              loading={loading}
              error={error}
              onRetry={onRetry}
              headingId="graph-raw-content-title"
            />
            {isRootMemoDetail && neighborhoodLoading && !neighborhood && (
              <p className="graph-detail-state" role="status">전체 연결을 불러오는 중…</p>
            )}
            {isRootMemoDetail && !neighborhoodLoading && neighborhoodError && !neighborhood && (
              <aside className="graph-detail-state graph-detail-state--error" role="alert">
                <p>{neighborhoodError}</p>
                <button type="button" className="secondary-button" onClick={onRetryNeighborhood}>
                  전체 연결 다시 불러오기
                </button>
              </aside>
            )}
            {!loading && !error && memoDetail && (
              <>
                {pinError && (
                  <aside className="graph-detail-state graph-detail-state--error" role="alert">
                    <p>{pinError}</p>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={interactionDisabled || pinPending}
                      onClick={onRetryPin}
                    >
                      고정 변경 다시 시도
                    </button>
                  </aside>
                )}
                <button
                  type="button"
                  className="approve-button graph-detail-pin"
                  disabled={interactionDisabled || pinPending}
                  onClick={() => onSetPinned(memoDetail.id, !memoDetail.pinned)}
                >
                  {pinPending
                    ? '고정 상태 변경 중…'
                    : memoDetail.pinned
                      ? '홈 그래프 고정 해제'
                      : '홈 그래프에 고정'}
                </button>
              </>
            )}
            <section className="graph-detail-block" aria-labelledby="graph-visible-tags-title">
              <div className="graph-detail-block__heading">
                <h3 id="graph-visible-tags-title">
                  {isRootMemoDetail ? '전체 1단계 태그 연결' : '이 경로의 태그 연결'}
                </h3>
                {isRootMemoDetail && neighborhood && (
                  <span>{tagNeighbors.length}개 불러옴</span>
                )}
              </div>
              {activeMemoTags.length > 0 ? (
                <ul className="graph-detail-neighbors">
                  {activeMemoTags.map((tag) => <li key={tag.id}>#{tag.label}</li>)}
                </ul>
              ) : (!isRootMemoDetail || neighborhood) ? (
                <p className="graph-detail-empty">불러온 연결에 태그가 없습니다.</p>
              ) : null}
            </section>
            {isRootMemoDetail && neighborhoodError && neighborhood && (
              <aside className="graph-detail-state graph-detail-state--error" role="alert">
                <p>{neighborhoodError}</p>
                <button type="button" className="secondary-button" onClick={onRetryNeighborhood}>
                  {neighborhoodRestartRequired
                    ? '전체 연결 처음부터 다시 불러오기'
                    : '다음 연결 다시 불러오기'}
                </button>
              </aside>
            )}
            {isRootMemoDetail && !neighborhoodRestartRequired && neighborhood?.nextCursor && (
              <button
                type="button"
                className="secondary-button graph-neighborhood-more"
                disabled={interactionDisabled || neighborhoodLoadingMore}
                onClick={onLoadMoreNeighborhood}
              >
                {neighborhoodLoadingMore ? '다음 연결 불러오는 중…' : '연결 더 불러오기'}
              </button>
            )}
            {isRootMemoDetail && neighborhood?.browserTruncated && (
              <p className="graph-detail-state" role="status">
                브라우저 표시 상한 100개에 도달해 나머지 연결은 불러오지 않았습니다.
              </p>
            )}
          </>
        ) : (
          <>
            <p className="graph-detail-drawer__scope">
              현재 홈 그래프의 강조 범위는 바꾸지 않고, 전체 메모에서 직접 연결된 항목을 페이지당 20개씩 불러옵니다.
              브라우저에는 최대 100개까지만 유지합니다.
            </p>
            <dl className="graph-detail-metadata">
              <div>
                <dt>{center.kind === 'TAG' ? '태그' : '메모'}</dt>
                <dd>{center.kind === 'TAG' ? `#${center.label}` : center.label}</dd>
              </div>
            </dl>
            {neighborhoodLoading && !neighborhood && (
              <p className="graph-detail-state" role="status">전체 연결을 불러오는 중…</p>
            )}
            {!neighborhoodLoading && neighborhoodError && !neighborhood && (
              <aside className="graph-detail-state graph-detail-state--error" role="alert">
                <p>{neighborhoodError}</p>
                <button type="button" className="secondary-button" onClick={onRetryNeighborhood}>
                  전체 연결 다시 불러오기
                </button>
              </aside>
            )}
            {neighborhood && (
              <section className="graph-detail-block" aria-labelledby="graph-full-neighbors-title">
                <div className="graph-detail-block__heading">
                  <h3 id="graph-full-neighbors-title">전체 1단계 연결</h3>
                  <span>{neighbors.length}개 불러옴</span>
                </div>
                {center.kind === 'TAG' && memoNeighbors.length > 0 && (
                  <ul className="graph-detail-neighbors graph-detail-neighbors--memos">
                    {memoNeighbors.map((memo) => (
                      <li key={memo.id}>
                        <button
                          type="button"
                          className="graph-neighborhood-memo"
                          data-neighborhood-node-id={memo.id}
                          disabled={interactionDisabled}
                          onClick={(event) => {
                            neighborOpenerRef.current = event.currentTarget;
                            onOpenNeighborhoodMemo(memo);
                          }}
                        >
                          <strong>{memo.label}</strong>
                          <span>{memoMetadata(memo).join(' · ')}</span>
                          <small>현재 원문 열기</small>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
                {center.kind === 'MEMO' && tagNeighbors.length > 0 && (
                  <ul className="graph-detail-neighbors">
                    {tagNeighbors.map((tag) => <li key={tag.id}>#{tag.label}</li>)}
                  </ul>
                )}
                {neighbors.length === 0 && (
                  <p className="graph-detail-empty">전체 메모에서 직접 연결된 항목이 없습니다.</p>
                )}
              </section>
            )}
            {neighborhoodError && neighborhood && (
              <aside className="graph-detail-state graph-detail-state--error" role="alert">
                <p>{neighborhoodError}</p>
                <button type="button" className="secondary-button" onClick={onRetryNeighborhood}>
                  {neighborhoodRestartRequired
                    ? '전체 연결 처음부터 다시 불러오기'
                    : '다음 연결 다시 불러오기'}
                </button>
              </aside>
            )}
            {!neighborhoodRestartRequired && neighborhood?.nextCursor && (
              <button
                type="button"
                className="secondary-button graph-neighborhood-more"
                disabled={interactionDisabled || neighborhoodLoadingMore}
                onClick={onLoadMoreNeighborhood}
              >
                {neighborhoodLoadingMore ? '다음 연결 불러오는 중…' : '연결 더 불러오기'}
              </button>
            )}
            {neighborhood?.browserTruncated && (
              <p className="graph-detail-state" role="status">
                브라우저 표시 상한 100개에 도달해 나머지 연결은 불러오지 않았습니다.
              </p>
            )}
          </>
        )}
      </section>
    </dialog>
  );
}

export function MemoTagGraph({
  projection,
  loading,
  error,
  selectedNode,
  selectionProjectionVersion,
  activeMemoNode,
  memoDetail,
  detailLoading,
  detailError,
  neighborhood,
  neighborhoodLoading,
  neighborhoodLoadingMore,
  neighborhoodError,
  neighborhoodRestartRequired,
  pinPending,
  pinError,
  interactionDisabled,
  onRetry,
  onSelectNode,
  onCloseDetail,
  onRetryDetail,
  onRetryNeighborhood,
  onLoadMoreNeighborhood,
  onOpenNeighborhoodMemo,
  onBackToNeighborhood,
  onSetPinned,
  onRetryPin,
}: Props) {
  const openerRef = useRef<HTMLButtonElement | null>(null);
  const focusRestoreCancelRef = useRef<(() => void) | null>(null);
  const openNodeIdRef = useRef<string | null>(null);
  const pendingUnlockFocusNodeIdRef = useRef<string | null>(null);
  const currentNode = selectedNodeForProjection(
    projection,
    selectedNode,
    loading,
    selectionProjectionVersion,
  );
  const { nodes, edges } = buildFlowElements(projection, currentNode?.id ?? null);
  const currentNodeId = currentNode?.id ?? null;

  useEffect(() => {
    if (currentNodeId) {
      focusRestoreCancelRef.current?.();
      focusRestoreCancelRef.current = null;
      openNodeIdRef.current = currentNodeId;
      pendingUnlockFocusNodeIdRef.current = null;
      return;
    }

    const closedNodeId = openNodeIdRef.current;
    if (!closedNodeId) return;
    openNodeIdRef.current = null;
    pendingUnlockFocusNodeIdRef.current = interactionDisabled ? closedNodeId : null;
    focusRestoreCancelRef.current = focusGraphNode(closedNodeId, openerRef.current);
  }, [currentNodeId, interactionDisabled]);

  useEffect(() => {
    if (interactionDisabled) return;
    const pendingNodeId = pendingUnlockFocusNodeIdRef.current;
    if (!pendingNodeId) return;
    pendingUnlockFocusNodeIdRef.current = null;
    if (
      !shouldResumeGraphFocus(
        document.activeElement,
        document.body,
        document.getElementById('graph-title'),
      )
    ) {
      focusRestoreCancelRef.current?.();
      focusRestoreCancelRef.current = null;
      return;
    }
    focusRestoreCancelRef.current?.();
    focusRestoreCancelRef.current = focusGraphNode(pendingNodeId, openerRef.current);
  }, [interactionDisabled]);

  useEffect(() => () => {
    focusRestoreCancelRef.current?.();
    focusRestoreCancelRef.current = null;
    openNodeIdRef.current = null;
    pendingUnlockFocusNodeIdRef.current = null;
  }, []);

  const activate = useCallback((
    id: string,
    kind: GraphNode['kind'],
    opener: HTMLButtonElement,
  ) => {
    const node = projection.nodes.find(
      (candidate) => candidate.id === id && candidate.kind === kind,
    );
    if (!node) return;
    focusRestoreCancelRef.current?.();
    focusRestoreCancelRef.current = null;
    pendingUnlockFocusNodeIdRef.current = null;
    openerRef.current = opener;
    onSelectNode(node);
  }, [onSelectNode, projection.nodes]);
  const interaction = useMemo(
    () => ({ disabled: interactionDisabled, activate }),
    [activate, interactionDisabled],
  );

  return (
    <section className="graph-section" aria-labelledby="graph-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">DERIVED VIEW</span>
          <h2 id="graph-title" tabIndex={-1}>메모와 태그</h2>
        </div>
        {projection.truncated && <span className="limit-badge">우선순위 기준 최대 100개</span>}
      </div>

      <div className="graph-canvas" aria-busy={loading}>
        {loading && nodes.length === 0 && (
          <div className="panel-state">그래프를 불러오는 중…</div>
        )}
        {!loading && error && nodes.length === 0 && (
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
        {nodes.length > 0 && (
          <>
            {(loading || error) && (
              <aside
                className={`graph-canvas__notice${error ? ' graph-canvas__notice--error' : ''}`}
                role={error ? 'alert' : 'status'}
              >
                <span>{error ?? '최신 그래프로 갱신하는 중…'}</span>
                {error && (
                  <button type="button" className="secondary-button" onClick={onRetry}>
                    다시 불러오기
                  </button>
                )}
              </aside>
            )}
            <GraphInteractionContext.Provider value={interaction}>
              <ReactFlow
                key={projection.projectionVersion}
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                fitView
                fitViewOptions={{ padding: 0.24, minZoom: 0.9, maxZoom: 1.1 }}
                minZoom={0.9}
                maxZoom={1.6}
                nodesConnectable={false}
                nodesDraggable={false}
                nodesFocusable={false}
                edgesFocusable={false}
                elementsSelectable={false}
                zoomOnDoubleClick={false}
                aria-label={`메모 ${nodes.filter((node) => node.data.kind === 'MEMO').length}개와 태그 ${nodes.filter((node) => node.data.kind === 'TAG').length}개의 관계 그래프`}
              >
                <Background color="#c9c5b9" gap={24} size={1} />
                <Controls showInteractive={false} position="bottom-right" />
              </ReactFlow>
            </GraphInteractionContext.Provider>
          </>
        )}
      </div>

      {currentNode && (
        <GraphNodeDetailDrawer
          node={currentNode}
          activeMemoNode={activeMemoNode}
          neighborhood={neighborhood}
          neighborhoodLoading={neighborhoodLoading}
          neighborhoodLoadingMore={neighborhoodLoadingMore}
          neighborhoodError={neighborhoodError}
          neighborhoodRestartRequired={neighborhoodRestartRequired}
          memoDetail={memoDetail}
          loading={detailLoading}
          error={detailError}
          pinPending={pinPending}
          pinError={pinError}
          interactionDisabled={interactionDisabled}
          onClose={onCloseDetail}
          onRetry={onRetryDetail}
          onRetryNeighborhood={onRetryNeighborhood}
          onLoadMoreNeighborhood={onLoadMoreNeighborhood}
          onOpenNeighborhoodMemo={onOpenNeighborhoodMemo}
          onBackToNeighborhood={onBackToNeighborhood}
          onSetPinned={onSetPinned}
          onRetryPin={onRetryPin}
        />
      )}
    </section>
  );
}
