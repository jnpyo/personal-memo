import { describe, expect, it } from 'vitest';
import type { AuthSession } from '../../shared/api/types';
import { authReducer, INITIAL_AUTH_STATE } from './authState';

const session: AuthSession = {
  userId: '0711213d-a079-4dc1-92af-f123ba60e45a',
  email: 'memo@example.com',
  displayName: '메모 사용자',
  loginMethods: ['LOCAL'],
};

describe('authentication state transitions', () => {
  it('keeps the workspace unavailable until bootstrap authenticates a session', () => {
    expect(INITIAL_AUTH_STATE.status).toBe('BOOTING');
    expect(INITIAL_AUTH_STATE.session).toBeNull();

    const authenticated = authReducer(INITIAL_AUTH_STATE, {
      type: 'BOOTSTRAPPED',
      capabilities: {
        registrationEnabled: true,
        googleEnabled: false,
        googleRegistrationEnabled: false,
      },
      session,
    });

    expect(authenticated.status).toBe('AUTHENTICATED');
    expect(authenticated.session).toEqual(session);
    expect(authenticated.connection).toBe('online');
  });

  it('retains the mounted workspace until logout is confirmed, then clears the identity', () => {
    const authenticated = { ...INITIAL_AUTH_STATE, status: 'AUTHENTICATED' as const, session };
    const pending = authReducer(authenticated, { type: 'LOGOUT_PENDING' });
    const loggedOut = authReducer(pending, { type: 'LOGGED_OUT' });

    expect(pending.status).toBe('LOGOUT_PENDING');
    expect(pending.session).toEqual(session);
    expect(pending.pending).toBe('LOGOUT');
    expect(loggedOut.status).toBe('UNAUTHENTICATED');
    expect(loggedOut.connection).toBe('online');
    expect(loggedOut.session).toBeNull();
    expect(loggedOut.pending).toBeNull();
  });

  it('stays locked without discarding in-memory workspace state when logout is unconfirmed', () => {
    const authenticated = { ...INITIAL_AUTH_STATE, status: 'AUTHENTICATED' as const, session };
    const pending = authReducer(authenticated, { type: 'LOGOUT_PENDING' });
    const failed = authReducer(pending, {
      type: 'LOGOUT_RETRY_FAILED',
      connection: 'offline',
      message: '로그아웃 확인 실패',
    });

    expect(failed.status).toBe('LOGOUT_PENDING');
    expect(failed.session).toEqual(session);
    expect(failed.pending).toBe('LOGOUT');
    expect(failed.connection).toBe('offline');
    expect(failed.error).toBe('로그아웃 확인 실패');
  });

  it('does not let a late bootstrap or authentication result reopen a logout lock', () => {
    const locked = authReducer(
      { ...INITIAL_AUTH_STATE, status: 'AUTHENTICATED', session },
      { type: 'LOGOUT_PENDING' },
    );

    expect(authReducer(locked, {
      type: 'BOOTSTRAPPED',
      capabilities: {
        registrationEnabled: true,
        googleEnabled: true,
        googleRegistrationEnabled: true,
      },
      session,
    })).toBe(locked);
    expect(authReducer(locked, { type: 'AUTHENTICATED', session })).toBe(locked);
    expect(authReducer(locked, { type: 'FAILED', message: 'late failure' })).toBe(locked);
  });

  it('restores the retained workspace only after an expired remote observation confirms the same owner', () => {
    const locked = authReducer(
      { ...INITIAL_AUTH_STATE, status: 'AUTHENTICATED', session },
      { type: 'LOGOUT_PENDING' },
    );

    const restored = authReducer(locked, { type: 'REMOTE_LOGOUT_RELEASED' });

    expect(restored.status).toBe('AUTHENTICATED');
    expect(restored.session).toEqual(session);
    expect(restored.pending).toBeNull();
  });

  it('renders an unauthenticated offline shell instead of retaining boot state', () => {
    const offline = authReducer(INITIAL_AUTH_STATE, { type: 'OFFLINE' });

    expect(offline.status).toBe('UNAUTHENTICATED');
    expect(offline.connection).toBe('offline');
    expect(offline.session).toBeNull();
  });

  it('preserves an explicit operation while an offline event is being reconciled', () => {
    const pending = authReducer(
      { ...INITIAL_AUTH_STATE, status: 'UNAUTHENTICATED', connection: 'online' },
      { type: 'BEGIN', operation: 'LOGIN' },
    );

    const offline = authReducer(pending, { type: 'OFFLINE' });

    expect(offline.connection).toBe('offline');
    expect(offline.pending).toBe('LOGIN');
  });

  it('distinguishes an online bootstrap server failure from an offline state', () => {
    const failed = authReducer(INITIAL_AUTH_STATE, {
      type: 'BOOTSTRAP_FAILED',
      message: '서버에서 세션을 확인하지 못했습니다.',
    });

    expect(failed.status).toBe('UNAUTHENTICATED');
    expect(failed.connection).toBe('online');
    expect(failed.error).toBe('서버에서 세션을 확인하지 못했습니다.');
  });

  it('hides the workspace immediately when another tab changes authentication', () => {
    const authenticated = {
      ...INITIAL_AUTH_STATE,
      status: 'AUTHENTICATED' as const,
      connection: 'online' as const,
      session,
    };

    const transitioning = authReducer(authenticated, { type: 'SESSION_TRANSITION_DETECTED' });

    expect(transitioning.status).toBe('BOOTING');
    expect(transitioning.connection).toBe('checking');
    expect(transitioning.session).toBeNull();
  });
});
