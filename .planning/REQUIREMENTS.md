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

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

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
| OpenStreetMap import | High complexity, JSON config is sufficient |
| Accident simulation | Out of scope — focus is on congestion, not collisions |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| (populated during roadmap creation) | | |

**Coverage:**
- v1 requirements: 33 total
- Mapped to phases: 0
- Unmapped: 33 ⚠️

---
*Requirements defined: 2026-03-27*
*Last updated: 2026-03-27 after initial definition*
