import { describe, expect, it, vi } from 'vitest';
import {
  connectionIsOffline,
  crossTabSessionResolution,
  probeCrossTabSession,
} from './useAuthSession';

describe('connectionIsOffline', () => {
  it('reports the browser offline state before auth bootstrap starts a request', () => {
    expect(connectionIsOffline(false)).toBe(true);
  });

  it('allows auth bootstrap while the browser reports an online connection', () => {
    expect(connectionIsOffline(true)).toBe(false);
    expect(connectionIsOffline(undefined)).toBe(false);
  });
});

describe('cross-tab session discovery reconciliation', () => {
  it('preserves the mounted workspace when another tab discovers the same owner', async () => {
    const readCurrentOwnerId = vi.fn().mockResolvedValue('owner-a');

    await expect(probeCrossTabSession('owner-a', readCurrentOwnerId))
      .resolves.toBe('UNCHANGED');
    expect(readCurrentOwnerId).toHaveBeenCalledOnce();
  });

  it('requires a transition only after GET confirms a different session boundary', () => {
    expect(crossTabSessionResolution('owner-a', 'owner-b')).toBe('OWNER_CHANGED');
    expect(crossTabSessionResolution('owner-a', null)).toBe('LOGGED_OUT');
    expect(crossTabSessionResolution(null, 'owner-a')).toBe('AUTHENTICATED');
  });

  it('does not authorize a destructive transition when the GET probe fails', async () => {
    await expect(probeCrossTabSession('owner-a', async () => {
      throw new TypeError('offline');
    })).resolves.toBe('UNCONFIRMED');
  });
});
