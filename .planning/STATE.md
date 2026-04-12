---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: — Map Screenshot to Simulation
current_phase: 18 — OSM Data Pipeline (Plan 01 complete)
current_plan: 19-01 (next phase)
status: Phase 18 Plan 01 complete — OSM pipeline backend done
last_updated: "2026-04-12T16:37:00Z"
progress:
  total_phases: 20
  completed_phases: 15
  total_plans: 66
  completed_plans: 67
---

# Project State

**Project:** Traffic Simulator
**Milestone:** v2.0 — Map Screenshot to Simulation
**Current Phase:** 17 — Routing & Map Embed (Plan 01 complete)
**Last Updated:** 2026-04-12

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-12)

**Core value:** Wierna symulacja fizyki ruchu drogowego
**Current focus:** Milestone v2.0 — roadmap created, starting Phase 17

## Phase Status

### v1.0 Phases (all complete)

| Phase | Name | Status |
|-------|------|--------|
| 1 | Project Bootstrap & Infrastructure | ✓ Complete |
| 2 | Domain Model & Road Network Foundation | ✓ Complete (5/5 plans done) |
| 3 | Physics Engine (IDM) | ✓ Complete (2/2 plans done) |
| 4 | Simulation Engine & Tick Loop | ✓ Complete (4/4 plans done) |
| 5 | Canvas Rendering & Basic UI | ✓ Complete |
| 6 | Live Obstacle Placement | ✓ Complete |
| 7 | Lane Changing & Road Narrowing | ✓ Complete |
| 8 | Traffic Signals & Intersections | ✓ Complete |
| 9 | JSON Map Configuration & Predefined Scenarios | ✓ Complete |
| 10 | Roundabouts, Priority Intersections & Polish | ✓ Complete |
| 11 | Architecture Refactoring | ✓ Complete |
| 12 | Intersection Rendering Refactor | ✓ Complete |
| 13 | Canvas Layout Fix & Merge Lane Targeting | ✓ Complete |
| 14 | Overlap Fix & Responsive Layout | ✓ Complete |
| 15 | SonarQube Code Quality Fixes | ✓ Complete |
| 16 | Combined Scenario — Roundabout + Signal + Merge + Loop | ✓ Complete (1/1 plans done) |

### v2.0 Phases

| Phase | Name | Status |
|-------|------|--------|
| 17 | Routing & Map Embed | ✓ Complete (2/2 plans done) |
| 18 | OSM Data Pipeline | ✓ Complete (1/1 plans done) |
| 19 | Simulation Integration & Export | ○ Not started |
| 20 | AI Vision (Claude CLI) | ○ Not started |

## Current Position

**Active phase:** 19 — Simulation Integration & Export
**Current plan:** 19-01 (next)
**Completed:** Phase 18 Plan 01 done — OsmPipelineService (Overpass client + OSM converter), BboxRequest DTO, OsmClientConfig, LoadConfig command in sealed interface, MapLoader.loadFromConfig(), CommandDispatcher.handleLoadConfig()
**Integration point:** Phase 19 wires OSM pipeline to frontend — OsmController POST endpoint + LoadConfig dispatch

## Key Decisions (v2.0)

| Decision | Rationale |
|----------|-----------|
| Leaflet + OSM instead of Google Maps | Google Maps ToS prohibits AI analysis of map content; Leaflet is open and free |
| Overpass API for road data (primary) | Structured, queryable OSM data — more reliable than screenshot + AI as primary path |
| AI Vision (Claude CLI) as separate phase | Optional path; should not block OSM pipeline delivery |
| react-router-dom for routing | Standard React routing; enables /map page without full reload |
| useWebSocket() at App root above Routes | Keeps WS STOMP connection alive when navigating to /map |
| Page components in src/pages/, shared hooks in src/hooks/ | Separation of concerns for scalable routing |
| Missing-node way throws IllegalStateException | Service correctly throws when all ways are filtered out — returning empty-roads MapConfig would fail MapValidator anyway |

## Session Log

