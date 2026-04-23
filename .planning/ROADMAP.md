# Roadmap: Traffic Simulator

**Created:** 2026-03-27
**Milestone:** v1.0
**Phases:** 10
**Requirements:** 33 mapped

## Overview

| # | Phase | Goal | Requirements | Plans | Success Criteria |
|---|-------|------|--------------|-------|------------------|
| 1 | Project Bootstrap & Infrastructure | Working end-to-end WebSocket pipeline | INFR-01, INFR-02, INFR-03, SIM-08 | 6 | 3 |
| 2 | Domain Model & Road Network Foundation | Core domain objects and road loading from JSON | ROAD-01, ROAD-05, ROAD-06, SIM-02, SIM-05, SIM-06, INFR-04 | 5 | 3 |
| 3 | Physics Engine (IDM) | ✓ Realistic car-following physics with edge-case safety | SIM-01, SIM-03, SIM-04, SIM-07 | 2 | 3 |
| 4 | Simulation Engine & Tick Loop | Running tick loop with command queue thread safety | SIM-08 (integration) | 4 | 3 |
| 5 | Canvas Rendering & Basic UI | First visible simulation with controls and stats | VIS-01, VIS-02, VIS-03, CTRL-01, CTRL-02, CTRL-03, CTRL-04, STAT-01, STAT-02, STAT-03 | 7 | 4 |
| 6 | Live Obstacle Placement | Core MVP differentiator — phantom jam trigger | OBST-01, OBST-02, OBST-03, OBST-04, VIS-05 | 4 | 3 |
| 7 | Lane Changing & Road Narrowing | MOBIL lane-change model and live lane reduction | ROAD-02, ROAD-03, ROAD-04 | 4 | 3 |
| 8 | Traffic Signals & Intersections | Signalised intersections with deadlock prevention | IXTN-01, IXTN-02, IXTN-03, IXTN-04, IXTN-07, VIS-04 | 5 | 4 |
| 9 | JSON Map Config & Predefined Scenarios | Decoupled map loading and scenario selector | CTRL-05 | 4 | 3 |
| 10 | Roundabouts, Priority Intersections & Polish | ✓ Roundabout yield/gating, visual polish, disconnect banner | IXTN-05, IXTN-06, VIS-06 | 3 | 4 |

## Phase Details

### Phase 1: Project Bootstrap & Infrastructure

**Goal:** Establish Maven + Vite monorepo, STOMP WebSocket wiring, and a working 20 Hz ping-pong tick loop before any simulation logic.
**Requirements:** INFR-01, INFR-02, INFR-03, SIM-08
**UI hint:** no — backend tick broadcasts to frontend console log only

**Success Criteria:**
1. Backend starts with `mvn spring-boot:run` and logs "Tick #N" at 20 Hz.
2. Frontend starts with `npm run dev` and connects via WebSocket — browser console shows tick messages.
3. End-to-end smoke test passes: backend tick → STOMP broker → frontend subscription → console log, no errors.

**Plans:**
1. Maven project setup — Spring Boot 3.x pom.xml with WebSocket, STOMP, SockJS, Lombok, Jackson dependencies
2. Vite + React + TypeScript scaffold — Vite 5 project with strict TypeScript, Zustand, @stomp/stompjs, sockjs-client
3. WebSocket config — `WebSocketConfig.java` with STOMP endpoint `/ws`, message broker `/topic`, app prefix `/app`
4. STOMP client hook — `useWebSocket.ts` connecting to `/ws` via SockJS, subscribing to `/topic/simulation`
5. Zustand store skeleton — `useSimulationStore.ts` with typed `SimulationStateDto` shape
6. Smoke test fixture — backend emits `{tick: N}` JSON every 50 ms; frontend logs receipt; verify latency < 100 ms

---

### Phase 2: Domain Model & Road Network Foundation

**Goal:** Define all core domain objects (`Vehicle`, `Lane`, `Road`, `RoadNetwork`, `Intersection`) and wire JSON-based road loading with a hardcoded single-road fixture.
**Requirements:** ROAD-01, ROAD-05, ROAD-06, SIM-02, SIM-05, SIM-06, INFR-04
**UI hint:** no — domain only, no rendering yet

**Success Criteria:**
1. `MapLoader` reads a hardcoded 3-lane straight-road JSON and constructs a valid `RoadNetwork` object.
2. `VehicleSpawner` spawns one vehicle per second at the road entry point; vehicle appears in tick state snapshot.
3. Vehicle despawns on reaching road exit point; tick snapshot vehicle count decrements correctly.

