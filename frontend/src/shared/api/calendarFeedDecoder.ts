import type {
  CalendarEvent,
  CalendarFeedDetail,
  CalendarFeedEligibleEvents,
  CalendarFeedEntry,
  CalendarFeedPublicationCapability,
  CalendarFeedSummary,
} from './types';
import { CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION } from './types';

const CALENDAR_EVENT_FIELDS = new Set([
  'id',
  'title',
  'scheduleKind',
  'startAt',
  'endAt',
  'startDate',
  'endDateExclusive',
  'sourceTimeZone',
]);
const CALENDAR_FEED_SUMMARY_FIELDS = new Set([
  'id',
  'displayName',
  'disclosureMode',
  'status',
  'version',
  'eventCount',
  'createdAt',
  'updatedAt',
  'rotatedAt',
  'revokedAt',
  'publicationScope',
  'publicConsentPolicyVersion',
  'publicConsentGrantedAt',
]);
const CALENDAR_FEED_DETAIL_FIELDS = new Set([
  ...CALENDAR_FEED_SUMMARY_FIELDS,
  'entries',
]);
const CALENDAR_FEED_ENTRY_FIELDS = new Set([
  'id',
  'eventId',
  'title',
  'state',
  'sequence',
  'scheduleKind',
  'startAt',
  'endAt',
  'startDate',
  'endDateExclusive',
  'sourceTimeZone',
]);
const CALENDAR_FEED_ELIGIBLE_EVENTS_FIELDS = new Set(['items', 'truncated']);
const CALENDAR_FEED_PUBLICATION_CAPABILITY_FIELDS = new Set([
  'mode',
  'publicOrigin',
  'consentPolicyVersion',
]);

export class CalendarFeedContractError extends Error {
  constructor(readonly field: string) {
    super(`Invalid calendar feed response field: ${field}`);
    this.name = 'CalendarFeedContractError';
  }
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new CalendarFeedContractError(field);
  }
  return value as Record<string, unknown>;
}

function assertOnlyFields(
  source: Record<string, unknown>,
  field: string,
  allowedFields: ReadonlySet<string>,
): void {
  for (const key of Object.keys(source)) {
    if (!allowedFields.has(key)) {
      throw new CalendarFeedContractError(`${field}.${key}`);
    }
  }
}

function string(value: unknown, field: string, maximum = 256): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > maximum) {
    throw new CalendarFeedContractError(field);
  }
  return value;
}

function nullableString(value: unknown, field: string, maximum = 256): string | null {
  return value === null ? null : string(value, field, maximum);
}

function integer(value: unknown, field: string, minimum: number, maximum: number): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new CalendarFeedContractError(field);
  }
  return value as number;
}

function enumeration<T extends string>(
  value: unknown,
  field: string,
  allowed: readonly T[],
): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    throw new CalendarFeedContractError(field);
  }
  return value as T;
}

function canonicalPublicHttpsOrigin(value: unknown, field: string): string {
  const origin = string(value, field, 255);
  let parsed: URL;
  try {
    parsed = new URL(origin);
  } catch {
    throw new CalendarFeedContractError(field);
  }
  const labels = parsed.hostname.split('.');
  const validLabels = labels.length >= 2 && labels.every((label) =>
    label.length >= 1 && label.length <= 63 &&
    /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/u.test(label)) &&
    /^[a-z](?:[a-z0-9-]*[a-z0-9])?$/u.test(labels.at(-1) ?? '');
  const ipLiteral = /^\d{1,3}(?:\.\d{1,3}){3}$/u.test(parsed.hostname) ||
    parsed.hostname.includes(':');
  if (
    parsed.protocol !== 'https:' || parsed.origin !== origin ||
    parsed.username !== '' || parsed.password !== '' || parsed.pathname !== '/' ||
    parsed.search !== '' || parsed.hash !== '' || parsed.hostname === 'localhost' ||
    parsed.hostname.endsWith('.localhost') ||
    ipLiteral || !validLabels
  ) {
    throw new CalendarFeedContractError(field);
  }
  return origin;
}

