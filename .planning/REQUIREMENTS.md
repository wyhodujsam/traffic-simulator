# Requirements: Traffic Simulator

**Defined:** 2026-03-27
**Core Value:** Wierna symulacja fizyki ruchu drogowego — realistyczne przyspieszanie, hamowanie i emergentne korki

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Simulation Engine

- [ ] **SIM-01**: Simulation runs on tick-based loop at configurable rate (default 20 Hz)
- [ ] **SIM-02**: Each vehicle has individual max speed, acceleration rate, and braking force
- [ ] **SIM-03**: Vehicles accelerate and decelerate smoothly using IDM car-following model
- [ ] **SIM-04**: Vehicles maintain safe following distance to vehicle ahead
- [ ] **SIM-05**: Vehicles spawn at road entry points at configurable rate
- [ ] **SIM-06**: Vehicles despawn when reaching road exit points
- [ ] **SIM-07**: Velocity is clamped to [0, maxSpeed] with edge-case guards (NaN, zero gap)
- [ ] **SIM-08**: Simulation state is broadcast via WebSocket (STOMP/SockJS) every tick

### Road Network

- [ ] **ROAD-01**: Roads support multiple lanes (2-4) with lane semantics
- [ ] **ROAD-02**: Vehicles change lanes using MOBIL model (safety + incentive criteria)
- [ ] **ROAD-03**: Lane changes use two-phase update (intent → conflict resolution → commit)
- [ ] **ROAD-04**: Road narrowing reduces active lanes mid-simulation
- [ ] **ROAD-05**: Road network is loaded from JSON configuration files
- [ ] **ROAD-06**: Map config includes roads, lanes, intersections, connections, and spawn points

### Intersections

- [ ] **IXTN-01**: Traffic lights cycle through green/yellow/red phases with configurable timings
- [ ] **IXTN-02**: Vehicles stop at red lights and proceed on green
- [ ] **IXTN-03**: Intersections prevent box-blocking (vehicle checks exit road has space before entering)
- [ ] **IXTN-04**: Deadlock watchdog detects frozen intersections and force-advances vehicles
- [ ] **IXTN-05**: Roundabouts implement yield-on-entry with exit-road check
- [ ] **IXTN-06**: Roundabouts implement entry gating at high capacity (>80%)
- [ ] **IXTN-07**: Unsignalled intersections enforce right-of-way priority (first z prawej)

### Obstacles

- [ ] **OBST-01**: User can place obstacles on any lane by clicking on the road
- [ ] **OBST-02**: Obstacles block the entire lane they are placed on
- [ ] **OBST-03**: User can remove obstacles by clicking on them
- [ ] **OBST-04**: Vehicles detect obstacles and brake/stop before them

### Visualization

- [ ] **VIS-01**: Simulation renders in top-down view on HTML5 Canvas
- [ ] **VIS-02**: Vehicles are displayed as colored rectangles with rotation matching direction
- [ ] **VIS-03**: Roads and lanes are visually rendered with lane markings
- [ ] **VIS-04**: Traffic lights show current state (green/yellow/red) at intersections
- [ ] **VIS-05**: Obstacles are visually distinct on the road
- [ ] **VIS-06**: Frontend renders at 60 fps with position interpolation between ticks

### Controls

- [ ] **CTRL-01**: User can start, stop, and pause the simulation
- [ ] **CTRL-02**: User can adjust simulation speed (0.5x to 5x)
- [ ] **CTRL-03**: User can adjust vehicle spawn rate
- [ ] **CTRL-04**: User can set global maximum vehicle speed
- [ ] **CTRL-05**: User can select from predefined map scenarios (3-4 built-in)

### Statistics

- [ ] **STAT-01**: Dashboard displays average speed of all vehicles in real-time
- [ ] **STAT-02**: Dashboard displays traffic density (vehicles per km per lane)
- [ ] **STAT-03**: Dashboard displays throughput (vehicles passing exit point per minute)

### Infrastructure

- [ ] **INFR-01**: Backend runs on Java 17 + Spring Boot 3.x with Maven
- [ ] **INFR-02**: Frontend runs on React 18 + TypeScript + Vite
- [ ] **INFR-03**: WebSocket communication uses STOMP over SockJS
- [ ] **INFR-04**: Command queue pattern ensures thread-safe simulation state access

### Bugfixes (Phase 13)

- [x] **FIX-01**: Canvas area must scroll instead of expanding the page when map is wider than viewport (e.g. phantom-jam-corridor at 2100px). Sidebar with controls must always remain visible.
- [x] **FIX-02**: Traffic lights on four-way-signal map — investigate and fix visual confusion where cars appear to stop on green and drive on red. Likely caused by box-blocking preventing movement on green, or traffic light rendering angle mismatch.
- [x] **FIX-03**: Highway merge — vehicles from on-ramp must merge onto the rightmost lane (lane 0) of the target road, not a random lane. pickTargetLane must respect merge semantics.

