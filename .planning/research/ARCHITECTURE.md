# Architecture — Traffic Simulator

*Research document. Stack: Java 17 + Spring Boot 3.x · React 18 + TypeScript + Vite · WebSocket (STOMP/SockJS) · HTML5 Canvas*

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        BROWSER (Client)                         │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐ │
│  │  Canvas       │  │  Controls    │  │  StatsPanel           │ │
│  │  Renderer     │  │  Panel       │  │                       │ │
│  │               │  │  start/stop  │  │  avg speed            │ │
│  │  draw roads   │  │  speed mult  │  │  density              │ │
│  │  draw vehicles│  │  spawn rate  │  │  throughput           │ │
│  │  draw lights  │  │  obstacles   │  │  tick rate            │ │
│  │  draw stats   │  │  light cycle │  │                       │ │
│  └──────┬───────┘  └──────┬───────┘  └───────────────────────┘ │
│         │                 │                    ▲                 │
│         │    useSimulation hook (state store)  │                 │
│         └────────────┬────┘────────────────────┘                │
│                      │                                          │
│              useWebSocket hook                                  │
│                      │ SockJS + STOMP                           │
└──────────────────────┼──────────────────────────────────────────┘
                       │
          ┌────────────┴────────────┐
          │   WebSocket Layer       │
          │                         │
          │  /topic/state  (push)   │
          │  /app/command  (recv)   │
          │  /app/map      (recv)   │
          └────────────┬────────────┘
                       │
┌──────────────────────┼──────────────────────────────────────────┐
│                   BACKEND (Spring Boot)                         │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │               API / WebSocket Layer                      │   │
│  │                                                          │   │
│  │  SimulationController  MapController  WebSocketConfig   │   │
│  │  (REST: /api/maps)     (REST: maps)   (STOMP broker)    │   │
│  │  CommandHandler        StatePublisher                    │   │
│  └─────────────────────────┬────────────────────────────────┘   │
│                            │                                    │
│  ┌─────────────────────────▼────────────────────────────────┐   │
│  │               Simulation Engine Layer                    │   │
│  │                                                          │   │
│  │  SimulationEngine  ──► ScheduledExecutorService          │   │
│  │     (tick loop)         (configurable Hz)                │   │
│  │         │                                                │   │
│  │         ├──► PhysicsEngine  (IDM acceleration model)     │   │
│  │         ├──► LaneChangeEngine  (MOBIL model)             │   │
│  │         ├──► CollisionDetector  (bounding boxes)         │   │
│  │         ├──► TrafficLightController  (cycle state)       │   │
│  │         ├──► IntersectionManager  (priority rules)       │   │
│  │         ├──► RoundaboutManager  (yield logic)            │   │
│  │         ├──► VehicleSpawner  (spawn rate, despawn)       │   │
│  │         └──► ObstacleManager  (add/remove at runtime)    │   │
│  └─────────────────────────┬────────────────────────────────┘   │
│                            │                                    │
│  ┌─────────────────────────▼────────────────────────────────┐   │
│  │               Domain Model Layer                         │   │
│  │                                                          │   │
│  │  RoadNetwork  Road  Lane  Intersection  Roundabout       │   │
│  │  Vehicle  TrafficLight  Obstacle  SimulationConfig       │   │
│  │  MapConfig  (loaded from JSON at startup / on demand)    │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Responsibilities

| Component | Layer | Responsibility |
|-----------|-------|----------------|
| `CanvasRenderer` | Frontend | Draws road network, vehicles, traffic lights, obstacles each animation frame. Reads from simulation state store. |
| `ControlsPanel` | Frontend | Emits user commands (start/stop/pause, speed multiplier, spawn rate, obstacle placement, light cycle). |
| `StatsPanel` | Frontend | Displays live metrics (avg speed, density, throughput) from simulation state. |
| `useWebSocket` | Frontend | Manages STOMP/SockJS connection lifecycle, subscribes to `/topic/state`, sends to `/app/command`. |
| `useSimulation` | Frontend | Central state store — receives WebSocket snapshots, exposes read state to Canvas and Stats. |
| `WebSocketConfig` | Backend | Registers STOMP message broker, endpoint `/ws`, enables `/topic` broker relay and `/app` prefix. |
| `SimulationController` | Backend | REST endpoints: load map, list presets, get/set config. |
| `CommandHandler` | Backend | Handles STOMP messages on `/app/command` — delegates to `SimulationEngine`. |
| `StatePublisher` | Backend | Converts `SimulationState` snapshot to DTO after each tick, broadcasts to `/topic/state`. |
| `SimulationEngine` | Backend | Orchestrates tick loop. Advances all subsystems in order each tick. Thread-safe state mutations. |
| `PhysicsEngine` | Backend | Computes IDM acceleration for each vehicle based on headway and relative speed. Integrates position. |
| `LaneChangeEngine` | Backend | MOBIL lane-change model — evaluates incentive and safety criteria for lane changes. |
| `CollisionDetector` | Backend | Axis-aligned bounding box overlap checks. Prevents vehicles from passing through obstacles. |
| `TrafficLightController` | Backend | Advances signal phase timers each tick. Exposes green/yellow/red state per phase. |
| `IntersectionManager` | Backend | Implements right-of-way priority rules. Queues vehicles waiting to enter intersection. |
| `RoundaboutManager` | Backend | Yield-on-entry logic; vehicles inside have priority. Manages entry slots. |
| `VehicleSpawner` | Backend | Creates vehicles at road entry points at configured spawn rate. Assigns randomised IDM parameters. |
| `ObstacleManager` | Backend | Adds/removes `Obstacle` objects from lanes at runtime. Notifies PhysicsEngine of new blockage. |
| `MapLoader` | Backend | Parses `MapConfig` JSON, constructs `RoadNetwork` graph. |

