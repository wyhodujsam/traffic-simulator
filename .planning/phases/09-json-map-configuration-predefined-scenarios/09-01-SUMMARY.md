---
phase: 9
plan: 1
status: "done"
---

# Plan 09-01 Summary: Backend Map Loading Fixes & MapInfoDto

## What was done

### Task 1: Add `description` field to MapConfig
- Added `@JsonProperty("description") private String description;` after the `name` field
- Field is nullable, so existing maps without it still parse correctly

### Task 2: Add description to existing map JSON files
- **straight-road.json**: "Simple 3-lane, 800m straight road. Good for observing basic car-following and lane change behavior."
- **four-way-signal.json**: "4-approach signalised intersection with green/yellow/all-red cycles. Demonstrates signal timing effects on traffic flow."

### Task 3: Create MapInfoDto
- New DTO at `dto/MapInfoDto.java` with `id`, `name`, `description` fields
- Uses Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`

### Task 4: Enhance GET /api/maps to return MapInfoDto list
- Changed return type from `List<String>` to `List<MapInfoDto>`
- Injects `ObjectMapper` to parse each map JSON and extract metadata
- Returns full metadata objects instead of plain filename strings

### Task 5: Add LOAD_MAP case to REST postCommand
- Added `case "LOAD_MAP" -> new SimulationCommand.LoadMap(dto.getMapId())` to the switch

### Task 6: Fix handleLoadMap in CommandDispatcher
- Added `engine.clearAllVehicles()` call
- Added `obstacleManager.clearAll(oldNetwork)` for obstacle cleanup
- Created `MapLoader.LoadedMap` record returning both `RoadNetwork` and `defaultSpawnRate`
- Wired `defaultSpawnRate` to engine and vehicleSpawner
- Added error feedback via `engine.setLastError()`
- Updated `SimulationEngine.loadDefaultMap()` for new return type

### Task 7: Add error and mapId fields to SimulationStateDto
- Added `mapId` and `error` fields to `SimulationStateDto`
- Updated `SnapshotBuilder.buildSnapshot()` signature with `mapId` and `error` params
- Updated `TickEmitter` to pass mapId/error and clear error after one broadcast

### Task 8: Add clearAll to ObstacleManager
- Added `clearAll(RoadNetwork network)` method that iterates all lanes and calls `clearObstacles()`

## Test results

All 118 tests pass. Fixed 4 test files to accommodate `MapLoader.LoadedMap` return type change.

## Files modified
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java`
- `backend/src/main/java/com/trafficsimulator/config/MapLoader.java`
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`
- `backend/src/main/java/com/trafficsimulator/dto/MapInfoDto.java` (new)
- `backend/src/main/java/com/trafficsimulator/dto/SimulationStateDto.java`
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java`
- `backend/src/main/java/com/trafficsimulator/engine/ObstacleManager.java`
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java`
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java`
- `backend/src/main/resources/maps/straight-road.json`
- `backend/src/main/resources/maps/four-way-signal.json`
- `backend/src/test/java/com/trafficsimulator/config/MapLoaderTest.java`
- `backend/src/test/java/com/trafficsimulator/config/MapLoaderIntersectionTest.java`
- `backend/src/test/java/com/trafficsimulator/engine/TickPipelineIntegrationTest.java`
- `backend/src/test/java/com/trafficsimulator/integration/FullPipelineTest.java`
- `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java`
