import { expect, test, type Locator, type Page, type Route } from '@playwright/test';

const OWNER_ID = '11111111-1111-4111-8111-111111111111';

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store' },
    body: JSON.stringify(body),
  });
}

const SYNTHETIC_GRAPH = {
  nodes: [
    {
      id: 'memo:00000000-0000-4000-8000-000000000001',
      kind: 'MEMO',
      label: '출시 점검 체크리스트',
      pinned: true,
      memoType: 'TASK',
      taskState: 'TODO',
      overdue: false,
    },
    {
      id: 'memo:00000000-0000-4000-8000-000000000002',
      kind: 'MEMO',
      label: '검색 흐름 개선 아이디어',
      pinned: false,
      memoType: 'IDEA',
      taskState: 'NONE',
      overdue: false,
    },
    {
      id: 'memo:00000000-0000-4000-8000-000000000003',
      kind: 'MEMO',
      label: '공개 데모 점검 일정',
      pinned: false,
      memoType: 'EVENT',
      taskState: 'NONE',
      overdue: false,
    },
    {
      id: 'memo:00000000-0000-4000-8000-000000000004',
      kind: 'MEMO',
      label: '접근성 참고 자료',
      pinned: false,
      memoType: 'INFORMATION',
      taskState: 'NONE',
      overdue: false,
    },
    {
      id: 'memo:00000000-0000-4000-8000-000000000005',
      kind: 'MEMO',
      label: '주간 품질 회고 기록',
      pinned: false,
      memoType: 'RECORD',
      taskState: 'NONE',
      overdue: false,
    },
    {
      id: 'tag:00000000-0000-4000-8000-000000000101',
      kind: 'TAG',
      label: '제품',
      pinned: false,
      memoType: null,
      taskState: null,
      overdue: false,
    },
    {
      id: 'tag:00000000-0000-4000-8000-000000000102',
      kind: 'TAG',
      label: '품질',
      pinned: false,
      memoType: null,
      taskState: null,
      overdue: false,
    },
    {
      id: 'tag:00000000-0000-4000-8000-000000000103',
      kind: 'TAG',
      label: '계획',
      pinned: false,
      memoType: null,
      taskState: null,
      overdue: false,
    },
  ],
  edges: [
    {
      id: 'edge:00000000-0000-4000-8000-000000000201',
      source: 'memo:00000000-0000-4000-8000-000000000001',
      target: 'tag:00000000-0000-4000-8000-000000000102',
      kind: 'MEMO_TAG',
    },
    {
      id: 'edge:00000000-0000-4000-8000-000000000202',
      source: 'memo:00000000-0000-4000-8000-000000000002',
      target: 'tag:00000000-0000-4000-8000-000000000101',
      kind: 'MEMO_TAG',
    },
    {
      id: 'edge:00000000-0000-4000-8000-000000000203',
      source: 'memo:00000000-0000-4000-8000-000000000003',
      target: 'tag:00000000-0000-4000-8000-000000000103',
      kind: 'MEMO_TAG',
    },
    {
      id: 'edge:00000000-0000-4000-8000-000000000204',
      source: 'memo:00000000-0000-4000-8000-000000000004',
      target: 'tag:00000000-0000-4000-8000-000000000102',
      kind: 'MEMO_TAG',
    },
    {
      id: 'edge:00000000-0000-4000-8000-000000000205',
      source: 'memo:00000000-0000-4000-8000-000000000005',
      target: 'tag:00000000-0000-4000-8000-000000000101',
      kind: 'MEMO_TAG',
    },
  ],
  truncated: false,
  projectionVersion: '22222222-2222-4222-8222-222222222222',
} as const;

