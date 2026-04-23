---
phase: 23-graphhopper-based-osm-parser
plan: 06
subsystem: frontend-osm-e2e
tags: [frontend, playwright, e2e, osm, graphhopper, a-b-comparison]

# Dependency graph
requires:
  - phase: 23
    plan: 05
    provides: "MapSidebar 'Fetch roads (GraphHopper)' button + resultOrigin heading; MapPage handleFetchRoadsGh -> POST /api/osm/fetch-roads-gh"
  - phase: 22
    plan: 01
    provides: "osm-bbox.spec.ts + osm-fetch-roads-response.json fixture pattern (readFileSync via fileURLToPath)"
provides:
  - "Playwright e2e coverage for the GraphHopper OSM flow — stubbed endpoint, button click, result-state heading + counts assertions"
  - "Regression gate: coexistence of osm-bbox.spec.ts and osm-bbox-gh.spec.ts in same run (verified 2 passed together)"
affects:
  - "Phase 23 exit gate — Plan 23-07 (spike cleanup) is the only remaining plan; all user-visible GraphHopper wiring is now covered by an automated test"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Playwright page.route stub mirroring Phase 22.1 pattern — registered BEFORE page.goto, fulfilled with readFileSync+JSON.parse'd fixture, no new JSON file"
    - "{ exact: true } on getByText('GraphHopper') — disambiguates result heading <p>GraphHopper</p> from the button label 'Fetch roads (GraphHopper)' present on the page before the click"
    - "Anchored regex /^Fetch roads \\(GraphHopper\\)$/ on getByRole button selector — prevents accidental matches if future copy contains the phrase as a substring"
    - "No afterEach cleanup — spec never dispatches STOMP START; backend simulation state stays STOPPED just like osm-bbox.spec.ts"

key-files:
  created:
    - frontend/e2e/osm-bbox-gh.spec.ts
  modified: []

key-decisions:
  - "Reused existing fixture osm-fetch-roads-response.json — the plan explicitly forbade creating a new JSON file, and the canned MapConfig (3 roads, 2 intersections) is origin-agnostic (same shape regardless of whether Overpass or GraphHopper produced it)"
  - "Single commit, commit type `test(...)` — Task 1 is a test-only change with no production code to split into RED/GREEN. Per TDD fail-fast rule, running the spec against the already-landed Plan 23-05 implementation passed immediately; this is not a TDD cycle but an e2e coverage add. Commit type reflects the change nature"
  - "Asserted result heading via { exact: true } on 'GraphHopper' — the button 'Fetch roads (GraphHopper)' remains mounted on the page after click only briefly (state transitions to 'result' which hides IdleActions), but defending against accidental ambiguity is free and matches Plan 23-05's heading assertion style in MapSidebar.test.tsx"
  - "Did NOT click Run Simulation — per plan scope and matching Phase 22.1 osm-bbox.spec.ts. Covering simulation start via OSM flow would require a real STOMP connection and dirty the backend state; that path is already covered by vision-components.spec.ts"
  - "Did NOT stub /api/osm/fetch-roads (Phase 18 endpoint) in this spec — keeps regression guard from osm-bbox.spec.ts intact (the other spec owns its own stub); ensures the two specs don't fight over route handlers when run in the same browser context"

requirements-completed: [GH-06]

# Metrics
duration: 2m 47s
completed: 2026-04-23
---

# Phase 23 Plan 06: Playwright spec `osm-bbox-gh.spec.ts` Summary

**Added a single Playwright smoke test `frontend/e2e/osm-bbox-gh.spec.ts` that stubs POST `/api/osm/fetch-roads-gh` with the existing Phase 22.1 canned MapConfig fixture, clicks the new 'Fetch roads (GraphHopper)' button, and asserts the sidebar transitions to the result state with the `GraphHopper` origin heading + `3 roads, 2 intersections` counts line — taking the Playwright suite from 6 tests to 7 with all green, including the Phase 22.1 `osm-bbox.spec.ts` regression gate that must stay unchanged.**

## Performance

- **Duration:** 2m 47s
- **Started:** 2026-04-23T06:49:38Z
- **Completed:** 2026-04-23T06:52:25Z
- **Tasks:** 1/1
- **Files created:** 1 (`frontend/e2e/osm-bbox-gh.spec.ts`, 65 lines)
- **Files modified:** 0

