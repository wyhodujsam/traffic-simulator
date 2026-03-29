---
phase: 2
plan: 5
name: "SimulationStateDto & Integration"
status: complete
completed_at: "2026-03-27"
---

# Plan 2.5 Summary: SimulationStateDto & Integration

## What Was Built

Phase 2 is now fully complete. This plan replaced the Phase 1 `TickDto` broadcast with a rich `SimulationStateDto` carrying full vehicle state, wired the tick loop to spawn/despawn vehicles, and delivered integration tests proving the end-to-end pipeline.

### Files Created

| File | Description |
|------|-------------|
| `dto/VehicleDto.java` | Projection DTO with pixel coords (x, y, angle) for Canvas rendering |
| `dto/StatsDto.java` | Statistics DTO: vehicleCount, avgSpeed, density, throughput |
| `dto/SimulationStateDto.java` | Top-level broadcast DTO: tick, timestamp, status, vehicles[], stats |
| `scheduler/TickEmitter.java` | Rewired to drain commands, run spawn/despawn, broadcast SimulationStateDto |
| `test/dto/SimulationStateDtoTest.java` | DTO projection math, builder, stopped state (3 tests) |
| `test/engine/CommandQueueTest.java` | 100-thread concurrency, state machine transitions, tick reset (5 tests) |
| `test/integration/FullPipelineTest.java` | End-to-end: map load → spawn → IDM params → despawn (2 tests) |

### Also completed (Plan 02-03 catch-up)

The 02-03 merge commit was missing the Java implementation files. This plan also created:

| File | Description |
|------|-------------|
| `config/MapConfig.java` | Jackson POJO mirroring map JSON schema |
| `config/MapValidator.java` | Structural validation before network construction |
| `config/MapLoader.java` | Loads JSON from classpath, validates, builds RoadNetwork |
| `resources/maps/straight-road.json` | 3-lane 800m fixture with nodes, spawn/despawn points |
| `test/config/MapLoaderTest.java` | 8 tests verifying fixture loading |

## Review Fixes Applied

From `02-REVIEWS.md` concerns:

1. **[HIGH, fixed]** `tickCounter` only increments when status is `RUNNING` — not when PAUSED or STOPPED.
2. **[HIGH, fixed]** Tick duration monitoring: `System.nanoTime()` measured; `WARN` logged if tick exceeds 40ms.
3. **[HIGH, fixed]** `MapValidator.validate()` is now called in `MapLoader.loadFromClasspath()` before `buildRoadNetwork()`.
4. **[HIGH, fixed]** `SetSpawnRate` command now wires through to `VehicleSpawner.setVehiclesPerSecond()` via `@Autowired(required=false)`.
5. **[MEDIUM, documented]** `LANE_WIDTH_PX = 14.0` kept in TickEmitter with a comment documenting the frontend-coupling concern and recommending frontend projection as the cleaner long-term approach.

## Test Results

```
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

All 30 tests across Phase 2 pass:
- `MapLoaderTest` — 8 tests
- `VehicleSpawnerTest` — 7 tests
- `VehicleDespawnTest` — 3 tests
- `SimulationStateDtoTest` — 3 tests
- `CommandQueueTest` — 5 tests
- `FullPipelineTest` — 2 tests
- (+ earlier plan tests from Phase 1)

## Architecture State

`/topic/simulation` now broadcasts:

```json
{
  "tick": 42,
  "timestamp": 1711580400000,
  "status": "RUNNING",
  "vehicles": [
    { "id": "uuid", "laneId": "r1-lane0", "position": 200.5,
      "speed": 16.65, "x": 250.5, "y": 300.0, "angle": 0.0 }
  ],
  "stats": {
    "vehicleCount": 1, "avgSpeed": 16.65,
    "density": 0.125, "throughput": 0.0
  }
}
```

## Requirements Satisfied

ROAD-01, ROAD-05, ROAD-06, SIM-02, SIM-05, SIM-06, INFR-04
