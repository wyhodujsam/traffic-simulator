import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Phase 23 smoke test: OSM bbox fetch flow via the GraphHopper converter.
 *
 * Mirrors osm-bbox.spec.ts (Phase 22.1) structurally:
 *  - Same fixture (canned MapConfig with 3 roads + 2 intersections)
 *  - Stubbed /api/osm/fetch-roads-gh endpoint — no real backend call
 *  - Clicks "Fetch roads (GraphHopper)" button
 *  - Asserts sidebar shows `GraphHopper` origin heading + counts
 *
 * Does NOT exercise Run Simulation. No STOMP side effects; backend stays STOPPED,
 * no afterEach reset needed (matches Phase 22.1 osm-bbox.spec.ts discipline).
 */

// JSON fixture via fs.readFileSync — avoids deprecated `assert { type: 'json' }`
// ESM attribute syntax that Playwright's TS loader may reject. Same form as osm-bbox.spec.ts.
const fixturePath = fileURLToPath(new URL('./fixtures/osm-fetch-roads-response.json', import.meta.url));
const cannedMapConfig = JSON.parse(readFileSync(fixturePath, 'utf-8'));

test.describe('OSM bbox fetch flow — GraphHopper', () => {
  test('stubbed fetch: click Fetch roads (GraphHopper) -> sidebar shows GraphHopper heading + 3 roads, 2 intersections', async ({ page }) => {
    // 1. Stub ONLY the GraphHopper endpoint. Do NOT stub the Phase 18 endpoint
    //    (keeps regression guard from osm-bbox.spec.ts intact — the other spec still
    //    controls its own stub).
    await page.route('**/api/osm/fetch-roads-gh', async (route) => {
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

    // 4. Click the new Fetch roads (GraphHopper) button.
    await page.getByRole('button', { name: /^Fetch roads \(GraphHopper\)$/ }).click();

    // 5. Assert sidebar transitions to result — origin heading visible.
    //    { exact: true } avoids matching the button label "Fetch roads (GraphHopper)".
    await expect(page.getByText('GraphHopper', { exact: true })).toBeVisible({ timeout: 10_000 });

    // 6. Assert counts line renders — matches Phase 22.1 assertion shape (byte-identical).
    await expect(page.getByText('3 roads, 2 intersections')).toBeVisible({ timeout: 10_000 });

    // 7. Assert result-state action buttons appear.
    await expect(page.getByRole('button', { name: /^Run Simulation$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Export JSON$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^New Selection$/ })).toBeVisible();
  });
});
