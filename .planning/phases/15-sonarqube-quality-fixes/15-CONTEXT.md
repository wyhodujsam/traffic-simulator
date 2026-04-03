# Phase 15: SonarQube Code Quality Fixes - Context

**Gathered:** 2026-04-03
**Status:** Ready for planning
**Source:** SonarQube scan (localhost:9000, project: traffic-simulator)

<domain>
## Phase Boundary

Fix all 20 BLOCKER and CRITICAL violations reported by SonarQube Community Edition scan. Pure refactoring — no behavior changes, all 140 tests must pass before and after.

</domain>

<decisions>
## Implementation Decisions

### SQ-01: Tests without assertions [BLOCKER]
- `CommandQueueTest.java:33` — test case without assertion (java:S2699)
- `VehicleSpawnerTest.java:99` — test case without assertion (java:S2699)
- Fix: add meaningful assertions (not just `assertTrue(true)`)

### SQ-02: MapValidator.validate() complexity 103 [CRITICAL]
- Single monolithic method doing all validation
- Fix: extract per-section validators: `validateNodes()`, `validateRoads()`, `validateIntersections()`, `validateSpawnPoints()`, `validateDespawnPoints()`
- Also SQ-07: extract constants `ROAD_PREFIX = "Road "`, `INTERSECTION_PREFIX = "Intersection "`

### SQ-03: SnapshotBuilder.buildSnapshot() complexity 77 [CRITICAL]
- One method building vehicles, obstacles, traffic lights, stats
- Fix: extract `collectVehiclesAndObstacles()`, `collectTrafficLights()`, `computeStats()`

### SQ-04: LaneChangeEngine — 4 methods [CRITICAL]
- `evaluateLaneChanges()` line 79, complexity 50
- `evaluateMOBIL()` line 141, complexity 37
- `processZipperMerge()` line 417, complexity 29
- `findMergeTarget()` line 454, complexity 16
- Fix: extract helper methods, reduce nesting with early returns and guard clauses

### SQ-05: IntersectionManager — 5 methods [CRITICAL]
- `processTransfers()` line 247, complexity 31
- `canEnterIntersection()` line 74, complexity 27
- `hasVehicleFromRight()` line 195, complexity 23
- `checkDeadlocks()` line 319, complexity 19
- `transferVehiclesFromLane()` line 373, complexity 18
- Fix: extract per-intersection-type logic, reduce nesting

### SQ-06: PhysicsEngine, TickEmitter, SimulationController [CRITICAL]
- `PhysicsEngine.tick()` line 43, complexity 37
- `TickEmitter.emitTick()` line 40, complexity 20
- `SimulationController` line 124, complexity 16
- Fix: extract helper methods, early returns

### SQ-07: Duplicated literals in MapValidator [CRITICAL]
- "Road " used 5 times, "Intersection " used 3 times
- Fix: extract to `private static final String` constants
- Bundled with SQ-02 (same file)

### Rules
- **MUST NOT change behavior** — pure refactoring
- **All 140 tests must pass** after each change
- **No new dependencies** — just code reorganization
- Verify with `mvn test` after each file change

</decisions>

<canonical_refs>
## Canonical References

### Files to refactor
- `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — SQ-02, SQ-07
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` — SQ-03
- `backend/src/main/java/com/trafficsimulator/engine/LaneChangeEngine.java` — SQ-04
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` — SQ-05
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java` — SQ-06
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` — SQ-06
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java` — SQ-06

### Tests to fix
- `backend/src/test/java/com/trafficsimulator/engine/CommandQueueTest.java` — SQ-01
- `backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTest.java` — SQ-01

</canonical_refs>

<specifics>
## Specific Ideas

- MapValidator: use a `List<String> errors` pattern — each sub-validator appends to list, main method just joins
- SnapshotBuilder: builder pattern already used, just extract collection loops into private methods
- LaneChangeEngine: extract `isSafeLaneChange()`, `computeIncentive()`, `findBestGap()` from evaluateMOBIL
- IntersectionManager: extract `canEnterSignalIntersection()`, `canEnterPriorityIntersection()`, `canEnterRoundabout()`

</specifics>

<deferred>
## Deferred Ideas

- MAJOR/MINOR SonarQube issues — address in future phase
- Code coverage improvements — separate concern

</deferred>

---

*Phase: 15-sonarqube-quality-fixes*
*Context gathered: 2026-04-03 via SonarQube scan*
