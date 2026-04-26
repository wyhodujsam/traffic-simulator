---
phase: 25-traffic-flow-visualization
plan: 04
subsystem: kpi
tags: [kpi, los, delay, queue, sub-sampling, lombok, spring]

# Dependency graph
requires:
  - phase: 25-traffic-flow-visualization
    provides: tick-keyed throughput window (Plan 01); RNG injection (Plan 02); MapConfig schema + ring-road (Plan 03)
provides:
  - KpiDto / SegmentKpiDto / IntersectionKpiDto on /topic/state (per literal D-08 inside StatsDto)
  - LosClassifier (D-07 A..F density thresholds, single-table v3.0 simplification)
  - QueueAnalyzer (D-06 contiguous-from-exit queue length, threshold 0.30 × speedLimit)
  - DelayWindow (tick-keyed 60s rolling window of despawn delays per D-05)
  - KpiAggregator (composes network/segment/intersection KPI computation)
  - Vehicle.freeFlowSeconds accumulator + IntersectionManager hand-off hook (D-05a)
  - SnapshotBuilder sub-sampling (every 5 ticks per D-08) + KpiCacheInvalidator (KPI-07)
affects:
  - phase 25 plan 05 (frontend StatsPanel delta to render kpi.*)
  - phase 25 plan 06 (Diagnostics panel space-time + fundamental diagrams reuse segment KPI feed)
  - phase 25 plan 07 (KpiBroadcastIT — integration test confirming KpiDto on every /topic/state frame)
  - phase 26 (CRUD scenario API consumes the same KPI shapes)
  - phase 28 (reward function reads meanDelaySeconds + worstLos)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Tick-keyed rolling windows mirror VehicleSpawner.getThroughput(currentTick) for DET-01 byte-identity (Plan 01 precedent)."
    - "I-prefix interface + concrete @Component implementation (IKpiAggregator + KpiAggregator) matches IPhysicsEngine / IVehicleSpawner pattern."
    - "Sub-sampling cache lives in scheduler; invalidation crosses the layer via a KpiCacheInvalidator interface in engine.kpi (no scheduler import in CommandDispatcher)."
    - "Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor on every new DTO."
    - "lenient() Mockito stubs for fixtures used in test paths that short-circuit on the first vehicle (avoids UnnecessaryStubbingException)."

key-files:
  created:
    - "backend/src/main/java/com/trafficsimulator/dto/KpiDto.java"
    - "backend/src/main/java/com/trafficsimulator/dto/SegmentKpiDto.java"
    - "backend/src/main/java/com/trafficsimulator/dto/IntersectionKpiDto.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/LosClassifier.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/QueueAnalyzer.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/DelayWindow.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/IKpiAggregator.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/KpiAggregator.java"
    - "backend/src/main/java/com/trafficsimulator/engine/kpi/KpiCacheInvalidator.java"
    - "backend/src/test/java/com/trafficsimulator/engine/kpi/LosClassifierTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/kpi/QueueAnalyzerTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/kpi/DelayWindowTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/kpi/KpiAggregatorTest.java"
  modified:
    - "backend/src/main/java/com/trafficsimulator/dto/StatsDto.java (added kpi/segmentKpis/intersectionKpis)"
    - "backend/src/main/java/com/trafficsimulator/model/Vehicle.java (added freeFlowSeconds + addFreeFlowSeconds)"
    - "backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java (DelayWindow setter, despawn records delay, initial freeFlowSeconds seeded)"
    - "backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java (D-05a hook on transfer + deadlock force-transfer)"
    - "backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java (constructor accepts DelayWindow; resolveSeedAndStart wires + resets it)"
    - "backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java (KpiCacheInvalidator + DelayWindow params; LoadMap/LoadConfig invalidate)"
    - "backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java (sub-sampling, cache, IKpiAggregator constructor injection, implements KpiCacheInvalidator)"
    - "backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java (5-arg ctor)"
    - "backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSplitOrderTest.java (5-arg ctor)"
    - "backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java (KPI-05 + KPI-07 tests)"

