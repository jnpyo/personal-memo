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
    pinned: false,
    status: 'ACTIVE',
    analysisState: 'NOT_STARTED',
    createdAt: '2026-08-05T00:00:00.000Z',
    ...overrides,
  };
}

describe('memo model', () => {
  it('builds an optimistic revision request without normalizing the raw text', () => {
    const request = buildUpdateMemoRequest(
      memo({ currentRevision: 4 }),
      '  원문 줄바꿈\n유지  ',
      '2026-08-05T02:03:04.000Z',
      'Asia/Seoul',
    );

    expect(request).toEqual({
      expectedRevision: 4,
      content: '  원문 줄바꿈\n유지  ',
      clientUpdatedAt: '2026-08-05T02:03:04.000Z',
      timeZone: 'Asia/Seoul',
    });
  });

  it('maps canonical analysis states to user-facing labels and tones', () => {
    expect(analysisStateLabel('REVIEW_REQUIRED')).toBe('제안 검토 필요');
    expect(analysisStateLabel('QUEUED')).toBe('분석 대기 중');
    expect(analysisStateLabel('RUNNING')).toBe('분석 중');
    expect(analysisStateLabel('FAILED')).toBe('분석 실패');
    expect(analysisStateTone('REVIEW_REQUIRED')).toBe('attention');
    expect(analysisStateTone('FAILED')).toBe('attention');
    expect(analysisStateTone('RUNNING')).toBe('neutral');
    expect(analysisStateTone('APPLIED')).toBe('complete');
    expect(analysisStateLabel('FUTURE_STATE')).toBe('FUTURE_STATE');
  });

  it('rejects blank and oversized content while preserving meaningful whitespace', () => {
    expect(isMemoContentValid('  ')).toBe(false);
    expect(isMemoContentValid(' 원문 ')).toBe(true);
    expect(isMemoContentValid('가'.repeat(20_001))).toBe(false);
  });
});