### Playwright Bug Fixes (Phase 14)

- [x] **BUG-1**: Merge vehicle overlap — pojazdy nakładają się w punkcie merge (highway-merge, spawn rate >= 2.0). Brak cross-road gap checking przy transferze.
- [ ] **BUG-2**: Brak responsywności mobilnej — na viewport 375x667 panel Controls niewidoczny, canvas zajmuje cały viewport, brak sterowania.
- [ ] **BUG-3**: Panel Controls obcięty w Straight Road — canvas wypycha sidebar poza viewport na 1280x720.
- [ ] **BUG-4**: Canvas nie skaluje się do dostępnej przestrzeni — na Full HD (1920x1080) canvas ma ~630px, reszta pusta.
- [x] **BUG-5**: Tytuł strony to generyczny "Vite + React + TS" zamiast "Traffic Simulator".
- [x] **BUG-6**: Przyciski Start/Pause/Stop łamią się na dwie linie zamiast mieścić w jednym rzędzie.

### SonarQube Code Quality (Phase 15)

- [x] **SQ-01**: [BLOCKER] Testy bez asercji — CommandQueueTest:33 i VehicleSpawnerTest:99 muszą mieć co najmniej jedną asercję (java:S2699)
- [x] **SQ-02**: [CRITICAL] MapValidator.validate() cognitive complexity 103→max 15 — rozbić na mniejsze metody walidacyjne (java:S3776)
- [x] **SQ-03**: [CRITICAL] SnapshotBuilder.buildSnapshot() cognitive complexity 77→max 15 — wydzielić metody pomocnicze (java:S3776)
- [x] **SQ-04**: [CRITICAL] LaneChangeEngine — 4 metody powyżej limitu (complexity 50, 37, 29, 16) — refaktor na mniejsze metody (java:S3776)
- [x] **SQ-05**: [CRITICAL] IntersectionManager — 5 metod powyżej limitu (complexity 31, 27, 23, 19, 18) — wydzielić logikę per typ skrzyżowania (java:S3776)
- [x] **SQ-06**: [CRITICAL] PhysicsEngine.tick() complexity 37, TickEmitter.emitTick() complexity 20, SimulationController complexity 16 — refaktor (java:S3776)
- [x] **SQ-07**: [CRITICAL] MapValidator zduplikowane literały "Road " i "Intersection " — wydzielić stałe (java:S1192)

## v2.0 Requirements — Map Screenshot to Simulation

Requirements for milestone v2.0. Adds real-world map import via OSM + optional AI vision.

### Map Embed

- [x] **MAP-01**: Użytkownik może otworzyć stronę /map z interaktywną mapą OSM (Leaflet)
- [x] **MAP-02**: Użytkownik może przesuwać i zoomować mapę do wybranego obszaru
- [x] **MAP-03**: Użytkownik widzi bounding box pokazujący obszar do pobrania
- [x] **MAP-04**: Aplikacja ma routing między stroną symulacji (/) a stroną mapy (/map)

### OSM Data Pipeline

- [x] **OSM-01**: Backend pobiera dane drogowe z Overpass API dla wybranego bbox
- [x] **OSM-02**: Konwerter zamienia OSM way/node na MapConfig (Road, Lane, Intersection)
- [x] **OSM-03**: Konwerter rozpoznaje typy skrzyżowań z tagów OSM (traffic_signals→SIGNAL, roundabout→ROUNDABOUT)
- [x] **OSM-04**: Konwerter wykrywa liczbę pasów z tagu lanes=X

### Simulation Integration

- [x] **SINT-01**: Użytkownik może załadować wygenerowany MapConfig do silnika i uruchomić symulację
- [x] **SINT-02**: Użytkownik widzi podgląd wygenerowanego grafu dróg przed uruchomieniem
- [x] **SINT-03**: Użytkownik może wyeksportować wygenerowany MapConfig jako plik JSON

### AI Vision (Claude CLI)

- [ ] **AVI-01**: Użytkownik może wgrać zdjęcie/screenshot drogi
- [x] **AVI-02**: Backend wywołuje Claude CLI do analizy obrazu i generowania MapConfig

## Future Requirements

Deferred to future releases.

### Live Editing

- **EDIT-01**: User can change traffic light cycle times while simulation runs
- **EDIT-02**: User can draw and edit road layouts in a map editor

### Advanced Visualization

