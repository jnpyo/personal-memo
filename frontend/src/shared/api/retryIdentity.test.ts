import { describe, expect, it } from 'vitest';
import { createCaptureAttempt, RetryIdentityStore } from './retryIdentity';

describe('retry request identity', () => {
  it('reuses an idempotency key for the same request and rotates it when the body changes', () => {
    let sequence = 0;
    const store = new RetryIdentityStore(() => `key-${++sequence}`);

    expect(store.keyFor('apply:1', 'body-a')).toBe('key-1');
    expect(store.keyFor('apply:1', 'body-a')).toBe('key-1');
    expect(store.keyFor('apply:1', 'body-b')).toBe('key-2');
  });

  it('keeps memo id, timestamps, and both mutation keys stable in a capture attempt', () => {
    let sequence = 0;
    const attempt = createCaptureAttempt(
      '원문 메모',
      () => `id-${++sequence}`,
      () => new Date('2026-08-05T00:00:00.000Z'),
    );

    expect(attempt).toEqual({
      content: '원문 메모',
      memoId: 'id-1',
      clientCreatedAt: '2026-08-05T00:00:00.000Z',
      createKey: 'id-2',
      analysisKey: 'id-3',
    });
  });
});
