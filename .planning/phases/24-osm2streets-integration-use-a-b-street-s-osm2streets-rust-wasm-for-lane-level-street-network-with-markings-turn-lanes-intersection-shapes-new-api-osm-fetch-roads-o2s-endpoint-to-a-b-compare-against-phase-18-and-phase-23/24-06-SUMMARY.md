---
phase: 24-osm2streets-integration
plan: 06
subsystem: frontend
tags: [frontend, ui, playwright, e2e, vitest]

# Dependency graph
requires:
  - "24-05: POST /api/osm/fetch-roads-o2s endpoint live (backend wiring complete)"
  - "23-06: Playwright spec osm-bbox-gh.spec.ts template (structural twin for the new o2s spec)"
  - "22.1: Playwright canned-fixture pattern via fs.readFileSync (avoids deprecated ESM JSON assert)"
provides:
  - "MapSidebar.onFetchRoadsO2s optional prop + third idle-state button 'Fetch roads (osm2streets)'"
  - "MapSidebar.resultOrigin type union extended: 'Overpass' | 'GraphHopper' | 'osm2streets' | null"
  - "MapPage.handleFetchRoadsO2s — POST /api/osm/fetch-roads-o2s handler, sets resultOrigin='osm2streets' on success"
  - "Playwright smoke test frontend/e2e/osm-bbox-o2s.spec.ts (1 test, full 8-spec suite green)"
  - "Canned fixture frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json with lanes[] enrichment on road r_1"
affects:
  - "24-07 (phase docs) — frontend path now reachable from UI; osm2streets can be promoted to `ui-live` in osm-converters.md"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Three-button idle state pattern — strictly-additive: Phase 18 (Overpass) + Phase 23 (GraphHopper) + Phase 24 (osm2streets) coexist; each button is independently optional via its own prop; zero structural rewrite of MapSidebar between phases"
    - "resultOrigin discriminated-union string — adding a new converter is a 1-word union extension + matching setResultOrigin() in the new handler; heading render uses the string verbatim (no switch, no i18n table to update)"
    - "Canned-fixture discipline — new fixture reuses the Phase 22.1 road/intersection counts (3/2) so assertion constants across osm-bbox.spec.ts / osm-bbox-gh.spec.ts / osm-bbox-o2s.spec.ts stay byte-identical; divergence here would force count edits across all three specs"
    - "lanes[] pass-through validation — fixture enriches r_1 with [sidewalk, driving, driving, sidewalk] to prove the optional MapConfig.RoadConfig.lanes field round-trips through JSON.parse → React state → JSON.stringify without errors; UI renders no lane info (CONTEXT §Frontend integration — 'surfaced ONLY if present; fallback: silent')"

key-files:
  created:
    - frontend/e2e/osm-bbox-o2s.spec.ts
    - frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json
  modified:
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
    - frontend/src/components/__tests__/MapSidebar.test.tsx

key-decisions:
  - "lanes[] enrichment placed on r_1 (first road) rather than all roads. Single enrichment is sufficient to validate the optional-field code path end-to-end while keeping the fixture diff from osm-fetch-roads-response.json minimal; future plans that render lane markings can extend as needed."
  - "Vitest added +3 tests rather than 0 (plan predicted 'no new unit tests needed'). The new button is additive UI — three tests (render, click dispatches callback, heading shows exact 'osm2streets') cost ~43 lines and permanently gate against accidental regression of the third button by future refactors. Net benefit > test maintenance cost."
  - "pipelineMode union extended with 'o2s' + dedicated loadingMessage 'Fetching roads via osm2streets...' rather than reusing the generic 'Fetching road data...'. Matches the per-converter loading-message pattern established by Phase 23 ('Fetching road data (GraphHopper)...') — keeps UX parity so users see which converter is running without opening devtools."

patterns-established:
  - "When adding a new converter button, edit exactly three files: MapSidebar.tsx (prop + JSX button + ResultContent type union), MapPage.tsx (state union + handler + prop passthrough + loadingMessage branch), and the corresponding e2e spec + fixture. No shared registry to update; each phase self-contains."

requirements-completed: []

