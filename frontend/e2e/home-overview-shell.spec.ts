import { expect, test, type Page, type Route } from '@playwright/test';

const OWNER_ID = '11111111-1111-4111-8111-111111111111';

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store' },
    body: JSON.stringify(body),
  });
}

async function mockEmptyOwnerWorkspace(page: Page): Promise<void> {
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
      await fulfillJson(route, {
        nodes: [],
        edges: [],
        truncated: false,
        projectionVersion: '22222222-2222-4222-8222-222222222222',
      });
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

test('renders the Today-first owner shell without personal data or a backend', async ({ page }) => {
  await mockEmptyOwnerWorkspace(page);
  await page.setViewportSize({ width: 384, height: 854 });
  await page.goto('/');

  const overview = page.locator('.home-overview');
  await expect(overview.getByRole('heading', { name: '오늘', exact: true })).toBeVisible();
  await expect(overview).toContainText(/오늘 일정\s*0/);
  await expect(overview).toContainText(/우선 할 일\s*0/);
  await expect(overview).toContainText(/검토 대기\s*0/);
  await expect(page.getByText('서버 연결됨')).toBeVisible();
  await expect(page.getByRole('button', { name: '원문 저장 후 제안 분석' })).toBeVisible();
  await expectNoHorizontalOverflow(page);

  await page.setViewportSize({ width: 854, height: 384 });
  await page.locator('header.hero').scrollIntoViewIfNeeded();
  await expect(overview).toBeVisible();
  await expectNoHorizontalOverflow(page);
});
