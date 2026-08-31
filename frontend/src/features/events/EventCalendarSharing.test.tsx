import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { CalendarEvent, CalendarFeedPublicationCapability } from '../../shared/api/types';
import {
  canCommitCalendarFeedOverviewResponse,
  CalendarFeedDisclosureFields,
  CalendarFeedEventSelector,
  CalendarFeedSecretPanel,
  CalendarSharingConfirmation,
  createUnavailableCalendarFeedOverview,
  EventCalendarSharing,
  EventCalendarSharingDialog,
  ExternalCalendarFeedPublicationConfirmation,
  prepareCalendarFeedSubscription,
  prepareCreatedCalendarFeedSubscription,
} from './EventCalendarSharing';

const event: CalendarEvent = {
  id: 'event-a',
  title: '디스코드 접속',
  scheduleKind: 'TIMED',
  startAt: '2026-08-25T09:00:00Z',
  endAt: null,
  startDate: null,
  endDateExclusive: null,
  sourceTimeZone: 'Asia/Seoul',
};

describe('recipient calendar sharing UI safety', () => {
  it('renders a management launcher without a public href or token', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarSharing
        disabled={false}
        online
        onProtectionChange={vi.fn()}
      />,
    );

    expect(markup).toContain('일정 공유 관리');
    expect(markup).not.toContain('href=');
    expect(markup).not.toContain('token=');
  });

  it('keeps BUSY_ONLY selected while explaining TITLE disclosure', () => {
    const markup = renderToStaticMarkup(
      <CalendarFeedDisclosureFields
        value="BUSY_ONLY"
        disabled={false}
        onChange={vi.fn()}
      />,
    );

    expect(markup).toMatch(/<input[^>]*checked=""[^>]*value="BUSY_ONLY"/);
    expect(markup).not.toMatch(/<input[^>]*checked=""[^>]*value="TITLE"/);
    expect(markup).toContain('일정 제목 없이 날짜·시각');
    expect(markup).toContain('승인된 제목까지');
  });

  it('starts every eligible event unchecked and states that future events are not automatic', () => {
    const markup = renderToStaticMarkup(
      <CalendarFeedEventSelector
        events={[event]}
        selectedEventIds={[]}
        disabled={false}
        truncated={false}
        onToggle={vi.fn()}
      />,
    );

    expect(markup).toContain('처음에는 모두 선택 해제');
    expect(markup).toContain('새 일정과 미래 일정은 자동으로 추가되지 않습니다.');
    expect(markup).not.toContain('checked=""');
  });

  it('shows the one-time URL as read-only copy-only text with no clickable link', () => {
    const url = `https://memo.example.test/calendar/v1/feed.ics?token=${'a'.repeat(42)}A`;
    const markup = renderToStaticMarkup(
      <CalendarFeedSecretPanel
        oneTime={{
          action: 'CREATED',
          displayName: '가족 공유',
          publicationMode: 'LOCAL_ONLY',
          url,
        }}
        copied={false}
        onCopy={vi.fn()}
        onDone={vi.fn()}
      />,
    );

    expect(markup).toContain('한 번만 표시되는 구독 주소');
    expect(markup).toContain('로컬·격리 검증용');
    expect(markup).toContain('readOnly');
    expect(markup).toContain('구독 주소 복사');
    expect(markup).not.toContain('href=');
  });

  it('labels a server-owned public HTTPS URL without turning it into a link', () => {
    const url = `https://calendar.example.com/calendar/v1/feed.ics?token=${'a'.repeat(42)}A`;
    const markup = renderToStaticMarkup(
      <CalendarFeedSecretPanel
        oneTime={{
          action: 'PUBLISHED',
          displayName: '가족 공유',
          publicationMode: 'PUBLIC_HTTPS',
          url,
        }}
        copied={false}
        onCopy={vi.fn()}
        onDone={vi.fn()}
      />,
    );

    expect(markup).toContain('새 외부 공개 구독 주소');
    expect(markup).toContain('서버가 지정한 공개 HTTPS 일정 전용 주소');
    expect(markup).toContain('Cloudflare가 query bearer와 요청 metadata를 처리합니다.');
    expect(markup).toContain('readOnly');
    expect(markup).not.toContain('href=');
  });

  it('disables sharing management while offline instead of queuing a mutation', () => {
    const markup = renderToStaticMarkup(
      <EventCalendarSharing
        disabled={false}
        online={false}
        onProtectionChange={vi.fn()}
      />,
    );

    expect(markup).toContain('disabled=""');
    expect(markup).toContain('온라인에서만 관리');
  });

  it('renders an assertive focus target for privacy or destructive confirmation content', () => {
    const markup = renderToStaticMarkup(
      <CalendarSharingConfirmation label="일정 공유 폐기 확인">
        <p>공유를 폐기합니다.</p>
        <button type="button">폐기 확인</button>
      </CalendarSharingConfirmation>,
    );

    expect(markup).toContain('role="alertdialog"');
    expect(markup).toContain('aria-label="일정 공유 폐기 확인"');
    expect(markup).toContain('aria-live="assertive"');
    expect(markup).toContain('aria-atomic="true"');
    expect(markup).toContain('tabindex="-1"');
  });

  it('starts with unavailable publication authority and no stale overview data', () => {
    const overview = createUnavailableCalendarFeedOverview();
    const markup = renderToStaticMarkup(
      <EventCalendarSharingDialog
        disabled={false}
        online
        onProtectionChange={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(overview).toEqual({
      publicationCapability: null,
      feeds: [],
      eligibleEvents: [],
      eligibleTruncated: false,
    });
    expect(markup).toContain('공개 구독 주소 권한을 확인하는 중입니다.');
    expect(markup).toContain('확인 전에는 공유를 만들거나 주소를 교체하지 않습니다.');
  });

  it('prepares a valid one-time URL before allowing a secret-bearing mutation to start', () => {
    const request = vi.fn();
    const secret = 'A'.repeat(43);
    const startMutation = (
      capability: CalendarFeedPublicationCapability | null,
      localOrigin: string,
    ) => {
      const subscription = prepareCalendarFeedSubscription(capability, localOrigin, secret);
      request();
      return subscription;
    };

    expect(() => startMutation(null, 'https://memo.example.test')).toThrow(
      'Calendar feed publication capability is unavailable',
    );
    expect(() => startMutation(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'http://calendar.example.test',
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://memo.example.test',
    )).toThrow('Invalid calendar feed origin');
    expect(request).not.toHaveBeenCalled();

    expect(startMutation(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'https://calendar.example.test',
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://private-memo.example.test',
    )).toEqual({
      publicationMode: 'PUBLIC_HTTPS',
      url: `https://calendar.example.test/calendar/v1/feed.ics?token=${secret}`,
    });
    expect(request).toHaveBeenCalledOnce();
  });

  it('does not build or reveal a one-time URL when create stays local in a public deployment', () => {
    const secret = 'A'.repeat(43);
    expect(prepareCreatedCalendarFeedSubscription(
      {
        mode: 'PUBLIC_HTTPS',
        publicOrigin: 'https://calendar.example.test',
        consentPolicyVersion: 'calendar-feed-public-v1',
      },
      'https://private-memo.example.test',
      secret,
    )).toBeNull();
    expect(prepareCreatedCalendarFeedSubscription(
      { mode: 'LOCAL_ONLY', publicOrigin: null, consentPolicyVersion: null },
      'https://private-memo.example.test',
      secret,
    )).toEqual({
      publicationMode: 'LOCAL_ONLY',
      url: `https://private-memo.example.test/calendar/v1/feed.ics?token=${secret}`,
    });
  });

  it('requires an unchecked explicit consent before starting external publication', () => {
    const markup = renderToStaticMarkup(
      <ExternalCalendarFeedPublicationConfirmation
        disclosureMode="BUSY_ONLY"
        accepted={false}
        disabled={false}
        onAcceptedChange={vi.fn()}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain('날짜와 시간을');
    expect(markup).toContain('새 일정은 자동으로 추가되지 않습니다.');
    expect(markup).toContain('Cloudflare가 query bearer와 요청 metadata를 처리합니다.');
    expect(markup).toContain('저장·캐시된 사본은 앱이 회수할 수 없습니다.');
    expect(markup).toContain('기존 주소는 즉시 무효화');
    expect(markup).toContain('새 공개 HTTPS 주소는 한 번만 표시');
    expect(markup).toMatch(/<input[^>]*type="checkbox"[^>]*\/?>/);
    expect(markup).not.toMatch(/<input[^>]*type="checkbox"[^>]*checked/);
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>동의하고 외부 공개 시작<\/button>/);
  });

  it('states TITLE disclosure exactly when public consent is requested', () => {
    const markup = renderToStaticMarkup(
      <ExternalCalendarFeedPublicationConfirmation
        disclosureMode="TITLE"
        accepted
        disabled={false}
        onAcceptedChange={vi.fn()}
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    expect(markup).toContain('제목과 시간을');
    expect(markup).not.toContain('날짜와 시간을');
    expect(markup).not.toMatch(/<button[^>]*disabled=""[^>]*>동의하고 외부 공개 시작<\/button>/);
  });

  it('rejects aborted and late overview responses before they can overwrite current authority', () => {
    const committed: string[] = [];
    const currentGeneration = 2;
    const commit = (label: string, requestGeneration: number, aborted: boolean) => {
      if (canCommitCalendarFeedOverviewResponse(
        currentGeneration,
        requestGeneration,
        aborted,
      )) committed.push(label);
    };

    commit('late', 1, false);
    commit('aborted', 2, true);
    commit('current', 2, false);

    expect(committed).toEqual(['current']);
  });
});
