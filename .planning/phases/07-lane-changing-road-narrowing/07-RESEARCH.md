# Phase 7 Research: Lane Changing & Road Narrowing

**Phase goal:** Implement MOBIL lane-change model with two-phase collision-safe update and live road narrowing that causes visible merge congestion.
**Requirements:** ROAD-02, ROAD-03, ROAD-04

---

## 1. MOBIL Lane-Change Model

The MOBIL (Minimizing Overall Braking Induced by Lane changes) model has two criteria that must both be satisfied for a lane change to occur.

### Safety Criterion
The new follower (rear vehicle in the target lane) must not be forced to brake harder than a safe threshold after the lane change:

```
a'_new_follower >= -b_safe
```

Where `a'_new_follower` is the IDM acceleration of the vehicle behind in the target lane after the subject vehicle is inserted. `b_safe` is a safety braking limit (typically 4.0 m/s^2 — more aggressive than comfortable braking `b`).

**Implementation:** Compute IDM acceleration for the new follower as if the subject vehicle were already in the target lane. If the result is below `-b_safe`, the lane change is unsafe and rejected.

### Incentive Criterion
The lane change must provide a net benefit to the subject vehicle while accounting for the disadvantage imposed on neighbors:

```
a'_subject - a_subject > p * (a'_old_follower - a_old_follower + a'_new_follower - a_new_follower) + a_threshold
```

Where:
- `a'_subject` = acceleration in target lane (with new leader)
- `a_subject` = current acceleration in current lane
- `a'_old_follower` = acceleration of old follower after subject leaves
- `a_old_follower` = current acceleration of old follower
- `a'_new_follower` = acceleration of new follower after subject arrives
- `a_new_follower` = current acceleration of new follower
- `p` = politeness factor (0.2 = aggressive, 0.5 = normal)
- `a_threshold` = minimum advantage threshold (0.2 m/s^2 typical)

### MOBIL Parameters (recommended defaults)
| Parameter | Symbol | Default | Description |
|-----------|--------|---------|-------------|
| Safe braking | `b_safe` | 4.0 m/s^2 | Max acceptable braking for new follower |
| Politeness | `p` | 0.3 | Weight given to neighbors' disadvantage |
| Threshold | `a_threshold` | 0.2 m/s^2 | Minimum acceleration advantage to change |

### Right-bias (keep-right rule)
For right-hand traffic, add a small asymmetric bias: `a_threshold` is lower for moves to the right (e.g., 0.1) and higher for moves to the left (e.g., 0.3). This encourages vehicles to return to the rightmost lane when not overtaking.

---

## 2. Finding Neighboring Lanes and Vehicles

### Current Lane Structure
- `Road` has `List<Lane> lanes` (index-ordered, lane 0 = rightmost)
- `Lane` has `int laneIndex` and `Road road` back-reference
- `Lane.active` field already exists for road narrowing (Phase 7 forward-design)

### Neighboring Lane Lookup
Given a vehicle's current lane, adjacent lanes are at `laneIndex - 1` (right) and `laneIndex + 1` (left) within the same `Road`. Bounds-check against `road.getLanes().size()`. Skip lanes where `active == false`.

```java
// In Road.java or a new utility
public Lane getLeftNeighbor(Lane lane) {
    int idx = lane.getLaneIndex() + 1;
    if (idx < lanes.size() && lanes.get(idx).isActive()) return lanes.get(idx);
    return null;
}

public Lane getRightNeighbor(Lane lane) {
    int idx = lane.getLaneIndex() - 1;
    if (idx >= 0 && lanes.get(idx).isActive()) return lanes.get(idx);
    return null;
}
```

### Finding Relevant Vehicles in Adjacent Lane
For MOBIL evaluation, we need:
1. **New leader** in target lane: nearest vehicle ahead of subject's position
2. **New follower** in target lane: nearest vehicle behind subject's position

```java
public Vehicle findLeaderAt(double position) {
    // vehicle with smallest position > given position
}

public Vehicle findFollowerAt(double position) {
    // vehicle with largest position < given position
}
```

These can be O(n) scans over `lane.getVehicles()`, similar to existing `Lane.getLeader()`. With current vehicle counts (< 500), this is acceptable at 20 Hz.

---

