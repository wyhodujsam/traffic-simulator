# Phase 2 Research: Domain Model & Road Network Foundation

**Phase:** 2 — Domain Model & Road Network Foundation
**Goal:** Define all core domain objects and wire JSON-based road loading with a hardcoded single-road fixture.
**Requirements:** ROAD-01, ROAD-05, ROAD-06, SIM-02, SIM-05, SIM-06, INFR-04
**Researched:** 2026-03-27

---

## Context: What Phase 1 Delivered

Phase 1 established the working WebSocket pipeline. The relevant existing code:

- `TrafficSimulatorApplication.java` — `@SpringBootApplication @EnableScheduling`, package `com.trafficsimulator`
- `TickEmitter.java` — `@Scheduled(fixedRate = 50)` emits `TickDto` to `/topic/simulation` at 20 Hz
- `TickDto.java` — `{long tick, long timestamp}` — minimal, carries no simulation state yet
- `WebSocketConfig.java` — STOMP broker on `/topic`, app prefix `/app`, endpoint `/ws` with SockJS

Phase 2 does not touch WebSocket wiring. It defines the domain model and makes simulation state available so that Phase 4 (when the real tick loop is wired) can broadcast vehicle positions.

---

## 1. Domain Model Design

### 1.1 Vehicle

`Vehicle` is the central simulation entity. It carries both its physical state (position, speed) and its IDM behavioural parameters, which are assigned at spawn time and remain constant for that vehicle's lifetime.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | Unique per vehicle, used in DTOs |
| `position` | `double` | Metres from lane start. Range [0, lane.length] |
| `speed` | `double` | m/s. Must be clamped to [0, maxSpeed] |
| `acceleration` | `double` | m/s². Updated each tick by PhysicsEngine. Stored to allow smooth despawn |
| `lane` | `Lane` | Direct reference — PhysicsEngine uses lane.getVehiclesAhead(this) |
| `length` | `double` | Physical length in metres (default 4.5 m). Used for gap calculation |
| `v0` | `double` | IDM: desired speed in m/s. Base ~33 m/s (120 km/h), ±20% noise |
| `aMax` | `double` | IDM: maximum acceleration in m/s². Base 1.4, ±20% noise |
| `b` | `double` | IDM: comfortable braking deceleration in m/s². Base 2.0, ±20% noise |
| `s0` | `double` | IDM: minimum gap in metres. Base 2.0 (not randomised — safety critical) |
| `T` | `double` | IDM: desired time headway in seconds. Base 1.5, ±20% noise |
| `spawnedAt` | `long` | Tick number when vehicle was created. Used for throughput stats |

**Design notes:**
- `lane` is a live reference, not an ID. During Phase 7 (lane changes), the reference is atomically swapped. For Phase 2 it is set once at spawn.
- No `x, y` pixel coordinates on the domain object. Pixel projection is a DTO/rendering concern. The domain uses 1D position along the lane.
- `acceleration` is transient per-tick state. It does not need to be serialised in snapshots (only position and speed matter for rendering).
- Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` reduces boilerplate. The `@Builder` pattern works naturally with the randomised spawner.

```java
// package com.trafficsimulator.model
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {
    private String id;
    private double position;      // metres from lane start
    private double speed;         // m/s
    private double acceleration;  // m/s² (transient, updated each tick)
    private Lane lane;            // live reference
    private double length;        // metres, default 4.5
    // IDM parameters
    private double v0;    // desired speed m/s
    private double aMax;  // max acceleration m/s²
    private double b;     // comfortable braking m/s²
    private double s0;    // minimum gap metres
    private double T;     // time headway seconds
    private long spawnedAt; // tick number
}
```

### 1.2 Lane

`Lane` owns an ordered list of vehicles sorted by position (ascending). The sort order is critical: IDM needs to find the vehicle directly ahead of any given vehicle in O(log n) via binary search.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Globally unique: `"r1-lane0"`, `"r1-lane1"` etc. |
| `laneIndex` | `int` | 0-based index within the parent road |
| `road` | `Road` | Back-reference to parent road |
| `length` | `double` | Metres. Copied from road at construction |
| `maxSpeed` | `double` | m/s. May differ from road speedLimit (e.g., slow lane) |
| `vehicles` | `List<Vehicle>` | Sorted by position ascending. `CopyOnWriteArrayList` for safe iteration; mutations under simulation lock |
| `active` | `boolean` | For Phase 7 road narrowing — lanes can be deactivated mid-sim |

**Finding the leader vehicle:** Given vehicle V at position p, its leader is the vehicle in `vehicles` with the smallest position > p. With a sorted list this is `Collections.binarySearch` or a linear scan from the end. For Phase 2 (simple prototype), a linear scan in O(n) is acceptable; spatial indexing is a Phase 3 optimisation.

```java
// package com.trafficsimulator.model
@Data
@Builder
public class Lane {
    private String id;
    private int laneIndex;
    private Road road;
    private double length;
    private double maxSpeed;
    private boolean active;
    @Builder.Default
    private List<Vehicle> vehicles = new ArrayList<>();