## Accomplishments

- **Task 1 — Author `osm-bbox-gh.spec.ts`:** Created a 65-line Playwright spec that structurally mirrors Phase 22.1's `osm-bbox.spec.ts`. The spec:
  1. Loads the canned MapConfig fixture via `readFileSync(fileURLToPath(new URL('./fixtures/osm-fetch-roads-response.json', import.meta.url)))` — the same pattern Phase 22.1 uses, avoiding the deprecated `assert { type: 'json' }` ESM attribute syntax that Playwright's TS loader rejects.
  2. Registers a `page.route('**/api/osm/fetch-roads-gh', ...)` handler BEFORE `page.goto('/map')` — fulfilling with status 200 + the canned body, and asserting the request method is POST and the body carries `south/west/north/east` keys.
  3. Waits for the Leaflet bbox to materialise (sidebar meters text visible) before clicking.
  4. Clicks `getByRole('button', { name: /^Fetch roads \(GraphHopper\)$/ })` — anchored regex guarantees exact match on the fifth idle-state button landed in Plan 23-05.
  5. Asserts three things in sequence on the result state: `getByText('GraphHopper', { exact: true })` (the new origin heading), `getByText('3 roads, 2 intersections')` (the byte-identical counts line from Phase 22.1), and visibility of the Run Simulation / Export JSON / New Selection buttons.
  6. Does NOT click Run Simulation — no STOMP side effects, no backend state pollution, no `afterEach` cleanup needed; matches Phase 22.1 discipline.

- **Full Playwright suite green:** 7 tests pass in 27.9 s (baseline was 6 tests). Isolation run of new spec alone: 1 passed in 30.5 s. Coexistence run (`osm-bbox.spec.ts` + `osm-bbox-gh.spec.ts` together): 2 passed in 15.4 s — confirms the two route stubs don't interfere.

- **Phase 22.1 regression gate green:** `osm-bbox.spec.ts` continues to pass inside the full suite run — the Phase 23-05 counts-line `<p>{roadCount} roads, {intersectionCount} intersections</p>` JSX is byte-identical to pre-Plan-05 state, and the new GraphHopper spec stubs a different endpoint so there's zero interference.

- **TDD gate assessment:** Plan Task 1 is marked `tdd="true"` but is test-only (no production code ships in Plan 06 — all production wiring landed in Plan 23-05). Running the new spec against the already-green Plan 23-05 implementation passed on the first attempt. Per the `<tdd_execution>` section's fail-fast rule, this would normally trigger an investigation — but here it's expected and correct: the feature shipped last wave, and this plan's explicit purpose is to add end-to-end coverage. Committed as a single `test(23-06)` commit, matching the change's nature.

## Task Commits

Committed atomically on branch `worktree-agent-ae189850`:

1. **Task 1:** `0ac26a7` — `test(23-06): add Playwright spec osm-bbox-gh.spec.ts for GraphHopper flow` (+65 lines, 1 new file)

No metadata commit in this SUMMARY — this worktree-scoped plan is forbidden from touching STATE.md / ROADMAP.md per `<parallel_execution>` directive. SUMMARY.md will be committed in a separate metadata commit below.

## Files Modified

### Tests (created)

- **`frontend/e2e/osm-bbox-gh.spec.ts`** (new file, 65 lines):
  - Top-of-file doc comment describes purpose, mirror relationship with `osm-bbox.spec.ts`, and the "no Run Simulation, no afterEach" discipline
  - Fixture loader: `const fixturePath = fileURLToPath(new URL('./fixtures/osm-fetch-roads-response.json', import.meta.url))` + `JSON.parse(readFileSync(fixturePath, 'utf-8'))`
  - `test.describe('OSM bbox fetch flow — GraphHopper', ...)` wrapping a single `test('stubbed fetch: click Fetch roads (GraphHopper) -> sidebar shows GraphHopper heading + 3 roads, 2 intersections', ...)` block
  - Route stub validates POST method + bbox body shape (`south/west/north/east` keys), fulfils with the canned map config
  - Anchored regex for the button click + `{ exact: true }` for the heading assertion

### Production code

None. Plan scope forbids backend / frontend src edits.

## Decisions Made

1. **No new fixture file.** The plan's must-have `key_links` explicitly point at `osm-fetch-roads-response.json`; reusing it is cheaper (no duplication, no drift risk) and the canned MapConfig is origin-agnostic. If future assertions need different counts, the shared fixture can evolve in one place.