---

## 3. Recommended Project Structure

### Backend — single Maven module

```
traffic-simulator-backend/
├── pom.xml
└── src/main/java/com/example/trafficsimulator/
    │
    ├── model/
    │   ├── Vehicle.java              # id, position, speed, acceleration, lane, IDM params
    │   ├── Lane.java                 # laneIndex, direction, width, list of vehicles
    │   ├── Road.java                 # id, lanes[], length, start/end node
    │   ├── Intersection.java         # connected roads, priority rules, waiting queue
    │   ├── Roundabout.java           # entry roads, internal lanes, yield slots
    │   ├── TrafficLight.java         # phases, cycle durations, current phase
    │   ├── Obstacle.java             # roadId, laneIndex, position, size
    │   └── RoadNetwork.java          # graph of roads and nodes
    │
    ├── engine/
    │   ├── SimulationEngine.java     # tick loop, ScheduledExecutorService, state lock
    │   ├── PhysicsEngine.java        # IDM: a = f(headway, deltaV, desiredSpeed)
    │   ├── LaneChangeEngine.java     # MOBIL: incentive + safety criteria
    │   ├── CollisionDetector.java    # AABB checks, obstacle clearance
    │   ├── TrafficLightController.java
    │   ├── IntersectionManager.java
    │   ├── RoundaboutManager.java
    │   ├── VehicleSpawner.java
    │   └── ObstacleManager.java
    │
    ├── config/
    │   ├── MapConfig.java            # POJO matching map JSON schema
    │   ├── MapLoader.java            # Jackson JSON → RoadNetwork
    │   ├── SimulationConfig.java     # tick rate, spawn rate, speed multiplier
    │   └── WebSocketConfig.java      # Spring STOMP broker registration
    │
    ├── controller/
    │   ├── SimulationController.java # REST: GET /api/maps, POST /api/simulation/load
    │   └── CommandHandler.java       # @MessageMapping("/command")
    │
    ├── service/
    │   └── StatePublisher.java       # post-tick: build DTO, SimpMessagingTemplate.convertAndSend
    │
    └── dto/
        ├── SimulationStateDto.java   # full snapshot: tick, vehicles[], lights[], stats
        ├── VehicleDto.java           # x, y, angle, speed, laneId
        ├── TrafficLightDto.java      # id, phase, remainingMs
        ├── StatsDto.java             # avgSpeed, density, throughput
        └── CommandDto.java           # type (START/STOP/PAUSE/SET_SPEED/ADD_OBSTACLE/...), payload
```

### Frontend — Vite + React

```
traffic-simulator-frontend/
├── vite.config.ts
├── package.json
└── src/
    ├── main.tsx
    ├── App.tsx
    │
    ├── components/
    │   ├── Canvas.tsx                # useRef<HTMLCanvasElement>, requestAnimationFrame loop
    │   ├── CanvasRenderer.ts         # pure rendering logic: drawRoads, drawVehicles, drawLights
    │   ├── ControlsPanel.tsx         # start/stop/pause, speed slider, spawn rate, obstacle tool
    │   └── StatsPanel.tsx            # avg speed, density, throughput display
    │
    ├── hooks/
    │   ├── useWebSocket.ts           # SockJS + @stomp/stompjs, connect/disconnect, send
    │   └── useSimulation.ts          # state store — receives snapshots, exposes read-only state
    │
    └── types/
        ├── SimulationState.ts        # mirrors SimulationStateDto
        ├── Vehicle.ts                # VehicleDto shape
        ├── TrafficLight.ts
        ├── Road.ts                   # static road network (received once on map load)
        └── Command.ts                # outbound command payload types
```

