import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import {
  EVENT_CALENDAR_FILENAME,
  EventCalendarExport,
  EventCalendarExportDialog,
  EventCalendarExportDialogContent,
} from './EventCalendarExport';

describe('authenticated calendar export', () => {
  it('renders a compact signed-in launcher without a public or subscription URL', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarExport disabled={false} snapshotIdentity="events-v1" />,
    );

    expect(markup).toContain('캘린더 파일 (.ics)');
    expect(markup).not.toContain('href=');
    expect(markup).not.toContain('구독 주소');
    expect(markup).not.toContain('공유 링크 만들기');
  });

  it('keeps the launcher unavailable while the workspace is interaction-locked', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarExport disabled snapshotIdentity="events-v1" />,
    );

    expect(markup).toContain('disabled=""');
  });

  it('explains the one-time export boundary before enabling a download', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarExportDialog disabled={false} onClose={vi.fn()} />,
    );

    expect(markup).toContain('사용자가 승인한 시간 정보가 있는 일정만 포함합니다.');
    expect(markup).toContain('원문 메모, 할 일, 태그와 AI 분석 정보');
    expect(markup).toContain('1회 가져오기용 복사본');
    expect(markup).toContain('자동 동기화, 공유 링크와 알람을 만들지');
    expect(markup).toContain('최신 파일 미리보기');
    expect(markup).toMatch(/disabled=""[^>]*>미리본 파일 다운로드/);
    expect(markup).toContain('aria-labelledby="calendar-export-title"');
    expect(markup).toContain('aria-describedby="calendar-export-boundary"');
  });

  it('shows a non-downloadable empty state for a 204 calendar response', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarExportDialogContent
        disabled={false}
        loading={false}
        error={null}
        empty
        preview={null}
        downloadNotice={null}
        onLoadPreview={vi.fn()}
        onDownload={vi.fn()}
      />,
    );

    expect(markup).toContain('내보낼 승인 일정이 없습니다.');
    expect(markup).toContain('시간 미정 EVENT와 할 일은 파일로 만들지 않습니다.');
    expect(markup).toContain('role="status"');
    expect(markup).toMatch(
      /<button type="button" class="approve-button" disabled="">미리본 파일 다운로드<\/button>/,
    );
    expect(markup).not.toContain('iCalendar 파일 원문 미리보기');
  });

  it('uses a fixed non-user-controlled filename', () => {
    expect(EVENT_CALENDAR_FILENAME).toBe('personal-memo-calendar.ics');
  });
});