    /** Returns the vehicle directly ahead of the given vehicle, or null if none. */
    public Vehicle getLeader(Vehicle vehicle) {
        Vehicle leader = null;
        for (Vehicle v : vehicles) {
            if (v != vehicle && v.getPosition() > vehicle.getPosition()) {
                if (leader == null || v.getPosition() < leader.getPosition()) {
                    leader = v;
                }
            }
        }
        return leader;
    }
}
```

### 1.3 Road

`Road` is the primary structural element. It holds an ordered list of `Lane` objects and knows its start/end coordinates for rendering.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Matches JSON config (e.g., `"r1"`) |
| `name` | `String` | Human-readable label |
| `lanes` | `List<Lane>` | Index-ordered (lane 0 = rightmost/slowest) |
| `length` | `double` | Metres. All lanes inherit this length |
| `speedLimit` | `double` | m/s. Base cap; lanes may be lower |
| `startX`, `startY` | `double` | Canvas/world coordinates of road start node |
| `endX`, `endY` | `double` | Canvas/world coordinates of road end node |
| `fromNodeId` | `String` | Connected node ID (for graph routing in Phase 8+) |
| `toNodeId` | `String` | Connected node ID |

**Design note:** `startX/Y`, `endX/Y` are computed from node coordinates during `MapLoader` construction, not stored directly in the JSON. The JSON stores node references; `MapLoader` resolves coordinates. This avoids redundancy and keeps the JSON compact.

```java
// package com.trafficsimulator.model
@Data
@Builder
public class Road {
    private String id;
    private String name;
    private List<Lane> lanes;
    private double length;
    private double speedLimit;   // m/s
    private double startX;
    private double startY;
    private double endX;
    private double endY;
    private String fromNodeId;
    private String toNodeId;
}
```

### 1.4 RoadNetwork

`RoadNetwork` is the top-level simulation context object. It is constructed by `MapLoader` and held by `SimulationEngine`. It never changes structure at runtime in Phase 2 (structural changes come in Phase 7+).

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Map scenario id (e.g., `"straight-road"`) |
| `roads` | `Map<String, Road>` | Keyed by road ID for O(1) lookup |
| `intersections` | `Map<String, Intersection>` | Empty in Phase 2 (single-road fixture has no intersections) |
| `spawnPoints` | `List<SpawnPoint>` | Entry points where VehicleSpawner creates vehicles |
| `despawnPoints` | `List<DespawnPoint>` | Exit points where vehicles are removed |

`SpawnPoint` and `DespawnPoint` are simple value types:

```java
// SpawnPoint: where new vehicles are created
public record SpawnPoint(String roadId, int laneIndex, double position) {}

// DespawnPoint: where vehicles reaching this position are removed
public record DespawnPoint(String roadId, int laneIndex, double position) {}
```

Records are used here (Java 17) because spawn/despawn points are pure data with no behaviour.

### 1.5 Intersection

`Intersection` is defined in Phase 2 but left structurally empty (no logic) — the single-road fixture has no intersections. It must exist in the domain model for `RoadNetwork` to compile and for `MapLoader` to parse map configs that include intersection nodes.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Node ID from map config |
| `type` | `IntersectionType` (enum) | `SIGNAL`, `ROUNDABOUT`, `PRIORITY`, `NONE` |
| `connectedRoadIds` | `List<String>` | Roads entering this intersection |
| `trafficLight` | `TrafficLight` | Null in Phase 2; populated in Phase 8 |

```java
public enum IntersectionType { SIGNAL, ROUNDABOUT, PRIORITY, NONE }

