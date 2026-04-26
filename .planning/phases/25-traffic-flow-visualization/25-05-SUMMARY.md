---
phase: 25-traffic-flow-visualization
plan: 05
subsystem: engine
tags: [replay, ndjson, run-for-ticks, fast-mode, validation, async]

requires:
  - phase: 25-traffic-flow-visualization
    provides: "Plan 02 — Spring-injected RandomGenerator + resolveSeedAndStart (D-01..D-04)"
  - phase: 25-traffic-flow-visualization
    provides: "Plan 03 — RoadNetwork.seed propagation, MapConfig.perturbation"
  - phase: 25-traffic-flow-visualization
    provides: "Plan 04 — KPI DTOs and SnapshotBuilder wiring"
provides:
  - "RUN_FOR_TICKS / RUN_FOR_TICKS_FAST commands with T-25-02 DoS bound (1..1_000_000)"
  - "SimulationEngine.fastMode + autoStopTick + scheduleAutoStop / clearAutoStop / isAutoStopReached"
  - "TickEmitter.runOneTick() factored entrypoint (used by both @Scheduled and worker thread)"
  - "TickEmitter early-return when fastMode active (Pitfall #5 / T-25-RACE mitigation)"
  - "FastSimulationRunner @Async worker driving the same tick pipeline (DET-07 byte-identity)"
  - "ReplayLogger NDJSON writer (D-14 schema) with path-traversal + IOException safety"
  - "ReplayLoggerProperties (simulator.replay.enabled, simulator.replay.directory)"
  - "VehicleSnapshotDto (narrow id/roadId/laneIndex/position/speed)"
  - "engine.run.IFastSimulationRunner — engine-layer interface keeping engine→scheduler dependency clean"
affects: [25-06, 25-07, 26-crud, 27-headless-batch]

tech-stack:
  added:
    - "Spring @Async (via @EnableAsync on TrafficSimulatorApplication)"
    - "Spring @ConfigurationProperties (ReplayLoggerProperties)"
  patterns:
    - "Engine-layer interface + scheduler-layer impl to satisfy ArchUnit layered_architecture rule (engine → scheduler is forbidden)"
    - "Auto-disable on disk failure: AtomicBoolean alreadyWarned + disabled flag + try/catch around every writeLine"
    - "@Nullable optional Spring beans for clean degradation when conditional beans are absent (BDD test contexts)"

key-files:
  created:
    - "backend/src/main/java/com/trafficsimulator/dto/VehicleSnapshotDto.java"
    - "backend/src/main/java/com/trafficsimulator/replay/ReplayLogger.java"
    - "backend/src/main/java/com/trafficsimulator/replay/ReplayLoggerProperties.java"
    - "backend/src/main/java/com/trafficsimulator/engine/run/IFastSimulationRunner.java"
    - "backend/src/main/java/com/trafficsimulator/scheduler/FastSimulationRunner.java"
    - "backend/src/test/java/com/trafficsimulator/replay/ReplayLoggerTest.java"
    - "backend/src/test/java/com/trafficsimulator/controller/CommandHandlerValidationTest.java"
  modified:
    - "backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java (+ RunForTicks, RunForTicksFast records)"
    - "backend/src/main/java/com/trafficsimulator/dto/CommandDto.java (+ Long ticks)"
    - "backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java (+ validateTicks, RUN_FOR_TICKS routing)"
    - "backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java (+ fastMode, autoStopTick, helpers)"
    - "backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java (+ handleRunForTicks/Fast, replay start/close)"
    - "backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java (+ isFastMode early-return, runOneTick factoring, replay write, auto-stop)"
    - "backend/src/main/java/com/trafficsimulator/TrafficSimulatorApplication.java (+ @EnableAsync)"
    - "backend/src/main/resources/application.properties (+ simulator.replay.enabled=false, simulator.replay.directory)"
    - ".gitignore (+ backend/target/replays/)"

