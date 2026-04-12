---
phase: 19
plan: "02"
subsystem: simulation-integration
tags: [backend, frontend, osm, load-config, routing]
dependency_graph:
  requires: [19-01]
  provides: [run-simulation-flow]
  affects: [SimulationController, MapSidebar, MapPage]
tech_stack:
  added: []
  patterns:
    - Dedicated REST endpoint for complex body (MapConfig) vs generic CommandDto
    - useNavigate for post-action navigation in React Router v6
    - useSimulationStore.getState() for accessing Zustand store outside React render
key_files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/controller/SimulationController.java
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
key_decisions:
  - Dedicated /api/command/load-config endpoint instead of overloading CommandDto — MapConfig body is too complex for the generic DTO pattern
  - POST load-config then STOMP START in sequence — ensures network is loaded before simulation begins
  - simulationLoading state disables Run Simulation button during in-flight request to prevent double-submit
metrics:
  duration_minutes: 8
  completed_date: "2026-04-12"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 3
---

# Phase 19 Plan 02: Run Simulation Wiring Summary

**One-liner:** OSM-to-simulation pipeline completed — POST MapConfig endpoint + Run Simulation button navigates from /map to / with loaded road network.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Backend POST /api/command/load-config endpoint | 3bb2d32 | SimulationController.java |
| 2 | Wire Run Simulation button with backend call and navigation | e5ceadd | MapSidebar.tsx, MapPage.tsx |

## What Was Built

### Task 1 — Backend POST /api/command/load-config

Added a dedicated endpoint to `SimulationController` that:
- Accepts a full `MapConfig` JSON body via `@RequestBody`
- Enqueues `SimulationCommand.LoadConfig(config)` into the simulation engine
- Returns `{ "status": "ok" }` on success
- Delegates to the existing `CommandDispatcher.handleLoadConfig()` which stops the engine, clears vehicles, loads the road network via `MapLoader.loadFromConfig()`, and sets spawn rate

The endpoint is separate from the generic `POST /api/command` because `MapConfig` is too complex to encode in `CommandDto` — it has nested nodes, roads, intersections, spawn points, etc.

### Task 2 — Frontend Run Simulation Button

**MapSidebar.tsx:**
- Added `onRunSimulation?: () => void` and `simulationLoading?: boolean` to `MapSidebarProps`
- `ResultContent` now accepts and passes these props to the button
- Run Simulation button: removed `disabled` + `title="Coming in Phase 19"`, wired `onClick={onRunSimulation}`
- Shows "Loading..." text and disables button when `simulationLoading` is true

**MapPage.tsx:**
- Imported `useNavigate` from `react-router-dom`
- Imported `useSimulationStore` for sending STOMP commands
- Added `simulationLoading` state
- `handleRunSimulation` callback: POST mapConfig to `/api/command/load-config`, then sends `{ type: 'START' }` via STOMP, then calls `navigate('/')` — full OSM-to-simulation pipeline
- Error handling mirrors `handleFetchRoads` pattern: sets error message and switches to 'error' state
- Passes `onRunSimulation` and `simulationLoading` to `MapSidebar`

## User Flow

1. User navigates to /map
2. Browses map, draws bounding box
3. Clicks "Fetch Roads" — backend calls Overpass, returns MapConfig
4. Road graph overlays on Leaflet map
5. **Clicks "Run Simulation"** — button shows "Loading..."
6. Frontend POSTs full MapConfig to `/api/command/load-config`
7. Backend enqueues LoadConfig — stops any running simulation, loads OSM road network
8. Frontend sends START command via STOMP
9. Frontend navigates to `/` — simulation page shows vehicles moving on OSM roads

## Deviations from Plan

None — plan executed exactly as written.

## Self-Check

### Files exist
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java` - modified
- `frontend/src/components/MapSidebar.tsx` - modified
- `frontend/src/pages/MapPage.tsx` - modified

### Commits exist
- `3bb2d32` - feat(19-02): add POST /api/command/load-config endpoint
- `e5ceadd` - feat(19-02): wire Run Simulation button with load-config call and navigation

### Verification
- Backend: `mvn compile -q` passed
- Frontend: `npx tsc --noEmit` passed

## Known Stubs

None — all functionality is fully wired end-to-end.
