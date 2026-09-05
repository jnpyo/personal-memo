import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import type {
  GraphNode,
  MemoSearchItem,
  MemoView,
  UpdateMemoRequest,
} from '../src/shared/api/types';

// This suite serves only synthetic responses. It never requires a backend or owner credentials.
const OWNER_ID = '11111111-1111-4111-8111-111111111111';
const MEMO_A = '00000000-0000-4000-8000-000000000001';
const MEMO_B = '00000000-0000-4000-8000-000000000002';
const RAW_MEMO = '00000000-0000-4000-8000-000000000003';
const TRASHED_MEMO = '00000000-0000-4000-8000-000000000004';
const SYNTHETIC_DRAFT = '공개 synthetic 수정 내용';
const CONCURRENT_CONTENT = '다른 창에서 저장한 공개 synthetic 원문';
type Surface = 'search' | 'graph' | 'browse';
type SaveBehavior = 'delayed' | 'retry' | 'conflict' | 'success';

function syntheticMemo(id: string, pinned = false): MemoView {
  return {
    id,
    currentRevision: 1,
    content: id === MEMO_A ? '공개 synthetic 원문 A' : '공개 synthetic 원문 B',
    pinned,
    status: 'ACTIVE',
    analysisState: 'APPLIED',
    createdAt: '2026-09-02T01:00:00Z',
  };
}

function graphNode(memo: MemoView): GraphNode {
  return {
    id: `memo:${memo.id}`,
    kind: 'MEMO',
    label: memo.id === MEMO_A ? 'Synthetic A' : 'Synthetic B',
    memoType: 'INFORMATION',
    taskState: 'NONE',
    overdue: false,
    pinned: memo.pinned,
  };
}

function searchItem(memo: MemoView): MemoSearchItem {
  return {
    memoId: memo.id,
    currentRevision: memo.currentRevision,
    canonicalRevision: 1,
    title: graphNode(memo).label,
    preview: memo.content,
    lifecycleStatus: memo.status,
    canonicalTags: [],
    taskState: 'NONE',
    overdue: false,
    pinned: memo.pinned,
    revisedAt: '2026-09-02T01:00:00Z',
    matchedFields: ['BODY'],
  };
}

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store' },
    body: JSON.stringify(body),
  });
}

