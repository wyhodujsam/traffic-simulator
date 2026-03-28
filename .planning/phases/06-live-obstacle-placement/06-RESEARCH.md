# Phase 6 Research: Live Obstacle Placement

**Date:** 2026-03-28
**Requirements:** OBST-01, OBST-02, OBST-03, OBST-04, VIS-05

---

## 1. PhysicsEngine Leader Handling — Obstacles as Virtual Vehicles

**Current behavior (`PhysicsEngine.java`):**
- `tick(Lane, dt)` sorts vehicles by position descending (front-to-back)
- Each vehicle gets its leader from `vehicles.get(i - 1)` — the vehicle directly ahead
- `computeAcceleration(vehicle, leader)` uses leader's `position`, `speed`, and `length` for gap calculation:
  ```
  gap = leader.getPosition() - vehicle.getPosition() - leader.getLength()
  ```
- When `leader == null`, vehicle is in free-flow mode

**Obstacle-as-virtual-vehicle approach:**
Obstacles can be modeled as zero-speed, immovable entities that participate in the leader search. Two options:

**Option A: Obstacles stored in `Lane.vehicles` list as special Vehicle instances**
- Set `speed=0`, `position=X`, `length=vehicleLength` (or wider to block lane)
- PhysicsEngine already handles speed=0 leaders correctly — IDM brakes hard toward stationary objects
- Pros: zero changes to PhysicsEngine
- Cons: pollutes vehicle list with non-vehicles; must filter in snapshot building, spawner, despawner

**Option B (recommended): Separate `List<Obstacle>` on Lane, merged into leader search**
- Obstacle is its own model class with `position`, `length`, `laneId`, `id`
- PhysicsEngine finds effective leader = min(nearest vehicle ahead, nearest obstacle ahead)
- Pros: clean domain separation; no risk of despawning obstacles; clear DTO separation
- Cons: requires modifying PhysicsEngine leader lookup (small change)

**Recommendation: Option B.** The PhysicsEngine change is minimal — after finding the vehicle leader, check if any obstacle is closer. The obstacle acts as a "virtual leader" with `speed=0`.

### PhysicsEngine Change Sketch

In `PhysicsEngine.tick()`, after sorting vehicles, for each vehicle:
1. Find vehicle leader (existing code: `vehicles.get(i - 1)`)
2. Find nearest obstacle ahead: iterate `lane.getObstacles()`, pick closest with `obstacle.position > vehicle.position`
3. Effective leader = whichever is closer (vehicle leader or obstacle)
4. If obstacle is closer, create a synthetic leader-like object (speed=0, position=obstacle.position, length=obstacle.length)

The `computeAcceleration` method takes a `Vehicle leader` — we need to either:
- Extract an interface/record for "leader info" (position, speed, length), or
- Pass leader position/speed/length as primitives (simplest refactor)

**Simplest approach:** overload or refactor `computeAcceleration` to accept `(Vehicle vehicle, double leaderPosition, double leaderSpeed, double leaderLength)`. When leader is an obstacle: `leaderSpeed=0`, `leaderLength=obstacle.length`.

---

## 2. Obstacle Model + ObstacleManager — Where in Tick Pipeline

### Obstacle Model

```java
// model/Obstacle.java
@Data @Builder
public class Obstacle {
    private String id;          // UUID
    private String laneId;      // lane this obstacle sits on
    private double position;    // metres from lane start
    private double length;      // metres (e.g. 3.0 — blocks ~1 car length)
    private long createdAtTick; // for stats/ordering
}
```

### Lane Extension

Add `List<Obstacle> obstacles = new ArrayList<>()` to `Lane.java`. This keeps obstacles co-located with the lane data that PhysicsEngine already iterates.

### ObstacleManager

```java
// engine/ObstacleManager.java
@Component
public class ObstacleManager {
    // Add obstacle to the correct lane (looked up via RoadNetwork)
    public Obstacle addObstacle(RoadNetwork network, String roadId, int laneIndex, double position);
    // Remove obstacle by ID (scans all lanes or uses a Map<String, Obstacle> index)
    public boolean removeObstacle(RoadNetwork network, String obstacleId);
    // Get all obstacles (for snapshot building)
    public List<Obstacle> getAllObstacles(RoadNetwork network);
}
```

