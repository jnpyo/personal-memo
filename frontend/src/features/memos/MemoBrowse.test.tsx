import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MemoView } from '../../shared/api/types';
import type { MemoDetailActionsConfig } from './MemoDetailActions';
import {
  focusMemoBrowseResult,
  MemoBrowse,
  MemoBrowseDetailDialog,
  memoBrowsePreview,
  memoBrowseTitle,
  saveCurrentMemoBrowseDetail,
} from './MemoBrowse';

const memo: MemoView = {
  id: '11111111-1111-4111-8111-111111111111',
  currentRevision: 2,
  content: '공개 synthetic 원문\n아직 정리하지 않은 메모',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-09-05T01:00:00Z',
};

const actions: MemoDetailActionsConfig = {
  busy: false,
  pendingScope: null,
  analysisBlocked: false,
  onUpdate: async () => true,
  onTrash: () => undefined,
  onRestore: () => undefined,
  onAnalyze: () => undefined,
  onDirtyChange: () => undefined,
};

afterEach(() => vi.unstubAllGlobals());

describe('MemoBrowse', () => {
  it('makes raw-only memos available without a search query and states its recent bound', () => {
    const markup = renderToStaticMarkup(
      <MemoBrowse
        activeMemos={[memo]}
        trashedMemos={[]}
        loading={false}
        error={null}
        onRetry={() => undefined}
        memoActions={actions}
      />,
    );
    expect(markup).toContain('공개 synthetic 원문');
    expect(markup).toContain('아직 정리하지 않은 메모');
    expect(markup).toContain('상태별 최근 50개');
    expect(markup).toContain('오래된 메모는 아래에서 검색');
    expect(markup).toContain('aria-label="최근 메모 상태"');
    expect(markup).toContain('>휴지통</button>');
    expect(markup).not.toContain('모든 메모');
    expect(markup).not.toContain('전체 메모');
    expect(markup).not.toContain('<form');
  });

  it('never renders more than its advertised 50 entries', () => {
    const markup = renderToStaticMarkup(
      <MemoBrowse
        activeMemos={Array.from({ length: 51 }, (_, index) => ({
          ...memo,
          id: `memo-${index}`,
          content: `synthetic ${index}`,
        }))}
        trashedMemos={[]}
        loading={false}
        error={null}
        onRetry={() => undefined}
        memoActions={actions}
      />,
    );
    expect(markup.match(/class="memo-search-result memo-browse-result"/g)).toHaveLength(50);
    expect(markup).not.toContain('memo-browse-result-memo-50');
  });

  it('bounds raw display text without splitting a supplementary character', () => {
    expect(memoBrowseTitle({ content: '\n  첫 줄  \n둘째 줄' })).toBe('첫 줄');
    const longText = '😀'.repeat(300);
    expect(Array.from(memoBrowseTitle({ content: longText }))).toHaveLength(80);
    expect(Array.from(memoBrowsePreview({ content: longText }))).toHaveLength(240);
    expect(memoBrowsePreview({ content: longText })).toBe(`${'😀'.repeat(239)}…`);
  });

  it.each([
    { loading: true, error: null },
    { loading: false, error: '최신 원문을 불러오지 못했습니다.' },
  ])('retains the action surface during a same-memo refresh: %j', ({ loading, error }) => {
    const markup = renderToStaticMarkup(
      <MemoBrowseDetailDialog
        selectedMemo={memo}
        memo={memo}
        loading={loading}
        error={error}
        onClose={() => undefined}
        onRetry={() => undefined}
        memoActions={actions}
      />,
    );
    expect(markup).toContain('memo-detail-actions');
    if (loading) expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>수정<\/button>/);
    if (error) {
      expect(markup).toContain(error);
      expect(markup).toContain('최신 원문 다시 불러오기');
    }
  });

  it('shows restore from the current trashed state without invented search metadata', () => {
    const markup = renderToStaticMarkup(
      <MemoBrowseDetailDialog
        selectedMemo={memo}
        memo={{ ...memo, status: 'TRASHED' }}
        loading={false}
        error={null}
        onClose={() => undefined}
        onRetry={() => undefined}
        memoActions={actions}
      />,
    );
    expect(markup).toContain('휴지통 메모');
    expect(markup).toContain('>복원</button>');
    expect(markup).not.toContain('정리하기');
    expect(markup).not.toContain('canonical');
    expect(markup).not.toContain('검색 시점');
  });

  it('rejects a detail belonging to another memo before exposing its content or actions', () => {
    const markup = renderToStaticMarkup(
      <MemoBrowseDetailDialog
        selectedMemo={memo}
        memo={{ ...memo, id: '22222222-2222-4222-8222-222222222222', content: 'mismatched synthetic' }}
        loading={false}
        error={null}
        onClose={() => undefined}
        onRetry={() => undefined}
        memoActions={actions}
      />,
    );
    expect(markup).toContain('선택한 메모와 상세 응답이 일치하지 않습니다');
    expect(markup).not.toContain('mismatched synthetic');
    expect(markup).not.toContain('memo-detail-actions');
  });

  it('does not start a save or retry after the original detail becomes stale', async () => {
    const save = vi.fn(async () => true);
    const reload = vi.fn();
    expect(await saveCurrentMemoBrowseDetail(save, () => false, reload)).toBe(false);
    expect(save).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });

  it('does not reload or clear another editor after a delayed save resolves', async () => {
    let current = true;
    let resolveSave!: (saved: boolean) => void;
    const save = new Promise<boolean>((resolve) => { resolveSave = resolve; });
    const reload = vi.fn();
    const pending = saveCurrentMemoBrowseDetail(() => save, () => current, reload);
    current = false;
    resolveSave(true);
    expect(await pending).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('refreshes only a successful save for the still-selected detail', async () => {
    const reload = vi.fn();
    expect(await saveCurrentMemoBrowseDetail(async () => false, () => true, reload)).toBe(false);
    expect(reload).not.toHaveBeenCalled();
    expect(await saveCurrentMemoBrowseDetail(async () => true, () => true, reload)).toBe(true);
    expect(reload).toHaveBeenCalledOnce();
  });

  it('restores focus to the current filter when its result was removed and cancels stale restoration', () => {
    const frames: FrameRequestCallback[] = [];
    const fallback = {
      isConnected: true,
      hasAttribute: () => false,
      focus: vi.fn(),
    } as unknown as HTMLElement;
    vi.stubGlobal('window', {
      requestAnimationFrame: (callback: FrameRequestCallback) => {
        frames.push(callback);
        return frames.length;
      },
      cancelAnimationFrame: vi.fn(),
    });
    vi.stubGlobal('document', { getElementById: () => null });
    focusMemoBrowseResult(memo.id, null, fallback);
    frames.shift()?.(0);
    expect(fallback.focus).toHaveBeenCalledWith({ preventScroll: true });
    const cancel = focusMemoBrowseResult(memo.id, null, fallback);
    cancel();
    frames.shift()?.(16);
    expect(fallback.focus).toHaveBeenCalledOnce();
  });
});