### Map Config JSON schema (example)

```json
{
  "id": "highway-merge",
  "roads": [
    { "id": "r1", "lanes": 3, "length": 800, "from": "n1", "to": "n2", "speedLimit": 120 }
  ],
  "nodes": [
    { "id": "n1", "type": "ENTRY" },
    { "id": "n2", "type": "INTERSECTION", "priority": "RIGHT_OF_WAY" }
  ],
  "trafficLights": [
    { "nodeId": "n2", "phases": [{"roads": ["r1"], "green": 30, "yellow": 3}] }
  ],
  "obstacles": []
}
```

---

## 4. Architectural Patterns

### Tick-Based Simulation Loop

The backend owns simulation time. A `ScheduledExecutorService` fires at a fixed interval (default 50 ms = 20 Hz). Each tick:

1. Acquire write lock on simulation state.
2. Advance `TrafficLightController` by one tick duration.
3. Run `VehicleSpawner` — potentially add new vehicles.
4. Run `PhysicsEngine.tick()` — update acceleration and speed for every vehicle.
5. Run `LaneChangeEngine.tick()` — evaluate and execute lane changes.
6. Run `CollisionDetector.tick()` — resolve any overlaps, enforce obstacle stops.
7. Run `IntersectionManager.tick()` / `RoundaboutManager.tick()`.
8. Despawn vehicles that have exited the road network.
9. Build `SimulationStateDto` snapshot.
10. Release lock.
11. `StatePublisher` broadcasts snapshot to `/topic/state`.

The tick interval is configurable at runtime via a `SET_SPEED` command (speed multiplier scales the logical dt, not the wall-clock interval, to keep WebSocket cadence stable).

### Car-Following Model — IDM (Intelligent Driver Model)

IDM acceleration for vehicle `i` following vehicle `i-1`:

```
a_i = a_max * [ 1 - (v_i / v_0)^4 - (s*(v_i, Δv) / gap_i)^2 ]

s*(v_i, Δv) = s_0 + v_i*T + v_i*Δv / (2*sqrt(a_max*b))
```

| Parameter | Symbol | Typical value |
|-----------|--------|---------------|
| Desired speed | v_0 | 100–130 km/h |
| Max acceleration | a_max | 1.0–2.0 m/s² |
| Comfortable deceleration | b | 1.5–3.0 m/s² |
| Minimum gap | s_0 | 2 m |
| Time headway | T | 1.5 s |

Each `Vehicle` carries its own randomised IDM parameter set (±20% noise on v_0, a_max, b) to produce heterogeneous traffic and emergent phantom jams.

### Lane Change — MOBIL Model

MOBIL (Minimising Overall Braking Induced by Lane Changes) evaluates:
- **Incentive**: acceleration gain for the changing vehicle minus a politeness factor times the braking imposed on the new follower.
- **Safety**: new follower must not be forced to brake harder than a safe deceleration threshold.

Lane changes are discrete events; a vehicle is locked to its current lane for a minimum of N ticks after changing.

### Observer Pattern — WebSocket State Broadcasting

`SimulationEngine` fires an event after each tick. `StatePublisher` is registered as an observer/listener. It serialises the current state to `SimulationStateDto` (Jackson) and calls `SimpMessagingTemplate.convertAndSend("/topic/state", dto)`. The frontend STOMP subscription receives this message and dispatches it into the `useSimulation` state store. The Canvas `requestAnimationFrame` loop reads the latest state on each frame — decoupled from the WebSocket message rate.

### Command Pattern — User Controls

All user interactions are serialised as `CommandDto` objects with a `type` discriminator:

| Command type | Payload | Effect |
|--------------|---------|--------|
| `START` | — | `SimulationEngine.start()` |
| `STOP` | — | `SimulationEngine.stop()` |
| `PAUSE` | — | `SimulationEngine.pause()` |
| `SET_SPEED_MULTIPLIER` | `{multiplier: 2.0}` | Scales logical dt |
| `SET_SPAWN_RATE` | `{vehiclesPerSecond: 5}` | Updates `VehicleSpawner` |
| `ADD_OBSTACLE` | `{roadId, laneIndex, position}` | `ObstacleManager.add()` |
| `REMOVE_OBSTACLE` | `{obstacleId}` | `ObstacleManager.remove()` |
| `SET_LIGHT_CYCLE` | `{nodeId, phases: [...]}` | `TrafficLightController.reconfigure()` |
| `LOAD_MAP` | `{mapId}` | Reset engine, load new `MapConfig` |

