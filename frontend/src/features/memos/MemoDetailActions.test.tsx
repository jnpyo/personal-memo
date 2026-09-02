import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { MemoView } from '../../shared/api/types';
import { MemoDetailActions } from './MemoDetailActions';

const activeMemo: MemoView = {
  id: 'memo-active',
  currentRevision: 2,
  content: '공개 synthetic 원문',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'SUCCEEDED',
  createdAt: '2026-09-02T01:00:00Z',
};

function renderActions(memo: MemoView, analysisBlocked = false) {
  return renderToStaticMarkup(
    <MemoDetailActions
      memo={memo}
      busy={false}
      pendingScope={null}
      analysisBlocked={analysisBlocked}
      onUpdate={vi.fn(async () => true)}
      onTrash={vi.fn()}
      onRestore={vi.fn()}
      onAnalyze={vi.fn()}
      onDirtyChange={vi.fn()}
    />,
  );
}

describe('MemoDetailActions', () => {
  it('keeps active memo management available from a detail surface', () => {
    const markup = renderActions(activeMemo);

    expect(markup).toContain('aria-label="메모 관리"');
    expect(markup).toContain('정리하기');
    expect(markup).toContain('>수정</button>');
    expect(markup).toContain('>휴지통</button>');
  });

  it('keeps restore available for a trashed memo found from graph search', () => {
    const markup = renderActions({ ...activeMemo, id: 'memo-trashed', status: 'TRASHED' });

    expect(markup).toContain('>복원</button>');
    expect(markup).not.toContain('정리하기');
    expect(markup).not.toContain('>휴지통</button>');
  });

  it('disables proposal generation while another review blocks analysis', () => {
    const markup = renderActions(activeMemo, true);

    expect(markup).toContain('title="열려 있는 제안을 먼저 처리해 주세요."');
    expect(markup).toContain('disabled=""');
  });
});
