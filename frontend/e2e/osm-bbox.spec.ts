import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Smoke test: OSM bbox fetch flow with a stubbed backend.
 *
 * /api/osm/fetch-roads is intercepted and fulfilled with a 3-road MapConfig.
 * The real Overpass API is never called.
 *
 * Does NOT exercise Run Simulation — that path is covered by vision-components.spec.ts.
 * Consequence: no real STOMP START is dispatched, backend stays STOPPED, no afterEach reset needed.
 */

// JSON fixture via fs.readFileSync — avoids deprecated `assert { type: 'json' }`
// ESM attribute syntax that Playwright's TS loader may reject. Same form as Plan 03.
const fixturePath = fileURLToPath(new URL('./fixtures/osm-fetch-roads-response.json', import.meta.url));
const cannedMapConfig = JSON.parse(readFileSync(fixturePath, 'utf-8'));

test.describe('OSM bbox fetch flow', () => {
  test('stubbed fetch: click Fetch Roads -> sidebar shows 3 roads, 2 intersections', async ({ page }) => {
    // 1. Stub the OSM endpoint.
    await page.route('**/api/osm/fetch-roads', async (route) => {
      expect(route.request().method()).toBe('POST');
      const body = route.request().postDataJSON() as { south: number; west: number; north: number; east: number };
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

    // 4. Click Fetch Roads.
    await page.getByRole('button', { name: /^Fetch Roads$/ }).click();

    // 5. Assert sidebar transitions to 'result' with the fixture's counts.
    await expect(page.getByText('3 roads, 2 intersections')).toBeVisible({ timeout: 10_000 });

    // 6. Assert result-state buttons appear.
    await expect(page.getByRole('button', { name: /^Run Simulation$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Export JSON$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^New Selection$/ })).toBeVisible();
  });
});
