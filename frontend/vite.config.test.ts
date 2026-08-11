import { describe, expect, it } from 'vitest';
import {
  BACKEND_NETWORK_ONLY_PATH_PATTERNS,
  isBackendNetworkOnlyPath,
  PWA_REGISTER_TYPE,
} from './vite.config';

function navigationFallbackIsDenied(pathname: string): boolean {
  return BACKEND_NETWORK_ONLY_PATH_PATTERNS.some((pattern) => pattern.test(pathname));
}

describe('PWA backend routing boundary', () => {
  it('waits for an explicit user choice before activating an update', () => {
    expect(PWA_REGISTER_TYPE).toBe('prompt');
  });

  it('keeps the login SPA route eligible for the offline app shell', () => {
    expect(navigationFallbackIsDenied('/login')).toBe(false);
    expect(navigationFallbackIsDenied('/login/')).toBe(false);
    expect(isBackendNetworkOnlyPath('/login')).toBe(false);
  });

  it('keeps backend API and OAuth endpoints network-only', () => {
    for (const pathname of [
      '/api/v1/auth/me',
      '/api/v1/search/memos',
      '/oauth2/authorization/google',
      '/login/oauth2',
      '/login/oauth2/code/google',
    ]) {
      expect(navigationFallbackIsDenied(pathname)).toBe(true);
      expect(isBackendNetworkOnlyPath(pathname)).toBe(true);
    }
  });

  it('does not widen prefix matches beyond backend route boundaries', () => {
    expect(isBackendNetworkOnlyPath('/apian')).toBe(false);
    expect(isBackendNetworkOnlyPath('/oauth2callback')).toBe(false);
    expect(isBackendNetworkOnlyPath('/login/oauth2callback')).toBe(false);
  });
});
