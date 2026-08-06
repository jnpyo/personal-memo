import { expect, test, type Page, type TestInfo } from '@playwright/test';

type TestCredentials = { email: string; password: string };
type CsrfToken = { headerName: string; token: string };
type AuthSession = { userId: string };

async function googleAuthorizationUrl(page: Page): Promise<URL> {
  const response = await page.request.get('/oauth2/authorization/google', {
    maxRedirects: 0,
  });
  expect(response.status()).toBe(302);
  const location = response.headers().location;
  expect(location).toBeTruthy();
  return new URL(location!);
}

async function createGoogleLinkIntent(page: Page): Promise<void> {
  const [csrfResponse, sessionResponse] = await Promise.all([
    page.request.get('/api/v1/auth/csrf'),
    page.request.get('/api/v1/auth/me'),
  ]);
  expect(csrfResponse.ok()).toBe(true);
  expect(sessionResponse.ok()).toBe(true);
  const csrf = await csrfResponse.json() as CsrfToken;
  const session = await sessionResponse.json() as AuthSession;

  const response = await page.request.post('/api/v1/auth/google/link-intent', {
    headers: {
      [csrf.headerName]: csrf.token,
      'X-Expected-Owner-Id': session.userId,
    },
  });
  expect(response.status()).toBe(200);
}

async function registerIsolatedUser(page: Page, testInfo: TestInfo): Promise<TestCredentials> {
  const unique = `${Date.now()}-${testInfo.workerIndex}-${testInfo.retry}-${Math.random().toString(36).slice(2, 9)}`;
  const credentials = {
    email: `memo-e2e-${unique}@example.com`,
    password: 'memo-e2e-passphrase',
  };

  await page.goto('/');
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  const createAccount = page.getByRole('button', { name: '계정 만들기', exact: true });
  await expect(createAccount).toBeEnabled();
  await createAccount.click();
  await page.getByLabel('표시 이름').fill(`E2E 사용자 ${unique}`);
  await page.getByLabel('이메일').fill(credentials.email);
  await page.getByLabel('비밀번호').fill(credentials.password);
  await page.locator('.auth-submit').click();
  await expect(page.getByText('서버 연결됨')).toBeVisible();
  return credentials;
}

async function invalidateServerSessionWithoutUpdatingTheApp(page: Page): Promise<void> {
  const status = await page.evaluate(async () => {
    const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
    const csrf = await csrfResponse.json() as { headerName: string; token: string };
    const response = await fetch('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { [csrf.headerName]: csrf.token },
    });
    return response.status;
  });
  expect(status).toBe(204);
}

test.afterEach(async ({ context }) => {
  await context.setOffline(false).catch(() => undefined);
});

test('creates a local account, logs out, and logs back in', async ({ page }, testInfo) => {
  const credentials = await registerIsolatedUser(page, testInfo);

  await page.getByLabel('계정 메뉴 열기').click();
  await expect(page.getByRole('heading', { name: '계정 설정' })).toBeVisible();
  await expect(page.getByText(credentials.email)).toBeVisible();
  await expect(page.getByText('이메일·비밀번호')).toBeVisible();

  const accountPanel = await page.locator('.account-panel__body').boundingBox();
  expect(accountPanel).not.toBeNull();
  expect(accountPanel!.x).toBeGreaterThanOrEqual(0);
  expect(accountPanel!.x + accountPanel!.width).toBeLessThanOrEqual(412);
  expect(accountPanel!.y + accountPanel!.height).toBeLessThanOrEqual(915);

  await page.getByRole('button', { name: '로그아웃' }).click();

  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await page.getByLabel('이메일').fill(credentials.email);
  await page.getByLabel('비밀번호').fill(credentials.password);
  await page.locator('.auth-submit').click();
  await expect(page.getByText('서버 연결됨')).toBeVisible();
});

test('keeps the workspace locked across reload until a failed logout is confirmed', async ({ page }, testInfo) => {
  await registerIsolatedUser(page, testInfo);
  await page.route('**/api/v1/auth/logout', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'simulated logout failure' }),
    });
  });

  await page.getByLabel('계정 메뉴 열기').click();
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(
    page.getByLabel('로그인', { exact: true })
      .getByText('서버에서 로그아웃을 확인하지 못했습니다.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃 다시 시도' })).toBeVisible();
  await expect(page.getByText('서버 연결됨')).toBeHidden();

  await page.reload();
  await expect(page.getByRole('button', { name: '로그아웃 다시 시도' })).toBeVisible();
  await expect(page.getByText('서버 연결됨')).toHaveCount(0);

  await page.unroute('**/api/v1/auth/logout');
  await page.getByRole('button', { name: '로그아웃 다시 시도' }).click();
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃 다시 시도' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '계정 만들기', exact: true })).toBeEnabled();
});

