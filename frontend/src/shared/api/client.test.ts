import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER,
  createApiClient,
  EXPECTED_OWNER_ID_HEADER,
  SESSION_OWNER_CHANGED_EVENT,
  SessionScopeChangedError,
} from './client';
import { ReviewOutcomeContractError } from './reviewOutcomeDecoder';
import { AnalysisPathEvidenceSummaryContractError } from './analysisPathEvidenceSummaryDecoder';
import type { ApplyProposalRequest, MemoView, Proposal } from './types';

const memo: MemoView = {
  id: 'memo-1',
  currentRevision: 2,
  content: '수정한 원문',
  pinned: false,
  status: 'ACTIVE',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-08-05T00:00:00.000Z',
  sourceTimeZone: 'Asia/Seoul',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

function okResponse(body: unknown = memo): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function csrfResponse(token = 'csrf-test-token'): Response {
  return okResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token });
}

function calendarResponse(body: string): Response {
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/calendar; charset=UTF-8' },
  });
}

function testClient(defaultResponse: unknown = memo) {
  const applicationFetch = vi.fn(
    async (...request: [RequestInfo | URL, RequestInit?]) => {
      void request;
      return okResponse(defaultResponse);
    },
  );
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v1/auth/csrf') return csrfResponse();
    return applicationFetch(input, init);
  });
  return { client: createApiClient(fetchMock), fetchMock, applicationFetch };
}

function validProposal() {
  return {
    schemaVersion: '1',
    memoId: '8dd29246-4ec2-4e7f-bbf9-a3ff316acdd4',
    memoRevision: 1,
    suggestedTitle: { value: '운영체제 과제', confidence: 0.9, needsConfirmation: true },
    typeCandidates: [{ value: 'TASK', score: 0.9 }],
    dateCandidates: [],
    tagCandidates: [],
    itemCandidates: [],
    relationCandidates: [],
    ambiguityReasons: [],
    providerMetadata: {},
  };
}

function validProposalV2() {
  return {
    ...validProposal(),
    schemaVersion: '2',
    dateCandidates: [
      {
        candidateId: 'date-1',
        surfaceText: '11월 25일',
        value: '2026-11-25',
        precision: 'DATE_ONLY',
        timeSpecified: false,
        confidence: 0.9,
        ambiguityReasons: [],
      },
    ],
    itemCandidates: [
      {
        candidateId: 'item-1',
        dueDateCandidateId: 'date-1',
        kind: 'TASK',
        title: '운영체제 과제',
        sourceSpan: null,
        action: '제출',
        object: '운영체제 과제',
        confidence: 0.9,
      },
    ],
  };
}

function validProposalV3() {
  return {
    ...validProposalV2(),
    schemaVersion: '3',
    itemCandidates: [
      {
        candidateId: 'item-1',
        dueDateCandidateId: null,
        eventScheduleCandidates: [
          {
            candidateId: 'event-schedule-1',
            mode: 'ALL_DAY',
            startDateCandidateId: 'date-1',
            end: null,
            score: 0.85,
          },
        ],
        suggestedEventScheduleCandidateId: 'event-schedule-1',
        kind: 'EVENT',
        title: '운영체제 일정',
        sourceSpan: null,
        action: '참석',
        object: '운영체제 일정',
        confidence: 0.9,
      },
    ],
  };
}

function validReviewOutcomeSummary() {
  return {
    schemaVersion: '1',
    comparisonPolicyVersion: 'review-default-v3',
    cohort: {
      basis: 'PROPOSAL_CREATED_AT',
      days: 14,
      fromInclusive: '2026-07-25T00:00:00Z',
      toExclusive: '2026-08-08T00:00:00Z',
      maxProposals: 1_000,
    },
    proposals: {
      total: 0,
      withApplication: 0,
      currentStates: {
        queued: 0,
        running: 0,
        reviewRequired: 0,
        currentPostponed: 0,
        failed: 0,
        stale: 0,
        applied: 0,
        rejected: 0,
        other: 0,
      },
    },
    latestApplications: { none: 0, applied: 0, undone: 0 },
    outcomes: {
      exact: 0,
      corrected: 0,
      userResolved: 0,
      unclassifiable: 0,
      correctedFields: { type: 0, title: 0, tags: 0, items: 0, due: 0 },
    },
    byAnalysisVersion: [],
  };
}

