# Plan 4.2 Summary: StatePublisher, Stop Reset, and Map Auto-Load

## Status: COMPLETE

## What was done

### Task 4.2.1: Create StatePublisher component
- Created `StatePublisher.java` in `engine/` package
- Wraps `SimpMessagingTemplate` with a `broadcast(SimulationStateDto)` method
- Single responsibility: broadcast state to `/topic/simulation`

### Task 4.2.2: Wire StatePublisher into TickEmitter
- Replaced direct `SimpMessagingTemplate` dependency with `StatePublisher`
- TickEmitter now calls `statePublisher.broadcast(state)` instead of `messagingTemplate.convertAndSend()`
- Removed unused `SimpMessagingTemplate` import

### Task 4.2.3: Fix Stop command to clear vehicles and reset spawner
- Stop command now calls `clearAllVehicles()` to remove all vehicles from all lanes
- Stop command calls `vehicleSpawner.reset()` to reset spawn accumulator and index
- Added private `clearAllVehicles()` method that iterates all roads/lanes

### Task 4.2.4: Auto-load default map at startup
- Added `@PostConstruct loadDefaultMap()` to `SimulationEngine`
- Loads `maps/straight-road.json` via `MapLoader` at application startup
- MapLoader wired as `@Autowired(required = false)` to keep tests working

## Files modified
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`

## Files created
- `backend/src/main/java/com/trafficsimulator/engine/StatePublisher.java`

## Verification
- `mvn compile` passes
- All 38 tests pass (0 failures, 0 errors)
- Acceptance criteria for all 4 tasks met
