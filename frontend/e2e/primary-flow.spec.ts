import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';

type TestCredentials = { email: string; password: string };
type CsrfToken = { headerName: string; token: string };
type AuthSession = { userId: string };
type ManifestIcon = {
  src?: string;
  sizes?: string;
  type?: string;
  purpose?: string;
};
type WebAppManifest = {
  id?: string;
  name?: string;
  short_name?: string;
  display?: string;
  scope?: string;
  start_url?: string;
  theme_color?: string;
  background_color?: string;
  icons?: ManifestIcon[];
};

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
  }));

  expect(dimensions.documentWidth).toBeLessThanOrEqual(dimensions.viewportWidth);
  expect(dimensions.bodyWidth).toBeLessThanOrEqual(dimensions.viewportWidth);
}

async function expectInsideViewport(page: Page, locator: Locator): Promise<void> {
  await expect(locator).toBeVisible();
  const [box, viewport] = await Promise.all([
    locator.boundingBox(),
    page.evaluate(() => ({ width: window.innerWidth, height: window.innerHeight })),
  ]);
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width);
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height);
}

async function expectMinimumTouchHeight(locator: Locator, minimum: number): Promise<void> {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.height).toBeGreaterThanOrEqual(minimum);
}

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

async function advanceMemoRevisionOutOfBand(page: Page, content: string): Promise<void> {
  const [csrfResponse, memosResponse] = await Promise.all([
    page.request.get('/api/v1/auth/csrf'),
    page.request.get('/api/v1/memos?status=ACTIVE&limit=1'),
  ]);
  expect(csrfResponse.ok()).toBe(true);
  expect(memosResponse.ok()).toBe(true);
  const csrf = await csrfResponse.json() as CsrfToken;
  const memos = await memosResponse.json() as Array<{ id: string; currentRevision: number }>;
  expect(memos).toHaveLength(1);

  const updateResponse = await page.request.patch(`/api/v1/memos/${memos[0].id}`, {
    headers: {
      [csrf.headerName]: csrf.token,
      'Idempotency-Key': `e2e-stale-update-${Date.now()}`,
    },
    data: {
      expectedRevision: memos[0].currentRevision,
      content,
      clientUpdatedAt: new Date().toISOString(),
      timeZone: 'Asia/Seoul',
    },
  });
  expect(updateResponse.status()).toBe(200);
}

