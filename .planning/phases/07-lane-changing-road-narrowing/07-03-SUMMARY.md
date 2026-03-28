# Plan 7.3 Summary: Road Narrowing — CloseLane Command & Forced Merge

## Status: COMPLETE

## What was done

### Task 1: Add CloseLane to SimulationCommand
Added `CloseLane(String roadId, int laneIndex)` record to the sealed interface and updated the permits clause.

### Task 2: Update CommandDto Javadoc
Updated class Javadoc to document CLOSE_LANE as a valid command type, along with its required fields (`roadId`, `laneIndex`). No new fields needed — reuses existing ones from ADD_OBSTACLE.

### Task 3: Wire CloseLane in CommandHandler
Added `"CLOSE_LANE"` to `VALID_TYPES` set and added switch case mapping to `new SimulationCommand.CloseLane(dto.getRoadId(), dto.getLaneIndex())`.

### Task 4: Handle CloseLane in SimulationEngine
Added full handler in `applyCommand`:
- Validates road and lane index exist
- Guards against closing the last active lane on a road
- Sets `lane.setActive(false)`
- Flags all vehicles in closed lane with `setForceLaneChange(true)`
- Added `lane.setActive(true)` reset in `clearAllVehicles()` (called on Stop)

### Task 5: Create LaneDto and extend RoadDto for REST
- Created `LaneDto.java` with `id`, `laneIndex`, `active` fields
- Added `List<LaneDto> lanes` field to `RoadDto.java`
- Updated `SimulationController.getRoads()` to populate lane DTOs from domain model

## Verification
- `mvn compile -q` -- SUCCESS
- All acceptance criteria grep checks pass for all 5 tasks

## Files modified
- `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java`
- `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java`
- `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `backend/src/main/java/com/trafficsimulator/dto/LaneDto.java` (new)
- `backend/src/main/java/com/trafficsimulator/dto/RoadDto.java`
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`
