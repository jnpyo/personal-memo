import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GraphNode, MemoView } from '../../shared/api/types';
import type { MemoGraphNodeData } from './graphModel';
import {
  focusGraphNode,
  GraphNodeButton,
  GraphNodeDetailDrawer,
  shouldResumeGraphFocus,
} from './MemoTagGraph';

const memoNode: GraphNode = {
  id: 'memo:11111111-1111-1111-1111-111111111111',
  kind: 'MEMO',
  label: '운영체제 과제',
  pinned: false,
  memoType: 'TASK',
  taskState: 'TODO',
  overdue: true,
};

const memoDetail: MemoView = {
  id: '11111111-1111-1111-1111-111111111111',
  currentRevision: 3,
  content: '줄바꿈을\n보존한 현재 원문',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'APPLIED',
  createdAt: '2026-08-11T00:00:00Z',
};

const noOp = () => undefined;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('graph node controls and detail drawer', () => {
  it('renders every graph node as a native keyboard and touch button', () => {
    const data: MemoGraphNodeData = {
      label: memoNode.label,
      kind: memoNode.kind,
      detail: 'TASK · TODO',
      overdue: true,
      pinned: false,
      emphasis: 'SELECTED',
    };

    const markup = renderToStaticMarkup(<GraphNodeButton id={memoNode.id} data={data} />);

    expect(markup).toContain('<button type="button"');
    expect(markup).toContain(`data-graph-node-id="${memoNode.id}"`);
    expect(markup).toContain('aria-pressed="true"');
    expect(markup).toContain('상세 열기');
  });

  it('shows current raw content, revision, task metadata, visible tags, and pin action', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        node={memoNode}
        neighbors={[
          { id: 'tag:1', kind: 'TAG', label: '운영체제', pinned: false },
        ]}
        truncated={false}
        memoDetail={memoDetail}
        loading={false}
        error={null}
        pinPending={false}
        pinError={null}
        interactionDisabled={false}
        onClose={noOp}
        onRetry={noOp}
        onSetPinned={noOp}
        onRetryPin={noOp}
      />,
    );

    expect(markup).toContain('줄바꿈을\n보존한 현재 원문');
    expect(markup).toContain('revision 3');
    expect(markup).toContain('할 일 상태 미완료');
    expect(markup).toContain('기한 초과');
    expect(markup).toContain('#운영체제');
    expect(markup).toContain('홈 그래프에 고정');
    expect(markup).toContain('현재 홈 그래프 스냅샷');
  });

  it('sorts and bounds each visible neighbor kind to twenty entries with an explicit notice', () => {
    const neighbors: GraphNode[] = Array.from({ length: 21 }, (_, index) => ({
      id: `tag:${index.toString().padStart(2, '0')}`,
      kind: 'TAG',
      label: `태그 ${index.toString().padStart(2, '0')}`,
      pinned: false,
    }));
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        node={memoNode}
        neighbors={neighbors.reverse()}
        truncated
        memoDetail={memoDetail}
        loading={false}
        error={null}
        pinPending={false}
        pinError={null}
        interactionDisabled={false}
        onClose={noOp}
        onRetry={noOp}
        onSetPinned={noOp}
        onRetryPin={noOp}
      />,
    );

    expect(markup).toContain('#태그 00');
    expect(markup).toContain('#태그 19');
    expect(markup).not.toContain('#태그 20');
    expect(markup).toContain('연결을 최대 20개까지 표시합니다');
    expect(markup).toContain('홈 그래프 최대 100개 제한');
  });

  it('does not expose a pin mutation for tag detail', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        node={{ id: 'tag:1', kind: 'TAG', label: '운영체제', pinned: false }}
        neighbors={[]}
        truncated={false}
        memoDetail={null}
        loading={false}
        error={null}
        pinPending={false}
        pinError={null}
        interactionDisabled={false}
        onClose={noOp}
        onRetry={noOp}
        onSetPinned={noOp}
        onRetryPin={noOp}
      />,
    );

    expect(markup).toContain('현재 홈 그래프에 보이는 메모 연결이 없습니다');
    expect(markup).not.toContain('홈 그래프에 고정');
    expect(markup).not.toContain('홈 그래프 고정 해제');
  });

  it('keeps pin retry disabled while another owner operation is locked', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        node={memoNode}
        neighbors={[]}
        truncated={false}
        memoDetail={memoDetail}
        loading={false}
        error={null}
        pinPending={false}
        pinError="고정 변경 실패"
        interactionDisabled
        onClose={noOp}
        onRetry={noOp}
        onSetPinned={noOp}
        onRetryPin={noOp}
      />,
    );

    expect(markup).toContain('고정 변경 실패');
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>고정 변경 다시 시도<\/button>/);
  });

  it('renders explicit memo loading, error/retry, and empty states', () => {
    const baseProps = {
      node: memoNode,
      neighbors: [],
      truncated: false,
      memoDetail: null,
      pinPending: false,
      pinError: null,
      interactionDisabled: false,
      onClose: noOp,
      onRetry: noOp,
      onSetPinned: noOp,
      onRetryPin: noOp,
    };
    const loading = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...baseProps} loading error={null} />,
    );
    const failed = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...baseProps} loading={false} error="원문 조회 실패" />,
    );
    const empty = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...baseProps} loading={false} error={null} />,
    );

    expect(loading).toContain('최신 원문을 불러오는 중');
    expect(failed).toContain('원문 조회 실패');
    expect(failed).toContain('최신 원문 다시 불러오기');
    expect(empty).toContain('표시할 최신 원문이 없습니다');
  });
});

