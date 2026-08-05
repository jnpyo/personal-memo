import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import type { MemoView } from './types';

const memo: MemoView = {
  id: 'memo-1',
  currentRevision: 2,
  content: '수정한 원문',
  status: 'ACTIVE',
  analysisState: 'NOT_STARTED',
  createdAt: '2026-08-05T00:00:00.000Z',
};

function okResponse(body: unknown = memo): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('memo API client', () => {
  it('sends optimistic revision data and the caller-owned idempotency key when editing', async () => {
    const fetchMock = vi.fn(async () => okResponse());
    vi.stubGlobal('fetch', fetchMock);

    await api.updateMemo(
      memo.id,
      { expectedRevision: 1, content: '수정한 원문' },
      'stable-edit-key',
    );

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/memos/memo-1', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'stable-edit-key',
      },
      body: JSON.stringify({ expectedRevision: 1, content: '수정한 원문' }),
    });
  });

  it('uses separate idempotent soft-trash and restore endpoints', async () => {
    const fetchMock = vi.fn(async () => okResponse());
    vi.stubGlobal('fetch', fetchMock);

    await api.trashMemo(memo.id, 'stable-trash-key');
    await api.restoreMemo(memo.id, 'stable-restore-key');

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/memos/memo-1', {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'stable-trash-key',
      },
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/memos/memo-1/restore', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'stable-restore-key',
      },
    });
  });

  it('bounds the recent memo list to the server-supported maximum', async () => {
    const fetchMock = vi.fn(async () => okResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await api.memos('TRASHED', 999);

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/memos?status=TRASHED&limit=50', {
      headers: { 'Content-Type': 'application/json' },
    });
  });

  it('loads persisted application and both review proposal states', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okResponse({ applicationId: 'application-1', status: 'APPLIED' }))
      .mockResolvedValueOnce(okResponse([]))
      .mockResolvedValueOnce(okResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await api.latestApplication();
    await api.proposals('REVIEW_REQUIRED', 1);
    await api.proposals('POSTPONED', 1);

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/analysis-applications/latest', {
      headers: { 'Content-Type': 'application/json' },
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/analysis-proposals?status=REVIEW_REQUIRED&limit=1',
      { headers: { 'Content-Type': 'application/json' } },
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/analysis-proposals?status=POSTPONED&limit=1',
      { headers: { 'Content-Type': 'application/json' } },
    );
  });
});
