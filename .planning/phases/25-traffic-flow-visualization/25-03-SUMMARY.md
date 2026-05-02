---
phase: 25-traffic-flow-visualization
plan: 03
subsystem: scenario-schema

tags: [scenario, perturbation, schema, ring-road, slow-leader-pulse, idm-override]

# Dependency graph
requires:
  - phase: 25-traffic-flow-visualization
    provides: Plan 02 — RoadNetwork.seed placeholder + SimulationEngine.resolveSeedAndStart consumes it; Plan 03 wires MapConfig.seed → MapLoader → RoadNetwork.seed end-to-end so the seed precedence chain (command > json > auto) becomes effective for JSON-source seeds
provides:
  - MapConfig schema fields seed (D-01), perturbation (D-12), initialVehicles (CONTEXT §Q2) — all optional with @JsonInclude NON_NULL, backwards compatible with existing 7 scenarios
  - MapConfig.PerturbationConfig + MapConfig.InitialVehicleConfig nested DTOs (Lombok @Data @NoArgsConstructor @AllArgsConstructor)
  - RoadNetwork mirrors the 3 fields (Lombok @Data setters generated)
  - MapLoader.buildRoadNetwork propagates MapConfig.{seed,perturbation,initialVehicles} → RoadNetwork
  - IPerturbationManager + PerturbationManager — D-12 slow-leader pulse hook with per-tick cached "vehicle 0" lookup (min spawnedAt + lex tie-break by id per RESEARCH §Pattern 6)
  - PhysicsEngine.tick 5-arg overload accepts IPerturbationManager + currentTick; computeAccelerationWithV0 helper substitutes effectiveV0 only when override active
  - TickEmitter injects IPerturbationManager and forwards (perturbationManager, tick) on every physics step
  - CommandDispatcher.primeInitialVehicles helper called after handleLoadMap and handleLoadConfig — out-of-range road/lane refs silently skipped (T-25-IV-01 mitigation)
  - ring-road.json — 8 chord segments approximating a 2000m perimeter circle (radius ≈318m), 2 lanes, 80 initial vehicles, perturbation block, all PRIORITY intersections (per WAVE0 spike directive)
  - MapLoaderScenarioTest#loadsRingRoad — RING-01 assertion that the new scenario loads cleanly with all 4 schema additions populated end-to-end
affects:
  - 25-04 ring-road KPI baseline run (consumes ring-road.json + perturbation hook)
  - 25-05 KPI suite (per-segment density/queue measured against ring-road steady state)
  - 25-06 NDJSON replay log (header line will record resolved seed when ring-road.json supplies one)
  - 25-07 determinism + RING-01..04 acceptance tests (full Spring-wired ring-road simulation)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Optional schema fields via Lombok @Data + Jackson @JsonInclude(NON_NULL) — additive, backwards compatible with shipped scenarios"
    - "IXxx interface + XxxImpl pattern for testability (IPerturbationManager + PerturbationManager) per CONVENTIONS"
    - "Per-tick cached deterministic lookup (vehicle 0) via {cachedTick, cachedVehicle0Id} guarded fields in PerturbationManager — O(N) once per tick, O(1) thereafter"
    - "PhysicsEngine.tick overload chain — 2-arg → 3-arg → 5-arg, each strictly delegates upward with safe defaults (null perturbationManager + 0L tick) so existing test fixtures compile unchanged"
    - "Lazy initial-vehicle priming in CommandDispatcher — neither MapLoader nor RoadNetwork mutate Lane state; CommandDispatcher.primeInitialVehicles owns the side effect after both handleLoadMap and handleLoadConfig"

