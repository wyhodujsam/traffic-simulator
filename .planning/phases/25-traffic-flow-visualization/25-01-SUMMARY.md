---
phase: 25-traffic-flow-visualization
plan: 01
subsystem: testing
tags: [determinism, rng, ring-road, requirements, refactor, tdd, foundation]

# Dependency graph
requires:
  - phase: 4
    provides: VehicleSpawner / IVehicleSpawner / SnapshotBuilder / TickEmitter wiring
  - phase: 8
    provides: IntersectionManager + canEnterIntersection PRIORITY/NONE branch
  - phase: 9
    provides: MapConfig + IntersectionType enum used by spike fixture
provides:
  - 26 v3.0 REQ-IDs (DET-01..07, KPI-01..07, RING-01..04, REPLAY-01..04, UI-01..04) back-filled into REQUIREMENTS.md (bulleted list + Traceability table + Coverage)
  - Tick-keyed rolling throughput window in VehicleSpawner (TICKS_PER_SEC=20, THROUGHPUT_WINDOW_TICKS=1200) — DET-01 precondition
  - IVehicleSpawner.getThroughput(long) and IVehicleSpawner.despawnVehicles(RoadNetwork, long) signatures
  - SnapshotBuilder.computeStats threads currentTick to throughput query
  - TickEmitter passes tick to despawnVehicles
  - 4-case VehicleSpawnerTickWindowTest (eviction, reset, fresh-spawner, inclusive-cutoff)
  - 2-case RingRoadPriorityYieldSpikeTest validating PRIORITY and NONE in 8-segment ring
  - 25-WAVE0-SPIKE-RESULT.md with Plan 03 directive `RING-ROAD-INTERSECTION-TYPE: PRIORITY`
affects: [25-02, 25-03, 25-04, 25-05, 25-06, 25-07]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Rolling-window aggregates keyed on simulated tick number (not wall clock) for cross-run reproducibility"
    - "Wave-0 spike pattern: minimal in-memory fixture exercising real engine subcomponents to retire pre-implementation risks"

key-files:
  created:
    - backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTickWindowTest.java
    - backend/src/test/java/com/trafficsimulator/engine/RingRoadPriorityYieldSpikeTest.java
    - .planning/phases/25-traffic-flow-visualization/25-WAVE0-SPIKE-RESULT.md
  modified:
    - .planning/REQUIREMENTS.md
    - backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java
    - backend/src/main/java/com/trafficsimulator/engine/IVehicleSpawner.java
    - backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java
    - backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java
    - backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTest.java
    - backend/src/test/java/com/trafficsimulator/engine/VehicleDespawnTest.java
    - backend/src/test/java/com/trafficsimulator/engine/TickPipelineIntegrationTest.java
    - backend/src/test/java/com/trafficsimulator/integration/FullPipelineTest.java
    - backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java
    - backend/src/test/java/com/trafficsimulator/bdd/SimulationSteps.java

key-decisions:
  - "Plan 03 will use IntersectionType.PRIORITY (not NONE, not a new straightThrough flag) for ring-road.json — both PRIORITY and NONE pass the spike, PRIORITY is the semantic match"
  - "TICKS_PER_SEC defined as int=20 inside VehicleSpawner (matches TickEmitter @Scheduled(fixedRate=50)); not yet promoted to a shared constants class — Plan 02 may decide where it ultimately lives"
  - "Rolling window cutoff uses strict less-than (entries equal to cutoff tick are retained) to match the prior wall-clock semantics: 'entries within the window' rather than 'entries strictly newer than cutoff'"
  - "Spike test exercises the real IntersectionManager.processTransfers (rather than only a position+transfer proxy as in the plan draft) so the PRIORITY/NONE branch in canEnterIntersection is genuinely covered"

