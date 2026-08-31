import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { PwaUpdatePrompt } from './PwaUpdatePrompt';

const callbacks = { onUpdate: vi.fn(), onDismiss: vi.fn() };

describe('PWA update prompt', () => {
  it('does not interrupt the page when no update is waiting', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available={false}
        updating={false}
        hasUnsavedChanges={false}
        operationPending={false}
        error={null}
      />,
    );

    expect(markup).toBe('');
  });

  it('asks the user to finish drafts before choosing an update', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available
        updating={false}
        hasUnsavedChanges={false}
        operationPending={false}
        error={null}
      />,
    );

    expect(markup).toContain('작성 중인 입력을 확인한 뒤');
    expect(markup).toContain('새 화면 적용');
    expect(markup).toContain('나중에');
  });

  it('locks both choices while the selected update is activating', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available
        updating
        hasUnsavedChanges={false}
        operationPending={false}
        error={null}
      />,
    );

    expect(markup).toContain('화면 업데이트 중…');
    expect(markup.match(/disabled=""/g)).toHaveLength(2);
  });

  it('keeps the prompt available for retry after an update failure', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available
        updating={false}
        hasUnsavedChanges={false}
        operationPending={false}
        error="UPDATE_FAILED"
      />,
    );

    expect(markup).toContain('업데이트하지 못했습니다');
    expect(markup).toContain('새 화면 적용');
  });

  it('blocks activation while proposal edits exist only in memory', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available
        updating={false}
        hasUnsavedChanges
        operationPending={false}
        error={null}
      />,
    );

    expect(markup).toContain('반영되지 않은 편집 내용');
    expect(markup).toContain('<button type="button" class="approve-button" disabled=""');
  });

  it('waits for an in-flight server operation even when no local edit is dirty', () => {
    const markup = renderToStaticMarkup(
      <PwaUpdatePrompt
        {...callbacks}
        available
        updating={false}
        hasUnsavedChanges={false}
        operationPending
        error={null}
      />,
    );

    expect(markup).toContain('진행 중인 작업');
    expect(markup).toContain('<button type="button" class="approve-button" disabled=""');
  });
});
