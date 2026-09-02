import type { DateCandidate, ProposalDateCandidate } from '../../shared/api/types';
import { isValidIsoDate } from '../../shared/validation/dateTime';
import type { ReviewDraft } from './reviewModel';

const OPTIONAL_MINUTE = String.raw`(?:\s*(?<minute>[0-5]?\d)분)?`;
const OPTIONAL_PARTICLE = String.raw`(?:\s*(?<particle>에|부터|까지))?`;
const BARE_KOREAN_TIME = new RegExp(
  String.raw`^\s*(?<hour12>[1-9]|1[0-2])\s*시${OPTIONAL_MINUTE}${OPTIONAL_PARTICLE}\s*$`,
  'u',
);
const MERIDIEM_KOREAN_TIME = new RegExp(
  String.raw`^\s*(?<meridiem>오전|오후)\s*(?<hour12>[1-9]|1[0-2])\s*시${OPTIONAL_MINUTE}${OPTIONAL_PARTICLE}\s*$`,
  'u',
);
const TWENTY_FOUR_HOUR_KOREAN_TIME = new RegExp(
  String.raw`^\s*(?<hour24>0|0\d|1[3-9]|2[0-3])\s*시${OPTIONAL_MINUTE}${OPTIONAL_PARTICLE}\s*$`,
  'u',
);
const HH_MM_TIME = new RegExp(
  String.raw`^\s*(?<hour24>[01]?\d|2[0-3]):(?<colonMinute>[0-5]\d)${OPTIONAL_PARTICLE}\s*$`,
  'u',
);

type WallClockParts = {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
};

export type BareTimeClarification = {
  candidateId: string;
  candidate: ProposalDateCandidate;
  itemIndex: number;
  hour12: number;
  minute: number;
  fixedHour24: number | null;
  fixedPeriod: 'AM' | 'PM' | null;
};

export type ResolvedWallClockOption = {
  value: string;
  offset: string;
};

export type WallClockResolution =
  | { status: 'UNIQUE'; option: ResolvedWallClockOption }
  | { status: 'OVERLAP'; options: ResolvedWallClockOption[] }
  | { status: 'GAP' }
  | { status: 'INVALID_DATE' }
  | { status: 'INVALID_ZONE' };

function parseBareTime(candidate: ProposalDateCandidate): {
  hour12: number;
  minute: number;
  fixedHour24: number | null;
  fixedPeriod: 'AM' | 'PM' | null;
  particle: '에' | '부터' | '까지' | null;
} | null {
  if (
    candidate.precision !== 'UNKNOWN' ||
    candidate.value !== null ||
    candidate.timeSpecified ||
    !candidate.candidateId
  ) {
    return null;
  }
  const match = [
    BARE_KOREAN_TIME,
    MERIDIEM_KOREAN_TIME,
    TWENTY_FOUR_HOUR_KOREAN_TIME,
    HH_MM_TIME,
  ].map((pattern) => pattern.exec(candidate.surfaceText)).find((result) => result !== null);
  if (!match?.groups) return null;
  const hour24Group = match.groups.hour24;
  const hour12Group = match.groups.hour12;
  const meridiem = match.groups.meridiem;
  const fixedPeriod = meridiem === '오전' ? 'AM' : meridiem === '오후' ? 'PM' : null;
  const hour12 = hour12Group === undefined
    ? Number(hour24Group) % 12 || 12
    : Number(hour12Group);
  const fixedHour24 = hour24Group !== undefined
    ? Number(hour24Group)
    : fixedPeriod === null
      ? null
      : toTwentyFourHour(hour12, fixedPeriod);
  return {
    hour12,
    minute: Number(match.groups.minute ?? match.groups.colonMinute ?? '0'),
    fixedHour24,
    fixedPeriod,
    particle: (match.groups.particle as '에' | '부터' | '까지' | undefined) ?? null,
  };
}

export function isBareTimeCandidate(candidate: ProposalDateCandidate): boolean {
  return parseBareTime(candidate) !== null;
}