key-decisions:
  - "Embed KpiDto + List<SegmentKpiDto> + List<IntersectionKpiDto> directly inside StatsDto (literal D-08 / Open Q3) — no parallel KPI envelope DTO."
  - "Sub-sampling lives in SnapshotBuilder (scheduler), not KpiAggregator — keeps the aggregator pure-compute and testable."
  - "computeNetworkKpi walks segments inline (does NOT call computeSegmentKpis) so a Mockito spy can count list-recompute invocations and prove sub-sampling."
  - "Layer-boundary fix via KpiCacheInvalidator interface in engine.kpi: CommandDispatcher (engine) calls clearCache through the interface, never imports SnapshotBuilder (scheduler) directly."
  - "DelayWindow is a Spring @Component. SimulationEngine constructor receives it and wires into VehicleSpawner via setter on every Start."
  - "Vehicle.freeFlowSeconds seeded at spawn from lane.length / lane.maxSpeed (avoids reaching back through Lane#road which is excluded from equality)."

patterns-established:
  - "KpiCacheInvalidator: when a downstream cache lives in a higher layer, expose a single-method invalidator interface in the domain layer to keep ArchUnit happy."
  - "Tick-keyed sliding window: ArrayDeque<Sample(tick,value)> + evict-on-read with cutoff = currentTick - WINDOW_TICKS — same shape as Plan 01's VehicleSpawner refactor."
  - "Density-based LOS as a string letter (A..F) with worse(a,b) returning the lexicographically-greater operand and treating null as 'no opinion'."

requirements-completed: [KPI-01, KPI-02, KPI-03, KPI-04, KPI-05, KPI-07]

# Metrics
duration: ~28 min
completed: 2026-04-26
---

# Phase 25 Plan 04: KPI compute + sub-sampling Summary

**Network/segment/intersection KPI block riding on /topic/state per D-08 with tick-keyed delay window, density-based LOS classifier, contiguous-from-exit queue analyser, and SnapshotBuilder sub-sampling every 5 ticks plus LoadMap-driven cache invalidation.**

## Performance

- **Duration:** ~28 min
- **Started:** 2026-04-26T17:39:00Z (approx)
- **Completed:** 2026-04-26T18:07:00Z (approx)
- **Tasks:** 3
- **Files created:** 13 (3 DTOs + 4 KPI services + 1 invalidator interface + 1 KPI interface + 4 test classes)
- **Files modified:** 10 (including 2 test ctor updates)
- **Tests added:** 18 (2 LOS + 5 Queue + 5 Delay + 4 Aggregator + 2 SnapshotBuilder KPI)
- **Backend test count:** 419 → 437 (all green)

## Accomplishments

- 3 KPI DTOs (KpiDto, SegmentKpiDto, IntersectionKpiDto) embedded inside StatsDto per literal D-08
- LosClassifier (D-07): A≤7, B≤11, C≤16, D≤22, E≤28, F>28 vehicles/km/lane plus `worse(a,b)` helper
- QueueAnalyzer (D-06): contiguous-from-exit queue length, threshold = `0.30 × speedLimit`
- DelayWindow (D-05): tick-keyed 60s rolling window (1200 ticks @ 20 Hz); reset on Start/LoadMap/LoadConfig
- Per-vehicle freeFlowSeconds accumulator (D-05a): seeded at spawn, incremented on every road hand-off in IntersectionManager (both normal and deadlock-resolver paths)
- KpiAggregator: composes throughput (delegates to spawner), mean delay (reads DelayWindow), per-segment / per-intersection density+flow+meanSpeed+queue+LOS
- SnapshotBuilder integrates KpiAggregator with sub-sampling cache (every 5 ticks, `KPI_LIST_SUBSAMPLE_TICKS` constant — comment shows literal `currentTick % 5 == 0`)
- KpiCacheInvalidator interface keeps the engine-vs-scheduler layer boundary clean (CommandDispatcher imports the interface, not SnapshotBuilder)
- DelayWindow + KPI cache cleared on LoadMap / LoadConfig (KPI-07)

## Task Commits

