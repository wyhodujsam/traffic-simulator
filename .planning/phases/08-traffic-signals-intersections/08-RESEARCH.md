# Phase 8 Research: Traffic Signals & Intersections

**Date:** 2026-03-28
**Requirements:** IXTN-01, IXTN-02, IXTN-03, IXTN-04, IXTN-07, VIS-04

---

## 1. Current Intersection Model

### What exists

`Intersection.java` is a skeleton with three fields:
- `id` (String) -- currently set to `nodeId` from JSON
- `type` (IntersectionType enum: `SIGNAL`, `ROUNDABOUT`, `PRIORITY`, `NONE`)
- `connectedRoadIds` (List<String>) -- always empty; never populated by MapLoader

There is a placeholder comment: `// TrafficLight trafficLight; -- null in Phase 2, added in Phase 8`.

`RoadNetwork` holds `Map<String, Intersection> intersections` but no code reads from it at runtime. The current map (`straight-road.json`) has `"intersections": []`.

### Road-Intersection connection

Roads have `fromNodeId` and `toNodeId` fields. Nodes in the JSON have `type` ("ENTRY", "EXIT", "INTERSECTION"). When a node has type "INTERSECTION", the corresponding `IntersectionConfig` exists in the JSON. MapLoader builds an `Intersection` keyed by `nodeId`, but **does not wire `connectedRoadIds`** and **does not store which roads connect to which intersection**.

### What needs to change

1. **MapLoader must populate `connectedRoadIds`** by scanning all roads whose `fromNodeId` or `toNodeId` matches an intersection's `nodeId`.
2. Intersection needs **directional awareness**: which roads are _inbound_ (toNodeId = intersection) vs _outbound_ (fromNodeId = intersection). This is critical for traffic light phases (green for approach A means inbound road A gets green).
3. Add fields to `Intersection`: `inboundRoadIds`, `outboundRoadIds`, plus the `TrafficLight` reference.

---

## 2. Traffic Light Phase Modeling (IXTN-01)

### Proposed model: `TrafficLight.java`

```
TrafficLight:
  - intersectionId: String
  - phases: List<TrafficLightPhase>    // ordered cycle
  - currentPhaseIndex: int
  - phaseElapsedMs: long               // time in current phase
  - cycleDurationMs: long              // total cycle time (sum of all phase durations)

TrafficLightPhase:
  - greenRoadIds: Set<String>          // inbound roads that get green in this phase
  - durationMs: long                   // how long this phase lasts
  - type: PhaseType                    // GREEN, YELLOW, RED (or ALL_RED for clearance)
```

### Cycle structure for a 4-way intersection

A standard 4-way signal has 4 phases per cycle:
1. **NS Green** (roads from north/south get green) -- e.g., 30s
2. **NS Yellow** (transition) -- 3s
3. **EW Green** (roads from east/west get green) -- 30s
4. **EW Yellow** (transition) -- 3s
5. Optionally: **All-Red clearance** between each pair -- 2s

Each tick, `phaseElapsedMs += dt * 1000`. When it exceeds phase duration, advance to next phase (modulo cycle length).

### TrafficLightController.java

A `@Component` that is called each tick to advance all traffic lights:
```
for each intersection with type SIGNAL:
    trafficLight.tick(dt)
    if phase changed -> log
```

This keeps traffic light state progression decoupled from physics.

---

## 3. Vehicles Stopping at Intersections (IXTN-02)

### Current stop mechanism

Vehicles currently stop because of:
- **Leader vehicle ahead** (IDM following)
- **Obstacle ahead** (treated as stationary leader in PhysicsEngine)
- **Lane end** (despawn at position >= lane.length)

There is **no concept of a stop line** at an intersection.

### Approach: Virtual stop line as phantom obstacle

When a traffic light is red for an inbound road, inject a **virtual obstacle** at the road's end (position = road.length - small buffer). This reuses the existing PhysicsEngine obstacle-following logic without modification.