**Plans:**
1. ✓ Domain model classes — `Vehicle.java` (IDM fields: v0, aMax, b, s0, T), `Lane.java`, `Road.java`, `RoadNetwork.java`, `Intersection.java` with Lombok
2. ✓ Command queue pattern — `SimulationCommand` sealed interface, `CommandHandler.java`, `LinkedBlockingQueue` wiring in `SimulationEngine`
3. ✓ `MapLoader.java` — Jackson-based JSON → `RoadNetwork` with hardcoded fixture `maps/straight-road.json`; `MapValidator` validates before build
4. ✓ `VehicleSpawner.java` — spawn at road entry points, configurable rate, overlap prevention (see 02-04-SUMMARY.md)
5. ✓ Integration — `SimulationStateDto` replaces `TickDto`; `TickEmitter` wired with spawn/despawn; 30 tests pass (see 02-05-SUMMARY.md)

**Note:** Phase 2 complete. All 5 plans done. 30 tests green.

---

### Phase 3: Physics Engine (IDM)

**Goal:** Implement full IDM car-following physics with velocity clamping and all edge-case guards so physics is correct and isolated before integration.
**Requirements:** SIM-01, SIM-03, SIM-04, SIM-07
**UI hint:** no — unit-tested physics, no rendering

**Success Criteria:**
1. Single-vehicle free-flow test: vehicle accelerates from 0 to `v0` within expected distance, no NaN/Infinity.
2. Two-vehicle following test: follower maintains safe gap `s >= s0`; gap never reaches zero.
3. Emergency stop test: vehicle ahead stops instantly; follower brakes hard and stops before collision; velocity clamped to [0, maxSpeed] throughout.

**Plans:**
1. ✓ `PhysicsEngine.java` — full IDM formula, Euler integration, 5 safety guards (zero-gap, negative speed, maxSpeed clamp, NaN fallback, s* floor)
2. ✓ Unit test suite — 9 tests: free-flow, following, emergency stop, zero-gap, NaN guard, velocity clamp, 500-vehicle benchmark, monotonicity, empty lane; 38 total tests pass

