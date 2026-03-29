# Plan 08-02 Summary: Intersection Manager & Vehicle Transfers

## Status: COMPLETE

## What was done

### Task 1: Modify VehicleSpawner despawn to EXIT-only roads
- Modified `despawnVehicles()` to build a set of exit road IDs from `DespawnPoint` records
- Only roads with despawn points now allow vehicle removal at lane end
- Updated `VehicleDespawnTest` to include despawn points in test networks
- **Acceptance criteria met**: vehicles on intersection-ending roads no longer despawn

### Task 2: Add stop-line awareness to PhysicsEngine
- Refactored existing `tick(Lane, double)` to delegate to new `tick(Lane, double, double)`
- New overload treats a positive `stopLinePosition` as a virtual stationary leader (speed=0, length=0)
- Stop line only affects vehicles behind it and only when closer than existing leader
- All existing PhysicsEngine tests pass unchanged
- **Acceptance criteria met**: backward-compatible with stop-line extension

### Task 3: Create IntersectionManager
- Created `IntersectionManager` Spring component with:
  - `computeStopLines()` — builds laneId->stopPos map for red-light and box-blocked lanes
  - `processTransfers()` — moves vehicles from inbound road end to outbound road at position 0
  - Box-blocking prevention (checks outbound lane capacity before allowing entry)
  - Random routing among outbound roads (excluding U-turns)
- **Acceptance criteria met**: full intersection transfer logic with safety checks

### Task 4: Wire IntersectionManager into tick pipeline
- Added `TrafficLightController` and `IntersectionManager` as dependencies in `TickEmitter`
- Pipeline order: lights -> stop lines -> spawn -> physics -> lane change -> transfers -> despawn
- Stop lines are computed per-tick and passed to `PhysicsEngine.tick()`
- **Acceptance criteria met**: complete integration with correct ordering

### Prerequisites created (for parallel plan 08-01)
- `TrafficLight` model with phase cycling and `isGreen()` check
- `TrafficLightPhase` model with green road IDs and duration
- `TrafficLightController` stub that advances all SIGNAL intersection lights
- Updated `Intersection` model with `inboundRoadIds`, `outboundRoadIds`, `trafficLight` fields

## Files modified
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java`
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` (new)
- `backend/src/main/java/com/trafficsimulator/engine/TrafficLightController.java` (new)
- `backend/src/main/java/com/trafficsimulator/model/TrafficLight.java` (new)
- `backend/src/main/java/com/trafficsimulator/model/TrafficLightPhase.java` (new)
- `backend/src/main/java/com/trafficsimulator/model/Intersection.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- `backend/src/test/java/com/trafficsimulator/engine/VehicleDespawnTest.java`

## Verification
- `mvn compile` passes
- `mvn test` passes (65 tests, 0 failures)
