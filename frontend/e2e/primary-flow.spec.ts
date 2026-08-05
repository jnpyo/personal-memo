import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test';
import { assertDestructiveCleanupAllowed } from '../src/shared/testing/destructiveCleanupGuard';

type RecoveryStatus = 'REVIEW_REQUIRED' | 'POSTPONED';

function assertCleanupPermission(): void {
  assertDestructiveCleanupAllowed(process.env.E2E_ALLOW_DESTRUCTIVE_CLEANUP);
}

async function expectOk(response: APIResponse): Promise<void> {
  if (!response.ok()) {
    throw new Error(`E2E cleanup failed (${response.status()}): ${await response.text()}`);
  }
}

async function rejectRecoverableProposals(
  request: APIRequestContext,
  status: RecoveryStatus,
): Promise<void> {
  for (let pass = 0; pass < 20; pass += 1) {
    const response = await request.get(`/api/v1/analysis-proposals?status=${status}&limit=50`);
    await expectOk(response);
    const proposals = (await response.json()) as Array<{ proposalId: string }>;
    if (proposals.length === 0) return;

    for (const proposal of proposals) {
      const rejected = await request.post(
        `/api/v1/analysis-proposals/${proposal.proposalId}/reject`,
        {
          headers: {
            'Idempotency-Key': `e2e-cleanup-proposal-${Date.now()}-${pass}-${proposal.proposalId}`,
          },
        },
      );
      await expectOk(rejected);
    }
  }
  throw new Error(`E2E cleanup could not drain ${status} proposals.`);
}

async function undoLatestApplication(request: APIRequestContext): Promise<void> {
  const response = await request.get('/api/v1/analysis-applications/latest');
  await expectOk(response);
  const application = (await response.json()) as {
    applicationId: string | null;
    status: 'NONE' | 'APPLIED' | 'UNDONE';
  };
  if (application.status !== 'APPLIED' || !application.applicationId) return;

  const undone = await request.post(
    `/api/v1/analysis-applications/${application.applicationId}/undo`,
    {
      headers: {
        'Idempotency-Key': `e2e-cleanup-application-${Date.now()}-${application.applicationId}`,
      },
    },
  );
  await expectOk(undone);
}

async function clearRecoverableServerState(request: APIRequestContext): Promise<void> {
  // Defense in depth: no request, including a future mutating cleanup request, runs before opt-in.
  assertCleanupPermission();
  await rejectRecoverableProposals(request, 'REVIEW_REQUIRED');
  await rejectRecoverableProposals(request, 'POSTPONED');
  await undoLatestApplication(request);
}

test.beforeAll(() => {
  assertCleanupPermission();
});

test.beforeEach(async ({ request }) => {
  // The README runs E2E against an isolated Compose project; drain only that test server's state.
  await clearRecoverableServerState(request);
});

test.afterEach(async ({ page, context, request }) => {
  await context.setOffline(false).catch(() => undefined);

  const reject = page.getByRole('button', { name: '제안 거절' });
  if (await reject.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await reject.click().catch(() => undefined);
  }

  const undo = page.getByRole('button', { name: '마지막 적용 되돌리기' });
  if (await undo.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await undo.click().catch(() => undefined);
  }

  await clearRecoverableServerState(request).catch(() => undefined);
});

test('raw memo survives review, apply, reload, and undo', async ({ page }, testInfo) => {
  const marker = `primary-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `11.25 OS과제 제출 E2E ${marker}`;
  const proposedTitle = `OS과제 제출 E2E ${marker}`;
  const approvedTitle = `${proposedTitle} 수정`;

  await page.goto('/');
  await expect(page.getByText('서버 연결됨')).toBeVisible();

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const reviewHeading = page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' });
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await page.reload();
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await page.getByRole('button', { name: '나중에 검토' }).click();
  await expect(page.getByText(`“${proposedTitle}” 제안을 보류했습니다.`)).toBeVisible();

  await page.reload();
  await expect(page.getByText(`“${proposedTitle}” 제안을 보류했습니다.`)).toBeVisible();
  await page.getByRole('button', { name: '검토 계속하기' }).click();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await page.getByLabel('대표 제목').fill(approvedTitle);
  await expect(page.getByLabel('항목 1 제목')).toHaveValue(approvedTitle);
  await page.getByLabel('마감 날짜').selectOption({ label: '날짜 직접 입력' });
  await page.getByLabel('확정 날짜').fill('2026-11-26');
  await page.getByRole('button', { name: '선택한 항목 승인' }).click();

  const task = page.locator('.task-row').filter({ hasText: approvedTitle });
  await expect(task).toBeVisible();
  await expect(task).toContainText('2026. 11. 26.');
  await expect(page.locator('.graph-node__content').filter({ hasText: approvedTitle })).toBeVisible();

  await page.reload();
  await expect(page.getByRole('button', { name: '마지막 적용 되돌리기' })).toBeVisible();
  await page.getByRole('button', { name: '마지막 적용 되돌리기' }).click();

  await expect(task).toHaveCount(0);
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
});

test('UNKNOWN analysis requires an explicit type and manually confirmed item', async ({ page }, testInfo) => {
  const marker = `unknown-e2e-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `${marker} 11.25 운영체제 과제`;
  const title = `${marker} 운영체제 과제`;

  await page.goto('/');
  await expect(page.getByText('서버 연결됨')).toBeVisible();

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const reviewHeading = page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' });
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await page.reload();
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await expect(page.getByLabel('대표 유형')).toHaveValue('');
  await expect(page.getByRole('button', { name: '선택한 항목 승인' })).toBeDisabled();
  await expect(page.getByText('아직 생성할 항목이 없습니다.')).toBeVisible();

  await page.getByLabel('대표 유형').selectOption('TASK');
  await page.getByRole('button', { name: '항목 직접 추가' }).click();
  const manualTitle = page.getByLabel('항목 1 제목');
  await expect(manualTitle).toHaveValue(title);
  await expect(manualTitle).toBeFocused();
  await expect(manualTitle).toBeInViewport();
  await page.getByRole('button', { name: '항목 직접 추가' }).click();
  const removableTitle = page.getByLabel('항목 2 제목');
  await expect(removableTitle).toBeFocused();
  await removableTitle.fill('적용하지 않을 보조 항목');
  await page.getByRole('button', { name: '항목 2 제거' }).click();
  await expect(manualTitle).toBeFocused();
  await expect(page.getByRole('button', { name: '선택한 항목 승인' })).toBeEnabled();
  await page.getByRole('button', { name: '선택한 항목 승인' }).click();

  await expect(page.locator('.task-row').filter({ hasText: title })).toBeVisible();
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
});

test('production build registers an installable offline app shell', async ({ page, context }) => {
  await page.goto('/');

  const manifest = await page.evaluate(async () => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]');
    if (!link) return null;
    const response = await fetch(link.href);
    return response.json() as Promise<{ icons?: Array<{ sizes?: string }> }>;
  });
  expect(manifest?.icons?.map((icon) => icon.sizes)).toEqual(
    expect.arrayContaining(['192x192', '512x512']),
  );

  const serviceWorkerReady = await page.evaluate(async () => {
    if (!('serviceWorker' in navigator)) return false;
    return Promise.race([
      navigator.serviceWorker.ready.then(() => true),
      new Promise<false>((resolve) => window.setTimeout(() => resolve(false), 8_000)),
    ]);
  });
  expect(serviceWorkerReady).toBe(true);

  await page.reload();
  await context.setOffline(true);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '생각을 먼저 적으세요.' })).toBeVisible();
  await context.setOffline(false);
});
