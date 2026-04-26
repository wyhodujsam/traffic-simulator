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
      // Phase 24.1 Plan 03: backend started with two property overrides via JVM system
      // properties (the `-Dspring-boot.run.jvmArguments=...` form, NOT `spring-boot.run.arguments`).
      //
      // Why `jvmArguments` and not `arguments`:
      //   `spring-boot.run.arguments` is a COMMA-separated list. Our first override
      //   (`osm.overpass.urls=http://localhost:18086`) doesn't survive that split because the
      //   plugin re-joins the tail values into the property's own list, producing
      //   `mirrors=[http://localhost:18086, --osm2streets.binary-path=...]` instead of two
      //   separate property bindings (verified during the Plan 03 Task 5 deviation).
      //   `jvmArguments` is space-separated and passes each `-D...=...` directly to the JVM
      //   as an independent system property, which Spring's `@Value` reads cleanly.
      //
      // Overrides:
      //   1. -Dosm.overpass.urls=http://localhost:18086 — redirects OverpassXmlFetcher's outbound
      //      HTTP from overpass-api.de to the local fixture server (Plan 03 Task 1 spike confirmed
      //      the `--osm.overpass.urls=...` form worked alone; the equivalent `-D` form is what
      //      we use here so it composes cleanly with the second override).
      //   2. -Dosm2streets.binary-path=bin/osm2streets-cli-linux-x64 — when mvn spring-boot:run
      //      starts with cwd=backend/, the production-default `backend/bin/...` path resolves to
      //      `backend/backend/bin/...` and the CLI subprocess fails with ENOENT. The cwd-relative
      //      `bin/...` form matches what OsmPipelineSmokeIT (Plan 02) uses via @TestPropertySource.
      command: "cd ../backend && mvn spring-boot:run -q '-Dspring-boot.run.jvmArguments=-Dosm.overpass.urls=http://localhost:18086 -Dosm2streets.binary-path=bin/osm2streets-cli-linux-x64'",
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