### Position in Tick Pipeline

Current tick pipeline in `TickEmitter.emitTick()`:
1. `simulationEngine.drainCommands()` — processes ADD_OBSTACLE / REMOVE_OBSTACLE here
2. `vehicleSpawner.tick()` — spawn vehicles
3. `physicsEngine.tick()` — advance physics (obstacles affect IDM here)
4. `vehicleSpawner.despawnVehicles()` — remove vehicles past road end
5. `buildSnapshot()` — include obstacles in DTO

Obstacles are managed via command queue (step 1), affect physics (step 3), and appear in snapshot (step 5). No new pipeline step needed — obstacles live on Lane and are read during physics.

---

## 3. IDM Integration — Obstacle as Leader with Speed=0

**How IDM responds to a stationary obstacle:**

Given IDM formula: `a = aMax * [1 - (v/v0)^4 - (s*/s)^2]`

Where `s* = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))`

When leader speed = 0 and approaching vehicle has speed v:
- `deltaV = v - 0 = v` (positive — follower is faster)
- `s* = s0 + v*T + v*v / (2*sqrt(aMax*b))` — large desired gap
- As `s` (actual gap) shrinks, `(s*/s)^2` grows rapidly, producing strong braking
- Vehicle comes to a smooth stop at distance ~s0 from obstacle

This is exactly the IDM behavior for a stopped leader — no special physics needed. The 5 existing safety guards (zero-gap clamp, negative speed clamp, maxSpeed clamp, NaN fallback, s* floor) all apply correctly.

**Key insight:** The shockwave / phantom jam forms naturally. When a vehicle stops behind the obstacle, the vehicle behind it brakes, creating a backward-propagating wave — this is emergent IDM behavior, no extra code needed.

---

## 4. Canvas Click to Road/Lane/Position Mapping

**Current coordinate system:**
- Roads have `startX, startY, endX, endY` in pixel coordinates
- Vehicles are projected to pixel coords in `TickEmitter.projectVehicle()` using: `fraction = position / road.length`
- Lane offset: `(laneIndex - (laneCount-1)/2.0) * LANE_WIDTH_PX` perpendicular to road direction
- `LANE_WIDTH_PX = 14.0`

**Click-to-simulation mapping algorithm:**

Given click point `(cx, cy)` on the vehicles canvas:

For each road:
1. Compute road vector: `dx = endX - startX`, `dy = endY - startY`, `roadLen = sqrt(dx^2 + dy^2)`
2. Compute unit vectors: `ux = dx/roadLen`, `uy = dy/roadLen` (along road), `nx = -uy`, `ny = ux` (perpendicular)
3. Vector from road start to click: `px = cx - startX`, `py = cy - startY`
4. Project onto road axis: `along = px*ux + py*uy` — must be in `[0, roadLen]`
5. Project onto perpendicular: `perp = px*nx + py*ny`
6. Road half-width = `laneCount * LANE_WIDTH_PX / 2`
7. If `|perp| <= halfWidth`, click is on road
8. Lane index = `floor((perp + halfWidth) / LANE_WIDTH_PX)` — clamped to `[0, laneCount-1]`
9. Simulation position (metres) = `(along / roadLen) * road.length` (road.length in metres from RoadDto)

Pick the road with smallest perpendicular distance if multiple roads overlap.

**Implementation location:** New function in `frontend/src/rendering/hitTest.ts` or similar. Called from canvas `onClick` handler in `SimulationCanvas.tsx`.

**Important:** The click handler should go on the top canvas (vehicles layer) since it's the one receiving pointer events.

---

## 5. ADD_OBSTACLE / REMOVE_OBSTACLE Commands via STOMP

### Backend Changes

**SimulationCommand.java** — add two new records:
```java
record AddObstacle(String roadId, int laneIndex, double position) implements SimulationCommand {}
record RemoveObstacle(String obstacleId) implements SimulationCommand {}
```

