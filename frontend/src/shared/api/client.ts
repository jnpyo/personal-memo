import { toApiError } from './errors';
import type {
  AnalysisRun,
  ApplicationResult,
  ApplyProposalRequest,
  GraphProjection,
  LatestApplication,
  MemoView,
  MemoStatus,
  Proposal,
  ProposalSummary,
  ReviewDispositionResult,
  Task,
  TaskStatus,
  UpdateMemoRequest,
} from './types';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { ...JSON_HEADERS, ...init?.headers },
  });

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export const api = {
  health: () => request<{ status: string }>('/api/v1/health'),

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

  analyze: (memoId: string, memoRevision: number, idempotencyKey: string) =>
    request<AnalysisRun>(`/api/v1/memos/${memoId}/analysis-runs`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ memoRevision, policy: 'AUTO' }),
    }),

  proposal: (proposalId: string) =>
    request<Proposal>(`/api/v1/analysis-proposals/${proposalId}`),

  proposals: (status: 'REVIEW_REQUIRED' | 'POSTPONED', limit = 1) =>
    request<ProposalSummary[]>(
      `/api/v1/analysis-proposals?status=${status}&limit=${Math.min(Math.max(limit, 1), 50)}`,
    ),

  latestApplication: () =>
    request<LatestApplication>('/api/v1/analysis-applications/latest'),

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
    request<GraphProjection>(`/api/v1/graph/home?limit=${Math.min(Math.max(limit, 1), 100)}`),
};

export type { Proposal } from './types';
