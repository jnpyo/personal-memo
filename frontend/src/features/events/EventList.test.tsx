import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { CalendarEvent } from '../../shared/api/types';
import { EventList, eventTimeLabel } from './EventList';

const timed: CalendarEvent = {
  id: 'event-1',
  title: '디스코드 접속',
  scheduleKind: 'TIMED',
  startAt: '2026-08-24T09:00:00Z',
  endAt: null,
  startDate: null,
  endDateExclusive: null,
  sourceTimeZone: 'Asia/Seoul',
};

it('formats a timed event in its canonical source time zone without inventing an end', () => {
  const label = eventTimeLabel(timed);

  expect(label).toContain('2026');
  expect(label).toContain('오후 6:00');
  expect(label).toContain('Asia/Seoul');
  expect(label).not.toContain('~');
});

describe('event list', () => {
  it('renders timed and all-day canonical schedules', () => {
    const allDay: CalendarEvent = {
      id: 'event-2',
      title: '휴가',
      scheduleKind: 'ALL_DAY',
      startAt: null,
      endAt: null,
      startDate: '2026-08-24',
      endDateExclusive: '2026-08-27',
      sourceTimeZone: 'Asia/Seoul',
    };

    const markup = renderToStaticMarkup(
      <EventList
        events={[timed, allDay]}
        loading={false}
        error={null}
        interactionDisabled={false}
        online
        onCalendarSharingProtectionChange={vi.fn()}
        onRetry={vi.fn()}
      />,
    );

    expect(markup).toContain('디스코드 접속');
    expect(markup).toContain('휴가');
    expect(markup).toContain('2026-08-24부터 2026-08-27 전까지 · 종일');
  });

  it('explains that title-only EVENT items are intentionally absent', () => {
    const markup = renderToStaticMarkup(
      <EventList
        events={[]}
        loading={false}
        error={null}
        interactionDisabled={false}
        online
        onCalendarSharingProtectionChange={vi.fn()}
        onRetry={vi.fn()}
      />,
    );

    expect(markup).toContain('시간 미정 EVENT는 이 목록에 표시되지 않습니다.');
  });
});