test('synchronizes an account change across tabs without retaining the previous draft', async ({
  context,
  page,
}, testInfo) => {
  const first = await registerIsolatedUser(page, testInfo);
  const capture = page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.');
  await capture.fill(`discard this ${first.email}`);

  const otherTab = await context.newPage();
  await otherTab.goto('/');
  await expect(otherTab.getByText('서버 연결됨')).toBeVisible();
  await otherTab.getByLabel('계정 메뉴 열기').click();
  await otherTab.getByRole('button', { name: '로그아웃' }).click();
  await expect(otherTab.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expect(otherTab.getByRole('button', { name: '계정 만들기', exact: true })).toBeEnabled();

  const second = await registerIsolatedUser(otherTab, testInfo);
  await expect(page.getByText('서버 연결됨')).toBeVisible();
  await page.getByLabel('계정 메뉴 열기').click();
  await expect(page.getByText(second.email)).toBeVisible();
  await expect(capture).toHaveValue('');
  await expect(page.getByText(first.email)).toHaveCount(0);
  await otherTab.close();
});

test('keeps unsaved proposal edits when another tab discovers the same owner', async ({
  context,
  page,
}, testInfo) => {
  const marker = `same-owner-tab-${Date.now()}-${testInfo.retry}`;
  const editedTitle = `${marker} 사용자가 수정한 제목`;
  await registerIsolatedUser(page, testInfo);

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 운영체제 과제 ${marker}`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await page.getByLabel('대표 제목').fill(editedTitle);

  const otherTab = await context.newPage();
  await otherTab.goto('/');
  await expect(otherTab.getByText('서버 연결됨')).toBeVisible();

  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await expect(page.getByLabel('대표 제목')).toHaveValue(editedTitle);
  await otherTab.close();
});

test('keeps receiver edits mounted while another tab logout remains unconfirmed', async ({
  context,
  page,
}, testInfo) => {
  const marker = `remote-logout-failure-${Date.now()}-${testInfo.retry}`;
  const editedTitle = `${marker} 보존할 수정 제목`;
  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 운영체제 과제 ${marker}`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await page.getByLabel('대표 제목').fill(editedTitle);

  const otherTab = await context.newPage();
  await otherTab.goto('/');
  await expect(otherTab.getByText('서버 연결됨')).toBeVisible();
  await otherTab.route('**/api/v1/auth/logout', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'simulated logout failure' }),
    });
  });
  await otherTab.getByLabel('계정 메뉴 열기').click();
  await otherTab.getByRole('button', { name: '로그아웃' }).click();

  await expect(
    otherTab.getByLabel('로그인', { exact: true })
      .getByText('서버에서 로그아웃을 확인하지 못했습니다.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃 다시 시도' })).toBeVisible();
  await expect(page.getByLabel('대표 제목')).toHaveValue(editedTitle);
  await otherTab.close();
});

test('returns to the login shell when the server session expires before a mutation', async ({ page }, testInfo) => {
  await registerIsolatedUser(page, testInfo);
  await invalidateServerSessionWithoutUpdatingTheApp(page);

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill('만료 뒤 저장되지 않을 원문');
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expect(page.getByText('로그인 세션이 만료되었습니다. 다시 로그인해 주세요.')).toBeVisible();
});

test('uses unmarked Google login state and marked explicit-link state without contacting Google', async ({
  page,
}, testInfo) => {
  test.skip(process.env.E2E_GOOGLE_ENABLED !== 'true', 'fake Google capability is disabled');

  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Google로 계속하기' }))
    .toHaveAttribute('href', '/oauth2/authorization/google');
  const loginAuthorization = await googleAuthorizationUrl(page);
  expect(loginAuthorization.searchParams.get('state')).not.toMatch(/^pm1\.link\./);
  expect(loginAuthorization.searchParams.get('client_id')).toBe('e2e-fake-client');
  expect(loginAuthorization.searchParams.get('scope')?.split(' ')).toEqual(
    expect.arrayContaining(['openid', 'profile', 'email']),
  );

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('계정 메뉴 열기').click();
  await expect(page.getByRole('button', { name: 'Google 계정 연결' })).toBeVisible();
  await createGoogleLinkIntent(page);
  const linkAuthorization = await googleAuthorizationUrl(page);
  expect(linkAuthorization.searchParams.get('state')).toMatch(/^pm1\.link\./);
  expect(linkAuthorization.searchParams.get('client_id')).toBe('e2e-fake-client');
});

test('raw memo survives review, apply, reload, and undo', async ({ page }, testInfo) => {
  const marker = `primary-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `11.25 OS과제 제출 E2E ${marker}`;
  const proposedTitle = `OS과제 제출 E2E ${marker}`;
  const approvedTitle = `${proposedTitle} 수정`;

  await registerIsolatedUser(page, testInfo);

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
  const graphNode = page.locator('.graph-node__content').filter({ hasText: approvedTitle });
  await graphNode.scrollIntoViewIfNeeded();
  await expect(graphNode).toBeVisible();
  await expect(graphNode).toBeInViewport();

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

  await registerIsolatedUser(page, testInfo);

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

test('production build registers an installable offline app shell', async ({ page, context }, testInfo) => {
  await registerIsolatedUser(page, testInfo);

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
  await expect(page.getByRole('heading', { name: /내 메모는/ })).toBeVisible();
  await expect(page.getByText(/오프라인에서는 로그인하거나 계정을 만들 수 없습니다/)).toBeVisible();
  await context.setOffline(false);
});
