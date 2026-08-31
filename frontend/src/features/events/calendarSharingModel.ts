import {
  CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION,
  type CalendarEvent,
  type CalendarFeedDisclosureMode,
  type CalendarFeedPublicationCapability,
  type CalendarFeedPublicationScope,
  type CalendarFeedSummary,
} from '../../shared/api/types';

export const CALENDAR_FEED_SECRET_BYTES = 32;
export const CALENDAR_FEED_SECRET_PATTERN = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;
export const CALENDAR_FEED_PUBLIC_PATH = '/calendar/v1/feed.ics';

export type CalendarFeedDraft = {
  displayName: string;
  disclosureMode: CalendarFeedDisclosureMode;
  selectedEventIds: string[];
};

export type CalendarSharingProtection = {
  pending: boolean;
  protectedState: boolean;
};

export type CalendarFeedDestructiveAction = 'ROTATE' | 'REVOKE' | null;

export type CalendarFeedScopedTransientState = {
  feedId: string | null;
  selectedAddEventId: string;
  removeEntryId: string | null;
  confirmTitleUpdate: boolean;
  destructiveAction: CalendarFeedDestructiveAction;
  confirmExternalPublication: boolean;
  externalPublicationConsentAccepted: boolean;
};

export function isCalendarSharingProtected(input: {
  dirty: boolean;
  oneTimeUrlVisible: boolean;
  retryContainsSecret: boolean;
  operation: string | null;
}): boolean {
  return input.dirty ||
    input.oneTimeUrlVisible ||
    input.retryContainsSecret ||
    input.operation === 'CREATE' ||
    input.operation === 'ROTATE' ||
    input.operation === 'ENABLE_EXTERNAL_PUBLICATION';
}

export function canDiscardCalendarFeedSensitiveRetry(
  retryContainsSecret: boolean,
  discardConfirmed: boolean,
): boolean {
  return !retryContainsSecret || discardConfirmed;
}

export function requiresCalendarFeedTitleConfirmation(
  disclosureMode: CalendarFeedDisclosureMode,
  confirmed: boolean,
): boolean {
  return disclosureMode === 'TITLE' && !confirmed;
}

type RandomBytes = (target: Uint8Array) => Uint8Array;

export function createCalendarFeedDraft(): CalendarFeedDraft {
  return {
    displayName: '',
    disclosureMode: 'BUSY_ONLY',
    selectedEventIds: [],
  };
}

export function createCalendarFeedScopedTransientState(
  feedId: string | null = null,
): CalendarFeedScopedTransientState {
  return {
    feedId,
    selectedAddEventId: '',
    removeEntryId: null,
    confirmTitleUpdate: false,
    destructiveAction: null,
    confirmExternalPublication: false,
    externalPublicationConsentAccepted: false,
  };
}

export function calendarFeedScopedTransientFor(
  state: CalendarFeedScopedTransientState,
  feedId: string,
): CalendarFeedScopedTransientState {
  return state.feedId === feedId ? state : createCalendarFeedScopedTransientState(feedId);
}

export function toggleCalendarFeedEvent(
  selectedEventIds: string[],
  eventId: string,
  selected: boolean,
): string[] {
  const withoutTarget = selectedEventIds.filter((candidate) => candidate !== eventId);
  return selected ? [...withoutTarget, eventId] : withoutTarget;
}

export function generateCalendarFeedSecret(
  randomBytes: RandomBytes = (target) => crypto.getRandomValues(target),
): string {
  const bytes = randomBytes(new Uint8Array(CALENDAR_FEED_SECRET_BYTES));
  if (bytes.length !== CALENDAR_FEED_SECRET_BYTES) {
    throw new Error('Invalid calendar feed random source');
  }
  const binary = Array.from(bytes, (value) => String.fromCharCode(value)).join('');
  const secret = btoa(binary)
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '');
  if (!CALENDAR_FEED_SECRET_PATTERN.test(secret)) {
    throw new Error('Invalid calendar feed secret');
  }
  return secret;
}

