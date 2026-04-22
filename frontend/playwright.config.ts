import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E config for traffic-simulator.
 *
 * - Runs Chromium only (Firefox/WebKit deferred per Phase 22.1 CONTEXT.md).
 * - fullyParallel: false — simulation has a single global tick loop; parallel specs would interfere.
 * - Two-server webServer: backend (Spring Boot, port 8086) + frontend (Vite, port 5173).
 * - Reuses existing dev servers locally; starts fresh in CI.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: [
    {
      command: 'cd ../backend && mvn spring-boot:run -q',
      url: 'http://localhost:8086/api/simulation/status',
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5173',
      timeout: 30_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
