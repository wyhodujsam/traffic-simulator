import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Phase 24 smoke test: OSM bbox fetch flow via the osm2streets converter.
 *
 * Mirrors osm-bbox-gh.spec.ts (Phase 23) structurally:
 *  - Canned MapConfig fixture with 3 roads + 2 intersections, plus optional lanes[] on road r_1
 *  - Stubbed /api/osm/fetch-roads-o2s endpoint — no real backend call, no osm2streets CLI subprocess
 *  - Clicks "Fetch roads (osm2streets)" button
 *  - Asserts sidebar shows `osm2streets` origin heading + counts
 *
 * The lanes[] enrichment on r_1 is a pass-through data shape validation — not surfaced in the UI
 * per CONTEXT §Frontend integration ("surfaced in the result panel ONLY if present; fallback: silent").
 * The spec verifies the frontend tolerates the optional field without errors; downstream consumers
 * (export JSON, future lane-aware rendering) can opt in to it as they land.
 */

// JSON fixture via fs.readFileSync — avoids deprecated `assert { type: 'json' }`
// ESM attribute syntax that Playwright's TS loader may reject. Same form as osm-bbox-gh.spec.ts.
const fixturePath = fileURLToPath(new URL('./fixtures/osm-fetch-roads-o2s-response.json', import.meta.url));
const cannedMapConfig = JSON.parse(readFileSync(fixturePath, 'utf-8'));

test.describe('OSM bbox fetch flow — osm2streets', () => {
  test('stubbed fetch: click Fetch roads (osm2streets) -> sidebar shows osm2streets heading + 3 roads, 2 intersections', async ({ page }) => {
    // 1. Stub ONLY the osm2streets endpoint. Do NOT stub Phase 18 or Phase 23 endpoints
    //    (keeps regression guard from osm-bbox.spec.ts and osm-bbox-gh.spec.ts intact — the other
    //    specs still control their own stubs).
    await page.route('**/api/osm/fetch-roads-o2s', async (route) => {
      expect(route.request().method()).toBe('POST');
      const body = route.request().postDataJSON() as {
        south: number; west: number; north: number; east: number;
      };
      expect(body).toHaveProperty('south');
      expect(body).toHaveProperty('west');
      expect(body).toHaveProperty('north');
      expect(body).toHaveProperty('east');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(cannedMapConfig),
      });
    });

    // 2. Navigate to /map.
    await page.goto('/map');

    // 3. Wait for Leaflet to emit the first bbox (sidebar meters text appears).
    await expect(page.getByText(/^\d+m x \d+m$/)).toBeVisible({ timeout: 10_000 });

    // 4. Click the new Fetch roads (osm2streets) button.
    await page.getByRole('button', { name: /^Fetch roads \(osm2streets\)$/ }).click();

    // 5. Assert sidebar transitions to result — origin heading visible.
    //    { exact: true } avoids matching the button label "Fetch roads (osm2streets)".
    await expect(page.getByText('osm2streets', { exact: true })).toBeVisible({ timeout: 10_000 });

    // 6. Assert counts line renders — matches Phase 22.1 / Phase 23 assertion shape (byte-identical).
    await expect(page.getByText('3 roads, 2 intersections')).toBeVisible({ timeout: 10_000 });

    // 7. Assert result-state action buttons appear.
    await expect(page.getByRole('button', { name: /^Run Simulation$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Export JSON$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^New Selection$/ })).toBeVisible();
  });
});
