import { describe, expect, it } from 'vitest';
import { authNoticeReducer, createAuthNotices } from './authNotices';

describe('authentication redirect notice lifetime', () => {
  it('preserves the callback notice through the initial session bootstrap', () => {
    const initial = createAuthNotices('?linked=google');

    expect(authNoticeReducer(initial, {
      type: 'SESSION_TRANSITION',
      previousUserId: null,
      currentUserId: 'user-a',
    })).toEqual(initial);
  });

  it('clears notices on logout, session expiry, or a direct user change', () => {
    const initial = createAuthNotices('?linked=google&error=OAUTH_FAILED');

    expect(authNoticeReducer(initial, {
      type: 'SESSION_TRANSITION',
      previousUserId: 'user-a',
      currentUserId: null,
    })).toEqual({ googleLinked: false, redirectError: null });
    expect(authNoticeReducer(initial, {
      type: 'SESSION_TRANSITION',
      previousUserId: 'user-a',
      currentUserId: 'user-b',
    })).toEqual({ googleLinked: false, redirectError: null });
  });

  it('supports explicit one-shot consumption', () => {
    expect(authNoticeReducer(createAuthNotices('?linked=google'), { type: 'CLEAR' }))
      .toEqual({ googleLinked: false, redirectError: null });
  });
});
