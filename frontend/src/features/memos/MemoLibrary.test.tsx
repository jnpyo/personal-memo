import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { MemoView } from '../../shared/api/types';
import { MemoLibrary } from './MemoLibrary';

const activeMemo: MemoView = {
  id: 'memo-active',
  currentRevision: 3,
  content: '6시 디스코드 접속하기',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'SUCCEEDED',
  createdAt: '2026-09-01T09:00:00Z',
};

const trashedMemo: MemoView = {
  ...activeMemo,
  id: 'memo-trashed',
  content: '지난 메모',
  status: 'TRASHED',
};

function renderLibrary(activeMemos: MemoView[], trashedMemos: MemoView[]) {
  return renderToStaticMarkup(
    <MemoLibrary
      activeMemos={activeMemos}
      trashedMemos={trashedMemos}
      loading={false}
      error={null}
      busy={false}
      pendingScope={null}
      analysisBlocked={false}
      onRetry={vi.fn()}
      onUpdate={vi.fn(async () => true)}
      onTrash={vi.fn()}
      onRestore={vi.fn()}
      onAnalyze={vi.fn()}
      onDirtyChange={vi.fn()}
    />,
  );
}

describe('MemoLibrary', () => {
  it('shows a concise active memo card without revision or analysis status details', () => {
    const markup = renderLibrary([activeMemo], []);

    expect(markup).toContain('모든 메모');
    expect(markup).toContain('6시 디스코드 접속하기');
    expect(markup).toContain('정리하기');
    expect(markup).toContain('>수정</button>');
    expect(markup).toContain('>휴지통</button>');
    expect(markup).not.toContain('revision');
    expect(markup).not.toContain('AI');
    expect(markup).not.toContain('analysis-badge');
    expect(markup).not.toContain('최대 50개');
  });

  it('keeps the active and trash filter counts compact', () => {
    const markup = renderLibrary([], [trashedMemo]);

    expect(markup).toContain('role="tab" aria-selected="true">활성 <span>0</span>');
    expect(markup).toContain('role="tab" aria-selected="false">휴지통 <span>1</span>');
    expect(markup).toContain('아직 저장한 메모가 없습니다.');
  });
});
