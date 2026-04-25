import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E config for traffic-simulator.
 *
 * - Runs Chromium only (Firefox/WebKit deferred per Phase 22.1 CONTEXT.md).
 * - fullyParallel: false — simulation has a single global tick loop; parallel specs would interfere.
 * - Three-server webServer (Phase 24.1 Plan 03):
 *   1. Local Overpass fixture HTTP server (port 18086) — must start FIRST so the backend's
 *      outbound Overpass calls have somewhere to land.
 *   2. Spring backend (port 8086) — started with --osm.overpass.urls=http://localhost:18086
 *      override so its OverpassXmlFetcher hits the local fixture instead of overpass-api.de.
 *   3. Frontend Vite dev server (port 5173).
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
      // Phase 24.1 Plan 03: local Overpass fixture HTTP server (port 18086) for the real-backend
      // e2e spec. Started before the backend so the backend's outbound Overpass call has somewhere
      // to land. See frontend/e2e/server/overpass-fixture-server.cjs.
      command: 'node ./e2e/server/overpass-fixture-server.cjs',
      url: 'http://localhost:18086/health',
      timeout: 10_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      // Phase 24.1 Plan 03: backend started with --osm.overpass.urls=http://localhost:18086 so
      // the real-backend e2e spec's call to /api/osm/fetch-roads-o2s hits the local fixture
      // server instead of overpass-api.de. The override syntax was confirmed in Plan 03's Task 1
      // spike — see 24.1-03-SPIKE-NOTES.md.
      command: 'cd ../backend && mvn spring-boot:run -q -Dspring-boot.run.arguments=--osm.overpass.urls=http://localhost:18086',
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
