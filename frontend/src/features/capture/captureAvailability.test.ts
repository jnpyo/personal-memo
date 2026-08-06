import { describe, expect, it } from 'vitest';
import {
  canSubmitMemo,
  LOCAL_DRAFT_STORAGE_FAILED_PROMPT,
  OFFLINE_CAPTURE_PROMPT,
} from './captureAvailability';

describe('memo capture availability', () => {
  it('permits server submission only after the health check is online', () => {
    expect(canSubmitMemo('online')).toBe(true);
    expect(canSubmitMemo('checking')).toBe(false);
    expect(canSubmitMemo('offline')).toBe(false);
  });

  it('states that an offline draft is owner-scoped locally while submission remains blocked', () => {
    expect(OFFLINE_CAPTURE_PROMPT).toContain('이 계정 전용 임시 초안');
    expect(OFFLINE_CAPTURE_PROMPT).toContain('기기에 저장');
    expect(OFFLINE_CAPTURE_PROMPT).toContain('제출할 수 없습니다');
  });

  it('does not claim persistence when browser storage rejects the draft', () => {
    expect(LOCAL_DRAFT_STORAGE_FAILED_PROMPT).toContain('보존하지 못했습니다');
    expect(LOCAL_DRAFT_STORAGE_FAILED_PROMPT).toContain('화면을 닫지 말고');
  });
});