key-files:
  created:
    - "backend/src/main/java/com/trafficsimulator/engine/IPerturbationManager.java"
    - "backend/src/main/java/com/trafficsimulator/engine/PerturbationManager.java"
    - "backend/src/main/resources/maps/ring-road.json"
    - "backend/src/test/java/com/trafficsimulator/config/MapConfigSchemaTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/PerturbationManagerTest.java"
  modified:
    - "backend/src/main/java/com/trafficsimulator/config/MapConfig.java (Task 1 — +seed/perturbation/initialVehicles fields + 2 nested DTOs)"
    - "backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java (Task 1 — mirror 3 fields)"
    - "backend/src/main/java/com/trafficsimulator/config/MapLoader.java (Task 1 — propagate 3 fields in buildRoadNetwork)"
    - "backend/src/main/java/com/trafficsimulator/engine/IPhysicsEngine.java (Task 2 — new 5-arg tick overload)"
    - "backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java (Task 2 — 5-arg tick + computeAccelerationWithV0 helper; 3-arg overload now delegates with null/0L)"
    - "backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java (Task 2 — inject IPerturbationManager + forward to physicsEngine.tick at line 121)"
    - "backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java (Task 2 — +primeInitialVehicles, called after both handleLoadMap line 320 and handleLoadConfig line 357)"
    - "backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java (Task 3 — +loadsRingRoad test for RING-01)"

key-decisions:
  - "Used PRIORITY for all 8 ring-road intersections per 25-WAVE0-SPIKE-RESULT.md directive — semantic match for unsignalled junction; spike proved single-inbound-road topology is safe (no schema extension needed)"
  - "Kept the existing 3-arg PhysicsEngine.tick(Lane, double, double) overload so ZipperMergeTest (7 callsites), RoadNarrowingIntegrationTest (3), PhysicsEngineTest (11), and SimulationSteps (1) compile unchanged — the 3-arg overload now delegates with null/0L"
  - "primeInitialVehicles uses road.getSpeedLimit() as the IDM v0 (desired speed) — keeps ring-road KPI comparable to the lane maxSpeed already enforced by Guard 3 in applyGuardsAndIntegrate"
  - "computeAccelerationWithV0 is a private helper that mirrors computeAcceleration verbatim except for the effectiveV0 substitution — kept the non-perturbed code path untouched to avoid any behavioural drift in existing tests"
  - "PerturbationManager caches vehicle-0 id per tick to avoid recomputing min(spawnedAt, id) for every vehicle in the lane on every step — at 80 vehicles × 8 lanes the saving is meaningful and the cache invalidates automatically when currentTick advances"
  - "CommandDispatcher.primeInitialVehicles silently skips out-of-range road/lane refs (logged at DEBUG) per T-25-IV-01 disposition — operator JSON is trusted, no need to throw"

patterns-established:
  - "Pattern 1: Schema extension via optional @JsonInclude(NON_NULL) fields — adds capability without breaking shipped scenarios; loader propagates verbatim, downstream consumers handle null gracefully (DET-04 preserved end-to-end)"
  - "Pattern 2: PhysicsEngine.tick overload chain — 2-arg/3-arg/5-arg each strictly delegate with safe defaults so adding a new dependency (perturbationManager, currentTick) does not force every existing callsite to be touched"
  - "Pattern 3: Per-tick deterministic lookup cache — {cachedTick, cachedVehicle0Id} fields in a Spring @Component invalidate automatically on tick advance; reusable for any 'recompute once per tick' pattern in v3.0 work"

requirements-completed:
  - RING-01
  - DET-04

# Metrics
duration: 13 min
completed: 2026-04-26
---

# Phase 25 Plan 03: Scenario schema + ring-road + D-12 perturbation hook Summary

**MapConfig gains optional seed/perturbation/initialVehicles fields (all backwards compatible with shipped 7 scenarios); ring-road.json (2000m, 2 lanes, 80 vehicles uniformly spaced, 8 PRIORITY intersections per WAVE0 directive) loads end-to-end through the new MapLoader propagation; PerturbationManager hooked into PhysicsEngine.tick via a 5-arg overload that overrides IDM desired speed for the deterministic "vehicle 0" during the configured tick window; CommandDispatcher.primeInitialVehicles inserts vehicles into lanes after both handleLoadMap and handleLoadConfig.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-04-26T15:20:46Z
- **Completed:** 2026-04-26T15:33:55Z
- **Tasks:** 3
- **Files modified:** 11 (5 created + 6 modified)

## Accomplishments