Implementation:
1. At the start of each tick (after TrafficLightController advances phases), for each intersection:
   - For each inbound road: if the road's approach is RED, create/maintain a virtual obstacle at position `road.length - 2.0` (2m before intersection entry).
   - If GREEN, remove the virtual obstacle.
2. Virtual obstacles are **not stored in lane.obstacles** (those are user-placed). Instead, use a separate list or inject them into PhysicsEngine's leader calculation.

**Alternative (cleaner):** Add a `stopLinePosition` field per inbound road approach. In PhysicsEngine, when processing a vehicle, check if there is a red-light stop line ahead and treat it as a stationary leader at that position. This avoids polluting the obstacle system.

**Recommended approach:** Modify `PhysicsEngine.tick()` to accept an optional `effectiveRoadEnd` parameter. When a red light is active, `effectiveRoadEnd = stopLinePosition`. The existing position-clamp guard (Guard 6) already prevents vehicles from passing a leader. We just need to add the stop line as an additional "virtual leader" candidate alongside vehicles and obstacles.

### Integration point

In `TickEmitter.emitTick()`, before the physics loop:
1. Call `trafficLightController.tick(effectiveDt)` to advance phases
2. Build a `Map<String, Double>` of `laneId -> stopLinePosition` for all red approaches
3. Pass this map into PhysicsEngine (or have PhysicsEngine query it)

---

## 4. Vehicle Intersection Crossing (Road Transitions)

### Current limitation

Vehicles are born on a lane, move from position 0 to `lane.length`, and despawn. There is **no road-to-road transfer**. This is the biggest structural gap for intersections.

### Required mechanism

When a vehicle reaches the end of an inbound road and the light is green (or priority allows):
1. Remove vehicle from inbound lane
2. Determine which outbound road the vehicle should take (routing decision)
3. Place vehicle at position 0 of the corresponding outbound lane
4. Preserve speed and IDM parameters

### Routing

For Phase 8, simple random routing is sufficient: at each intersection, randomly pick one of the outbound roads. Phase 9 can add origin-destination routing.

### Implementation: IntersectionManager.java

```
IntersectionManager:
  - processIntersections(network, tick)
    for each intersection:
      for each inbound road:
        for each lane in inbound road:
          vehicle at end of lane (position >= length - buffer):
            if canEnter(intersection, inboundRoad):
              outboundRoad = pickOutboundRoad(intersection, inboundRoad)
              transferVehicle(vehicle, inboundLane, outboundLane)
```

### Where in the tick pipeline

Insert between physics and despawn:
1. Spawn
2. Physics (IDM)
3. Lane changes
4. **Intersection transfers** (new)
5. Despawn

Despawn should only apply to roads that end at EXIT nodes, not at INTERSECTION nodes. This requires modifying `VehicleSpawner.despawnVehicles()` to check whether the road ends at an exit or an intersection.

---

## 5. Box-Blocking Prevention (IXTN-03)

### Problem

A vehicle enters the intersection box (crosses from inbound road to outbound road) but the outbound road is full. The vehicle blocks the intersection, preventing cross-traffic from passing.

### Detection: exit road capacity check

Before allowing a vehicle to transfer through an intersection:
```
outboundLane = target lane on outbound road
gapAtEntry = findLeaderAt(0.0) on outbound lane
if gapAtEntry < vehicle.length + s0:
    BLOCK -- vehicle must wait on inbound road
```

This is equivalent to checking if position 0 of the outbound lane has space for the vehicle.

### Implementation

In `IntersectionManager.canEnter()`:
```java
boolean canEnter(Intersection ixtn, Road inboundRoad, Lane outboundLane) {
    // 1. Traffic light check (if SIGNAL type)
    if (ixtn.getType() == SIGNAL) {
        if (!trafficLight.isGreen(inboundRoad.getId())) return false;
    }
    // 2. Box-blocking check
    Vehicle firstOnOutbound = outboundLane.findLeaderAt(-1.0); // first vehicle
    if (firstOnOutbound != null && firstOnOutbound.getPosition() < MIN_ENTRY_GAP) {
        return false; // outbound road too full at entry
    }
    return true;
}
```