async function mockSyntheticOwnerWorkspace(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;

    if (path === '/api/v1/auth/csrf') {
      await fulfillJson(route, { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'synthetic' });
      return;
    }
    if (path === '/api/v1/auth/capabilities') {
      await fulfillJson(route, {
        registrationEnabled: false,
        googleEnabled: false,
        googleRegistrationEnabled: false,
      });
      return;
    }
    if (path === '/api/v1/auth/me') {
      await fulfillJson(route, {
        userId: OWNER_ID,
        email: 'synthetic-owner@example.invalid',
        displayName: 'Synthetic Owner',
        loginMethods: ['LOCAL'],
      });
      return;
    }
    if (path === '/api/v1/health') {
      await fulfillJson(route, { status: 'UP' });
      return;
    }
    if (path === '/api/v1/tasks' || path === '/api/v1/events' || path === '/api/v1/memos') {
      await fulfillJson(route, []);
      return;
    }
    if (path === '/api/v1/graph/home') {
      await fulfillJson(route, SYNTHETIC_GRAPH);
      return;
    }
    if (path === '/api/v1/analysis-proposals') {
      await fulfillJson(route, []);
      return;
    }
    if (path === '/api/v1/analysis-applications/latest') {
      await fulfillJson(route, { applicationId: null, status: 'NONE' });
      return;
    }
    if (path === '/api/v1/analysis-review-outcomes/summary') {
      await fulfillJson(route, {
        schemaVersion: '1',
        comparisonPolicyVersion: 'review-default-v3',
        cohort: {
          basis: 'PROPOSAL_CREATED_AT',
          days: 14,
          fromInclusive: '2026-08-17T00:00:00Z',
          toExclusive: '2026-08-31T00:00:00Z',
          maxProposals: 1000,
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
      });
      return;
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const widths = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
  expect(widths.body).toBeLessThanOrEqual(widths.viewport);
}

async function expectMinimumTouchTarget(locator: Locator): Promise<void> {
  const count = await locator.count();
  expect(count).toBeGreaterThan(0);
  for (let index = 0; index < count; index += 1) {
    const box = await locator.nth(index).boundingBox();
    expect(box, `touch target ${index + 1} should have a layout box`).not.toBeNull();
    expect(box!.width, `touch target ${index + 1} width`).toBeGreaterThanOrEqual(44);
    expect(box!.height, `touch target ${index + 1} height`).toBeGreaterThanOrEqual(44);
  }
}

test('renders the synthetic graph-first shell and keeps capture out of the default view', async ({ page }) => {
  await mockSyntheticOwnerWorkspace(page);
  await page.setViewportSize({ width: 384, height: 854 });
  await page.goto('/');

  await expect(
    page.getByRole('heading', { level: 1, name: '연결 지도', exact: true }),
  ).toBeVisible();
  const graph = page.getByLabel('메모 5개와 태그 3개의 관계 그래프');
  await expect(graph).toBeVisible();
  for (const title of [
    '출시 점검 체크리스트',
    '검색 흐름 개선 아이디어',
    '공개 데모 점검 일정',
    '접근성 참고 자료',
    '주간 품질 회고 기록',
    '제품',
    '품질',
    '계획',
  ]) {
    await expect(graph.getByRole('button', { name: new RegExp(`^${title},`) })).toBeVisible();
  }

  const navigation = page.getByRole('navigation', { name: '주요 화면' });
  await expect(navigation.getByRole('button')).toHaveCount(4);
  for (const label of ['연결', '메모', '일정', '설정']) {
    await expect(navigation.getByRole('button', { name: label, exact: true })).toBeVisible();
  }
  await expect(page.getByRole('button', { name: '새 메모', exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '메모 내용' })).toBeHidden();
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeHidden();

  await navigation.getByRole('button', { name: '메모', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '메모 내용' })).toBeVisible();
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  await expect(
    page.getByText('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.', { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole('button', { name: '원문 저장 후 제안 분석' })).toHaveCount(0);

  await navigation.getByRole('button', { name: '일정', exact: true }).click();
  await expect(page.getByRole('heading', { level: 2, name: '할 일', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: '일정', exact: true })).toBeVisible();
  await expect(page.getByText('할 일이 없습니다.', { exact: true })).toBeVisible();
  await expect(page.getByText('일정이 없습니다.', { exact: true })).toBeVisible();
});

test('keeps public shell controls reachable at portrait and landscape mobile sizes', async ({ page }) => {
  await mockSyntheticOwnerWorkspace(page);

  for (const viewport of [
    { width: 384, height: 854 },
    { width: 854, height: 384 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto('/');

    const navigation = page.getByRole('navigation', { name: '주요 화면' });
    const fab = page.getByRole('button', { name: '새 메모', exact: true });
    await expect(page.getByLabel('메모 5개와 태그 3개의 관계 그래프')).toBeVisible();
    await expectMinimumTouchTarget(navigation.getByRole('button'));
    await expectMinimumTouchTarget(fab);
    await expectNoHorizontalOverflow(page);

    await navigation.getByRole('button', { name: '메모', exact: true }).click();
    const saveButton = page.getByRole('button', { name: '저장', exact: true });
    await expect(saveButton).toBeVisible();
    await expectMinimumTouchTarget(saveButton);
    await expectNoHorizontalOverflow(page);
  }
});