function validAnalysisPathEvidenceSummary() {
  return {
    schemaVersion: '1',
    aggregationPolicyVersion: 'analysis-path-evidence-summary-v1',
    cohort: {
      basis: 'ANALYSIS_RUN_CREATED_AT',
      days: 14,
      fromInclusive: '2026-07-25T00:00:00Z',
      toExclusive: '2026-08-08T00:00:00Z',
      maxRuns: 1_000,
    },
    runs: { total: 0, withDispatch: 0, withoutDispatch: 0 },
    localDecisionEvidence: { current: 0, legacy: 0 },
    dispatchRoutes: {
      localModel: 0,
      externalMemoTransfer: 0,
      builtInFake: 0,
      legacyOrOther: 0,
    },
    lifecycle: { prepared: 0, running: 0, finalized: 0 },
    invocationModes: { legacyUnknown: 0, uncertaintyOnly: 0, aiPreferred: 0 },
    invocationReasons: {
      legacyUnknown: 0,
      semanticUncertainty: 0,
      aiPreferredPolicy: 0,
    },
    localModelContributions: {
      notRecorded: 0,
      pending: 0,
      acceptedChanged: 0,
      acceptedUnchanged: 0,
      localFallback: 0,
    },
    approvedCorrectionSnapshots: { withSignals: 0, totalSignals: 0 },
    fallbackReasons: {
      defaultRecordFallback: 0,
      unparsedTemporalCue: 0,
      unrecognizedActionCue: 0,
      lowTypeMargin: 0,
      tagUncertainty: 0,
      dateUncertainty: 0,
      unresolvedReference: 0,
      incompleteTask: 0,
      multiIntent: 0,
      candidateLimit: 0,
      localConflict: 0,
    },
    changedFields: {
      suggestedTitle: 0,
      typeCandidates: 0,
      dateCandidates: 0,
      tagCandidates: 0,
      itemCandidates: 0,
      relationCandidates: 0,
      ambiguityReasons: 0,
    },
  };
}

function validCalendarFeedSummary() {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    displayName: '가족 공유',
    disclosureMode: 'BUSY_ONLY',
    status: 'ACTIVE',
    version: 1,
    eventCount: 1,
    createdAt: '2026-08-25T00:00:00Z',
    updatedAt: '2026-08-25T00:00:00Z',
    rotatedAt: '2026-08-25T00:00:00Z',
    revokedAt: null,
    publicationScope: 'LOCAL_ONLY',
    publicConsentPolicyVersion: null,
    publicConsentGrantedAt: null,
  };
}

function validCalendarFeedDetail() {
  return {
    ...validCalendarFeedSummary(),
    entries: [{
      id: '22222222-2222-4222-8222-222222222222',
      eventId: '33333333-3333-4333-8333-333333333333',
      title: '디스코드 접속',
      state: 'ACTIVE',
      sequence: 0,
      scheduleKind: 'TIMED',
      startAt: '2026-08-25T09:00:00Z',
      endAt: null,
      startDate: null,
      endDateExclusive: null,
      sourceTimeZone: 'Asia/Seoul',
    }],
  };
}