- **D-01 seed JSON path closed end-to-end.** Plan 02 added the placeholder `RoadNetwork.seed` and `SimulationEngine.resolveSeedAndStart(Long)` reads it; Plan 03 added the `MapConfig.seed` JSON field and made `MapLoader.buildRoadNetwork` propagate it. From now on, scenarios that include `"seed": <long>` produce reproducible runs without a STOMP override.
- **D-11 ring-road scenario shipped.** `backend/src/main/resources/maps/ring-road.json` exists with the canonical 8-segment chord approximation of a 2000m perimeter circle, 2 lanes, 80 initial vehicles (10 per segment × 8 segments, all in lane 0 at 17.78 m/s ≈ 80% of speedLimit; density = 20 veh/km/lane = LOS D/E boundary). All 8 intersections are PRIORITY per the WAVE0 spike directive — no schema extension, no fallback needed.
- **D-12 perturbation hook implemented.** `PerturbationManager.getActiveV0(vehicle, currentTick)` returns `targetSpeed` when (a) a perturbation is configured on the active scenario, (b) the current tick is in `[start, start + durationTicks)`, and (c) the supplied vehicle is the deterministic "vehicle 0" (min `spawnedAt` with lex tie-break by id). PhysicsEngine.tick consults this on every per-vehicle step; non-null override replaces vehicle.v0 for that integration step only, then the vehicle resumes normal IDM behaviour.
- **CONTEXT §Q2 initialVehicles primed via CommandDispatcher.** No separate PrimeScenario command needed — `primeInitialVehicles` reads `RoadNetwork.getInitialVehicles()` and inserts via `Lane.addVehicle` after both `handleLoadMap` and `handleLoadConfig`. Out-of-range road/lane refs silently skipped (T-25-IV-01 mitigation).
- **DET-04 regression preserved.** All 7 shipped scenarios (combined-loop, four-way-signal, highway-merge, phantom-jam-corridor, roundabout, straight-road, construction-zone) parse and load via `MapLoader.loadFromClasspath` without exception. Verified by `MapConfigSchemaTest.existingScenariosStillLoad` (Task 1) and the existing 7 `MapLoaderScenarioTest` cases.
- **+11 new tests, full backend suite green.** Backend regression went from 408 → 419 passing tests, 0 failures, 0 errors, 1 skipped (pre-existing). New: 5 schema tests + 5 perturbation tests + 1 ring-road test.

## Task Commits

Each task committed atomically:

1. **Task 1: Extend MapConfig schema (seed, perturbation, initialVehicles); propagate via MapLoader; verify DET-04** — `6e4b53d` (feat)
2. **Task 2: PerturbationManager + IPerturbationManager + PhysicsEngine D-12 hook + TickEmitter wiring + CommandDispatcher.primeInitialVehicles** — `b0eeba9` (feat)
3. **Task 3: ring-road.json scenario file (per D-11 + WAVE0 PRIORITY directive) + MapLoaderScenarioTest#loadsRingRoad (RING-01)** — `0f39d7e` (feat)

**Plan metadata commit:** to follow (this SUMMARY.md commit).

## Files Created/Modified

### Created
- `backend/src/main/java/com/trafficsimulator/engine/IPerturbationManager.java` — D-12 hook contract used by PhysicsEngine.tick
- `backend/src/main/java/com/trafficsimulator/engine/PerturbationManager.java` — Spring @Component implementing D-12 with per-tick cached vehicle-0 resolution
- `backend/src/main/resources/maps/ring-road.json` — D-11 scenario file (8 nodes, 8 roads, 8 PRIORITY intersections, 80 initialVehicles, perturbation block)
- `backend/src/test/java/com/trafficsimulator/config/MapConfigSchemaTest.java` — Task 1 RED→GREEN: 5 tests for schema extension + DET-04 regression
- `backend/src/test/java/com/trafficsimulator/engine/PerturbationManagerTest.java` — Task 2 RED→GREEN: 5 tests for the D-12 hook contract