@Data
@Builder
public class Intersection {
    private String id;
    private IntersectionType type;
    @Builder.Default
    private List<String> connectedRoadIds = new ArrayList<>();
    private TrafficLight trafficLight; // null in Phase 2
}
```

---

## 2. JSON Map Configuration Schema

### 2.1 Design Principles

The JSON schema must:
- Be human-readable and editable (used by developers to create scenarios)
- Separate structural elements (roads, nodes) from behavioural elements (spawn rates, signal timings)
- Use normalised node references rather than duplicating coordinates in every road
- Be forward-compatible with Phase 8 intersections and Phase 9 predefined scenarios

### 2.2 Full Schema

```json
{
  "id": "straight-road",
  "name": "Straight Road (3 lanes, 800m)",
  "nodes": [
    {
      "id": "n1",
      "type": "ENTRY",
      "x": 50.0,
      "y": 300.0
    },
    {
      "id": "n2",
      "type": "EXIT",
      "x": 850.0,
      "y": 300.0
    }
  ],
  "roads": [
    {
      "id": "r1",
      "name": "Main Road",
      "fromNodeId": "n1",
      "toNodeId": "n2",
      "length": 800.0,
      "speedLimit": 33.3,
      "laneCount": 3
    }
  ],
  "intersections": [],
  "spawnPoints": [
    { "roadId": "r1", "laneIndex": 0, "position": 0.0 },
    { "roadId": "r1", "laneIndex": 1, "position": 0.0 },
    { "roadId": "r1", "laneIndex": 2, "position": 0.0 }
  ],
  "despawnPoints": [
    { "roadId": "r1", "laneIndex": 0, "position": 800.0 },
    { "roadId": "r1", "laneIndex": 1, "position": 800.0 },
    { "roadId": "r1", "laneIndex": 2, "position": 800.0 }
  ],
  "defaultSpawnRate": 1.0
}
```

**Field glossary:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Unique scenario identifier |
| `name` | string | yes | Display name for UI selector (Phase 9) |
| `nodes[].id` | string | yes | Referenced by roads and intersections |
| `nodes[].type` | string | yes | `ENTRY`, `EXIT`, `INTERSECTION` |
| `nodes[].x`, `.y` | double | yes | World/canvas coordinates in pixels |
| `roads[].id` | string | yes | Referenced by spawn/despawn points |
| `roads[].fromNodeId` | string | yes | Must match a node id |
| `roads[].toNodeId` | string | yes | Must match a node id |
| `roads[].length` | double | yes | Metres |
| `roads[].speedLimit` | double | yes | m/s (33.3 = 120 km/h) |
| `roads[].laneCount` | int | yes | 2–4 per ROAD-01 |
| `intersections[]` | array | yes | Empty for Phase 2; populated in Phase 8 |
| `spawnPoints[]` | array | yes | One entry per lane typically |
| `despawnPoints[]` | array | yes | At road end per lane |
| `defaultSpawnRate` | double | no | Vehicles/second; default 1.0 if absent |

### 2.3 MapConfig POJO Structure

The Jackson POJO mirrors the JSON 1:1. All fields use `@JsonProperty` for explicit mapping (avoids camelCase/snake_case surprises).

```java
// package com.trafficsimulator.config

@Data
@NoArgsConstructor
public class MapConfig {
    @JsonProperty("id")          private String id;
    @JsonProperty("name")        private String name;
    @JsonProperty("nodes")       private List<NodeConfig> nodes;
    @JsonProperty("roads")       private List<RoadConfig> roads;
    @JsonProperty("intersections") private List<IntersectionConfig> intersections;
    @JsonProperty("spawnPoints") private List<SpawnPointConfig> spawnPoints;
    @JsonProperty("despawnPoints") private List<DespawnPointConfig> despawnPoints;
    @JsonProperty("defaultSpawnRate") private double defaultSpawnRate = 1.0;

    @Data @NoArgsConstructor
    public static class NodeConfig {
        private String id;
        private String type;  // "ENTRY", "EXIT", "INTERSECTION"
        private double x;
        private double y;
    }

    @Data @NoArgsConstructor
    public static class RoadConfig {
        private String id;
        private String name;
        private String fromNodeId;
        private String toNodeId;
        private double length;
        private double speedLimit;
        private int laneCount;
    }

    @Data @NoArgsConstructor
    public static class IntersectionConfig {
        private String nodeId;
        private String type;  // "SIGNAL", "ROUNDABOUT", "PRIORITY"
        // Phase 8 will add signal timings here
    }

    @Data @NoArgsConstructor
    public static class SpawnPointConfig {
        private String roadId;
        private int laneIndex;
        private double position;
    }