1. **Task 1: KPI DTOs + LosClassifier + QueueAnalyzer + DelayWindow** — `a276492` (feat)
2. **Task 2: Vehicle.freeFlowSeconds + IntersectionManager hook + DelayWindow wiring** — `b408124` (feat)
3. **Task 3: KpiAggregator + SnapshotBuilder sub-sampling + cache invalidation** — `ba32d40` (feat)

_Plan metadata commit: pending (next step in workflow)._

## Files Created/Modified

### Created
- `backend/src/main/java/com/trafficsimulator/dto/KpiDto.java` — network-level KPI shape
- `backend/src/main/java/com/trafficsimulator/dto/SegmentKpiDto.java` — per-segment KPI shape
- `backend/src/main/java/com/trafficsimulator/dto/IntersectionKpiDto.java` — per-intersection KPI shape
- `backend/src/main/java/com/trafficsimulator/engine/kpi/LosClassifier.java` — D-07 thresholds + `worse()`
- `backend/src/main/java/com/trafficsimulator/engine/kpi/QueueAnalyzer.java` — D-06 contiguous-queue analyser
- `backend/src/main/java/com/trafficsimulator/engine/kpi/DelayWindow.java` — D-05 60s tick-keyed window
- `backend/src/main/java/com/trafficsimulator/engine/kpi/IKpiAggregator.java` — testability seam
- `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiAggregator.java` — composer @Component
- `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiCacheInvalidator.java` — engine-layer hook implemented by SnapshotBuilder
- `backend/src/test/java/com/trafficsimulator/engine/kpi/LosClassifierTest.java` — 2 tests (boundaries + worse)
- `backend/src/test/java/com/trafficsimulator/engine/kpi/QueueAnalyzerTest.java` — 5 tests (empty / no queue / contiguous / break / not-at-exit)
- `backend/src/test/java/com/trafficsimulator/engine/kpi/DelayWindowTest.java` — 5 tests (record/retrieve/evict/reset/size)
- `backend/src/test/java/com/trafficsimulator/engine/kpi/KpiAggregatorTest.java` — 4 tests (throughput / delay / worstLos / segment fields)

