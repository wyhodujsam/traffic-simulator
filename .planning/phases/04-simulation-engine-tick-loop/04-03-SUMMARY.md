---
phase: 4
plan: 3
name: "SimulationController REST Endpoints"
status: complete
completed: "2026-03-28"
---

# Plan 4.3 Summary: SimulationController REST Endpoints

## What was done

### Task 4.3.1: SimulationStatusDto
- Created `SimulationStatusDto` with fields: status, tick, vehicleCount, speedMultiplier, spawnRate, mapId
- Follows existing DTO conventions (Lombok @Data/@Builder/@NoArgsConstructor/@AllArgsConstructor)

### Task 4.3.2: SimulationController
- Created `SimulationController` with `@RestController` and `@RequestMapping("/api")`
- `GET /api/simulation/status` — returns current simulation state by reading from SimulationEngine (status, tick counter, vehicle count across all lanes, speed multiplier, spawn rate, map ID)
- `GET /api/maps` — scans `classpath:maps/*.json` and returns list of map IDs (filename without .json extension)

## Files created
- `backend/src/main/java/com/trafficsimulator/dto/SimulationStatusDto.java`
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`

## Verification
- `mvn compile -q` — passes
- `mvn test -q` — all 38 existing tests pass
- No regressions introduced