## 3. Two-Phase Update (Intent Buffer)

ROAD-03 requires: "Lane changes use two-phase update (intent -> conflict resolution -> commit)."

### Why Two-Phase?
If lane changes are applied immediately during iteration, two vehicles could simultaneously decide to move into the same gap, causing a collision. The two-phase approach prevents this.

### Phase 1: Intent Collection
Iterate over all vehicles. For each, evaluate MOBIL for left and right lanes. If a lane change is desirable, record an **intent** (not yet applied):

```java
record LaneChangeIntent(
    Vehicle vehicle,
    Lane sourceLane,
    Lane targetLane,
    double position   // vehicle's current position
) {}
```

Collect all intents into a `List<LaneChangeIntent>`.

### Phase 2: Conflict Resolution
Group intents by target lane. For each target lane, sort intents by position. Check for conflicts — two vehicles wanting to occupy overlapping positions in the same target lane. Resolution rules:
1. **No overlap:** If two intents would place vehicles within `s0 + vehicleLength` of each other in the target lane, keep only the one with the higher incentive score.
2. **Winner-takes-slot:** The vehicle with the highest net acceleration gain wins the contested slot.

### Phase 3: Commit
For each surviving intent:
1. Remove vehicle from `sourceLane.getVehicles()`
2. Add vehicle to `targetLane.getVehicles()`
3. Update `vehicle.setLane(targetLane)`
4. Record `vehicle.setLastLaneChangeTick(currentTick)` for cooldown

### Integration Point
The two-phase update runs as a distinct step in the tick pipeline in `TickEmitter.emitTick()`, **after** IDM physics but **before** despawn:

```
1. Drain commands
2. Spawn vehicles
3. Physics (IDM) sub-steps
4. Lane change (intent -> resolve -> commit)   <-- NEW
5. Despawn vehicles
```

This ordering ensures vehicles have up-to-date positions/speeds from physics before MOBIL evaluation, and that lane-changed vehicles are correctly despawned if they passed the lane end.

---

## 4. Per-Vehicle Cooldown

### Storage
Add field to `Vehicle.java`:

```java
private long lastLaneChangeTick;  // tick number of last lane change, 0 = never
```

### 3-Second Check
At 20 Hz, 3 seconds = 60 ticks. Before evaluating MOBIL for a vehicle:

```java
long ticksSinceLastChange = currentTick - vehicle.getLastLaneChangeTick();
long cooldownTicks = (long)(3.0 / baseDt);  // 60 ticks at 20 Hz
if (ticksSinceLastChange < cooldownTicks) {
    // skip MOBIL evaluation for this vehicle
}
```

**Speed multiplier consideration:** The cooldown should be in simulation-time (3 seconds), not wall-clock time. With speed multiplier M, effective ticks per second = 20*M, so cooldown ticks = 3 * 20 = 60 (unchanged, since both tick rate and sim-time scale equally). The simplest approach: store the tick number and compare against `3.0 / baseDt` ticks regardless of multiplier, because the tick counter already reflects simulation time progression.

---

## 5. Lateral Transition Animation (Frontend)

### Problem
When a vehicle changes lanes, its `laneId` and `y` coordinate jump instantly between two tick snapshots. This looks jarring at 60 fps even with interpolation, because the previous and current snapshots have different `y` values.

### Solution: `targetLaneId` in VehicleDto
Add a `targetLaneId` field and a `laneChangeProgress` (0.0 to 1.0) to `VehicleDto`:

```java
// VehicleDto additions
private String targetLaneId;       // null if not changing lanes
private double laneChangeProgress; // 0.0 = source lane, 1.0 = target lane
```

During lane change transition (e.g., over 10 ticks / 0.5 seconds):
- Backend keeps the vehicle in the **target** lane (for physics purposes)
- Backend sends `laneChangeProgress` that increments from 0 to 1 over N ticks
- Frontend interpolates y-position between source lane y and target lane y based on progress

### Simpler Alternative (Recommended)
Since the frontend already interpolates y between `prevSnapshot` and `currSnapshot`, and the y-coordinate changes when `laneId` changes, the **existing interpolation already provides smooth lateral movement**. The alpha-based lerp between prev.y and curr.y in `interpolation.ts` will naturally slide the vehicle sideways over one tick interval (50ms).

