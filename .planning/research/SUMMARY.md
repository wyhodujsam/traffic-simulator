# Research Summary — Traffic Simulator

**Synthesized:** 2026-03-27
**Sources:** STACK.md · FEATURES.md · ARCHITECTURE.md · PITFALLS.md · PROJECT.md
**Overall Confidence:** HIGH

---

## 1. Executive Summary

This project is a web-based, top-down traffic microsimulator whose core purpose is demonstrating emergent congestion phenomena — specifically phantom traffic jams — through real-time interactive experimentation. The technical stack is fully constrained by user preference: Java 17 + Spring Boot 3.x on the backend, React 18 + TypeScript + Vite on the frontend, communicating over WebSocket (STOMP/SockJS). Research confirms this is a well-matched stack for the problem domain, with no gaps and no forced trade-offs. All research documents converge on the same architecture: a tick-based backend simulation engine that owns time, broadcasting state snapshots at 20 Hz to a frontend that renders on `requestAnimationFrame` at 60 fps — fully decoupled, which is the correct design.

The physics foundation is the Intelligent Driver Model (IDM), a continuous car-following model proven to produce realistic acceleration, braking, and spontaneous phantom jam formation when vehicles are given heterogeneous parameters. The MOBIL model governs lane changes. Both are O(N) algorithms — the simulation can support 200–500 vehicles comfortably on a single core at 20 Hz without specialised optimisation. The most distinctive user-facing feature is live obstacle placement mid-simulation, which is what makes the phantom jam effect observable and manipulable in a way no existing web tool provides.