    @Data @NoArgsConstructor
    public static class DespawnPointConfig {
        private String roadId;
        private int laneIndex;
        private double position;
    }
}
```

---

## 3. Command Queue Pattern (INFR-04)

### 3.1 Design Goal

The simulation tick loop runs on a `ScheduledExecutorService` thread. User commands arrive on Spring's WebSocket thread pool (potentially multiple threads). Direct mutations of simulation state from command handlers would cause `ConcurrentModificationException` or stale reads.

The command queue pattern solves this by making all state mutations happen exclusively on the tick thread: commands are enqueued by handlers and drained at the start of each tick.

### 3.2 SimulationCommand Sealed Interface

Java 17 sealed interfaces with `record` implementations provide exhaustive pattern matching and type-safe command dispatch.

```java
// package com.trafficsimulator.engine.command

public sealed interface SimulationCommand
    permits SimulationCommand.Start,
            SimulationCommand.Stop,
            SimulationCommand.Pause,
            SimulationCommand.Resume,
            SimulationCommand.SetSpawnRate,
            SimulationCommand.SetSpeedMultiplier,
            SimulationCommand.LoadMap {

    record Start()                                      implements SimulationCommand {}
    record Stop()                                       implements SimulationCommand {}
    record Pause()                                      implements SimulationCommand {}
    record Resume()                                     implements SimulationCommand {}
    record SetSpawnRate(double vehiclesPerSecond)        implements SimulationCommand {}
    record SetSpeedMultiplier(double multiplier)        implements SimulationCommand {}
    record LoadMap(String mapId)                        implements SimulationCommand {}
}
```

Future phases extend this: `AddObstacle`, `RemoveObstacle`, `SetLightCycle`, `ReduceLanes` are added in Phases 6–8 without touching the core queue plumbing.

### 3.3 CommandHandler (STOMP Layer)

`CommandHandler` receives `CommandDto` from `/app/command` and translates to typed `SimulationCommand`, then enqueues it. It never touches simulation state directly.

```java
// package com.trafficsimulator.controller

@Controller
@RequiredArgsConstructor
public class CommandHandler {

    private final SimulationEngine simulationEngine;

    @MessageMapping("/command")
    public void handleCommand(@Payload CommandDto dto) {
        SimulationCommand command = switch (dto.getType()) {
            case "START"               -> new SimulationCommand.Start();
            case "STOP"                -> new SimulationCommand.Stop();
            case "PAUSE"               -> new SimulationCommand.Pause();
            case "RESUME"              -> new SimulationCommand.Resume();
            case "SET_SPAWN_RATE"      -> new SimulationCommand.SetSpawnRate(dto.getSpawnRate());
            case "SET_SPEED_MULTIPLIER"-> new SimulationCommand.SetSpeedMultiplier(dto.getMultiplier());
            default -> throw new IllegalArgumentException("Unknown command: " + dto.getType());
        };
        simulationEngine.enqueue(command);
    }
}
```

### 3.4 Queue Wiring in SimulationEngine

```java
// package com.trafficsimulator.engine

@Component
@RequiredArgsConstructor
public class SimulationEngine {

    private final LinkedBlockingQueue<SimulationCommand> commandQueue =
        new LinkedBlockingQueue<>();

    private volatile SimulationStatus status = SimulationStatus.STOPPED;

    public void enqueue(SimulationCommand command) {
        commandQueue.offer(command);  // non-blocking; queue is unbounded
    }

    // Called each tick BEFORE any simulation logic
    private void drainCommands() {
        List<SimulationCommand> pending = new ArrayList<>();
        commandQueue.drainTo(pending);
        for (SimulationCommand cmd : pending) {
            applyCommand(cmd);
        }
    }

