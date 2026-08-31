import type { CalendarEvent, Task } from '../../shared/api/types';

export const HOME_OVERVIEW_ITEM_LIMIT = 3;

const DNS_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

export function normalizeOwnerRemoteAppHostname(value: string | undefined): string | null {
  if (!value || value !== value.trim() || value.length > 253 || value.endsWith('.')) return null;
  const normalized = value.toLocaleLowerCase('en-US');
  const labels = normalized.split('.');
  if (labels.length < 2 || labels.some((label) => !DNS_LABEL_PATTERN.test(label))) return null;
  return normalized;
}

export const OWNER_REMOTE_APP_HOSTNAME = normalizeOwnerRemoteAppHostname(
  import.meta.env.VITE_OWNER_REMOTE_APP_HOSTNAME,
);

type IndexedTask = {
  task: Task;
  index: number;
  dueDateKey: string | null;
  priority: number;
};

type IndexedEvent = {
  event: CalendarEvent;
  index: number;
};

export type HomeOverviewProjection = {
  dateKey: string;
  dateLabel: string;
  todayEvents: CalendarEvent[];
  todayEventCount: number;
  priorityTasks: Task[];
  openTaskCount: number;
};

const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function validDate(value: Date): boolean {
  return !Number.isNaN(value.getTime());
}

function parsedInstant(value: string | null): Date | null {
  if (!value) return null;
  const parsed = new Date(value);
  return validDate(parsed) ? parsed : null;
}

export function dateKeyInTimeZone(value: Date, timeZone: string): string | null {
  if (!validDate(value)) return null;
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(value);
    const part = (type: Intl.DateTimeFormatPartTypes) =>
      parts.find((candidate) => candidate.type === type)?.value;
    const year = part('year');
    const month = part('month');
    const day = part('day');
    return year && month && day ? `${year}-${month}-${day}` : null;
  } catch {
    return null;
  }
}

function dateLabel(value: Date, timeZone: string, fallback: string): string {
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone,
      month: 'long',
      day: 'numeric',
      weekday: 'short',
    }).format(value);
  } catch {
    return fallback;
  }
}

function taskDueDateKey(task: Task, timeZone: string): string | null {
  if (task.dueDate && DATE_KEY_PATTERN.test(task.dueDate)) return task.dueDate;
  const dueAt = parsedInstant(task.dueAt);
  return dueAt ? dateKeyInTimeZone(dueAt, timeZone) : null;
}

function taskPriority(task: Task, dueDateKey: string | null, today: string): number {
  if (task.overdue) return 0;
  if (dueDateKey === today) return 1;
  if (dueDateKey !== null) return 2;
  return 3;
}

function eventOccursToday(event: CalendarEvent, now: Date): boolean {
  const today = dateKeyInTimeZone(now, event.sourceTimeZone);
  if (!today) return false;

  if (event.scheduleKind === 'ALL_DAY') {
    if (!event.startDate || !DATE_KEY_PATTERN.test(event.startDate)) return false;
    if (event.endDateExclusive && DATE_KEY_PATTERN.test(event.endDateExclusive)) {
      return event.startDate <= today && today < event.endDateExclusive;
    }
    return event.startDate === today;
  }

  const start = parsedInstant(event.startAt);
  if (!start) return false;
  const startDate = dateKeyInTimeZone(start, event.sourceTimeZone);
  if (!startDate) return false;
  const end = parsedInstant(event.endAt);
  const endDate = end ? dateKeyInTimeZone(end, event.sourceTimeZone) : null;
  return startDate === today || (endDate !== null && startDate < today && today <= endDate);
}

function compareEvents(left: IndexedEvent, right: IndexedEvent): number {
  const leftAllDay = left.event.scheduleKind === 'ALL_DAY';
  const rightAllDay = right.event.scheduleKind === 'ALL_DAY';
  if (leftAllDay !== rightAllDay) return leftAllDay ? -1 : 1;

  const leftKey = leftAllDay
    ? left.event.startDate ?? ''
    : String(parsedInstant(left.event.startAt)?.getTime() ?? Number.MAX_SAFE_INTEGER);
  const rightKey = rightAllDay
    ? right.event.startDate ?? ''
    : String(parsedInstant(right.event.startAt)?.getTime() ?? Number.MAX_SAFE_INTEGER);
  const ordered = leftKey.localeCompare(rightKey);
  return ordered !== 0 ? ordered : left.index - right.index;
}

export function buildHomeOverview(
  tasks: readonly Task[],
  events: readonly CalendarEvent[],
  now: Date,
  timeZone: string,
): HomeOverviewProjection {
  const today = dateKeyInTimeZone(now, timeZone) ?? '';
  const openTasks: IndexedTask[] = tasks
    .map((task, index): IndexedTask | null => {
      if (task.status !== 'TODO') return null;
      const dueDateKey = taskDueDateKey(task, timeZone);
      return {
        task,
        index,
        dueDateKey,
        priority: taskPriority(task, dueDateKey, today),
      };
    })
    .filter((task): task is IndexedTask => task !== null)
    .sort((left, right) => {
      if (left.priority !== right.priority) return left.priority - right.priority;
      if (left.dueDateKey !== right.dueDateKey) {
        if (left.dueDateKey === null) return 1;
        if (right.dueDateKey === null) return -1;
        return left.dueDateKey.localeCompare(right.dueDateKey);
      }
      return left.index - right.index;
    });

  const todayEvents = events
    .map((event, index): IndexedEvent => ({ event, index }))
    .filter(({ event }) => eventOccursToday(event, now))
    .sort(compareEvents);

  return {
    dateKey: today,
    dateLabel: dateLabel(now, timeZone, today),
    todayEvents: todayEvents.slice(0, HOME_OVERVIEW_ITEM_LIMIT).map(({ event }) => event),
    todayEventCount: todayEvents.length,
    priorityTasks: openTasks.slice(0, HOME_OVERVIEW_ITEM_LIMIT).map(({ task }) => task),
    openTaskCount: openTasks.length,
  };
}

export function isExactOwnerRemoteAppHostname(
  hostname: string,
  configuredHostname: string | null = OWNER_REMOTE_APP_HOSTNAME,
): boolean {
  const expectedHostname = normalizeOwnerRemoteAppHostname(configuredHostname ?? undefined);
  return expectedHostname !== null && hostname.toLocaleLowerCase('en-US') === expectedHostname;
}

export function homeTaskDueLabel(task: Task, timeZone: string): string {
  if (task.overdue) return '기한 초과';
  if (task.dueDate && DATE_KEY_PATTERN.test(task.dueDate)) {
    return `${task.dueDate} · 날짜만 지정`;
  }
  const dueAt = parsedInstant(task.dueAt);
  if (!dueAt) return '기한 없음';
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone,
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(dueAt);
  } catch {
    return task.dueAt ?? '기한 없음';
  }
}
