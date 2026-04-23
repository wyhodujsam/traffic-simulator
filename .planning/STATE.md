---
gsd_state_version: 1.0
milestone: v2.0
milestone_name: — Map Screenshot to Simulation
current_phase: 23
current_plan: 1
status: Ready to execute
last_updated: "2026-04-23T09:08:16.953Z"
progress:
  total_phases: 26
  completed_phases: 21
  total_plans: 100
  completed_plans: 94
  percent: 94
---

# Project State

**Project:** Traffic Simulator
**Milestone:** v2.0 — Map Screenshot to Simulation
**Current Phase:** 23
**Last Updated:** 2026-04-12

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-12)

**Core value:** Wierna symulacja fizyki ruchu drogowego
**Current focus:** Phase 23 — GraphHopper-based OSM parser

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
| 18 | OSM Data Pipeline | ✓ Complete (2/2 plans done) |
| 19 | Simulation Integration & Export | ✓ Complete (2/2 plans done) |
| 20 | AI Vision (Claude CLI) | ✓ Complete (1/1 plans done) |
| 21 | Predefined Map Components (Pattern Library) | ✓ Complete (6/6 plans done) |
| 22 | Extend Component Library (VIADUCT + HIGHWAY_EXIT_RAMP) | ✓ Complete (3/3 plans done) |
| 23 | GraphHopper-based OSM parser | ○ Not started |
| 24 | osm2streets integration | ○ Not started |
| 25 | Traffic flow visualization | ○ Not started |

## Current Position

Phase: 23 (GraphHopper-based OSM parser) — EXECUTING
Plan: 1 of 8
**Active phase:** — (between phases; ready to pick up 23/24/25)
**Current plan:** 1
**Completed:** Phase 22 Plan 03 — VisionComparisonHarness parametrised over viaduct + highway-exit-ramp fixtures. All 308 backend + 51 frontend tests green.
**Integration point:** Full component-library vision pipeline ships end-to-end with 6 component types (ROUNDABOUT_4ARM, SIGNAL_4WAY, T_INTERSECTION, STRAIGHT_SEGMENT, VIADUCT, HIGHWAY_EXIT_RAMP). Phase 20 free-form pipeline coexists; harness runs both for A/B comparison.

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
| Dedicated /api/command/load-config endpoint | MapConfig body is too complex for generic CommandDto — dedicated endpoint is cleaner |
| POST load-config then STOMP START in sequence | Ensures network loaded before simulation begins |

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
- 2026-04-12: Phase 18 Plan 02 complete — OsmController POST /api/osm/fetch-roads (422/503 error handling), OsmControllerTest (3 WebMvcTest tests), MapPage wired to backend fetch, MapSidebar loading/error/result states with road+intersection counts; 219 total tests pass; Phase 18 fully complete
- 2026-04-12: Phase 19 Plan 01 complete — RoadGraphPreview (CircleMarker nodes + Polyline edges on Leaflet), BoundingBoxMap children prop, Export JSON Blob download; tsc --noEmit clean
- 2026-04-12: Phase 19 Plan 02 complete — POST /api/command/load-config endpoint in SimulationController, Run Simulation button wired in MapSidebar/MapPage, useNavigate redirect to /, STOMP START after load; both backend and frontend compile clean
- 2026-04-12: Phase 20 Plan 01 complete — ClaudeVisionService (ProcessBuilder Claude CLI, JSON extraction, MapValidator), VisionController POST /api/vision/analyze (JPEG/PNG, 10MB limit, 400/422/503/504), ClaudeCliConfig @ConfigurationProperties; 238 tests pass (19 new)
- 2026-04-13: Phase 21 Plan 01 complete — vision/components package: sealed ComponentSpec + 4 records (RoundaboutFourArm, SignalFourWay, TIntersection, StraightSegment) + ArmRef/Connection/ExpansionContext + MapComponentLibrary skeleton (commit 997d733)
- 2026-04-13: Phase 21 Plan 02 complete — stitching algorithm (merge-coincident + bridge-via-segment); MapComponentLibrary.expand(components, connections) with ExpansionException on non-coincident arms; +11 tests (commit 8cdbfd9)
- 2026-04-13: Phase 21 Plan 03 complete — ComponentVisionService: Claude prompt enumerating 4 MVP types, two-pass DTO parsing, expansion integration (commit b66a1f5)
- 2026-04-13: Phase 21 Plan 04 complete — VisionController: POST /api/vision/analyze-components + /analyze-components-bbox; Phase 20 regression suite green (commit 0000a03)
- 2026-04-13: Phase 21 Plan 05 complete — MapSidebar "AI Vision (component library)" button routes bbox to new endpoint (commit a72dc18)
- 2026-04-13: Phase 21 Plan 06 complete — VisionComparisonHarness: opt-in @SpringBootTest (@EnabledIfSystemProperty vision.harness=true), writes target/vision-comparison/{free-form,components}/{map.json,diff.md} (commit 25ec1c4)
- 2026-04-14: Phase 21 fix — commit c2e4d59: explicit connection authoritative (drop 5px distance gate; Claude cannot know arm-endpoint geometry), STRAIGHT_SEGMENT prompt clarifies start/end arm names
- 2026-04-14: Phase 22 Plan 01 complete — Viaduct + HighwayExitRamp records + expansion tests; sealed ComponentSpec permits clause extended (commit f88d818)
- 2026-04-14: Phase 22 Plan 02 complete — ComponentVisionService prompt and DTO toSpec switch expose VIADUCT + HIGHWAY_EXIT_RAMP to Claude (commit d2bf9aa)
- 2026-04-14: Phase 22 Plan 03 complete — VisionComparisonHarness parametrised over viaduct + highway-exit-ramp fixtures; assume-skip when fixtures absent (commit 710d4bc)
- 2026-04-22: Phase 21/22 closed — wrote missing SUMMARY.md files, VERIFICATION.md for both phases; fixed two test failures surfaced by full-suite run (ArchitectureTest OsmPipelineService RuntimeException → IllegalStateException; ComponentVisionServiceTest disconnectedArms replaced by explicitConnection_mergesRegardlessOfDistance to match c2e4d59 contract); 308 backend + 51 frontend tests green

## Accumulated Context

### Roadmap Evolution

- Phase 21 added: Predefined map components — pattern library for Claude vision (roundabout, signal junction, T-intersection) with deterministic connection logic.
- Phase 22 added (2026-04-14): Extend component library — VIADUCT + HIGHWAY_EXIT_RAMP.
- Phase 23 added (2026-04-14): GraphHopper-based OSM parser — additive endpoint `/api/osm/fetch-roads-gh` for A/B comparison vs Phase 18.
- Phase 24 added (2026-04-14): osm2streets (Rust/WASM) integration — lane-level street network; endpoint `/api/osm/fetch-roads-o2s`.
- Phase 25 added (2026-04-14): Traffic flow visualization — space-time diagram, fundamental diagram, speed-colored vehicles, trails, ring-road scenario.
- Phase 22.1 inserted after Phase 22 (2026-04-22): Playwright E2E test suite — install @playwright/test, config for backend+frontend dev servers, smoke tests for critical paths (simulation start/pause, AI Vision component-library flow, OSM bbox load, responsive layout) (URGENT / INSERTED)
