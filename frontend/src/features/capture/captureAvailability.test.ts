import { describe, expect, it } from 'vitest';
import { canSubmitMemo, OFFLINE_CAPTURE_PROMPT } from './captureAvailability';

describe('memo capture availability', () => {
  it('permits server submission only after the health check is online', () => {
    expect(canSubmitMemo('online')).toBe(true);
    expect(canSubmitMemo('checking')).toBe(false);
    expect(canSubmitMemo('offline')).toBe(false);
  });

  it('states that an offline draft is not persisted on the device', () => {
    expect(OFFLINE_CAPTURE_PROMPT).toContain('기기에 저장되지 않');
    expect(OFFLINE_CAPTURE_PROMPT).toContain('제출할 수 없습니다');
  });
});
