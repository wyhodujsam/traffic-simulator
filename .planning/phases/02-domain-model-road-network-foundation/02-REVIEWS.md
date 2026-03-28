---
phase: 2
reviewers: [claude]
reviewed_at: 2026-03-28T09:00:00Z
plans_reviewed: [02-01-PLAN.md, 02-02-PLAN.md, 02-03-PLAN.md, 02-04-PLAN.md, 02-05-PLAN.md]
---

# Phase 2 Plan Review

## Plan 2.1: Domain Model Classes

**Strengths:**
- Clean IDM parameter set on Vehicle — correct fields for Intelligent Driver Model
- Java 17 records for SpawnPoint/DespawnPoint — good immutable data carriers
- Lane.getLeader() is simple and correct for single-lane scenarios
- Intersection stub with enum avoids premature complexity

**Concerns:**

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | `Lane.getLeader()` is O(n) linear scan per vehicle per tick. With 500 vehicles/lane at 20Hz, this becomes a hot path. Sorted list or index needed by Phase 3. |
| 2 | **MEDIUM** | `Vehicle` has `Lane lane` back-reference — circular reference will cause infinite recursion in Jackson serialization if domain objects are ever accidentally serialized directly (e.g., debug logging with `@ToString`). Lombok `@Data` generates `toString()` including `lane`, which includes `vehicles`, which includes `vehicle`... |
| 3 | **MEDIUM** | `Lane` has `@Builder` but no `@NoArgsConstructor`/`@AllArgsConstructor` — `@Builder` alone doesn't generate a no-arg constructor, which Jackson needs if Lane is ever deserialized. Not a problem now but fragile. |
| 4 | **LOW** | `laneCount` hard-capped at 4 in MapValidator but no corresponding constraint on the model. Nothing stops programmatic creation of 10-lane roads. |

**Suggestions:**
- Add `@ToString.Exclude` on `Vehicle.lane` and `Lane.road` to break circular toString
- Document that `getLeader()` must be replaced with sorted-list lookup in Phase 3 (IDM tick)
- Consider `TreeMap<Double, Vehicle>` keyed by position for O(log n) leader lookup

**Risk:** LOW — straightforward data classes, main risk is the O(n) leader lookup becoming a bottleneck silently.

---

## Plan 2.2: Command Queue Pattern

**Strengths:**
- Sealed interface with records — exhaustive pattern matching, compile-time safety
- `LinkedBlockingQueue` + `drainTo` — correct pattern for producer-consumer across threads
- Clean separation: CommandHandler never touches simulation state, only enqueues
- `volatile` on status field — correct for cross-thread visibility

**Concerns:**

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | `SetSpawnRate` and `SetSpeedMultiplier` commands are logged but **not stored anywhere**. When Plan 2.5 wires TickEmitter, spawn rate changes via STOMP will be silently dropped. The `applyCommand` needs to actually call `vehicleSpawner.setVehiclesPerSecond(rate)`. |
| 2 | **MEDIUM** | No state machine validation — `Resume` when `STOPPED`, `Pause` when already `PAUSED`, `Start` when `RUNNING` are all silently accepted. Could cause confusing behavior (e.g., Start resets to RUNNING from PAUSED, losing pause intent). |
| 3 | **MEDIUM** | `SimulationEngine` is `@Component` singleton but has no dependency on `VehicleSpawner` — the `SetSpawnRate` command cannot reach the spawner. This is a wiring gap between Plan 2.2 and 2.4. |
| 4 | **LOW** | `CommandDto` uses `String type` instead of an enum — typos in frontend will produce `IllegalArgumentException` with no helpful error message sent back via STOMP. |

**Suggestions:**
- Inject `VehicleSpawner` into `SimulationEngine` or pass a callback map for command side-effects
- Add state guard: `Start` only from STOPPED, `Resume` only from PAUSED, `Pause` only from RUNNING
- Return error responses on `/topic/errors` for unknown command types

**Risk:** MEDIUM — the spawn rate wiring gap is a real integration bug that will surface in Plan 2.5.

---

## Plan 2.3: MapLoader & JSON Config

**Strengths:**
- Clean separation: MapConfig (JSON shape) vs domain model (runtime shape)
- MapValidator catches structural errors before construction
- Good test coverage — 7 tests covering happy path, edge cases, error handling
- Fixture JSON is realistic with proper coordinates

