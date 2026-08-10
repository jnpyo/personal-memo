import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER,
  createApiClient,
  EXPECTED_OWNER_ID_HEADER,
  SESSION_OWNER_CHANGED_EVENT,
  SessionScopeChangedError,
} from './client';
import { ReviewOutcomeContractError } from './reviewOutcomeDecoder';
import type { ApplyProposalRequest, MemoView } from './types';

const memo: MemoView = {
  id: 'memo-1',
  currentRevision: 2,
  content: '수정한 원문',
  status: 'ACTIVE',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-08-05T00:00:00.000Z',
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

describe('memo API client', () => {
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
    expect(applicationFetch.mock.calls[0]?.[1]?.body).not.toContain('candidateId');
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
          [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '2',
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
          [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '2',
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
    const { client } = testClient({ ...validProposal(), schemaVersion: '3' });

    await expect(client.proposal('proposal-1')).rejects.toMatchObject({
      name: 'ProposalContractError',
      field: 'schemaVersion',
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
        [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: '2',
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
