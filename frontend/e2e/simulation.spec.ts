import { test, expect, type Page } from '@playwright/test';

/**
 * Smoke test: start → vehicles spawn → pause → count freezes.
 *
 * Runs against REAL backend (Spring Boot :8086) and REAL frontend (Vite :5173).
 * No network stubs — exercises the full STOMP + tick loop + canvas pipeline.
 *
 * Map: four-way-signal (backend/src/main/resources/maps/four-way-signal.json).
 * Default spawn rate 0.5 veh/s → first vehicle within ~2-4s of START.
 *
 * State leak: Plan 03 (vision-components) also runs against the real backend and
 * leaves it in RUNNING state after dispatching STOMP START via Run Simulation.
 * This spec MUST reset backend to STOPPED in afterEach so 3x reruns and
 * cross-spec order independence both hold.
 */

const MAP_ID = 'four-way-signal';

async function readVehicleCount(page: Page): Promise<number> {
  // StatsPanel renders <div><span>Vehicles</span><span>{count}</span></div>.
  // The value span is the LAST span inside the div that contains the label span.
  const valueLocator = page
    .locator('div:has(> span:text-is("Vehicles"))')
    .locator('> span')
    .last();
  const text = (await valueLocator.textContent())?.trim() ?? '';
  const n = Number.parseInt(text, 10);
  return Number.isFinite(n) ? n : -1;
}

test.afterEach(async ({ request }) => {
  // Reset backend to STOPPED so this spec does not leak RUNNING state into
  // subsequent specs (Plans 03/06 run against the same real backend).
  // POST /api/command with { type: "STOP" } is the REST equivalent of the
  // STOMP STOP command and goes through the same SimulationController handler.
  await request.post('http://localhost:8086/api/command', {
    data: { type: 'STOP' },
    failOnStatusCode: false, // already-stopped backends respond non-2xx; that is fine
  });
});

test.describe('Simulation start/pause', () => {
  test('starts producing vehicles on four-way-signal, pause freezes the count', async ({ page }) => {
    // 1. Load the Simulation page.
    await page.goto('/');

    // 2. Confirm WebSocket is connected (disconnect banner absent).
    //    The banner appears only when `connected === false` in the Zustand store.
    //    useWebSocket connects on mount; give it up to 5s.
    await expect(page.getByText(/WebSocket disconnected/i)).toBeHidden({ timeout: 5_000 });

    // 3. Select the four-way-signal map (if not already default).
    const scenarioSelect = page.getByLabel('Scenario:');
    await expect(scenarioSelect).toBeVisible();
    // Wait for the option to be attached — availableMaps is fetched async from /api/maps.
    await expect(scenarioSelect.locator('option[value="four-way-signal"]')).toBeAttached({ timeout: 10_000 });
    await scenarioSelect.selectOption(MAP_ID);

    // 4. Wait for StatsPanel to render the Vehicles row (stats become non-null).
    //    Before the first tick arrives, StatsPanel shows "Waiting for data..." — we need the real row.
    await expect(page.getByText('Vehicles')).toBeVisible({ timeout: 10_000 });

    // 5. Press Start. Button label flips to 'Resume' after click (when status → RUNNING).
    await page.getByRole('button', { name: /^Start$/ }).click();

    // 6. Assert vehicle count becomes >= 1 within 15 seconds.
    //    20 Hz tick + 0.5 veh/s spawn rate → first vehicle typically 2-4s; 15s absorbs cold-start jitter.
    await expect.poll(
      async () => readVehicleCount(page),
      {
        message: 'Vehicle count did not reach >= 1 within 15s after Start',
        timeout: 15_000,
        intervals: [250, 500, 1000],
      }
    ).toBeGreaterThanOrEqual(1);

    // 7. Capture the running count, press Pause.
    const countBeforePause = await readVehicleCount(page);
    expect(countBeforePause).toBeGreaterThanOrEqual(1);

    await page.getByRole('button', { name: /^Pause$/ }).click();

    // 8. Wait 2.5 seconds and assert count did not increase.
    //    Paused tick loop means no spawns. Allow at most one trailing spawn
    //    in-flight when PAUSE was dispatched (a tick may have been mid-process).
    await page.waitForTimeout(2_500);
    const countAfterPause = await readVehicleCount(page);

    expect(countAfterPause).toBeLessThanOrEqual(countBeforePause + 1);
  });
});