**Concerns:**

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | `MapValidator` is created but **never called** by `MapLoader`. The loader goes straight from JSON parse to `buildRoadNetwork()`. Validation is dead code. |
| 2 | **MEDIUM** | `MapLoader.loadFromClasspath()` uses `getClass().getClassLoader().getResourceAsStream()` — this works in unit tests but may fail in fat JAR deployments where classpath resource loading behaves differently. Spring's `ResourceLoader` or `ClassPathResource` is safer. |
| 3 | **MEDIUM** | `MapConfig.intersections` and `spawnPoints`/`despawnPoints` can be `null` from JSON (no `@Builder.Default` equivalent for Jackson). `buildRoadNetwork()` will NPE on `config.getIntersections()` if JSON omits the field. The fixture has `"intersections": []` but other maps might not. |
| 4 | **LOW** | `SpawnPointConfig.laneIndex` is not validated against the actual `laneCount` of the referenced road. A spawn point referencing lane 5 on a 3-lane road will cause `IndexOutOfBoundsException` in `MapLoader.buildRoadNetwork()` → `road.getLanes().get(sp.laneIndex())`. |

**Suggestions:**
- Call `mapValidator.validate(config)` in `loadFromClasspath()` before `buildRoadNetwork()`, throw if errors non-empty
- Add null-safe defaults: `if (config.getIntersections() == null) config.setIntersections(List.of())`
- Validate spawn point `laneIndex < road.laneCount` in MapValidator
- Add a test for MapValidator specifically

**Risk:** MEDIUM — the unused validator is the main concern. Validation exists but provides zero protection.

---

## Plan 2.4: VehicleSpawner & Despawn

**Strengths:**
- Accumulator pattern is correct — handles fractional spawn rates and variable dt cleanly
- Overlap prevention with MIN_SAFE_GAP prevents physics explosions from overlapping vehicles
- s0 not randomized — good call, minimum gap is safety-critical
- `despawnVehicles` as static utility is clean and testable
- Good test coverage including edge cases (empty network, rate change, reset)

**Concerns:**

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | When all spawn points are blocked, `spawnAccumulator += 1.0` re-adds the budget. But the `while (spawnAccumulator >= 1.0)` loop will immediately try again on the same tick, creating an **infinite loop** if all spawn points stay blocked. The re-add should happen *after* the while loop breaks, not inside `trySpawnOne`. |
| 2 | **MEDIUM** | `despawnVehicles` uses `lane.getVehicles().removeIf()` — if Lane's vehicle list is being iterated elsewhere concurrently (e.g., TickEmitter building snapshot while despawn runs), this is a `ConcurrentModificationException`. Currently single-threaded in TickEmitter but fragile. |
| 3 | **MEDIUM** | Vehicles spawn at `speed = 0.0` — on a 120 km/h road, a stationary vehicle at the spawn point will immediately trigger emergency braking in following vehicles (once IDM is active in Phase 3). Consider spawning at a fraction of lane maxSpeed. |
| 4 | **LOW** | `despawnVehicles` uses `log.debug` but the method is `static` — Lombok's `@Slf4j` generates an instance logger, so the static method references the instance `log`. This won't compile. |

**Suggestions:**
- Fix the infinite loop: move `spawnAccumulator += 1.0` outside the while loop, add a `boolean spawned` flag
- Spawn vehicles at `0.5 * lane.maxSpeed` or `v0 * 0.5` to avoid day-1 congestion at entry
- Make `despawnVehicles` an instance method, or use a separate static logger

**Risk:** **HIGH** — the infinite loop bug is a showstopper. If 3 vehicles park near spawn points, the tick thread hangs forever.

---

## Plan 2.5: SimulationStateDto & Integration

**Strengths:**
- Clean DTO hierarchy: SimulationStateDto > VehicleDto + StatsDto
- Pixel projection math is correct for horizontal roads
- Lane offset calculation centers lanes around road centerline
- Integration test validates full pipeline end-to-end
- CommandQueueTest with 100 threads validates concurrency