    private void applyCommand(SimulationCommand cmd) {
        switch (cmd) {
            case SimulationCommand.Start()     -> status = SimulationStatus.RUNNING;
            case SimulationCommand.Stop()      -> status = SimulationStatus.STOPPED;
            case SimulationCommand.Pause()     -> status = SimulationStatus.PAUSED;
            case SimulationCommand.Resume()    -> status = SimulationStatus.RUNNING;
            case SimulationCommand.SetSpawnRate(double rate) -> vehicleSpawner.setRate(rate);
            case SimulationCommand.SetSpeedMultiplier(double m) -> config.setSpeedMultiplier(m);
            case SimulationCommand.LoadMap(String id) -> loadMap(id);
            default -> log.warn("Unhandled command: {}", cmd);
        }
    }
}
```

**Thread safety guarantee:** `drainCommands()` is called at the top of the tick method, which runs on the single tick thread. All `applyCommand` mutations happen exclusively on that thread. No lock is needed for the domain state itself. `LinkedBlockingQueue` is intrinsically thread-safe for concurrent producers.

**Why `drainTo` not `poll` in a loop:** `drainTo` atomically moves all available elements into the local list in a single lock acquisition, minimising queue contention when commands arrive in bursts.

---

## 4. VehicleSpawner Design

### 4.1 Spawn Rate Mechanics

The spawner operates in terms of vehicle-seconds accumulation. Each tick adds `dt * rate` to an accumulator. When it crosses 1.0, one vehicle is spawned and 1.0 is subtracted. This gives accurate spawn rates at any tick frequency without floating-point drift.

```java
private double spawnAccumulator = 0.0;
private double vehiclesPerSecond = 1.0; // configurable via command queue

public void tick(double dt, RoadNetwork network) {
    spawnAccumulator += dt * vehiclesPerSecond;
    while (spawnAccumulator >= 1.0) {
        spawnAccumulator -= 1.0;
        trySpawnOne(network);
    }
}
```

### 4.2 Spawn Point Selection

At Phase 2, one vehicle is distributed across spawn points in round-robin order. This ensures all lanes receive vehicles. A future improvement (Phase 5) could weight spawning by lane capacity.

### 4.3 Overlap Prevention

Before spawning, check that the entry position (0.0 m) is clear by at least `s0 + vehicleLength` from the nearest vehicle in the target lane. If blocked, skip the spawn attempt (accumulator keeps its value — next tick it may succeed).

```java
private boolean isSpawnPositionClear(Lane lane, double spawnPosition) {
    double minSafeGap = DEFAULT_S0 + DEFAULT_VEHICLE_LENGTH; // 2.0 + 4.5 = 6.5 m
    return lane.getVehicles().stream()
        .noneMatch(v -> Math.abs(v.getPosition() - spawnPosition) < minSafeGap);
}
```

### 4.4 IDM Parameter Variation

Each spawned vehicle receives base IDM parameters with ±20% uniform random noise. This produces heterogeneous traffic and is the source of emergent congestion waves.

```java
private static final double V0_BASE  = 33.3;  // m/s ~ 120 km/h
private static final double A_BASE   = 1.4;   // m/s²
private static final double B_BASE   = 2.0;   // m/s²
private static final double S0       = 2.0;   // metres (not randomised — safety)
private static final double T_BASE   = 1.5;   // seconds

private double vary(double base) {
    return base * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4); // ±20%
}

private Vehicle createVehicle(Lane lane) {
    return Vehicle.builder()
        .id(UUID.randomUUID().toString())
        .position(0.0)
        .speed(0.0)
        .acceleration(0.0)
        .lane(lane)
        .length(DEFAULT_VEHICLE_LENGTH)
        .v0(vary(V0_BASE))
        .aMax(vary(A_BASE))
        .b(vary(B_BASE))
        .s0(S0)
        .T(vary(T_BASE))
        .spawnedAt(currentTick)
        .build();
}
```

### 4.5 Despawn Logic

A vehicle despawns when its `position >= lane.getLength()`. This check runs at the end of each tick (after physics update), in the `SimulationEngine` sweep. The vehicle is removed from `lane.getVehicles()` and a despawn event is logged for throughput statistics.

```java
private void despawnVehicles(RoadNetwork network) {
    for (Road road : network.getRoads().values()) {
        for (Lane lane : road.getLanes()) {
            lane.getVehicles().removeIf(v -> {
                if (v.getPosition() >= lane.getLength()) {
                    log.debug("Vehicle {} despawned after {} ticks", v.getId(),
                              currentTick - v.getSpawnedAt());
                    return true;
                }
                return false;
            });
        }
    }
}
```

---

## 5. MapLoader with Jackson

### 5.1 Loading Strategy

`MapLoader` is a Spring `@Component` that uses `ObjectMapper` (Spring Boot autoconfigures Jackson) to parse the JSON file from the classpath. In Phase 2 it loads `maps/straight-road.json` hardcoded. Phase 9 extends it to load by ID from a configurable directory.

```java
// package com.trafficsimulator.config

@Component
@RequiredArgsConstructor
public class MapLoader {

    private final ObjectMapper objectMapper;