async function createMemoOutOfBand(page: Page, content: string): Promise<void> {
  const sessionResponse = await page.request.get('/api/v1/auth/me');
  expect(sessionResponse.ok()).toBe(true);
  const session = await sessionResponse.json() as AuthSession;
  const idempotencyKey = `e2e-search-memo-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const data = {
    id: crypto.randomUUID(),
    content,
    clientCreatedAt: new Date().toISOString(),
    timeZone: 'Asia/Seoul',
  };

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const csrfResponse = await page.request.get('/api/v1/auth/csrf');
    expect(csrfResponse.ok()).toBe(true);
    const csrf = await csrfResponse.json() as CsrfToken;
    const response = await page.request.post('/api/v1/memos', {
      headers: {
        [csrf.headerName]: csrf.token,
        'X-Expected-Owner-Id': session.userId,
        'Idempotency-Key': idempotencyKey,
      },
      data,
    });
    if (response.status() === 201) return;
    const responseBody = await response.text();
    if (attempt === 0 && response.status() === 403 && responseBody.includes('CSRF_TOKEN_INVALID')) {
      continue;
    }
    expect(response.status(), responseBody).toBe(201);
  }
  throw new Error('Memo creation did not complete after the bounded CSRF retry.');
}

async function openProposalEditor(page: Page): Promise<void> {
  await page.getByRole('button', { name: '아니오, 다른 경우 보기' }).click();
  await page.getByRole('button', { name: /유형은 맞아요/ }).click();
  await expect(page.getByLabel('대표 제목')).toBeVisible();
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

test('keeps the 384px portrait and landscape shell usable without horizontal overflow', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 384, height: 854 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expectMinimumTouchHeight(page.locator('.auth-submit'), 48);
  await expectMinimumTouchHeight(page.getByRole('button', { name: '계정 만들기', exact: true }), 44);
  await expectNoHorizontalOverflow(page);

  await registerIsolatedUser(page, testInfo);
  const accountTrigger = page.getByLabel('계정 메뉴 열기');
  const capture = page.locator('.capture-bar');
  const captureSubmit = page.getByRole('button', { name: '원문 저장 후 제안 분석' });

  await expectMinimumTouchHeight(accountTrigger, 44);
  await expectMinimumTouchHeight(captureSubmit, 48);
  await expectInsideViewport(page, capture);
  await expectNoHorizontalOverflow(page);

  await accountTrigger.click();
  await expectInsideViewport(page, page.locator('.account-panel__body'));
  await accountTrigger.click();

  await page.setViewportSize({ width: 854, height: 384 });
  await page.locator('header.hero').scrollIntoViewIfNeeded();
  await expectInsideViewport(page, accountTrigger);
  await expectInsideViewport(page, capture);
  await expectMinimumTouchHeight(captureSubmit, 48);
  await expectNoHorizontalOverflow(page);
});

test('keeps the proposal popup usable on S24 portrait and landscape sizes', async ({
  page,
}, testInfo) => {
  const semanticRequests = { apply: 0, postpone: 0, reject: 0 };
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (/\/api\/v1\/analysis-proposals\/[^/]+\/apply$/.test(path)) semanticRequests.apply += 1;
    if (/\/api\/v1\/analysis-proposals\/[^/]+\/postpone$/.test(path)) semanticRequests.postpone += 1;
    if (/\/api\/v1\/analysis-proposals\/[^/]+\/reject$/.test(path)) semanticRequests.reject += 1;
  });
  await page.setViewportSize({ width: 384, height: 854 });
  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 OS과제 제출 popup-${Date.now()}-${testInfo.retry}`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  const yes = page.getByRole('button', { name: '예, 이대로 적용' });
  const no = page.getByRole('button', { name: '아니오, 다른 경우 보기' });
  await expectInsideViewport(page, dialog);
  await expectMinimumTouchHeight(yes, 48);
  await expectMinimumTouchHeight(no, 48);
  await expectNoHorizontalOverflow(page);

  await no.click();
  await expect(page.getByText('어떤 부분이 다른가요?')).toBeVisible();
  await expect(page.getByRole('heading', { name: '어떤 부분이 다른가요?' })).toBeFocused();
  await expectMinimumTouchHeight(page.getByRole('button', { name: '정보 유형 선택' }), 48);
  expect(semanticRequests).toEqual({ apply: 0, postpone: 0, reject: 0 });

  await page.setViewportSize({ width: 854, height: 384 });
  await expectInsideViewport(page, dialog);
  await expectNoHorizontalOverflow(page);

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  const resumeReview = page.getByRole('button', { name: '검토 팝업 열기' });
  await expect(resumeReview).toBeFocused();
  await resumeReview.click();
  await expect(page.getByText('어떤 부분이 다른가요?')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeFocused();
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
  const retryLogout = page.getByRole('button', { name: '로그아웃 다시 시도' });
  await retryLogout.evaluate((button: HTMLButtonElement) => button.click()).catch(() => {
    // A pending confirmation can finish while the test removes the failed-request route.
    // The terminal login-shell assertions below still prove that logout completed.
  });
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
    .fill(`11.25 운영체제 과제 ${marker} 제출`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await expect(page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await openProposalEditor(page);
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
    .fill(`11.25 운영체제 과제 ${marker} 제출`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await openProposalEditor(page);
  await page.getByLabel('대표 제목').fill(editedTitle);

  const otherTab = await context.newPage();
  await otherTab.goto('/');
  await expect(otherTab.getByText('서버 연결됨')).toBeVisible();
  await otherTab.getByRole('button', { name: '검토 팝업 닫기' }).click();
  await expect(otherTab.getByRole('button', { name: '검토 팝업 열기' })).toBeFocused();
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

test('applies the complete AI recommendation only after an explicit yes', async ({ page }, testInfo) => {
  const marker = `yes-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `11.25 OS과제 E2E ${marker} 제출`;
  const proposedTitle = `OS과제 E2E ${marker} 제출`;

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await expect(dialog).toBeVisible();
  await expect(page.getByLabel('대표 제목')).toHaveCount(0);
  await expect(page.locator('.task-row').filter({ hasText: proposedTitle })).toHaveCount(0);

  let releaseOutcomeRefresh = () => {};
  let outcomeRefreshStarted = false;
  const heldOutcomeRefresh = new Promise<void>((resolve) => {
    releaseOutcomeRefresh = resolve;
  });
  await page.route('**/api/v1/analysis-review-outcomes/summary?days=14', async (route) => {
    outcomeRefreshStarted = true;
    await heldOutcomeRefresh;
    await route.continue();
  });

  try {
    await page.getByRole('button', { name: '예, 이대로 적용' }).click();

    await expect.poll(() => outcomeRefreshStarted).toBe(true);
    await expect(page.getByRole('button', { name: '마지막 적용 되돌리기' })).toBeEnabled({
      timeout: 5_000,
    });
  } finally {
    releaseOutcomeRefresh();
  }

  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.task-row').filter({ hasText: proposedTitle })).toBeVisible();
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
  const reviewOutcomes = page.locator('.review-outcome-section');
  await expect(reviewOutcomes).toContainText('AI의 정답률이나 정확도를 뜻하지');
  await expect(
    reviewOutcomes
      .locator('.review-outcome-metric')
      .filter({ hasText: '제안 그대로 적용' })
      .locator('dd'),
  ).toHaveText('1');
});

test('applies two explicitly bound task dates and undoes both together', async ({
  page,
}, testInfo) => {
  const marker = `date-binding-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `보고서 E2E ${marker} 초안은 11월 20일, 최종 제출은 11월 25일`;

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('마감 11월 20일 → 2026-11-20')).toBeVisible();
  await expect(dialog.getByText('마감 11월 25일 → 2026-11-25')).toBeVisible();
  await expect(page.getByRole('button', { name: '예, 이대로 적용' })).toBeEnabled();
  await expect(page.locator('.task-row')).toHaveCount(0);

  await page.getByRole('button', { name: '예, 이대로 적용' }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.task-row')).toHaveCount(2);
  await expect(
    page.locator('.task-row').filter({ hasText: '2026. 11. 20.' }),
  ).toBeVisible();
  await expect(
    page.locator('.task-row').filter({ hasText: '2026. 11. 25.' }),
  ).toBeVisible();
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();

  await page.getByRole('button', { name: '마지막 적용 되돌리기' }).click();

  await expect(page.locator('.task-row')).toHaveCount(0);
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
});

test('keeps an apply failure and its retry action inside the proposal popup', async ({
  page,
}, testInfo) => {
  const marker = `apply-retry-${Date.now()}-${testInfo.retry}`;
  const proposedTitle = `OS과제 E2E ${marker} 제출`;
  const applyAttempts: Array<{ idempotencyKey: string | undefined; body: string | null }> = [];

  page.on('request', (request) => {
    if (/\/api\/v1\/analysis-proposals\/[^/]+\/apply$/.test(new URL(request.url()).pathname)) {
      applyAttempts.push({
        idempotencyKey: request.headers()['idempotency-key'],
        body: request.postData(),
      });
    }
  });

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 ${proposedTitle}`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  await page.route('**/api/v1/analysis-proposals/*/apply', async (route) => {
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'simulated apply failure' }),
    });
  });

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await page.getByRole('button', { name: '예, 이대로 적용' }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('alert')).toContainText('simulated apply failure');
  const retry = dialog.getByRole('button', { name: '승인 다시 시도' });
  await expect(retry).toBeVisible();

  await openProposalEditor(page);
  await page.getByLabel('새 태그').fill('아직 추가하지 않은 태그');
  await expect(page.getByRole('button', { name: '수정한 내용 승인·적용' })).toBeDisabled();
  await expect(retry).toHaveCount(0);
  await page.getByLabel('새 태그').fill('');
  await expect(retry).toBeVisible();

  await page.unroute('**/api/v1/analysis-proposals/*/apply');
  await retry.click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.task-row').filter({ hasText: proposedTitle })).toBeVisible();
  expect(applyAttempts).toHaveLength(2);
  expect(applyAttempts[0].idempotencyKey).toBeTruthy();
  expect(applyAttempts[1]).toEqual(applyAttempts[0]);
});

test('reviews owner-visible relations manually and recovers an unavailable target without losing the draft', async ({
  page,
}, testInfo) => {
  const marker = `relation-review-${Date.now()}-${testInfo.retry}`;
  const rawMemo = `11.25 연결 검토 E2E ${marker} 제출`;
  const relationTagLabel = `연결 전용 태그 ${marker}`;
  const editedTitle = `연결 검토 수정 ${marker}`;
  const tagTargetId = '42a8b44d-e2f2-42d0-9168-49e19947e6e7';
  const memoTargetId = '8e2c76fd-c91f-4b8e-9657-5c0a737e334e';
  let sourceCandidateId: string | null = null;
  let relationCandidateRequests = 0;
  let targetBecameUnavailable = false;
  const applyAttempts: Array<{
    idempotencyKey: string | undefined;
    body: Record<string, unknown>;
  }> = [];

  await registerIsolatedUser(page, testInfo);
  await page.route(/\/api\/v1\/analysis-proposals\/[^/?]+$/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const proposal = await response.json() as {
      itemCandidates: Array<{ candidateId: string }>;
      relationCandidates: unknown[];
    };
    sourceCandidateId = proposal.itemCandidates[0]?.candidateId ?? null;
    expect(sourceCandidateId).toBeTruthy();
    await route.fulfill({
      response,
      json: {
        ...proposal,
        relationCandidates: [
          {
            sourceCandidateId,
            targetType: 'TAG',
            targetId: tagTargetId,
            relationType: 'RELATED_TO',
            score: 0.84,
          },
          {
            sourceCandidateId,
            targetType: 'MEMO',
            targetId: memoTargetId,
            relationType: 'REFERENCES',
            score: 0.72,
          },
        ],
      },
    });
  });
  await page.route('**/api/v1/analysis-proposals/*/relation-review-candidates', async (route) => {
    relationCandidateRequests += 1;
    expect(route.request().headers()['x-expected-owner-id']).toBeTruthy();
    expect(route.request().headers()['idempotency-key']).toBeUndefined();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify([
        {
          proposalIndex: 0,
          targetType: 'TAG',
          targetId: tagTargetId,
          targetLabel: targetBecameUnavailable ? null : relationTagLabel,
          available: !targetBecameUnavailable,
        },
        {
          proposalIndex: 1,
          targetType: 'MEMO',
          targetId: memoTargetId,
          targetLabel: null,
          available: false,
        },
      ]),
    });
  });
  await page.route(/\/api\/v1\/analysis-proposals\?status=REVIEW_REQUIRED&limit=1$/, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/v1/analysis-proposals/*/apply', async (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    applyAttempts.push({
      idempotencyKey: route.request().headers()['idempotency-key'],
      body,
    });
    if (applyAttempts.length === 1) {
      targetBecameUnavailable = true;
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'RELATION_TARGET_UNAVAILABLE',
          message: 'target changed during apply',
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ applicationId: 'mock-relation-application', status: 'APPLIED' }),
    });
  });

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('heading', { name: '선택한 내용을 확인해 주세요.' }))
    .toBeFocused();

  const relationOptions = dialog.locator('.relation-review-option');
  const availableOption = relationOptions.nth(0);
  const unavailableOption = relationOptions.nth(1);
  const availableCheckbox = availableOption.getByRole('checkbox');
  const unavailableCheckbox = unavailableOption.getByRole('checkbox');
  await expect(availableOption).toContainText(relationTagLabel);
  await expect(availableOption).toContainText('관련 있음 · 태그');
  await expect(availableCheckbox).toBeEnabled();
  await expect(availableCheckbox).not.toBeChecked();
  await expect(unavailableCheckbox).toBeDisabled();
  await availableOption.scrollIntoViewIfNeeded();
  await expectMinimumTouchHeight(availableOption, 48);
  await expectNoHorizontalOverflow(page);

  await page.getByLabel('대표 제목').fill(editedTitle);
  await availableCheckbox.check();
  await page.getByRole('button', { name: '수정한 내용 승인·적용' }).click();

  await expect(dialog.getByRole('alert')).toContainText('검토 내용은 유지되었습니다.');
  await expect(page.getByLabel('대표 제목')).toHaveValue(editedTitle);
  await expect.poll(() => relationCandidateRequests).toBe(2);
  await expect(availableCheckbox).toBeChecked();
  await expect(availableCheckbox).toBeDisabled();
  const excludeUnavailable = dialog.getByRole('button', { name: '이 연결 제외' });
  await expect(excludeUnavailable).toBeVisible();
  await expectMinimumTouchHeight(excludeUnavailable, 48);

  await excludeUnavailable.click();
  await expect(page.getByRole('button', { name: '수정한 내용 승인·적용' })).toBeEnabled();
  await page.getByRole('button', { name: '수정한 내용 승인·적용' }).click();
  await expect(dialog).toHaveCount(0);

  expect(applyAttempts).toHaveLength(2);
  expect(applyAttempts[0]?.idempotencyKey).toBeTruthy();
  expect(applyAttempts[1]?.idempotencyKey).toBeTruthy();
  expect(applyAttempts[1]?.idempotencyKey).not.toBe(applyAttempts[0]?.idempotencyKey);
  expect(applyAttempts[0]?.body).toMatchObject({
    title: editedTitle,
    selectedRelations: [{ proposalIndex: 0 }],
    items: expect.arrayContaining([
      expect.objectContaining({ proposalCandidateId: sourceCandidateId }),
    ]),
  });
  expect(applyAttempts[1]?.body).toMatchObject({
    title: editedTitle,
    selectedRelations: [],
  });
  expect(JSON.stringify(applyAttempts[0]?.body.selectedTags)).not.toContain(relationTagLabel);
});

test('keeps a generic apply conflict draft and exact retry identity with explicit empty relations', async ({
  page,
}, testInfo) => {
  const marker = `generic-conflict-${Date.now()}-${testInfo.retry}`;
  const editedTitle = `일반 충돌 초안 ${marker}`;
  const applyAttempts: Array<{ idempotencyKey: string | undefined; body: string | null }> = [];

  await registerIsolatedUser(page, testInfo);
  await page.route(/\/api\/v1\/analysis-proposals\?status=REVIEW_REQUIRED&limit=1$/, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
  await page.route('**/api/v1/analysis-proposals/*/apply', async (route) => {
    applyAttempts.push({
      idempotencyKey: route.request().headers()['idempotency-key'],
      body: route.request().postData(),
    });
    if (applyAttempts.length === 1) {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'FUTURE_CONFLICT', message: 'simulated generic conflict' }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ applicationId: 'mock-generic-application', status: 'APPLIED' }),
    });
  });

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 OS과제 E2E ${marker} 제출`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await openProposalEditor(page);
  await page.getByLabel('대표 제목').fill(editedTitle);
  await page.getByRole('button', { name: '수정한 내용 승인·적용' }).click();

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await expect(dialog).toBeVisible();
  await expect(page.getByLabel('대표 제목')).toHaveValue(editedTitle);
  await expect(dialog.getByRole('alert')).toContainText('입력한 검토 내용은 유지되었습니다.');
  const retry = dialog.getByRole('button', { name: '승인 다시 시도' });
  await expect(retry).toBeVisible();
  await expectMinimumTouchHeight(retry, 48);
  await retry.click();
  await expect(dialog).toHaveCount(0);

  expect(applyAttempts).toHaveLength(2);
  expect(applyAttempts[0]?.idempotencyKey).toBeTruthy();
  expect(applyAttempts[1]).toEqual(applyAttempts[0]);
  expect(JSON.parse(applyAttempts[0]?.body ?? '{}')).toMatchObject({
    title: editedTitle,
    selectedRelations: [],
  });
});

test('ignores an aborted late relation-label response after a new proposal opens', async ({
  page,
}, testInfo) => {
  const marker = `relation-generation-${Date.now()}-${testInfo.retry}`;
  const targetId = '7cf3f117-5b35-4631-8f6e-8c074d1a9de8';
  const staleLabel = `이전 연결 ${marker}`;
  const freshLabel = `현재 연결 ${marker}`;
  let proposalReads = 0;
  let relationCandidateRequests = 0;
  let releaseFirstResponse = () => {};
  let finishFirstResponse = () => {};
  const holdFirstResponse = new Promise<void>((resolve) => {
    releaseFirstResponse = resolve;
  });
  const firstResponseFinished = new Promise<void>((resolve) => {
    finishFirstResponse = resolve;
  });

  await registerIsolatedUser(page, testInfo);
  await page.route(/\/api\/v1\/analysis-proposals\/[^/?]+$/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const proposal = await response.json() as {
      itemCandidates: Array<{ candidateId: string }>;
    };
    proposalReads += 1;
    await route.fulfill({
      response,
      json: {
        ...proposal,
        relationCandidates: [{
          sourceCandidateId: proposal.itemCandidates[0]?.candidateId,
          targetType: 'TAG',
          targetId,
          relationType: 'CONTINUES',
          score: 0.78,
        }],
      },
    });
  });
  await page.route('**/api/v1/analysis-proposals/*/relation-review-candidates', async (route) => {
    relationCandidateRequests += 1;
    const requestNumber = relationCandidateRequests;
    if (requestNumber === 1) await holdFirstResponse;
    try {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store' },
        body: JSON.stringify([{
          proposalIndex: 0,
          targetType: 'TAG',
          targetId,
          targetLabel: requestNumber === 1 ? staleLabel : freshLabel,
          available: true,
        }]),
      });
    } catch {
      // The first browser request is expected to be aborted when its review closes.
    } finally {
      if (requestNumber === 1) finishFirstResponse();
    }
  });

  try {
    await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
      .fill(`11.25 첫 연결 E2E ${marker} 제출`);
    await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
    const firstDialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
    await expect(firstDialog.getByText('내 메모와 태그에서 연결 대상 이름을 확인하고 있습니다…'))
      .toBeVisible();
    await firstDialog.getByRole('button', { name: '이 제안 사용하지 않기' }).click();
    await firstDialog.getByRole('button', { name: '예, 제안만 버리기' }).click();
    await expect(firstDialog).toHaveCount(0);

    await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
      .fill(`11.25 둘째 연결 E2E ${marker} 제출`);
    const analyze = page.getByRole('button', { name: '원문 저장 후 제안 분석' });
    await expect(analyze).toBeEnabled();
    await analyze.click();

    const currentDialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
    await expect(currentDialog.getByText(freshLabel)).toBeVisible();
    await expect(currentDialog.getByRole('heading', { name: '선택한 내용을 확인해 주세요.' }))
      .toBeFocused();
    await expect(currentDialog.getByRole('checkbox')).not.toBeChecked();

    releaseFirstResponse();
    await firstResponseFinished;
    await expect(currentDialog.getByText(freshLabel)).toBeVisible();
    await expect(currentDialog.getByText(staleLabel)).toHaveCount(0);
    expect(proposalReads).toBe(2);
    expect(relationCandidateRequests).toBe(2);
  } finally {
    releaseFirstResponse();
  }
});

test('discards a failed apply retry when the proposal is postponed', async ({ page }, testInfo) => {
  const marker = `apply-postpone-${Date.now()}-${testInfo.retry}`;
  const proposedTitle = `OS과제 E2E ${marker} 제출`;
  let applyRequests = 0;

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.')
    .fill(`11.25 ${proposedTitle}`);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  await page.route('**/api/v1/analysis-proposals/*/apply', async (route) => {
    applyRequests += 1;
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'simulated apply failure' }),
    });
  });

  const dialog = page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' });
  await page.getByRole('button', { name: '예, 이대로 적용' }).click();
  await expect(dialog.getByRole('button', { name: '승인 다시 시도' })).toBeVisible();

  await dialog.getByRole('button', { name: '나중에 검토' }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: '승인 다시 시도' })).toHaveCount(0);
  await expect(page.getByText('제안을 보류했습니다. 승인 전이므로 생성된 항목은 없습니다.')).toBeVisible();
  await expect(page.locator('.task-row').filter({ hasText: proposedTitle })).toHaveCount(0);
  expect(applyRequests).toBe(1);
});