### Modified
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — Task 1: added `Long seed`, `PerturbationConfig perturbation`, `List<InitialVehicleConfig> initialVehicles` (all `@JsonInclude(NON_NULL)`), plus 2 nested `@Data @NoArgsConstructor @AllArgsConstructor` static classes (lines 49-105)
- `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java` — Task 1: added 3 mirror fields (`Long seed`, `MapConfig.PerturbationConfig perturbation`, `List<MapConfig.InitialVehicleConfig> initialVehicles`) at lines 22-40 (Lombok @Data generates getters/setters)
- `backend/src/main/java/com/trafficsimulator/config/MapLoader.java` — Task 1: added `network.setSeed/setPerturbation/setInitialVehicles` propagation at lines 91-93 (inside `buildRoadNetwork`)
- `backend/src/main/java/com/trafficsimulator/engine/IPhysicsEngine.java` — Task 2: added 5-arg `tick(Lane, double, double, IPerturbationManager, long)` overload (lines 12-25)
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java` — Task 2: 3-arg `tick` now delegates to 5-arg (line 47); 5-arg implementation reads `perturbationManager.getActiveV0` and routes through new private `computeAccelerationWithV0` helper when override active (lines 50-95 for tick, 263+ for helper)
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` — Task 2: added `IPerturbationManager perturbationManager` field (line 44) and forwarded to `physicsEngine.tick(..., perturbationManager, tick)` at line 121
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` — Task 2: imported `MapConfig`; added `primeInitialVehicles(RoadNetwork)` private helper (lines 369-407); call sites in both `handleLoadMap` (line 320) and `handleLoadConfig` (line 357)
- `backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java` — Task 3: added `loadsRingRoad` test asserting 8/8/8/80 shape + perturbation block + null seed + all-PRIORITY intersections

## Decisions Made

- **PRIORITY intersection type for the ring (not NONE, no schema extension).** Per `25-WAVE0-SPIKE-RESULT.md` the spike validated both PRIORITY and NONE on a single-inbound-road ring topology and recommended PRIORITY as the semantic match for unsignalled junctions. The `IntersectionManager.hasVehicleFromRight` short-circuit (Intersection.java line 168, `otherRoadId.equals(inboundRoadId)`) prevents false positives because each ring node has exactly one inbound road.
- **PhysicsEngine.tick overload chain preserved (2-arg → 3-arg → 5-arg).** Adding `IPerturbationManager + currentTick` parameters as a 5-arg overload — and making the existing 3-arg overload delegate with `(null, 0L)` — kept all 22 existing tick callsites in test code (ZipperMergeTest, RoadNarrowingIntegrationTest, PhysicsEngineTest) and 1 in BDD steps compiling without changes. Only `TickEmitter.java` needed updating to pass `perturbationManager + tick` through.
- **`computeAccelerationWithV0` is a verbatim mirror of `computeAcceleration` except for the v0 substitution.** Avoided refactoring the IDM math into a shared helper because risk of behavioural drift on the non-perturbed path was too high; the duplication is small and confined.
- **`primeInitialVehicles` uses `road.getSpeedLimit()` as IDM v0 (desired speed).** Lane's `Guard 3` clamp (`applyGuardsAndIntegrate`) already enforces lane max speed; using the road's speed limit as v0 keeps the ring-road KPI baseline comparable with what `VehicleSpawner` would produce for spawned vehicles (which read v0 from the same source).
- **`PerturbationManager` caches "vehicle 0" per tick.** Recomputing `min(spawnedAt, id)` for every vehicle on every per-vehicle step would be O(N²) per tick across the lane. Cache reduces it to O(N) once per tick + O(1) per vehicle thereafter; cache invalidates automatically on `currentTick` advance.
- **`CommandDispatcher.primeInitialVehicles` silently skips bad refs (logs at DEBUG).** Operator-supplied scenario JSON is trusted (build artifact); throwing on a single bad initial-vehicle entry would block loading an otherwise valid ring scenario. Per T-25-IV-01 disposition.

## Deviations from Plan

None — plan executed exactly as written.

The plan's `<action>` blocks were detailed enough that no deviations were needed. The only minor judgement calls were:
- `road.getSpeedLimit()` for v0 in `primeInitialVehicles` — the plan didn't specify and `33.3` was suggested in the example. Used `road.getSpeedLimit()` instead so initial vehicles match the road's actual speed (consistent with what spawned vehicles get).
- Wave0 directive read confirmed `PRIORITY` (no fallback branch needed); the plan's contingency for `STRAIGHT_THROUGH_FALLBACK` was not exercised.

Both adjustments are documented under "Decisions Made" above; neither is a deviation rule trigger.

## Authentication Gates

None — all work is in-process Java + JSON. No external services touched.

## Issues Encountered

None.

## TDD Gate Compliance

Plan-level type was `execute` but Tasks 1 and 2 were marked `tdd="true"`:
- **Task 1 RED:** `MapConfigSchemaTest` written before MapConfig was extended. Initial `mvn test` failed with `cannot find symbol getSeed/getPerturbation/getInitialVehicles` (compile error — appropriate RED for a new field). After GREEN edits, all 5 tests pass.
- **Task 2 RED:** `PerturbationManagerTest` written before `PerturbationManager` existed. Implementation followed; tests pass on first GREEN run (5/5).
- **Task 3** is non-TDD in the plan — JSON-shape tests written alongside the file in a single commit.

## User Setup Required

None — no external service configuration required. All schema additions, the new scenario file, and the perturbation hook are entirely in-process.

## Next Phase Readiness

- **Plan 25-04 (ring-road KPI baseline)** can proceed — `ring-road.json` loads via `LoadMap("ring-road")`, primes 80 vehicles, and the perturbation hook is wired into PhysicsEngine.tick.
- **Plan 25-05 (KPI suite)** can proceed — vehicles primed via `initialVehicles` carry `spawnedAt=0L`, which is the input to KPI mean-delay computation (D-05).
- **Plan 25-06 (NDJSON replay)** can proceed — `RoadNetwork.seed` is now populated end-to-end from JSON; replay header line can record it.
- **Plan 25-07 (full determinism + RING-01..04 acceptance tests)** unblocked — the byte-identical-tick-stream contract (DET-01) can now be validated against the ring-road scenario.

### Blockers / Concerns

- None. Backend suite green at 419/419. Frontend not touched in this plan.

## Self-Check: PASSED

Verified all claims:

**Files created exist:**
- FOUND: `backend/src/main/java/com/trafficsimulator/engine/IPerturbationManager.java`
- FOUND: `backend/src/main/java/com/trafficsimulator/engine/PerturbationManager.java`
- FOUND: `backend/src/main/resources/maps/ring-road.json`
- FOUND: `backend/src/test/java/com/trafficsimulator/config/MapConfigSchemaTest.java`
- FOUND: `backend/src/test/java/com/trafficsimulator/engine/PerturbationManagerTest.java`

**Commits exist:**
- FOUND: `6e4b53d` (Task 1 — MapConfig schema + propagation + DET-04 regression)
- FOUND: `b0eeba9` (Task 2 — PerturbationManager + PhysicsEngine hook + CommandDispatcher.primeInitialVehicles)
- FOUND: `0f39d7e` (Task 3 — ring-road.json + loadsRingRoad test)

**Acceptance grep checks:**
- PASSED: `grep -q "private Long seed" backend/.../config/MapConfig.java`
- PASSED: `grep -q "PerturbationConfig" backend/.../config/MapConfig.java`
- PASSED: `grep -q "InitialVehicleConfig" backend/.../config/MapConfig.java`
- PASSED: `grep -q "private MapConfig.PerturbationConfig" backend/.../model/RoadNetwork.java`
- PASSED: `grep -q "network.setSeed" backend/.../config/MapLoader.java`
- PASSED: `grep -q "perturbationManager" backend/.../engine/PhysicsEngine.java`
- PASSED: `grep -q "primeInitialVehicles" backend/.../engine/CommandDispatcher.java`
- PASSED: `grep -q "physicsEngine.tick(lane, stepDt, stopLine, perturbationManager, tick)" backend/.../scheduler/TickEmitter.java`

**JSON shape (ring-road.json):**
- PASSED: 8 nodes, 8 roads, 8 intersections, 80 initialVehicles
- PASSED: perturbation.tick=200, perturbation.targetSpeed=5.0, perturbation.durationTicks=60
- PASSED: All intersections type "PRIORITY" (per WAVE0 directive)

**Test counts:**
- PASSED: MapConfigSchemaTest 5/5 pass (Task 1)
- PASSED: PerturbationManagerTest 5/5 pass (Task 2)
- PASSED: MapLoaderScenarioTest#loadsRingRoad 1/1 pass (Task 3 — RING-01 closed)
- PASSED: PhysicsEngineTest 9/9 pass (existing — no regression from new tick signature)
- PASSED: Full backend regression 419/419 pass, 0 failures, 0 errors, 1 skipped (pre-existing); +11 from Plan 03 (was 408 after Plan 02)

---
*Phase: 25-traffic-flow-visualization*
*Completed: 2026-04-26*