function bareTimeContext(review: ReviewDraft): BareTimeClarification | null {
  if (
    review.proposal.schemaVersion === '1' ||
    review.proposal.itemCandidates.length !== 1 ||
    review.proposal.dateCandidates.length !== 1
  ) {
    return null;
  }

  const proposalItem = review.proposal.itemCandidates[0];
  const candidate = review.proposal.dateCandidates[0];
  const matchingItemIndexes = review.items
    .map((item, index) => item.proposalCandidateId === proposalItem.candidateId ? index : -1)
    .filter((index) => index >= 0);
  if (matchingItemIndexes.length !== 1) return null;
  const itemIndex = matchingItemIndexes[0]!;
  const item = review.items[itemIndex]!;
  if (
    (item.kind !== 'TASK' && item.kind !== 'EVENT') ||
    proposalItem.dueDateCandidateId !== null ||
    proposalItem.eventScheduleCandidates.length > 0
  ) {
    return null;
  }

  const parsed = parseBareTime(candidate);
  if (!parsed || !candidate.candidateId) return null;
  if (
    (item.kind === 'TASK' && parsed.particle === '부터') ||
    (item.kind === 'EVENT' && parsed.particle === '까지')
  ) {
    return null;
  }
  return {
    candidateId: candidate.candidateId,
    candidate,
    itemIndex,
    hour12: parsed.hour12,
    minute: parsed.minute,
    fixedHour24: parsed.fixedHour24,
    fixedPeriod: parsed.fixedPeriod,
  };
}

export function compactBareTimeCandidateId(review: ReviewDraft): string | null {
  return bareTimeContext(review)?.candidateId ?? null;
}

export function findBareTimeClarification(review: ReviewDraft): BareTimeClarification | null {
  const context = bareTimeContext(review);
  if (!context) return null;
  const item = review.items[context.itemIndex]!;
  if (
    item.due !== null ||
    item.eventSchedule !== null ||
    review.dismissedDateCandidateIds.includes(context.candidateId)
  ) {
    return null;
  }
  return context;
}

export function dismissBareTimeClarification(
  review: ReviewDraft,
  candidateId: string,
): ReviewDraft {
  if (review.dismissedDateCandidateIds.includes(candidateId)) return review;
  return {
    ...review,
    dismissedDateCandidateIds: [...review.dismissedDateCandidateIds, candidateId],
  };
}

export function createUserResolvedExactDue(
  candidate: ProposalDateCandidate,
  value: string,
): DateCandidate {
  return {
    surfaceText: candidate.surfaceText,
    value,
    precision: 'EXACT_TIME',
    timeSpecified: true,
  };
}

export function requiresReviewSourceTimeZone(review: ReviewDraft): boolean {
  if (findBareTimeClarification(review)) return true;
  return review.items.some(
    (item) =>
      item.eventSchedule !== null ||
      (item.kind === 'TASK' &&
        item.due !== null &&
        (item.due.precision === 'EXACT_TIME' || item.due.precision === 'RELATIVE_EXACT')),
  );
}

export function toTwentyFourHour(hour12: number, period: 'AM' | 'PM'): number {
  if (!Number.isInteger(hour12) || hour12 < 1 || hour12 > 12) {
    throw new RangeError('hour12 must be between 1 and 12.');
  }
  if (period === 'AM') return hour12 === 12 ? 0 : hour12;
  return hour12 === 12 ? 12 : hour12 + 12;
}

function utcMilliseconds(parts: WallClockParts): number {
  const date = new Date(0);
  date.setUTCFullYear(parts.year, parts.month - 1, parts.day);
  date.setUTCHours(parts.hour, parts.minute, parts.second, 0);
  return date.getTime();
}

function formatterFor(timeZone: string): Intl.DateTimeFormat | null {
  try {
    return new Intl.DateTimeFormat('en-GB-u-ca-iso8601-nu-latn', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    });
  } catch {
    return null;
  }
}

function partsAt(formatter: Intl.DateTimeFormat, instant: number): WallClockParts | null {
  const values = new Map(
    formatter
      .formatToParts(new Date(instant))
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, Number(part.value)]),
  );
  const result: WallClockParts = {
    year: values.get('year') ?? Number.NaN,
    month: values.get('month') ?? Number.NaN,
    day: values.get('day') ?? Number.NaN,
    hour: values.get('hour') ?? Number.NaN,
    minute: values.get('minute') ?? Number.NaN,
    second: values.get('second') ?? Number.NaN,
  };
  return Object.values(result).every(Number.isFinite) ? result : null;
}

