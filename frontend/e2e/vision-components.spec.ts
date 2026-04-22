import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Smoke test: AI Vision (component library) flow with a stubbed backend.
 *
 * - /api/vision/analyze-components-bbox is intercepted and fulfilled with the fixture.
 * - /api/command/load-config is ALSO intercepted (Run Simulation posts there) to avoid
 *   a real map load on the running backend.
 *
 * State leak note: handleRunSimulation ALSO calls store.sendCommand({ type: 'START' })
 * over the REAL STOMP WebSocket. That cannot be stubbed by page.route() (HTTP-only).
 * Consequence: after this test, backend is in RUNNING state. afterEach resets with
 * POST /api/command { type: 'STOP' } so Plans 02/06 see a STOPPED backend.
 *
 * The real Claude CLI / Overpass are never called.
 */

// JSON fixture via fs.readFileSync — avoids the deprecated ESM
// import-assertion / import-attribute JSON syntax that Playwright's TS loader may reject.
const fixturePath = fileURLToPath(new URL('./fixtures/vision-components-response.json', import.meta.url));
const cannedMapConfig = JSON.parse(readFileSync(fixturePath, 'utf-8'));

test.afterEach(async ({ request }) => {
  // handleRunSimulation dispatched a real STOMP START — reset backend to STOPPED.
  await request.post('http://localhost:8086/api/command', {
    data: { type: 'STOP' },
    failOnStatusCode: false,
  });
});

test.describe('AI Vision (component library) flow', () => {
  test('stubbed flow: click → preview → run → navigate to /', async ({ page }) => {
    // 1. Stub the vision endpoint. Match both with and without query string.
    await page.route('**/api/vision/analyze-components-bbox', async (route) => {
      expect(route.request().method()).toBe('POST');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(cannedMapConfig),
      });
    });

    // 2. Stub load-config so Run Simulation does not mutate the real backend state
    //    via the REST call. (The STOMP START side effect still fires; see afterEach.)
    await page.route('**/api/command/load-config', async (route) => {
      expect(route.request().method()).toBe('POST');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ok: true }),
      });
    });

    // 3. Navigate to /map.
    await page.goto('/map');

    // 4. Wait for Leaflet to emit its first bbox — the sidebar shows "Xm x Ym" only once bbox !== null.
    //    The dimension paragraph in IdleContent: `<p>{w}m x {h}m</p>`.
    await expect(page.getByText(/^\d+m x \d+m$/)).toBeVisible({ timeout: 10_000 });

    // 5. Click "AI Vision (component library)".
    await page.getByRole('button', { name: /AI Vision \(component library\)/i }).click();

    // 6. Assert the result sidebar renders — "2 roads, 1 intersections" from the fixture.
    //    MapSidebar ResultContent: <p>{roadCount} roads, {intersectionCount} intersections</p>.
    await expect(page.getByText('2 roads, 1 intersections')).toBeVisible({ timeout: 10_000 });

    // 7. Run Simulation — triggers stubbed load-config + REAL STOMP START + navigate('/').
    await page.getByRole('button', { name: /^Run Simulation$/ }).click();

    // 8. Assert navigation to /.
    //    navigate('/') is called synchronously after the fetch resolves.
    await expect(page).toHaveURL(/\/$/, { timeout: 10_000 });

    // 9. Confirm we are on SimulationPage: Controls header appears.
    await expect(page.getByRole('heading', { name: /^Controls$/ })).toBeVisible();
  });
});
