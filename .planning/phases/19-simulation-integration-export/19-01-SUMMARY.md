---
phase: 19-simulation-integration-export
plan: "01"
subsystem: frontend
tags: [react-leaflet, road-graph, export, overlay, mapconfig]
dependency_graph:
  requires: [18-02]
  provides: [road-graph-preview, json-export]
  affects: [MapPage, MapSidebar, BoundingBoxMap]
tech_stack:
  added: []
  patterns: [react-leaflet CircleMarker/Polyline overlay, Blob URL download, children prop pass-through]
key_files:
  created:
    - frontend/src/components/RoadGraphPreview.tsx
  modified:
    - frontend/src/components/BoundingBoxMap.tsx
    - frontend/src/components/MapSidebar.tsx
    - frontend/src/pages/MapPage.tsx
decisions:
  - "Pass RoadGraphPreview as children to BoundingBoxMap rather than alongside it — avoids needing a shared map ref"
  - "Linear interpolation for pixel→lat/lng conversion using node coordinate bounds (maxX/maxY from node set)"
  - "Typed MapConfigData interface replaces loose unknown for mapConfig state"
metrics:
  duration: "12 minutes"
  completed_date: "2026-04-12"
  tasks_completed: 2
  tasks_total: 2
  files_changed: 4
---

# Phase 19 Plan 01: Road Graph Preview and Export JSON Summary

Road graph overlay on Leaflet map using react-leaflet CircleMarker/Polyline with pixel-to-latLng coordinate mapping, plus client-side Blob download for Export JSON.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Road graph preview overlay on Leaflet map | f2ed988 | RoadGraphPreview.tsx (new), BoundingBoxMap.tsx, MapPage.tsx |
| 2 | Wire Export JSON button to download mapConfig | f2ed988 | MapSidebar.tsx, MapPage.tsx |

## What Was Built

### RoadGraphPreview component (`frontend/src/components/RoadGraphPreview.tsx`)

New component (68 lines) that renders the road graph returned by `/api/osm/fetch-roads` as an overlay on the Leaflet map:
- `CircleMarker` for each node (radius 4, `#44aaff`)
- `Polyline` for each road edge (`#44aaff`, weight proportional to `laneCount * 1.5`, min 2)
- Coordinate conversion: pixel x/y from node set are mapped back to lat/lng via linear interpolation over bbox bounds. `lat = south + (1 - y/maxY) * (north - south)`, `lng = west + (x/maxX) * (east - west)` (y-axis inverted because pixel y grows downward)
- Renders directly inside MapContainer as react-leaflet children

### BoundingBoxMap updated (`frontend/src/components/BoundingBoxMap.tsx`)

Added `children?: React.ReactNode` prop — rendered inside `MapContainer` after `BoundingBoxLayer`. This allows `MapPage` to pass overlay components without needing a shared map ref.

### MapPage updated (`frontend/src/pages/MapPage.tsx`)

- `mapConfig` state typed as `MapConfigData` (typed interface with nodes/roads/intersections arrays) replacing `unknown`
- `handleExportJson` callback: serializes mapConfig to JSON, creates Blob with `application/json`, triggers `<a>` click for download as `map-config.json`
- Renders `<RoadGraphPreview mapConfig={mapConfig} bbox={bbox} />` inside `<BoundingBoxMap>` when `sidebarState === 'result'`

### MapSidebar updated (`frontend/src/components/MapSidebar.tsx`)

- Added `onExportJson?: () => void` to `MapSidebarProps`
- `ResultContent` receives and calls `onExportJson` from the Export JSON button
- Export JSON button is now enabled (no `disabled`, no `title="Coming in Phase 19"`)
- Run Simulation button remains disabled (`Coming in Phase 19`)

## Decisions Made

1. **Children prop for BoundingBoxMap**: Preferred over a shared map ref — simpler, less state, react-leaflet components work natively as children inside MapContainer.
2. **Pixel→lat/lng linear interpolation**: Used node coordinate bounds (max x/y from node set) for mapping. The converter uses a simple pixel-space, so linear interpolation over bbox bounds is sufficient.
3. **Typed MapConfigData**: Replaced the `unknown` cast with a concrete interface that matches the OSM pipeline output shape, eliminating type assertions downstream.

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

- "Run Simulation" button in result state remains disabled with `title="Coming in Phase 19"`. This is intentional — Phase 19 Plan 02 (if planned) or Phase 20 will wire simulation loading. The Export JSON functionality is complete.

## Self-Check: PASSED