export function decodeCalendarFeedPublicationCapability(
  value: unknown,
): CalendarFeedPublicationCapability {
  const source = record(value, 'calendarFeedPublicationCapability');
  assertOnlyFields(
    source,
    'calendarFeedPublicationCapability',
    CALENDAR_FEED_PUBLICATION_CAPABILITY_FIELDS,
  );
  const mode = enumeration(
    source.mode,
    'calendarFeedPublicationCapability.mode',
    ['LOCAL_ONLY', 'PUBLIC_HTTPS'] as const,
  );
  if (mode === 'LOCAL_ONLY') {
    if (source.publicOrigin !== null || source.consentPolicyVersion !== null) {
      throw new CalendarFeedContractError('calendarFeedPublicationCapability.publicOrigin');
    }
    return { mode, publicOrigin: null, consentPolicyVersion: null };
  }
  if (source.consentPolicyVersion !== CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION) {
    throw new CalendarFeedContractError(
      'calendarFeedPublicationCapability.consentPolicyVersion',
    );
  }
  return {
    mode,
    publicOrigin: canonicalPublicHttpsOrigin(
      source.publicOrigin,
      'calendarFeedPublicationCapability.publicOrigin',
    ),
    consentPolicyVersion: CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION,
  };
}

function decodeCalendarEvent(value: unknown, field: string): CalendarEvent {
  const source = record(value, field);
  assertOnlyFields(source, field, CALENDAR_EVENT_FIELDS);
  const scheduleKind = enumeration(
    source.scheduleKind,
    `${field}.scheduleKind`,
    ['TIMED', 'ALL_DAY'] as const,
  );
  const event: CalendarEvent = {
    id: string(source.id, `${field}.id`, 128),
    title: string(source.title, `${field}.title`, 500),
    scheduleKind,
    startAt: nullableString(source.startAt, `${field}.startAt`, 64),
    endAt: nullableString(source.endAt, `${field}.endAt`, 64),
    startDate: nullableString(source.startDate, `${field}.startDate`, 10),
    endDateExclusive: nullableString(
      source.endDateExclusive,
      `${field}.endDateExclusive`,
      10,
    ),
    sourceTimeZone: string(source.sourceTimeZone, `${field}.sourceTimeZone`, 64),
  };

  if (
    (scheduleKind === 'TIMED' && (event.startAt === null || event.startDate !== null ||
      event.endDateExclusive !== null)) ||
    (scheduleKind === 'ALL_DAY' && (event.startDate === null || event.startAt !== null ||
      event.endAt !== null))
  ) {
    throw new CalendarFeedContractError(`${field}.schedule`);
  }
  return event;
}

function decodeSummary(
  value: unknown,
  field: string,
  allowedFields: ReadonlySet<string> = CALENDAR_FEED_SUMMARY_FIELDS,
): CalendarFeedSummary {
  const source = record(value, field);
  assertOnlyFields(source, field, allowedFields);
  const summary: CalendarFeedSummary = {
    id: string(source.id, `${field}.id`, 128),
    displayName: string(source.displayName, `${field}.displayName`, 80),
    disclosureMode: enumeration(
      source.disclosureMode,
      `${field}.disclosureMode`,
      ['BUSY_ONLY', 'TITLE'] as const,
    ),
    status: enumeration(source.status, `${field}.status`, ['ACTIVE', 'REVOKED'] as const),
    version: integer(source.version, `${field}.version`, 1, Number.MAX_SAFE_INTEGER),
    eventCount: integer(source.eventCount, `${field}.eventCount`, 0, 100),
    createdAt: string(source.createdAt, `${field}.createdAt`, 64),
    updatedAt: string(source.updatedAt, `${field}.updatedAt`, 64),
    rotatedAt: string(source.rotatedAt, `${field}.rotatedAt`, 64),
    revokedAt: nullableString(source.revokedAt, `${field}.revokedAt`, 64),
    publicationScope: enumeration(
      source.publicationScope,
      `${field}.publicationScope`,
      ['LOCAL_ONLY', 'PUBLIC_HTTPS'] as const,
    ),
    publicConsentPolicyVersion: nullableString(
      source.publicConsentPolicyVersion,
      `${field}.publicConsentPolicyVersion`,
      64,
    ) as CalendarFeedSummary['publicConsentPolicyVersion'],
    publicConsentGrantedAt: nullableString(
      source.publicConsentGrantedAt,
      `${field}.publicConsentGrantedAt`,
      64,
    ),
  };

  if (
    (summary.status === 'ACTIVE' && summary.revokedAt !== null) ||
    (summary.status === 'REVOKED' && summary.revokedAt === null) ||
    (summary.publicationScope === 'LOCAL_ONLY' &&
      (summary.publicConsentPolicyVersion !== null ||
        summary.publicConsentGrantedAt !== null)) ||
    (summary.publicationScope === 'PUBLIC_HTTPS' &&
      (summary.publicConsentPolicyVersion !== CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION ||
        summary.publicConsentGrantedAt === null))
  ) {
    throw new CalendarFeedContractError(`${field}.state`);
  }
  return summary;
}

