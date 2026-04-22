import { test, expect, type Page } from '@playwright/test';

/**
 * Responsive layout smoke tests.
 *
 * Mirrors the intent of frontend/src/test/bugfixes.test.tsx (Phase 14) but uses real CSS
 * layout via Playwright boundingBox() — JSDOM cannot render flex geometry.
 *
 * Breakpoint is 768px (useIsMobile). Tests sample 375 (mobile) and 1920 (desktop).
 * Vehicle spawning is not required; these tests only care about static layout.
 */

async function getBoxes(page: Page) {
  const main = page.locator('main').first();
  const aside = page.locator('aside').first();
  await expect(main).toBeVisible();
  await expect(aside).toBeVisible();
  const mainBox = await main.boundingBox();
  const asideBox = await aside.boundingBox();
  if (!mainBox || !asideBox) throw new Error('boundingBox returned null');
  return { mainBox, asideBox };
}

test.describe('Responsive layout', () => {
  test.use({ viewport: { width: 1920, height: 1080 } });

  test('desktop (1920x1080): canvas is left-of sidebar, same row', async ({ page }) => {
    await page.goto('/');
    const { mainBox, asideBox } = await getBoxes(page);

    // main (canvas area) is to the LEFT of aside (sidebar).
    // Right edge of main must not extend past left edge of aside (plus small overlap tolerance).
    expect(mainBox.x + mainBox.width).toBeLessThanOrEqual(asideBox.x + 2);

    // Both elements roughly share the same vertical band (side-by-side).
    // Their top edges should differ by less than 100px (DisconnectBanner may shift one).
    expect(Math.abs(mainBox.y - asideBox.y)).toBeLessThan(100);

    // Sidebar not cut off: its full declared min-width (260px) fits inside the viewport.
    expect(asideBox.x + asideBox.width).toBeLessThanOrEqual(1920);
    expect(asideBox.width).toBeGreaterThanOrEqual(260 - 4); // allow 4px for subpixel/padding rounding
  });
});

test.describe('Responsive layout (mobile)', () => {
  test.use({ viewport: { width: 375, height: 667 } });

  test('mobile (375x667): canvas stacked above sidebar, full width', async ({ page }) => {
    await page.goto('/');
    const { mainBox, asideBox } = await getBoxes(page);

    // main (canvas area) is ABOVE aside (sidebar).
    // Bottom edge of main must be at or above top edge of aside.
    expect(mainBox.y + mainBox.height).toBeLessThanOrEqual(asideBox.y + 2);

    // Sidebar spans (close to) the full viewport width on mobile.
    expect(asideBox.width).toBeGreaterThanOrEqual(375 - 16); // allow 16px for scrollbar/padding

    // Canvas area is capped at 50vh on mobile (SimulationPage.tsx sets maxHeight: 50vh).
    expect(mainBox.height).toBeLessThanOrEqual(667 * 0.5 + 4);

    // Sidebar is visible (y starts within viewport).
    expect(asideBox.y).toBeLessThan(667);
  });
});
