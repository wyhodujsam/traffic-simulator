# Plan 11-01 Summary: Encapsulate Collections in Lane and RoadNetwork

## Status: COMPLETE

## What was done

Encapsulated `vehicles` and `obstacles` collections in `Lane.java` behind controlled mutation methods, eliminating direct mutable list access across the entire codebase.

### Changes by task

**Task 1 - Lane mutation methods:**
- Replaced `@Data` with explicit `@Getter`/`@Setter`/`@EqualsAndHashCode`/`@ToString`
- Suppressed Lombok getters on `vehicles` and `obstacles` fields
- Added: `addVehicle`, `removeVehicle`, `removeVehiclesIf`, `getVehicleCount`, `getVehiclesView`, `clearVehicles`
- Added: `addObstacle`, `removeObstacle`, `removeObstaclesIf`, `getObstaclesView`, `clearObstacles`
- Both `removeVehiclesIf` and `removeObstaclesIf` return `boolean` for callers that need it

**Task 2 - RoadNetwork query methods:**
- Added `getAllLanes()`, `getAllVehicles()`, `findRoad(id)`, `findLane(laneId)`

**Task 3 - PhysicsEngine:** Uses `new ArrayList<>(getVehiclesView())` for sort-in-place, `getObstaclesView()` for read-only iteration.

**Task 4 - VehicleSpawner:** `addVehicle`, `getVehiclesView`, `removeVehiclesIf`.

**Task 5 - LaneChangeEngine:** All 11 call sites migrated to `getVehiclesView`/`getObstaclesView`/`removeVehicle`/`addVehicle`.

**Task 6 - IntersectionManager & ObstacleManager:** Replaced iterator pattern with collect-then-remove in `transferVehiclesFromLane`. ObstacleManager uses `addObstacle`/`removeObstaclesIf`/`getObstaclesView`.

**Task 7 - SimulationEngine, TickEmitter, SimulationController, tests:**
- Migrated all remaining production code including `SimulationController` (not in original plan but had references)
- Removed deprecated `getVehicles()` and `getObstacles()` from Lane
- Updated all 10 test files to use new API

## Files modified

- `backend/src/main/java/com/trafficsimulator/model/Lane.java`
- `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java`
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java`
- `backend/src/main/java/com/trafficsimulator/engine/LaneChangeEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java`
- `backend/src/main/java/com/trafficsimulator/engine/ObstacleManager.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- 10 test files updated

## Verification

- All 86 tests pass after each task
- Zero `Lane.getVehicles()` / `Lane.getObstacles()` calls remain in production code
- Only `SimulationStateDto.getVehicles()` (DTO, not Lane) remains in grep output -- correct
