---
phase: 17-routing-map-embed
plan: 01
subsystem: ui
tags: [react-router-dom, leaflet, react-leaflet, routing, navigation]

# Dependency graph
requires:
  - phase: 14-responsive-layout
    provides: useIsMobile hook pattern and responsive simulation layout
provides:
  - react-router-dom BrowserRouter wrapping App with / and /map routes
  - SimulationPage component with full simulation UI extracted from App
  - useIsMobile hook extracted to its own file
  - NavHeader with active-link highlighting via useLocation
  - Leaflet + react-leaflet installed for Plan 02 map integration
affects: [17-02, 18-osm-pipeline, 19-simulation-integration]

# Tech tracking
tech-stack:
  added: [react-router-dom@6.30.3, leaflet@1.9.4, react-leaflet@4.2.1, @types/leaflet@1.9.21]
  patterns: [page-component-extraction, root-level-hook-for-persistent-connection]

key-files:
  created:
    - frontend/src/hooks/useIsMobile.ts
    - frontend/src/pages/SimulationPage.tsx
  modified:
    - frontend/src/App.tsx
    - frontend/package.json

key-decisions:
  - "useWebSocket() called in App root (above Routes) so WS stays mounted on /map navigation"
  - "DisconnectBanner and PausedOverlay moved to SimulationPage (simulation-specific UI)"
  - "NavHeader uses useLocation().pathname to highlight active link"
  - "@types/leaflet placed in devDependencies (type declarations only)"

patterns-established:
  - "Page components live in frontend/src/pages/ — one file per route"
  - "Hooks shared across pages live in frontend/src/hooks/"
  - "Persistent connection hooks (WS) stay at App root level above Routes"

requirements-completed: [MAP-04]

# Metrics
duration: 2min
completed: 2026-04-12
---

# Phase 17 Plan 01: Routing & Map Embed Summary

**react-router-dom v6 routing with BrowserRouter, SimulationPage extraction, NavHeader with active-link highlighting, and Leaflet dependencies installed**

## Performance

- **Duration:** 2 min
- **Started:** 2026-04-12T14:12:59Z
- **Completed:** 2026-04-12T14:14:59Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Installed react-router-dom@6.30.3, leaflet@1.9.4, react-leaflet@4.2.1 as runtime deps; @types/leaflet@1.9.21 as devDep
- Extracted simulation layout (canvas + sidebar + DisconnectBanner + PausedOverlay) into `SimulationPage` page component
- Rewrote App.tsx with BrowserRouter + Routes: `/` -> SimulationPage, `/map` -> placeholder
- NavHeader with `Link` components and `useLocation` for active-link highlighting (#4af active, #888 inactive)
- `useIsMobile` extracted from App.tsx into `frontend/src/hooks/useIsMobile.ts`

## Task Commits

Each task was committed atomically:

1. **Task 1: Install dependencies and extract useIsMobile hook** - `4e8c72c` (chore)
2. **Task 2: Wrap App in BrowserRouter, extract SimulationPage, add nav header** - `5add07f` (feat)

**Plan metadata:** (docs commit — see below)

## Files Created/Modified
- `frontend/src/hooks/useIsMobile.ts` - Mobile breakpoint hook (768px), extracted from App.tsx
- `frontend/src/pages/SimulationPage.tsx` - Full simulation UI: canvas area, PausedOverlay, DisconnectBanner, sidebar with ControlsPanel + StatsPanel
- `frontend/src/App.tsx` - Rewritten with BrowserRouter, Routes, NavHeader; useWebSocket() at root
- `frontend/package.json` - Added react-router-dom, leaflet, react-leaflet, @types/leaflet

## Decisions Made
- `useWebSocket()` must remain at App root level (above Routes) so the STOMP connection is not torn down when navigating between `/` and `/map`. Per CONTEXT.md decision.
- `@types/leaflet` moved to devDependencies (it is a type declaration package, not needed at runtime).

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

Pre-existing test failures in `src/rendering/__tests__/interpolation.test.ts` (4 tests) and `src/test/e2e-websocket.test.ts` (3 tests, require running backend) were present before this plan. No regression introduced.

## Known Stubs

- `/map` route renders `MapPagePlaceholder` (inline component in App.tsx): `"Map page — coming in Plan 02"`. This is intentional — Plan 02 (17-02) will replace it with the real MapPage + Leaflet integration.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Routing infrastructure complete. Plan 02 can import `react-leaflet` and create `MapPage` component at `/map`.
- `leaflet/dist/leaflet.css` import needed in MapPage (per CONTEXT.md note).
- Zustand store extension for map position/zoom state is Plan 02 scope.

## Self-Check: PASSED

- FOUND: frontend/src/hooks/useIsMobile.ts
- FOUND: frontend/src/pages/SimulationPage.tsx
- FOUND: frontend/src/App.tsx (updated)
- FOUND: .planning/phases/17-routing-map-embed/17-01-SUMMARY.md
- FOUND: commit 4e8c72c (chore: install deps, extract useIsMobile)
- FOUND: commit 5add07f (feat: BrowserRouter, SimulationPage, NavHeader)

---
*Phase: 17-routing-map-embed*
*Completed: 2026-04-12*