patterns-established:
  - "Tick-keyed rolling windows: replace System.currentTimeMillis with a long currentTick parameter on the consumer; convert window-in-seconds to window-in-ticks via TICKS_PER_SEC"
  - "Wave-0 spike artefact: backend/src/test/java/.../<X>SpikeTest.java + .planning/phases/<phase>/<phase>-WAVE0-<TOPIC>-RESULT.md publishing a one-line directive other plans grep for"

requirements-completed: []  # plan frontmatter requirements: [] (single-ownership rule — DET-04 owned by Plan 03, RING-02 owned by Plan 07)

# Metrics
duration: ~11min
completed: 2026-04-26
---

# Phase 25 Plan 01: Foundation — REQ back-fill, tick-keyed throughput, ring-road spike Summary

**26 Phase-25 REQ-IDs registered, VehicleSpawner rolling throughput window made deterministic by re-keying on tick numbers, and the ring-road PRIORITY-yield assumption (RESEARCH.md Pitfall #2) discharged via a real-pipeline spike directing Plan 03 to use IntersectionType.PRIORITY.**

## Performance

- **Duration:** ~11 min (638 s)
- **Started:** 2026-04-26T08:39:06Z
- **Completed:** 2026-04-26T08:49:44Z
- **Tasks:** 3 of 3
- **Files modified:** 11 (3 created, 8 modified)

## Accomplishments

- 26 v3.0 REQ-IDs (DET / KPI / RING / REPLAY / UI) listed under a new `## v3.0 Requirements — LLM-assisted redesign foundation` heading in REQUIREMENTS.md, with 26 matching Traceability rows pointing at "Phase 25 — Determinism + KPI Foundation" and the Coverage block extended (`v3.0 requirements: 26 total — mapped: 26 ✓`). Downstream plans can now declare `requirements: [REQ-ID]` ownership without back-fill at execution time.
- VehicleSpawner rolling throughput window converted from `System.currentTimeMillis()` to `currentTick`. New `TICKS_PER_SEC = 20` and `THROUGHPUT_WINDOW_TICKS = 1200` constants inside VehicleSpawner.java; `despawnTimestamps` renamed to `despawnTicks`. Two runs of the same seed will now produce byte-identical throughput observations — DET-01 precondition discharged.
- Wave-0 ring-road spike (`RingRoadPriorityYieldSpikeTest`) proves both `IntersectionType.PRIORITY` and `IntersectionType.NONE` pass an 8-segment ring topology with 8 vehicles for 100 ticks. `25-WAVE0-SPIKE-RESULT.md` publishes the directive `RING-ROAD-INTERSECTION-TYPE: PRIORITY` for Plan 03 to consume.
- Full backend test suite runs **396 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS** — DET-04 prerequisite (308-test baseline preserved + 6 new tests + counted Cucumber re-runs).

## Task Commits

Each task was committed atomically on `feat/phase-25-determinism-kpi`:

1. **Task 1: Back-fill 26 REQ-IDs into REQUIREMENTS.md** — `eb86d8d` (`docs(25-01): back-fill 26 Phase-25 REQ-IDs into REQUIREMENTS.md`)
2. **Task 2: Refactor VehicleSpawner rolling throughput window from wall-clock to tick numbers** — `6c8abdd` (`feat(25-01): refactor VehicleSpawner throughput window from wall-clock to tick-keyed (DET-01 precondition)`)
3. **Task 3: Wave-0 ring-road PRIORITY-yield spike + result directive** — `8f68a05` (`test(25-01): add Wave-0 ring-road PRIORITY-yield spike + result directive (Q1 RESOLVED)`)

_Plan metadata commit (this SUMMARY.md): pending — orchestrator will own STATE/ROADMAP and the metadata commit per execution prompt._

## Files Created/Modified

### Created
- `backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTickWindowTest.java` — 4 unit tests proving the tick-keyed cutoff is correct (eviction at cutoff, empty after reset, no negatives on fresh spawner, strict-less-than cutoff is inclusive of equality).
- `backend/src/test/java/com/trafficsimulator/engine/RingRoadPriorityYieldSpikeTest.java` — 2 cases (PRIORITY + NONE) building an 8-segment closed ring and exercising real `IntersectionManager.processTransfers` with a minimal in-test physics step. Asserts vehicle count preserved and total advanced distance > 0 after 100 ticks.
- `.planning/phases/25-traffic-flow-visualization/25-WAVE0-SPIKE-RESULT.md` — publishes `RING-ROAD-INTERSECTION-TYPE: PRIORITY` directive plus the why/method/result/boundary table for Plan 03.

### Modified
- `.planning/REQUIREMENTS.md` (lines 132–176 added v3.0 section; lines 267–292 added 26 Traceability rows; coverage block + footer updated).
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java` — `THROUGHPUT_WINDOW_MS` removed; `TICKS_PER_SEC = 20` and `THROUGHPUT_WINDOW_TICKS = 60L * TICKS_PER_SEC` added; `despawnTimestamps` → `despawnTicks`; `despawnVehicles(RoadNetwork)` → `despawnVehicles(RoadNetwork, long currentTick)`; `getThroughput()` → `getThroughput(long currentTick)`. All `System.currentTimeMillis()` calls removed from this file.
- `backend/src/main/java/com/trafficsimulator/engine/IVehicleSpawner.java` — interface signatures updated (`despawnVehicles(RoadNetwork, long)`, `getThroughput(long)`); javadoc references DET-01 precondition.
- `backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java` — `computeStats(VehicleObstacleCollection, IVehicleSpawner)` → `computeStats(VehicleObstacleCollection, IVehicleSpawner, long currentTick)`; `buildSnapshot(...)` (line 53 overload) threads `tick` to `computeStats`.
- `backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java` — `vehicleSpawner.despawnVehicles(network)` → `vehicleSpawner.despawnVehicles(network, tick)` (line 131).
- `backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTest.java` — `getThroughput()` → `getThroughput(1L)` in `tick_noSpawnPoints_doesNothing`.
- `backend/src/test/java/com/trafficsimulator/engine/VehicleDespawnTest.java` — three `despawnVehicles(network)` call sites → `despawnVehicles(network, 0L)`.
- `backend/src/test/java/com/trafficsimulator/engine/TickPipelineIntegrationTest.java` — two `despawnVehicles(network)` call sites pass loop `tick`.
- `backend/src/test/java/com/trafficsimulator/integration/FullPipelineTest.java` — `despawnVehicles(network)` → `despawnVehicles(network, 20L)`.
- `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java` — Mockito stub `when(vehicleSpawner.getThroughput()).thenReturn(0)` → `when(vehicleSpawner.getThroughput(anyLong())).thenReturn(0)` (added `org.mockito.ArgumentMatchers.anyLong` import).
- `backend/src/test/java/com/trafficsimulator/bdd/SimulationSteps.java` — `vehicleSpawner.despawnVehicles(network)` → `vehicleSpawner.despawnVehicles(network, currentTick)` in the BDD tick-loop step.

## Decisions Made

1. **Plan 03 will use `IntersectionType.PRIORITY` for ring-road.json.** Both `PRIORITY` and `NONE` pass the spike (8-segment ring, 8 vehicles, 100 ticks, real `IntersectionManager.processTransfers`). `PRIORITY` is the semantic match for an unsignalled junction; `NONE` was the documented fallback per RESEARCH.md Pitfall #2 but is not needed.
2. **Pitfall #2 itself was a false alarm in the rescoped ring topology.** With each ring intersection having exactly one inbound road, the inner stream in `hasVehicleFromRight` filters the self-road and finds no other candidate — so the right-of-way short-circuit never blocks. This holds only as long as the ring keeps the single-inbound-per-node invariant; future scenarios with on/off ramps will need to re-run the spike.
3. **`TICKS_PER_SEC = 20` lives inside VehicleSpawner.** Plan 02 may promote it to a shared constants class when introducing the seed-resolution + sub-RNG infrastructure; deferring that decision keeps this plan minimal-change.
4. **Rolling-window cutoff uses strict `<` (entries equal to cutoff tick are retained).** Mirrors the prior wall-clock semantics ("entries within the last 60 s" rather than "entries strictly newer than now − 60 s"). The tick-window inclusive-cutoff test case documents this in code.
5. **TDD plan-gate: RED+GREEN committed together for the refactor task.** A standalone RED commit would have broken compilation (the test references the new `getThroughput(long)` and `despawnTicks` field not yet present), so per TDD pragmatism for Java refactors the test + impl ship in a single `feat` commit. Documented in TDD Gate Compliance section below.

## Deviations from Plan

The execution found two deviations against the literal text of the plan, both inside Rule 1/2 territory and both kept in scope:

### Auto-fixed Issues

**1. [Rule 1 — Bug] Plan-draft spike test bypassed IntersectionManager**
- **Found during:** Task 3 (Wave-0 spike).
- **Issue:** The spike test sketched in 25-01-PLAN.md `<action>` ran a hand-rolled physics + transfer loop (compute next pos, switch lane manually). That loop never invoked `IntersectionManager.canEnterIntersection` / `hasVehicleFromRight`, so it could not have detected the very pitfall it was meant to test. A passing test under that design would have proven nothing.
- **Fix:** Replaced the in-test transfer logic with `IntersectionManager.processTransfers(network, t)`. The in-test physics step now only advances vehicle positions to the lane end; the actual transfer (and its right-of-way check) is performed by the real engine subcomponent. This is what the plan's Task 3 was actually trying to validate (per Pitfall #2 wording).
- **Files modified:** `backend/src/test/java/com/trafficsimulator/engine/RingRoadPriorityYieldSpikeTest.java`
- **Verification:** Test passes; the assertion that vehicle count remains 8 across 100 ticks is now meaningful because vehicles must successfully cross intersections to stay in the network.
- **Committed in:** `8f68a05`

**2. [Rule 3 — Blocking] Stale `.claude/worktrees/agent-*/.git` gitlinks crash `git status`**
- **Found during:** Pre-execution environment check.
- **Issue:** The repository contains many tracked gitlinks under `.claude/worktrees/` that point to `/home/sebastian/traffic-simulator/.git/worktrees/agent-…` — a path that does not exist in this environment. Plain `git status` (and any operation that triggers submodule discovery) fails with `fatal: not a git repository: …agent-a0b775e1`.
- **Fix:** Did not modify the gitlinks (out of plan scope; those files are tracked from prior agent sessions). Worked around by passing `--ignore-submodules=all` to every `git status`/`git diff` invocation, and by listing files explicitly when committing. The `gsd-tools.cjs commit --files <…>` flow is unaffected because it stages explicit paths.
- **Files modified:** none (work-around only).
- **Verification:** All three task commits succeeded. No accidental staging of stale worktree files.
- **Committed in:** n/a (no code change).
- **Logged for follow-up:** This is a pre-existing project hygiene issue — recommend a future cleanup plan to either prune the dead worktrees with `git worktree prune` from the original parent repo, untrack the `.claude/worktrees/` directory, or add it to `.gitignore`. Not a Phase-25 blocker.

---

**Total deviations:** 2 auto-fixed (1 bug fix in the plan's draft spike test, 1 environment workaround for a pre-existing repo hygiene issue).
**Impact on plan:** Both auto-fixes were required for correctness/proceedability. No scope creep — both touch only files explicitly listed in the plan or are workarounds, not changes.

## TDD Gate Compliance

Task 2 was marked `tdd="true"` in the plan. The full RED → GREEN gate sequence was:

1. **RED (intent):** `VehicleSpawnerTickWindowTest.java` written first; running `mvn -Dtest=VehicleSpawnerTickWindowTest test` produced a **compilation error** (`method getThroughput in class com.trafficsimulator.engine.VehicleSpawner cannot be applied to given types; required: no arguments; found: int`). This proves the test was driving the new contract.
2. **GREEN (implementation):** VehicleSpawner refactor + IVehicleSpawner signature update + SnapshotBuilder/TickEmitter call-site fixes + 6 existing test call-site updates applied. `mvn -Dtest=VehicleSpawnerTickWindowTest test` then passed (4/4); full suite `mvn test` passed (396 tests, 0 failures).

Both RED and GREEN ship in a **single `feat(25-01): ...` commit** (`6c8abdd`) rather than two commits, because Java compilation is all-or-nothing — a standalone RED commit would have left the repo broken (existing tests referencing `getThroughput()` would no longer compile after the test file was added but before the impl was changed). The git log therefore shows only the GREEN commit; the test is part of it.

This is the correct shape for a refactor-style TDD task — the test documents the contract, the impl satisfies it, both atomically. The intent of the plan-level TDD gate (proving the test fails before impl) was honoured by capturing the RED compilation error in this section.

## Issues Encountered

- **Backend test suite reports 396 tests, not 308.** The plan referenced "308-test backend suite" as the regression baseline. The actual count is higher because Cucumber/JUnit-Platform reports each feature-file run as a separate `Tests run: 24` entry, and the new tests (4 + 2 = 6) push the total to 396. No tests are missing or skipped beyond the pre-existing 1 skip. The regression contract — "no test that previously passed now fails" — is met.

## User Setup Required

None — refactor + spike + doc edits only. No external services, no env vars, no secrets touched.

## Next Phase Readiness

- **Plan 02 (RNG bootstrap + sub-RNG split + MASTER_ALGORITHM):** Unblocked. The `TICKS_PER_SEC` constant lives in VehicleSpawner; if Plan 02 wants a shared constants class, this plan's location is easy to refactor.
- **Plan 03 (ring-road scenario):** Unblocked. `RING-ROAD-INTERSECTION-TYPE: PRIORITY` directive in `25-WAVE0-SPIKE-RESULT.md` is ready to consume; ring-road.json should set every intersection's `type: "PRIORITY"`.
- **Plans 04..07:** Unblocked for `requirements:` ownership claims — all 26 REQ-IDs are now in REQUIREMENTS.md.
- **DET-01 (byte-identical NDJSON over 1000 ticks):** Throughput rolling window is no longer a determinism source of drift. Remaining sources to address in later plans: `ThreadLocalRandom` in VehicleSpawner.vary() (line 141, Plan 02), `ThreadLocalRandom` in IntersectionManager (lines 8 import + force-transfer line ~366, Plan 02), and any `System.currentTimeMillis` elsewhere in the per-tick path (audit in Plan 02 or 04).

---
*Phase: 25-traffic-flow-visualization*
*Completed: 2026-04-26*

## Self-Check: PASSED

- File `backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTickWindowTest.java` — FOUND
- File `backend/src/test/java/com/trafficsimulator/engine/RingRoadPriorityYieldSpikeTest.java` — FOUND
- File `.planning/phases/25-traffic-flow-visualization/25-WAVE0-SPIKE-RESULT.md` — FOUND
- Commit `eb86d8d` (Task 1) — FOUND in git log
- Commit `6c8abdd` (Task 2) — FOUND in git log
- Commit `8f68a05` (Task 3) — FOUND in git log
- REQUIREMENTS.md `^- \[ \] \*\*(DET|KPI|RING|REPLAY|UI)-` count = 26 — VERIFIED
- REQUIREMENTS.md `^| (DET|KPI|RING|REPLAY|UI)-.*Phase 25` count = 26 — VERIFIED
- VehicleSpawner.java contains `TICKS_PER_SEC` and `despawnTicks`, no longer contains `despawnTimestamps` or `System.currentTimeMillis` — VERIFIED
- IVehicleSpawner.java contains `int getThroughput(long currentTick)` — VERIFIED
- Full backend suite: 396 tests run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS — VERIFIED
- 25-WAVE0-SPIKE-RESULT.md contains `RING-ROAD-INTERSECTION-TYPE: PRIORITY` directive — VERIFIED