    public RoadNetwork loadFromClasspath(String resourcePath) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Map resource not found: " + resourcePath);
        }
        MapConfig config = objectMapper.readValue(is, MapConfig.class);
        return buildRoadNetwork(config);
    }

    private RoadNetwork buildRoadNetwork(MapConfig config) {
        // 1. Index nodes by id
        Map<String, MapConfig.NodeConfig> nodes = config.getNodes().stream()
            .collect(Collectors.toMap(MapConfig.NodeConfig::getId, n -> n));

        // 2. Build roads and lanes
        Map<String, Road> roads = new LinkedHashMap<>();
        for (MapConfig.RoadConfig rc : config.getRoads()) {
            MapConfig.NodeConfig fromNode = nodes.get(rc.getFromNodeId());
            MapConfig.NodeConfig toNode   = nodes.get(rc.getToNodeId());

            Road road = Road.builder()
                .id(rc.getId())
                .name(rc.getName())
                .length(rc.getLength())
                .speedLimit(rc.getSpeedLimit())
                .fromNodeId(rc.getFromNodeId())
                .toNodeId(rc.getToNodeId())
                .startX(fromNode.getX()).startY(fromNode.getY())
                .endX(toNode.getX()).endY(toNode.getY())
                .lanes(new ArrayList<>())
                .build();

            for (int i = 0; i < rc.getLaneCount(); i++) {
                Lane lane = Lane.builder()
                    .id(rc.getId() + "-lane" + i)
                    .laneIndex(i)
                    .road(road)
                    .length(rc.getLength())
                    .maxSpeed(rc.getSpeedLimit())
                    .active(true)
                    .build();
                road.getLanes().add(lane);
            }
            roads.put(road.getId(), road);
        }

        // 3. Build spawn/despawn points
        List<SpawnPoint> spawnPoints = config.getSpawnPoints().stream()
            .map(sp -> new SpawnPoint(sp.getRoadId(), sp.getLaneIndex(), sp.getPosition()))
            .toList();

        List<DespawnPoint> despawnPoints = config.getDespawnPoints().stream()
            .map(dp -> new DespawnPoint(dp.getRoadId(), dp.getLaneIndex(), dp.getPosition()))
            .toList();

        // 4. Build intersections (empty in Phase 2)
        Map<String, Intersection> intersections = new LinkedHashMap<>();
        for (MapConfig.IntersectionConfig ic : config.getIntersections()) {
            Intersection ixtn = Intersection.builder()
                .id(ic.getNodeId())
                .type(IntersectionType.valueOf(ic.getType()))
                .build();
            intersections.put(ixtn.getId(), ixtn);
        }

        return RoadNetwork.builder()
            .id(config.getId())
            .roads(roads)
            .intersections(intersections)
            .spawnPoints(spawnPoints)
            .despawnPoints(despawnPoints)
            .build();
    }
}
```

### 5.2 Hardcoded Fixture: straight-road.json

File location: `backend/src/main/resources/maps/straight-road.json`

```json
{
  "id": "straight-road",
  "name": "Straight Road — 3 lanes, 800m",
  "nodes": [
    { "id": "n1", "type": "ENTRY", "x": 50.0,  "y": 300.0 },
    { "id": "n2", "type": "EXIT",  "x": 850.0, "y": 300.0 }
  ],
  "roads": [
    {
      "id": "r1",
      "name": "Main Road",
      "fromNodeId": "n1",
      "toNodeId": "n2",
      "length": 800.0,
      "speedLimit": 33.3,
      "laneCount": 3
    }
  ],
  "intersections": [],
  "spawnPoints": [
    { "roadId": "r1", "laneIndex": 0, "position": 0.0 },
    { "roadId": "r1", "laneIndex": 1, "position": 0.0 },
    { "roadId": "r1", "laneIndex": 2, "position": 0.0 }
  ],
  "despawnPoints": [
    { "roadId": "r1", "laneIndex": 0, "position": 800.0 },
    { "roadId": "r1", "laneIndex": 1, "position": 800.0 },
    { "roadId": "r1", "laneIndex": 2, "position": 800.0 }
  ],
  "defaultSpawnRate": 1.0
}
```

**Rendering note:** Nodes at `x=50, y=300` and `x=850, y=300` place the road horizontally centred in a typical 900×600 canvas. The 3 lanes are rendered as parallel horizontal bands; lane spacing is a rendering concern, not encoded in the domain model.

---

## 6. TickDto Evolution

### 6.1 Current State

`TickDto` carries only `{long tick, long timestamp}`. It was sufficient for Phase 1's smoke test. Phase 2 must enrich the broadcast to carry vehicle state so that:
- Phase 3 (physics) can be validated via WebSocket frames without a frontend
- Phase 5 (Canvas rendering) has data to draw

### 6.2 Target: SimulationStateDto

`TickDto` is **replaced** by `SimulationStateDto` — a richer snapshot. The old `TickDto` should be kept temporarily but its usage in `TickEmitter` migrated to `SimulationStateDto` by end of Phase 2.

```java
// package com.trafficsimulator.dto

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationStateDto {
    private long tick;
    private long timestamp;
    private String status;          // "RUNNING", "PAUSED", "STOPPED"
    private List<VehicleDto> vehicles;
    private StatsDto stats;
}
```

```java
// package com.trafficsimulator.dto

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleDto {
    private String id;
    private String laneId;
    private double position;   // metres from lane start
    private double speed;      // m/s
    // x, y pixel coordinates computed from road geometry for Canvas rendering
    private double x;
    private double y;
    private double angle;      // radians — road direction angle for vehicle rectangle rotation
}
```

```java
// package com.trafficsimulator.dto

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsDto {
    private int vehicleCount;
    private double avgSpeed;    // m/s
    private double density;     // vehicles/km (across all lanes)
    private double throughput;  // vehicles despawned in last 60 seconds
}
```

**VehicleDto projection:** The conversion from `Vehicle` (domain) to `VehicleDto` (DTO) is done by `StatePublisher` post-tick. The pixel `x, y` are computed from the road's `startX/Y`, `endX/Y` and the vehicle's `position` as a fraction of road length. `angle` is `atan2(endY - startY, endX - startX)`.

For the straight-road fixture with horizontal roads:
```
x = road.startX + (vehicle.position / road.length) * (road.endX - road.startX)
y = road.startY + laneOffset  // lane 0: +y offset, lane 2: -y offset (lane width ~14px)
angle = atan2(road.endY - road.startY, road.endX - road.startX) = 0.0
```

### 6.3 STOMP Topic Change

The topic remains `/topic/simulation` — no change to the WebSocket config or frontend subscription. Only the JSON payload shape changes. This is a breaking change for the frontend; the Phase 1 `useWebSocket.ts` will need to update its type from `TickDto` to `SimulationStateDto` in Phase 5.

### 6.4 Migration Path

Phase 2 wires `SimulationStateDto` in a minimal `TickEmitter`-replacement. The existing `TickEmitter` will be deprecated but not yet deleted — it serves as a reference. The new `SimulationStateEmitter` (or updated `TickEmitter`) reads state from `SimulationEngine` and broadcasts `SimulationStateDto`. When `status = STOPPED`, `vehicles` is an empty list and `stats` carries zeroes.

---

## 7. Package Structure for Phase 2

Following the architecture document's recommended layout (adjusted to the existing package `com.trafficsimulator`):

```
src/main/java/com/trafficsimulator/
├── model/
│   ├── Vehicle.java
│   ├── Lane.java
│   ├── Road.java
│   ├── RoadNetwork.java
│   ├── Intersection.java
│   ├── IntersectionType.java     (enum)
│   ├── SpawnPoint.java           (record)
│   └── DespawnPoint.java         (record)
│
├── engine/
│   ├── SimulationEngine.java     (stub — holds RoadNetwork, command queue, status)
│   ├── VehicleSpawner.java
│   └── command/
│       └── SimulationCommand.java  (sealed interface + records)
│
├── config/
│   ├── MapConfig.java            (Jackson POJO with nested static classes)
│   └── MapLoader.java
│
├── controller/
│   └── CommandHandler.java       (@MessageMapping("/command"))
│
├── dto/
│   ├── TickDto.java              (kept from Phase 1)
│   ├── SimulationStateDto.java   (new)
│   ├── VehicleDto.java           (new)
│   ├── StatsDto.java             (new)
│   └── CommandDto.java           (new)
│
└── scheduler/
    └── TickEmitter.java          (Phase 1 — migrated to emit SimulationStateDto)