**Concerns:**

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | `TickEmitter` removes the Phase 1 `TickDto` but the existing Phase 1 frontend likely subscribes to `/topic/simulation` expecting the old format. This is a breaking change with no migration path — frontend will receive `SimulationStateDto` and likely crash or show nothing. Plan doesn't mention frontend updates. |
| 2 | **HIGH** | `TickEmitter.emitTick()` runs spawn + despawn + snapshot build on `@Scheduled` thread. With 500+ vehicles, `buildSnapshot()` iterates all vehicles, computes projections, builds DTOs — if this exceeds 50ms, ticks will queue up and lag behind real-time. No timing guard or skip mechanism. |
| 3 | **MEDIUM** | `TickEmitter` increments `tickCounter` even when STOPPED/PAUSED. The tick number keeps climbing regardless of simulation state, which means tick count doesn't reflect simulation time. |
| 4 | **MEDIUM** | `throughput` is hardcoded to `0.0` with comment "deferred to Phase 5" — but StatsDto is broadcast to frontend. Frontend may display misleading zero throughput. |
| 5 | **LOW** | `LANE_WIDTH_PX = 14.0` is a rendering constant baked into the backend. This couples backend to frontend pixel layout. If Canvas scale changes, backend must be redeployed. |

**Suggestions:**
- Only increment tickCounter when status is RUNNING
- Add tick duration monitoring: `long start = System.nanoTime()` and warn if tick exceeds 40ms
- Move pixel projection to frontend — backend sends `(position, laneId, roadId)`, frontend computes (x, y, angle) from its own road geometry. This is cleaner separation and eliminates LANE_WIDTH_PX coupling.
- Document the frontend breaking change or add a Phase 2 frontend update task

**Risk:** MEDIUM — the frontend breakage is expected (Phase 2 is "domain only, no rendering yet"), but the tick timing concern will compound as vehicle count grows.

---

## Cross-Plan Issues

| # | Severity | Issue |
|---|----------|-------|
| 1 | **HIGH** | **Infinite loop in VehicleSpawner** (Plan 2.4) — blocks the tick thread, freezes the entire simulation |
| 2 | **HIGH** | **MapValidator never called** (Plan 2.3) — invalid maps will produce cryptic NPEs instead of validation errors |
| 3 | **HIGH** | **SetSpawnRate command is a no-op** (Plan 2.2) — STOMP command accepted but spawn rate never changes |
| 4 | **MEDIUM** | **Circular toString** (Plan 2.1) — Vehicle↔Lane↔Road will stack overflow on debug logging |
| 5 | **MEDIUM** | **Static method using instance logger** (Plan 2.4) — `despawnVehicles` won't compile |

## Wave Dependencies

Wave 1 (Plans 2.1, 2.2) — parallel, no issues.
Wave 2 (Plans 2.3, 2.4) — both depend on 2.1 only, can run parallel. Correct.
Wave 3 (Plan 2.5) — depends on 2.3 + 2.4. Correct, but needs the wiring fix from 2.2 (spawn rate command).

## Overall Verdict

The plans are well-structured with good test coverage and clean architecture. The three HIGH issues (infinite loop, unused validator, no-op spawn rate command) are all fixable with small changes before execution. The domain model and command pattern choices are solid foundations for subsequent phases.

---

## Consensus Summary

Single reviewer (Claude). Key findings prioritized below.

### Top Concerns (Action Required)

1. **HIGH — Infinite loop in VehicleSpawner** (Plan 2.4): When all spawn points are blocked, `spawnAccumulator += 1.0` inside the while loop creates infinite iteration. Fix: move re-add outside loop, add max-attempts guard.
2. **HIGH — MapValidator never called** (Plan 2.3): Validator exists but `MapLoader` never invokes it. Fix: call `validate()` before `buildRoadNetwork()`.
3. **HIGH — SetSpawnRate command is a no-op** (Plan 2.2): Command is logged but doesn't reach VehicleSpawner. Fix: inject spawner into SimulationEngine.

### Medium Concerns

4. Circular toString (Vehicle↔Lane↔Road) — add `@ToString.Exclude`
5. Static method using instance logger in `despawnVehicles` — won't compile
6. Vehicles spawn at speed=0 causing immediate braking cascade
7. `tickCounter` increments when PAUSED
8. Frontend breaking change from TickDto→SimulationStateDto (expected — Phase 2 is domain-only)

### Strengths Confirmed

- Clean domain model with correct IDM parameters
- Sealed interface + records for commands — compile-time exhaustive
- Accumulator-based spawning handles fractional rates correctly
- Good test coverage (22 tests across 5 classes)
- Correct thread safety pattern (LinkedBlockingQueue + drainTo)
