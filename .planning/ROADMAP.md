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
| 10 | Roundabouts, Priority Intersections & Polish | Complete intersection taxonomy and visual polish | IXTN-05, IXTN-06, VIS-06 | 5 | 4 |

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
1. `LaneChangeEngine.java` — MOBIL safety criterion (rear vehicle safe braking) + incentive criterion (speed gain threshold)
2. Two-phase update — intent scratch buffer marks target slots reserved; conflict resolution picks winner; commit applies to live state
3. Per-vehicle cooldown — `lastLaneChangeAt` timestamp, 3-second minimum between changes; lateral transition animation over N ticks
4. Road narrowing — `REDUCE_LANES` command in `ObstacleManager`; marks lane as closed at position; vehicles reroute via lane change

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

---
*Roadmap created: 2026-03-27*
*All 33 v1 requirements mapped across 10 phases*
