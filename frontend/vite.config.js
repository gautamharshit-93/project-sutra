import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The Spring Boot API runs on 8080; proxying keeps the browser on one origin in development.
    // xfwd adds X-Forwarded-For, so the gateway sees each user's own address (its sign-in rate limit is per address).
    proxy: {
      '/api': { target: process.env.SUTRA_API_URL || 'http://localhost:8080', xfwd: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    css: false,
  },
});