describe('graph drawer focus restoration', () => {
  it('does not resume a delayed opener restore after the user moved focus elsewhere', () => {
    const body = {} as HTMLElement;
    const heading = {} as HTMLElement;
    const userTarget = {} as HTMLElement;

    expect(shouldResumeGraphFocus(null, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(body, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(heading, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(userTarget, body, heading)).toBe(false);
  });

  it('cancels a pending focus attempt when its workspace unmounts', () => {
    const frames: FrameRequestCallback[] = [];
    const cancelAnimationFrame = vi.fn();
    const heading = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        frames.push(callback);
        return 7;
      },
      cancelAnimationFrame,
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [],
      getElementById: () => heading,
    });

    const cancel = focusGraphNode('memo:removed', null);
    cancel();
    frames.shift()?.(0);

    expect(cancelAnimationFrame).toHaveBeenCalledWith(7);
    expect(heading.focus).not.toHaveBeenCalled();
  });

  it('focuses the remounted opener once a pending refresh enables it', () => {
    const frames: FrameRequestCallback[] = [];
    const remountedOpener = {
      dataset: { graphNodeId: memoNode.id },
      disabled: true,
      focus: vi.fn(),
    };
    const heading = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        frames.push(callback);
        return frames.length;
      },
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [remountedOpener],
      getElementById: () => heading,
      activeElement: heading,
      body: {},
    });

    focusGraphNode(memoNode.id, null);
    frames.shift()?.(0);
    expect(heading.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(remountedOpener.focus).not.toHaveBeenCalled();

    remountedOpener.disabled = false;
    frames.shift()?.(16);
    expect(remountedOpener.focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('focuses the first remaining graph node when the refreshed projection removed the opener', () => {
    const firstRemaining = {
      dataset: { graphNodeId: 'memo:remaining' },
      disabled: false,
      focus: vi.fn(),
    };
    const heading = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        callback(0);
        return 1;
      },
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [firstRemaining],
      getElementById: () => heading,
    });

    focusGraphNode(
      'memo:removed',
      { isConnected: false, focus: vi.fn() } as unknown as HTMLButtonElement,
    );

    expect(firstRemaining.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(heading.focus).not.toHaveBeenCalled();
  });

  it('falls back to the graph heading when no graph nodes remain', () => {
    const heading = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        callback(0);
        return 1;
      },
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [],
      getElementById: () => heading,
    });

    focusGraphNode('memo:removed', null);

    expect(heading.focus).toHaveBeenCalledWith({ preventScroll: true });
  });
});