test('recovers from a stale proposal without offering the same apply retry', async ({
  page,
}, testInfo) => {
  const marker = `stale-review-${Date.now()}-${testInfo.retry}`;
  const original = `11.25 운영체제 과제 제출 ${marker}`;
  const revised = `${original} revision 2`;

  await registerIsolatedUser(page, testInfo);
  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(original);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await expect(page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();

  await advanceMemoRevisionOutOfBand(page, revised);
  await page.getByRole('button', { name: '예, 이대로 적용' }).click();

  await expect(page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '승인 다시 시도' })).toHaveCount(0);
  await expect(page.getByText('메모 상태가 다른 곳에서 변경되었습니다.')).toBeVisible();
  await expect(page.locator('.memo-card').filter({ hasText: revised })).toContainText('revision 2');
  await expect(page.locator('.task-row')).toHaveCount(0);
});

test('raw memo survives review, apply, reload, and undo', async ({ page }, testInfo) => {
  const marker = `primary-${Date.now()}-${testInfo.retry}`;
  const oldRawMemo = `기록: 운영체제 이전 참고 E2E ${marker}`;
  const rawMemo = `2026.11.25 운영체제 과제 E2E ${marker} 제출`;
  const proposedTitle = `운영체제 과제 E2E ${marker} 제출`;
  const approvedTitle = `${proposedTitle} 수정`;
  let rewrittenHomeRequests = 0;

  await page.route('**/api/v1/graph/home?limit=100', async (route) => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get('limit')).toBe('100');
    rewrittenHomeRequests += 1;
    url.searchParams.set('limit', '2');
    await route.continue({ url: url.toString() });
  });

  await registerIsolatedUser(page, testInfo);

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(oldRawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();
  await expect(page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' })).toBeVisible();
  await page.getByRole('button', { name: '예, 이대로 적용' }).click();
  await expect(page.getByRole('dialog', { name: 'AI 제안을 확인해 주세요' })).toHaveCount(0);

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const reviewHeading = page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' });
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await expect(page.getByText('마감 2026.11.25 → 2026-11-25')).toBeVisible();
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
  await openProposalEditor(page);
  await page.getByRole('button', { name: '과제 태그 제외' }).click();
  await page.getByLabel('새 태그').fill('운영체제');
  await page.getByRole('button', { name: '추가', exact: true }).click();
  await expect(page.getByLabel('새 태그')).toHaveValue('');
  await page.getByLabel('대표 제목').fill(approvedTitle);
  await expect(page.getByLabel('항목 1 제목')).toHaveValue(approvedTitle);
  await page.getByLabel('마감 날짜').selectOption({ label: '날짜 직접 입력' });
  await page.getByLabel('확정 날짜').fill('2026-11-26');
  const applyEditedProposal = page.getByRole('button', { name: '수정한 내용 승인·적용' });
  await page.getByLabel('새 태그').fill('아직 추가하지 않은 태그');
  await expect(applyEditedProposal).toBeDisabled();
  await expect(page.getByText('입력한 태그를 반영하려면 ‘추가’를 누르거나 입력을 비워 주세요.'))
    .toBeVisible();
  await page.getByLabel('새 태그').fill('');
  await expect(applyEditedProposal).toBeEnabled();
  await applyEditedProposal.click();

  const task = page.locator('.task-row').filter({ hasText: approvedTitle });
  await expect(task).toBeVisible();
  await expect(task).toContainText('2026. 11. 26.');
  await expect(
    page.locator('.review-outcome-metric').filter({ hasText: '수정 후 적용' }).locator('dd'),
  ).toHaveText('1');
  const graphNode = page.locator('.graph-node--memo .graph-node__content')
    .filter({ hasText: approvedTitle });
  await graphNode.scrollIntoViewIfNeeded();
  await expect(graphNode).toBeVisible();
  await expect(graphNode).toBeInViewport();
  await expectMinimumTouchHeight(graphNode, 48);

  await graphNode.press('Enter');
  const graphDetail = page.getByRole('dialog', { name: `${approvedTitle} 상세` });
  const graphDetailDrawer = graphDetail.locator('.graph-detail-drawer');
  await expect(graphDetail).toBeVisible();
  await expectInsideViewport(page, graphDetailDrawer);
  await expectNoHorizontalOverflow(page);
  await expect(graphDetail.getByRole('heading', { name: `${approvedTitle} 상세` })).toBeFocused();
  await expect(
    graphDetail.getByRole('region', { name: '현재 원문' }).locator('pre'),
  ).toHaveText(rawMemo);
  await expect(graphDetail.getByText('revision 1')).toBeVisible();
  await expect(graphDetail.getByText(/할 일 상태 미완료/)).toBeVisible();
  await expect(graphDetail.getByText('#운영체제')).toBeVisible();
  await expect(graphDetail.getByText(/전체 메모에서 직접 연결된 태그를 페이지당 20개씩/))
    .toBeVisible();

  const pinMemo = graphDetail.getByRole('button', { name: '홈 그래프에 고정' });
  await expectMinimumTouchHeight(pinMemo, 48);
  await pinMemo.click();
  await expect(graphDetail.getByRole('button', { name: '홈 그래프 고정 해제' })).toBeVisible();
  await expect(graphNode).toContainText('고정됨');

  await page.setViewportSize({ width: 854, height: 384 });
  await expectInsideViewport(page, graphDetailDrawer);
  await expectNoHorizontalOverflow(page);
  await expectMinimumTouchHeight(
    graphDetail.getByRole('button', { name: '그래프 상세 닫기' }),
    48,
  );
  await page.setViewportSize({ width: 412, height: 915 });

  await graphDetail.getByRole('button', { name: '그래프 상세 닫기' }).click();
  await expect(graphDetail).toHaveCount(0);
  await expect(graphNode).toBeFocused();

  const tagNode = page.locator('.graph-node--tag .graph-node__content')
    .filter({ hasText: '운영체제' });
  const oldHomeGraphNode = page.locator('.graph-node--memo .graph-node__content')
    .filter({ hasText: oldRawMemo });
  await expect(oldHomeGraphNode).toHaveCount(0);
  await page.locator('.graph-canvas').evaluate((element) => {
    element.scrollIntoView({ block: 'start', behavior: 'auto' });
  });
  await expect(tagNode).toBeVisible();
  await expectMinimumTouchHeight(tagNode, 48);
  await tagNode.click({ position: { x: 24, y: 24 } });
  const tagDetail = page.getByRole('dialog', { name: '운영체제 연결' });
  const graphDrawer = page.locator('dialog.graph-detail-dialog');
  await expect(tagDetail.getByText(`#운영체제`)).toBeVisible();
  await expect(tagDetail.getByText(approvedTitle)).toBeVisible();
  await expect(tagDetail.getByRole('button', { name: '홈 그래프에 고정' })).toHaveCount(0);
  const oldNeighborhoodMemo = tagDetail.getByRole('button', {
    name: new RegExp(oldRawMemo),
  });
  await expect(oldNeighborhoodMemo).toBeVisible();
  await expectMinimumTouchHeight(oldNeighborhoodMemo, 48);
  await oldNeighborhoodMemo.click();
  await expect(graphDrawer.getByRole('heading', { name: `${oldRawMemo} 상세` })).toBeFocused();
  await expect(
    graphDrawer.getByRole('region', { name: '현재 원문' }).locator('pre'),
  ).toHaveText(oldRawMemo);
  await expect(oldHomeGraphNode).toHaveCount(0);
  await graphDrawer.getByRole('button', { name: /운영체제 연결로 돌아가기/ }).click();
  await expect(tagDetail.getByRole('heading', { name: '운영체제 연결' })).toBeVisible();
  await expect(oldNeighborhoodMemo).toBeFocused();
  await tagDetail.getByRole('button', { name: '그래프 상세 닫기' }).click();
  await expect(tagDetail).toHaveCount(0);
  await expect(tagNode).toBeFocused();
  expect(rewrittenHomeRequests).toBeGreaterThan(0);

  await page.reload();
  await expect(page.getByRole('button', { name: '마지막 적용 되돌리기' })).toBeVisible();
  await page.getByRole('button', { name: '마지막 적용 되돌리기' }).click();

  await expect(task).toHaveCount(0);
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
  await expect(
    page.locator('.review-outcome-metric').filter({ hasText: '되돌림' }).locator('dd'),
  ).toHaveText('1');
});