The research reveals ten specific pitfalls in this domain, of which thread safety and simulation-rendering coupling are the most architecturally consequential. Both are fully mitigated by the recommended architecture: a command queue pattern eliminates data races between the tick thread and the STOMP broker thread, and keeping the simulation loop on the backend `ScheduledExecutorService` (not the browser's `requestAnimationFrame`) eliminates all coupling between physics and rendering. The remaining pitfalls are implementation-level concerns addressable in dedicated phases.

---

## 2. Key Findings

### 2.1 Recommended Stack

| Layer | Technology | Version | Key Notes |
|---|---|---|---|
| Backend runtime | Java | 17 LTS | Spring Boot 3.x minimum |
| Backend framework | Spring Boot | 3.3.x | WebSocket STOMP, autoconfiguration |
| Build | Maven | 3.9.x | Standard, Spring Initializr |
| Frontend framework | React | 18.3.x | Concurrent rendering, hooks API |
| Type safety | TypeScript | 5.4.x | Strict mode for simulation state types |
| Frontend build | Vite | 5.x | Fast HMR, replaces CRA |
| Frontend runtime | Node.js | 20 LTS | Required for Vite 5 |
| WebSocket client | @stomp/stompjs | 7.x | Standalone, no jQuery |
| WS transport fallback | sockjs-client | 1.6.x | ESM-compatible |
| State management | Zustand | 4.x | Low-overhead real-time store; avoids Redux re-render cost |
| Canvas rendering | Raw HTML5 Canvas API | — | `useRef` + `requestAnimationFrame`; react-konva rejected (per-frame scene overhead) |
| Backend serialisation | Jackson (managed) | 2.17.x | Auto-configured by Spring Boot |
| Backend utilities | Lombok | 1.18.32 | DTOs and simulation entities |
| Frontend testing | Vitest + @testing-library/react | 1.x / 16.x | Vite-native, no Jest needed |

**Key rejection decisions:** Redux (re-render overhead), react-konva/PixiJS (wrong abstraction for 30 fps simulation), SSE (one-directional), SVG (performance cliff at 100+ elements), Spring Security/Hibernate/WebFlux (all out of scope for single-user in-memory sim).

---

### 2.2 Expected Features

#### Table Stakes (must have — absence causes rejection)

| Feature | Why It Is Non-Negotiable |
|---|---|
| IDM car-following physics | Core of any microsimulator; without it vehicles behave unrealistically |
| Individual vehicle parameters | Required for heterogeneous traffic and emergent phantom jams |
| Multi-lane roads | Lane semantics required for narrowing and lane-change effects |
| Intersections (signalised) | #1 user experiment target; traffic lights are the first control lever |
| Lane changing | Without it, road narrowing has no visible effect |
| Real-time top-down Canvas rendering | Users cannot observe emergent phenomena without seeing them unfold |
| Play / Pause / Speed controls | Simulation is unusable as an interactive tool without these |
| Spawn rate control | Primary independent variable for congestion experiments |
| Statistics dashboard | Minimum feedback loop: average speed + density + throughput |
| WebSocket real-time delivery | Pull polling would introduce artificial latency incompatible with live obstacle experiments |

#### Differentiators (competitive advantage)

| Feature | Competitive Edge | Priority |
|---|---|---|
| Live obstacle placement mid-simulation | No web tool does this; triggers instant shockwave — the core educational moment | P0 — MVP |
| Phantom traffic jam visibility | Emergent from IDM; branding this explicitly is unique to this tool | P2 |
| JSON-configurable maps (no XML) | Lightweight alternative to SUMO's verbose XML; enables community sharing | P1 |
| Configurable signal cycles on running sim | Most simulators require restart; live editing is the differentiator | P1 |
| Roundabout simulation | Distinct dynamics from signals; uncommon in educational web tools | P2 |
| Road narrowing (live lane reduction) | Models construction zones; demonstrates merge-induced congestion | P1 |
| Right-of-way priority intersections | Completes the intersection taxonomy | P2 |

#### Anti-Features (explicitly excluded)

3D rendering, AI/ML vehicle control, map editor (v1), multiplayer, pedestrians/cyclists, persistent storage, auth, OSM import, accident simulation. These are either wrong domain, scope-doubles with no educational gain, or correctly deferred to v2+.

#### MVP Definition (smallest runnable phantom jam demo)

Single straight multi-lane road + IDM physics + spawn rate control + live obstacle placement + play/pause/speed + Canvas rendering + basic stats + WebSocket delivery + hardcoded map. **DoD:** Drop obstacle → traffic backs up within 10s; remove obstacle → jam dissipates naturally; average speed visibly drops during jam.

---

### 2.3 Architecture Approach

**Pattern:** Backend-authoritative tick-based simulation with frontend-only rendering.

The backend runs a `ScheduledExecutorService` at 20 Hz (50 ms/tick). Each tick executes subsystems in a fixed order: traffic lights → spawn → physics (IDM) → lane changes → collision detection → intersection logic → despawn → state snapshot → WebSocket broadcast. The frontend receives state snapshots via STOMP subscription, stores them in a `useRef` (not `useState` — no React re-render triggered), and reads the latest state on each `requestAnimationFrame` (60 fps), interpolating positions between the last two snapshots for smooth motion.

User commands (pause, add obstacle, change spawn rate, etc.) are sent as typed `CommandDto` objects to `/app/command`. The `CommandHandler` enqueues them in a `LinkedBlockingQueue`. The tick thread drains this queue at the start of each tick — the tick thread is the exclusive owner of simulation state. This eliminates all synchronization on the hot path.

**Backend layers:**
1. Domain Model — `Vehicle`, `Lane`, `Road`, `Intersection`, `Roundabout`, `TrafficLight`, `Obstacle`, `RoadNetwork`
2. Simulation Engine — `SimulationEngine` + `PhysicsEngine` (IDM) + `LaneChangeEngine` (MOBIL) + `CollisionDetector` + `TrafficLightController` + `IntersectionManager` + `RoundaboutManager` + `VehicleSpawner` + `ObstacleManager`
3. API/WebSocket Layer — `WebSocketConfig`, `CommandHandler`, `StatePublisher`, `SimulationController` (REST)
4. Config — `MapLoader` (JSON → `RoadNetwork`), `MapValidator`

**Frontend layers:**
1. `useWebSocket` hook — STOMP/SockJS connection, message dispatch
2. `useSimulation` hook / Zustand store — simulation state ref
3. `CanvasRenderer` — `drawRoads`, `drawVehicles`, `drawLights`, `drawObstacles`
4. `ControlsPanel` + `StatsPanel` — command emission and metric display

**Performance sweet spot:** 200 vehicles at 20 Hz — ~8 KB JSON per tick, ~160 KB/s WebSocket throughput. Comfortable for local and Tailscale remote access.

**Scaling mitigations (if needed):** Delta updates above 500 vehicles; binary serialisation (MessagePack) above 1000; `OffscreenCanvas` + Web Worker for rendering above 500 vehicles; viewport culling above 1000 vehicles.

---

### 2.4 Critical Pitfalls (Top 5)

**Pitfall 1 — Thread Safety (Severity: CRITICAL)**
The simulation tick thread and the STOMP broker thread both access simulation state. `ArrayList<Vehicle>` is not thread-safe; concurrent access causes `ConcurrentModificationException` and torn reads.
Mitigation: Command queue pattern — broker thread only enqueues `SimulationCommand` objects; tick thread drains the queue before each physics step and exclusively owns state. Never use `synchronized` on the simulation object (causes tick jitter).

**Pitfall 2 — Simulation-Rendering Coupling (Severity: CRITICAL)**
Running the simulation loop in the browser's `requestAnimationFrame` ties physics to render speed. Browser tab pause freezes simulation time. Speed multiplier becomes inconsistent across machines.
Mitigation: Simulation loop lives on the backend `ScheduledExecutorService`. Speed multiplier scales `dt` inside the physics step (sub-stepping at high speeds), not the scheduler frequency. Frontend `requestAnimationFrame` is cosmetic-only.

**Pitfall 3 — IDM Physics Edge Cases (Severity: HIGH)**
IDM produces `NaN`/`-Infinity` velocity when gap becomes zero (spawn overlap), when speed crosses zero (no clamp), or when position exceeds road length with no exit segment.
Mitigation: Clamp `velocity` to `[0, maxSpeed]` after every integration step. Enforce minimum gap `s_min >= 1.0 m`. Validate spawn positions against existing vehicles. Resolve road segment transitions before position update.

**Pitfall 4 — Intersection Deadlocks (Severity: HIGH)**
All approaches to a 4-way intersection fill simultaneously; vehicles block each other in a circular wait. The simulation keeps ticking but no vehicle moves.
Mitigation: Box-blocking prevention (vehicle checks exit road has free space before entering intersection). Occupancy limit per intersection. Watchdog deadlock detector (10s of frozen occupancy → force-advance one vehicle). Traffic light cycle coordination between adjacent intersections.

**Pitfall 5 — Lane Change Collisions (Severity: MEDIUM-HIGH)**
Two vehicles in adjacent lanes simultaneously pass the "gap safe?" check and commit to the same target slot. Both vehicles end up in the same lane position.
Mitigation: Two-phase update (intent phase marks slots as reserved in a scratch buffer; conflict resolution picks a winner; commit phase applies to live state). Per-vehicle lane change cooldown (3 seconds). Gradual lateral transition animation over N ticks (natural exclusion buffer).

---

## 3. Implications for Roadmap

### Suggested Phase Structure (8–10 phases, fine granularity)

#### Phase 1 — Project Bootstrap & Infrastructure
**Rationale:** Establish the Maven + Vite monorepo structure, STOMP WebSocket wiring, and a working ping-pong tick loop before any simulation logic. Every subsequent phase depends on this plumbing. Validate that the backend can push JSON to the frontend at 20 Hz and the frontend renders something.
**Plans:** Maven project setup with correct pom.xml dependencies; Vite + React + TypeScript scaffold; WebSocket config (STOMP, SockJS, `/ws` endpoint); STOMP client hook (`useWebSocket`); Zustand store skeleton; Docker Compose for local dev; end-to-end smoke test (backend tick → frontend console log).

#### Phase 2 — Domain Model & Road Network Foundation
**Rationale:** All simulation subsystems operate on the domain model. Getting `Vehicle`, `Lane`, `Road`, `RoadNetwork`, and `Intersection` right before writing physics prevents costly rewrites. Hardcoded single-road test fixture is sufficient here.
**Plans:** `Vehicle.java` with IDM parameter fields; `Lane.java`, `Road.java`, `RoadNetwork.java`; hardcoded single 3-lane road fixture; unit tests for model construction; `VehicleSpawner` stub (spawn one vehicle, verify it appears in state).

#### Phase 3 — Physics Engine (IDM + Integration)
**Rationale:** IDM is the core value of the entire project. Must be correct and edge-case-safe before anything else is built on top of it. Isolated unit tests are critical here — phantom jam emergence is the acceptance criterion.
**Plans:** `PhysicsEngine.java` with full IDM formula; velocity clamping `[0, maxSpeed]`; minimum gap guard `s_min`; Euler integration with configurable `dt`; parameterised `Vehicle` (±20% noise on v0, aMax, b); unit tests: single-vehicle free flow, two-vehicle following, emergency stop, edge cases (zero gap, NaN guard); benchmark 500 vehicles per tick < 5 ms.
**Research flag:** Verify IDM parameter ranges produce phantom jams at realistic vehicle densities before committing to values.

#### Phase 4 — Simulation Engine & Tick Loop
**Rationale:** Wire the physics engine into a running tick loop with proper thread architecture. Command queue pattern must be established here — retrofitting it later into a God class is the #1 technical debt trap identified in PITFALLS.md.
**Plans:** `SimulationEngine.java` with `ScheduledExecutorService` (dedicated `ThreadPoolTaskScheduler`); command queue pattern (`LinkedBlockingQueue<SimulationCommand>`); tick order (lights → spawn → physics → collision → despawn → snapshot); `StatePublisher` with `SimpMessagingTemplate`; `SimulationStateDto`, `VehicleDto` (Jackson); configurable tick rate and `dt`; start/stop/pause commands.

#### Phase 5 — Canvas Rendering & Basic UI
**Rationale:** First visible simulation. Layered canvas strategy must be established now (static roads layer + dynamic vehicles layer) — retrofitting this after 500-vehicle stress testing is expensive.
**Plans:** Layered canvas setup (roads layer drawn once, vehicles layer cleared per frame); `CanvasRenderer.ts` (`drawRoads`, `drawVehicles` as coloured rectangles with rotation); `requestAnimationFrame` loop reading `simulationStateRef`; position interpolation between ticks (alpha = elapsed/tickInterval); `ControlsPanel` (start/stop/pause, speed slider, spawn rate); `StatsPanel` (average speed, vehicle count); visual smoke test: phantom jam emergence on straight road.

#### Phase 6 — Live Obstacle Placement (Differentiator Feature)
**Rationale:** This is the core educational differentiator. Delivered early because it validates the entire MVP concept. It also tests the command dispatch path end-to-end and exercises `ObstacleManager` + collision detection.
**Plans:** `Obstacle.java`, `ObstacleManager.java` (add/remove at runtime); `CollisionDetector.java` (AABB checks with obstacle geometry); `ADD_OBSTACLE` / `REMOVE_OBSTACLE` commands; canvas click-to-place obstacle UI; visual indicator for blocked lane; end-to-end UAT: place obstacle → shockwave forms within 10s; remove obstacle → jam dissipates; average speed drop confirmed in stats.

#### Phase 7 — Lane Changing (MOBIL) & Road Narrowing
**Rationale:** Lane changing is a prerequisite for road narrowing to have any effect. MOBIL is the most technically complex subsystem. The two-phase update pattern (intent → conflict resolution → commit) must be implemented correctly here to prevent lane-change collision bugs.
**Plans:** `LaneChangeEngine.java` with MOBIL incentive + safety criteria; two-phase update (intent scratch buffer, conflict resolution); per-vehicle cooldown (3s); lateral animation over N ticks; road narrowing (`REDUCE_LANES` command, `ObstacleManager` marks lanes as closed); unit tests: simultaneous adjacent lane changes (no collision); MOBIL acceptance threshold tuning; visual test: lane closure causes merge congestion.

#### Phase 8 — Traffic Signals & Intersections
**Rationale:** Signals are the #1 user experiment target (feature research). Intersection logic is also where deadlock pitfalls live. Box-blocking prevention and deadlock watchdog must be part of this phase.
**Plans:** `TrafficLight.java` + `TrafficLightController.java` (phase timers, green/yellow/red per direction); `IntersectionManager.java` with right-of-way queue; box-blocking prevention (exit road free space check before entry); intersection occupancy limit; deadlock watchdog; `SET_LIGHT_CYCLE` command (live cycle editing); traffic light rendering on canvas (colour-coded signals at intersections); configurable cycles via `ControlsPanel`; UAT: signal change mid-run → downstream speed change visible within 5s.
**Research flag:** Verify deadlock prevention logic covers all 4-way configurations before closing phase.

#### Phase 9 — JSON Map Configuration & Predefined Scenarios
**Rationale:** Decouples simulation from hardcoded test fixtures. Enables reproducible experiments and prepares for roundabout + priority intersection scenarios. `MapValidator` must be complete before any user-facing map loading.
**Plans:** `MapConfig.java` POJO + Jackson schema; `MapLoader.java` (JSON → `RoadNetwork`); `MapValidator.java` (structural + topological + reachability checks); REST endpoint `GET /api/maps`, `POST /api/simulation/load`; 3–4 predefined scenario JSON files (highway-merge, signalised grid, phantom-jam corridor, construction-zone); frontend map selector UI; error display for invalid map config; unit tests for validator covering all failure modes from PITFALLS.md §9.

#### Phase 10 — Roundabouts, Priority Intersections & Polish
**Rationale:** Completes the intersection taxonomy. Roundabout gridlock is a distinct pitfall requiring specific entry-gating logic. Priority (right-of-way) intersections complete the feature set. This phase also covers visual polish and the "looks done but isn't" checklist from PITFALLS.md.
**Plans:** `Roundabout.java` + `RoundaboutManager.java` (yield-on-entry, entry gating, exit-road check, forced yield at >80% capacity); `IntersectionManager` extension for priority-from-right rule; roundabout and priority-road scenario JSON files; phantom traffic jam visualisation overlay (backward density wave highlight); canvas state indicators (paused indicator, WebSocket disconnect indicator, lane change animation); statistics async computation (separate 500 ms thread); WebSocket reconnect state reconciliation; full "looks done but isn't" checklist sweep.
**Research flag:** Roundabout capacity formula (`circumference / average_spacing`) needs validation against real roundabout capacity models before implementing entry gating.

---

### Phase Ordering Rationale

The order follows strict dependency chains identified in FEATURES.md:
- Phases 1–2 establish infrastructure before any physics (cannot simulate without plumbing).
- Phase 3 isolates physics with unit tests before integration (IDM bugs are hardest to find after integration).
- Phase 4 establishes thread architecture before any concurrent features (thread safety retrofit is catastrophic technical debt).
- Phase 5 provides visual feedback before complex features (invisible bugs are undebuggable).
- Phase 6 delivers the MVP differentiator early (validates the core educational concept).
- Phases 7–8 add lane-level and intersection complexity in dependency order (lane changes before narrowing; signals before roundabouts).
- Phase 9 decouples maps before the last intersection types (roundabout needs JSON config to be testable with multiple geometries).
- Phase 10 completes the feature set with the most complex and interconnected pieces last.

### Research Flags

| Phase | Open Question | Action Needed |
|---|---|---|
| Phase 3 | IDM parameter ranges that reliably produce phantom jams at target vehicle density (200 vehicles, 800m road) | Run numerical experiment with IDM formula before committing to parameter defaults |
| Phase 8 | Deadlock prevention coverage for all 4-way and T-junction configurations | Design decision needed on whether watchdog or structural prevention is the primary mechanism |
| Phase 10 | Roundabout capacity formula validation | Cross-reference with Highway Capacity Manual or SUMO roundabout model before implementation |

---

## 4. Confidence Assessment

| Area | Confidence | Basis |
|---|---|---|
| Stack selection | HIGH | Stack is user-constrained; all libraries confirmed compatible; no open version conflicts |
| Feature scope (MVP) | HIGH | Features cross-referenced against PROJECT.md Active requirements; MVP definition is concrete and testable |
| Feature scope (P1–P2) | HIGH | Dependency graph is clear; prioritisation matrix grounded in educational value |
| Architecture patterns | HIGH | IDM, MOBIL, STOMP/STOMP/SockJS are well-documented; patterns (command queue, observer, layered canvas) are established |
| Physics correctness | MEDIUM-HIGH | IDM formula is published; edge case guards are identified; actual parameter tuning requires empirical testing |
| Pitfall coverage | HIGH | 10 specific pitfalls documented with concrete mitigations; covers thread safety, performance, physics, UX, and data integrity |
| Phase ordering | HIGH | Dependency chain is fully traceable through feature dependency graph |
| Effort estimates | MEDIUM | Phase complexity is assessed but not time-estimated; IDM tuning and intersection deadlock prevention are the highest uncertainty items |

---

## 5. Sources

- **PROJECT.md** — `/home/sebastian/traffic-simulator/.planning/PROJECT.md` — primary requirements, constraints, and out-of-scope decisions
- **STACK.md** — `/home/sebastian/traffic-simulator/.planning/research/STACK.md` — technology selection, version compatibility, code patterns
- **FEATURES.md** — `/home/sebastian/traffic-simulator/.planning/research/FEATURES.md` — feature landscape (table stakes, differentiators, anti-features), MVP definition, prioritisation matrix
- **ARCHITECTURE.md** — `/home/sebastian/traffic-simulator/.planning/research/ARCHITECTURE.md` — system overview, component responsibilities, data flow diagrams, scaling analysis
- **PITFALLS.md** — `/home/sebastian/traffic-simulator/.planning/research/PITFALLS.md` — 10 critical pitfalls with root causes and mitigations; technical debt patterns; "looks done but isn't" checklist
- **Intelligent Driver Model** — Treiber, Hennecke, Helbing (2000). Congested Traffic States in Empirical Observations and Microscopic Simulations. Physics source for car-following model.
- **MOBIL** — Kesting, Treiber, Helbing (2007). General Lane-Changing Model MOBIL for Car-Following Models. Physics source for lane-change model.
- **SUMO (Eclipse)** — sumo.dlr.de — reference feature set for microsimulation completeness
- **Spring Boot 3.3 Release Notes** — https://spring.io/blog/2024/05/23/spring-boot-3-3-0-available-now
- **Spring WebSocket Documentation** — https://docs.spring.io/spring-framework/reference/web/websocket.html
- **Zustand README** — https://github.com/pmndrs/zustand
- **Vite 5 Migration Guide** — https://vitejs.dev/guide/migration

---

*Synthesized from 4 research documents. Ready for phase planning.*
