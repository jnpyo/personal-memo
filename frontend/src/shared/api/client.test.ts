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

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('memo API client', () => {
  it('reuses the exact update body snapshot and caller-owned key on retry', async () => {
    const fetchMock = vi.fn(async () => okResponse());
    vi.stubGlobal('fetch', fetchMock);

    const body = {
      expectedRevision: 1,
      content: '수정한 원문',
      clientUpdatedAt: '2026-08-05T02:03:04.000Z',
      timeZone: 'Asia/Seoul',
    } as const;
    await api.updateMemo(memo.id, body, 'stable-edit-key');
    await api.updateMemo(memo.id, body, 'stable-edit-key');

    const expectedRequest = {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'stable-edit-key',
      },
      body: JSON.stringify({
        expectedRevision: 1,
        content: '수정한 원문',
        clientUpdatedAt: '2026-08-05T02:03:04.000Z',
        timeZone: 'Asia/Seoul',
      }),
    };
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/memos/memo-1', expectedRequest);
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/memos/memo-1', expectedRequest);
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

  it('rejects unsupported proposal versions before they reach review state', async () => {
    const fetchMock = vi.fn(async () =>
      okResponse({ ...validProposal(), schemaVersion: '2' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.proposal('proposal-1')).rejects.toMatchObject({
      name: 'ProposalContractError',
      field: 'schemaVersion',
    });
  });

  it('rejects a proposal whose memo identity differs from the analysis run', async () => {
    const fetchMock = vi.fn(async () => okResponse(validProposal()));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      api.proposal('proposal-1', {
        memoId: '5a35efeb-bcf7-4f53-ab71-0fcaad547cf1',
        memoRevision: 1,
      }),
    ).rejects.toMatchObject({
      name: 'ProposalContractError',
      field: 'memoId',
    });
  });
});
