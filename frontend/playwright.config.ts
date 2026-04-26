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
      // Phase 24.1 Plan 03 + Phase 24.2 Plan 03: backend started with one property
      // override via JVM system property (the `-Dspring-boot.run.jvmArguments=...`
      // form, NOT `spring-boot.run.arguments`).
      //
      // Why `jvmArguments` and not `arguments`:
      //   `spring-boot.run.arguments` is a COMMA-separated list. Even though we now
      //   pass only one override, sticking with `jvmArguments` keeps this entry
      //   composable with future overrides without re-discovering the comma-split
      //   pitfall (verified during Phase 24.1 Plan 03 Task 5 deviation).
      //   `jvmArguments` is space-separated and passes each `-D...=...` directly to
      //   the JVM as an independent system property, which Spring's `@Value` reads
      //   cleanly.
      //
      // Override:
      //   1. -Dosm.overpass.urls=http://localhost:18086 — redirects OverpassXmlFetcher's
      //      outbound HTTP from overpass-api.de to the local fixture server (Plan 03
      //      Task 1 spike confirmed the `--osm.overpass.urls=...` form worked alone;
      //      the equivalent `-D` form is what we use here so it composes cleanly with
      //      any future overrides).
      //
      // Phase 24.2 note: a SECOND override
      //   `-Dosm2streets.binary-path=bin/osm2streets-cli-linux-x64`
      // used to be required because `mvn spring-boot:run` runs with cwd=backend/ and
      // the production default `backend/bin/osm2streets-cli-linux-x64` resolved to
      // `backend/backend/bin/...` → ENOENT. Phase 24.2 Plan 01 made
      // Osm2StreetsConfig.getBinaryPath() smart-resolve the configured path against
      // the project root regardless of cwd, so the override is no longer needed and
      // would in fact short-circuit the very resolver this e2e suite should now
      // exercise. Removed deliberately.
      command: "cd ../backend && mvn spring-boot:run -q '-Dspring-boot.run.jvmArguments=-Dosm.overpass.urls=http://localhost:18086'",
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