test('private lexical search opens an off-home current raw memo without graph injection', async ({
  page,
}, testInfo) => {
  const marker = `search-${Date.now()}-${testInfo.retry}`;
  const targetRaw = `오래된 검색 대상 ${marker}`;
  await page.route('**/api/v1/graph/home?limit=100', async (route) => {
    const url = new URL(route.request().url());
    url.searchParams.set('limit', '2');
    await route.continue({ url: url.toString() });
  });
  await registerIsolatedUser(page, testInfo);
  await createMemoOutOfBand(page, targetRaw);
  await createMemoOutOfBand(page, `최근 메모 하나 ${marker}`);
  await createMemoOutOfBand(page, `최근 메모 둘 ${marker}`);
  await page.reload();

  const offHomeNode = page.locator('.graph-node--memo').filter({ hasText: targetRaw });
  await expect(offHomeNode).toHaveCount(0);
  const query = page.getByLabel('메모 검색어');
  await query.fill(targetRaw);
  const searchRequestPromise = page.waitForRequest((request) =>
    new URL(request.url()).pathname === '/api/v1/search/memos',
  );
  await query.press('Enter');
  const searchRequest = await searchRequestPromise;
  expect(searchRequest.method()).toBe('POST');
  expect(new URL(searchRequest.url()).search).toBe('');
  expect(searchRequest.postDataJSON()).toMatchObject({
    query: targetRaw,
    lifecycleStatus: 'ACTIVE',
    limit: 20,
  });
  expect(searchRequest.headers()['idempotency-key']).toBeUndefined();

  const result = page.locator('.memo-search-result').filter({ hasText: targetRaw });
  await expect(result).toBeVisible();
  await expectMinimumTouchHeight(result, 48);
  await result.click();
  const detail = page.getByRole('dialog', { name: `${targetRaw} 상세` });
  await expect(detail).toBeVisible();
  await expect(detail.getByRole('heading', { name: `${targetRaw} 상세` })).toBeFocused();
  await expect(detail.getByRole('region', { name: '현재 원문' }).locator('pre')).toHaveText(targetRaw);
  await expect(detail.getByText('revision 1')).toBeVisible();
  await expect(offHomeNode).toHaveCount(0);
  await expectNoHorizontalOverflow(page);

  await page.setViewportSize({ width: 854, height: 384 });
  await expectInsideViewport(page, detail.locator('.search-detail-drawer'));
  await expectNoHorizontalOverflow(page);
  await expectMinimumTouchHeight(detail.getByRole('button', { name: '검색 메모 상세 닫기' }), 48);
  await page.setViewportSize({ width: 412, height: 915 });
  await detail.getByRole('button', { name: '검색 메모 상세 닫기' }).click();
  await expect(detail).toHaveCount(0);
  await expect(result).toBeFocused();
});