### Also applies to the stop mechanism

When box-blocking prevents entry, the vehicle on the inbound road should stop before the intersection. This is automatically handled if the stop-line virtual leader is maintained whenever `canEnter()` returns false (not just for red lights).

---

## 6. Deadlock Detection & Resolution (IXTN-04)

### Scenario

4-way intersection, all approaches have vehicles waiting, all exit roads are full. No vehicle can move. The simulation freezes.

### Detection

Track per-intersection: `ticksSinceLastTransfer`. If this exceeds a threshold (10 seconds = 200 ticks at 20 Hz), declare deadlock.

```java
class IntersectionState {
    long lastTransferTick;
    int waitingVehicleCount;  // vehicles at stop lines across all approaches
}
```

Deadlock condition:
- `waitingVehicleCount >= 2` (at least 2 approaches blocked)
- `currentTick - lastTransferTick > DEADLOCK_THRESHOLD_TICKS` (200 ticks)

### Resolution

Force-advance one vehicle: pick the vehicle with the longest wait time (or lowest priority approach), teleport it to the outbound road regardless of space. This breaks the circular dependency.

```java
if (deadlockDetected) {
    Vehicle victim = pickLongestWaiting(intersection);
    forceTransfer(victim);  // ignores box-blocking check
    log.warn("Deadlock resolved at {} by force-advancing {}", ixtn.getId(), victim.getId());
}
```

The forced vehicle may overlap briefly with others on the outbound road, but IDM will sort it out within a few ticks.

---

## 7. Priority Intersection / Right-of-Way (IXTN-07)

### Rule: "first from right" (pierwszenstwo z prawej)

At unsignalled intersections (type = PRIORITY or NONE), vehicles must yield to traffic approaching from their right side.

### Geometric determination

Each inbound road has an approach direction (angle from `atan2(endY - startY, endX - startX)`). For a vehicle on road A, "right" is the road whose approach direction is 90 degrees clockwise from A's direction.

### Implementation

In `IntersectionManager`, for PRIORITY/NONE intersections:
1. Collect all vehicles waiting to enter (within threshold distance of road end)
2. For each waiting vehicle, check if any vehicle is approaching from the right
3. If yes, yield (do not enter)
4. If no, proceed (with box-blocking check)

### Conflict resolution

If two vehicles from perpendicular roads arrive simultaneously:
- The one on the right has priority
- If they are on opposing roads (head-on), both can proceed (no conflict on a standard 4-way)

---

## 8. Map Format for Intersections

### Current JSON structure

```json
{
  "nodes": [{"id": "n1", "type": "ENTRY", "x": 50, "y": 300}],
  "roads": [{"id": "r1", "fromNodeId": "n1", "toNodeId": "n2", ...}],
  "intersections": [{"nodeId": "n3", "type": "SIGNAL"}]
}
```

### Extensions needed

**IntersectionConfig needs signal timing:**
```json
{
  "nodeId": "n3",
  "type": "SIGNAL",
  "signalPhases": [
    { "greenRoadIds": ["r1", "r3"], "durationMs": 30000 },
    { "greenRoadIds": ["r1", "r3"], "durationMs": 3000, "type": "YELLOW" },
    { "greenRoadIds": ["r2", "r4"], "durationMs": 30000 },
    { "greenRoadIds": ["r2", "r4"], "durationMs": 3000, "type": "YELLOW" }
  ]
}
```

**MapConfig.IntersectionConfig changes:**
- Add `List<SignalPhaseConfig> signalPhases` (nullable, only for SIGNAL type)
- `SignalPhaseConfig`: `greenRoadIds`, `durationMs`, `type` (GREEN/YELLOW/ALL_RED)

**MapValidator changes:**
- For SIGNAL intersections: validate that `signalPhases` is non-empty
- Validate that `greenRoadIds` reference actual inbound roads
- Validate that at least one road connects to each intersection node

---

## 9. New Map with Intersections

### Required: 4-way signalised intersection map

