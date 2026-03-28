---
phase: 2
plan: 4
status: complete
completed_at: "2026-03-27"
---

# Summary: Plan 2.4 — VehicleSpawner & Despawn

## Outcome

VehicleSpawner implemented with accumulator-based spawn rate, round-robin spawn point selection, overlap prevention, and ±20% IDM parameter noise. Despawn logic removes vehicles past lane length. All 11 unit tests pass. Backend compiles clean.

## Files Created

| File | Type | Notes |
|------|------|-------|
| `engine/VehicleSpawner.java` | `@Component @Slf4j` class | Accumulator spawn, round-robin, overlap prevention, despawn |
| `engine/VehicleSpawnerTest.java` | JUnit 5 tests | 8 tests covering all spawn behaviors |
| `engine/VehicleDespawnTest.java` | JUnit 5 tests | 3 tests covering despawn edge cases |

## Review Feedback Applied

| Concern | Severity | Resolution |
|---------|----------|------------|
| Infinite loop bug: spawnAccumulator += 1.0 inside while loop | HIGH | `trySpawnOne` returns `boolean`; re-add + `break` outside the while loop only when spawn fails |
| `despawnVehicles` static method can't use @Slf4j instance logger | MEDIUM | Changed to instance method; test updated to use `new VehicleSpawner().despawnVehicles(network)` |
| Vehicles spawn at speed=0.0 causing braking cascade | MEDIUM | Spawn at `0.5 * lane.getMaxSpeed()`; test updated to assert `0.5 * maxSpeed` |

## Key Design Decisions

- `spawnAccumulator += dt * vehiclesPerSecond` each tick; spawn when >= 1.0
- `trySpawnOne` returns `false` when all spawn points blocked → outer while breaks, accumulator restored
- `maxAttempts = spawnPoints.size()` prevents trying same blocked point twice in one call
- `MIN_SAFE_GAP = s0 + vehicleLength = 6.5m` for overlap prevention
- `vary(base) = base * (0.8 + random * 0.4)` gives ±20% uniform noise
- `s0` is fixed at 2.0m (NOT randomised — safety-critical minimum gap)
- Initial speed = `0.5 * lane.maxSpeed` (~16.7 m/s on 120 km/h road) — avoids day-1 entry congestion

## Verification

- `mvn compile -q` exits 0
- `mvn test -Dtest="VehicleSpawnerTest,VehicleDespawnTest"` exits 0
- VehicleSpawnerTest: 8 @Test methods
- VehicleDespawnTest: 3 @Test methods
- Total: 11 tests, 11 pass

## Must-Haves Checklist

- [x] Accumulator-based spawn rate: `spawnAccumulator += dt * vehiclesPerSecond` (SIM-05)
- [x] Overlap prevention: no spawn if nearest vehicle < 6.5m from spawn position
- [x] IDM parameters randomised ±20% via `base * (0.8 + random * 0.4)` (SIM-02)
- [x] `s0` is NOT randomised — fixed at 2.0m (safety-critical constant)
- [x] Despawn on `position >= lane.getLength()` using `removeIf` (SIM-06)
- [x] Round-robin spawn point selection
- [x] All 11 unit tests pass (8 spawner + 3 despawn)

## Git Commits

- `feat(02-04): Task 2.4.1 — VehicleSpawner with accumulator-based spawn rate`
- `test(02-04): Task 2.4.2 — VehicleSpawnerTest (8 tests)`
- `test(02-04): Task 2.4.3 — VehicleDespawnTest (3 tests)`
