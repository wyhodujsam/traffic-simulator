---
phase: 18-osm-data-pipeline
plan: 02
subsystem: osm-pipeline
tags: [rest-api, osm, frontend-wiring, error-handling, tdd]
dependency_graph:
  requires: [18-01]
  provides: [POST /api/osm/fetch-roads, frontend-fetch-roads-flow]
  affects: [MapPage, MapSidebar, OsmController]
tech_stack:
  added: []
  patterns: [WebMvcTest with MockBean, fetch API with error handling, React state machine]
key_files:
  created:
    - backend/src/main/java/com/trafficsimulator/controller/OsmController.java
    - backend/src/test/java/com/trafficsimulator/controller/OsmControllerTest.java
  modified:
    - backend/src/main/resources/application.properties
    - frontend/src/pages/MapPage.tsx
    - frontend/src/components/MapSidebar.tsx
decisions:
  - "catch(Exception e) in addition to RestClientException to handle any unexpected Overpass API errors with 503"
  - "void mapConfig suppresses unused-var in MapPage — Phase 19 will consume it"
  - "sidebarState 'error' added as 4th state to MapSidebarProps (plan had 'idle|loading|result', error is required for correct operation)"
metrics:
  duration: "~15 minutes"
  completed_date: "2026-04-12"
  tasks_completed: 2
  files_changed: 5
---

# Phase 18 Plan 02: OSM Pipeline REST Endpoint & Frontend Wiring Summary

**One-liner:** REST endpoint POST /api/osm/fetch-roads delegates to OsmPipelineService with 422/503 error codes, frontend MapPage calls it with loading/result/error state machine.

## What Was Built

### Task 1: OsmController REST endpoint (TDD)

`OsmController` exposes `POST /api/osm/fetch-roads` accepting a `BboxRequest` JSON body. It delegates to `OsmPipelineService.fetchAndConvert()` and handles three outcomes:

- **200**: Returns `MapConfig` JSON from the Overpass pipeline
- **422**: `IllegalStateException` (no roads found) → `{"error": "No roads found in selected area"}`
- **503**: `RestClientException` or any other exception → `{"error": "Overpass API unavailable. Please try again later."}`

`OsmControllerTest` (3 tests) verifies all three paths using `@WebMvcTest` + `@MockBean`.

### Task 2: Frontend MapPage wiring

`MapPage.tsx` now has a full state machine (`idle | loading | result | error`) with:
- `handleFetchRoads`: async function calling `POST /api/osm/fetch-roads`, parsing success/error
- `handleReset`: resets to idle state
- `mapConfig`: stores full `MapConfig` for Phase 19 use

`MapSidebar.tsx` extended with:
- New props: `result`, `error`, `onReset`
- `ErrorContent`: shows red error message + "Try Again" button
- `ResultContent`: shows road count, intersection count + "New Selection" button (Run Simulation / Export JSON disabled, labelled "Coming in Phase 19")

The Vite proxy for `/api` was already configured in `vite.config.ts` (no change needed).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] Added catch(Exception e) alongside RestClientException**
- **Found during:** Task 1 implementation
- **Issue:** Plan specified only `RestClientException` catch, but Overpass API parsing errors throw `IllegalStateException` already caught by the first handler; other unexpected errors (timeout, JSON parse, network) would surface as 500 from Spring default handler
- **Fix:** Added outer `catch (Exception e)` after `RestClientException` to guarantee 503 for all non-business errors
- **Files modified:** `OsmController.java`
- **Commit:** d7e3f87

**2. [Rule 2 - Missing functionality] Added 'error' as 4th sidebar state**
- **Found during:** Task 2 implementation
- **Issue:** Plan interface spec showed `'idle' | 'loading' | 'result'` but the task action clearly needed an `'error'` state for the ErrorContent component
- **Fix:** Extended `MapSidebarProps.state` to `'idle' | 'loading' | 'result' | 'error'` — consistent with the plan's action spec
- **Files modified:** `MapSidebar.tsx`, `MapPage.tsx`
- **Commit:** 51726b0

## Commits

| Hash | Description |
|------|-------------|
| d7e3f87 | feat(18-02): OsmController POST /api/osm/fetch-roads with error handling |
| 51726b0 | feat(18-02): wire frontend MapPage to POST /api/osm/fetch-roads |

## Verification Results

- `mvn test`: 219/219 tests pass (including 3 new OsmControllerTest)
- `tsc --noEmit`: clean compilation, no errors

## Known Stubs

- `Run Simulation` button in ResultContent: disabled with `title="Coming in Phase 19"` — stub intentional, Phase 19 will wire mapConfig to simulation dispatch
- `Export JSON` button in ResultContent: disabled with `title="Coming in Phase 19"` — stub intentional, Phase 19 will implement export
- `mapConfig` state in MapPage: stored but `void`-suppressed — Phase 19 will pass it to simulation

These stubs do NOT prevent the plan's goal (end-to-end fetch flow from map click to road data display). The fetch, loading indicator, and result/error display all work.

## Self-Check: PASSED
