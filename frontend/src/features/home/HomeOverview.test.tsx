import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { CalendarEvent, Task } from '../../shared/api/types';
import { HomeOverview } from './HomeOverview';

const NOW = new Date('2026-08-31T03:00:00Z');

const tasks: Task[] = Array.from({ length: 4 }, (_, index) => ({
  id: `task-${index + 1}`,
  title: `우선 할 일 ${index + 1}`,
  status: 'TODO',
  dueAt: `2026-08-3${index + 1}T09:00:00Z`,
  dueDate: null,
  overdue: index === 0,
}));

const events: CalendarEvent[] = Array.from({ length: 4 }, (_, index) => ({
  id: `event-${index + 1}`,
  title: `오늘 일정 ${index + 1}`,
  scheduleKind: 'TIMED',
  startAt: `2026-08-31T0${index}:00:00Z`,
  endAt: null,
  startDate: null,
  endDateExclusive: null,
  sourceTimeZone: 'Asia/Seoul',
}));

describe('home overview', () => {
  it('renders bounded read-only summaries, pending review, and accessible anchors', () => {
    const markup = renderToStaticMarkup(
      <HomeOverview
        tasks={tasks}
        events={events}
        pendingReview={{ title: '디스코드 접속', state: 'POSTPONED' }}
        now={NOW}
        timeZone="Asia/Seoul"
        currentHostname="memo.example.com"
        ownerRemoteAppHostname="memo.example.com"
      />,
    );

    expect(markup).toContain('aria-labelledby="home-overview-title"');
    expect(markup).toContain('<h2 id="home-overview-title">오늘</h2>');
    expect(markup).toContain('aria-label="오늘 요약 건수"');
    expect(markup).toContain('<li>검토 대기 1</li>');
    expect(markup).toContain('aria-label="오늘 화면 빠른 이동"');
    expect(markup).toContain('href="#memo-content"');
    expect(markup).toContain('href="#review-pending"');
    expect(markup).toContain('href="#tasks-title"');
    expect(markup).toContain('href="#events-title"');
    expect(markup).toContain('승인 전에는 어떤 항목도 적용되지 않습니다.');
    expect(markup).toContain('현재 접속 주소:');
    expect(markup).toContain('서버·터널·분석 모델 상태 확인을 뜻하지 않습니다.');
    expect(markup).toContain('우선 할 일 3');
    expect(markup).not.toContain('우선 할 일 4</strong>');
    expect(markup).toContain('오늘 일정 3');
    expect(markup).not.toContain('오늘 일정 4</strong>');
    expect(markup).toContain('그 외 미완료 1개');
    expect(markup).toContain('그 외 오늘 일정 1개');
    expect(markup).not.toContain('Cloudflare');
    expect(markup).not.toContain('Ollama');
    expect(markup).not.toContain('Healthy');
    expect(markup).not.toContain('DB 정상');
  });

  it('does not show the owner address wording on any other host', () => {
    const markup = renderToStaticMarkup(
      <HomeOverview
        tasks={[]}
        events={[]}
        pendingReview={null}
        now={NOW}
        timeZone="Asia/Seoul"
        currentHostname="calendar.example.com"
        ownerRemoteAppHostname="memo.example.com"
      />,
    );

    expect(markup).not.toContain('현재 접속 주소:');
    expect(markup).not.toContain('분석 제안 검토');
    expect(markup).toContain('<li>검토 대기 0</li>');
    expect(markup).toContain('미완료 할 일이 없습니다.');
    expect(markup).toContain('오늘로 확인된 일정이 없습니다.');
  });

  it('keeps loading and error states distinct from empty claims', () => {
    const markup = renderToStaticMarkup(
      <HomeOverview
        tasks={[]}
        events={[]}
        pendingReview={null}
        tasksLoading
        eventsError="bounded test error"
        now={NOW}
        timeZone="Asia/Seoul"
        currentHostname="localhost"
      />,
    );

    expect(markup).toContain('할 일을 확인하는 중…');
    expect(markup).toContain('오늘 일정 요약을 표시하지 못했습니다.');
    expect(markup).not.toContain('미완료 할 일이 없습니다.');
    expect(markup).not.toContain('오늘로 확인된 일정이 없습니다.');
    expect(markup).not.toContain('bounded test error');
  });
});
