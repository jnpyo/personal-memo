import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MemoSearchItem, MemoView } from '../../shared/api/types';
import {
  focusMemoSearchResult,
  memoSearchOverdueDisabled,
  MemoSearch,
  MemoSearchDetailDialog,
  memoSearchResultTitle,
} from './MemoSearch';

const item: MemoSearchItem = {
  memoId: '11111111-1111-4111-8111-111111111111',
  currentRevision: 2,
  canonicalRevision: 1,
  title: '운영체제 과제',
  preview: '현재 원문 preview',
  lifecycleStatus: 'TRASHED',
  canonicalTags: [{ id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: '운영체제' }],
  taskState: 'TODO',
  overdue: true,
  pinned: false,
  revisedAt: '2026-08-11T03:00:00Z',
  matchedFields: ['ALIAS'],
};

const memo: MemoView = {
  id: item.memoId,
  currentRevision: 3,
  content: '서버의 현재 원문',
  pinned: false,
  status: 'TRASHED',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-08-11T03:00:00Z',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MemoSearch', () => {
  it('renders an explicit private POST search form with accessible filters', () => {
    const markup = renderToStaticMarkup(<MemoSearch />);
    expect(markup).toContain('<section class="search-section"');
    expect(markup).toContain('메모 검색어');
    expect(markup).toContain('type="search"');
    expect(markup).toContain('maxLength="200"');
    expect(markup).toContain('autoComplete="off"');
    expect(markup).toContain('메모 상태');
    expect(markup).toContain('작업·수정일 필터');
    expect(markup).toContain('<option value="NONE">작업 없음</option>');
    expect(markup).toContain('aria-live="polite"');
    expect(markup).toContain('페이지 20 · 최대 100');
  });

  it('disables overdue-only for every explicit non-TODO task filter', () => {
    expect(memoSearchOverdueDisabled('')).toBe(false);
    expect(memoSearchOverdueDisabled('TODO')).toBe(false);
    expect(memoSearchOverdueDisabled('DONE')).toBe(true);
    expect(memoSearchOverdueDisabled('CANCELLED')).toBe(true);
    expect(memoSearchOverdueDisabled('NONE')).toBe(true);
  });

  it('renders the shared current raw detail without graph actions for trash', () => {
    const markup = renderToStaticMarkup(
      <MemoSearchDetailDialog
        item={item}
        memo={memo}
        loading={false}
        error={null}
        onClose={() => undefined}
        onRetry={() => undefined}
      />,
    );
    expect(markup).toContain('검색 결과를 그래프에 추가하지 않고');
    expect(markup).toContain('서버의 현재 원문');
    expect(markup).toContain('검색 후 원문이 변경되었습니다');
    expect(markup).toContain('#운영체제');
    expect(markup).toContain('최대 8개 표시');
    expect(markup).toContain('휴지통 메모에서는 그래프 연결이나 고정 변경을 제공하지 않습니다');
    expect(markup).not.toContain('홈 그래프에 고정');
  });

  it('uses a safe nonempty result title fallback', () => {
    expect(memoSearchResultTitle(item)).toBe('운영체제 과제');
    expect(memoSearchResultTitle({ ...item, title: null, preview: '\n본문' })).toBe('제목 없는 메모');
    expect(memoSearchResultTitle({ ...item, title: null, preview: '첫 줄\n둘째 줄' })).toBe('첫 줄');
  });

  it('restores focus to a remounted result and can cancel pending restoration', () => {
    const frames: FrameRequestCallback[] = [];
    const target = {
      isConnected: true,
      hasAttribute: () => false,
      focus: vi.fn(),
      scrollIntoView: vi.fn(),
    } as unknown as HTMLElement;
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
    vi.stubGlobal('document', { getElementById: () => target });

    focusMemoSearchResult(item.memoId, null, fallback);
    frames.shift()?.(0);
    expect(target.focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(target.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });

    const cancel = focusMemoSearchResult(item.memoId, null, fallback);
    cancel();
    frames.shift()?.(16);
    expect(target.focus).toHaveBeenCalledTimes(1);
  });
});