async function mockWorkspace(
  page: Page,
  behavior: SaveBehavior,
  pinned = false,
) {
  page.on('pageerror', (error) => { throw error; });
  let currentA = syntheticMemo(MEMO_A, pinned);
  const currentB = syntheticMemo(MEMO_B);
  const rawMemo: MemoView = {
    ...syntheticMemo(RAW_MEMO), content: '원문만 저장한 공개 synthetic 메모', analysisState: 'NOT_STARTED',
  };
  let trashedMemo: MemoView = {
    ...syntheticMemo(TRASHED_MEMO), content: '휴지통의 공개 synthetic 메모', status: 'TRASHED',
  };
  let pendingSave: { route: Route; body: UpdateMemoRequest } | null = null;
  const detailReads: string[] = [];
  const saves: { memoId: string; body: UpdateMemoRequest; idempotencyKey: string | undefined }[] = [];
  const pinWrites: boolean[] = [];
  const restoreWrites: string[] = [];
  const searchRequests: string[] = [];
  const unexpectedApi: string[] = [];

  await page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/api/v1/auth/csrf') {
      return fulfillJson(route, { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'synthetic' });
    }
    if (path === '/api/v1/auth/capabilities') {
      return fulfillJson(route, {
        registrationEnabled: false, googleEnabled: false, googleRegistrationEnabled: false,
      });
    }
    if (path === '/api/v1/auth/me') {
      return fulfillJson(route, {
        userId: OWNER_ID, email: 'synthetic-owner@example.invalid',
        displayName: 'Synthetic Owner', loginMethods: ['LOCAL'],
      });
    }
    if (path === '/api/v1/health') return fulfillJson(route, { status: 'UP' });
    if (path === '/api/v1/memos') {
      const status = new URL(request.url()).searchParams.get('status');
      return fulfillJson(route, [currentA, currentB, rawMemo, trashedMemo].filter((memo) => memo.status === status));
    }
    if (['/api/v1/tasks', '/api/v1/events', '/api/v1/analysis-proposals'].includes(path)) {
      return fulfillJson(route, []);
    }
    if (path === '/api/v1/graph/home') {
      return fulfillJson(route, {
        nodes: [graphNode(currentA), graphNode(currentB)], edges: [], truncated: false,
        projectionVersion: '22222222-2222-4222-8222-222222222222',
      });
    }
    if (path === `/api/v1/graph/nodes/MEMO/${MEMO_A}/neighborhood`) {
      return fulfillJson(route, {
        center: graphNode(currentA), neighbors: [], edges: [], truncated: false, nextCursor: null,
      });
    }
    if (path === '/api/v1/analysis-applications/latest') {
      return fulfillJson(route, { applicationId: null, status: 'NONE' });
    }
    if (path === '/api/v1/analysis-review-outcomes/summary') {
      return fulfillJson(route, {
        schemaVersion: '1', comparisonPolicyVersion: 'review-default-v3',
        cohort: { basis: 'PROPOSAL_CREATED_AT', days: 14, fromInclusive: '2026-08-19T00:00:00Z',
          toExclusive: '2026-09-02T00:00:00Z', maxProposals: 1000 },
        proposals: { total: 0, withApplication: 0, currentStates: { queued: 0, running: 0,
          reviewRequired: 0, currentPostponed: 0, failed: 0, stale: 0, applied: 0, rejected: 0, other: 0 } },
        latestApplications: { none: 0, applied: 0, undone: 0 },
        outcomes: { exact: 0, corrected: 0, userResolved: 0, unclassifiable: 0,
          correctedFields: { type: 0, title: 0, tags: 0, items: 0, due: 0 } },
        byAnalysisVersion: [],
      });
    }
    if (path === '/api/v1/search/memos') {
      searchRequests.push(path);
      return fulfillJson(route, {
        items: [searchItem(currentA), searchItem(currentB)], nextCursor: null, truncated: false,
      });
    }
    if (path === `/api/v1/memos/${MEMO_A}` && request.method() === 'PATCH') {
      const body = request.postDataJSON() as UpdateMemoRequest;
      saves.push({ memoId: MEMO_A, body, idempotencyKey: request.headers()['idempotency-key'] });
      if (behavior === 'delayed') {
        pendingSave = { route, body };
        return;
      }
      if (behavior === 'retry' && saves.length === 1) {
        return fulfillJson(route, { code: 'SYNTHETIC_SAVE_FAILURE', message: '저장 연결을 다시 확인해 주세요.' }, 500);
      }
      if (behavior === 'conflict' && saves.length === 1) {
        currentA = { ...currentA, currentRevision: 2, content: CONCURRENT_CONTENT };
        return fulfillJson(route, { code: 'STALE_MEMO_REVISION' }, 409);
      }
      if (body.expectedRevision !== currentA.currentRevision) {
        return fulfillJson(route, { code: 'STALE_MEMO_REVISION' }, 409);
      }
      currentA = { ...currentA, currentRevision: currentA.currentRevision + 1, content: body.content };
      return fulfillJson(route, currentA);
    }
    if (path === `/api/v1/memos/${MEMO_A}/pin` && request.method() === 'PATCH') {
      const body = request.postDataJSON() as { pinned: boolean };
      pinWrites.push(body.pinned);
      currentA = { ...currentA, pinned: body.pinned };
      return fulfillJson(route, { id: MEMO_A, pinned: body.pinned, updated: true });
    }
    if (path === `/api/v1/memos/${TRASHED_MEMO}/restore` && request.method() === 'POST') {
      restoreWrites.push(TRASHED_MEMO);
      trashedMemo = { ...trashedMemo, status: 'ACTIVE' };
      return fulfillJson(route, trashedMemo);
    }
    const requestedMemo = [currentA, currentB, rawMemo, trashedMemo].find((memo) => path === `/api/v1/memos/${memo.id}`);
    if (request.method() === 'GET' && requestedMemo) {
      const memo = requestedMemo;
      detailReads.push(memo.id);
      return fulfillJson(route, memo);
    }
    unexpectedApi.push(`${request.method()} ${path}`);
    return fulfillJson(route, {}, 404);
  });

  return {
    saves, detailReads, pinWrites, restoreWrites, searchRequests, unexpectedApi,
    pendingSave: () => pendingSave !== null,
    currentA: () => currentA,
    releaseSave: async () => {
      if (!pendingSave) throw new Error('Expected a pending synthetic save');
      currentA = { ...currentA, currentRevision: 2, content: pendingSave.body.content };
      await fulfillJson(pendingSave.route, currentA);
      pendingSave = null;
    },
  };
}

async function openDetail(page: Page, surface: Surface): Promise<Locator> {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: '연결 지도', exact: true })).toBeVisible();
  if (surface !== 'graph') {
    await page.locator('.graph-search-disclosure > summary').click();
    if (surface === 'search') {
      await page.getByLabel('메모 검색어', { exact: true }).fill('synthetic');
      await page.getByRole('button', { name: '검색', exact: true }).click();
    }
    await page.locator(`#memo-${surface}-result-${MEMO_A}`).click();
  } else {
    await page.getByRole('button', { name: /^Synthetic A,/ }).click();
  }
  const detail = page.locator('dialog[open]');
  await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(syntheticMemo(MEMO_A).content);
  return detail;
}

