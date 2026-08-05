import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [react(), VitePWA({ registerType: 'autoUpdate', manifest: { name: 'Personal Memo', short_name: 'Memo', theme_color: '#17221c', background_color: '#f6f2e8', display: 'standalone', start_url: '/', icons: [] } })],
  server: { proxy: { '/api': 'http://localhost:8080' } },
});

