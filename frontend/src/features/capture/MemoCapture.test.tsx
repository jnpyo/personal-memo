import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { MemoCapture } from './MemoCapture';

describe('memo capture offline behavior', () => {
  it('keeps the textarea editable while server submission is disabled', () => {
    const markup = renderToStaticMarkup(
      <MemoCapture
        content="오프라인에서도 보존할 원문"
        disabled={false}
        submissionDisabled
        submitting={false}
        rawOnly={false}
        prompt="계정 전용 임시 초안으로 저장됩니다."
        onContentChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(markup).toContain('<textarea');
    expect(markup).not.toContain('<textarea disabled=""');
    expect(markup).toContain('<button type="submit" disabled=""');
    expect(markup).toContain('오프라인에서도 보존할 원문');
  });
});