**CommandDto.java** — add fields:
```java
private String roadId;      // for ADD_OBSTACLE
private Integer laneIndex;  // for ADD_OBSTACLE
private Double position;    // for ADD_OBSTACLE (metres)
private String obstacleId;  // for REMOVE_OBSTACLE
```

**CommandHandler.java** — add cases:
```java
case "ADD_OBSTACLE" -> new SimulationCommand.AddObstacle(dto.getRoadId(), dto.getLaneIndex(), dto.getPosition());
case "REMOVE_OBSTACLE" -> new SimulationCommand.RemoveObstacle(dto.getObstacleId());
```

**SimulationEngine.applyCommand()** — add handling:
- `AddObstacle`: delegate to `ObstacleManager.addObstacle(roadNetwork, roadId, laneIndex, position)`
- `RemoveObstacle`: delegate to `ObstacleManager.removeObstacle(roadNetwork, obstacleId)`

### Frontend Changes

**simulation.ts** — extend `CommandType` and `CommandDto`:
```typescript
type CommandType = ... | 'ADD_OBSTACLE' | 'REMOVE_OBSTACLE';

interface CommandDto {
  ...
  roadId?: string;
  laneIndex?: number;
  position?: number;     // metres
  obstacleId?: string;
}
```

**SimulationCanvas.tsx** — onClick handler:
```typescript
const handleCanvasClick = (e: React.MouseEvent) => {
  const rect = canvas.getBoundingClientRect();
  const cx = e.clientX - rect.left;
  const cy = e.clientY - rect.top;

  // 1. Check if click hits an existing obstacle → REMOVE_OBSTACLE
  // 2. Otherwise, map to road/lane/position → ADD_OBSTACLE
  // 3. Send command via store.sendCommand()
};
```

---

## 6. Obstacles in SimulationStateDto

### New ObstacleDto

```java
// dto/ObstacleDto.java
@Data @Builder
public class ObstacleDto {
    private String id;
    private String laneId;
    private double position;  // metres
    private double x;         // pixel x
    private double y;         // pixel y
    private double angle;     // road angle (for rendering rotation)
}
```

### SimulationStateDto Extension

Add `List<ObstacleDto> obstacles` field to `SimulationStateDto`.

### TickEmitter.buildSnapshot() Changes

After iterating vehicles, also iterate obstacles per lane and project them to pixel coords using the same `projectVehicle`-like logic (fraction-based positioning on road geometry).

### Frontend TypeScript Types

```typescript
interface ObstacleDto {
  id: string;
  laneId: string;
  position: number;
  x: number;
  y: number;
  angle: number;
}

interface SimulationStateDto {
  ...
  obstacles: ObstacleDto[];
}
```

Store the obstacles in Zustand (either inside snapshots or as a separate `obstacles` field).

---

## 7. Canvas Rendering of Obstacles

Obstacles should be visually distinct from vehicles. Render them in the `drawVehicles` layer (dynamic canvas), since obstacles can be added/removed at runtime.

**New function:** `drawObstacles(ctx, obstacles)` in `frontend/src/rendering/drawObstacles.ts`

**Visual design:**
- Red/orange filled rectangle, wider than vehicles (span full lane width)
- Dimensions: `LANE_WIDTH_PX` wide (perpendicular), ~8px along road
- X-pattern or warning stripes for extra distinction
- Slight transparency or pulsing glow (optional)

**Rendering approach:**
```typescript
function drawObstacles(ctx: CanvasRenderingContext2D, obstacles: ObstacleDto[]): void {
  for (const o of obstacles) {
    ctx.save();
    ctx.translate(o.x, o.y);
    ctx.rotate(o.angle);

    // Red rectangle blocking lane
    ctx.fillStyle = '#ff3333';
    ctx.fillRect(-OBSTACLE_LENGTH_PX / 2, -LANE_WIDTH_PX / 2, OBSTACLE_LENGTH_PX, LANE_WIDTH_PX);

    // X-pattern for visual distinction
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(-OBSTACLE_LENGTH_PX / 2, -LANE_WIDTH_PX / 2);
    ctx.lineTo(OBSTACLE_LENGTH_PX / 2, LANE_WIDTH_PX / 2);
    ctx.moveTo(OBSTACLE_LENGTH_PX / 2, -LANE_WIDTH_PX / 2);
    ctx.lineTo(-OBSTACLE_LENGTH_PX / 2, LANE_WIDTH_PX / 2);
    ctx.stroke();

    ctx.restore();
  }
}
```

