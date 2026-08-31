import { describe, expect, it } from 'vitest';
import type {
  CalendarFeedPublicationCapability,
  CalendarFeedSummary,
} from '../../shared/api/types';
import {
  buildCalendarFeedSubscriptionUrl,
  calendarFeedScopedTransientFor,
  canDiscardCalendarFeedSensitiveRetry,
  canRotateCalendarFeedSubscription,
  CALENDAR_FEED_PUBLIC_PATH,
  CALENDAR_FEED_SECRET_PATTERN,
  createCalendarFeedDraft,
  createCalendarFeedScopedTransientState,
  generateCalendarFeedSecret,
  isCalendarSharingProtected,
  replaceCalendarFeedSummary,
  requiresCalendarFeedTitleConfirmation,
  toggleCalendarFeedEvent,
} from './calendarSharingModel';

describe('calendar sharing safety model', () => {
  it('starts BUSY_ONLY with no automatically selected event', () => {
    expect(createCalendarFeedDraft()).toEqual({
      displayName: '',
      disclosureMode: 'BUSY_ONLY',
      selectedEventIds: [],
    });
  });

  it('guards browser departure throughout secret-bearing create and rotate requests', () => {
    const base = {
      dirty: false,
      oneTimeUrlVisible: false,
      retryContainsSecret: false,
    };
    expect(isCalendarSharingProtected({ ...base, operation: 'CREATE' })).toBe(true);
    expect(isCalendarSharingProtected({ ...base, operation: 'ROTATE' })).toBe(true);
    expect(isCalendarSharingProtected({
      ...base,
      operation: 'ENABLE_EXTERNAL_PUBLICATION',
    })).toBe(true);
    expect(isCalendarSharingProtected({
      ...base,
      retryContainsSecret: true,
      operation: null,
    })).toBe(true);
    expect(isCalendarSharingProtected({ ...base, operation: 'UPDATE' })).toBe(false);
  });

  it('blocks response-loss navigation until the one-time retry is explicitly discarded', () => {
    expect(canDiscardCalendarFeedSensitiveRetry(true, false)).toBe(false);
    expect(canDiscardCalendarFeedSensitiveRetry(true, true)).toBe(true);
    expect(canDiscardCalendarFeedSensitiveRetry(false, false)).toBe(true);
  });

  it('cannot carry a destructive confirmation from feed A into feed B', () => {
    const feedA = {
      ...createCalendarFeedScopedTransientState('feed-a'),
      selectedAddEventId: 'event-a',
      removeEntryId: 'entry-a',
      confirmTitleUpdate: true,
      destructiveAction: 'REVOKE' as const,
    };

    expect(calendarFeedScopedTransientFor(feedA, 'feed-a')).toBe(feedA);
    expect(calendarFeedScopedTransientFor(feedA, 'feed-b')).toEqual(
      createCalendarFeedScopedTransientState('feed-b'),
    );
  });

  it('requires an additional confirmation before a TITLE feed is created', () => {
    expect(requiresCalendarFeedTitleConfirmation('TITLE', false)).toBe(true);
    expect(requiresCalendarFeedTitleConfirmation('TITLE', true)).toBe(false);
    expect(requiresCalendarFeedTitleConfirmation('BUSY_ONLY', false)).toBe(false);
  });

  it('adds and removes only the event explicitly toggled by the owner', () => {
    const selected = toggleCalendarFeedEvent([], 'event-a', true);
    expect(selected).toEqual(['event-a']);
    expect(toggleCalendarFeedEvent(selected, 'event-b', false)).toEqual(['event-a']);
    expect(toggleCalendarFeedEvent(selected, 'event-a', false)).toEqual([]);
  });

  it('encodes exactly 32 random bytes as a 43-character base64url secret', () => {
    const secret = generateCalendarFeedSecret((target) => {
      target.forEach((_value, index) => { target[index] = index; });
      return target;
    });

    expect(secret).toHaveLength(43);
    expect(secret).toMatch(CALENDAR_FEED_SECRET_PATTERN);
    expect(secret).not.toContain('=');
    expect(secret).not.toContain('+');
    expect(secret).not.toContain('/');
  });

  it('builds only the fixed query-token URL without changing browser state', () => {
    const secret = 'A'.repeat(43);
    const url = buildCalendarFeedSubscriptionUrl(
      { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null },
      'https://memo.example.test',
      secret,
    );
    const parsed = new URL(url);

    expect(parsed.pathname).toBe(CALENDAR_FEED_PUBLIC_PATH);
    expect([...parsed.searchParams]).toEqual([['token', secret]]);
    expect(parsed.hash).toBe('');
  });

  it('rejects a malformed bearer before a URL can be assembled', () => {
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null },
      'https://memo.example.test',
      'not-long-enough',
    )).toThrow('Invalid calendar feed secret');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null },
      'https://memo.example.test',
      'a'.repeat(43),
    )).toThrow('Invalid calendar feed secret');
  });

  it('uses only the server-owned public HTTPS origin when publication is enabled', () => {
    const secret = 'A'.repeat(43);
    const url = buildCalendarFeedSubscriptionUrl(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'https://calendar.example.com',
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://private-memo.example.test',
      secret,
    );

    expect(new URL(url).origin).toBe('https://calendar.example.com');
    expect(url).not.toContain('private-memo');
  });

  it('rejects a LOCAL_ONLY URL under a PUBLIC_HTTPS capability', () => {
    expect(() => buildCalendarFeedSubscriptionUrl(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'https://calendar.example.com',
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://private-memo.example.test',
      'A'.repeat(43),
      'LOCAL_ONLY',
    )).toThrow('Calendar feed publication scope is unavailable');
  });

  it('allows rotation only when feed scope and current consent match capability', () => {
    const localFeed = {
      publicationScope: 'LOCAL_ONLY' as const,
      publicConsentPolicyVersion: null,
      publicConsentGrantedAt: null,
    };
    const publicFeed = {
      publicationScope: 'PUBLIC_HTTPS' as const,
      publicConsentPolicyVersion: 'calendar-feed-public-v1' as const,
      publicConsentGrantedAt: '2026-08-27T10:00:00Z',
    };
    const localCapability = {
      mode: 'LOCAL_ONLY' as const,
      publicOrigin: null,
      consentPolicyVersion: null,
    };
    const publicCapability = {
      mode: 'PUBLIC_HTTPS' as const,
      publicOrigin: 'https://calendar.example.com',
      consentPolicyVersion: 'calendar-feed-public-v1' as const,
    };

    expect(canRotateCalendarFeedSubscription(localCapability, localFeed)).toBe(true);
    expect(canRotateCalendarFeedSubscription(publicCapability, publicFeed)).toBe(true);
    expect(canRotateCalendarFeedSubscription(publicCapability, localFeed)).toBe(false);
    expect(canRotateCalendarFeedSubscription(localCapability, publicFeed)).toBe(false);
    expect(canRotateCalendarFeedSubscription(publicCapability, {
      ...publicFeed,
      publicConsentPolicyVersion: null,
    })).toBe(false);
  });

  it('rejects an unknown public consent policy before assembling a public URL', () => {
    expect(() => buildCalendarFeedSubscriptionUrl(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'https://calendar.example.com',
        consentPolicyVersion: 'future-policy',
      } as unknown as CalendarFeedPublicationCapability,
      'https://private-memo.example.test',
      'A'.repeat(43),
      'PUBLIC_HTTPS',
    )).toThrow('Invalid calendar feed origin');
  });

  it('rejects a malformed runtime public capability instead of falling back locally', () => {
    const secret = 'A'.repeat(43);
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'PUBLIC_HTTPS', publicOrigin: 'http://calendar.example.com', consentPolicyVersion: 'calendar-feed-public-v1' },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://127.0.0.1', consentPolicyVersion: 'calendar-feed-public-v1' },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://127.1', consentPolicyVersion: 'calendar-feed-public-v1' },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.123', consentPolicyVersion: 'calendar-feed-public-v1' },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.localhost', consentPolicyVersion: 'calendar-feed-public-v1' },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin:
          `https://${'a'.repeat(63)}.${'a'.repeat(63)}.${'a'.repeat(63)}.${'a'.repeat(56)}`,
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
    expect(() => buildCalendarFeedSubscriptionUrl(
      { mode: 'LOCAL_ONLY', publicOrigin: 'https://calendar.example.com' } as unknown as
        CalendarFeedPublicationCapability,
      'https://memo.example.test',
      secret,
    )).toThrow('Invalid calendar feed origin');
  });

  it('updates metadata without duplicating a feed', () => {
    const summary: CalendarFeedSummary = {
      id: 'feed-a',
      displayName: '가족',
      disclosureMode: 'BUSY_ONLY',
      status: 'ACTIVE',
      version: 1,
      eventCount: 1,
      createdAt: '2026-08-25T00:00:00Z',
      updatedAt: '2026-08-25T00:00:00Z',
      rotatedAt: '2026-08-25T00:00:00Z',
      revokedAt: null,
      publicationScope: 'LOCAL_ONLY',
      publicConsentPolicyVersion: null,
      publicConsentGrantedAt: null,
    };

    expect(replaceCalendarFeedSummary([summary], { ...summary, version: 2 }))
      .toEqual([{ ...summary, version: 2 }]);
  });
});
