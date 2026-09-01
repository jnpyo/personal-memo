import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { GraphNode, MemoView } from '../../shared/api/types';
import type { GraphNeighborhoodCollection } from './graphNeighborhoodModel';
import type { MemoGraphNodeData } from './graphModel';
import {
  focusGraphNode,
  focusNeighborhoodNode,
  GraphNodeButton,
  GraphNodeDetailDrawer,
  shouldMoveGraphDetailFocus,
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

const tagNode: GraphNode = {
  id: 'tag:22222222-2222-2222-2222-222222222222',
  kind: 'TAG',
  label: '운영체제',
  pinned: false,
  memoType: null,
  taskState: null,
  overdue: false,
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

function collection(
  center: GraphNode,
  neighbors: GraphNode[] = [],
  overrides: Partial<GraphNeighborhoodCollection> = {},
): GraphNeighborhoodCollection {
  return {
    center,
    neighbors,
    edges: [],
    truncated: false,
    nextCursor: null,
    pagesLoaded: 1,
    browserTruncated: false,
    loadedCursors: ['__FIRST_PAGE__'],
    ...overrides,
  };
}

function drawerProps(overrides: Partial<Parameters<typeof GraphNodeDetailDrawer>[0]> = {}) {
  return {
    node: memoNode,
    activeMemoNode: memoNode,
    neighborhood: collection(memoNode, [tagNode]),
    neighborhoodLoading: false,
    neighborhoodLoadingMore: false,
    neighborhoodError: null,
    neighborhoodRestartRequired: false,
    memoDetail,
    loading: false,
    error: null,
    pinPending: false,
    pinError: null,
    interactionDisabled: false,
    onClose: noOp,
    onRetry: noOp,
    onRetryNeighborhood: noOp,
    onLoadMoreNeighborhood: noOp,
    onOpenNeighborhoodMemo: noOp,
    onBackToNeighborhood: noOp,
    onSetPinned: noOp,
    onRetryPin: noOp,
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('graph node controls and full-corpus detail drawer', () => {
  it('renders every graph node as a native keyboard and touch button', () => {
    const data: MemoGraphNodeData = {
      label: memoNode.label,
      kind: memoNode.kind,
      detail: '할 일 · 기한 지남',
      tone: 'task',
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

  it('shows current raw content, revision, task metadata, full tags, and pin for a root memo', () => {
    const markup = renderToStaticMarkup(<GraphNodeDetailDrawer {...drawerProps()} />);

    expect(markup).toContain('줄바꿈을\n보존한 현재 원문');
    expect(markup).toContain('revision 3');
    expect(markup).toContain('할 일 상태 미완료');
    expect(markup).toContain('기한 초과');
    expect(markup).toContain('#운영체제');
    expect(markup).toContain('홈 그래프에 고정');
    expect(markup).toContain('전체 1단계 태그 연결');
    expect(markup).not.toContain('연결로 돌아가기');
  });

  it('shows Back only for an off-home memo opened from a tag neighborhood', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          node: tagNode,
          activeMemoNode: memoNode,
          neighborhood: collection(tagNode, [memoNode]),
        })}
      />,
    );

    expect(markup).toContain('← 운영체제 연결로 돌아가기');
    expect(markup).toContain('홈 그래프에 추가하지 않고');
  });

  it('renders off-home memo neighbors as native controls and exposes bounded load-more state', () => {
    const oldMemo = {
      ...memoNode,
      id: 'memo:33333333-3333-3333-3333-333333333333',
      label: '오래된 xv6 메모',
      overdue: false,
    };
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          node: tagNode,
          activeMemoNode: null,
          memoDetail: null,
          neighborhood: collection(tagNode, [oldMemo], {
            truncated: true,
            nextCursor: 'page-2',
          }),
        })}
      />,
    );

    expect(markup).toContain('FULL CORPUS NEIGHBORHOOD');
    expect(markup).toContain('오래된 xv6 메모');
    expect(markup).toContain(`data-neighborhood-node-id="${oldMemo.id}"`);
    expect(markup).toContain('현재 원문 열기');
    expect(markup).toContain('연결 더 불러오기');
    expect(markup).toContain('브라우저에는 최대 100개');
  });

  it('shows the browser-cap notice without keeping a load-more control', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          node: tagNode,
          activeMemoNode: null,
          memoDetail: null,
          neighborhood: collection(tagNode, [memoNode], {
            truncated: true,
            browserTruncated: true,
          }),
        })}
      />,
    );

    expect(markup).toContain('브라우저 표시 상한 100개에 도달');
    expect(markup).not.toContain('연결 더 불러오기');
  });

  it('does not expose a pin mutation in tag neighborhood view', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          node: tagNode,
          activeMemoNode: null,
          neighborhood: collection(tagNode),
          memoDetail: null,
        })}
      />,
    );

    expect(markup).toContain('전체 메모에서 직접 연결된 항목이 없습니다');
    expect(markup).not.toContain('홈 그래프에 고정');
    expect(markup).not.toContain('홈 그래프 고정 해제');
  });

  it('keeps pin retry disabled while another owner operation is locked', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ pinError: '고정 변경 실패', interactionDisabled: true })}
      />,
    );

    expect(markup).toContain('고정 변경 실패');
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>고정 변경 다시 시도<\/button>/);
  });

  it('renders explicit raw memo loading, error/retry, and empty states', () => {
    const loading = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...drawerProps({ memoDetail: null, loading: true })} />,
    );
    const failed = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ memoDetail: null, error: '원문 조회 실패' })}
      />,
    );
    const empty = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...drawerProps({ memoDetail: null })} />,
    );

    expect(loading).toContain('최신 원문을 불러오는 중');
    expect(failed).toContain('원문 조회 실패');
    expect(failed).toContain('최신 원문 다시 불러오기');
    expect(empty).toContain('표시할 최신 원문이 없습니다');
  });

  it('renders neighborhood loading, error/retry, and empty states', () => {
    const base = {
      node: tagNode,
      activeMemoNode: null,
      memoDetail: null,
      neighborhood: null,
    };
    const loading = renderToStaticMarkup(
      <GraphNodeDetailDrawer {...drawerProps({ ...base, neighborhoodLoading: true })} />,
    );
    const failed = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ ...base, neighborhoodError: '전체 연결 조회 실패' })}
      />,
    );
    const empty = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ ...base, neighborhood: collection(tagNode) })}
      />,
    );

    expect(loading).toContain('전체 연결을 불러오는 중');
    expect(failed).toContain('전체 연결 조회 실패');
    expect(failed).toContain('전체 연결 다시 불러오기');
    expect(empty).toContain('전체 메모에서 직접 연결된 항목이 없습니다');
  });

  it('keeps root memo raw detail open while neighborhood loading, retry, and pagination render inline', () => {
    const loading = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ neighborhood: null, neighborhoodLoading: true })}
      />,
    );
    const failed = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({ neighborhood: null, neighborhoodError: '태그 연결 조회 실패' })}
      />,
    );
    const paged = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          neighborhood: collection(memoNode, [tagNode], {
            truncated: true,
            nextCursor: 'page-2',
          }),
        })}
      />,
    );
    const rawFailedButNeighborhoodLoaded = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          memoDetail: null,
          error: '원문 조회 실패',
          neighborhood: collection(memoNode, [tagNode]),
        })}
      />,
    );

    expect(loading).toContain('줄바꿈을\n보존한 현재 원문');
    expect(loading).toContain('전체 연결을 불러오는 중');
    expect(loading).not.toContain('불러온 연결에 태그가 없습니다');
    expect(failed).toContain('태그 연결 조회 실패');
    expect(failed).toContain('전체 연결 다시 불러오기');
    expect(paged).toContain('연결 더 불러오기');
    expect(paged).not.toContain('연결로 돌아가기');
    expect(rawFailedButNeighborhoodLoaded).toContain('원문 조회 실패');
    expect(rawFailedButNeighborhoodLoaded).toContain('#운영체제');
    expect(rawFailedButNeighborhoodLoaded).toContain('1개 불러옴');
  });

  it('offers a first-page restart while retaining an explicitly stale accumulated list', () => {
    const markup = renderToStaticMarkup(
      <GraphNodeDetailDrawer
        {...drawerProps({
          node: tagNode,
          activeMemoNode: null,
          memoDetail: null,
          neighborhood: collection(tagNode, [memoNode], {
            truncated: true,
            nextCursor: 'expired-page-2',
          }),
          neighborhoodError: '현재 목록은 이전 페이지 기준일 수 있습니다.',
          neighborhoodRestartRequired: true,
        })}
      />,
    );

    expect(markup).toContain('현재 목록은 이전 페이지 기준일 수 있습니다.');
    expect(markup).toContain('전체 연결 처음부터 다시 불러오기');
    expect(markup).not.toContain('다음 연결 다시 불러오기');
    expect(markup).not.toContain('연결 더 불러오기');
  });
});