src/main/resources/
└── maps/
    └── straight-road.json
```

---

## Validation Architecture

### Unit Test Strategy

Phase 2 verification is entirely unit-test driven. No Spring context needed for model and engine tests. Spring slice tests (`@SpringBootTest` + `@AutoConfigureMockMvc`) only for `CommandHandler` STOMP endpoint.

**Test class plan:**

| Test Class | What It Tests | Key Assertions |
|---|---|---|
| `MapLoaderTest` | `MapLoader.loadFromClasspath("maps/straight-road.json")` | Returns non-null `RoadNetwork`; 1 road with 3 lanes; all lane IDs correct; spawn points present |
| `RoadNetworkConstructionTest` | `MapLoader` builds correct object graph | `road.getLanes().size() == 3`; `lane.getRoad() == road` (back-reference); node coordinates propagated |
| `VehicleSpawnerTest` | Spawner tick mechanics | After 1.0s at 1.0 veh/s: 1 vehicle spawned; overlap prevention blocks double-spawn at position 0; IDM params in [±20%] range |
| `VehicleDespawnTest` | Despawn on reaching road end | Vehicle at position 799.9m with speed 10 m/s: after dt=0.1s position=800.8m → despawned from lane |
| `CommandQueueTest` | Thread-safety of command queue | 100 threads concurrently enqueue `SetSpawnRate` commands; drain produces 100 commands, no exception |
| `SimulationStateDtoTest` | DTO projection | `VehicleDto` from `Vehicle` on straight horizontal road: `x` and `y` correct; `angle=0.0` |

**Success criteria mapping:**

| Phase 2 Success Criterion | Test Coverage |
|---|---|
| `MapLoader` reads 3-lane straight-road JSON and constructs valid `RoadNetwork` | `MapLoaderTest` + `RoadNetworkConstructionTest` |
| `VehicleSpawner` spawns one vehicle/second; vehicle appears in tick state | `VehicleSpawnerTest` + `SimulationStateDtoTest` |
| Vehicle despawns at road exit; tick snapshot vehicle count decrements | `VehicleDespawnTest` |

### Integration Smoke Test

After unit tests pass, a manual end-to-end check (no frontend required):
1. Start backend with `mvn spring-boot:run`
2. Subscribe to `/topic/simulation` via Postman/Bruno WebSocket client
3. Observe `SimulationStateDto` payloads with `status="STOPPED"`, `vehicles=[]`
4. Send `{"type":"START"}` to `/app/command`
5. Observe `vehicles` list beginning to populate with spawned vehicle entries
6. After 10 seconds: vehicle count should be ~10 (1 veh/s spawn rate); positions incrementing
7. Verify no errors in backend log

### IDM Parameter Range Validation

The `VehicleSpawnerTest` must assert that each spawned vehicle's IDM parameters fall within [base × 0.8, base × 1.2]:

```java
assertThat(vehicle.getV0()).isBetween(V0_BASE * 0.8, V0_BASE * 1.2);
assertThat(vehicle.getAMax()).isBetween(A_BASE * 0.8, A_BASE * 1.2);
assertThat(vehicle.getB()).isBetween(B_BASE * 0.8, B_BASE * 1.2);
assertThat(vehicle.getS0()).isEqualTo(S0); // not randomised
```

---

## Key Design Decisions & Rationale

| Decision | Chosen Approach | Rationale |
|---|---|---|
| Vehicle position is 1D | `double position` (metres along lane) | Simplest correct representation for 1D traffic flow; x/y projection is a DTO concern |
| Lane vehicle list sorting | `ArrayList` + manual sort order maintained by spawner/physics | Simple for Phase 2; binary search optimisation deferred to Phase 3 |
| Command sealed interface | Java 17 `sealed interface` + `record` permits | Exhaustive `switch` at compile time; zero runtime cost; forward-extensible |
| `drainTo` not `poll` loop | `LinkedBlockingQueue.drainTo(list)` | Atomic batch drain; avoids repeated lock acquisitions; standard practice |
| JSON in classpath resources | `src/main/resources/maps/` | Simple classpath loading; no file system path config; works in JAR packaging |
| MapConfig inner static classes | Nested `@Data @NoArgsConstructor` statics | Jackson can deserialise nested types; avoids proliferation of top-level config POJOs |
| `s0` not randomised | Fixed at 2.0 m | Minimum gap is a safety property; randomising it can produce unrealistic behaviour (too small s0 causes crashes at low noise) |
| `VehicleDto` carries `x, y` | Projection done in `StatePublisher` | Frontend only needs pixel coordinates; keeps domain model free of rendering concerns |
| Intersection stub in Phase 2 | Empty `Intersection` class | Forward-compatibility with Phase 8; `MapLoader` can parse intersection nodes without logic |

---

## RESEARCH COMPLETE
