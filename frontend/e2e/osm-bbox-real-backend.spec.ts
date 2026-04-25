import { test, expect } from '@playwright/test';

/**
 * Phase 24.1 Plan 03 — real-backend Playwright e2e for the osm2streets endpoint.
 *
 * Unlike osm-bbox-o2s.spec.ts (Phase 24) and osm-bbox-gh.spec.ts (Phase 23), this spec does NOT
 * stub the backend with `page.route`. The backend is started by playwright.config.ts with
 * `--osm.overpass.urls=http://localhost:18086`, so the real OverpassXmlFetcher → Osm2StreetsService
 * → osm2streets-cli subprocess → StreetNetworkMapper pipeline executes against the local
 * fixture-server's canned Overpass XML.
 *
 * This is the third coverage surface from 24.1-CONTEXT.md (acceptance criterion 4): proves the
 * end-to-end real-backend flow actually works on the fixed Overpass query (Plan 24.1-01).
 */

const MODLIN_BBOX = { south: 52.431, west: 20.65, north: 52.438, east: 20.662 };

test.describe('OSM bbox fetch flow — REAL backend (no page.route stub)', () => {
  test('clicking Fetch roads (osm2streets) hits the real backend and renders ≥ 1 road', async ({ page, request }) => {
    // 1. Sanity check — the backend's outbound Overpass URL was overridden to localhost:18086
    //    AND the local fixture server is up. If either fails, fail fast with a useful message
    //    rather than discovering it via a brittle UI assertion.
    const fixtureHealth = await request.get('http://localhost:18086/health', { timeout: 5_000 });
    expect(fixtureHealth.ok(), 'local Overpass fixture server must be reachable on :18086').toBeTruthy();

    // 2. Drive the backend directly first as a smoke check — guards against UI-side flakiness
    //    masking a real-backend regression. If THIS call fails, the bug is server-side, not in
    //    the spec or the UI.
    const apiResponse = await request.post('http://localhost:8086/api/osm/fetch-roads-o2s', {
      headers: { 'Content-Type': 'application/json' },
      data: MODLIN_BBOX,
      timeout: 60_000,   // osm2streets-cli on the small fixture should finish well under 30s
    });
    expect(apiResponse.status(), `backend POST returned ${apiResponse.status()}, body=${await apiResponse.text()}`).toBe(200);
    const body = await apiResponse.json() as { roads?: unknown[] };
    expect(Array.isArray(body.roads) && body.roads.length, 'backend MapConfig.roads must be a non-empty array').toBeGreaterThan(0);

    // 3. Now drive the UI. Navigate to /map; wait for Leaflet to emit a bbox.
    await page.goto('/map');
    await expect(page.getByText(/^\d+m x \d+m$/)).toBeVisible({ timeout: 10_000 });

    // 4. Click "Fetch roads (osm2streets)". The frontend posts the CURRENT bbox (set by Leaflet,
    //    not necessarily MODLIN_BBOX), but the backend is fixture-served regardless of bbox so
    //    the result is the same MapConfig from the canned XML.
    await page.getByRole('button', { name: /^Fetch roads \(osm2streets\)$/ }).click();

    // 5. Sidebar must transition to result state — origin heading visible.
    await expect(page.getByText('osm2streets', { exact: true })).toBeVisible({ timeout: 30_000 });

    // 6. Counts line — loosened to a regex (vs the stubbed spec's exact "3 roads, 2 intersections")
    //    because real-backend output depends on StreetNetworkMapper translation, not on a
    //    pre-stubbed JSON. Assertion contract: at least one road; counts are non-zero.
    const counts = page.getByText(/^\d+ roads?, \d+ intersections?$/);
    await expect(counts).toBeVisible({ timeout: 10_000 });
    const countsText = await counts.textContent();
    expect(countsText, 'counts label must report ≥ 1 road').toMatch(/^([1-9]\d*) roads?, \d+ intersections?$/);

    // 7. No error banner — the result panel, not the error panel.
    await expect(page.getByText(/no roads found/i)).toHaveCount(0);
    await expect(page.getByText(/overpass api unavailable/i)).toHaveCount(0);
    await expect(page.getByText(/osm2streets unavailable/i)).toHaveCount(0);

    // 8. Result-state action buttons appear.
    await expect(page.getByRole('button', { name: /^Run Simulation$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Export JSON$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^New Selection$/ })).toBeVisible();
  });
});