function exactHttpOrigin(origin: string): string {
  let parsed: URL;
  try {
    parsed = new URL(origin);
  } catch {
    throw new Error('Invalid calendar feed origin');
  }
  if (
    parsed.origin !== origin ||
    (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') ||
    parsed.username !== '' || parsed.password !== '' || parsed.pathname !== '/' ||
    parsed.search !== '' || parsed.hash !== ''
  ) {
    throw new Error('Invalid calendar feed origin');
  }
  return parsed.origin;
}

function publicHttpsOrigin(origin: string): string {
  if (origin.length > 255) {
    throw new Error('Invalid calendar feed origin');
  }
  const parsedOrigin = exactHttpOrigin(origin);
  const parsed = new URL(parsedOrigin);
  const labels = parsed.hostname.split('.');
  const validLabels = labels.length >= 2 && labels.every((label) =>
    label.length >= 1 && label.length <= 63 &&
    /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/u.test(label)) &&
    /^[a-z](?:[a-z0-9-]*[a-z0-9])?$/u.test(labels.at(-1) ?? '');
  const ipLiteral = /^\d{1,3}(?:\.\d{1,3}){3}$/u.test(parsed.hostname) ||
    parsed.hostname.includes(':');
  if (
    parsed.protocol !== 'https:' || parsed.hostname === 'localhost' ||
    parsed.hostname.endsWith('.localhost') || ipLiteral || !validLabels
  ) {
    throw new Error('Invalid calendar feed origin');
  }
  return parsedOrigin;
}

export function buildCalendarFeedSubscriptionUrl(
  capability: CalendarFeedPublicationCapability,
  localOrigin: string,
  secret: string,
  publicationScope: CalendarFeedPublicationScope = capability.mode,
): string {
  if (!CALENDAR_FEED_SECRET_PATTERN.test(secret)) {
    throw new Error('Invalid calendar feed secret');
  }
  if (publicationScope !== capability.mode) {
    throw new Error('Calendar feed publication scope is unavailable');
  }
  let origin: string;
  if (
    publicationScope === 'PUBLIC_HTTPS' && capability.mode === 'PUBLIC_HTTPS' &&
    capability.consentPolicyVersion === CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION
  ) {
    origin = publicHttpsOrigin(capability.publicOrigin);
  } else if (
    publicationScope === 'LOCAL_ONLY' && capability.mode === 'LOCAL_ONLY' &&
    capability.publicOrigin === null && capability.consentPolicyVersion === null
  ) {
    origin = exactHttpOrigin(localOrigin);
  } else {
    throw new Error('Invalid calendar feed origin');
  }
  const url = new URL(CALENDAR_FEED_PUBLIC_PATH, origin);
  url.searchParams.set('token', secret);
  return url.toString();
}

export function canRotateCalendarFeedSubscription(
  capability: CalendarFeedPublicationCapability | null,
  feed: Pick<
    CalendarFeedSummary,
    'publicationScope' | 'publicConsentPolicyVersion' | 'publicConsentGrantedAt'
  >,
): boolean {
  if (capability?.mode === 'LOCAL_ONLY') {
    return feed.publicationScope === 'LOCAL_ONLY' &&
      feed.publicConsentPolicyVersion === null && feed.publicConsentGrantedAt === null;
  }
  return capability?.mode === 'PUBLIC_HTTPS' &&
    capability.consentPolicyVersion === CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION &&
    feed.publicationScope === 'PUBLIC_HTTPS' &&
    feed.publicConsentPolicyVersion === CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION &&
    feed.publicConsentGrantedAt !== null;
}

export function calendarFeedDisclosureLabel(mode: CalendarFeedDisclosureMode): string {
  return mode === 'BUSY_ONLY' ? '시간만 (BUSY_ONLY)' : '제목과 시간 (TITLE)';
}

export function replaceCalendarFeedSummary(
  summaries: CalendarFeedSummary[],
  next: CalendarFeedSummary,
): CalendarFeedSummary[] {
  const existingIndex = summaries.findIndex((summary) => summary.id === next.id);
  if (existingIndex < 0) return [next, ...summaries];
  return summaries.map((summary) => summary.id === next.id ? next : summary);
}

function timedValue(value: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone,
    }).format(new Date(value));
  } catch {
    return value;
  }
}

export function calendarSharingEventTimeLabel(event: CalendarEvent): string {
  if (event.scheduleKind === 'ALL_DAY') {
    if (!event.startDate) return '종일 일정 날짜 확인 필요';
    return event.endDateExclusive
      ? `${event.startDate}부터 ${event.endDateExclusive} 전까지 · 종일`
      : `${event.startDate} · 종일`;
  }
  if (!event.startAt) return '일정 시각 확인 필요';
  const start = timedValue(event.startAt, event.sourceTimeZone);
  const end = event.endAt ? timedValue(event.endAt, event.sourceTimeZone) : null;
  return `${start}${end ? ` ~ ${end}` : ''} · ${event.sourceTimeZone}`;
}
