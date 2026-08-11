import { toApiError } from './errors';
import { decodeProposal, decodeProposalSummaries } from './proposalDecoder';
import type { ExpectedProposalIdentity } from './proposalDecoder';
import { decodeReviewOutcomeSummary } from './reviewOutcomeDecoder';
import {
  assertGraphNeighborhoodExpectedCenter,
  decodeGraphNeighborhoodPage,
} from './graphNeighborhoodDecoder';
import type {
  AnalysisRun,
  AnalysisReviewOutcomeSummary,
  ApplicationResult,
  ApplyProposalRequest,
  AuthCapabilities,
  AuthSession,
  CsrfToken,
  GraphProjection,
  GraphNode,
  LatestApplication,
  MemoPinResult,
  MemoView,
  MemoStatus,
  ReviewDispositionResult,
  Task,
  TaskStatus,
  UpdateMemoRequest,
} from './types';

const JSON_HEADERS = { 'Content-Type': 'application/json' };
export const AUTHENTICATION_REQUIRED_EVENT = 'personal-memo:authentication-required';
export const SESSION_OWNER_CHANGED_EVENT = 'personal-memo:session-owner-changed';
export const EXPECTED_OWNER_ID_HEADER = 'X-Expected-Owner-Id';
export const ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER =
  'X-Analysis-Proposal-Schema-Version';
const ANALYSIS_PROPOSAL_SCHEMA_VERSION = '2';
const ANALYSIS_PROPOSAL_HEADERS = {
  [ANALYSIS_PROPOSAL_SCHEMA_VERSION_HEADER]: ANALYSIS_PROPOSAL_SCHEMA_VERSION,
};
const SESSION_OWNER_CHANGED_CODE = 'SESSION_OWNER_CHANGED';
const LOGOUT_PENDING_STORAGE_KEY = 'personal-memo.logout-pending.v1';
const LOGOUT_EXPECTED_OWNER_STORAGE_KEY = 'personal-memo.logout-expected-owner.v1';
const CSRF_ERROR_CODES = new Set([
  'CSRF',
  'CSRF_TOKEN_INVALID',
  'CSRF_TOKEN_MISSING',
  'INVALID_CSRF_TOKEN',
]);
const AUTHENTICATION_PROBE_PATHS = new Set([
  '/api/v1/auth/capabilities',
  '/api/v1/auth/csrf',
  '/api/v1/auth/register',
  '/api/v1/auth/login',
  '/api/v1/auth/logout',
  '/api/v1/auth/me',
]);
const SESSION_INDEPENDENT_PATHS = new Set([
  '/api/v1/health',
  '/api/v1/auth/capabilities',
]);
type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

function readPendingLogoutOwner(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    if (window.sessionStorage.getItem(LOGOUT_PENDING_STORAGE_KEY) !== '1') {
      window.sessionStorage.removeItem(LOGOUT_EXPECTED_OWNER_STORAGE_KEY);
      return null;
    }
    return window.sessionStorage.getItem(LOGOUT_EXPECTED_OWNER_STORAGE_KEY);
  } catch {
    return null;
  }
}

function writePendingLogoutOwner(expectedOwnerId: string | null): void {
  if (typeof window === 'undefined') return;
  try {
    if (expectedOwnerId) {
      window.sessionStorage.setItem(LOGOUT_EXPECTED_OWNER_STORAGE_KEY, expectedOwnerId);
    } else {
      window.sessionStorage.removeItem(LOGOUT_EXPECTED_OWNER_STORAGE_KEY);
    }
  } catch {
    // The in-memory owner still protects this page when sessionStorage is blocked.
  }
}

export class SessionScopeChangedError extends Error {
  constructor() {
    super('The authenticated session changed before the request completed.');
    this.name = 'SessionScopeChangedError';
  }
}

function isMutation(init?: RequestInit): boolean {
  const method = (init?.method ?? 'GET').toUpperCase();
  return method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS';
}

function shouldSignalAuthenticationRequired(url: string): boolean {
  return !AUTHENTICATION_PROBE_PATHS.has(url);
}

function isSessionScoped(url: string): boolean {
  return !SESSION_INDEPENDENT_PATHS.has(url);
}

function isOwnerScoped(url: string): boolean {
  return !AUTHENTICATION_PROBE_PATHS.has(url) && !SESSION_INDEPENDENT_PATHS.has(url);
}