2. **Single `test(...)` commit, not RED→GREEN.** Plan Task 1 has `tdd="true"` but no production code is introduced — the feature landed in Plan 23-05. Writing a failing test first would require deleting the Plan 23-05 code, which contradicts the parallel-execution directive and the plan's own "no backend or frontend src edits" constraint. The plan's `<action>` block is the spec content verbatim; there's nothing to RED/GREEN split.

3. **`{ exact: true }` on heading matcher.** Three rationales: (a) matches Phase 23-05 MapSidebar.test.tsx style where all result-heading assertions use this flag; (b) defends against future button or info copy containing 'GraphHopper' as a substring (e.g. 'GraphHopper warnings' or 'Switched to GraphHopper'); (c) avoids any lurking race where the `Fetch roads (GraphHopper)` button text is still in the DOM during the state transition.

4. **Anchored regex `/^Fetch roads \(GraphHopper\)$/`.** Same defensive style as Phase 22.1's `/^Fetch Roads$/`. Future copy drift (e.g. adding a hint like 'Fetch roads (GraphHopper) — beta') would fail fast here rather than silently match.

5. **Did not stub Phase 18 `/api/osm/fetch-roads`.** The only button clicked in this spec is the GraphHopper one; the Phase 18 button is not exercised. Stubbing it anyway would be inert noise and would collide with `osm-bbox.spec.ts`'s own stub if the two specs were ever run with Playwright route-sharing (they aren't by default — each `test()` gets its own page — but keeping stubs minimal is good hygiene).

6. **Did not click Run Simulation.** Plan scope explicitly excludes this path; matching Phase 22.1 `osm-bbox.spec.ts`. The `vision-components.spec.ts` already covers Run Simulation via a different entry point. Clicking it here would dispatch STOMP START, dirty the backend simulation state, and require `afterEach` cleanup — out of scope and contrary to the plan's "smoke test" framing.

## Deviations from Plan

### Auto-fixed Issues (Rules 1–3)

**None.** Plan executed as written with no auto-fixes required.

### Plan additions applied

- **Worktree node_modules symlink.** The worktree was missing `frontend/node_modules`. Same resolution as Plan 23-05: symlinked `frontend/node_modules → /home/sebastian/traffic-simulator/frontend/node_modules`. Not part of any commit (untracked via `.gitignore`). Playwright runs correctly through the symlink.
- **Worktree HEAD reset.** The worktree was at commit `6316d64` (older than the required base `73b7455`). Applied `git reset --hard 73b7455f774c58fac1c963420b7ab71a52ca3eec` per the `<worktree_branch_check>` directive before starting any edits. No work was lost.

### Plan predictions that didn't hold

None. Plan's predicted file shape, assertion surface, and test count (1 new test → 7 total) matched exactly.

## Authentication Gates

None. The test runs entirely locally: Playwright auto-starts the Spring Boot backend on :8086 and Vite dev server on :5173 via the webServer config; the GraphHopper endpoint is stubbed, so no external API credentials are required at test time.

## User Setup Required

None. Running `cd frontend && npx playwright test` from the worktree will auto-start both servers (or reuse them if already up) and exercise all 7 specs.

## Known Stubs

None. The spec is a complete, passing end-to-end test; no TODO/FIXME markers; no hardcoded placeholder values that flow to production UI.

## Issues Encountered

- **Worktree missing node_modules.** Resolved by symlinking from main repo (~1 s). Documented above under "Plan additions applied".
- **Worktree at older base than required.** Resolved by `git reset --hard` per protocol.
- **RTK parser passthrough on playwright commands.** `rtk` (Rust Token Killer) reported `playwright parser: All parsing tiers failed` on bare `npx playwright test`; worked around with `rtk proxy npx playwright test` (documented upstream escape hatch). Zero impact on results. Same workaround Plan 23-05 used for vitest.

## Next Phase Readiness