test('latest search wins and an invalid continuation requires an explicit first-page restart', async ({
  page,
}, testInfo) => {
  await registerIsolatedUser(page, testInfo);
  let releaseDelayed!: () => void;
  let markDelayedStarted!: () => void;
  const delayed = new Promise<void>((resolve) => { releaseDelayed = resolve; });
  const delayedStarted = new Promise<void>((resolve) => { markDelayedStarted = resolve; });
  let cursorStarts = 0;
  const responseItem = (memoId: string, preview: string, revisedAt: string) => ({
    memoId,
    currentRevision: 1,
    canonicalRevision: null,
    title: null,
    preview,
    lifecycleStatus: 'ACTIVE',
    canonicalTags: [],
    taskState: 'NONE',
    overdue: false,
    pinned: false,
    revisedAt,
    matchedFields: ['BODY'],
  });

  await page.route('**/api/v1/search/memos', async (route) => {
    const body = route.request().postDataJSON() as { query: string; cursor?: string };
    if (body.query === 'delayed-a') {
      markDelayedStarted();
      await delayed;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [responseItem(
            '11111111-1111-4111-8111-111111111111',
            'delayed-a result',
            '2026-08-11T03:00:00Z',
          )],
          nextCursor: null,
          truncated: false,
        }),
      }).catch(() => undefined);
      return;
    }
    if (body.query === 'latest-b') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [responseItem(
            '22222222-2222-4222-8222-222222222222',
            'latest-b result',
            '2026-08-11T02:00:00Z',
          )],
          nextCursor: null,
          truncated: false,
        }),
      });
      return;
    }
    if (body.query === 'cursor-test' && body.cursor) {
      await route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'INVALID_SEARCH_CURSOR',
          message: 'The memo search cursor is invalid.',
        }),
      });
      return;
    }
    if (body.query === 'cursor-test') {
      cursorStarts += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [responseItem(
            cursorStarts === 1
              ? '33333333-3333-4333-8333-333333333333'
              : '44444444-4444-4444-8444-444444444444',
            cursorStarts === 1 ? 'stale accumulated result' : 'fresh restarted result',
            cursorStarts === 1 ? '2026-08-11T01:00:00Z' : '2026-08-11T04:00:00Z',
          )],
          nextCursor: cursorStarts === 1 ? 'cursor_1' : null,
          truncated: cursorStarts === 1,
        }),
      });
      return;
    }
    await route.fulfill({ status: 500, body: '{}' });
  });

  const query = page.getByLabel('메모 검색어');
  await query.fill('delayed-a');
  await query.press('Enter');
  await delayedStarted;
  await query.fill('latest-b');
  await query.press('Enter');
  const latestResult = page.locator('.memo-search-result').filter({ hasText: 'latest-b result' });
  await expect(latestResult).toBeVisible();
  releaseDelayed();
  await expect(latestResult).toBeVisible();
  await expect(page.locator('.memo-search-result').filter({ hasText: 'delayed-a result' })).toHaveCount(0);

  await query.fill('cursor-test');
  await query.press('Enter');
  const staleResult = page.locator('.memo-search-result').filter({ hasText: 'stale accumulated result' });
  await expect(staleResult).toBeVisible();
  await page.getByRole('button', { name: '결과 더 불러오기' }).click();
  await expect(page.getByRole('alert').filter({ hasText: '검색 결과가 변경되었거나' })).toBeVisible();
  await expect(page.getByRole('button', { name: '결과 더 불러오기' })).toHaveCount(0);
  await page.getByRole('button', { name: '처음부터 다시 검색' }).click();
  await expect(page.locator('.memo-search-result').filter({ hasText: 'fresh restarted result' })).toBeVisible();
  await expect(staleResult).toHaveCount(0);
});