describe('memo API client', () => {
  it('loads graph and current memo detail through uncached owner-scoped reads', async () => {
    const { client, applicationFetch } = testClient(memo);
    client.setSessionOwner('user-a');

    await client.graph(999);
    await client.memo('memo-1');

    const expectedRead = {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    };
    expect(applicationFetch).toHaveBeenNthCalledWith(
      1,
      '/api/v1/graph/home?limit=100',
      expectedRead,
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      2,
      '/api/v1/memos/memo-1',
      expectedRead,
    );
  });

  it('loads a bounded confirmed-event list through an uncached owner-scoped read', async () => {
    const { client, applicationFetch } = testClient([]);
    client.setSessionOwner('user-a');

    await client.events(999);

    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/events?limit=100', {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    });
  });

  it('loads metadata-only calendar feed management reads with owner and no-store scope', async () => {
    const detail = validCalendarFeedDetail();
    const applicationFetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/capabilities')) {
        return okResponse({
          mode: 'LOCAL_ONLY',
          publicOrigin: null,
          consentPolicyVersion: null,
        });
      }
      if (url.endsWith('/eligible-events')) {
        return okResponse({ items: [], truncated: false });
      }
      if (url === '/api/v1/calendar-feeds') return okResponse([validCalendarFeedSummary()]);
      return okResponse(detail);
    });
    const client = createApiClient(applicationFetch);
    client.setSessionOwner('user-a');

    await client.calendarFeedPublicationCapability();
    await client.calendarFeedEligibleEvents();
    await client.calendarFeeds();
    await client.calendarFeed('feed/with space');

    const expectedRead = {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    };
    expect(applicationFetch).toHaveBeenNthCalledWith(
      1,
      '/api/v1/calendar-feeds/capabilities',
      expectedRead,
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      2,
      '/api/v1/calendar-feeds/eligible-events',
      expectedRead,
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      3,
      '/api/v1/calendar-feeds',
      expectedRead,
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      4,
      '/api/v1/calendar-feeds/feed%2Fwith%20space',
      expectedRead,
    );
  });

  it('sends every calendar feed mutation with CSRF, owner scope and caller idempotency', async () => {
    const detail = validCalendarFeedDetail();
    const { client, applicationFetch } = testClient(detail);
    client.setSessionOwner('user-a');
    const secret = 'a'.repeat(43);

    await client.createCalendarFeed({
      displayName: '가족 공유',
      disclosureMode: 'BUSY_ONLY',
      eventIds: ['event-a'],
      bearerSecret: secret,
    }, 'create-key');
    await client.updateCalendarFeed('feed/a', {
      displayName: '가족 일정',
      disclosureMode: 'TITLE',
      expectedVersion: 1,
    }, 'update-key');
    await client.rotateCalendarFeed('feed/a', {
      bearerSecret: secret,
      expectedVersion: 1,
    }, 'rotate-key');
    await client.enableExternalCalendarFeedPublication('feed/a', {
      bearerSecret: secret,
      expectedVersion: 1,
      consentPolicyVersion: 'calendar-feed-public-v1',
    }, 'public-key');
    await client.revokeCalendarFeed('feed/a', { expectedVersion: 1 }, 'revoke-key');
    await client.addCalendarFeedEvent('feed/a', {
      eventId: 'event-b',
      expectedVersion: 1,
    }, 'add-key');
    await client.removeCalendarFeedEvent(
      'feed/a',
      'entry/a',
      { expectedVersion: 1 },
      'remove-key',
    );

    expect(applicationFetch.mock.calls.map(([url, init]) => ({
      url,
      method: init?.method,
      key: new Headers(init?.headers).get('Idempotency-Key'),
      csrf: new Headers(init?.headers).get('X-CSRF-TOKEN'),
      owner: new Headers(init?.headers).get(EXPECTED_OWNER_ID_HEADER),
      body: init?.body,
      cache: init?.cache,
    }))).toEqual([
      {
        url: '/api/v1/calendar-feeds',
        method: 'POST',
        key: 'create-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({
          displayName: '가족 공유',
          disclosureMode: 'BUSY_ONLY',
          eventIds: ['event-a'],
          bearerSecret: secret,
        }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa',
        method: 'PATCH',
        key: 'update-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({
          displayName: '가족 일정',
          disclosureMode: 'TITLE',
          expectedVersion: 1,
        }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa/rotate',
        method: 'POST',
        key: 'rotate-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({ bearerSecret: secret, expectedVersion: 1 }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa/external-publication/enable',
        method: 'POST',
        key: 'public-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({
          bearerSecret: secret,
          expectedVersion: 1,
          consentPolicyVersion: 'calendar-feed-public-v1',
        }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa/revoke',
        method: 'POST',
        key: 'revoke-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({ expectedVersion: 1 }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa/events',
        method: 'POST',
        key: 'add-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({ eventId: 'event-b', expectedVersion: 1 }),
        cache: 'no-store',
      },
      {
        url: '/api/v1/calendar-feeds/feed%2Fa/events/entry%2Fa/remove',
        method: 'POST',
        key: 'remove-key',
        csrf: 'csrf-test-token',
        owner: 'user-a',
        body: JSON.stringify({ expectedVersion: 1 }),
        cache: 'no-store',
      },
    ]);
  });

  it('rejects a calendar feed response that exposes the bearer secret', async () => {
    const detail = { ...validCalendarFeedDetail(), bearerSecret: 'a'.repeat(43) };
    const client = createApiClient(async () => okResponse(detail));
    client.setSessionOwner('user-a');

    await expect(client.calendarFeed('feed-a')).rejects.toMatchObject({
      name: 'CalendarFeedContractError',
    });
  });

  it('loads an authenticated calendar Blob through an uncached owner-scoped read', async () => {
    const calendar = [
      'BEGIN:VCALENDAR',
      'VERSION:2.0',
      'BEGIN:VEVENT',
      'SUMMARY:디스코드 접속',
      'END:VEVENT',
      'END:VCALENDAR',
      '',
    ].join('\r\n');
    const applicationFetch = vi.fn(async (
      ...request: [RequestInfo | URL, RequestInit?]
    ) => {
      void request;
      return calendarResponse(calendar);
    });
    const client = createApiClient(applicationFetch);
    const controller = new AbortController();
    client.setSessionOwner('user-a');

    const result = await client.eventCalendarExport(controller.signal);

    expect(result).not.toBeNull();
    if (result === null) throw new Error('Expected a calendar Blob');
    expect(await result.text()).toBe(calendar);
    expect(result.type).toBe('text/calendar;charset=utf-8');
    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/events/calendar.ics', {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        Accept: 'text/calendar',
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    });
    const requestSignal = applicationFetch.mock.calls[0]?.[1]?.signal;
    expect(requestSignal).not.toBe(controller.signal);
    controller.abort();
    expect(requestSignal?.aborted).toBe(true);
  });

  it('normalizes an empty authenticated calendar export to null', async () => {
    const applicationFetch = vi.fn(async (
      ...request: [RequestInfo | URL, RequestInit?]
    ) => {
      void request;
      return new Response(null, { status: 204 });
    });
    const client = createApiClient(applicationFetch);
    client.setSessionOwner('user-a');

    await expect(client.eventCalendarExport()).resolves.toBeNull();
    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/events/calendar.ics', {
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        Accept: 'text/calendar',
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    });
  });

  it('rejects a non-calendar success response before it can be downloaded', async () => {
    const client = createApiClient(async () => okResponse({ unexpected: true }));
    client.setSessionOwner('user-a');

    await expect(client.eventCalendarExport()).rejects.toThrow(
      'Invalid calendar export response',
    );
  });

  it('discards a calendar Blob when the owner changes while its body is being decoded', async () => {
    let resolveBlob!: (blob: Blob) => void;
    const delayedBlob = new Promise<Blob>((resolve) => {
      resolveBlob = resolve;
    });
    const response = calendarResponse('BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n');
    const blobSpy = vi.spyOn(response, 'blob').mockImplementation(() => delayedBlob);
    const client = createApiClient(async () => response);
    client.setSessionOwner('user-a');

    const request = client.eventCalendarExport();
    await vi.waitFor(() => expect(blobSpy).toHaveBeenCalledTimes(1));
    client.invalidateSession();
    resolveBlob(new Blob(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'], {
      type: 'text/calendar;charset=utf-8',
    }));

    await expect(request).rejects.toBeInstanceOf(SessionScopeChangedError);
  });

  it('normalizes a session-aborted calendar fetch to a scope-change failure', async () => {
    let requestStarted = false;
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      requestStarted = true;
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'));
        }, { once: true });
      });
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    const request = client.eventCalendarExport();
    await vi.waitFor(() => expect(requestStarted).toBe(true));
    client.invalidateSession();

    await expect(request).rejects.toBeInstanceOf(SessionScopeChangedError);
  });

  it('loads an encoded, uncached neighborhood page with the caller abort signal', async () => {
    const neighborhood = {
      center: {
        id: 'tag:10000000-0000-0000-0000-000000000001',
        kind: 'TAG',
        label: '운영 체제',
        pinned: false,
        overdue: false,
        memoType: null,
        taskState: null,
      },
      neighbors: [],
      edges: [],
      truncated: false,
      nextCursor: null,
    };
    const { client, applicationFetch } = testClient(neighborhood);
    const controller = new AbortController();
    client.setSessionOwner('user-a');

    await client.graphNeighborhood(
      'TAG',
      '10000000-0000-0000-0000-000000000001',
      'opaque cursor/+',
      controller.signal,
      999,
    );

    expect(applicationFetch).toHaveBeenCalledWith(
      '/api/v1/graph/nodes/TAG/10000000-0000-0000-0000-000000000001/neighborhood?limit=20&cursor=opaque+cursor%2F%2B',
      {
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        headers: {
          'Content-Type': 'application/json',
          [EXPECTED_OWNER_ID_HEADER]: 'user-a',
        },
      },
    );
    const requestSignal = applicationFetch.mock.calls[0]?.[1]?.signal;
    expect(requestSignal).not.toBe(controller.signal);
    controller.abort();
    expect(requestSignal?.aborted).toBe(true);
  });

  it('posts a private memo search body with CSRF, owner scope and no idempotency key', async () => {
    const searchPage = {
      items: [{
        memoId: '11111111-1111-4111-8111-111111111111',
        currentRevision: 1,
        canonicalRevision: null,
        title: null,
        preview: '운영체제 메모',
        lifecycleStatus: 'ACTIVE',
        canonicalTags: [],
        taskState: 'NONE',
        overdue: false,
        pinned: false,
        revisedAt: '2026-08-11T03:00:00Z',
        matchedFields: ['BODY'],
      }],
      nextCursor: null,
      truncated: false,
    };
    const { client, applicationFetch } = testClient(searchPage);
    const controller = new AbortController();
    client.setSessionOwner('user-a');
    const body = {
      query: '운영체제',
      lifecycleStatus: 'ACTIVE' as const,
      taskState: 'NONE' as const,
      revisedFrom: '2026-08-01T00:00:00Z',
      limit: 20,
    };

    await client.searchMemos(body, controller.signal);

    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/search/memos', {
      method: 'POST',
      cache: 'no-store',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-test-token',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
      body: JSON.stringify(body),
    });
    expect(applicationFetch.mock.calls[0]?.[0]).not.toContain('?');
    expect(applicationFetch.mock.calls[0]?.[1]?.headers).not.toHaveProperty('Idempotency-Key');
    const requestSignal = applicationFetch.mock.calls[0]?.[1]?.signal;
    controller.abort();
    expect(requestSignal?.aborted).toBe(true);
  });

  it('retries a CSRF failure with the exact same search body and cursor', async () => {
    let csrfAttempt = 0;
    let searchAttempt = 0;
    const searchRequests: RequestInit[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/auth/csrf') {
        csrfAttempt += 1;
        return csrfResponse(`csrf-${csrfAttempt}`);
      }
      searchRequests.push(init ?? {});
      searchAttempt += 1;
      if (searchAttempt === 1) {
        return new Response(JSON.stringify({ code: 'CSRF_TOKEN_INVALID' }), {
          status: 403,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return okResponse({ items: [], nextCursor: null, truncated: false });
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');
    const body = {
      query: '운영체제',
      lifecycleStatus: 'ACTIVE' as const,
      limit: 20,
      cursor: 'opaque_cursor-1',
    };

    await client.searchMemos(body);

    expect(searchRequests).toHaveLength(2);
    expect(searchRequests[0]?.body).toBe(JSON.stringify(body));
    expect(searchRequests[1]?.body).toBe(JSON.stringify(body));
    expect(searchRequests[0]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf-1' });
    expect(searchRequests[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf-2' });
  });

  it('rejects a noncanonical neighborhood center before sending a request', async () => {
    const { client, applicationFetch } = testClient({});
    client.setSessionOwner('user-a');

    await expect(client.graphNeighborhood(
      'TAG',
      '10000000-0000-0000-0000-00000000000A',
    )).rejects.toThrow(/expectedCenter/);
    expect(applicationFetch).not.toHaveBeenCalled();
  });

  it('reuses the exact pin body and caller-owned key on retry', async () => {
    const { client, applicationFetch } = testClient({
      id: 'memo-1',
      pinned: true,
      updated: true,
    });
    client.setSessionOwner('user-a');

    await client.setMemoPinned('memo-1', true, 'stable-pin-key');
    await client.setMemoPinned('memo-1', true, 'stable-pin-key');

    const expectedRequest = {
      method: 'PATCH',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-test-token',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
        'Idempotency-Key': 'stable-pin-key',
      },
      body: JSON.stringify({ pinned: true }),
    };
    expect(applicationFetch).toHaveBeenNthCalledWith(
      1,
      '/api/v1/memos/memo-1/pin',
      expectedRequest,
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      2,
      '/api/v1/memos/memo-1/pin',
      expectedRequest,
    );
  });

  it('reuses the exact update body snapshot and caller-owned key on retry', async () => {
    const { client, applicationFetch } = testClient();

    const body = {
      expectedRevision: 1,
      content: '수정한 원문',
      clientUpdatedAt: '2026-08-05T02:03:04.000Z',
      timeZone: 'Asia/Seoul',
    } as const;
    await client.updateMemo(memo.id, body, 'stable-edit-key');
    await client.updateMemo(memo.id, body, 'stable-edit-key');

    const expectedRequest = {
      method: 'PATCH',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-test-token',
        'Idempotency-Key': 'stable-edit-key',
      },
      body: JSON.stringify({
        expectedRevision: 1,
        content: '수정한 원문',
        clientUpdatedAt: '2026-08-05T02:03:04.000Z',
        timeZone: 'Asia/Seoul',
      }),
    };
    expect(applicationFetch).toHaveBeenNthCalledWith(1, '/api/v1/memos/memo-1', expectedRequest);
    expect(applicationFetch).toHaveBeenNthCalledWith(2, '/api/v1/memos/memo-1', expectedRequest);
  });

  it('uses separate idempotent soft-trash and restore endpoints', async () => {
    const { client, applicationFetch } = testClient();

    await client.trashMemo(memo.id, 'stable-trash-key');
    await client.restoreMemo(memo.id, 'stable-restore-key');

    expect(applicationFetch).toHaveBeenNthCalledWith(1, '/api/v1/memos/memo-1', {
      method: 'DELETE',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-test-token',
        'Idempotency-Key': 'stable-trash-key',
      },
    });
    expect(applicationFetch).toHaveBeenNthCalledWith(2, '/api/v1/memos/memo-1/restore', {
      method: 'POST',
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-TOKEN': 'csrf-test-token',
        'Idempotency-Key': 'stable-restore-key',
      },
    });
  });

  it('sends the concrete reviewed apply body with the caller-owned idempotency key', async () => {
    const { client, applicationFetch } = testClient({
      applicationId: 'application-1',
      status: 'APPLIED',
    });
    const body: ApplyProposalRequest = {
      expectedMemoRevision: 1,
      selectedType: 'TASK',
      title: '운영체제 과제',
      selectedTags: [],
      items: [
        {
          proposalCandidateId: ' item-1 ',
          kind: 'TASK',
          title: '운영체제 과제',
          due: {
            surfaceText: '11월 25일',
            value: '2026-11-25',
            precision: 'DATE_ONLY',
            timeZone: 'Asia/Seoul',
            timeSpecified: false,
          },
        },
      ],
      selectedRelations: [{ proposalIndex: 0 }],
    };

    await client.apply('proposal-1', body, 'stable-apply-key');

    expect(applicationFetch).toHaveBeenCalledWith(
      '/api/v1/analysis-proposals/proposal-1/apply',
      {
        method: 'POST',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'csrf-test-token',
          'Idempotency-Key': 'stable-apply-key',
        },
        body: JSON.stringify(body),
      },
    );
    expect(applicationFetch.mock.calls[0]?.[1]?.body).toContain('proposalCandidateId');
    expect(applicationFetch.mock.calls[0]?.[1]?.body).toContain('selectedRelations');
  });

  it('loads strict relation labels through an uncached owner-scoped abortable read', async () => {
    const targetId = '1bb89795-60a0-40b0-b88e-76eecab89b7f';
    const proposal = {
      ...validProposalV2(),
      relationCandidates: [{
        sourceCandidateId: 'item-1',
        targetType: 'TAG',
        targetId,
        relationType: 'RELATED_TO',
        score: 0.8,
      }],
    } as unknown as Proposal;
    const { client, applicationFetch } = testClient([{
      proposalIndex: 0,
      targetType: 'TAG',
      targetId,
      targetLabel: '운영체제',
      available: true,
    }]);
    client.setSessionOwner('owner-1');
    const controller = new AbortController();

    await expect(
      client.relationReviewCandidates('proposal/1', proposal, controller.signal),
    ).resolves.toEqual([expect.objectContaining({ targetLabel: '운영체제', available: true })]);

    expect(applicationFetch).toHaveBeenCalledWith(
      '/api/v1/analysis-proposals/proposal%2F1/relation-review-candidates',
      {
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          [EXPECTED_OWNER_ID_HEADER]: 'owner-1',
        },
      },
    );
  });

  it('bounds the recent memo list to the server-supported maximum', async () => {
    const { client, applicationFetch } = testClient([]);

    await client.memos('TRASHED', 999);

    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/memos?status=TRASHED&limit=50', {
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: { 'Content-Type': 'application/json' },
    });
  });

  it('loads persisted application and both review proposal states', async () => {
    const { client, applicationFetch } = testClient();
    applicationFetch
      .mockResolvedValueOnce(okResponse({ applicationId: 'application-1', status: 'APPLIED' }))
      .mockResolvedValueOnce(okResponse([]))
      .mockResolvedValueOnce(okResponse([]));

    await client.latestApplication();
    await client.proposals('REVIEW_REQUIRED', 1);
    await client.proposals('POSTPONED', 1);

    expect(applicationFetch).toHaveBeenNthCalledWith(1, '/api/v1/analysis-applications/latest', {
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: { 'Content-Type': 'application/json' },
    });
    expect(applicationFetch).toHaveBeenNthCalledWith(
      2,
      '/api/v1/analysis-proposals?status=REVIEW_REQUIRED&limit=1',
      {
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '3',
        },
      },
    );
    expect(applicationFetch).toHaveBeenNthCalledWith(
      3,
      '/api/v1/analysis-proposals?status=POSTPONED&limit=1',
      {
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '3',
        },
      },
    );
  });

  it('does not send user A memo data after logout and user B authentication', async () => {
    let resolveUserACsrf!: (response: Response) => void;
    const userACsrf = new Promise<Response>((resolve) => {
      resolveUserACsrf = resolve;
    });
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, init });
      if (url === '/api/v1/auth/csrf' && requests.length === 1) return userACsrf;
      if (url === '/api/v1/auth/csrf') return Promise.resolve(csrfResponse('user-b-token'));
      return Promise.resolve(okResponse());
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    const userARequest = client.createMemo({
      id: 'memo-a',
      content: 'user A private memo',
      clientCreatedAt: '2026-08-05T00:00:00.000Z',
      timeZone: 'Asia/Seoul',
      idempotencyKey: 'memo-a-key',
    });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    client.invalidateSession();
    client.setSessionOwner('user-b');
    resolveUserACsrf(csrfResponse('user-a-token'));

    await expect(userARequest).rejects.toBeInstanceOf(SessionScopeChangedError);
    expect(requests.filter(({ url }) => url === '/api/v1/memos')).toHaveLength(0);

    await client.createMemo({
      id: 'memo-b',
      content: 'user B memo',
      clientCreatedAt: '2026-08-05T00:00:00.000Z',
      timeZone: 'Asia/Seoul',
      idempotencyKey: 'memo-b-key',
    });

    const memoWrites = requests.filter(({ url }) => url === '/api/v1/memos');
    expect(memoWrites).toHaveLength(1);
    expect(memoWrites[0]?.init?.body).toContain('user B memo');
    expect(memoWrites[0]?.init?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'user-b-token',
    });
  });

  it('aborts an in-flight domain request when the authenticated owner changes', async () => {
    let requestSignal: AbortSignal | null = null;
    let resolveRequest!: (response: Response) => void;
    const pendingResponse = new Promise<Response>((resolve) => {
      resolveRequest = resolve;
    });
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/memos?status=ACTIVE&limit=50') {
        requestSignal = init?.signal ?? null;
        return pendingResponse;
      }
      return Promise.resolve(okResponse([]));
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    const request = client.memos('ACTIVE');
    await vi.waitFor(() => expect(requestSignal).not.toBeNull());
    client.invalidateSession();

    expect((requestSignal as AbortSignal | null)?.aborted).toBe(true);
    resolveRequest(okResponse([]));
    await expect(request).rejects.toBeInstanceOf(SessionScopeChangedError);
  });

  it('pins owner-scoped requests to the owner captured at request start', async () => {
    const { client, applicationFetch } = testClient([]);
    client.setSessionOwner('user-a');

    await client.memos('ACTIVE');

    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/memos?status=ACTIVE&limit=50', {
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      headers: {
        'Content-Type': 'application/json',
        [EXPECTED_OWNER_ID_HEADER]: 'user-a',
      },
    });
  });

  it('loads an uncached owner-scoped review summary without CSRF or idempotency headers', async () => {
    const { client, fetchMock, applicationFetch } = testClient(validReviewOutcomeSummary());
    client.setSessionOwner('user-a');

    await client.reviewOutcomeSummary(14);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(applicationFetch).toHaveBeenCalledWith(
      '/api/v1/analysis-review-outcomes/summary?days=14',
      {
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        headers: {
          'Content-Type': 'application/json',
          [EXPECTED_OWNER_ID_HEADER]: 'user-a',
        },
      },
    );
  });

  it('bounds the review summary window to the server contract', async () => {
    const { client, applicationFetch } = testClient(validReviewOutcomeSummary());
    client.setSessionOwner('user-a');

    await client.reviewOutcomeSummary(0);
    await client.reviewOutcomeSummary(120);
    await client.reviewOutcomeSummary(14.9);
    await client.reviewOutcomeSummary(Number.NaN);

    expect(applicationFetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/analysis-review-outcomes/summary?days=1',
      '/api/v1/analysis-review-outcomes/summary?days=90',
      '/api/v1/analysis-review-outcomes/summary?days=14',
      '/api/v1/analysis-review-outcomes/summary?days=14',
    ]);
  });

  it('rejects an unsupported review summary contract before it reaches the workspace', async () => {
    const unsupported = { ...validReviewOutcomeSummary(), schemaVersion: '2' };
    const { client } = testClient(unsupported);
    client.setSessionOwner('user-a');

    await expect(client.reviewOutcomeSummary()).rejects.toBeInstanceOf(
      ReviewOutcomeContractError,
    );
  });

  it('loads an uncached owner-scoped analysis path summary without mutation headers', async () => {
    const { client, fetchMock, applicationFetch } = testClient(
      validAnalysisPathEvidenceSummary(),
    );
    client.setSessionOwner('user-a');

    await client.analysisPathEvidenceSummary(14);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(applicationFetch).toHaveBeenCalledWith(
      '/api/v1/analysis-path-evidence/summary?days=14',
      {
        cache: 'no-store',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
        headers: {
          'Content-Type': 'application/json',
          [EXPECTED_OWNER_ID_HEADER]: 'user-a',
        },
      },
    );
  });

  it('bounds the analysis path evidence window to the server contract', async () => {
    const { client, applicationFetch } = testClient(validAnalysisPathEvidenceSummary());
    client.setSessionOwner('user-a');

    await client.analysisPathEvidenceSummary(0);
    await client.analysisPathEvidenceSummary(120);
    await client.analysisPathEvidenceSummary(14.9);
    await client.analysisPathEvidenceSummary(Number.NaN);

    expect(applicationFetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/analysis-path-evidence/summary?days=1',
      '/api/v1/analysis-path-evidence/summary?days=90',
      '/api/v1/analysis-path-evidence/summary?days=14',
      '/api/v1/analysis-path-evidence/summary?days=14',
    ]);
  });

  it('rejects an unsupported analysis path contract before it reaches the workspace', async () => {
    const unsupported = { ...validAnalysisPathEvidenceSummary(), schemaVersion: '2' };
    const { client } = testClient(unsupported);
    client.setSessionOwner('user-a');

    await expect(client.analysisPathEvidenceSummary()).rejects.toBeInstanceOf(
      AnalysisPathEvidenceSummaryContractError,
    );
  });

  it('invalidates local scope and emits a dedicated event on server owner mismatch', async () => {
    const browserEvents = new EventTarget();
    vi.stubGlobal('window', browserEvents);
    const listener = vi.fn();
    browserEvents.addEventListener(SESSION_OWNER_CHANGED_EVENT, listener);
    const fetchMock = vi.fn(async (
      ...request: [RequestInfo | URL, RequestInit?]
    ) => {
      void request;
      return new Response(JSON.stringify({
        code: 'SESSION_OWNER_CHANGED',
        message: 'Session owner changed',
      }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      });
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    await expect(client.memos('ACTIVE')).rejects.toMatchObject({
      status: 409,
      code: 'SESSION_OWNER_CHANGED',
    });
    expect(fetchMock.mock.calls[0]?.[1]?.headers).toMatchObject({
      [EXPECTED_OWNER_ID_HEADER]: 'user-a',
    });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('does not retry an authentication mutation with another session epoch CSRF token', async () => {
    let resolveLogout!: (response: Response) => void;
    const delayedLogout = new Promise<Response>((resolve) => {
      resolveLogout = resolve;
    });
    const logoutRequests: RequestInit[] = [];
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/v1/auth/csrf') return Promise.resolve(csrfResponse('logout-token'));
      if (url === '/api/v1/auth/logout') {
        logoutRequests.push(init ?? {});
        return delayedLogout;
      }
      return Promise.resolve(okResponse());
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    const logout = client.logout();
    await vi.waitFor(() => expect(logoutRequests).toHaveLength(1));
    client.setSessionOwner('user-b');
    resolveLogout(new Response(JSON.stringify({ code: 'CSRF_TOKEN_INVALID' }), {
      status: 403,
      headers: { 'Content-Type': 'application/json' },
    }));

    await expect(logout).rejects.toBeInstanceOf(SessionScopeChangedError);
    expect(logoutRequests).toHaveLength(1);
  });

  it('pins a failed logout retry to user A so it cannot log out user B', async () => {
    const logoutOwners: Array<string | null> = [];
    let logoutAttempt = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/v1/auth/csrf') return csrfResponse(`token-${logoutAttempt}`);
      if (url === '/api/v1/auth/logout') {
        logoutOwners.push(new Headers(init?.headers).get(EXPECTED_OWNER_ID_HEADER));
        logoutAttempt += 1;
        if (logoutAttempt === 1) throw new TypeError('offline');
        return new Response(JSON.stringify({
          code: 'SESSION_OWNER_CHANGED',
          message: 'Another account owns this session',
        }), {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return okResponse();
    });
    const client = createApiClient(fetchMock);
    client.setSessionOwner('user-a');

    await expect(client.logout()).rejects.toBeInstanceOf(TypeError);
    client.setSessionOwner('user-b');
    await expect(client.logout()).rejects.toMatchObject({
      status: 409,
      code: 'SESSION_OWNER_CHANGED',
    });

    expect(logoutOwners).toEqual(['user-a', 'user-a']);
    expect(logoutAttempt).toBe(2);
  });

  it('rejects unsupported proposal versions before they reach review state', async () => {
    const { client } = testClient({ ...validProposal(), schemaVersion: '4' });

    await expect(client.proposal('proposal-1')).rejects.toMatchObject({
      name: 'ProposalContractError',
      field: 'schemaVersion',
    });
  });

  it('decodes v3 EVENT alternatives without turning the suggestion into a selection', async () => {
    const { client } = testClient(validProposalV3());

    await expect(client.proposal('proposal-1')).resolves.toMatchObject({
      schemaVersion: '3',
      itemCandidates: [
        {
          candidateId: 'item-1',
          suggestedEventScheduleCandidateId: 'event-schedule-1',
          eventScheduleCandidates: [{ candidateId: 'event-schedule-1' }],
        },
      ],
    });
  });

  it('decodes an explicit v2 binding before it reaches review state', async () => {
    const { client, applicationFetch } = testClient(validProposalV2());

    await expect(client.proposal('proposal-1')).resolves.toMatchObject({
      schemaVersion: '2',
      dateCandidates: [{ candidateId: 'date-1' }],
      itemCandidates: [{ candidateId: 'item-1', dueDateCandidateId: 'date-1' }],
    });
    expect(applicationFetch).toHaveBeenCalledWith('/api/v1/analysis-proposals/proposal-1', {
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/json',
        [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '3',
      },
    });
  });

  it('rejects a proposal whose memo identity differs from the analysis run', async () => {
    const { client } = testClient(validProposal());

    await expect(
      client.proposal('proposal-1', {
        memoId: '5a35efeb-bcf7-4f53-ab71-0fcaad547cf1',
        memoRevision: 1,
      }),
    ).rejects.toMatchObject({
      name: 'ProposalContractError',
      field: 'memoId',
    });
  });
});