- **AVIS-01**: Heatmap overlay showing congestion density
- **AVIS-02**: Phantom traffic jam wave visualization (backward-propagating highlight)

## Out of Scope

| Feature | Reason |
|---------|--------|
| 3D rendering | Wrong domain — top-down 2D is sufficient for traffic analysis |
| AI/ML vehicle control | Too complex, not the educational goal |
| Multiplayer | Single-user app, no collaboration needed |
| Pedestrians/cyclists | Scope-doubles with minimal educational gain |
| Persistent storage/database | In-memory simulation, no persistence needed |
| Authentication | Single-user app |
| Google Maps embed | ToS prohibits AI analysis of Google Maps content; Leaflet + OSM used instead |
| Accident simulation | Out of scope — focus is on congestion, not collisions |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFR-01 | Phase 1 — Project Bootstrap & Infrastructure | ○ Pending |
| INFR-02 | Phase 1 — Project Bootstrap & Infrastructure | ○ Pending |
| INFR-03 | Phase 1 — Project Bootstrap & Infrastructure | ○ Pending |
| SIM-08 | Phase 1 — Project Bootstrap & Infrastructure | ○ Pending |
| ROAD-01 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| ROAD-05 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| ROAD-06 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| SIM-02 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| SIM-05 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| SIM-06 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| INFR-04 | Phase 2 — Domain Model & Road Network Foundation | ○ Pending |
| SIM-01 | Phase 3 — Physics Engine (IDM) | ○ Pending |
| SIM-03 | Phase 3 — Physics Engine (IDM) | ○ Pending |
| SIM-04 | Phase 3 — Physics Engine (IDM) | ○ Pending |
| SIM-07 | Phase 3 — Physics Engine (IDM) | ○ Pending |
| VIS-01 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| VIS-02 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| VIS-03 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| CTRL-01 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| CTRL-02 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| CTRL-03 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| CTRL-04 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| STAT-01 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| STAT-02 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| STAT-03 | Phase 5 — Canvas Rendering & Basic UI | ○ Pending |
| OBST-01 | Phase 6 — Live Obstacle Placement | ○ Pending |
| OBST-02 | Phase 6 — Live Obstacle Placement | ○ Pending |
| OBST-03 | Phase 6 — Live Obstacle Placement | ○ Pending |
| OBST-04 | Phase 6 — Live Obstacle Placement | ○ Pending |
| VIS-05 | Phase 6 — Live Obstacle Placement | ○ Pending |
| ROAD-02 | Phase 7 — Lane Changing & Road Narrowing | ○ Pending |
| ROAD-03 | Phase 7 — Lane Changing & Road Narrowing | ○ Pending |
| ROAD-04 | Phase 7 — Lane Changing & Road Narrowing | ○ Pending |
| IXTN-01 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| IXTN-02 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| IXTN-03 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| IXTN-04 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| IXTN-07 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| VIS-04 | Phase 8 — Traffic Signals & Intersections | ○ Pending |
| CTRL-05 | Phase 9 — JSON Map Configuration & Predefined Scenarios | ○ Pending |
| IXTN-05 | Phase 10 — Roundabouts, Priority Intersections & Polish | ○ Pending |
| IXTN-06 | Phase 10 — Roundabouts, Priority Intersections & Polish | ○ Pending |
| VIS-06 | Phase 10 — Roundabouts, Priority Intersections & Polish | ○ Pending |
| MAP-01 | Phase 17 — Routing & Map Embed | ○ Pending |
| MAP-02 | Phase 17 — Routing & Map Embed | ○ Pending |
| MAP-03 | Phase 17 — Routing & Map Embed | ○ Pending |
| MAP-04 | Phase 17 — Routing & Map Embed | ○ Pending |
| OSM-01 | Phase 18 — OSM Data Pipeline | ○ Pending |
| OSM-02 | Phase 18 — OSM Data Pipeline | ○ Pending |
| OSM-03 | Phase 18 — OSM Data Pipeline | ○ Pending |
| OSM-04 | Phase 18 — OSM Data Pipeline | ○ Pending |
| SINT-01 | Phase 19 — Simulation Integration & Export | ○ Pending |
| SINT-02 | Phase 19 — Simulation Integration & Export | ○ Pending |
| SINT-03 | Phase 19 — Simulation Integration & Export | ○ Pending |
| AVI-01 | Phase 20 — AI Vision (Claude CLI) | ○ Pending |
| AVI-02 | Phase 20 — AI Vision (Claude CLI) | ○ Pending |

**Coverage:**
- v1 requirements: 33 total — mapped: 33 ✓
- v2.0 requirements: 13 total — mapped: 13 ✓
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-27*
*Last updated: 2026-04-10 — v2.0 traceability added (phases 17–20)*