function sameParts(left: WallClockParts, right: WallClockParts): boolean {
  return left.year === right.year &&
    left.month === right.month &&
    left.day === right.day &&
    left.hour === right.hour &&
    left.minute === right.minute &&
    left.second === right.second;
}

function formatOffset(offsetMinutes: number): string | null {
  if (!Number.isInteger(offsetMinutes) || Math.abs(offsetMinutes) > 18 * 60) return null;
  const sign = offsetMinutes < 0 ? '-' : '+';
  const absolute = Math.abs(offsetMinutes);
  return `${sign}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(
    absolute % 60,
  ).padStart(2, '0')}`;
}

function formatOffsetDateTime(parts: WallClockParts, offset: string): string {
  return `${String(parts.year).padStart(4, '0')}-${String(parts.month).padStart(2, '0')}-${String(
    parts.day,
  ).padStart(2, '0')}T${String(parts.hour).padStart(2, '0')}:${String(parts.minute).padStart(
    2,
    '0',
  )}:00${offset}`;
}

export function resolveWallClockInTimeZone(
  isoDate: string,
  hour: number,
  minute: number,
  timeZone: string,
): WallClockResolution {
  if (
    !isValidIsoDate(isoDate) ||
    !Number.isInteger(hour) ||
    hour < 0 ||
    hour > 23 ||
    !Number.isInteger(minute) ||
    minute < 0 ||
    minute > 59
  ) {
    return { status: 'INVALID_DATE' };
  }
  const formatter = formatterFor(timeZone);
  if (!formatter) return { status: 'INVALID_ZONE' };

  const target: WallClockParts = {
    year: Number(isoDate.slice(0, 4)),
    month: Number(isoDate.slice(5, 7)),
    day: Number(isoDate.slice(8, 10)),
    hour,
    minute,
    second: 0,
  };
  const localAsUtc = utcMilliseconds(target);
  const candidateOffsets = new Set<number>();
  // Collect the bounded set of offsets surrounding the requested local day, then round-trip each
  // possible instant through Intl. Zero matches is a DST gap; multiple matches are an overlap that
  // the UI must not choose on the user's behalf.
  for (let deltaHours = -36; deltaHours <= 36; deltaHours += 6) {
    const sampleInstant = localAsUtc + deltaHours * 60 * 60 * 1_000;
    const sampleParts = partsAt(formatter, sampleInstant);
    if (!sampleParts) return { status: 'INVALID_ZONE' };
    const offsetMinutes = (utcMilliseconds(sampleParts) - sampleInstant) / 60_000;
    if (Number.isInteger(offsetMinutes)) candidateOffsets.add(offsetMinutes);
  }

  const options = [...candidateOffsets]
    .map((offsetMinutes) => {
      const offset = formatOffset(offsetMinutes);
      if (!offset) return null;
      const instant = localAsUtc - offsetMinutes * 60_000;
      const roundTrip = partsAt(formatter, instant);
      if (!roundTrip || !sameParts(roundTrip, target)) return null;
      return {
        instant,
        option: {
          value: formatOffsetDateTime(target, offset),
          offset,
        },
      };
    })
    .filter((candidate): candidate is { instant: number; option: ResolvedWallClockOption } =>
      candidate !== null,
    )
    .sort((left, right) => left.instant - right.instant)
    .filter(
      (candidate, index, all) =>
        index === 0 || candidate.option.value !== all[index - 1]!.option.value,
    )
    .map((candidate) => candidate.option);

  if (options.length === 0) return { status: 'GAP' };
  if (options.length === 1) return { status: 'UNIQUE', option: options[0]! };
  return { status: 'OVERLAP', options };
}

export function captureDateInTimeZone(
  clientRecordedAt: string,
  timeZone: string,
): string | null {
  const instant = Date.parse(clientRecordedAt);
  if (!Number.isFinite(instant)) return null;
  const formatter = formatterFor(timeZone);
  if (!formatter) return null;
  const parts = partsAt(formatter, instant);
  if (!parts) return null;
  return `${String(parts.year).padStart(4, '0')}-${String(parts.month).padStart(2, '0')}-${String(
    parts.day,
  ).padStart(2, '0')}`;
}
