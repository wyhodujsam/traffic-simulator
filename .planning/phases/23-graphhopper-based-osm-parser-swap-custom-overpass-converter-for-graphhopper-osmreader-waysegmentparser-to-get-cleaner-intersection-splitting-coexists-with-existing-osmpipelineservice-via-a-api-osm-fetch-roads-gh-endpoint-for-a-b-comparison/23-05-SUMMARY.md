---
phase: 23-graphhopper-based-osm-parser
plan: 05
subsystem: frontend-osm
tags: [frontend, react, osm, graphhopper, a-b-comparison, vitest, tdd]

# Dependency graph
requires:
  - phase: 23
    plan: 04
    provides: "POST /api/osm/fetch-roads-gh endpoint with BboxRequest contract + 422/503 error taxonomy"
provides:
  - "MapSidebar: fifth idle-state button 'Fetch roads (GraphHopper)' + resultOrigin prop drives result-heading label"
  - "MapPage: handleFetchRoadsGh handler wired to /api/osm/fetch-roads-gh; pipelineMode='gh'; resultOrigin threading for A/B visual distinction"
  - "5 new Vitest tests: 2 idle-button tests + 3 result-state origin tests; all use { exact: true } on heading assertions"
affects:
  - "23-06 Playwright spec — real MapSidebar now renders 'Fetch roads (GraphHopper)' + origin headings; stub must match"
  - "Phase 22.1 osm-bbox.spec.ts — compatibility preserved; counts text 'N roads, M intersections' byte-identical"

# Tech tracking
tech-stack:
  added: []  # No new deps; uses existing React 18 + TS + Vitest stack
  patterns:
    - "Additive prop extension — onFetchRoadsGh / resultOrigin added as optional props, zero breaking changes to existing MapSidebar callers"
    - "Origin-label via ??-fallback — single `heading = resultOrigin ?? 'Roads loaded'` variable; no dead 'headline' aggregate"
    - "Counts-line preservation for Playwright cross-compatibility — heading goes in separate <p>, counts <p> remains byte-identical to Phase 22.1 assertion target"
    - "{ exact: true } on short heading strings — guards against future copy containing 'Overpass'/'GraphHopper'/'Roads loaded' as substrings (e.g. 'Overpass timeout', 'GraphHopper warnings')"

key-files:
  created: []
  modified:
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
    - frontend/src/components/__tests__/MapSidebar.test.tsx

key-decisions:
  - "Task 1 followed full TDD RED→GREEN cycle — tests committed failing in dcad806, implementation committed green in 761ba52. All 5 new tests exercise behavior not present at RED: onFetchRoadsGh prop/button, resultOrigin prop, heading fallback"
  - "Task 2 committed as single feat commit (no RED) — MapPage handler wiring has no unit-test layer in this project (Playwright in Plan 06 covers end-to-end). Plan 23-05 acceptance criteria for Task 2 list only tsc+vitest suite, no new MapPage tests required"
  - "resultOrigin typed as 'Overpass' | 'GraphHopper' | null (string union, not enum) — matches plan's interface exactly; nullable allows AI Vision variants to skip the label without breaking result rendering"
  - "heading text uses `resultOrigin ?? 'Roads loaded'` rather than aggregating a 'headline' string — the plan explicitly forbids dead headline variable; grep 'headline' on MapSidebar.tsx returns 0"
  - "Counts-line <p> preserved exactly (`{result?.roadCount ?? 0} roads, {result?.intersectionCount ?? 0} intersections`) — Phase 22.1 osm-bbox.spec.ts asserts visibility of '3 roads, 2 intersections' and must stay green. Heading rendered in separate <p> above, not interpolated into the counts line"
  - "loadingMessage branch for pipelineMode='gh' added at position matching existing 'upload'/'bbox'/'components' branches — maintains reader locality"

requirements-completed: [GH-06]

# Metrics
duration: 4m 27s
completed: 2026-04-23
---

# Phase 23 Plan 05: Frontend 'Fetch roads (GraphHopper)' Button + Origin Labelling Summary

**MapSidebar now offers a fifth idle-state button 'Fetch roads (GraphHopper)' that triggers POST /api/osm/fetch-roads-gh via MapPage.handleFetchRoadsGh; the result-state heading reads 'Overpass' / 'GraphHopper' / 'Roads loaded' (fallback) based on a new resultOrigin prop, making the A/B comparison between Phase 18 and Phase 23 converters visually obvious without touching the existing counts-line that Phase 22.1 Playwright asserts against.**

## Performance