The current `straight-road.json` has no intersections. A new map file is needed, e.g., `four-way-signal.json`:

```
Layout:

    N (entry)
    |
    | r_north (south-bound)
    |
W --+-- E     (n_center = INTERSECTION node)
    |
    | r_south (north-bound)
    |
    S (entry)

With entry/exit nodes at each cardinal direction.
```

**Node layout example (pixel coords):**
- n_north: (400, 50)  -- ENTRY
- n_south: (400, 550) -- ENTRY
- n_west:  (50, 300)  -- ENTRY
- n_east:  (750, 300) -- ENTRY
- n_center: (400, 300) -- INTERSECTION

**Roads:**
- r_north_in: n_north -> n_center (southbound approach)
- r_south_in: n_south -> n_center (northbound approach)
- r_west_in: n_west -> n_center (eastbound approach)
- r_east_in: n_east -> n_center (westbound approach)
- r_north_out: n_center -> n_north (northbound exit)
- r_south_out: n_center -> n_south (southbound exit)
- r_west_out: n_center -> n_west (westbound exit)
- r_east_out: n_center -> n_east (eastbound exit)

Total: 8 roads (4 inbound, 4 outbound), each 1-2 lanes, 200-250m length.

Spawn points on all 4 inbound roads. Despawn points on all 4 outbound roads.

---

## 10. Canvas Rendering of Traffic Lights (VIS-04)

### What to render

At each signalised intersection, draw colored circles (traffic light indicators) at the stop line of each approach road.

### Position calculation

For each inbound road approaching an intersection:
- Stop line is at `road.endX, road.endY` (the road end)
- Draw 3 small circles (red/yellow/green) offset perpendicular to the road direction
- Only the active phase circle is bright; others are dim

### Implementation: `drawTrafficLights.ts`

```typescript
export function drawTrafficLights(
  ctx: CanvasRenderingContext2D,
  trafficLights: TrafficLightDto[]
): void {
  for (const tl of trafficLights) {
    // Draw at stop line position with current phase color
    drawLightIndicator(ctx, tl.x, tl.y, tl.angle, tl.state);
  }
}
```

### Backend DTO extension

Add `TrafficLightDto` to `SimulationStateDto`:
```java
TrafficLightDto:
  - intersectionId: String
  - roadId: String          // which approach this light serves
  - state: String           // "GREEN", "YELLOW", "RED"
  - x, y: double            // pixel coordinates at stop line
  - angle: double           // road direction for orientation
```

Add `List<TrafficLightDto> trafficLights` to `SimulationStateDto`.

### Frontend type extension

```typescript
export interface TrafficLightDto {
  intersectionId: string;
  roadId: string;
  state: 'GREEN' | 'YELLOW' | 'RED';
  x: number;
  y: number;
  angle: number;
}
```

Add `trafficLights: TrafficLightDto[]` to `SimulationStateDto`.

### Rendering layer

