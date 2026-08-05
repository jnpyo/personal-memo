import { defineConfig } from '@playwright/test';

const startLocalPreview = process.env.E2E_START_LOCAL_PREVIEW === 'true';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
  webServer: startLocalPreview
    ? {
        command: 'npm run preview',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: false,
        timeout: 120_000,
        env: {
          API_PROXY_TARGET: process.env.E2E_API_PROXY_TARGET ?? 'http://backend:8080',
        },
      }
    : undefined,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    viewport: { width: 412, height: 915 },
    hasTouch: true,
    isMobile: true,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
});