describe('graph drawer focus restoration', () => {
  it('does not schedule a detail-heading focus move when only pin busy state changes', () => {
    expect(shouldMoveGraphDetailFocus(memoNode.id, memoNode.id)).toBe(false);
    expect(shouldMoveGraphDetailFocus(memoNode.id, null)).toBe(true);
    expect(shouldMoveGraphDetailFocus(null, memoNode.id)).toBe(true);
  });

  it('does not resume a delayed opener restore after the user moved focus elsewhere', () => {
    const body = {} as HTMLElement;
    const heading = {} as HTMLElement;
    const userTarget = {} as HTMLElement;

    expect(shouldResumeGraphFocus(null, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(body, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(heading, body, heading)).toBe(true);
    expect(shouldResumeGraphFocus(userTarget, body, heading)).toBe(false);
  });

  it('cancels a pending graph focus attempt when its workspace unmounts', () => {
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

  it('focuses the remounted graph opener once a pending refresh enables it', () => {
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

  it('focuses the first remaining graph node when refresh removed the opener', () => {
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

    focusGraphNode('memo:removed', null);

    expect(firstRemaining.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(heading.focus).not.toHaveBeenCalled();
  });

  it('falls back to the visible graph canvas when no graph nodes remain', () => {
    const graphCanvas = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        callback(0);
        return 1;
      },
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [],
      getElementById: () => graphCanvas,
    });

    focusGraphNode('memo:removed', null);

    expect(graphCanvas.focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('restores focus to a remounted off-home memo button after Back', () => {
    const frames: FrameRequestCallback[] = [];
    const remounted = {
      dataset: { neighborhoodNodeId: memoNode.id },
      disabled: false,
      focus: vi.fn(),
    };
    const heading = { focus: vi.fn() };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        frames.push(callback);
        return 11;
      },
      cancelAnimationFrame: vi.fn(),
    });
    vi.stubGlobal('document', {
      querySelectorAll: () => [remounted],
      getElementById: () => heading,
      activeElement: heading,
      body: {},
    });

    focusNeighborhoodNode(memoNode.id, null);
    frames.shift()?.(0);

    expect(remounted.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(heading.focus).not.toHaveBeenCalled();
  });

  it('does not steal focus if the user moves before an off-home opener remounts', () => {
    const frames: FrameRequestCallback[] = [];
    const heading = { focus: vi.fn() };
    const userControl = {};
    const documentStub = {
      querySelectorAll: () => [],
      getElementById: () => heading,
      activeElement: heading as unknown,
      body: {},
    };
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        frames.push(callback);
        return frames.length;
      },
      cancelAnimationFrame: vi.fn(),
    });
    vi.stubGlobal('document', documentStub);

    focusNeighborhoodNode(memoNode.id, null);
    frames.shift()?.(0);
    documentStub.activeElement = userControl;
    frames.shift()?.(16);

    expect(heading.focus).toHaveBeenCalledTimes(1);
  });
});
