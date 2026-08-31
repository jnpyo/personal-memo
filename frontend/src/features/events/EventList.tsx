import type { CalendarEvent } from '../../shared/api/types';
import { EventCalendarExport } from './EventCalendarExport';
import { EventCalendarSharing } from './EventCalendarSharing';
import type { CalendarSharingProtection } from './calendarSharingModel';

type Props = {
  events: CalendarEvent[];
  loading: boolean;
  error: string | null;
  interactionDisabled: boolean;
  online: boolean;
  onCalendarSharingProtectionChange: (state: CalendarSharingProtection) => void;
  onRetry: () => void;
};

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

export function eventTimeLabel(event: CalendarEvent): string {
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

export function EventList({
  events,
  loading,
  error,
  interactionDisabled,
  online,
  onCalendarSharingProtectionChange,
  onRetry,
}: Props) {
  return (
    <section className="event-section" aria-labelledby="events-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">CONFIRMED</span>
          <h2 id="events-title">일정</h2>
        </div>
        <span className="count-badge">{events.length}</span>
      </div>

      {loading && <p className="panel-state">일정을 불러오는 중…</p>}
      {!loading && error && (
        <div className="panel-state panel-state--error" role="alert">
          <p>{error}</p>
          <button type="button" className="secondary-button" onClick={onRetry}>
            다시 불러오기
          </button>
        </div>
      )}
      {!loading && !error && events.length === 0 && (
        <p className="panel-state">
          승인된 시간 정보가 있는 일정이 없습니다. 시간 미정 EVENT는 이 목록에 표시되지 않습니다.
        </p>
      )}

      {!loading && !error && events.length > 0 && (
        <div className="event-list">
          {events.map((event) => (
            <article className="event-row" key={event.id}>
              <div className="event-copy">
                <strong>{event.title}</strong>
                <span>{eventTimeLabel(event)}</span>
              </div>
            </article>
          ))}
        </div>
      )}

      <div className="event-calendar-actions" aria-label="일정 내보내기와 공유">
        <EventCalendarExport
          disabled={interactionDisabled}
          snapshotIdentity={JSON.stringify(events)}
        />
        <EventCalendarSharing
          disabled={interactionDisabled}
          online={online}
          onProtectionChange={onCalendarSharingProtectionChange}
        />
      </div>
    </section>
  );
}
