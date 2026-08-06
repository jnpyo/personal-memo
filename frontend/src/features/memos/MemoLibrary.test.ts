import { describe, expect, it } from 'vitest';
import { memoEditHasChanges } from './MemoLibrary';

describe('memo edit dirty state', () => {
  it('marks only content that differs from the source revision as unsaved', () => {
    expect(memoEditHasChanges('원본 메모', '원본 메모')).toBe(false);
    expect(memoEditHasChanges('원본 메모', '수정한 메모')).toBe(true);
  });
});