However, 50ms is too fast for a natural-looking lane change. To stretch it:
- **Backend approach:** During lane change, gradually adjust the vehicle's projected y-offset over 10 ticks using a `laneChangeProgress` field. This is the cleanest solution.
- The backend `projectVehicle()` would compute: `y = sourceLaneY + progress * (targetLaneY - sourceLaneY)`.

### Frontend TypeScript Changes
```typescript
// simulation.ts - VehicleDto additions
targetLaneId?: string | null;
laneChangeProgress?: number;  // 0..1, undefined = not changing
```

The existing `interpolateVehicles` function in `interpolation.ts` already handles y interpolation. With the backend providing gradually changing y values over 10 ticks, no frontend changes are needed for smooth lane changes.

---

## 6. Road Narrowing (REDUCE_LANES Command)

### Command Design
Add a new command to the sealed interface:

```java
record ReduceLanes(String roadId, int newLaneCount) implements SimulationCommand {}
```

Alternatively, close a specific lane:

```java
record CloseLane(String roadId, int laneIndex) implements SimulationCommand {}
```

The `CloseLane` approach is simpler and more flexible (can close any lane, not just the outermost).

### Lane Closure Behavior
When `CloseLane` is processed:
1. Set `lane.setActive(false)` on the target lane
2. Vehicles currently in the closed lane need to merge out:
   - Option A: Force-flag them for lane change on the next MOBIL evaluation (skip incentive check, only safety)
   - Option B: Place a virtual obstacle at the start of the closed lane to prevent new entries, and let vehicles naturally exit as they reach the lane end

**Recommended: Option A** — more realistic merge behavior. Set a `forceLaneChange` flag on vehicles in the closed lane. During MOBIL evaluation, these vehicles skip the incentive criterion (they _must_ change) but still respect the safety criterion.

### Preventing New Spawns/Entries
- `VehicleSpawner` already checks `lane.isActive()` before spawning (line 72 of VehicleSpawner.java)
- MOBIL evaluation should skip inactive lanes as targets