key-decisions:
  - "engine.run.IFastSimulationRunner interface introduced to satisfy ArchUnit layered architecture rule (engine cannot import scheduler); concrete bean lives in scheduler"
  - "FastSimulationRunner gated by simulation.tick-emitter.enabled to mirror TickEmitter conditional — avoids unsatisfiable bean dependency in BDD test contexts that disable the ticker"
  - "RUN_FOR_TICKS auto-starts the engine if STOPPED (calls resolveSeedAndStart(null)) so the operator does not need a separate START before scheduling a finite run"
  - "Replay writer uses VehicleDto from the broadcast snapshot, mapped to the narrow VehicleSnapshotDto inside TickEmitter — avoids double-iteration over the road network"

patterns-established:
  - "Spring conditional beans for hot-path scheduling: @ConditionalOnProperty mirroring the upstream conditional, plus @Nullable injection for graceful degradation"
  - "ArchUnit-clean cross-layer dispatch: engine-layer interface + scheduler-layer implementation"
  - "IOException-safe append-only writers: AtomicBoolean compareAndSet for once-only WARN, disabled flag for fast-path no-op"

requirements-completed: [REPLAY-02, REPLAY-04]

duration: 22min
completed: 2026-04-26
---

# Phase 25 Plan 05: Scenario duration + replay logger Summary

**RUN_FOR_TICKS / RUN_FOR_TICKS_FAST scenario controls with NDJSON replay logger (D-13 + D-14 + D-15 closed at unit level).**

## Performance

- **Duration:** 22 min
- **Started:** 2026-04-26T16:26:27Z
- **Completed:** 2026-04-26T16:49:23Z
- **Tasks:** 3
- **Files created:** 7 (5 prod + 2 test)
- **Files modified:** 9

## Accomplishments

