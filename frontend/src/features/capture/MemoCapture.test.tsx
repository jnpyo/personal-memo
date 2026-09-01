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
    expect(markup).toContain('메모 내용');
    expect(markup).toContain('계정 전용 임시 초안으로 저장됩니다.');
    expect(markup).toContain('placeholder="무슨 생각이 떠올랐나요?"');
    expect(markup).toContain('>저장</button>');
    expect(markup).not.toContain('원문만 저장');
    expect(markup).not.toContain('제안 분석');
  });

  it('keeps the status prompt conditional and uses the same saving label in raw-only mode', () => {
    const markup = renderToStaticMarkup(
      <MemoCapture
        content="떠오른 생각"
        disabled={false}
        submissionDisabled={false}
        submitting
        rawOnly
        prompt=""
        onContentChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(markup).not.toContain('class="capture-prompt"');
    expect(markup).toContain('>저장 중…</button>');
    expect(markup).not.toContain('원문 저장');
  });
});