async function editMemo(detail: Locator): Promise<void> {
  await detail.getByRole('button', { name: '수정', exact: true }).click();
  await detail.getByRole('textbox', { name: '메모 수정', exact: true }).fill(SYNTHETIC_DRAFT);
}

for (const surface of ['search', 'browse'] as const) {
  for (const targetId of [MEMO_B, MEMO_A]) {
    test(`late ${surface} save does not reload a replaced detail selection (${targetId === MEMO_B ? 'other memo' : 'same memo reopened'})`, async ({ page }) => {
      const state = await mockWorkspace(page, 'delayed');
      const detail = await openDetail(page, surface);
      await editMemo(detail);
      await detail.getByRole('button', { name: '저장', exact: true }).click();
      await expect.poll(state.pendingSave).toBe(true);
      page.once('dialog', (confirmation) => confirmation.accept());
      await detail.getByRole('button', {
        name: surface === 'search' ? '검색 메모 상세 닫기' : '최근 메모 상세 닫기', exact: true,
      }).click();
      await page.locator(`#memo-${surface}-result-${targetId}`).click();
      await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(syntheticMemo(targetId).content);
      await state.releaseSave();
      await expect(detail.getByRole('button', { name: '수정', exact: true })).toBeEnabled();
      await expect(detail.getByRole('heading', {
        name: surface === 'search'
          ? targetId === MEMO_B ? 'Synthetic B 상세' : 'Synthetic A 상세'
          : syntheticMemo(targetId).content,
        exact: true,
      })).toBeVisible();
      await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(syntheticMemo(targetId).content);
      await detail.getByRole('button', { name: '수정', exact: true }).click();
      await expect(detail.locator('textarea')).toHaveAttribute('id', `memo-detail-edit-content-${targetId}`);
      expect(state.detailReads).toEqual([MEMO_A, targetId]);
      if (surface === 'browse') expect(state.searchRequests).toEqual([]);
      expect(state.unexpectedApi).toEqual([]);
    });
  }
}

test('closing a pending graph save keeps graph selection locked until the saved memo can be reopened', async ({ page }) => {
  const state = await mockWorkspace(page, 'delayed');
  const detail = await openDetail(page, 'graph');
  await editMemo(detail);
  await detail.getByRole('button', { name: '저장', exact: true }).click();
  await expect.poll(state.pendingSave).toBe(true);
  page.once('dialog', (confirmation) => confirmation.accept());
  await detail.getByRole('button', { name: '그래프 상세 닫기', exact: true }).click();
  const node = page.getByRole('button', { name: /^Synthetic A,/ });
  await expect(node).toBeDisabled();
  await state.releaseSave();
  await expect(node).toBeEnabled();
  await node.click();
  await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(SYNTHETIC_DRAFT);
  await detail.getByRole('button', { name: '수정', exact: true }).click();
  await expect(detail.locator('textarea')).toHaveAttribute('id', `memo-detail-edit-content-${MEMO_A}`);
  await expect(detail.locator('textarea')).toHaveValue(SYNTHETIC_DRAFT);
  expect(state.detailReads).toEqual([MEMO_A, MEMO_A]);
  expect(state.unexpectedApi).toEqual([]);
});

test('query-free recent and trash browsing keeps raw-only memos reachable and restores only on request', async ({ page }) => {
  const state = await mockWorkspace(page, 'success');
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: '연결 지도', exact: true })).toBeVisible();
  await page.locator('.graph-search-disclosure > summary').click();
  const browse = page.locator('.memo-browse-section');
  await expect(browse.getByRole('heading', { name: '최근 메모', exact: true })).toBeVisible();
  await browse.locator(`#memo-browse-result-${RAW_MEMO}`).click();
  const detail = page.locator('dialog[open]');
  await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText('원문만 저장한 공개 synthetic 메모');
  await expect(detail.getByRole('button', { name: '정리하기', exact: true })).toBeEnabled();
  await detail.getByRole('button', { name: '최근 메모 상세 닫기', exact: true }).click();
  const filters = browse.getByRole('group', { name: '최근 메모 상태', exact: true });
  await filters.getByRole('button', { name: '휴지통', exact: true }).click();
  await browse.locator(`#memo-browse-result-${TRASHED_MEMO}`).click();
  await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText('휴지통의 공개 synthetic 메모');
  await expect(detail.getByRole('button', { name: '복원', exact: true })).toBeEnabled();
  expect(state.restoreWrites).toEqual([]);
  expect(state.searchRequests).toEqual([]);
  await detail.getByRole('button', { name: '복원', exact: true }).click();
  await expect(detail).toHaveCount(0);
  await expect(browse.getByText('휴지통이 비어 있습니다.', { exact: true })).toBeVisible();
  await filters.getByRole('button', { name: '최근 메모', exact: true }).click();
  await expect(browse.locator(`#memo-browse-result-${TRASHED_MEMO}`)).toBeVisible();
  expect(state.restoreWrites).toEqual([TRASHED_MEMO]);
  expect(state.searchRequests).toEqual([]);
  expect(state.saves).toEqual([]);
  expect(state.unexpectedApi).toEqual([]);
});