- ReplayLogger writes NDJSON header + per-tick lines per CONTEXT.md D-14, gated by `simulator.replay.enabled` (default false) and auto-enabled by RUN_FOR_TICKS dispatch.
- RUN_FOR_TICKS / RUN_FOR_TICKS_FAST commands accept a validated `ticks` field (1..1_000_000); CommandHandler rejects out-of-range values with `IllegalArgumentException` before enqueue (T-25-02 mitigation closed at unit level).
- SimulationEngine gains `fastMode` + `autoStopTick` + `scheduleAutoStop` / `clearAutoStop` / `isAutoStopReached` so both wall-clock and worker-thread modes share auto-stop semantics.
- TickEmitter `emitTick()` early-returns when fast mode is active (Pitfall #5 / T-25-RACE), and the per-tick body is factored into a public `runOneTick()` reused by `FastSimulationRunner.runFor(...)`.
- FastSimulationRunner runs RUN_FOR_TICKS_FAST in an `@Async` worker bound to the same `simulation.tick-emitter.enabled` property as the regular ticker; on completion it clears `fastMode` and `autoStopTick` so the wall-clock ticker resumes cleanly.
- All 452 backend tests still pass after the changes (15 new unit tests added across plans 04+05; no regressions).

## Task Commits

1. **Task 1: ReplayLogger + ReplayLoggerProperties + VehicleSnapshotDto** — `6f18c4b` (feat) — 6 unit tests covering REPLAY-02 schema, REPLAY-04 IOException safety, and T-25-01 path-traversal contract.
2. **Task 2: RUN_FOR_TICKS / RUN_FOR_TICKS_FAST commands + T-25-02 validation** — `a0f98ec` (feat) — 9 unit tests over the 1..1_000_000 inclusive bound (null/0/-1/1/1M/1M+1) for both fast and wall-clock variants.
3. **Task 3: SimulationEngine flags + TickEmitter refactor + FastSimulationRunner + CommandDispatcher wiring** — `5e9ee2e` (feat) — full backend suite remains green (452 tests, 0 failures).

## Files Created/Modified

### Created
- `backend/src/main/java/com/trafficsimulator/dto/VehicleSnapshotDto.java` — narrow vehicle snapshot for replay NDJSON (id, roadId, laneIndex, position, speed only).
- `backend/src/main/java/com/trafficsimulator/replay/ReplayLogger.java` — NDJSON append-only writer; synchronized; AtomicBoolean alreadyWarned + disabled flag; AutoCloseable.
- `backend/src/main/java/com/trafficsimulator/replay/ReplayLoggerProperties.java` — Spring `@ConfigurationProperties("simulator.replay")` with `enabled` (default false) and `directory` (default `target/replays`).
- `backend/src/main/java/com/trafficsimulator/engine/run/IFastSimulationRunner.java` — engine-layer interface (single method `void runFor(long ticks)`).
- `backend/src/main/java/com/trafficsimulator/scheduler/FastSimulationRunner.java` — `@Async @Component` impl reusing `TickEmitter.runOneTick()`; gated by `simulation.tick-emitter.enabled`.
- `backend/src/test/java/com/trafficsimulator/replay/ReplayLoggerTest.java` — 6 tests (header, tick, path format, T-25-01 contract, T-25-IO no-crash, not-started no-op).
- `backend/src/test/java/com/trafficsimulator/controller/CommandHandlerValidationTest.java` — 9 tests covering T-25-02 bound + dispatch on accept.

### Modified
- `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java` — sealed permits extended with `RunForTicks(long)` + `RunForTicksFast(long)`.
- `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java` — `Long ticks` field added.
- `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java` — `RUN_FOR_TICKS` / `RUN_FOR_TICKS_FAST` cases routed through `validateTicks()` helper enforcing `1..MAX_RUN_TICKS`.
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` — added `fastMode` (volatile, Lombok `@Getter @Setter`), `autoStopTick` (volatile, `@Getter`), `scheduleAutoStop` / `clearAutoStop` / `isAutoStopReached` helpers.
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` — registered `RunForTicks` + `RunForTicksFast` handlers; `handleStop` now clears auto-stop and closes replay; new private helpers `startReplayForRun` (force-start) + `closeReplayQuietly`.
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` — `emitTick` early-returns on `engine.isFastMode()`; tick body extracted into public `runOneTick()`; replay NDJSON line written from the broadcast `state.vehicles`; auto-stop check at end of tick triggers STOP + replay close.
- `backend/src/main/java/com/trafficsimulator/TrafficSimulatorApplication.java` — `@EnableAsync` added so `@Async` on FastSimulationRunner activates.
- `backend/src/main/resources/application.properties` — `simulator.replay.enabled=false` + `simulator.replay.directory=target/replays`.
- `.gitignore` — `backend/target/replays/` excluded (because `target/` itself is partly tracked in this project).

## Decisions Made

- **engine.run.IFastSimulationRunner interface (Rule 3 — Blocking architecture).** First Task 3 attempt put `FastSimulationRunner` in `scheduler/` and had `CommandDispatcher` import it. This violated the ArchUnit `layered_architecture` rule (engine layer cannot import scheduler) and made `mvn test` fail with both 24 BDD context-load errors and 1 architecture test failure. Created a thin engine-layer interface (`IFastSimulationRunner`) that the dispatcher depends on; the impl in `scheduler/` keeps its `TickEmitter` collaboration. Resolved both failures with no scope expansion.
- **Conditional gate on FastSimulationRunner** (Rule 3 — Blocking). BDD tests disable the regular ticker via `simulation.tick-emitter.enabled=false`; the first attempt to wire FastSimulationRunner with a non-nullable `TickEmitter` constructor parameter caused 24 ApplicationContext failures. Mirrored the same `@ConditionalOnProperty` on FastSimulationRunner so the bean is absent when TickEmitter is absent, and made `CommandDispatcher.fastSimulationRunner` `@Nullable` (no-op log warning when missing).
- **RUN_FOR_TICKS auto-starts the engine if STOPPED.** Calling `engine.resolveSeedAndStart(null)` inside the dispatcher means an operator can fire a RUN_FOR_TICKS without a prior START. Avoids the surprising "command queued but ignored because status != RUNNING" footgun, while staying inside the existing seed precedence chain (auto-source uses `System.nanoTime()`).
- **Replay writer reads VehicleDto from broadcast state, not the live RoadNetwork.** Saves a second iteration over all roads/lanes/vehicles per tick; the broadcast list is already built by `SnapshotBuilder` under writeLock, so it is consistent and immutable for the duration of the call.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Engine-layer to scheduler-layer dependency forbidden by ArchUnit**
- **Found during:** Task 3 verification (`mvn test` after wiring CommandDispatcher to import FastSimulationRunner)
- **Issue:** ArchUnit `layered_architecture` test forbids `engine → scheduler` imports. Direct import of `com.trafficsimulator.scheduler.FastSimulationRunner` in `CommandDispatcher` produced 1 architecture failure plus 24 BDD context-load failures (downstream cascade).
- **Fix:** Introduced `com.trafficsimulator.engine.run.IFastSimulationRunner` interface in the engine layer; `FastSimulationRunner` (scheduler) implements it; `CommandDispatcher` depends on the interface only. Mirrors the existing `KpiCacheInvalidator` pattern from Plan 04.
- **Files modified:** Added `backend/src/main/java/com/trafficsimulator/engine/run/IFastSimulationRunner.java`; updated CommandDispatcher import + field type.
- **Verification:** `mvn test` now passes 452 tests (including the architecture test).
- **Committed in:** `5e9ee2e` (Task 3 commit).

**2. [Rule 3 - Blocking] FastSimulationRunner unsatisfiable when TickEmitter is conditionally absent**
- **Found during:** Task 3 verification (BDD tests use `simulation.tick-emitter.enabled=false` to disable the ticker)
- **Issue:** First wiring of FastSimulationRunner had a non-nullable `TickEmitter` parameter; in BDD test contexts where TickEmitter is conditionally excluded, FastSimulationRunner's bean creation failed, cascading 24 ApplicationContext load errors across BDD scenarios.
- **Fix:** Added `@ConditionalOnProperty(name = "simulation.tick-emitter.enabled", havingValue = "true", matchIfMissing = true)` to FastSimulationRunner (mirrors TickEmitter's gate); injected TickEmitter as `@Nullable`; CommandDispatcher's `IFastSimulationRunner` is also `@Nullable` so the dispatcher logs a warning and no-ops on RUN_FOR_TICKS_FAST when the bean is absent.
- **Files modified:** `FastSimulationRunner.java`, `CommandDispatcher.java`.
- **Verification:** All 452 backend tests pass including the BDD suite.
- **Committed in:** `5e9ee2e` (Task 3 commit).

---

**Total deviations:** 2 auto-fixed (both Rule 3 blocking).
**Impact on plan:** Both fixes preserve plan intent; the IFastSimulationRunner interface is the standard cross-layer pattern already used by `KpiCacheInvalidator` in this project. No scope expansion.

## Authentication Gates

None.

## Issues Encountered

- Spotless `mvn spotless:apply` reformatted many pre-existing files in the repo on every run. Per scope boundary, only Plan-05-owned files were staged in each commit; pre-existing reformatting noise is left as untracked working-tree state for a future house-keeping commit (NOT this plan's responsibility).
- The `git` command in this environment fails on stale `.claude/worktrees/agent-*` directories. Worked around by adding `--ignore-submodules=all` to `git status` and using `git -C <path>` for `git log`. Per `<environment_workaround>` instructions, the stale directories are not deleted.

## User Setup Required

None — internal-only feature behind a default-disabled property.

## Next Phase Readiness

- Plan 05 implementation is ready for Plan 07 integration tests (DeterminismIT / RunForTicksIT / FastModeParityIT / ReplayLoggerIT) which will close DET-06, DET-07, REPLAY-01, REPLAY-03 at the integration level.
- D-13, D-14, D-15 contracts are in place. Operators can now request bounded scenario duration and obtain auditable NDJSON replay logs.
- Frontend integration (Plan 06) can wire RUN_FOR_TICKS / RUN_FOR_TICKS_FAST controls; payload shape is `{type:"RUN_FOR_TICKS", ticks:<long>}`.

## Self-Check: PASSED

All claimed artifacts verified on disk and all task commit hashes verified in git log:

- 7 created files exist on disk (5 prod + 2 test)
- 3 task commits exist (`6f18c4b`, `a0f98ec`, `5e9ee2e`)
- Plan-level verification block: `mvn -Dtest=ReplayLoggerTest test` (6 pass), `mvn -Dtest=CommandHandlerValidationTest test` (9 pass), `mvn test` (452 pass / 1 skipped / 0 failures), all required `grep`/`ls` checks PASS.

---
*Phase: 25-traffic-flow-visualization*
*Completed: 2026-04-26*
