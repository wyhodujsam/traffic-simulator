# Plan 11-04 Summary: Extract CommandDispatcher from SimulationEngine

## Status: DONE

## What was done

### Task 1: Created CommandDispatcher component
- Extracted the 150-line `applyCommand()` if-else chain from SimulationEngine into a dedicated `CommandDispatcher` @Component
- Implemented full `handleLoadMap()` handler (was previously a stub that only logged — bug C2 fix)
- LoadMap stops simulation, resets tick counter, resets spawner, and loads the new map via MapLoader

### Task 2: Refactored SimulationEngine to delegate to CommandDispatcher
- Removed `applyCommand()` method entirely from SimulationEngine
- `drainCommands()` now delegates to `commandDispatcher.dispatch()` for each command
- Moved `VehicleSpawner`, `ObstacleManager` dependencies from SimulationEngine to CommandDispatcher
- Made `clearAllVehicles()` package-private for CommandDispatcher access
- Added `@Setter` for `status`, `spawnRate`, `speedMultiplier`, `maxSpeed` fields
- Used `@Lazy` on CommandDispatcher injection to break circular dependency
- SimulationEngine reduced from ~255 lines to 105 lines

### Task 3: Added CommandDispatcher tests (8 tests)
- `testStartCommand` — verifies STOPPED -> RUNNING transition
- `testStopCommand` — verifies RUNNING -> STOPPED with tick counter reset
- `testPauseResumeRoundTrip` — verifies Start -> Pause -> Resume state transitions
- `testSetSpawnRate` — verifies spawn rate propagation
- `testSetSpeedMultiplier` — verifies speed multiplier propagation
- `testCloseLane` — verifies lane deactivation on 3-lane road
- `testCloseLaneLastLaneRejected` — verifies last-lane protection (no-op)
- `testLoadMap` — verifies map loading with real MapLoader, status reset to STOPPED
- Updated existing CommandQueueTest to wire CommandDispatcher via reflection (required after refactor)

## Metrics
- **Tests**: 94 total (86 existing + 8 new), 0 failures
- **SimulationEngine**: 105 lines (was ~255)
- **CommandDispatcher**: 240 lines (new)

## Files modified
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` (new)
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` (refactored)
- `backend/src/test/java/com/trafficsimulator/engine/CommandDispatcherTest.java` (new)
- `backend/src/test/java/com/trafficsimulator/engine/CommandQueueTest.java` (updated)