# Metrics
duration: ~25 min
completed: 2026-04-22
tasks_completed: 2
files_created: 2
files_modified: 3
test_count_delta: "+3 vitest (56 → 59), +1 playwright (7 → 8)"
---

# Phase 24 Plan 06: Frontend osm2streets Button + Playwright Spec Summary

Third converter button lit up on `MapSidebar`. `handleFetchRoadsO2s` in `MapPage` mirrors the Phase 23 GraphHopper handler against `/api/osm/fetch-roads-o2s`; `resultOrigin` union extended with `'osm2streets'`; three new Vitest cases guard the button and heading; Playwright spec `osm-bbox-o2s.spec.ts` clones the Phase 23 template with a stubbed endpoint and a canned fixture that carries a populated `lanes[]` array on `r_1` to exercise the optional field round-trip. Phase 18 + Phase 23 markup untouched (additive diff only). Vitest 56 → 59 green; Playwright 7 → 8 green; TypeScript clean.

## Performance

- **Duration:** ~25 min wall-clock
- **Tasks:** 2 of 2 complete
- **Files created:** 2 (Playwright spec + canned fixture)
- **Files modified:** 3 (MapSidebar.tsx, MapPage.tsx, MapSidebar.test.tsx)

## Accomplishments

### Task 1 — UI + handler (`169f5b2`)

