import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  BACKEND_NETWORK_ONLY_PATH_PATTERNS,
  CALENDAR_FEED_DEV_PROXY_CONTEXT,
  isBackendNetworkOnlyPath,
  PWA_REGISTER_TYPE,
} from './vite.config';

const applicationStyles = readFileSync(new URL('./src/app/styles.css', import.meta.url), 'utf8');

function navigationFallbackIsDenied(pathnameAndSearch: string): boolean {
  return BACKEND_NETWORK_ONLY_PATH_PATTERNS.some((pattern) => pattern.test(pathnameAndSearch));
}

function networkOnlyPatternDiagnostics(pathname: string): string {
  return BACKEND_NETWORK_ONLY_PATH_PATTERNS
    .map((pattern) => `${pattern.toString()}=${pattern.test(pathname)}`)
    .join(', ');
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

  it('keeps backend API, Cloudflare Access, and OAuth endpoints network-only', () => {
    for (const pathname of [
      '/api/v1/auth/me',
      '/api/v1/events/calendar.ics',
      '/api/v1/search/memos',
      '/cdn-cgi/access',
      '/cdn-cgi/access?state=synthetic',
      '/cdn-cgi/access/',
      '/cdn-cgi/access/authorized?state=synthetic',
      '/cdn-cgi/access/callback',
      '/cdn-cgi/access/login/memo.example.com',
      '/CDN-CGI/ACCESS/authorized',
      '/calendar/v1/feed.ics',
      '/oauth2/authorization/google',
      '/login/oauth2',
      '/login/oauth2/code/google',
    ]) {
      expect(
        navigationFallbackIsDenied(pathname),
        `${pathname}: ${networkOnlyPatternDiagnostics(pathname)}`,
      ).toBe(true);
      expect(
        isBackendNetworkOnlyPath(new URL(pathname, 'https://memo.invalid').pathname),
        pathname,
      ).toBe(true);
    }
  });

  it('denies the real query-bearing feed navigation before the app-shell fallback', () => {
    const subscriptionTarget = `/calendar/v1/feed.ics?token=${'a'.repeat(42)}A`;

    expect(
      navigationFallbackIsDenied(subscriptionTarget),
      networkOnlyPatternDiagnostics(subscriptionTarget),
    ).toBe(true);
    expect(isBackendNetworkOnlyPath('/calendar/v1/feed.ics')).toBe(true);
  });

  it('keeps the development feed proxy fixed to only the token-bearing endpoint', () => {
    const proxyPattern = new RegExp(CALENDAR_FEED_DEV_PROXY_CONTEXT);

    expect(proxyPattern.test(`/calendar/v1/feed.ics?token=${'a'.repeat(42)}A`)).toBe(true);
    expect(proxyPattern.test('/calendar/v1/feed.ics')).toBe(true);
    expect(proxyPattern.test('/calendar/v1/feed.ics/extra')).toBe(false);
    expect(proxyPattern.test('/calendar/v1/feed.ics.evil')).toBe(false);
  });

  it('does not widen prefix matches beyond backend route boundaries', () => {
    expect(isBackendNetworkOnlyPath('/apian')).toBe(false);
    expect(isBackendNetworkOnlyPath('/calendar/v1/feed.ics/extra')).toBe(false);
    expect(isBackendNetworkOnlyPath('/calendar/v1/another.ics')).toBe(false);
    expect(isBackendNetworkOnlyPath('/cdn-cgi/access.evil')).toBe(false);
    expect(isBackendNetworkOnlyPath('/cdn-cgi/accessibility')).toBe(false);
    expect(isBackendNetworkOnlyPath('/cdn-cgi/access-token')).toBe(false);
    expect(isBackendNetworkOnlyPath('/cdn-cgi/trace')).toBe(false);
    expect(isBackendNetworkOnlyPath('/cdn-cgian/access')).toBe(false);
    expect(isBackendNetworkOnlyPath('/oauth2callback')).toBe(false);
    expect(isBackendNetworkOnlyPath('/login/oauth2callback')).toBe(false);
  });
});

describe('review touch target CSS contract', () => {
  it('keeps the unavailable relation exclusion action above the 48px touch minimum', () => {
    expect(applicationStyles).toMatch(
      /\.review-dialog \.relation-review__exclude\s*\{\s*min-height:\s*48px;/,
    );
  });
});
