import { useMemo } from 'react';
import type { CalendarEvent, Task } from '../../shared/api/types';
import { eventTimeLabel } from '../events/EventList';
import {
  buildHomeOverview,
  homeTaskDueLabel,
  isExactOwnerRemoteAppHostname,
  OWNER_REMOTE_APP_HOSTNAME,
} from './homeOverviewModel';

export type HomePendingReview = {
  title: string | null;
  state: 'REVIEW_REQUIRED' | 'POSTPONED';
};

type Props = {
  tasks: readonly Task[];
  events: readonly CalendarEvent[];
  pendingReview: HomePendingReview | null;
  tasksLoading?: boolean;
  eventsLoading?: boolean;
  tasksError?: string | null;
  eventsError?: string | null;
  now?: Date;
  timeZone?: string;
  currentHostname?: string;
  ownerRemoteAppHostname?: string | null;
  captureAnchorId?: string;
  tasksAnchorId?: string;
  eventsAnchorId?: string;
  reviewAnchorId?: string;
};

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Seoul';
}

function currentBrowserHostname(): string {
  return typeof window === 'undefined' ? '' : window.location.hostname;
}

export function HomeOverview({
  tasks,
  events,
  pendingReview,
  tasksLoading = false,
  eventsLoading = false,
  tasksError = null,
  eventsError = null,
  now = new Date(),
  timeZone = browserTimeZone(),
  currentHostname = currentBrowserHostname(),
  ownerRemoteAppHostname = OWNER_REMOTE_APP_HOSTNAME,
  captureAnchorId = 'memo-content',
  tasksAnchorId = 'tasks-title',
  eventsAnchorId = 'events-title',
  reviewAnchorId = 'review-pending',
}: Props) {
  const overview = useMemo(
    () => buildHomeOverview(tasks, events, now, timeZone),
    [events, now, tasks, timeZone],
  );
  const hiddenEventCount = overview.todayEventCount - overview.todayEvents.length;
  const hiddenTaskCount = overview.openTaskCount - overview.priorityTasks.length;

  return (
    <section className="home-overview" aria-labelledby="home-overview-title">
      <header className="home-overview__heading">
        <div>
          <span className="eyebrow">TODAY</span>
          <h2 id="home-overview-title">오늘</h2>
        </div>
        <time dateTime={overview.dateKey}>{overview.dateLabel}</time>
      </header>

      <ul className="home-overview__counts" aria-label="오늘 요약 건수">
        <li>{`오늘 일정 ${eventsLoading || eventsError ? '확인 중' : overview.todayEventCount}`}</li>
        <li>{`우선 할 일 ${tasksLoading || tasksError ? '확인 중' : overview.priorityTasks.length}`}</li>
        <li>{`검토 대기 ${pendingReview ? 1 : 0}`}</li>
      </ul>

      {isExactOwnerRemoteAppHostname(currentHostname, ownerRemoteAppHostname) && (
        <p className="home-overview__remote-address">
          현재 접속 주소: <strong>{currentHostname}</strong>
          <small>주소 표시이며 서버·터널·분석 모델 상태 확인을 뜻하지 않습니다.</small>
        </p>
      )}

      <nav className="home-overview__quick-links" aria-label="오늘 화면 빠른 이동">
        <a href={`#${captureAnchorId}`}>메모 적기</a>
        {pendingReview && <a href={`#${reviewAnchorId}`}>분석 제안 검토</a>}
        <a href={`#${tasksAnchorId}`}>전체 할 일</a>
        <a href={`#${eventsAnchorId}`}>전체 일정</a>
      </nav>

      {pendingReview && (
        <aside className="home-overview__review" aria-labelledby="home-review-title">
          <div>
            <h3 id="home-review-title">검토 대기 중인 분석 제안</h3>
            <p>
              {pendingReview.title ? `“${pendingReview.title}” 제안이 ` : '분석 제안이 '}
              검토를 기다립니다. 승인 전에는 어떤 항목도 적용되지 않습니다.
            </p>
          </div>
          <a href={`#${reviewAnchorId}`}>
            {pendingReview.state === 'POSTPONED' ? '보류한 검토로 이동' : '검토 위치로 이동'}
          </a>
        </aside>
      )}

      <div className="home-overview__grid">
        <section className="home-overview__panel" aria-labelledby="home-priority-tasks-title">
          <header>
            <h3 id="home-priority-tasks-title">우선 할 일</h3>
            {!tasksLoading && !tasksError && <span>{overview.openTaskCount}</span>}
          </header>
          {tasksLoading && <p className="home-overview__state" role="status">할 일을 확인하는 중…</p>}
          {!tasksLoading && tasksError && (
            <p className="home-overview__state home-overview__state--error" role="alert">
              할 일 요약을 표시하지 못했습니다. 전체 할 일에서 다시 확인해 주세요.
            </p>
          )}
          {!tasksLoading && !tasksError && overview.priorityTasks.length === 0 && (
            <p className="home-overview__state">미완료 할 일이 없습니다.</p>
          )}
          {!tasksLoading && !tasksError && overview.priorityTasks.length > 0 && (
            <ul className="home-overview__list">
              {overview.priorityTasks.map((task) => (
                <li key={task.id}>
                  <strong>{task.title}</strong>
                  <span className={task.overdue ? 'overdue-label' : undefined}>
                    {homeTaskDueLabel(task, timeZone)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {!tasksLoading && !tasksError && hiddenTaskCount > 0 && (
            <p className="home-overview__more">그 외 미완료 {hiddenTaskCount}개</p>
          )}
          <a className="home-overview__all-link" href={`#${tasksAnchorId}`}>전체 할 일 보기</a>
        </section>

        <section className="home-overview__panel" aria-labelledby="home-today-events-title">
          <header>
            <h3 id="home-today-events-title">오늘 일정</h3>
            {!eventsLoading && !eventsError && <span>{overview.todayEventCount}</span>}
          </header>
          {eventsLoading && <p className="home-overview__state" role="status">오늘 일정을 확인하는 중…</p>}
          {!eventsLoading && eventsError && (
            <p className="home-overview__state home-overview__state--error" role="alert">
              오늘 일정 요약을 표시하지 못했습니다. 전체 일정에서 다시 확인해 주세요.
            </p>
          )}
          {!eventsLoading && !eventsError && overview.todayEvents.length === 0 && (
            <p className="home-overview__state">오늘로 확인된 일정이 없습니다.</p>
          )}
          {!eventsLoading && !eventsError && overview.todayEvents.length > 0 && (
            <ul className="home-overview__list">
              {overview.todayEvents.map((event) => (
                <li key={event.id}>
                  <strong>{event.title}</strong>
                  <span>{eventTimeLabel(event)}</span>
                </li>
              ))}
            </ul>
          )}
          {!eventsLoading && !eventsError && hiddenEventCount > 0 && (
            <p className="home-overview__more">그 외 오늘 일정 {hiddenEventCount}개</p>
          )}
          <a className="home-overview__all-link" href={`#${eventsAnchorId}`}>전체 일정 보기</a>
        </section>
      </div>
    </section>
  );
}