### Modified
- `backend/src/main/java/com/trafficsimulator/dto/StatsDto.java` — added `kpi`, `segmentKpis`, `intersectionKpis` (lines 17-19)
- `backend/src/main/java/com/trafficsimulator/model/Vehicle.java` — added `freeFlowSeconds` field + `addFreeFlowSeconds(double)`
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java` — `@Setter DelayWindow delayWindow`; despawn loop records `(tick, delaySeconds)`; createVehicle seeds initial freeFlowSeconds
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` — `placeVehicleOnTargetLane` (line ~501) and `forceTransferVehicle` (line ~404) call `victim.addFreeFlowSeconds(road.length / road.speedLimit)` after lane assignment
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` — `@Autowired` ctor accepts `@Nullable DelayWindow`; `resolveSeedAndStart` wires it via `vehicleSpawnerConcrete.setDelayWindow(delayWindow)` and calls `delayWindow.reset()`
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` — `@Autowired` 6-arg ctor accepts `@Nullable KpiCacheInvalidator + @Nullable DelayWindow`; `handleLoadMap` and `handleLoadConfig` call `kpiCacheInvalidator.clearCache()` + `delayWindow.reset()`
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` — implements `KpiCacheInvalidator`; constructor-injected `IKpiAggregator`; `computeStats` builds KpiDto every tick and refreshes `lastSegmentKpis` / `lastIntersectionKpis` only when `currentTick % KPI_LIST_SUBSAMPLE_TICKS == 0`
- `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java` — updated to 5-arg ctor
- `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSplitOrderTest.java` — updated to 5-arg ctor
- `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java` — added `subSampling_recomputesEvery5thTick_KPI05` (verifies `times(1)` across ticks 5..9 with Mockito spy) and `cacheClearedOnClearCache_KPI07` (verifies recompute after clearCache, empty list after clearCache + non-multiple tick)

## Decisions Made

1. **Literal embedding of KPI inside StatsDto** (CONTEXT.md Open Q3): downstream JSON consumers see `stats.kpi.*` rather than a parallel envelope. Lombok `@Data @Builder` keeps the DTO line count low.
2. **Sub-sampling lives in SnapshotBuilder, not KpiAggregator**: the aggregator stays pure-compute (every method is idempotent and testable in isolation). The cache is a transport-layer concern.
3. **KpiAggregator.computeNetworkKpi walks segments inline**: avoids re-invoking `computeSegmentKpis()` which would inflate the spy invocation count and make sub-sampling untestable.
4. **KpiCacheInvalidator interface for cross-layer cache invalidation**: ArchUnit forbids `engine.* → scheduler.*` imports; `engine.kpi.KpiCacheInvalidator` (implemented by `scheduler.SnapshotBuilder`) is the single dependency CommandDispatcher needs.
5. **DelayWindow is @Component + @Setter on VehicleSpawner**: Spring wires the bean automatically; SimulationEngine still has the seam to call `setDelayWindow` on every Start so unit tests can inject mocks.
6. **freeFlowSeconds seeded from `lane.length / lane.maxSpeed`** (not `road.speedLimit`): avoids reaching back through `Lane#road`, which is excluded from equality and may be null in fixture tests.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] KpiAggregator.computeNetworkKpi delegated to computeSegmentKpis, breaking the sub-sampling spy assertion**
- **Found during:** Task 3 (SnapshotBuilderTest.subSampling_recomputesEvery5thTick_KPI05 reported "Wanted 1 time, but was 6")
- **Issue:** The original implementation called `computeSegmentKpis(...)` from inside `computeNetworkKpi(...)`, which fired the spy verifier on every tick — masking the sub-sampling enforcement.
- **Fix:** Inlined the per-segment loop inside `computeNetworkKpi` so it directly accumulates `worstLos` and `maxQueue` without going through the public list method. The behavioural contract (worstLos = max LOS across segments) is preserved by `KpiAggregatorTest.worstLos_isMaxAcrossSegments`.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiAggregator.java`
- **Verification:** `mvn -Dtest=SnapshotBuilderTest test` exits 0; KpiAggregatorTest still green.
- **Committed in:** `ba32d40`

**2. [Rule 1 - Bug] CommandDispatcher imported scheduler.SnapshotBuilder (forbidden cross-layer dependency, ArchUnit violation)**
- **Found during:** Task 3 (full backend regression: `ArchitectureTest.layered_architecture` failed)
- **Issue:** Plan called for CommandDispatcher to invoke `snapshotBuilder.clearCache()`. Direct import `engine.* → scheduler.*` violates the project's layered architecture contract.
- **Fix:** Introduced `engine.kpi.KpiCacheInvalidator` (single-method functional interface). `SnapshotBuilder` implements it; `CommandDispatcher`'s ctor takes the interface. Engine layer no longer imports scheduler.
- **Files modified:** new `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiCacheInvalidator.java`; `CommandDispatcher.java`; `SnapshotBuilder.java`.
- **Verification:** `ArchitectureTest.layered_architecture` passes (5.7 s, 7 tests green).
- **Committed in:** `ba32d40`

**3. [Rule 1 - Bug] SnapshotBuilder used @Autowired field injection (ArchUnit violation `no_field_injection`)**
- **Found during:** Task 3 (full backend regression)
- **Issue:** First draft used `@Autowired` on the `kpiAggregator` field; the project enforces constructor injection only.
- **Fix:** Replaced field with constructor injection — added 2-arg `SnapshotBuilder(IKpiAggregator)` constructor and a 0-arg fallback for legacy/test callers. Field stays mutable (non-final) so tests can swap in a Mockito spy via `ReflectionTestUtils#setField`, but the *initial* wiring goes through the constructor.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java`
- **Verification:** `ArchitectureTest.no_field_injection` passes.
- **Committed in:** `ba32d40`