function normalizedReviewOutcomeDays(days: number): number {
  if (!Number.isFinite(days)) return 14;
  return Math.min(Math.max(Math.trunc(days), 1), 90);
}

function combineSignals(primary: AbortSignal, secondary?: AbortSignal | null): AbortSignal {
  if (!secondary || primary === secondary) return primary;

  const controller = new AbortController();
  const abort = () => controller.abort();
  if (primary.aborted || secondary.aborted) {
    abort();
  } else {
    primary.addEventListener('abort', abort, { once: true });
    secondary.addEventListener('abort', abort, { once: true });
  }
  return controller.signal;
}

async function isCsrfFailure(response: Response): Promise<boolean> {
  if (response.status !== 403) return false;
  if (response.headers.get('X-CSRF-Error') === 'true') return true;

  try {
    const payload = (await response.clone().json()) as { code?: string; error?: string };
    return CSRF_ERROR_CODES.has(payload.code ?? payload.error ?? '');
  } catch {
    return false;
  }
}

export function createApiClient(fetcher: FetchLike = (...args) => fetch(...args)) {
  let ownerId: string | null = null;
  let pendingLogoutOwnerId: string | null = readPendingLogoutOwner();
  let sessionEpoch = 0;
  let sessionController = new AbortController();
  let csrfToken: { epoch: number; value: CsrfToken } | null = null;
  let csrfRequest: { epoch: number; promise: Promise<CsrfToken> } | null = null;

  function assertSessionEpoch(expectedEpoch: number): void {
    if (expectedEpoch !== sessionEpoch) throw new SessionScopeChangedError();
  }

  function advanceSessionScope(nextOwnerId: string | null, force = false): void {
    if (!force && ownerId === nextOwnerId) return;
    sessionController.abort();
    sessionController = new AbortController();
    sessionEpoch += 1;
    ownerId = nextOwnerId;
    csrfToken = null;
    csrfRequest = null;
  }

  function setSessionOwner(nextOwnerId: string | null): void {
    advanceSessionScope(nextOwnerId);
  }

  function invalidateSession(): void {
    advanceSessionScope(null, true);
  }

  function clearPendingLogoutIntent(): void {
    pendingLogoutOwnerId = null;
    writePendingLogoutOwner(null);
  }

  async function refreshCsrf(expectedEpoch = sessionEpoch): Promise<CsrfToken> {
    assertSessionEpoch(expectedEpoch);
    if (csrfRequest?.epoch === expectedEpoch) return csrfRequest.promise;

    const request = {
      epoch: expectedEpoch,
      promise: (async () => {
        const response = await fetcher('/api/v1/auth/csrf', {
          credentials: 'same-origin',
          headers: JSON_HEADERS,
        });
        assertSessionEpoch(expectedEpoch);
        if (!response.ok) throw await toApiError(response);
        const nextToken = (await response.json()) as CsrfToken;
        assertSessionEpoch(expectedEpoch);
        if (!nextToken.headerName || !nextToken.parameterName || !nextToken.token) {
          throw new Error('Invalid CSRF token response');
        }
        csrfToken = { epoch: expectedEpoch, value: nextToken };
        return nextToken;
      })(),
    };
    csrfRequest = request;

    try {
      return await request.promise;
    } finally {
      if (csrfRequest === request) csrfRequest = null;
    }
  }

  async function request<T>(
    url: string,
    init?: RequestInit,
    csrfRetry = true,
    expectedEpoch = isSessionScoped(url) ? sessionEpoch : undefined,
    expectedOwnerId = isOwnerScoped(url) ? ownerId : null,
  ): Promise<T> {
    if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);
    const token = isMutation(init)
      ? (csrfToken?.epoch === sessionEpoch ? csrfToken.value : await refreshCsrf(sessionEpoch))
      : null;
    if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);

    const scopeSignal = expectedEpoch === undefined
      ? init?.signal
      : combineSignals(sessionController.signal, init?.signal);
    const response = await fetcher(url, {
      ...init,
      credentials: 'same-origin',
      ...(scopeSignal ? { signal: scopeSignal } : {}),
      headers: {
        ...JSON_HEADERS,
        ...(token ? { [token.headerName]: token.token } : {}),
        ...(expectedOwnerId ? { [EXPECTED_OWNER_ID_HEADER]: expectedOwnerId } : {}),
        ...init?.headers,
      },
    });
    if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);

    if (csrfRetry && token && await isCsrfFailure(response)) {
      if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);
      csrfToken = null;
      await refreshCsrf(expectedEpoch ?? sessionEpoch);
      if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);
      return request<T>(url, init, false, expectedEpoch, expectedOwnerId);
    }
    if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);

    if (!response.ok) {
      const error = await toApiError(response);
      if (
        error.code === SESSION_OWNER_CHANGED_CODE &&
        typeof window !== 'undefined'
      ) {
        invalidateSession();
        window.dispatchEvent(new Event(SESSION_OWNER_CHANGED_EVENT));
        throw error;
      }
      if (
        response.status === 401 &&
        shouldSignalAuthenticationRequired(url) &&
        typeof window !== 'undefined'
      ) {
        invalidateSession();
        window.dispatchEvent(new Event(AUTHENTICATION_REQUIRED_EVENT));
      }
      if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);
      throw error;
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const payload = await response.json() as T;
    if (expectedEpoch !== undefined) assertSessionEpoch(expectedEpoch);
    return payload;
  }

  return {
    setSessionOwner,

    invalidateSession,

    clearPendingLogoutIntent,

    pendingLogoutOwner: () => pendingLogoutOwnerId,

    health: () => request<{ status: string }>('/api/v1/health'),

    authCapabilities: () =>
      request<AuthCapabilities>('/api/v1/auth/capabilities'),

    refreshCsrf,

    authMe: () => request<AuthSession>('/api/v1/auth/me'),

    register: async (input: {
      email: string;
      password: string;
      displayName: string;
      timeZone: string;
    }) => {
      const session = await request<AuthSession>('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify(input),
      });
      advanceSessionScope(session.userId, true);
      // Authentication is already committed by the server. Priming the token
      // is best-effort; the next mutation will join or repeat this refresh.
      void refreshCsrf().catch(() => undefined);
      return session;
    },

    login: async (input: { email: string; password: string }) => {
      const session = await request<AuthSession>('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify(input),
      });
      advanceSessionScope(session.userId, true);
      // Do not turn a successful login into a client-visible failure merely
      // because the post-authentication CSRF priming request was interrupted.
      void refreshCsrf().catch(() => undefined);
      return session;
    },

    logout: async () => {
      const expectedLogoutOwnerId = pendingLogoutOwnerId ?? ownerId;
      if (expectedLogoutOwnerId) {
        pendingLogoutOwnerId = expectedLogoutOwnerId;
        writePendingLogoutOwner(expectedLogoutOwnerId);
      }
      invalidateSession();
      await request<void>(
        '/api/v1/auth/logout',
        { method: 'POST' },
        true,
        sessionEpoch,
        expectedLogoutOwnerId,
      );
      csrfToken = null;
      // The local workspace must disappear as soon as the server confirms logout.
      // Prime the anonymous session token without extending the logout lifetime.
      void refreshCsrf().catch(() => undefined);
    },

    googleLinkIntent: () =>
      request<{ authorizationUrl: string }>('/api/v1/auth/google/link-intent', {
        method: 'POST',
      }),

    unlinkGoogle: () =>
      request<AuthSession>('/api/v1/auth/identities/google', {
        method: 'DELETE',
      }),

    createMemo: (input: {
      id: string;
      content: string;
      clientCreatedAt: string;
      timeZone: string;
      idempotencyKey: string;
    }) =>
      request<MemoView>('/api/v1/memos', {
        method: 'POST',
        headers: { 'Idempotency-Key': input.idempotencyKey },
        body: JSON.stringify({
          id: input.id,
          content: input.content,
          clientCreatedAt: input.clientCreatedAt,
          timeZone: input.timeZone,
        }),
      }),

    memos: (status: MemoStatus, limit = 50) =>
      request<MemoView[]>(
        `/api/v1/memos?status=${status}&limit=${Math.min(Math.max(limit, 1), 50)}`,
      ),

    memo: (memoId: string, signal?: AbortSignal) =>
      request<MemoView>(`/api/v1/memos/${encodeURIComponent(memoId)}`, {
        cache: 'no-store',
        signal,
      }),

    updateMemo: (
      memoId: string,
      body: UpdateMemoRequest,
      idempotencyKey: string,
    ) =>
      request<MemoView>(`/api/v1/memos/${memoId}`, {
        method: 'PATCH',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      }),

    trashMemo: (memoId: string, idempotencyKey: string) =>
      request<MemoView>(`/api/v1/memos/${memoId}`, {
        method: 'DELETE',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    restoreMemo: (memoId: string, idempotencyKey: string) =>
      request<MemoView>(`/api/v1/memos/${memoId}/restore`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    setMemoPinned: (memoId: string, pinned: boolean, idempotencyKey: string) =>
      request<MemoPinResult>(`/api/v1/memos/${encodeURIComponent(memoId)}/pin`, {
        method: 'PATCH',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ pinned }),
      }),

    analyze: (memoId: string, memoRevision: number, idempotencyKey: string) =>
      request<AnalysisRun>(`/api/v1/memos/${memoId}/analysis-runs`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ memoRevision, policy: 'AUTO' }),
      }),

    proposal: async (proposalId: string, expectedIdentity?: ExpectedProposalIdentity) =>
      decodeProposal(
        await request<unknown>(`/api/v1/analysis-proposals/${proposalId}`, {
          cache: 'no-store',
          headers: ANALYSIS_PROPOSAL_HEADERS,
        }),
        expectedIdentity,
      ),

    proposals: async (status: 'REVIEW_REQUIRED' | 'POSTPONED', limit = 1) =>
      decodeProposalSummaries(
        await request<unknown>(
          `/api/v1/analysis-proposals?status=${status}&limit=${Math.min(Math.max(limit, 1), 50)}`,
          { cache: 'no-store', headers: ANALYSIS_PROPOSAL_HEADERS },
        ),
        status,
      ),

    latestApplication: () =>
      request<LatestApplication>('/api/v1/analysis-applications/latest'),

    reviewOutcomeSummary: async (days = 14): Promise<AnalysisReviewOutcomeSummary> =>
      decodeReviewOutcomeSummary(
        await request<unknown>(
          `/api/v1/analysis-review-outcomes/summary?days=${normalizedReviewOutcomeDays(days)}`,
          { cache: 'no-store' },
        ),
      ),

    apply: (proposalId: string, body: ApplyProposalRequest, idempotencyKey: string) =>
      request<ApplicationResult>(`/api/v1/analysis-proposals/${proposalId}/apply`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      }),

    rejectProposal: (proposalId: string, idempotencyKey: string) =>
      request<ReviewDispositionResult>(`/api/v1/analysis-proposals/${proposalId}/reject`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    postponeProposal: (proposalId: string, idempotencyKey: string) =>
      request<ReviewDispositionResult>(`/api/v1/analysis-proposals/${proposalId}/postpone`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    undo: (applicationId: string, idempotencyKey: string) =>
      request<ApplicationResult>(`/api/v1/analysis-applications/${applicationId}/undo`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    tasks: () => request<Task[]>('/api/v1/tasks'),

    updateTask: (taskId: string, status: TaskStatus, idempotencyKey: string) =>
      request<{ id: string; status: TaskStatus; updated: boolean }>(`/api/v1/tasks/${taskId}`, {
        method: 'PATCH',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify({ status }),
      }),

    graph: (limit = 100) =>
      request<GraphProjection>(
        `/api/v1/graph/home?limit=${Math.min(Math.max(limit, 1), 100)}`,
        { cache: 'no-store' },
      ),

    graphNeighborhood: async (
      kind: GraphNode['kind'],
      nodeId: string,
      cursor?: string | null,
      signal?: AbortSignal,
      limit = 20,
    ) => {
      assertGraphNeighborhoodExpectedCenter({ kind, entityId: nodeId });
      const query = new URLSearchParams({
        limit: String(Math.min(Math.max(Math.trunc(limit), 1), 20)),
      });
      if (cursor) query.set('cursor', cursor);
      return decodeGraphNeighborhoodPage(
        await request<unknown>(
          `/api/v1/graph/nodes/${encodeURIComponent(kind)}/${encodeURIComponent(nodeId)}/neighborhood?${query.toString()}`,
          { cache: 'no-store', signal },
        ),
        { kind, entityId: nodeId },
      );
    },
  };
}

export const api = createApiClient();

export type { Proposal } from './types';
