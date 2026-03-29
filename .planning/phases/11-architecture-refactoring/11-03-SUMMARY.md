---
phase: 11
plan: 3
status: done
---

# Plan 11-03 Summary: Extract SnapshotBuilder from TickEmitter

## What was done

Extracted the ~135-line snapshot-building logic from `TickEmitter` into a dedicated `SnapshotBuilder` component, separating scheduling concerns from DTO projection.

### Task 1: Create SnapshotBuilder component
- Created `SnapshotBuilder.java` (163 lines) as a Spring `@Component`
- Moved `LANE_WIDTH_PX`, `buildSnapshot()`, `projectVehicle()`, `projectObstacle()` from TickEmitter
- Method signature accepts parameters that TickEmitter had as fields (network, tick, status, spawnRate, speedMultiplier, vehicleSpawner)

### Task 2: Wire SnapshotBuilder into TickEmitter
- Added `SnapshotBuilder` as constructor-injected dependency via `@RequiredArgsConstructor`
- Replaced internal `buildSnapshot()` call with delegation to `snapshotBuilder.buildSnapshot()`
- Removed `LANE_WIDTH_PX`, `buildSnapshot()`, `projectVehicle()`, `projectObstacle()` from TickEmitter
- TickEmitter reduced from 263 lines to 114 lines (pure orchestrator)

### Task 3: Add SnapshotBuilder unit tests
- Created `SnapshotBuilderTest.java` with 5 tests:
  - `testVehicleProjection` — verifies pixel coordinate calculation for vehicle at 50% position
  - `testObstacleProjection` — verifies pixel coords and angle for obstacle on diagonal road
  - `testStatsCalculation` — verifies avgSpeed, vehicleCount, and density for 3 vehicles
  - `testEmptyNetwork` — verifies graceful handling of null network
  - `testTrafficLightDtos` — verifies traffic light DTO state and position

## Test results

All 99 tests pass (94 existing + 5 new), 0 failures.

## Files modified
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` (new, 163 lines)
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` (reduced from 263 to 114 lines)
- `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java` (new, 206 lines)