test('search filters send an explicit half-open private body', async ({ page }, testInfo) => {
  await registerIsolatedUser(page, testInfo);
  let capturedBody: Record<string, unknown> | null = null;
  let capturedUrl = '';
  await page.route('**/api/v1/search/memos', async (route) => {
    capturedBody = route.request().postDataJSON() as Record<string, unknown>;
    capturedUrl = route.request().url();
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], nextCursor: null, truncated: false }),
    });
  });

  const searchSection = page.locator('.search-section');
  await searchSection.getByText('작업·수정일 필터').click();
  await searchSection.getByLabel('휴지통', { exact: true }).check();
  await searchSection.getByLabel('작업 상태').selectOption('NONE');
  await expect(searchSection.getByLabel('기한 지난 미완료만')).toBeDisabled();
  await searchSection.getByLabel('원문 수정일 시작').fill('2026-08-10');
  await searchSection.getByLabel('원문 수정일 끝 (포함)').fill('2026-08-11');
  await searchSection.getByLabel('메모 검색어').fill('필터 검색');
  await searchSection.getByLabel('메모 검색어').press('Enter');
  await expect(searchSection.getByText('일치하는 메모가 없습니다. 검색어나 필터를 바꿔 보세요.'))
    .toBeVisible();

  expect(new URL(capturedUrl).search).toBe('');
  expect(capturedBody).toEqual({
    query: '필터 검색',
    lifecycleStatus: 'TRASHED',
    taskState: 'NONE',
    revisedFrom: '2026-08-09T15:00:00.000Z',
    revisedBefore: '2026-08-11T15:00:00.000Z',
    limit: 20,
  });
  expect(JSON.stringify(capturedBody)).not.toContain('overdue');
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
  await expect(page.getByText('AI가 유형을 확정하지 못했어요.')).toBeVisible();
  await expect(page.getByRole('button', { name: '예, 이대로 적용' })).toHaveCount(0);
  await expect(page.getByLabel('대표 유형')).toHaveCount(0);

  await page.getByRole('button', { name: '할 일 유형 선택' }).click();
  await expect(page.getByLabel('대표 유형')).toHaveValue('TASK');
  const manualTitle = page.getByLabel('항목 1 제목');
  await expect(manualTitle).toHaveValue(title);
  await expect(manualTitle).toBeInViewport();
  await page.getByRole('button', { name: '항목 직접 추가' }).click();
  const removableTitle = page.getByLabel('항목 2 제목');
  await expect(removableTitle).toBeFocused();
  await removableTitle.fill('적용하지 않을 보조 항목');
  await page.getByRole('button', { name: '항목 2 제거' }).click();
  await expect(manualTitle).toBeFocused();
  await expect(page.getByRole('button', { name: '수정한 내용 승인·적용' })).toBeEnabled();
  await page.getByRole('button', { name: '수정한 내용 승인·적용' }).click();

  await expect(page.locator('.task-row').filter({ hasText: title })).toBeVisible();
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
});