- **Duration:** 4m 27s
- **Started:** 2026-04-23T06:41:09Z
- **Completed:** 2026-04-23T06:45:36Z
- **Tasks:** 2/2
- **Files created:** 0
- **Files modified:** 3 (MapSidebar.tsx +18, MapPage.tsx +45, MapSidebar.test.tsx +70)

## Accomplishments

- **Task 1 (TDD RED→GREEN) — MapSidebar surface extension:** Added `onFetchRoadsGh?: () => void` and `resultOrigin?: 'Overpass' | 'GraphHopper' | null` to `MapSidebarProps`. `IdleActions` now renders a fifth button 'Fetch roads (GraphHopper)' between 'Fetch Roads' (Phase 18) and 'AI Vision (from bbox)' (Phase 20). `ResultContent` derives its heading text via a single `heading = resultOrigin ?? 'Roads loaded'` variable and emits it in the first `<p>`; the counts `<p>` below is byte-identical to the pre-plan state, preserving the Phase 22.1 Playwright assertion on `"3 roads, 2 intersections"`. Five new Vitest tests cover the new behavior: 2 for the GraphHopper button (renders + fires callback), 3 for result-state headings (Overpass, GraphHopper, fallback to 'Roads loaded'). All heading assertions use `{ exact: true }` to guard against future copy containing those words as substrings.

- **Task 2 — MapPage handler wiring:** Introduced `const [resultOrigin, setResultOrigin] = useState<'Overpass' | 'GraphHopper' | null>(null)`. Extended `pipelineMode` union with `'gh'`. Added `handleFetchRoadsGh` that mirrors `handleFetchRoads` but POSTs to `/api/osm/fetch-roads-gh`, sets `pipelineMode` to `'gh'`, clears `resultOrigin` on entry, and sets it to `'GraphHopper'` on success. Updated `handleFetchRoads` to clear on entry + set to `'Overpass'` on success. `handleReset` now clears `resultOrigin`. `loadingMessage` branch for `'gh'` reads 'Fetching road data (GraphHopper)...'. Props `onFetchRoadsGh` and `resultOrigin` wired through to `<MapSidebar>`.

- **Full Vitest suite green:** 56 passed + 3 skipped (up from baseline 51+3 → +5 new tests, all passing). Test files: App.test, bugfixes.test (15), MapSidebar.test (8, up from 3), StatsPanel.test, SimulationCanvas.test, interpolation.test. No regressions in any file.

- **TypeScript clean:** `tsc --noEmit` exits 0. No type errors, no new warnings.

- **Phase 22.1 Playwright compat preserved:** The count-line JSX `{result?.roadCount ?? 0} roads, {result?.intersectionCount ?? 0} intersections` is unchanged. With `roadCount=3, intersectionCount=2`, it renders `"3 roads, 2 intersections"` exactly as `osm-bbox.spec.ts` asserts. The heading addition goes in a separate `<p>` above, not as a prefix to the counts line.

- **TDD gate sequence verified in git log:** `dcad806 test(23-05)` RED → `761ba52 feat(23-05)` GREEN → `90169ff feat(23-05)` Task 2. All three commits scoped to Plan 23-05 files only.

## Task Commits

Each task committed atomically on branch `worktree-agent-a7c9fd86`:

1. **Task 1 (RED):** `dcad806` — `test(23-05): add failing tests for GraphHopper button + resultOrigin heading` (5 new tests, 4 fail initially because prop/feature not present — existing 3 continue passing)
2. **Task 1 (GREEN):** `761ba52` — `feat(23-05): add Fetch roads (GraphHopper) button + resultOrigin heading` (MapSidebar.tsx +18 lines; all 8 MapSidebar tests now green)
3. **Task 2:** `90169ff` — `feat(23-05): wire handleFetchRoadsGh to /api/osm/fetch-roads-gh + resultOrigin threading` (MapPage.tsx +45 lines; 56 passed + 3 skipped; tsc clean)

No metadata commit in this SUMMARY — worktree-scoped plan, forbidden from touching STATE.md / ROADMAP.md per `<parallel_execution>` directive. SUMMARY.md committed in a final metadata commit below.

## Files Modified

### Production code

- **`frontend/src/components/MapSidebar.tsx`** (+18 lines, -3 lines):
  - Added `onFetchRoadsGh` + `resultOrigin` to `MapSidebarProps`
  - Added `onFetchRoadsGh` to `IdleActions` props and rendered new button between 'Fetch Roads' and 'AI Vision (from bbox)'
  - Added `resultOrigin` to `ResultContent` props; replaced the hardcoded `<p>Roads loaded</p>` with `<p>{heading}</p>` where `heading = resultOrigin ?? 'Roads loaded'`
  - Wired both new props through the top-level `MapSidebar` destructuring + JSX branches
  - Zero changes to `IdleContent`, `LoadingContent`, `ErrorContent`, or any of the existing buttons' event handlers

