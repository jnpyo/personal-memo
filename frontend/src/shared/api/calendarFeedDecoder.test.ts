import { describe, expect, it } from 'vitest';
import {
  CalendarFeedContractError,
  decodeCalendarFeedDetail,
  decodeCalendarFeedEligibleEvents,
  decodeCalendarFeedPublicationCapability,
  decodeCalendarFeedSummaries,
} from './calendarFeedDecoder';

const summary = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: '가족 공유',
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

const activeEntry = {
  id: '22222222-2222-4222-8222-222222222222',
  eventId: '33333333-3333-4333-8333-333333333333',
  title: '디스코드 접속',
  state: 'ACTIVE',
  sequence: 0,
  scheduleKind: 'TIMED',
  startAt: '2026-08-25T09:00:00Z',
  endAt: null,
  startDate: null,
  endDateExclusive: null,
  sourceTimeZone: 'Asia/Seoul',
};

const eligibleEvent = {
  id: 'event-a',
  title: '디스코드 접속',
  scheduleKind: 'TIMED',
  startAt: '2026-08-25T09:00:00Z',
  endAt: null,
  startDate: null,
  endDateExclusive: null,
  sourceTimeZone: 'Asia/Seoul',
};

describe('calendar feed response decoder', () => {
  it('accepts only the exact local and public publication capability shapes', () => {
    expect(decodeCalendarFeedPublicationCapability({
      mode: 'LOCAL_ONLY',
      publicOrigin: null,
      consentPolicyVersion: null,
    })).toEqual({ mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null });
    expect(decodeCalendarFeedPublicationCapability({
      mode: 'PUBLIC_HTTPS',
      publicOrigin: 'https://calendar.example.com:8443',
      consentPolicyVersion: 'calendar-feed-public-v1',
    })).toEqual({
      mode: 'PUBLIC_HTTPS',
      publicOrigin: 'https://calendar.example.com:8443',
      consentPolicyVersion: 'calendar-feed-public-v1',
    });
  });

  it.each([
    { mode: 'LOCAL_ONLY', publicOrigin: 'https://calendar.example.com', consentPolicyVersion: null },
    { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: null, consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com', consentPolicyVersion: null },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com', consentPolicyVersion: 'future-policy' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'http://calendar.example.com', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://user:pass@calendar.example.com', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com/path', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com/', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com?query=1', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.example.com#fragment', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://localhost', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.localhost', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://127.0.0.1', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://127.1', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://2130706433', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://0x7f.0.0.1', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://calendar.123', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://[::1]', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'PUBLIC_HTTPS', publicOrigin: 'https://Calendar.example.com', consentPolicyVersion: 'calendar-feed-public-v1' },
    { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null, extra: true },
    {
      mode: 'PUBLIC_HTTPS',
      publicOrigin: `https://${'a'.repeat(63)}.${'a'.repeat(63)}.${'a'.repeat(63)}.${'a'.repeat(56)}`,
      consentPolicyVersion: 'calendar-feed-public-v1',
    },
  ])('rejects unsafe or mismatched publication capability %#', (value) => {
    expect(() => decodeCalendarFeedPublicationCapability(value)).toThrow(
      CalendarFeedContractError,
    );
  });

  it('accepts metadata-only summaries and active/cancelled detail entries', () => {
    expect(decodeCalendarFeedSummaries([summary])).toEqual([summary]);
    expect(decodeCalendarFeedDetail({
      ...summary,
      entries: [
        activeEntry,
        {
          ...activeEntry,
          id: '44444444-4444-4444-8444-444444444444',
          eventId: null,
          title: null,
          state: 'CANCELLED',
          sequence: 1,
        },
      ],
    })).toMatchObject({ entries: [{ state: 'ACTIVE' }, { state: 'CANCELLED' }] });
  });

  it('accepts exact public consent evidence and rejects mismatched publication state', () => {
    const publicSummary = {
      ...summary,
      publicationScope: 'PUBLIC_HTTPS',
      publicConsentPolicyVersion: 'calendar-feed-public-v1',
      publicConsentGrantedAt: '2026-08-27T10:00:00Z',
    };

    expect(decodeCalendarFeedSummaries([publicSummary])).toEqual([publicSummary]);
    expect(() => decodeCalendarFeedSummaries([{
      ...publicSummary,
      publicConsentPolicyVersion: 'future-policy',
    }])).toThrow(CalendarFeedContractError);
    expect(() => decodeCalendarFeedSummaries([{
      ...summary,
      publicConsentGrantedAt: '2026-08-27T10:00:00Z',
    }])).toThrow(CalendarFeedContractError);
  });

  it.each(['bearerSecret', 'token', 'tokenVerifier', 'verifierDigest', 'subscriptionUrl'])(
    'fails closed when a management response exposes %s',
    (field) => {
      expect(() => decodeCalendarFeedSummaries([{ ...summary, [field]: 'secret' }]))
        .toThrow(CalendarFeedContractError);
    },
  );

  it.each([
    ['summary feedUrl', () => decodeCalendarFeedSummaries([{ ...summary, feedUrl: 'secret' }])],
    ['detail href', () => decodeCalendarFeedDetail({ ...summary, entries: [], href: 'secret' })],
    ['entry link', () => decodeCalendarFeedDetail({
      ...summary,
      entries: [{ ...activeEntry, link: 'secret' }],
    })],
    ['eligible envelope bearer', () => decodeCalendarFeedEligibleEvents({
      items: [eligibleEvent],
      truncated: false,
      bearer: 'secret',
    })],
    ['eligible event credential', () => decodeCalendarFeedEligibleEvents({
      items: [{ ...eligibleEvent, credential: 'secret' }],
      truncated: false,
    })],
  ])('rejects the unknown URL or credential field in %s', (_label, decode) => {
    expect(decode).toThrow(CalendarFeedContractError);
  });

  it.each([
    ['summary', () => decodeCalendarFeedSummaries([{ ...summary, futureField: true }])],
    ['detail', () => decodeCalendarFeedDetail({
      ...summary,
      entries: [],
      futureField: true,
    })],
    ['entry', () => decodeCalendarFeedDetail({
      ...summary,
      entries: [{ ...activeEntry, futureField: true }],
    })],
    ['eligible envelope', () => decodeCalendarFeedEligibleEvents({
      items: [eligibleEvent],
      truncated: false,
      futureField: true,
    })],
    ['eligible event', () => decodeCalendarFeedEligibleEvents({
      items: [{ ...eligibleEvent, futureField: true }],
      truncated: false,
    })],
  ])('fails closed for every unknown %s response key', (_label, decode) => {
    expect(decode).toThrow(CalendarFeedContractError);
  });

  it('rejects nested or newly named token, secret and verifier fields', () => {
    expect(() => decodeCalendarFeedDetail({
      ...summary,
      entries: [{
        ...activeEntry,
        publication: { future_token_generation: 2 },
      }],
    })).toThrow(CalendarFeedContractError);
    expect(() => decodeCalendarFeedSummaries([{
      ...summary,
      secretMetadata: { present: true },
    }])).toThrow(CalendarFeedContractError);
  });

  it('requires removed entries to omit current event identity and title', () => {
    expect(() => decodeCalendarFeedDetail({
      ...summary,
      entries: [{ ...activeEntry, state: 'CANCELLED', sequence: 1 }],
    })).toThrow(CalendarFeedContractError);
  });

  it('decodes a bounded eligible-event page and preserves truncation', () => {
    expect(decodeCalendarFeedEligibleEvents({
      items: [eligibleEvent],
      truncated: true,
    })).toMatchObject({ truncated: true, items: [{ id: 'event-a' }] });
  });
});