Traffic lights should be drawn on the **static roads layer** (they don't move), but their state changes each tick. Two options:
1. **Redraw roads layer each tick** -- wasteful but simple
2. **Add a third canvas layer** for traffic light state -- cleaner separation

Recommendation: draw traffic lights on the **vehicles (dynamic) layer** since their state changes per tick. This avoids modifying the static roads layer redraw logic.

---

## 11. SET_LIGHT_CYCLE Command

### Purpose

Allow the user to change traffic light timing at runtime (EDIT-01 says v2, but success criterion 4 requires it).

### Command definition

```java
// SimulationCommand.java
record SetLightCycle(String intersectionId, List<SignalPhaseConfig> phases)
    implements SimulationCommand {}
```

```java
// CommandDto.java -- new fields
private String intersectionId;        // SET_LIGHT_CYCLE
private List<SignalPhaseDto> phases;   // SET_LIGHT_CYCLE
```

### Frontend integration

Add to ControlsPanel (or a new IntersectionPanel):
- Dropdown to select intersection
- Sliders for green duration per phase
- "Apply" button sends SET_LIGHT_CYCLE command

For Phase 8, a minimal implementation: single slider per intersection controlling green duration (both phases use the same value). Advanced per-phase editing can wait for Phase 10 or v2.

### Timing constraint

Success criterion 4: "takes effect within one full cycle." Implementation: when the command is applied, reset `phaseElapsedMs` to 0 and rebuild the phase list. The new timing starts from the current phase.

---

## Key Decisions & Risks

| Decision | Rationale |
|----------|-----------|
| Virtual stop line approach (not phantom obstacles) | Cleaner than injecting/removing obstacles; avoids polluting user-visible obstacle list |
| Road-to-road transfer in IntersectionManager | Centralized logic; called between physics and despawn in tick pipeline |
| Random routing at intersections | Simplest for Phase 8; deterministic O/D routing deferred |
| Traffic lights on dynamic canvas layer | Avoids redrawing static roads layer every tick |
| Single new map file (4-way signal) | Proves the concept; more complex maps in Phase 9 |
| 8 unidirectional roads per 4-way intersection | Each direction has separate inbound/outbound road for independent lane management |

### Risks

1. **Road-to-road transfer complexity**: Vehicle position reset from end-of-inbound to start-of-outbound requires careful IDM parameter continuity. Speed should be preserved.
2. **Despawn logic change**: Currently despawns at `position >= lane.length` for ALL roads. Must be restricted to EXIT-node roads only. This is a breaking change for the existing straight-road map (which has EXIT nodes, so it should still work).
3. **PhysicsEngine modification**: Adding stop-line awareness to PhysicsEngine must not break existing tests. The stop line should be an _additional_ leader candidate, not a replacement.
4. **Canvas rendering for non-horizontal roads**: Current rendering assumes roads can be at any angle (uses rotate/translate). Intersection rendering must handle perpendicular roads correctly.
5. **Tick pipeline ordering**: IntersectionManager must run after physics (vehicles need updated positions) but before despawn (to transfer instead of despawn at intersection nodes).

---

## File Change Summary

### New files (backend)
- `model/TrafficLight.java` -- phase state, timing
- `model/TrafficLightPhase.java` -- single phase definition
- `engine/TrafficLightController.java` -- advances phases each tick
- `engine/IntersectionManager.java` -- transfers, box-blocking, deadlock watchdog, priority
- `dto/TrafficLightDto.java` -- broadcast traffic light state
- `resources/maps/four-way-signal.json` -- new test map

### Modified files (backend)
- `model/Intersection.java` -- add inboundRoadIds, outboundRoadIds, trafficLight reference
- `config/MapConfig.java` -- add SignalPhaseConfig inner class
- `config/MapLoader.java` -- wire intersection road connections, build TrafficLight from config
- `config/MapValidator.java` -- validate intersection signal phases
- `engine/command/SimulationCommand.java` -- add SetLightCycle variant
- `engine/SimulationEngine.java` -- handle SetLightCycle command
- `engine/PhysicsEngine.java` -- accept optional stop line position per lane
- `engine/VehicleSpawner.java` -- despawn only at EXIT nodes, not INTERSECTION nodes
- `scheduler/TickEmitter.java` -- integrate TrafficLightController and IntersectionManager into tick pipeline; add TrafficLightDto to snapshot
- `dto/SimulationStateDto.java` -- add trafficLights list
- `dto/CommandDto.java` -- add intersectionId, phases fields
- `controller/CommandHandler.java` -- handle SET_LIGHT_CYCLE

### New files (frontend)
- `rendering/drawTrafficLights.ts` -- render traffic light indicators

### Modified files (frontend)
- `types/simulation.ts` -- add TrafficLightDto, update SimulationStateDto, update CommandType
- `components/SimulationCanvas.tsx` -- call drawTrafficLights in render loop
- `components/ControlsPanel.tsx` -- add signal timing controls (minimal)
- `store/useSimulationStore.ts` -- store trafficLights in state
- `rendering/constants.ts` -- add traffic light rendering constants

---
*Research completed: 2026-03-28*
