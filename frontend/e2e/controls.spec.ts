import { test, expect, type Page } from '@playwright/test';

/**
 * Smoke test: sim controls (speed + spawn rate) are wired to the backend.
 *
 * Asserts:
 *   1. Tick counter advances after Start (proves STOMP + tick loop).
 *   2. Speed slider reflects a new value after change (proves slider wiring).
 *   3. After increasing spawn rate from 1x to 3x, vehicle count climbs
 *      to a measurably higher number than the observed count at 1x.
 *
 * Selector note: ControlsPanel has TWO <label>-wrapped range inputs whose
 * text begins with "Speed" — `Speed: Nx` (multiplier) and `Max speed: N km/h`.
 * A naive /Speed/ or /Speed:/ regex is a strict-mode violation. We scope to
 * the label whose text starts with exactly "Speed: " AND does NOT contain
 * "Max speed", then drill to the inner range input.
 *
 * Uses real backend + real frontend. No stubs. afterEach resets backend to STOPPED.
 */

const MAP_ID = 'four-way-signal';
const DEBOUNCE_MS = 200;

async function readStatValue(page: Page, label: string): Promise<number> {
  const valueLocator = page
    .locator(`div:has(> span:text-is("${label}"))`)
    .locator('> span')
    .last();
  const text = (await valueLocator.textContent())?.trim() ?? '';
  // Strip trailing unit (" km/h", " veh/km", " veh/min", "x") — keep the leading numeric.
  const match = /-?\d+(?:\.\d+)?/.exec(text);
  return match ? Number.parseFloat(match[0]) : -1;
}

test.afterEach(async ({ request }) => {
  // Reset backend to STOPPED so this spec does not leak RUNNING state into
  // subsequent specs (Plans 02/03 also run against the same real backend).
  await request.post('http://localhost:8086/api/command', {
    data: { type: 'STOP' },
    failOnStatusCode: false,
  });
});

test.describe('Simulation controls', () => {
  test('speed slider + spawn rate slider reflect in StatsPanel', async ({ page }) => {
    await page.goto('/');

    // 1. Select map and wait for stats row to appear once WS delivers first state.
    const scenarioSelect = page.getByLabel('Scenario:');
    // availableMaps populates async from /api/maps; wait for the option before selecting.
    await expect(scenarioSelect.locator('option[value="four-way-signal"]')).toBeAttached({ timeout: 10_000 });
    await scenarioSelect.selectOption(MAP_ID);
    await expect(page.getByText('Ticks received')).toBeVisible({ timeout: 10_000 });

    // 2. Start the sim.
    await page.getByRole('button', { name: /^Start$/ }).click();

    // 3. Assert tick counter advances within 5 seconds.
    await expect.poll(
      async () => readStatValue(page, 'Ticks received'),
      { message: 'Tick counter did not advance after Start', timeout: 5_000, intervals: [100, 250, 500] }
    ).toBeGreaterThan(5);

    // 4. Speed slider wiring — disambiguated from Max speed slider.
    //    ControlsPanel has TWO range inputs in <label> elements:
    //      - "Speed: {n.toFixed(1)}x" (multiplier, what we want)
    //      - "Max speed: {n} km/h" (max-speed, NOT what we want)
    //    Scope to the label whose text starts with "Speed: " exactly and does NOT contain "Max speed".
    const speedSlider = page
      .locator('label', { hasText: /^Speed: /, hasNotText: 'Max speed' })
      .locator('input[type="range"]');
    await expect(speedSlider).toHaveCount(1); // sanity: exactly one match
    await speedSlider.fill('3');
    // Label text update — anchor with ^ to avoid matching "Max speed: ...".
    await expect(page.getByText(/^Speed: 3\.0x/)).toBeVisible({ timeout: 2_000 });
    // Wait out the debounce so the STOMP command actually fires.
    await page.waitForTimeout(DEBOUNCE_MS + 100);

    // 5. Baseline vehicle-count sample at default spawn rate.
    //    Wait ~3s so a stable baseline is established.
    await page.waitForTimeout(3_000);
    const baselineVehicles = await readStatValue(page, 'Vehicles');
    expect(baselineVehicles).toBeGreaterThanOrEqual(0);

    // 6. Bump spawn rate to 3.0 veh/s.
    const spawnSlider = page.getByLabel(/Spawn rate:/);
    await spawnSlider.fill('3');
    await expect(page.getByText(/Spawn rate: 3\.0 veh\/s/)).toBeVisible({ timeout: 2_000 });
    await page.waitForTimeout(DEBOUNCE_MS + 100);

    // 7. After another ~6 seconds at 3x spawn rate, vehicle count must be higher than baseline.
    //    3 veh/s/entry * 4 entries = 12 veh/s ramping vs 0.5 * 4 = 2 veh/s — difference is pronounced.
    //    Allow for despawns: still expect at least +5 over baseline within 6s.
    await expect.poll(
      async () => readStatValue(page, 'Vehicles'),
      {
        message: `Vehicle count did not climb past baseline (${baselineVehicles}) + 5 after spawn rate bump`,
        timeout: 8_000,
        intervals: [500, 1_000],
      }
    ).toBeGreaterThan(baselineVehicles + 5);
  });
});
