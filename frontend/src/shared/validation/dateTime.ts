const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const OFFSET_DATE_TIME =
  /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-](\d{2}):(\d{2}))$/;

function isLeapYear(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

export function isValidIsoDate(value: string | null): value is string {
  if (!value) return false;
  const match = ISO_DATE.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1) return false;

  const daysInMonth = [
    31,
    isLeapYear(year) ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ];
  return day <= daysInMonth[month - 1];
}

/**
 * Accepts the RFC 3339 subset also accepted by the backend's OffsetDateTime parser.
 * Requiring seconds and limiting fractions to nanoseconds keeps browser and server
 * validation aligned instead of relying on Date.parse's calendar normalization.
 */
export function isValidOffsetDateTime(value: string | null): value is string {
  if (!value) return false;
  const match = OFFSET_DATE_TIME.exec(value);
  if (!match || !isValidIsoDate(match[1])) return false;

  const hour = Number(match[2]);
  const minute = Number(match[3]);
  const second = Number(match[4]);
  if (hour > 23 || minute > 59 || second > 59) return false;

  if (match[6] === 'Z') return true;
  const offsetHour = Number(match[7]);
  const offsetMinute = Number(match[8]);
  return (
    offsetHour <= 18 &&
    offsetMinute <= 59 &&
    (offsetHour < 18 || offsetMinute === 0)
  );
}
