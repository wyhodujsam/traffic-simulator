/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    global: 'globalThis',
  },
  server: {
    port: 5173,
    proxy: {
      '/ws': {
        target: 'http://localhost:8086',
        ws: true,
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    // Exclude Playwright E2E specs — those are handled by `npm run test:e2e`.
    // Without this, Vitest attempts to collect frontend/e2e/*.spec.ts and crashes
    // on Playwright-only APIs (test.afterEach fixtures, etc.).
    exclude: ['node_modules/**', 'dist/**', 'e2e/**'],
  },
})