- **Plan 23-07 (spike-package cleanup):** READY. This plan's artefact (the spec file) is independent of the spike package — removing spike code won't affect the spec because the spec stubs the endpoint at the HTTP boundary.
- **Phase 23 exit gate:** After Plan 23-07 lands, Phase 23 has: backend endpoint (Plan 04), frontend button + origin labelling (Plan 05), Playwright coverage (Plan 06, this plan). A/B comparison between Overpass and GraphHopper pipelines is fully usable in the dev environment.
- **Phase 22.1 regression gate:** Confirmed green by running the full suite (7 tests, all passing). `osm-bbox.spec.ts` counts-text assertion `"3 roads, 2 intersections"` continues to match — MapSidebar's counts-line JSX was preserved byte-identical in Plan 23-05.

## Verification Evidence

### Acceptance-criteria greps

```
$ grep -c "test(" frontend/e2e/osm-bbox-gh.spec.ts                                 → 1 (≥1 required)
$ grep "page.route.*/api/osm/fetch-roads-gh" frontend/e2e/osm-bbox-gh.spec.ts      → 1 match (≥1 required)
$ grep "Fetch roads (GraphHopper)" frontend/e2e/osm-bbox-gh.spec.ts                → 2 matches (doc comment + click comment; ≥1 required)
$ grep "3 roads, 2 intersections" frontend/e2e/osm-bbox-gh.spec.ts                 → 1 match (≥1 required)
$ grep "osm-fetch-roads-response.json" frontend/e2e/osm-bbox-gh.spec.ts            → 1 match (≥1 required — fixture reuse)
```

### Playwright outcomes

```
$ cd frontend && rtk proxy npx playwright test --list e2e/osm-bbox-gh.spec.ts
  [chromium] › osm-bbox-gh.spec.ts:24:3 › OSM bbox fetch flow — GraphHopper › stubbed fetch: ...
  Total: 1 test in 1 file

$ cd frontend && rtk proxy npx playwright test e2e/osm-bbox-gh.spec.ts --reporter=line
  1 passed (30.5s)

$ cd frontend && rtk proxy npx playwright test --reporter=line                          # full suite
  7 passed (27.9s)                                                                      # baseline 6 + 1 new

$ cd frontend && rtk proxy npx playwright test e2e/osm-bbox.spec.ts e2e/osm-bbox-gh.spec.ts --reporter=line
  2 passed (15.4s)                                                                      # coexistence verified
```

### Baseline vs new total

- **Before Plan 23-06:** 6 tests across 5 spec files (controls, osm-bbox, responsive, simulation, vision-components)
- **After Plan 23-06:** 7 tests across 6 spec files (+ osm-bbox-gh)
- **Delta:** +1 spec file, +1 test

### Commit history (scoped to this plan)

```
$ git log --oneline 73b7455..HEAD
  0ac26a7 test(23-06): add Playwright spec osm-bbox-gh.spec.ts for GraphHopper flow
  (single task; no production code changes; SUMMARY commit appended after this log capture)
```

## TDD Gate Compliance

- **RED gate:** Not applicable — Task 1 is a test-only change against pre-existing production code (Plan 23-05). Writing a traditional RED commit would require deleting Plan 23-05's implementation, which contradicts the parallel-execution directive's "no frontend src edits".
- **GREEN gate:** Satisfied implicitly by the `test(23-06)` commit — the spec passes the first time it runs because the production wiring already exists.
- **REFACTOR gate:** Not applicable — no cleanup needed after GREEN.
- **Plan-level assessment:** The plan's `type: execute` (not `type: tdd`) frontmatter is consistent with this interpretation. The `tdd="true"` task attribute on Task 1 signals "this task belongs in the test layer" rather than mandating a RED commit. Documenting here for verifier transparency.

## Self-Check: PASSED

- `frontend/e2e/osm-bbox-gh.spec.ts` — CREATED, verified via `git log --stat` showing `1 file changed, 65 insertions(+)` in commit `0ac26a7`.
- Commit `0ac26a7` — FOUND in `git log --oneline 73b7455..HEAD`.
- Playwright suite — 7 tests pass, zero failures, zero flaky re-runs.
- Phase 22.1 `osm-bbox.spec.ts` — green inside the full suite run (regression gate intact).
- No edits to `STATE.md`, `ROADMAP.md`, `backend/`, or `frontend/src/` — VERIFIED via `git status` during execution showing only the 1 new spec file tracked as a change.

---
*Phase: 23-graphhopper-based-osm-parser*
*Plan: 06 (Wave 6) — Playwright spec osm-bbox-gh.spec.ts for GraphHopper flow*
*Completed: 2026-04-23*
