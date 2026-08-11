import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { MemoView } from '../../shared/api/types';
import { CurrentMemoDetail } from './CurrentMemoDetail';

const memo: MemoView = {
  id: 'memo-1',
  currentRevision: 2,
  content: '현재 원문',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-08-11T00:00:00Z',
};

describe('CurrentMemoDetail', () => {
  it('renders current raw content and reports a search snapshot revision change', () => {
    const markup = renderToStaticMarkup(
      <CurrentMemoDetail
        memo={memo}
        loading={false}
        error={null}
        onRetry={() => undefined}
        headingId="raw-title"
        expectedRevision={1}
      />,
    );
    expect(markup).toContain('검색 후 원문이 변경되었습니다');
    expect(markup).toContain('revision 2');
    expect(markup).toContain('<pre aria-label="현재 원문">현재 원문</pre>');
  });

  it('keeps loading and retry errors accessible', () => {
    expect(renderToStaticMarkup(
      <CurrentMemoDetail
        memo={null}
        loading
        error={null}
        onRetry={() => undefined}
        headingId="raw-title"
      />,
    )).toContain('role="status"');
    const error = renderToStaticMarkup(
      <CurrentMemoDetail
        memo={null}
        loading={false}
        error="연결 오류"
        onRetry={() => undefined}
        headingId="raw-title"
      />,
    );
    expect(error).toContain('role="alert"');
    expect(error).toContain('최신 원문 다시 불러오기');
  });
});
