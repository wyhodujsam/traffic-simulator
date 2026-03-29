# Plan 12.1 Summary: Backend — add intersectionSize to MapConfig + clipStart/clipEnd to RoadDto

## Status: DONE

## What was done

### Task 12.1.1: Add intersectionSize to IntersectionConfig
- Added `intersectionSize` field (double, default 0) to `MapConfig.IntersectionConfig`
- Added `"intersectionSize": 40` to `four-way-signal.json` intersection config

### Task 12.1.2: Add clipStart/clipEnd fields to RoadDto
- Added `clipStart` and `clipEnd` double fields to `RoadDto.java`
- Added `clipStart: number` and `clipEnd: number` to frontend `RoadDto` interface in `simulation.ts`

### Task 12.1.3: Compute clip distances in SimulationController /api/roads
- Added `intersectionSize` field (double, default 0) to `Intersection.java` model with `@Builder.Default`
- Populated `intersectionSize` from config in `MapLoader.buildRoadNetwork()`
- Computed `clipStart`/`clipEnd` in `SimulationController.getRoads()` based on intersection sizes at road endpoints (half the intersection size)

## Files modified
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java`
- `backend/src/main/resources/maps/four-way-signal.json`
- `backend/src/main/java/com/trafficsimulator/dto/RoadDto.java`
- `frontend/src/types/simulation.ts`
- `backend/src/main/java/com/trafficsimulator/model/Intersection.java`
- `backend/src/main/java/com/trafficsimulator/config/MapLoader.java`
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`

## Test results
- All 133 backend tests pass
- Backend compiles cleanly after each task