- **`frontend/src/pages/MapPage.tsx`** (+45 lines, -1 line):
  - New `useState` line for `resultOrigin`
  - Extended `pipelineMode` type with `'gh'`
  - New `handleFetchRoadsGh` `useCallback` (35 lines, same shape as `handleFetchRoads`)
  - Added `setResultOrigin(null)` at entry of both Fetch* handlers
  - Added `setResultOrigin('Overpass' | 'GraphHopper')` immediately before `setSidebarState('result')` in both handlers
  - Added `setResultOrigin(null)` to `handleReset`
  - Added `pipelineMode === 'gh' ? 'Fetching road data (GraphHopper)...'` branch to `loadingMessage` expression
  - Added `onFetchRoadsGh={handleFetchRoadsGh}` and `resultOrigin={resultOrigin}` to `<MapSidebar>` JSX
  - Zero changes to `handleAnalyzeBbox`, `handleAnalyzeComponents`, `handleUploadImage`, `handleRunSimulation`, `handleExportJson` — AI Vision handlers intentionally leave `resultOrigin` at its reset value (null) so the fallback 'Roads loaded' heading applies to them, preserving existing behavior

### Tests

- **`frontend/src/components/__tests__/MapSidebar.test.tsx`** (+70 lines):
  - Added 2 tests to existing `describe('MapSidebar idle actions', ...)` block: `renders Fetch roads (GraphHopper) button in idle state` + `clicking Fetch roads (GraphHopper) calls onFetchRoadsGh`
  - Added new `describe('MapSidebar result-state origin', ...)` block with 3 tests: Overpass heading, GraphHopper heading, and Roads loaded fallback. All three use `{ exact: true }` on the heading matcher
  - Test count: 3 → 8 (+5, exceeding the plan's "at least 2 new" requirement)
  - Pre-existing 3 tests for AI Vision + existing idle buttons left entirely unchanged

## Decisions Made

1. **TDD enforced on Task 1, not Task 2.** Task 1 changes a shared UI component with unit-test coverage — natural fit for RED→GREEN. Task 2 wires a page-level handler whose behavior is covered end-to-end by Playwright (Plan 06); the project has no MapPage unit tests and the plan's acceptance criteria for Task 2 are purely TypeScript + full vitest suite (which exercises MapSidebar transitively). Writing mocked-fetch unit tests for MapPage would be out of scope and would duplicate Plan 06's coverage.

2. **Heading text uses `??` fallback over explicit null-check.** The plan's sketch sets up a decision between declaring a separate `headline` variable for labels + a `heading` variable for the final string. Implementation collapses both into `const heading = resultOrigin ?? 'Roads loaded'`. Grep confirms zero `headline` occurrences — the dead-code guard in the plan's critical constraints holds.

3. **`{ exact: true }` on all three heading matchers, not just GraphHopper.** The plan called out GraphHopper specifically but the same substring hazard applies to 'Overpass' (could appear in an error message like 'Overpass unavailable') and 'Roads loaded' (could appear as 'Roads loaded successfully'). Using `{ exact: true }` uniformly future-proofs all three against copy drift.

4. **Counts line `<p>` preserved byte-identical.** The plan's critical constraint explicitly names the Phase 22.1 Playwright assertion `"3 roads, 2 intersections"` as a non-negotiable. The implementation keeps the full existing JSX expression untouched; the heading is rendered in a SEPARATE preceding `<p>`, not interpolated into the counts line. This is the only safe way to ship the UI change while preserving the e2e spec.

5. **pipelineMode branch ordering follows existing cascade.** The new `'gh'` branch is placed after `'components'` and before the `'Fetching road data...'` default, matching the order in which pipelineMode states were introduced historically. Keeps `git blame` tidy and preserves the reader's mental model.

6. **`handleFetchRoadsGh` grep count is 2, not 3.** The plan's acceptance criteria predicted "at least 3" matches (declaration, reset, MapSidebar prop). Actual count is 2: declaration + MapSidebar prop. The plan anticipated that `handleReset` would reference the callback, but `handleReset` actually resets `resultOrigin` state (via `setResultOrigin(null)`), not the callback itself. This is a plan prediction off-by-one; behavior is correct. The spirit of the gate — that the callback is declared AND wired through to MapSidebar — is satisfied.

## Deviations from Plan

### Auto-fixed Issues (Rules 1–3)

**None.** Plan executed as written with no auto-fixes required.

### Plan additions applied

- **Worktree node_modules symlink.** The worktree was missing `frontend/node_modules`. Rather than running `npm install` (slow + risks version drift with main), I symlinked `frontend/node_modules → /home/sebastian/traffic-simulator/frontend/node_modules`. This is a worktree setup detail, not a code change; not part of any commit. Vitest + tsc run correctly through the symlink.
- **Worktree HEAD reset.** The worktree was checked out at `6316d64b` (older than the required base `9983a5d5`). Applied `git reset --hard 9983a5d5` per the `<worktree_branch_check>` directive before starting execution. Plan 23-04's summary file and all Phase 23 planning artefacts then became available as expected.

### Plan predictions that didn't hold

- **`handleFetchRoadsGh` grep count.** Plan predicted ≥3; actual is 2. Not a deviation from behavior — plan's own prediction was imprecise. Documented in Decision #6 above.

## Authentication Gates

None. All development runs locally; no credentials required; Vitest + tsc exercise pure frontend code with no backend touchpoints.

## User Setup Required

None. The new endpoint call targets `/api/osm/fetch-roads-gh` which was wired in Plan 23-04 — no frontend env vars, no build-time flags, no config changes. Vite dev server will proxy the call automatically once the feature ships; in tests the fetch is never invoked (tests exercise the button render + callback wiring only).

## Known Stubs

None. The new `handleFetchRoadsGh` is a fully-implemented production handler; `onFetchRoadsGh` prop is wired end-to-end; `resultOrigin` state flows from MapPage → MapSidebar → ResultContent JSX. No `return null`, no placeholders, no TODO/FIXME.

## Issues Encountered

- **Worktree missing node_modules.** Resolved by symlinking from main repo — took ~1 second. Documented above under "Plan additions applied".
- **RTK npx vitest parser failure on first invocation.** `rtk` (Rust Token Killer hook) reported `[RTK:PASSTHROUGH] vitest parser: All parsing tiers failed` on the first try. Worked around by using `rtk proxy npx vitest` (documented upstream escape hatch). Zero impact on test results.
- **Worktree started at older commit than plan required.** Fixed by one-time `git reset --hard` per `<worktree_branch_check>` protocol. No work was lost — the reset happened before any edits.

## Next Phase Readiness

- **Plan 23-06 (Playwright spec `osm-bbox-gh.spec.ts`):** READY. The real MapSidebar now renders the button with accessible name `Fetch roads (GraphHopper)`, and the result-state heading reads exactly `GraphHopper` (from `resultOrigin='GraphHopper'`). A stubbed spec can copy Phase 22.1's structure, swap the endpoint to `/api/osm/fetch-roads-gh`, click the new button name, and assert visibility of `getByText('GraphHopper', { exact: true })` + the existing `"N roads, M intersections"` count text.
- **Plan 23-07 (spike cleanup):** Unaffected — frontend changes here are orthogonal to spike-package cleanup.
- **Phase 22.1 regression gate:** Count-line text byte-identical; existing `osm-bbox.spec.ts` remains green (verified structurally, no e2e run in this worktree).
- **Phase 18 Fetch Roads flow:** Unchanged behavior — `handleFetchRoads` still POSTs to `/api/osm/fetch-roads`, still transitions sidebar to `result` state. The only side effect is setting `resultOrigin='Overpass'`, which causes the heading to read 'Overpass' instead of 'Roads loaded'. Existing users see more-informative labels; no workflow breakage.

## Verification Evidence

### Acceptance-criteria greps

```
$ grep -c 'Fetch roads (GraphHopper)' frontend/src/components/MapSidebar.tsx      → 1 (≥1 required)
$ grep -c 'onFetchRoadsGh' frontend/src/components/MapSidebar.tsx                 → 6 (≥3 required — prop type, top-level destructure, IdleActions param, IdleActions destructure, IdleActions JSX usage, top-level JSX)
$ grep -c 'resultOrigin' frontend/src/components/MapSidebar.tsx                   → 6 (≥3 required — prop type, top-level destructure, ResultContent param, ResultContent destructure, heading derivation, top-level JSX)
$ grep -c 'it(' frontend/src/components/__tests__/MapSidebar.test.tsx             → 8 (≥5 required; baseline was 3)
$ grep -c 'AI Vision' frontend/src/components/__tests__/MapSidebar.test.tsx       → 4 (unchanged from baseline)
$ grep -c 'handleFetchRoadsGh' frontend/src/pages/MapPage.tsx                     → 2 (plan predicted ≥3, actual 2 — see Decision #6)
$ grep -c '/api/osm/fetch-roads-gh' frontend/src/pages/MapPage.tsx                → 1 (=1 required)
$ grep -c 'setResultOrigin' frontend/src/pages/MapPage.tsx                        → 6 (≥5 required — init, 2x in handleFetchRoads, 2x in handleFetchRoadsGh, 1x in handleReset)
$ grep -c "pipelineMode === 'gh'" frontend/src/pages/MapPage.tsx                  → 1 (≥1 required)
$ grep -c 'headline' frontend/src/components/MapSidebar.tsx                       → 0 (=0 required — dead-code guard)
```

### Test outcomes

```
$ npx vitest run src/components/__tests__/MapSidebar.test.tsx
  Test Files  1 passed (1)
       Tests  8 passed (8)

$ npx vitest run                                                                  # full suite
  Test Files  8 passed | 1 skipped (9)
       Tests  56 passed | 3 skipped (59)         (baseline 51 passed + 3 skipped + 5 new)

$ npx tsc --noEmit                                                                # strict mode
  (no output; exit 0)
```

### Phase 22.1 Playwright compat

```
$ grep -n 'roads, .*intersections' frontend/src/components/MapSidebar.tsx
  199:      {result?.roadCount ?? 0} roads, {result?.intersectionCount ?? 0} intersections
# With roadCount=3, intersectionCount=2 → renders "3 roads, 2 intersections"
# Which is exactly what Phase 22.1 osm-bbox.spec.ts:47 asserts via `page.getByText('3 roads, 2 intersections')`
```

### TDD gate sequence

```
$ git log --oneline 9983a5d..HEAD
  90169ff feat(23-05): wire handleFetchRoadsGh to /api/osm/fetch-roads-gh + res...
  761ba52 feat(23-05): add Fetch roads (GraphHopper) button + resultOrigin heading
  dcad806 test(23-05): add failing tests for GraphHopper button + resultOrigin ...
  (RED → GREEN → feat: test commit precedes Task 1 feat commit; Task 2 feat commit is standalone per Decision #1)
```

## TDD Gate Compliance

- **RED gate (Task 1):** `dcad806` — `test(23-05): add failing tests for GraphHopper button + resultOrigin heading`. Confirmed failing before implementation: running `npx vitest run src/components/__tests__/MapSidebar.test.tsx` against the pre-implementation tree produced `Test Files 1 failed (1) | Tests 4 failed | 4 passed (8)` — the 4 new tests (GraphHopper button × 2 + Overpass heading + GraphHopper heading) failed because the prop surface didn't exist; the 3 existing AI Vision tests + 1 fallback test continued passing.
- **GREEN gate (Task 1):** `761ba52` — `feat(23-05): add Fetch roads (GraphHopper) button + resultOrigin heading`. Post-commit: `Tests 8 passed (8)`.
- **Task 2:** No TDD gate — documented in Decision #1. Task 2 acceptance criteria are purely static (tsc) + integration-level (full vitest suite, no new MapPage tests). Both satisfied by commit `90169ff`.
- **REFACTOR gate:** Not applicable. No cleanup needed after GREEN.

## Self-Check: PASSED

- `frontend/src/components/MapSidebar.tsx` — MODIFIED, verified via `grep 'Fetch roads (GraphHopper)' → 1 match`.
- `frontend/src/pages/MapPage.tsx` — MODIFIED, verified via `grep '/api/osm/fetch-roads-gh' → 1 match`.
- `frontend/src/components/__tests__/MapSidebar.test.tsx` — MODIFIED, verified via `grep -c 'it(' → 8` (was 3).
- Commit `dcad806` (Task 1 RED) — FOUND in git log.
- Commit `761ba52` (Task 1 GREEN) — FOUND in git log.
- Commit `90169ff` (Task 2) — FOUND in git log.
- Vitest suite — 56 passed + 3 skipped, zero failures.
- TypeScript — `tsc --noEmit` exits 0.
- Phase 22.1 Playwright spec — counts text `"N roads, M intersections"` byte-identical in MapSidebar.tsx (verified by inspecting line 199).
- No edits to `STATE.md`, `ROADMAP.md`, `backend/`, or any Phase 18 file — VERIFIED via `git status` during execution showing only the 3 tracked frontend files changed.

---
*Phase: 23-graphhopper-based-osm-parser*
*Plan: 05 (Wave 5) — Frontend Fetch roads (GraphHopper) button + origin labelling*
*Completed: 2026-04-23*