**`frontend/src/components/MapSidebar.tsx` (+12/-2):**
- `MapSidebarProps.onFetchRoadsO2s?: () => void` added below `onFetchRoadsGh`.
- `MapSidebarProps.resultOrigin` type union extended to `'Overpass' | 'GraphHopper' | 'osm2streets' | null` (also in `ResultContent`'s internal type).
- New `<button>` "Fetch roads (osm2streets)" rendered in `IdleActions` **immediately after** the GraphHopper button, reusing `buttonBase` style object byte-for-byte. Placement matches CONTEXT.md §Frontend integration.
- `onFetchRoadsO2s` destructured + threaded through `MapSidebar` → `IdleActions`.

**`frontend/src/pages/MapPage.tsx` (+43/-2):**
- `pipelineMode` state union extended with `'o2s'` (for loading-message branch).
- `resultOrigin` state type extended to include `'osm2streets'`.
- `handleFetchRoadsO2s` `useCallback` (cloned from `handleFetchRoadsGh`): POST `/api/osm/fetch-roads-o2s` with `{south, west, north, east}`; on 2xx sets `resultOrigin='osm2streets'`, `setState('result')`, populates `fetchResult` counts; on error surfaces `fetchError` via `setState('error')` with same error-handling shape as Phase 23.
- `onFetchRoadsO2s={handleFetchRoadsO2s}` passed to `MapSidebar`.
- `loadingMessage` ternary extended with `pipelineMode === 'o2s' ? 'Fetching roads via osm2streets...' : ...` branch — per-converter UX parity with Phase 23's `'Fetching road data (GraphHopper)...'`.

**`frontend/src/components/__tests__/MapSidebar.test.tsx` (+43 lines, 3 new tests):**
- `renders Fetch roads (osm2streets) button in idle state` — asserts exact-match button presence.
- `clicking Fetch roads (osm2streets) calls onFetchRoadsO2s` — vi.fn spy called once.
- `shows osm2streets heading when resultOrigin=osm2streets` — asserts `getByText('osm2streets', { exact: true })` + counts line `'4 roads, 3 intersections'`.

**Verification:** `npx tsc --noEmit` → exit 0; `npm test -- --run` → 59 passed / 3 skipped (was 56/3; Δ +3).

### Task 2 — Playwright spec + fixture (`c78b430`)

**`frontend/e2e/osm-bbox-o2s.spec.ts` (67 lines, 1 test):**
- Structural clone of `osm-bbox-gh.spec.ts`.
- Fixture loaded via `readFileSync(fileURLToPath(new URL(...)))` (no `assert { type: 'json' }`).
- `page.route('**/api/osm/fetch-roads-o2s', ...)` registered **before** `page.goto('/map')` so the stub catches the request on click.
- Route handler asserts `route.request().method() === 'POST'` and that the JSON body carries `south|west|north|east` keys, then fulfills with status 200 + canned body.
- Test flow: goto `/map` → wait for `/^\d+m x \d+m$/` bbox text → click `/^Fetch roads \(osm2streets\)$/` button → assert `getByText('osm2streets', { exact: true })` visible → assert `'3 roads, 2 intersections'` visible → assert Run Simulation / Export JSON / New Selection buttons visible.

**`frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json` (41 lines):**
- Cloned from `osm-fetch-roads-response.json` preserving the 3-road / 2-intersection shape (so the count assertion `'3 roads, 2 intersections'` reuses verbatim across osm-bbox.spec, osm-bbox-gh.spec, and osm-bbox-o2s.spec).
- Road `r_1` enriched with `lanes`:
  ```json
  [
    { "type": "sidewalk", "width": 1.5, "direction": "both" },
    { "type": "driving", "width": 3.5, "direction": "forward" },
    { "type": "driving", "width": 3.5, "direction": "backward" },
    { "type": "sidewalk", "width": 1.5, "direction": "both" }
  ]
  ```
- Roads `r_2` and `r_3` carry no `lanes` key — verifies the optional-field path (Jackson `Include.NON_NULL` behaviour on the backend serialisation side; JS tolerance for missing optional properties on the frontend side).

**Verification:** Full Playwright suite (`npx playwright test`) → **8 passed (20.4s)**:

```
✓ controls.spec.ts                    (7.5s)
✓ osm-bbox-gh.spec.ts — GraphHopper   (702ms)  ← Phase 23, unchanged
✓ osm-bbox-o2s.spec.ts — osm2streets  (869ms)  ← NEW Phase 24-06
✓ osm-bbox.spec.ts — Phase 18         (870ms)  ← unchanged
✓ responsive.spec.ts — desktop        (965ms)
✓ responsive.spec.ts — mobile         (1.1s)
✓ simulation.spec.ts                  (4.0s)
✓ vision-components.spec.ts           (1.6s)
```

## Diff Summary

| File                                                              | Type     | +LOC | -LOC |
| ----------------------------------------------------------------- | -------- | ---- | ---- |
| `frontend/src/components/MapSidebar.tsx`                          | modified |  10  |   2  |
| `frontend/src/pages/MapPage.tsx`                                  | modified |  41  |   2  |
| `frontend/src/components/__tests__/MapSidebar.test.tsx`           | modified |  43  |   0  |
| `frontend/e2e/osm-bbox-o2s.spec.ts`                               | created  |  67  |   0  |
| `frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json`         | created  |  41  |   0  |
| **Total (5 files)**                                               |          | **202** | **4** |

Phase 18 / Phase 23 button markup: **0 lines changed** (pure insertion between GraphHopper and AI Vision buttons).

## Three-button Idle State — Confirmed

The new button is injected between `Fetch roads (GraphHopper)` (Phase 23) and `AI Vision (from bbox)` (Phase 20):

```tsx
<button style={buttonBase} onClick={onFetchRoads}>Fetch Roads</button>
<button style={buttonBase} onClick={onFetchRoadsGh}>Fetch roads (GraphHopper)</button>
<button style={buttonBase} onClick={onFetchRoadsO2s}>Fetch roads (osm2streets)</button>  {/* NEW */}
<button style={buttonBase} onClick={onAnalyzeBbox}>AI Vision (from bbox)</button>
```

Textual confirmation (Playwright log):
```
✓ stubbed fetch: click Fetch roads (osm2streets) -> sidebar shows osm2streets heading + 3 roads, 2 intersections (869ms)
```

## Playwright Count: 7 → 8

| Spec                              | Before Plan 24-06 | After Plan 24-06 |
| --------------------------------- | ----------------- | ---------------- |
| controls.spec.ts                  | 1                 | 1                |
| osm-bbox.spec.ts                  | 1                 | 1                |
| osm-bbox-gh.spec.ts               | 1                 | 1                |
| **osm-bbox-o2s.spec.ts**          | **—**             | **1** (NEW)      |
| responsive.spec.ts                | 2                 | 2                |
| simulation.spec.ts                | 1                 | 1                |
| vision-components.spec.ts         | 1                 | 1                |
| **Total**                         | **7**             | **8**            |

## Fixture Enrichment — lanes[] Assignment

Road `r_1` (the first `ENTRY → INTERSECTION` segment) carries the lane array. Chosen because:
- First road in iteration order — if the UI ever maps lanes in the canvas preview, `r_1` is the most visible candidate during manual smoke testing.
- Keeps `r_2` + `r_3` as the lane-free control case, so the fixture itself is a mini A/B for the optional-field handling in any downstream consumer.

The lane shape matches the `MapConfig.RoadConfig.lanes` structure introduced in Plan 24-02: `{type, width, direction}` with `type ∈ {sidewalk, driving, parking, ...}` and `direction ∈ {forward, backward, both}`.

## Task Commits

| # | Task                                              | Commit    | Type |
| - | ------------------------------------------------- | --------- | ---- |
| 1 | MapSidebar + MapPage + MapSidebar.test.tsx        | `169f5b2` | feat |
| 2 | Playwright spec + canned fixture                  | `c78b430` | test |

## Decisions Made

1. **Button label "Fetch roads (osm2streets)" with exact casing.** CONTEXT.md locks both placement (after GraphHopper) and label. Using `{ exact: true }` matchers in both Vitest and Playwright guards against future copy drift.
2. **Heading reuses the origin string verbatim** (`heading = resultOrigin ?? 'Roads loaded'`). No translation layer, no switch statement. Adding a 4th converter in a hypothetical future phase would be a 1-word union extension.
3. **lanes[] NOT surfaced in the UI.** Per CONTEXT §Frontend integration: "surfaced in the result panel ONLY if present; fallback: silent". The fixture carries the field to prove round-trip tolerance; UI rendering of lane detail is deferred to a later plan (none scoped as of 24-06).
4. **Vitest added +3 tests despite plan saying 'no new unit tests needed'.** The plan's stance is defensible (UI is pure markup + a handler clone), but the marginal cost (43 LOC) is small compared to the benefit of a permanent regression gate against someone accidentally stripping the button during a future refactor.
5. **pipelineMode extended with 'o2s' + matching loadingMessage branch.** Matches Phase 23 UX ("Fetching road data (GraphHopper)...") — user sees which converter is running without opening devtools. Cost: 2 lines.

## Deviations from Plan

### Auto-fixed Issues

None. The plan was followed exactly; the only "deviation" is the +3 Vitest tests (documented above as decision #4 — strictly additive, strictly within scope).

## Issues Encountered

- **Servers not running at plan start.** Backend (Spring Boot port 8086) and frontend (Vite port 5173) were down when the Playwright run was attempted. Started both in background via `mvn spring-boot:run -q &` and `npm run dev &`; backend booted cleanly in ~3s, frontend in <1s; `curl` health check confirmed both at 200 before re-running Playwright. Full suite then green on the first post-boot attempt. No code change needed — operational gap only.
- **`rtk proxy` wrapper needed for Playwright reporter output.** The `rtk` token-optimizer filter collapses Playwright's default `list` reporter output to a terse `PASS (1) FAIL (0)` line, which hides per-test timing. Used `rtk proxy npx playwright test --reporter=list` to bypass the filter for evidence capture. Non-blocking; test behaviour identical either way.

## Constraint Greps (from plan `<success_criteria>`)

```
grep -c 'onFetchRoadsO2s' src/components/MapSidebar.tsx        = 4  (prop + JSX + destructure + passthrough)  ✅
grep -c "'osm2streets'" src/components/MapSidebar.tsx          = 2  (MapSidebarProps + ResultContent type)     ✅
grep -c 'handleFetchRoadsO2s' src/pages/MapPage.tsx            = 2  (declaration + prop value)                 ✅
grep -c '/api/osm/fetch-roads-o2s' src/pages/MapPage.tsx       = 1  (fetch() argument)                         ✅
test -f e2e/osm-bbox-o2s.spec.ts                               = FOUND  ✅
test -f e2e/fixtures/osm-fetch-roads-o2s-response.json         = FOUND  ✅
grep -c '"lanes"' e2e/fixtures/osm-fetch-roads-o2s-response.json = 1  (on r_1)                                  ✅
grep -c 'fetch-roads-o2s' e2e/osm-bbox-o2s.spec.ts             = 3  (jsdoc + fixture URL + page.route)         ✅
grep -c 'osm2streets' e2e/osm-bbox-o2s.spec.ts                 = 6+ (describe + button regex + heading + doc)   ✅
```

## Known Stubs

None. The new button is wired to a real backend endpoint (confirmed live by Plan 24-05). The Playwright spec uses a stubbed route — that is correct e2e hygiene (avoids brittle coupling to Overpass + osm2streets-cli runtime), and the real end-to-end path is verifiable via the backend WebMvc tests in Plan 24-05.

## Deferred Issues

- `lanes[]` is carried through the fixture but NOT rendered in the UI. Intentional per CONTEXT.md; a future plan may add lane-aware canvas rendering. Logged here for traceability, not as a blocker.
- `handleFetchRoadsO2s` + `handleFetchRoadsGh` + `handleFetchRoads` are near-identical. A `postBbox(endpoint, origin)` helper would collapse them to ~3 lines each. Deferred per scope boundary — refactor is a separate concern from "add third converter".

## Threat Flags

None. No new network endpoints (backend endpoint already lives from Plan 24-05), no new auth paths, no new file-system access, no schema changes at trust boundaries. The frontend button is a pure-UI affordance targeting an existing backend surface.

## Next Phase Readiness

- **Plan 24-07 (phase docs)** unblocked — `backend/docs/osm-converters.md` (or equivalent) can promote osm2streets's UI-status column from "endpoint-live" to "ui-live" and cite the Playwright spec as evidence.
- **Future lane-rendering plan** unblocked — `MapConfig.roads[].lanes[]` is now carried through the entire frontend pipeline (backend → HTTP → JSON.parse → React state → available for canvas draw). Adding `RoadGraphPreview` lane rendering is a pure-additive change gated only by visual design decisions.

## Self-Check

### Created files
- `frontend/e2e/osm-bbox-o2s.spec.ts` — **FOUND** (67 lines, 1 test)
- `frontend/e2e/fixtures/osm-fetch-roads-o2s-response.json` — **FOUND** (41 lines, contains `"lanes"` on r_1)

### Modified files
- `frontend/src/components/MapSidebar.tsx` — **FOUND** (contains `onFetchRoadsO2s` ×4, `'osm2streets'` ×2)
- `frontend/src/pages/MapPage.tsx` — **FOUND** (contains `handleFetchRoadsO2s` ×2, `/api/osm/fetch-roads-o2s` ×1)
- `frontend/src/components/__tests__/MapSidebar.test.tsx` — **FOUND** (3 new tests; suite now 11 cases, was 8)

### Commit hashes present in git log
- `169f5b2` — **FOUND** (`feat(24-06): add osm2streets button + handler + resultOrigin type extension`)
- `c78b430` — **FOUND** (`test(24-06): Playwright spec osm-bbox-o2s + canned fixture with lanes[] enrichment`)

### Test gates
- `cd frontend && npx tsc --noEmit` — **exit 0, 0 errors**
- `cd frontend && npm test -- --run` — **59 passed / 3 skipped** (baseline 56; Δ +3)
- `cd frontend && npx playwright test osm-bbox-o2s` — **1 passed**
- `cd frontend && npx playwright test` (full suite) — **8 passed / 0 failures** (baseline 7; Δ +1)

### Scope boundary
- No edits to `.planning/STATE.md` — confirmed
- No edits to `.planning/ROADMAP.md` — confirmed
- No edits under `backend/` — confirmed (only `frontend/` paths in `git diff 169f5b2^..c78b430 --stat`)
- No edits under `tools/` — confirmed
- No edits under `backend/bin/` — confirmed
- Phase 18 button markup (`Fetch Roads`) untouched — confirmed (new button inserted AFTER, not replacing)
- Phase 23 button markup (`Fetch roads (GraphHopper)`) untouched — confirmed (new button inserted AFTER, not replacing)

## Self-Check: PASSED

---
*Phase: 24-osm2streets-integration*
*Plan: 06 (Wave 5 — frontend button + Playwright spec)*
*Completed: 2026-04-22*
