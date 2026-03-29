---
phase: 5
plan: 1
status: done
---

# Plan 5.1 Summary: Backend gaps — RoadDto endpoint, throughput tracking, SetMaxSpeed command

## Completed Tasks

### Task 5.1.1: RoadDto and GET /api/roads endpoint
- Created `RoadDto.java` with fields: id, name, laneCount, length, speedLimit, startX/Y, endX/Y
- Added `GET /api/roads` endpoint to `SimulationController` returning road geometry from loaded network

### Task 5.1.2: Throughput tracking with rolling 60-second despawn counter
- Added `despawnTimestamps` deque to `VehicleSpawner` with 60s rolling window
- Records timestamp on each vehicle despawn in `despawnVehicles()`
- Added `getThroughput()` method that evicts stale entries and returns count
- Wired into `TickEmitter.buildSnapshot()` replacing hardcoded `throughput(0.0)`
- Clears deque on `reset()`

### Task 5.1.3: SetMaxSpeed command for CTRL-04
- Added `SetMaxSpeed(double maxSpeedMs)` record to `SimulationCommand` sealed interface
- Added `maxSpeed` field to `CommandDto`
- Added `SET_MAX_SPEED` case to `CommandHandler` switch and `VALID_TYPES`
- Added `maxSpeed` field (default 33.33 m/s) to `SimulationEngine` with handler that updates all lane speeds
- Added `maxSpeed` to `SimulationStatusDto` and wired in `getStatus()`

## Verification
- `mvn compile -q` exits 0
- `mvn test` — 44 tests, 0 failures
- All acceptance criteria grep checks pass