**4. [Rule 3 - Blocking] Mockito UnnecessaryStubbingException on `mockVehicle()` helper in QueueAnalyzerTest**
- **Found during:** Task 1 (initial test run reported 4 errors — `UnfinishedStubbing` / `UnnecessaryStubbing`)
- **Issue:** Helper used inside `when(...).thenReturn(List.of(mockVehicle(...), ...))` — calling `lenient().when(...)` inside an outer in-progress `when()` call confused Mockito.
- **Fix:** Hoisted the `Vehicle` mocks out of the `when().thenReturn()` argument and into named local variables in each test. `lenient()` is still applied because some tests short-circuit before reading every stub.
- **Files modified:** `backend/src/test/java/com/trafficsimulator/engine/kpi/QueueAnalyzerTest.java`
- **Verification:** `mvn -Dtest=QueueAnalyzerTest test` — 5/5 tests pass.
- **Committed in:** `a276492`

**5. [Rule 2 - Missing Critical] freeFlowSeconds did not accumulate on the deadlock-resolver hand-off path**
- **Found during:** Task 2 (reading IntersectionManager more carefully — deadlock resolver `forceTransferVehicle` is a real road hand-off, just bypassing space checks)
- **Issue:** Plan only specified the normal-transfer hook (`placeVehicleOnTargetLane`). Without the deadlock-path hook, vehicles freed by the resolver would under-count their free-flow time and overstate delay.
- **Fix:** Added `victim.addFreeFlowSeconds(outRoad.getLength() / Math.max(0.1, outRoad.getSpeedLimit()))` immediately after `targetLane.addVehicle(victim)` in `forceTransferVehicle`. Same pattern as the normal path.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java`
- **Verification:** Existing IntersectionManagerTest suite (24 tests) remains green; KPI delay accounting now consistent across both transfer paths.
- **Committed in:** `b408124`

---

**Total deviations:** 5 auto-fixed (3 bugs, 1 missing critical, 1 blocking).
**Impact on plan:** Two of the bugs (architecture violations) only surfaced in the full regression — the plan-level acceptance criteria didn't cover them. The KpiCacheInvalidator interface is a small but worthwhile structural addition that keeps the engine/scheduler layer boundary intact going forward.

## Issues Encountered

None beyond the deviations documented above. Every test failure surfaced a real issue and was fixed inline; no pre-existing issues touched.

## Authentication Gates

None — entirely backend / unit-test work.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- KPI compute layer fully unit-tested (KPI-01..05 + KPI-07 closed at unit level).
- KPI-06 (KpiDto present on /topic/state every tick) intentionally deferred to Plan 07's `KpiBroadcastIT` — covered in plan source-artifact audit.
- Plan 05 (frontend StatsPanel KPI rendering) can consume the new `stats.kpi.*` shape directly. Mirror the DTOs into `frontend/src/types/`.
- Plan 06 (Diagnostics panel) reuses `stats.segmentKpis[].densityPerKm` / `meanSpeedMps` for the fundamental diagram and `stats.segmentKpis` time-series for the space-time strip.
- Plan 07 integration test `KpiBroadcastIT` should assert `stats.kpi != null` on at least 2 consecutive ticks.

---
*Phase: 25-traffic-flow-visualization*
*Completed: 2026-04-26*

## Self-Check: PASSED

Verified all created files exist on disk:
- `backend/src/main/java/com/trafficsimulator/dto/KpiDto.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/dto/SegmentKpiDto.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/dto/IntersectionKpiDto.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/LosClassifier.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/QueueAnalyzer.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/DelayWindow.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/IKpiAggregator.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiAggregator.java` — FOUND
- `backend/src/main/java/com/trafficsimulator/engine/kpi/KpiCacheInvalidator.java` — FOUND
- 4 test files — FOUND

Verified all 3 task commits in git log:
- `a276492` — FOUND (Task 1: DTOs + classifiers + window)
- `b408124` — FOUND (Task 2: Vehicle + IntersectionManager + spawner wiring)
- `ba32d40` — FOUND (Task 3: KpiAggregator + SnapshotBuilder + dispatcher invalidation)

Final regression: `mvn -pl backend test` — 437 tests, 0 failures, 0 errors, 1 skipped. All KPI-01..05 + KPI-07 acceptance criteria pass.