### Merge Congestion
When a lane closes:
1. Vehicles in the closed lane slow down (can't proceed, obstacle logic or IDM with no leader ahead in their now-dead lane)
2. They try to merge via forced lane change
3. The receiving lane gets denser, IDM kicks in with shorter gaps
4. Visible congestion wave propagates backward

### Frontend Visualization
- Closed lane rendered with hatched pattern or darker color
- Road rendering in `drawRoads.ts` reads lane active status from a new field in `RoadDto`

### RoadDto Extension
```typescript
export interface LaneDto {
  id: string;
  laneIndex: number;
  active: boolean;
}

export interface RoadDto {
  // existing fields...
  lanes: LaneDto[];  // replaces laneCount
}
```

---

## 7. Frontend Visual Changes

### Lane Change Visualization
The existing interpolation in `interpolation.ts` handles most of the work. When a vehicle changes lanes:
- `prevSnapshot` has the vehicle at old lane y
- `currSnapshot` has the vehicle at new lane y
- `interpolateVehicles()` lerps between them

For a more gradual transition, the backend sends gradually changing y over ~10 ticks via `laneChangeProgress`. No new rendering logic needed — `drawVehicles.ts` already draws at the interpolated (x, y).

### Closed Lane Visualization
In `drawRoads.ts`, add hatched overlay for inactive lanes. Requires lane active status in the road data sent to the frontend.

### No Changes Needed
- `drawVehicles.ts` — already draws at interpolated position, no changes
- `SimulationCanvas.tsx` — no changes needed for lane changes
- `interpolation.ts` — existing lerp handles lateral movement

---

## 8. Integration with Existing Tick Pipeline

### Current Pipeline (TickEmitter.emitTick)
```
1. simulationEngine.drainCommands()
2. vehicleSpawner.tick(effectiveDt, network, tick)
3. for each sub-step:
     for each road -> for each lane:
       physicsEngine.tick(lane, stepDt)
4. vehicleSpawner.despawnVehicles(network)
5. buildSnapshot(tick, network)
6. statePublisher.broadcast(state)
```

### New Pipeline with Lane Changes
```
1. simulationEngine.drainCommands()          // may process CloseLane
2. vehicleSpawner.tick(effectiveDt, network, tick)
3. for each sub-step:
     for each road -> for each lane:
       physicsEngine.tick(lane, stepDt)
4. laneChangeEngine.tick(network, tick)      // NEW: intent -> resolve -> commit
5. vehicleSpawner.despawnVehicles(network)
6. buildSnapshot(tick, network)              // MODIFIED: includes laneChangeProgress
7. statePublisher.broadcast(state)
```

### New Components
| Component | Type | Responsibility |
|-----------|------|----------------|
| `LaneChangeEngine.java` | `@Component` | MOBIL evaluation, two-phase update, cooldown enforcement |
| `LaneChangeIntent` | Record | Data class for intent buffer |
| `CloseLane` | SimulationCommand variant | New command for road narrowing |

### Wiring
- `LaneChangeEngine` injected into `TickEmitter` alongside `PhysicsEngine`
- `LaneChangeEngine.tick(RoadNetwork, long currentTick)` called once per tick (not per sub-step)
- Lane changes happen at tick granularity, not sub-step granularity — this is intentional to keep MOBIL evaluation simple and avoid mid-step conflicts

### SimulationEngine Changes
- Add `CloseLane` command handling in `applyCommand()`
- Set `lane.setActive(false)`, mark vehicles in that lane with `forceLaneChange = true`

### Vehicle.java Additions
```java
private long lastLaneChangeTick;   // cooldown tracking
private boolean forceLaneChange;   // set when lane is closed under this vehicle
private double laneChangeProgress; // 0.0 = just changed, 1.0 = settled; for animation
private int laneChangeSourceIndex; // source lane index for y-interpolation
```

---

## 9. Test Strategy

### Safety Invariants (Unit Tests)
1. **No dual occupancy:** After lane change commit, no two vehicles in the same lane are within `vehicleLength` of each other at the same position.
2. **Cooldown enforcement:** Vehicle that just changed lanes cannot change again within 60 ticks.
3. **Safety criterion:** Lane change never produces `a_new_follower < -b_safe` for the new follower.
4. **Inactive lane rejection:** MOBIL never selects an inactive lane as target.

### MOBIL Correctness (Unit Tests)
5. **Free lane preferred:** Vehicle behind slow leader changes to empty adjacent lane.
6. **No change when alone:** Single vehicle in single-lane road produces no intent.
7. **Incentive threshold:** Lane change not triggered when speed gain is below `a_threshold`.
8. **Politeness factor:** Lane change blocked when it would severely disadvantage new follower.

### Two-Phase Conflict Resolution (Unit Tests)
9. **Conflict winner:** Two vehicles targeting the same gap — only one intent survives.
10. **Independent non-conflicting:** Two vehicles targeting different gaps in the same lane both succeed.

### Road Narrowing (Integration Tests)
11. **Lane closure command:** `CloseLane` sets `lane.active = false` and flags vehicles.
12. **Forced merge:** Vehicles in closed lane change to adjacent active lane within N ticks.
13. **Congestion formation:** Average speed decreases after lane closure (statistical check over 100 ticks).

### Frontend (Existing interpolation tests cover lateral movement)
14. **Smooth y-transition:** Vehicle changing lanes has gradually changing y over multiple ticks (not a jump).

### Recommended Test Count
- 8-10 unit tests for `LaneChangeEngine`
- 2-3 integration tests for road narrowing pipeline
- Total: ~12 new tests

---

## Key Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Lane change timing | Once per tick, after physics | Avoids sub-step conflicts; vehicles have fresh positions |
| Conflict resolution | Highest incentive wins | Fair and deterministic |
| Cooldown storage | `lastLaneChangeTick` on Vehicle | Minimal memory, tick comparison is cheap |
| Animation approach | Backend `laneChangeProgress` field | Frontend interpolation already handles y-lerp; backend controls duration |
| Road narrowing | `CloseLane` command + `forceLaneChange` flag | Flexible (any lane), clean separation of concerns |
| Lane neighbor lookup | Index arithmetic on Road.lanes | Simple, O(1), matches existing lane index convention |
| MOBIL evaluation order | All vehicles evaluated before any commits | Two-phase prevents race conditions |

---

*Research completed: 2026-03-28*
*Ready for planning phase*