Commands arrive on `/app/command` (STOMP), handled by `CommandHandler`, which delegates to the appropriate engine method. The engine is thread-safe; commands mutate configuration under the same lock as the tick loop.

---

## 5. Data Flow

### Simulation Tick → Canvas Render

```
ScheduledExecutorService (50 ms interval)
    │
    ▼
SimulationEngine.tick()
    │  mutates in-place: vehicle positions, speeds, light phases
    │
    ▼
StatePublisher.onTickComplete(state)
    │  serialize → SimulationStateDto (Jackson, ~5–20 KB JSON for 200 vehicles)
    │
    ▼
SimpMessagingTemplate.convertAndSend("/topic/state", dto)
    │  WebSocket frame
    │
    ▼
useWebSocket.onMessage(frame)
    │  deserialize JSON → SimulationState
    │
    ▼
useSimulation.setState(newState)
    │  React state update (triggers no re-render — Canvas reads ref)
    │
    ▼
requestAnimationFrame callback
    │  reads simulationStateRef.current
    │
    ▼
CanvasRenderer.draw(ctx, state)
    │  clearRect, drawRoads, drawVehicles (rectangles), drawLights, drawObstacles
    │
    ▼
Display (60 fps visual, 20 Hz data updates)
```

Key design: `useSimulation` stores state in a `useRef` (not `useState`) to avoid React re-render overhead on every WebSocket message. The Canvas loop reads the ref directly — smooth 60 fps rendering regardless of tick rate.

### User Control → Engine Update

```
User interacts with ControlsPanel
    │
    ▼
CommandDto constructed in TypeScript
    │  e.g. { type: "ADD_OBSTACLE", payload: { roadId: "r1", laneIndex: 1, position: 400 } }
    │
    ▼
useWebSocket.send("/app/command", commandDto)
    │  STOMP send frame
    │
    ▼
CommandHandler.handleCommand(@Payload CommandDto)
    │  @MessageMapping("/command")
    │
    ▼
SimulationEngine.applyCommand(CommandDto)
    │  acquire lock, mutate config/state, release lock
    │
    ▼
Effect visible in next tick's StateDto → broadcast → Canvas
```

---

## 6. Scaling Considerations

### Vehicle Count vs. Tick Rate

| Vehicles | Tick rate | Est. CPU (single core) | JSON payload | Notes |
|----------|-----------|----------------------|--------------|-------|
| 50 | 20 Hz | ~2% | ~2 KB | Trivial, any hardware |
| 200 | 20 Hz | ~8% | ~8 KB | Comfortable target |
| 500 | 20 Hz | ~20% | ~20 KB | Achievable, monitor GC |
| 1000 | 20 Hz | ~45% | ~40 KB | Feasible, consider delta compression |
| 200 | 60 Hz | ~25% | ~480 KB/s | High cadence — may skip unchanged state |

### Bottlenecks and Mitigations

**Serialisation cost**: At 200 vehicles × 20 Hz, the backend serialises ~160 KB/s of JSON. Jackson with pre-registered modules is fast enough. If payload size becomes a concern, switch to binary (MessagePack via Jackson Dataformat) or send delta diffs (only changed vehicles).

**IDM computation**: O(n) per tick — each vehicle checks only its immediate leader. No global pairwise comparison needed. For 1000 vehicles this is ~1000 floating-point computations — negligible.

**Collision detection**: Spatial indexing (simple lane-bucketed sorted list by position) keeps this O(n) per lane rather than O(n²) global.

**Canvas rendering**: The frontend paints every vehicle as a `fillRect` + `rotate` each frame. At 200 vehicles and 60 fps this is ~12,000 canvas operations/s — well within browser limits. Above 500 vehicles consider OffscreenCanvas with a Web Worker.

**WebSocket bandwidth**: At 20 Hz with 200 vehicles and ~8 KB per message, throughput is ~160 KB/s — trivial for a local or LAN connection. For remote access (Tailscale), this remains comfortable under typical latency.

**Recommended production target**: 200 vehicles, 20 Hz tick rate, single-core backend thread. This is the sweet spot for visible phantom jam emergent behaviour without engineering complexity.

### Tick Rate vs. Visual Fidelity

The simulation tick rate (20 Hz) and the render frame rate (60 fps) are intentionally decoupled. The frontend interpolates vehicle positions between the last two received snapshots to produce smooth 60 fps motion even when ticks arrive at 20 Hz. This interpolation is linear: `pos = lastPos + (nextPos - lastPos) * alpha` where `alpha = elapsedSinceLastTick / tickInterval`.

---

*Document written: 2026-03-27*