function decodeEntry(value: unknown, field: string): CalendarFeedEntry {
  const source = record(value, field);
  assertOnlyFields(source, field, CALENDAR_FEED_ENTRY_FIELDS);
  const state = enumeration(source.state, `${field}.state`, ['ACTIVE', 'CANCELLED'] as const);
  const entry: CalendarFeedEntry = {
    id: string(source.id, `${field}.id`, 128),
    eventId: nullableString(source.eventId, `${field}.eventId`, 128),
    title: nullableString(source.title, `${field}.title`, 500),
    state,
    sequence: integer(source.sequence, `${field}.sequence`, 0, Number.MAX_SAFE_INTEGER),
    scheduleKind: enumeration(
      source.scheduleKind,
      `${field}.scheduleKind`,
      ['TIMED', 'ALL_DAY'] as const,
    ),
    startAt: nullableString(source.startAt, `${field}.startAt`, 64),
    endAt: nullableString(source.endAt, `${field}.endAt`, 64),
    startDate: nullableString(source.startDate, `${field}.startDate`, 10),
    endDateExclusive: nullableString(
      source.endDateExclusive,
      `${field}.endDateExclusive`,
      10,
    ),
    sourceTimeZone: string(source.sourceTimeZone, `${field}.sourceTimeZone`, 64),
  };

  if (
    (state === 'ACTIVE' && (entry.eventId === null || entry.title === null)) ||
    (state === 'CANCELLED' && (entry.eventId !== null || entry.title !== null))
  ) {
    throw new CalendarFeedContractError(`${field}.identity`);
  }
  if (
    (entry.scheduleKind === 'TIMED' && (entry.startAt === null || entry.startDate !== null ||
      entry.endDateExclusive !== null)) ||
    (entry.scheduleKind === 'ALL_DAY' && (entry.startDate === null || entry.startAt !== null ||
      entry.endAt !== null))
  ) {
    throw new CalendarFeedContractError(`${field}.schedule`);
  }
  return entry;
}

export function decodeCalendarFeedEligibleEvents(value: unknown): CalendarFeedEligibleEvents {
  const source = record(value, 'calendarFeedEligibleEvents');
  assertOnlyFields(
    source,
    'calendarFeedEligibleEvents',
    CALENDAR_FEED_ELIGIBLE_EVENTS_FIELDS,
  );
  if (!Array.isArray(source.items) || typeof source.truncated !== 'boolean') {
    throw new CalendarFeedContractError('calendarFeedEligibleEvents');
  }
  if (source.items.length > 100) {
    throw new CalendarFeedContractError('calendarFeedEligibleEvents.items');
  }
  return {
    items: source.items.map((item, index) =>
      decodeCalendarEvent(item, `calendarFeedEligibleEvents.items[${index}]`)),
    truncated: source.truncated,
  };
}

export function decodeCalendarFeedSummaries(value: unknown): CalendarFeedSummary[] {
  if (!Array.isArray(value) || value.length > 100) {
    throw new CalendarFeedContractError('calendarFeeds');
  }
  return value.map((item, index) => decodeSummary(item, `calendarFeeds[${index}]`));
}

export function decodeCalendarFeedDetail(value: unknown): CalendarFeedDetail {
  const source = record(value, 'calendarFeed');
  assertOnlyFields(source, 'calendarFeed', CALENDAR_FEED_DETAIL_FIELDS);
  if (!Array.isArray(source.entries) || source.entries.length > 100) {
    throw new CalendarFeedContractError('calendarFeed.entries');
  }
  return {
    ...decodeSummary(source, 'calendarFeed', CALENDAR_FEED_DETAIL_FIELDS),
    entries: source.entries.map((entry, index) =>
      decodeEntry(entry, `calendarFeed.entries[${index}]`)),
  };
}
