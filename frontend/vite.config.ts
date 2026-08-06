import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export const BACKEND_NETWORK_ONLY_PATH_PATTERNS = [
  /^\/api(?:\/|$)/,
  /^\/login\/oauth2(?:\/|$)/,
  /^\/oauth2(?:\/|$)/,
];

export const PWA_REGISTER_TYPE = 'prompt' as const;

export function isBackendNetworkOnlyPath(pathname: string): boolean {
  return BACKEND_NETWORK_ONLY_PATH_PATTERNS.some((pattern) => pattern.test(pathname));
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const apiProxyTarget = env.API_PROXY_TARGET || 'http://localhost:8080';
  const backendProxy = { target: apiProxyTarget, changeOrigin: true };
  const proxy = {
    '/api': backendProxy,
    '/oauth2': backendProxy,
    '/login/oauth2': backendProxy,
  };

  return {
    plugins: [
      react(),
      VitePWA({
        registerType: PWA_REGISTER_TYPE,
        workbox: {
          navigateFallbackDenylist: BACKEND_NETWORK_ONLY_PATH_PATTERNS,
          runtimeCaching: [
            {
              urlPattern: ({ url }) => isBackendNetworkOnlyPath(url.pathname),
              handler: 'NetworkOnly',
            },
          ],
        },
        manifest: {
          name: 'Personal Memo',
          short_name: 'Memo',
          description: '검토하고 승인한 결과만 지식과 할 일로 만드는 개인 메모',
          lang: 'ko',
          theme_color: '#17221c',
          background_color: '#f6f2e8',
          display: 'standalone',
          start_url: '/',
          icons: [
            {
              src: '/icons/icon-192.png',
              sizes: '192x192',
              type: 'image/png',
              purpose: 'any maskable',
            },
            {
              src: '/icons/icon-512.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'any maskable',
            },
          ],
        },
      }),
    ],
    server: { proxy },
    preview: { proxy },
  };
});
