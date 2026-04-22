# E2E Tests (Playwright)

Smoke tests for critical user flows that require a real backend + real browser.
Complements Vitest unit tests (`npm test`) — they are NOT replaced.

## Running

Prerequisites: none for first local run — `playwright.config.ts` starts both servers.

```bash
cd frontend
npm run test:e2e                 # run all specs
npm run test:e2e -- --ui         # interactive UI mode
npm run test:e2e -- <spec-name>  # single spec
npm run test:e2e -- --list       # list without running (smoke check)
```

The `webServer` array in `playwright.config.ts` starts:
- Backend: `cd ../backend && mvn spring-boot:run -q` on `http://localhost:8086`
- Frontend: `npm run dev` on `http://localhost:5173`

If either port is already in use by a running dev server, Playwright reuses it
(via `reuseExistingServer: !process.env.CI`).

Cold start of `mvn spring-boot:run` takes ~20s; webServer timeout is 120s.

## Browsers

Chromium only for MVP. Firefox/WebKit will be added if a cross-browser bug is found.

## Adding a spec

1. Create `e2e/<feature>.spec.ts`.
2. Use `getByRole` / `getByLabel` selectors first; fall back to `data-testid` only when semantics are insufficient.
3. For network stubs use `page.route('**/api/...', route => route.fulfill({ body: JSON.stringify(fixture) }))`.
4. Place canned JSONs in `e2e/fixtures/`.

## Scope

Phase 22.1 ships five smoke tests:
- `simulation.spec.ts` — start/pause against real backend + `four-way-signal` map
- `vision-components.spec.ts` — AI Vision (component library) flow with stubbed `/api/vision/analyze-components-bbox`
- `osm-bbox.spec.ts` — OSM bbox load with stubbed `/api/osm/fetch-roads`
- `responsive.spec.ts` — mobile (375x667) + desktop (1920x1080) layouts
- `controls.spec.ts` — speed multiplier + spawn rate reflected in StatsPanel

## Out of scope (deferred)

- GitHub Actions CI — no CI config exists yet.
- Visual regression snapshots — maintenance burden too high for MVP.
- Firefox / WebKit — add when a browser-specific bug is found.
- E2E against the real Claude CLI or Overpass API — stubbed for determinism.