**Call site:** In `SimulationCanvas.tsx` renderLoop, after `drawVehicles()`, call `drawObstacles()`.

---

## 8. Click Hit-Testing for Obstacle Removal

When user clicks the canvas, we need to distinguish between:
1. Click on existing obstacle -> remove it
2. Click on empty road -> place new obstacle

**Hit-test algorithm:**

For each obstacle in current snapshot:
1. Compute distance from click point `(cx, cy)` to obstacle center `(o.x, o.y)`
2. Transform click into obstacle's local frame (accounting for rotation):
   ```
   localX = (cx - o.x) * cos(-angle) - (cy - o.y) * sin(-angle)
   localY = (cx - o.x) * sin(-angle) + (cy - o.y) * cos(-angle)
   ```
3. Check if `|localX| <= OBSTACLE_LENGTH_PX/2` and `|localY| <= LANE_WIDTH_PX/2`
4. If hit -> send `REMOVE_OBSTACLE` with obstacle ID
5. If no obstacle hit -> proceed with road/lane mapping for `ADD_OBSTACLE`

**Expand hit area slightly** (e.g., +4px padding) for better click ergonomics on narrow lane width (14px).

**Data source for hit-testing:** Read `obstacles` from the current Zustand snapshot (already available from WebSocket broadcast).

---

## Summary of Changes by File

### New Files
| File | Purpose |
|------|---------|
| `backend/.../model/Obstacle.java` | Domain model |
| `backend/.../engine/ObstacleManager.java` | Add/remove logic |
| `backend/.../dto/ObstacleDto.java` | Wire DTO |
| `frontend/src/rendering/drawObstacles.ts` | Canvas rendering |
| `frontend/src/rendering/hitTest.ts` | Click-to-road mapping + obstacle hit-test |

### Modified Files
| File | Change |
|------|--------|
| `Lane.java` | Add `List<Obstacle> obstacles` field |
| `PhysicsEngine.java` | Find effective leader (vehicle or obstacle, whichever closer) |
| `SimulationCommand.java` | Add `AddObstacle`, `RemoveObstacle` records |
| `CommandDto.java` | Add `roadId`, `laneIndex`, `position`, `obstacleId` fields |
| `CommandHandler.java` | Add `ADD_OBSTACLE` / `REMOVE_OBSTACLE` switch cases |
| `SimulationEngine.java` | Handle new commands, delegate to `ObstacleManager` |
| `SimulationStateDto.java` | Add `List<ObstacleDto> obstacles` |
| `TickEmitter.java` | Project obstacles to pixel coords in `buildSnapshot()` |
| `simulation.ts` | Add `ObstacleDto`, extend `CommandDto`, `CommandType` |
| `useSimulationStore.ts` | Store obstacles from snapshots |
| `SimulationCanvas.tsx` | Add `onClick` handler, call `drawObstacles()` |
| `constants.ts` | Add `OBSTACLE_LENGTH_PX` constant |

### Risk Assessment
| Risk | Mitigation |
|------|------------|
| PhysicsEngine refactor breaks existing IDM tests | Minimal change — add obstacle check after existing leader logic; all existing tests stay green |
| Click mapping inaccurate for angled roads | Use proper vector projection (section 4); test with straight road first |
| Obstacle removal flaky on narrow lanes | Expand hit-test area by +4px padding |
| Thread safety of obstacle list | Obstacles modified only via command queue (tick thread); same pattern as existing commands |
| Performance with many obstacles | O(n*m) per lane per tick (n vehicles, m obstacles); acceptable for <100 obstacles |