test('production build registers an installable offline app shell', async ({
  page,
  context,
  browserName,
}, testInfo) => {
  await registerIsolatedUser(page, testInfo);

  expect(await page.evaluate(() => window.isSecureContext)).toBe(true);

  const manifest = await page.evaluate(async () => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="manifest"]');
    if (!link) return null;
    const response = await fetch(link.href);
    if (!response.ok) return null;
    return response.json() as Promise<WebAppManifest>;
  });
  expect(manifest).toMatchObject({
    id: '/',
    name: 'Personal Memo',
    short_name: 'Memo',
    display: 'standalone',
    scope: '/',
    start_url: '/',
    theme_color: '#17221c',
    background_color: '#f6f2e8',
  });
  expect(manifest?.icons?.map((icon) => icon.sizes)).toEqual(
    expect.arrayContaining(['192x192', '512x512']),
  );

  const iconResults = await page.evaluate(async (icons) => Promise.all(icons.map(async (icon) => {
    if (!icon.src) return { ...icon, ok: false, width: 0, height: 0, contentType: null };
    const response = await fetch(icon.src, { cache: 'no-store' });
    const bitmap = response.ok ? await createImageBitmap(await response.blob()) : null;
    const result = {
      ...icon,
      ok: response.ok,
      width: bitmap?.width ?? 0,
      height: bitmap?.height ?? 0,
      contentType: response.headers.get('content-type'),
    };
    bitmap?.close();
    return result;
  })), manifest?.icons ?? []);
  expect(iconResults).toEqual(expect.arrayContaining([
    expect.objectContaining({
      sizes: '192x192',
      type: 'image/png',
      purpose: 'any maskable',
      ok: true,
      width: 192,
      height: 192,
      contentType: 'image/png',
    }),
    expect.objectContaining({
      sizes: '512x512',
      type: 'image/png',
      purpose: 'any maskable',
      ok: true,
      width: 512,
      height: 512,
      contentType: 'image/png',
    }),
  ]));

  const serviceWorkerReady = await page.evaluate(async () => {
    if (!('serviceWorker' in navigator)) return false;
    return Promise.race([
      navigator.serviceWorker.ready.then(() => true),
      new Promise<false>((resolve) => window.setTimeout(() => resolve(false), 8_000)),
    ]);
  });
  expect(serviceWorkerReady).toBe(true);

  await page.reload();
  await expect(page.getByText('서버 연결됨')).toBeVisible();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);

  if (browserName === 'chromium') {
    const cdp = await context.newCDPSession(page);
    try {
      const result = await cdp.send('Page.getInstallabilityErrors');
      expect(result.installabilityErrors, JSON.stringify(result.installabilityErrors)).toEqual([]);
    } finally {
      await cdp.detach();
    }
  }

  await context.setOffline(true);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: /내 메모는/ })).toBeVisible();
  await expect(page.getByText(/오프라인에서는 로그인하거나 계정을 만들 수 없습니다/)).toBeVisible();

  const networkOnlyPaths = [
    '/api/v1/auth/me',
    '/api/v1/search/memos',
    '/oauth2/authorization/google',
    '/login/oauth2/code/google',
  ];
  const offlineResults = await page.evaluate(async (paths) => Promise.all(paths.map(async (path) => {
    try {
      const response = await fetch(path, {
        cache: 'no-store',
        credentials: 'same-origin',
        redirect: 'manual',
      });
      return { path, resolved: true, status: response.status };
    } catch {
      return { path, resolved: false, status: null };
    }
  })), networkOnlyPaths);
  expect(offlineResults).toEqual(networkOnlyPaths.map((path) => ({
    path,
    resolved: false,
    status: null,
  })));

  await context.setOffline(false);
});
