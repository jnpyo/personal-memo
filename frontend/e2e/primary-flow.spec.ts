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
    .fill(`11.25 운영체제 과제 ${marker}`);
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
    .fill(`11.25 운영체제 과제 ${marker}`);
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
  const rawMemo = `11.25 OS과제 제출 E2E ${marker}`;
  const proposedTitle = `OS과제 제출 E2E ${marker}`;

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

test('keeps an apply failure and its retry action inside the proposal popup', async ({
  page,
}, testInfo) => {
  const marker = `apply-retry-${Date.now()}-${testInfo.retry}`;
  const proposedTitle = `OS과제 제출 E2E ${marker}`;
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

test('discards a failed apply retry when the proposal is postponed', async ({ page }, testInfo) => {
  const marker = `apply-postpone-${Date.now()}-${testInfo.retry}`;
  const proposedTitle = `OS과제 제출 E2E ${marker}`;
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
  const rawMemo = `2026.11.25 운영체제 과제 제출 E2E ${marker}`;
  const proposedTitle = `운영체제 과제 제출 E2E ${marker}`;
  const approvedTitle = `${proposedTitle} 수정`;

  await registerIsolatedUser(page, testInfo);

  await page.getByLabel('메모 원문은 AI 결과와 별도로 먼저 저장됩니다.').fill(rawMemo);
  await page.getByRole('button', { name: '원문 저장 후 제안 분석' }).click();

  const reviewHeading = page.getByRole('heading', { name: 'AI 제안을 확인해 주세요' });
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toBeInViewport();
  await expect(page.getByText('날짜 2026.11.25 → 2026-11-25')).toBeVisible();
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
  const graphNode = page.locator('.graph-node__content').filter({ hasText: approvedTitle });
  await graphNode.scrollIntoViewIfNeeded();
  await expect(graphNode).toBeVisible();
  await expect(graphNode).toBeInViewport();

  await page.reload();
  await expect(page.getByRole('button', { name: '마지막 적용 되돌리기' })).toBeVisible();
  await page.getByRole('button', { name: '마지막 적용 되돌리기' }).click();

  await expect(task).toHaveCount(0);
  await expect(page.locator('.memo-card').filter({ hasText: rawMemo })).toBeVisible();
  await expect(
    page.locator('.review-outcome-metric').filter({ hasText: '되돌림' }).locator('dd'),
  ).toHaveText('1');
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
