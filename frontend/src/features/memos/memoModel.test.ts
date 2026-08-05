import { describe, expect, it } from 'vitest';
import type { MemoView } from '../../shared/api/types';
import {
  analysisStateLabel,
  analysisStateTone,
  buildUpdateMemoRequest,
  isMemoContentValid,
} from './memoModel';

function memo(overrides: Partial<MemoView> = {}): MemoView {
  return {
    id: 'memo-1',
    currentRevision: 1,
    content: '원문',
    status: 'ACTIVE',
    analysisState: 'NOT_STARTED',
    createdAt: '2026-08-05T00:00:00.000Z',
    ...overrides,
  };
}

describe('memo model', () => {
  it('builds an optimistic revision request without normalizing the raw text', () => {
    const request = buildUpdateMemoRequest(memo({ currentRevision: 4 }), '  원문 줄바꿈\n유지  ');

    expect(request).toEqual({
      expectedRevision: 4,
      content: '  원문 줄바꿈\n유지  ',
    });
  });

  it('maps canonical analysis states to user-facing labels and tones', () => {
    expect(analysisStateLabel('REVIEW_REQUIRED')).toBe('제안 검토 필요');
    expect(analysisStateTone('REVIEW_REQUIRED')).toBe('attention');
    expect(analysisStateTone('APPLIED')).toBe('complete');
    expect(analysisStateLabel('FUTURE_STATE')).toBe('FUTURE_STATE');
  });

  it('rejects blank and oversized content while preserving meaningful whitespace', () => {
    expect(isMemoContentValid('  ')).toBe(false);
    expect(isMemoContentValid(' 원문 ')).toBe(true);
    expect(isMemoContentValid('가'.repeat(20_001))).toBe(false);
  });
});
