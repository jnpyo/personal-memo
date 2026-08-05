import { expect, test } from '@playwright/test';

test('raw memo survives review, apply, reload, and undo', async ({ page }) => {
  const rawMemo = '11.25 OS과제 제출 E2E';
  const proposedTitle = 'OS과제 제출 E2E';
  const approvedTitle = 'OS과제 제출 E2E 수정';

  await page.goto('/');
  await expect(page.getByText('서버 연결됨')).toBeVisible();

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 Fake 분석' }).click();

  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await page.getByRole('button', { name: '나중에 검토' }).click();
  await expect(page.getByText(`“${proposedTitle}” 제안을 보류했습니다.`)).toBeVisible();

  await page.reload();
  await expect(page.getByText(`“${proposedTitle}” 제안을 보류했습니다.`)).toBeVisible();
  await page.getByRole('button', { name: '검토 계속하기' }).click();
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
