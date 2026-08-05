import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const apiProxyTarget = env.API_PROXY_TARGET || 'http://localhost:8080';
  const proxy = { '/api': { target: apiProxyTarget, changeOrigin: true } };

  return {
    plugins: [
      react(),
      VitePWA({
        registerType: 'autoUpdate',
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