for (const surface of ['search', 'graph', 'browse'] as const) {
  test(`${surface} detail exposes a failed save retry and preserves request identity`, async ({ page }) => {
    const state = await mockWorkspace(page, 'retry');
    const detail = await openDetail(page, surface);
    await editMemo(detail);
    await detail.getByRole('button', { name: '저장', exact: true }).click();
    const retry = detail.getByRole('button', { name: '원문 저장 다시 시도', exact: true });
    await expect(retry).toBeVisible();
    await expect(detail.getByRole('alert')).toContainText('저장 연결을 다시 확인해 주세요.');
    await retry.focus();
    await expect(retry).toBeFocused();
    await expect(detail.getByRole('textbox', { name: '메모 수정', exact: true })).toHaveValue(SYNTHETIC_DRAFT);
    await retry.click();
    await expect(detail.locator('textarea')).toHaveCount(0);
    await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(SYNTHETIC_DRAFT);
    expect(state.saves).toHaveLength(2);
    expect(state.saves[0]!.idempotencyKey).toBeTruthy();
    expect(state.saves[1]).toEqual(state.saves[0]);
    expect(state.currentA().currentRevision).toBe(2);
    expect(state.unexpectedApi).toEqual([]);
  });

  test(`${surface} conflict recovery keeps the draft until explicit rebase and save`, async ({ page }) => {
    const state = await mockWorkspace(page, 'conflict');
    const detail = await openDetail(page, surface);
    await editMemo(detail);
    await detail.getByRole('button', { name: '저장', exact: true }).click();
    const reload = detail.getByRole('button', { name: '최신 메모 불러오기', exact: true });
    await expect(reload).toBeVisible();
    await reload.click();
    await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(CONCURRENT_CONTENT);
    await expect(detail.getByRole('textbox', { name: '메모 수정', exact: true })).toHaveValue(SYNTHETIC_DRAFT);
    await expect(detail.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
    expect(state.saves).toHaveLength(1);
    await detail.getByRole('button', { name: '내 수정 내용 유지', exact: true }).click();
    await expect(detail.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
    expect(state.saves).toHaveLength(1);
    expect(state.currentA().content).toBe(CONCURRENT_CONTENT);
    await detail.getByRole('button', { name: '저장', exact: true }).click();
    await expect(detail.locator('textarea')).toHaveCount(0);
    await expect(detail.locator('pre[aria-label="현재 원문"]')).toHaveText(SYNTHETIC_DRAFT);
    expect(state.saves).toHaveLength(2);
    expect(state.saves[1]!.body.expectedRevision).toBe(2);
    expect(state.saves[1]!.idempotencyKey).not.toBe(state.saves[0]!.idempotencyKey);
    expect(state.currentA().currentRevision).toBe(3);
    expect(state.unexpectedApi).toEqual([]);
  });
}

for (const pinned of [true, false]) {
  test(`dirty graph editor prevents ${pinned ? 'unpinning' : 'pinning'} until the draft is resolved`, async ({ page }) => {
    const state = await mockWorkspace(page, 'success', pinned);
    const detail = await openDetail(page, 'graph');
    const pin = detail.getByRole('button', {
      name: pinned ? '홈 그래프 고정 해제' : '홈 그래프에 고정', exact: true,
    });
    await expect(pin).toBeEnabled();
    await editMemo(detail);
    await expect(pin).toBeDisabled();
    await expect(detail.getByRole('textbox', { name: '메모 수정', exact: true })).toHaveValue(SYNTHETIC_DRAFT);
    expect(state.pinWrites).toEqual([]);
    await detail.getByRole('button', { name: '취소', exact: true }).click();
    await expect(pin).toBeEnabled();
    await pin.click();
    await expect.poll(() => state.pinWrites).toEqual([!pinned]);
    expect(state.unexpectedApi).toEqual([]);
  });
}
