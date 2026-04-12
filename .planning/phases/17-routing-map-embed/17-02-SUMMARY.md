---
phase: 17-routing-map-embed
plan: "02"
subsystem: frontend-map
tags: [leaflet, react-leaflet, bounding-box, osm, map-page, dark-theme]
dependency_graph:
  requires: ["17-01"]
  provides: ["18-01"]
  affects: ["frontend/src/App.tsx", "frontend/src/pages/MapPage.tsx", "frontend/src/components/BoundingBoxMap.tsx", "frontend/src/components/MapSidebar.tsx"]
tech_stack:
  added: []
  patterns: ["useMap() hook for imperative Leaflet access", "L.rectangle for bbox overlay", "useRef for view state persistence", "inner component pattern for react-leaflet hooks"]
key_files:
  created:
    - frontend/src/components/BoundingBoxMap.tsx
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
  modified:
    - frontend/src/App.tsx
decisions:
  - "Bbox auto-sized to 60% of visible map area (20% inset per edge) — updates on pan/zoom, no drag handles in Phase 17"
  - "BboxInfo exported from BoundingBoxMap.tsx so MapSidebar can import the interface without circular deps"
  - "mapViewRef uses useRef (not useState) to persist center/zoom without triggering re-render"
  - "MapSidebar receives sidebar 'state' prop for future idle/loading/result switching by Phase 18"
  - "Pre-existing interpolation test failures (7 tests) confirmed as pre-existing — not caused by this plan"
metrics:
  duration: "5 minutes"
  completed_date: "2026-04-12"
  tasks_completed: 2
  tasks_total: 2
  files_created: 3
  files_modified: 1
---

# Phase 17 Plan 02: MapPage with Leaflet Map and Bbox Summary

Leaflet/OSM map page at /map with a blue bounding box auto-sized to visible area and a sidebar showing bbox dimensions in meters and placeholder action buttons.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | BoundingBoxMap component | d04900c | frontend/src/components/BoundingBoxMap.tsx |
| 2 | MapSidebar + MapPage + App.tsx wiring | c74a89f | frontend/src/components/MapSidebar.tsx, frontend/src/pages/MapPage.tsx, frontend/src/App.tsx |

## What Was Built

**BoundingBoxMap** (`frontend/src/components/BoundingBoxMap.tsx`):
- `MapContainer` with OSM TileLayer (openstreetmap.org)
- `BoundingBoxLayer` inner component using `useMap()` — attaches to `moveend`/`zoomend` events
- `L.rectangle` with 20% inset from each edge of the visible map bounds (blue `#0088ff`, fillOpacity 0.1)
- `onBoundsChange` callback delivers `BboxInfo` (south, west, north, east, widthMeters, heightMeters)
- `onViewChange` callback for persisting center/zoom
- Default center: Warsaw `[52.2297, 21.0122]`, zoom 14
- Exports `BboxInfo` interface for downstream consumers

**MapSidebar** (`frontend/src/components/MapSidebar.tsx`):
- Three state variants: `idle` (functional), `loading` (placeholder), `result` (placeholder)
- Idle state: bbox dimensions in meters (`{w}m x {h}m`), N/S/W/E coordinates to 4 decimal places
- "Fetch Roads" button (active), "Upload Image" button (disabled + opacity 0.5 with Phase 20 tooltip)
- Dark theme: `#0d0d1a` background, `#1a1a2e` button bg, `#e0e0e0` text, monospace font

**MapPage** (`frontend/src/pages/MapPage.tsx`):
- Responsive flex layout: desktop = row (map flex:1, sidebar 260px), mobile = column (stacked)
- Map area `flex:1` with no padding — map fills available space
- `mapViewRef` (useRef) persists center/zoom without triggering re-render loop
- Passes saved center/zoom to `BoundingBoxMap` as props for position memory

**App.tsx update**:
- Removed `MapPagePlaceholder` function
- Added `import { MapPage } from './pages/MapPage'`
- Route `/map` now renders `<MapPage />`

## Verification Results

- `tsc --noEmit`: PASSES (zero TypeScript errors)
- `npm test -- --run`: 44 passing / 7 pre-existing failing (interpolation tests — unrelated to this plan, confirmed pre-existing before this plan's changes)
- Leaflet CSS imported in `BoundingBoxMap.tsx` (satisfies leaflet styling requirement)
- `MapContainer` has `style={{ height: '100%', width: '100%' }}` (avoids zero-height pitfall)
- `useMap()` hook used inside child component of `MapContainer` (correct react-leaflet v4 pattern)

## Requirements Satisfied

| Req ID | Description | Status |
|--------|-------------|--------|
| MAP-01 | Interactive OSM map on /map page | DONE |
| MAP-02 | Pan and zoom to any area | DONE (Leaflet default) |
| MAP-03 | Bounding box visible and updates on pan/zoom | DONE |

## Deviations from Plan

None — plan executed exactly as written. The `MapSidebar` responsive behavior on mobile was handled by the parent `MapPage` applying `borderTop` and removing `borderLeft` in the mobile wrapper div (rather than inside the sidebar itself), which is a minor layout implementation detail consistent with the plan's intent.

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| "Fetch Roads" button — onClick not wired | MapSidebar.tsx | Phase 18 will wire to Overpass API backend |
| "Upload Image" button — disabled | MapSidebar.tsx | Phase 20 (AI Vision) will implement |
| `state` prop always `'idle'` in MapPage | MapPage.tsx | Phase 18 will introduce loading/result states |
| Loading state progress bar — static 60% | MapSidebar.tsx | Phase 18 will wire real progress |

These stubs are intentional — the plan explicitly scopes them as "structural placeholders for Phase 18/20".

## Self-Check: PASSED

Files exist:
- frontend/src/components/BoundingBoxMap.tsx: FOUND
- frontend/src/components/MapSidebar.tsx: FOUND
- frontend/src/pages/MapPage.tsx: FOUND
- frontend/src/App.tsx: modified, MapPage imported and wired

Commits exist:
- d04900c: feat(17-02): create BoundingBoxMap component with Leaflet map and bbox overlay
- c74a89f: feat(17-02): create MapSidebar, MapPage, wire /map route in App