**Note:** Phase 3 complete. Plans reduced from 4 to 2 — edge-case guards and heterogeneous parameters were implemented inline in Plan 3.1 (PhysicsEngine already reads per-vehicle IDM params from VehicleSpawner's ±20% noise).

---

### Phase 4: Simulation Engine & Tick Loop

**Goal:** Wire physics into a running 20 Hz tick loop with proper thread architecture and command queue so all concurrency hazards are eliminated before any frontend interaction.
**Requirements:** SIM-01 (tick loop), SIM-08 (integration), INFR-04 (command queue wiring)
**UI hint:** no — backend only; state visible in WebSocket frames

**Success Criteria:**
1. Simulation ticks at 20 Hz continuously; tick timestamps in broadcast show < 5 ms jitter.
2. Pause command sent via WebSocket pauses the tick loop; resume restarts from correct state.
3. Thread safety: 1000 concurrent command enqueues during active tick loop produce zero `ConcurrentModificationException`.

**Plans:**
1. ✓ Wire PhysicsEngine into TickEmitter with speed multiplier and sub-stepping
2. Command queue integration — `LinkedBlockingQueue` drained at tick start; `START`, `STOP`, `PAUSE`, `RESUME` commands
3. ✓ `SimulationController.java` + `StatePublisher.java` — REST endpoints for status/maps + STOMP broadcast of SimulationStateDto
4. ✓ Thread safety & integration tests — 1000-thread concurrent enqueue, tick pipeline integration (spawn/physics/despawn, pause/resume, stop/restart, speed multiplier); 44 tests pass

---

### Phase 5: Canvas Rendering & Basic UI

**Goal:** First visible simulation — roads and vehicles rendered on Canvas with play/pause/speed controls and live statistics dashboard.
**Requirements:** VIS-01, VIS-02, VIS-03, CTRL-01, CTRL-02, CTRL-03, CTRL-04, STAT-01, STAT-02, STAT-03
**UI hint:** yes — full top-down Canvas view with control panel and stats panel

**Success Criteria:**
1. Canvas shows roads with lane markings and vehicles as colored rectangles moving smoothly at 60 fps.
2. Start/Pause/Stop buttons control simulation; speed slider (0.5x–5x) visibly changes vehicle movement pace.
3. Spawn rate control increases visible vehicle density on road within 5 seconds of adjustment.
4. Stats panel updates every tick showing average speed, vehicle count/density, and throughput.

**Plans:**
1. ✓ Layered canvas setup — static roads layer (drawn once) + dynamic vehicles layer (cleared per frame) using `useRef`
2. ✓ `CanvasRenderer.ts` — `drawRoads` (lane lines, road boundaries), `drawVehicles` (colored rectangles with rotation), `requestAnimationFrame` loop
3. ✓ Position interpolation — alpha = elapsed/tickInterval between last two snapshots for smooth 60 fps motion
4. ✓ `ControlsPanel.tsx` — Start/Stop/Pause buttons, speed multiplier slider, spawn rate slider, max speed input; each emits typed `CommandDto` via STOMP
5. ✓ `StatsPanel.tsx` — average speed (km/h), vehicle density (vehicles/km/lane), throughput (vehicles/min); reads from Zustand store
6. `ControlsPanel.tsx` wiring — STOMP command sending integration with Zustand store
7. ✓ Frontend tests — interpolation, store, and component smoke tests (15 tests)

---

### Phase 6: Live Obstacle Placement

**Goal:** Deliver the core MVP differentiator — user can place and remove obstacles mid-simulation, triggering visible shockwave and phantom jam formation.
**Requirements:** OBST-01, OBST-02, OBST-03, OBST-04, VIS-05
**UI hint:** yes — click-to-place obstacle on canvas; visual indicator for blocked lane

**Success Criteria:**
1. Clicking on a lane in the canvas places an obstacle; it appears as a distinct visual marker immediately.
2. Traffic backs up behind obstacle within 10 seconds; average speed stat visibly drops.
3. Clicking the obstacle removes it; jam dissipates naturally and average speed recovers over time.

**Plans:**
1. ✓ `Obstacle.java` + `ObstacleManager.java` — add/remove at runtime, obstacle occupies full lane width at position; command pipeline wired end-to-end
2. `CollisionDetector.java` — AABB distance check between vehicle front and obstacle; triggers IDM braking response
3. `ADD_OBSTACLE` / `REMOVE_OBSTACLE` commands — canvas click calculates road/lane/position from pixel coords, sends typed command
4. Canvas obstacle rendering — red/orange rectangle blocking lane with visual distinction from vehicles; click hit-testing for removal

---

### Phase 7: Lane Changing & Road Narrowing

**Goal:** Implement MOBIL lane-change model with two-phase collision-safe update and live road narrowing that causes visible merge congestion.
**Requirements:** ROAD-02, ROAD-03, ROAD-04
**UI hint:** yes — vehicles visibly change lanes; narrowed road shows merge point

**Success Criteria:**
1. Vehicles change lanes when the incentive criterion is met; no two vehicles occupy the same lane slot simultaneously.
2. Road narrowing command closes a lane mid-simulation; vehicles merge into remaining lanes with visible congestion.
3. Per-vehicle 3-second lane change cooldown prevents rapid oscillation between lanes.

**Plans:**
1. ✓ `LaneChangeEngine.java` — MOBIL safety criterion (rear vehicle safe braking) + incentive criterion (speed gain threshold)
2. Two-phase update — intent scratch buffer marks target slots reserved; conflict resolution picks winner; commit applies to live state
3. ✓ Road narrowing — `CloseLane` command through full pipeline (STOMP → CommandHandler → SimulationCommand → SimulationEngine); LaneDto + RoadDto REST extension
4. ✓ Lane change & road narrowing tests — 12 tests: LaneChangeEngineTest (9 MOBIL tests), RoadNarrowingIntegrationTest (3 integration tests)

---

### Phase 8: Traffic Signals & Intersections

**Goal:** Implement signalised intersections with configurable cycle timings, box-blocking prevention, and deadlock watchdog.
**Requirements:** IXTN-01, IXTN-02, IXTN-03, IXTN-04, IXTN-07, VIS-04
**UI hint:** yes — colored traffic lights rendered at intersections

**Success Criteria:**
1. Traffic lights cycle green/yellow/red at configurable intervals; vehicles stop at red and proceed on green.
2. Box-blocking prevention: vehicle at intersection entry waits if exit road is full; no gridlock on 4-way crossing.
3. Deadlock watchdog: simulated deadlock (all approaches blocked) resolves within 10 seconds by force-advancing one vehicle.
4. Changing signal timing via control panel takes effect within one full cycle; downstream speed change visible within 5 seconds.

**Plans:**
1. `TrafficLight.java` + `TrafficLightController.java` — phase timers, green/yellow/red state per approach, configurable timing
2. `IntersectionManager.java` — right-of-way queue, occupancy tracking, box-blocking prevention (exit road free-space check before entry)
3. Deadlock watchdog — 10-second frozen-occupancy detector; force-advances lowest-priority blocked vehicle
4. Priority (right-of-way) intersection logic — `IXTN-07` first-from-right rule within `IntersectionManager`
5. Traffic light canvas rendering — colored circles (green/yellow/red) at intersection stop lines; `SET_LIGHT_CYCLE` command wired to controls

---

### Phase 9: JSON Map Configuration & Predefined Scenarios

**Goal:** Decouple simulation from hardcoded fixtures by implementing full JSON map loading, validation, and a frontend scenario selector with 3–4 built-in maps.
**Requirements:** ROAD-05 (full), ROAD-06 (full), CTRL-05
**UI hint:** yes — map selector dropdown in controls panel

**Success Criteria:**
1. REST endpoint `GET /api/maps` returns list of available scenario names; frontend populates a selector dropdown.
2. Selecting a scenario reloads the simulation with the new map; canvas updates to show new road layout.
3. Invalid or malformed map JSON is rejected by `MapValidator` with a descriptive error displayed in the frontend.

**Plans:**
1. `MapConfig.java` POJO + Jackson schema — full typed config: roads, lanes, intersections, spawn points, signal timings
2. `MapLoader.java` + `MapValidator.java` — JSON → `RoadNetwork` with structural, topological, and reachability validation
3. 3–4 predefined scenario JSON files — `highway-merge.json`, `signalised-grid.json`, `phantom-jam-corridor.json`, `construction-zone.json`
4. Frontend map selector — `MapSelector.tsx` dropdown, REST fetch on mount, `LOAD_MAP` command on selection; error state display

---

### Phase 10: Roundabouts, Priority Intersections & Polish

**Goal:** Complete the intersection taxonomy with roundabout yield-on-entry and entry gating, then deliver visual polish and 60 fps interpolation smoothness.
**Requirements:** IXTN-05, IXTN-06, VIS-06
**UI hint:** yes — roundabout rendered on canvas; smooth vehicle animations; polish indicators

**Success Criteria:**
1. Vehicles entering a roundabout yield to circulating traffic; no simultaneous entry collision occurs.
2. At >80% roundabout capacity, entry gating activates; queue forms at entry; simulation does not deadlock.
3. Frontend renders at a steady 60 fps with position interpolation; vehicles move smoothly between 20 Hz ticks.
4. WebSocket disconnect indicator appears within 2 seconds of connection loss; reconnect restores state correctly.

**Plans:**
1. `Roundabout.java` + `RoundaboutManager.java` — yield-on-entry rule, exit-road check, entry gating at >80% capacity, forced yield under gridlock
2. `roundabout-scenario.json` + `priority-road-scenario.json` — scenario files exercising new intersection types
3. 60 fps interpolation hardening — position interpolation alpha clamped to [0,1]; browser tab visibility change handling; sub-step at 5x speed
4. Visual polish — paused indicator overlay, WebSocket disconnect banner, lane change smooth lateral animation over 5 ticks
5. Statistics async computation — stats computed on separate 500 ms schedule thread; WebSocket reconnect state reconciliation

### Phase 11: Architecture Refactoring

**Goal:** [To be planned]
**Requirements**: TBD
**Depends on:** Phase 10
**Plans:** 8/8 plans complete

Plans:
- [x] TBD (run /gsd:plan-phase 11 to break down) (completed 2026-03-29)

### Phase 12: Intersection Rendering Refactor

**Goal:** Clean intersection rendering — clip roads at intersection boundary, draw intersection boxes, parallel in/out roads via perpendicular offset
**Requirements**: VIS-04
**Depends on:** Phase 11
**Plans:** 5/5 plans complete ✓

Plans:
- [x] 12.1: Clip roads at intersection boundary (completed 2026-03-29)
- [x] 12.2: Draw intersection boxes (completed 2026-03-29)
- [x] 12.3: Parallel in/out roads via perpendicular offset (completed 2026-03-29)
- [x] 12.4: Paired road boundary suppression + yellow center lines (completed 2026-03-29)
- [x] 12.5: Visual verification and tuning (completed 2026-04-01)

### Phase 13: UI & Simulation Bugfixes

**Goal:** Fix 3 user-reported bugs — canvas layout overflow, traffic light visual confusion on four-way signal, and highway merge ramp targeting wrong lane
**Requirements**: FIX-01, FIX-02, FIX-03
**Depends on:** Phase 12

**Plans:** 3/3 plans complete

Plans:
- [x] 13-01-PLAN.md — Fix canvas layout overflow (FIX-01)
- [x] 13-02-PLAN.md — Fix traffic light visual confusion and box-blocking indicator (FIX-02)
- [x] 13-03-PLAN.md — Fix highway merge ramp lane targeting (FIX-03)

### Phase 14: Playwright Bug Fixes & Responsive Layout

**Goal:** Fix 6 bugs from Playwright testing session: merge vehicle overlap, mobile responsiveness, sidebar cutoff, canvas scaling, page title, button layout + UX polish
**Requirements**: BUG-1, BUG-2, BUG-3, BUG-4, BUG-5, BUG-6
**Depends on:** Phase 13

**Plans:** 3/3 plans complete

Plans:
- [x] 14-01-PLAN.md — Fix merge vehicle overlap with same-tick gap tracking (BUG-1)
- [x] 14-02-PLAN.md — Responsive layout + canvas scaling (BUG-2, BUG-3, BUG-4)
- [x] 14-03-PLAN.md — Page title + button layout fix (BUG-5, BUG-6)

### Phase 15: SonarQube Code Quality Fixes

**Goal:** Fix all 20 SonarQube BLOCKER and CRITICAL violations — test assertions, cognitive complexity reduction, duplicated literals
**Requirements**: SQ-01, SQ-02, SQ-03, SQ-04, SQ-05, SQ-06, SQ-07
**Depends on:** Phase 14

**Plans:** 3/3 plans complete

Plans:
- [x] 15-01-PLAN.md — Fix test assertions (SQ-01) + refactor MapValidator (SQ-02, SQ-07)
- [x] 15-02-PLAN.md — Refactor SnapshotBuilder, PhysicsEngine, TickEmitter, SimulationController (SQ-03, SQ-06)
- [x] 15-03-PLAN.md — Refactor LaneChangeEngine (SQ-04) + IntersectionManager (SQ-05)

### Phase 16: Combined Scenario — Roundabout + Signal + Merge + Loop

**Goal:** Create a complex multi-feature scenario JSON map combining a roundabout, signalized intersection, highway merge, and a loop road — vehicles circulate on the loop or despawn off-screen
**Requirements**: MAP-01
**Depends on:** Phase 15

**Plans:** 1/1 plans complete

Plans:
- [x] 16-01-PLAN.md — Create combined-loop.json scenario map with roundabout + signal + merge + loop (MAP-01)

---
*Roadmap created: 2026-03-27*
*All 33 v1 requirements mapped across 10 phases*

---

## Milestone v2.0 — Map Screenshot to Simulation

**Milestone goal:** User can select a real-world area, fetch road data from OpenStreetMap, convert it to a simulation-ready MapConfig, and run it — with an optional AI vision path for user-uploaded images.

**Phase numbering:** Continues from v1.0 (last phase: 16). Starts at 17.

**v2.0 Requirements:** 13 total (MAP-01–04, OSM-01–04, SINT-01–03, AVI-01–02)

### v2.0 Phase Overview

| # | Phase | Goal | Requirements | Success Criteria |
|---|-------|------|--------------|------------------|
| 17 | Routing & Map Embed | 2/2 | Complete   | 2026-04-12 |
| 18 | OSM Data Pipeline | 2/2 | Complete   | 2026-04-12 |
| 19 | Simulation Integration & Export | 1/2 | In Progress|  |
| 20 | AI Vision (Claude CLI) | 1/2 | In Progress|  |

### Phase 17: Routing & Map Embed

**Goal:** User can navigate between the simulation page and a new /map page that shows an interactive OSM map with a bounding box selector.
**Depends on:** Phase 16
**Requirements:** MAP-01, MAP-02, MAP-03, MAP-04
**Plans:** 2/2 plans complete

Plans:
- [x] 17-01-PLAN.md — Install deps, BrowserRouter routing, extract SimulationPage, nav header (MAP-04)
- [x] 17-02-PLAN.md — MapPage with Leaflet map, bounding box overlay, sidebar panel (MAP-01, MAP-02, MAP-03)
**UI hint**: yes

**Success Criteria (what must be TRUE):**
1. User can click a "Map" link/button on the simulation page and be taken to /map without a full page reload (react-router-dom).
2. The /map page shows a Leaflet map centered on a default location; user can pan and zoom to any area.
3. A bounding box rectangle is visible on the map and updates as the user moves or resizes it, showing the area that will be fetched.
4. User can navigate back to / (simulation page) from the map page without losing simulation state.

---

### Phase 18: OSM Data Pipeline

**Goal:** Backend can fetch road data for a selected bounding box from Overpass API and convert OSM way/node data into a valid MapConfig (Road, Lane, Intersection).
**Depends on:** Phase 17
**Requirements:** OSM-01, OSM-02, OSM-03, OSM-04
**Plans:** 2/2 plans complete

Plans:
- [x] 18-01-PLAN.md — LoadConfig command chain + OsmPipelineService (Overpass client + OSM-to-MapConfig converter) (OSM-01, OSM-02, OSM-03, OSM-04)
- [x] 18-02-PLAN.md — OsmController REST endpoint + frontend MapPage wiring (OSM-01, OSM-02, OSM-03, OSM-04)
**UI hint**: no (backend-focused; frontend wiring of existing "Fetch Roads" button)

**Success Criteria (what must be TRUE):**
1. User clicks "Fetch Roads" on the /map page; backend calls Overpass API for the visible bbox and returns a result within 10 seconds.
2. The returned MapConfig contains Road objects with lane counts matching the OSM `lanes=X` tag for roads that have it.
3. OSM nodes tagged `traffic_signals` produce SIGNAL intersections; OSM ways tagged `junction=roundabout` produce ROUNDABOUT intersections in the MapConfig.
4. An invalid or empty bbox (ocean, unpopulated area) returns a descriptive error message displayed on the /map page rather than crashing.

---

### Phase 19: Simulation Integration & Export

**Goal:** User can preview the road graph generated from OSM data, load it into the simulation engine, and export the MapConfig as JSON.
**Depends on:** Phase 18
**Requirements:** SINT-01, SINT-02, SINT-03
**Plans:** 1/2 plans executed

Plans:
- [x] 19-01-PLAN.md — Road graph preview overlay + Export JSON download (SINT-02, SINT-03)
- [ ] 19-02-PLAN.md — Run Simulation backend endpoint + frontend wiring with navigation (SINT-01)
**UI hint**: yes

**Success Criteria (what must be TRUE):**
1. After fetching OSM data, the /map page shows a schematic preview of the generated road graph (nodes and edges) before the user commits to loading it.
2. User clicks "Run Simulation" and is redirected to the simulation page (/) with the OSM-derived map loaded; vehicles spawn and move on the generated roads.
3. User can click "Export JSON" to download the generated MapConfig as a .json file that can be re-loaded as a predefined scenario.

---

### Phase 20: AI Vision (Claude CLI)

**Goal:** User can upload a photo or screenshot of a road and the backend calls Claude CLI to analyse the image and generate a MapConfig that can be loaded into the simulator.
**Depends on:** Phase 19
**Requirements:** AVI-01, AVI-02
**Plans:** 1/2 plans executed

Plans:
- [x] 20-01-PLAN.md — ClaudeVisionService + VisionController (Claude CLI via ProcessBuilder, multipart upload endpoint) (AVI-02)
- [ ] 20-02-PLAN.md — Frontend Upload Image wiring in MapSidebar + MapPage (AVI-01)
**UI hint**: yes

**Success Criteria (what must be TRUE):**
1. The /map page has an "Upload Image" section; user selects a file (JPEG/PNG) and it is accepted without error.
2. After upload, the backend calls Claude CLI with the image; within 30 seconds a MapConfig is returned and the road graph preview appears on the page.
3. If Claude CLI is unavailable or returns an unrecognisable response, the user sees a clear error message and can fall back to the OSM fetch path.

---

### Phase 21: Predefined Map Components (Pattern Library for Claude Vision)

**Goal:** Add an alternative Claude vision pipeline where a component catalog (ROUNDABOUT_4ARM, SIGNAL_4WAY, T_INTERSECTION, STRAIGHT_SEGMENT) owns the geometry in deterministic Java code. Claude only identifies component types, anchor positions, rotations, and arm-to-arm connections. Phase 20 endpoints remain byte-for-byte identical — both pipelines coexist for A/B comparison.

**Depends on:** Phase 20
**Requirements:** P21-CATALOG-TYPES, P21-CATALOG-SEALED, P21-LIB-SKELETON, P21-STITCH-MERGE, P21-STITCH-BRIDGE, P21-STITCH-ORPHAN, P21-VISION-PROMPT, P21-VISION-PARSE, P21-VISION-EXPAND-INTEGRATION, P21-ENDPOINT-MULTIPART, P21-ENDPOINT-BBOX, P21-PHASE20-REGRESSION, P21-FRONTEND-BUTTON, P21-FRONTEND-HANDLER, P21-HARNESS-GATED, P21-HARNESS-DIFF
**Plans:** 6/6 plans executed

Plans:
- [x] 21-01-PLAN.md — Component catalog (sealed ComponentSpec + 4 records + MapComponentLibrary skeleton)
- [x] 21-02-PLAN.md — Stitching algorithm (merge-coincident + bridge-via-segment)
- [x] 21-03-PLAN.md — ComponentVisionService (Claude prompt + parse + expansion integration)
- [x] 21-04-PLAN.md — POST /api/vision/analyze-components + /analyze-components-bbox endpoints
- [x] 21-05-PLAN.md — Frontend "AI Vision (component library)" button
- [x] 21-06-PLAN.md — VisionComparisonHarness (opt-in @SpringBootTest diff tool)

**Success Criteria (what must be TRUE):**
1. The /map page has a third button "AI Vision (component library)" that routes the current bbox to the component-library pipeline and loads the returned MapConfig.
2. The backend deterministically expands Claude's component recognition into a MapValidator-clean MapConfig; output passes the same validation gate as Phase 20 output.
3. Phase 20 endpoints (`/api/vision/analyze` and `/api/vision/analyze-bbox`) remain byte-for-byte identical; users can A/B the two pipelines on the same input.

---

### Phase 22: Extend Component Library (VIADUCT + HIGHWAY_EXIT_RAMP)

**Goal:** Add two component records to the Phase 21 catalog — `VIADUCT` (two through-roads crossing at different heights, no shared node) and `HIGHWAY_EXIT_RAMP` (three arms: mainIn, mainOut, rampOut with a PRIORITY split). Follow the existing sealed-interface patterns so adding types stays cheap.

**Depends on:** Phase 21
**Requirements:** CLIB-V22-01, CLIB-V22-02, CLIB-V22-03, CLIB-V22-04
**Plans:** 3/3 plans executed

Plans:
- [x] 22-01-PLAN.md — Viaduct + HighwayExitRamp records + expansion tests
- [x] 22-02-PLAN.md — Expose VIADUCT + HIGHWAY_EXIT_RAMP to Claude (prompt + DTO switch)
- [x] 22-03-PLAN.md — VisionComparisonHarness parametrised over viaduct + highway-exit-ramp fixtures

**Success Criteria (what must be TRUE):**
1. Claude can return VIADUCT or HIGHWAY_EXIT_RAMP in the components envelope and the pipeline expands them into a MapValidator-clean MapConfig.
2. No edits to Phase 20 code or the 4 original Phase 21 component records; `git log --stat` confirms additive diff.
3. `VisionComparisonHarness` covers both new fixture types via parametrised entries; harness still skips cleanly when fixture images are absent.

---

### v2.0 Progress Table

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 17. Routing & Map Embed | 2/2 | Complete | 2026-04-12 |
| 18. OSM Data Pipeline | 2/2 | Complete | 2026-04-12 |
| 19. Simulation Integration & Export | 2/2 | Complete | 2026-04-12 |
| 20. AI Vision (Claude CLI) | 1/1 | Complete | 2026-04-12 |
| 21. Predefined Map Components | 6/6 | Complete | 2026-04-14 |
| 22. Extend Component Library (VIADUCT + HIGHWAY_EXIT_RAMP) | 3/3 | Complete | 2026-04-14 |

### Phase 22.1: Playwright E2E test suite — install @playwright/test, config for backend+frontend dev servers, smoke tests for critical paths (simulation start/pause, AI Vision component-library flow, OSM bbox load, responsive layout) (INSERTED)

**Goal:** Install and configure Playwright e2e test framework in the frontend workspace and author five smoke tests for the critical user flows — simulation start/pause, AI Vision (component library) with stubbed backend, OSM bbox load with stubbed backend, responsive layout at mobile/desktop viewports, and sim controls (speed + spawn rate) reflected in StatsPanel. Infrastructure + smoke tests only; comprehensive coverage is a follow-up.
**Requirements**: N/A (inserted phase; scope is defined by CONTEXT.md decisions, not ROADMAP requirements)
**Depends on:** Phase 22
**Plans:** 6 plans

Plans:
- [ ] 22.1-01-PLAN.md — Install @playwright/test + @types/node, Chromium binary, playwright.config.ts with two-server webServer (backend 8086, frontend 5173), test:e2e script, e2e/ directory + README + .gitignore updates
- [ ] 22.1-02-PLAN.md — simulation.spec.ts: real-backend smoke test for four-way-signal Start -> vehicles spawn, Pause -> count freezes (no stubs)
- [ ] 22.1-03-PLAN.md — vision-components.spec.ts: stubbed /api/vision/analyze-components-bbox flow, RoadGraphPreview renders, Run Simulation navigates to /
- [ ] 22.1-04-PLAN.md — osm-bbox.spec.ts: stubbed /api/osm/fetch-roads flow, sidebar transitions to result state with correct counts
- [ ] 22.1-05-PLAN.md — responsive.spec.ts: mobile (375x667) stacked + desktop (1920x1080) side-by-side layouts via boundingBox geometry
- [ ] 22.1-06-PLAN.md — controls.spec.ts: speed slider + spawn rate slider round-trip through STOMP to StatsPanel (vehicle count climbs at 3x spawn rate)

### Phase 23: GraphHopper-based OSM parser

**Goal:** Swap custom Overpass converter for GraphHopper OSMReader/WaySegmentParser to get cleaner intersection splitting. Additive — coexists with Phase 18 `OsmPipelineService` via new `/api/osm/fetch-roads-gh` endpoint for A/B comparison on the same bbox.

**Requirements**: TBD (inserted phase — scope defined by 23-CONTEXT.md and 23-RESEARCH.md decisions)
**Depends on:** Phase 18
**Plans:** 8 plans

Plans:
- [ ] 23-00-PLAN.md — Wave-0 spike: verify A1 (WaySegmentParser nodeTags) + A7 (failing @Service bean vs Spring context) + add GraphHopper 10.2 dependency
- [ ] 23-01-PLAN.md — Extract Phase 18 helpers into OsmConversionUtils (shared projection/speed/lane/signal/endpoint utilities for A/B fairness)
- [ ] 23-02-PLAN.md — Create OsmConverter interface; retrofit OsmPipelineService to implement it with converterName() = "Overpass"
- [ ] 23-03-PLAN.md — Implement GraphHopperOsmService (WaySegmentParser Path B) + 5 OSM XML fixtures + 7+ unit tests
- [ ] 23-04-PLAN.md — Wire POST /api/osm/fetch-roads-gh in OsmController + 3 WebMvc tests + OsmPipelineComparisonTest (@SpringBootTest, disabled-by-default)
- [ ] 23-05-PLAN.md — Frontend: Fetch roads (GraphHopper) button in MapSidebar + MapPage handler + origin-labelled result headline + Vitest tests
- [ ] 23-06-PLAN.md — Playwright spec osm-bbox-gh.spec.ts mirroring Phase 22.1 osm-bbox.spec.ts
- [ ] 23-07-PLAN.md — Docs: backend/docs/osm-converters.md + delete Wave-0 spike + final full-suite gate

### Phase 24: osm2streets integration

**Goal:** Integrate A/B Street's osm2streets (Rust/WASM) for lane-level street network with markings, turn lanes, and intersection shapes. New `/api/osm/fetch-roads-o2s` endpoint for three-way A/B comparison against Phase 18 and Phase 23.

**Requirements**: TBD
**Depends on:** Phase 18
**Plans:** 0 plans

Plans:
- [ ] TBD (run /gsd:plan-phase 24 to break down)

### Phase 25: Traffic flow visualization

**Goal:** Scientific visualization for phantom-jam experiments — space-time diagram (trajectory t/x, color=speed), live fundamental diagram (flow vs density scatter), speed-colored vehicles, optional trails, ring-road scenario for Sugiyama-style self-emerging jams.

**Scope:**
- Space-time diagram canvas (rolling buffer of last N ticks × vehicle positions per road, color by speed).
- Fundamental diagram component (scatter flow [veh/h] vs density [veh/km], sampled per second).
- Vehicle rendering: color by speed (HSL red→green) with toggle.
- Optional trails (last 2s path behind vehicle).
- New scenario: `ring-road.json` — closed loop, uniform initial speed, no spawner; perturbations generate phantom jams.

**Requirements**: TBD
**Depends on:** Phase 5 (rendering), Phase 9 (scenarios)
**Plans:** 0 plans

Plans:
- [ ] TBD (run /gsd:plan-phase 25 to break down)

---
*v2.0 roadmap appended: 2026-04-10*
*All 13 v2.0 requirements mapped across 4 phases (17–20)*
