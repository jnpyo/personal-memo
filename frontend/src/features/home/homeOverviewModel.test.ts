import { describe, expect, it } from 'vitest';
import type { CalendarEvent, Task } from '../../shared/api/types';
import {
  buildHomeOverview,
  dateKeyInTimeZone,
  HOME_OVERVIEW_ITEM_LIMIT,
  isExactOwnerRemoteAppHostname,
  normalizeOwnerRemoteAppHostname,
} from './homeOverviewModel';

const NOW = new Date('2026-08-31T03:00:00Z');

function task(input: Partial<Task> & Pick<Task, 'id' | 'title'>): Task {
  return {
    status: 'TODO',
    dueAt: null,
    dueDate: null,
    overdue: false,
    ...input,
  };
}

function timedEvent(id: string, startAt: string, endAt: string | null = null): CalendarEvent {
  return {
    id,
    title: id,
    scheduleKind: 'TIMED',
    startAt,
    endAt,
    startDate: null,
    endDateExclusive: null,
    sourceTimeZone: 'Asia/Seoul',
  };
}

describe('home overview projection', () => {
  it('keeps only three priority TODO items with overdue and today first', () => {
    const tasks = [
      task({ id: 'undated', title: '기한 없음' }),
      task({ id: 'future', title: '내일', dueDate: '2026-09-01' }),
      task({ id: 'today', title: '오늘', dueAt: '2026-08-31T09:00:00Z' }),
      task({ id: 'overdue', title: '밀림', dueDate: '2026-08-30', overdue: true }),
      task({ id: 'done', title: '완료', status: 'DONE', dueDate: '2026-08-31' }),
    ];

    const result = buildHomeOverview(tasks, [], NOW, 'Asia/Seoul');

    expect(result.openTaskCount).toBe(4);
    expect(result.priorityTasks).toHaveLength(HOME_OVERVIEW_ITEM_LIMIT);
    expect(result.priorityTasks.map(({ id }) => id)).toEqual(['overdue', 'today', 'future']);
    expect(tasks.map(({ id }) => id)).toEqual(['undated', 'future', 'today', 'overdue', 'done']);
  });

  it('uses each event source zone, all-day exclusive ends, and a deterministic now', () => {
    const events: CalendarEvent[] = [
      {
        id: 'all-day-active',
        title: '진행 중 종일',
        scheduleKind: 'ALL_DAY',
        startAt: null,
        endAt: null,
        startDate: '2026-08-30',
        endDateExclusive: '2026-09-01',
        sourceTimeZone: 'Asia/Seoul',
      },
      {
        id: 'all-day-ended',
        title: '종료된 종일',
        scheduleKind: 'ALL_DAY',
        startAt: null,
        endAt: null,
        startDate: '2026-08-30',
        endDateExclusive: '2026-08-31',
        sourceTimeZone: 'Asia/Seoul',
      },
      timedEvent('morning', '2026-08-31T00:00:00Z'),
      timedEvent('evening', '2026-08-31T09:00:00Z'),
      timedEvent('overnight', '2026-08-30T14:00:00Z', '2026-08-31T01:00:00Z'),
      timedEvent('tomorrow', '2026-09-01T00:00:00Z'),
    ];

    const result = buildHomeOverview([], events, NOW, 'Asia/Seoul');

    expect(result.dateKey).toBe('2026-08-31');
    expect(result.todayEventCount).toBe(4);
    expect(result.todayEvents).toHaveLength(HOME_OVERVIEW_ITEM_LIMIT);
    expect(result.todayEvents.map(({ id }) => id)).toEqual([
      'all-day-active',
      'overnight',
      'morning',
    ]);
  });

  it('does not fail open for invalid dates or time zones', () => {
    expect(dateKeyInTimeZone(new Date('invalid'), 'Asia/Seoul')).toBeNull();
    expect(dateKeyInTimeZone(NOW, 'Not/AZone')).toBeNull();

    const event = timedEvent('invalid-event', 'not-a-date');
    const result = buildHomeOverview([], [event], NOW, 'Not/AZone');
    expect(result.dateKey).toBe('');
    expect(result.todayEvents).toEqual([]);
  });
});

describe('owner remote hostname disclosure', () => {
  it('matches only a valid configured hostname without embedding a personal domain', () => {
    expect(normalizeOwnerRemoteAppHostname('memo.example.com')).toBe('memo.example.com');
    expect(normalizeOwnerRemoteAppHostname('MEMO.EXAMPLE.COM')).toBe('memo.example.com');
    expect(normalizeOwnerRemoteAppHostname(' memo.example.com')).toBeNull();
    expect(normalizeOwnerRemoteAppHostname('localhost')).toBeNull();
    expect(normalizeOwnerRemoteAppHostname('memo.example.com.')).toBeNull();

    expect(isExactOwnerRemoteAppHostname('memo.example.com', 'memo.example.com')).toBe(true);
    expect(isExactOwnerRemoteAppHostname('MEMO.EXAMPLE.COM', 'memo.example.com')).toBe(true);
    expect(isExactOwnerRemoteAppHostname('calendar.example.com', 'memo.example.com')).toBe(false);
    expect(isExactOwnerRemoteAppHostname('memo.example.com.evil.test', 'memo.example.com')).toBe(false);
    expect(isExactOwnerRemoteAppHostname('memo.example.com.', 'memo.example.com')).toBe(false);
    expect(isExactOwnerRemoteAppHostname(' memo.example.com', 'memo.example.com')).toBe(false);
    expect(isExactOwnerRemoteAppHostname('memo.example.com', null)).toBe(false);
  });
});