- 2026-03-27: Project initialized, research complete, requirements defined, roadmap created
- 2026-03-27: Phase 2 Plan 2.1 complete — 8 domain model classes created (Vehicle, Lane, Road, RoadNetwork, Intersection, IntersectionType, SpawnPoint, DespawnPoint)
- 2026-03-27: Phase 2 Plan 2.2 complete — CommandQueue pattern: sealed SimulationCommand, SimulationEngine with LinkedBlockingQueue, CommandHandler STOMP controller
- 2026-03-27: Phase 2 Plan 2.4 complete — VehicleSpawner with accumulator-based rate, overlap prevention, ±20% IDM noise, despawn logic; 11 unit tests pass; 3 review bugs fixed
- 2026-03-27: Phase 2 Plan 2.3 catch-up — MapConfig, MapValidator, MapLoader, straight-road.json fixture created; 8 MapLoaderTest tests pass
- 2026-03-27: Phase 2 Plan 2.5 complete — SimulationStateDto replaces TickDto; VehicleDto+StatsDto; TickEmitter fully wired; 3 review fixes applied (tick counter, timing guard, SetSpawnRate wiring); 30 total tests pass; Phase 2 complete
- 2026-03-28: Phase 3 Plan 3.1 complete — PhysicsEngine with IDM car-following model, Euler integration, 5 safety guards
- 2026-03-28: Phase 3 Plan 3.2 complete — 9-test PhysicsEngine test suite (free-flow, following, emergency stop, zero-gap, NaN guard, velocity clamp, benchmark, monotonicity, empty lane); 38 total tests pass; Phase 3 complete
- 2026-03-28: Phase 4 Plan 4.3 complete — SimulationStatusDto + SimulationController with GET /api/simulation/status and GET /api/maps endpoints; all 38 tests pass
- 2026-03-28: Phase 4 Plan 4.4 complete — 1000-thread concurrent enqueue test + 5 tick pipeline integration tests (spawn/physics/despawn, pause/resume, stop/restart, speed multiplier); 44 total tests pass
- 2026-03-28: Phase 5 Plan 5.2 complete — TypeScript DTOs (VehicleDto, SimulationStateDto, CommandDto, RoadDto, Snapshot), Zustand store expansion (prev/curr snapshots, sendCommand, roads), useWebSocket wiring (sendCommand + REST /api/roads); tsc --noEmit passes
- 2026-03-28: Phase 5 Plan 5.5 complete — StatsPanel component with live statistics (vehicles, avg speed km/h, density, throughput, tick count); reads from Zustand store; tsc --noEmit passes
- 2026-03-28: Phase 5 Plan 5.7 complete — 15 frontend tests: interpolation (6), Zustand store (6), StatsPanel rendering (3); all pass; tsc --noEmit clean
- 2026-03-28: Phase 7 Plan 7.4 complete — 12 tests: LaneChangeEngineTest (9 MOBIL safety/correctness tests), RoadNarrowingIntegrationTest (3 integration tests); 56 total tests pass
- 2026-04-02: Phase 13 Plan 13-01 complete — Fixed canvas layout overflow: removed flex centering from App.tsx main, added margin:auto + flexShrink:0 to SimulationCanvas; wide maps now scroll instead of breaking layout; tsc --noEmit passes
- 2026-04-02: Phase 13 Plan 13-03 complete — Merge-aware lane targeting: ramp vehicles (1-lane inbound) now target lane 0 of multi-lane outbound road; pickTargetLane overloaded with inboundRoad parameter; 140 tests pass; FIX-03 satisfied
- 2026-04-02: Phase 14 Plan 14-01 complete — BUG-1 fix: shared lastPlacedPosition map prevents same-tick vehicle overlap at merge intersections; effectivePosition = max(outBuffer, lastPlaced + MIN_ENTRY_GAP); all 140 tests pass
- 2026-04-02: Phase 14 Plan 14-02 complete — Responsive flex layout (useIsMobile hook, 768px breakpoint), flexShrink:0 on sidebar (BUG-3 fix), CSS transform:scale canvas fill with ResizeObserver (BUG-4 fix), mobile stacked layout with 50vh canvas cap (BUG-2 fix); tsc --noEmit passes
- 2026-04-08: Phase 16 Plan 16-01 complete — combined-loop.json capstone scenario: 14 nodes, 15 roads, 8 intersections (4 ROUNDABOUT + 1 SIGNAL + 3 PRIORITY); 6-phase traffic light; loop topology; MapLoaderScenarioTest.loadsCombinedLoop() passes; 7/7 scenario tests pass
- 2026-04-10: Milestone v2.0 roadmap created — 4 phases (17–20), 13 requirements mapped, starting Phase 17
- 2026-04-12: Phase 17 Plan 01 complete — react-router-dom v6, BrowserRouter + Routes, SimulationPage extracted from App, NavHeader with useLocation active-link highlighting, useIsMobile hook extracted, leaflet + react-leaflet installed; tsc --noEmit clean
- 2026-04-12: Phase 17 Plan 02 complete — MapPage with Leaflet/OSM map, BoundingBoxMap (L.rectangle 20% inset, moveend/zoomend updates), MapSidebar (idle/loading/result states, bbox dimensions in meters), responsive layout; tsc --noEmit clean; Phase 17 complete
- 2026-04-12: Phase 18 Plan 01 complete — OsmPipelineService (Overpass client + OSM converter), BboxRequest DTO, OsmClientConfig, LoadConfig command in sealed interface, MapLoader.loadFromConfig(), CommandDispatcher.handleLoadConfig(); 216 total tests pass; Phase 18 complete
