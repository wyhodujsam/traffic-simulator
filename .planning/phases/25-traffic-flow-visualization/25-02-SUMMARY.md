---
phase: 25-traffic-flow-visualization
plan: 02
subsystem: simulation-engine

tags: [determinism, rng, splittable-generator, l64x128mixrandom, spring-injection, sub-rng, seed-precedence]

# Dependency graph
requires:
  - phase: 25-traffic-flow-visualization
    provides: Plan 01 — tick-keyed throughput window (DET-01 precondition; replaces wall-clock keying so seeded reproductions match byte-for-byte)
provides:
  - SimulationEngine.resolveSeedAndStart(Long) — D-01 precedence (command > json > nanoTime)
  - Master L64X128MixRandom constructed via factory.of(MASTER_ALGORITHM).create(seed)
  - Three sub-RNGs split in fixed D-02 append-only order: spawnerRng -> ixtnRoutingRng -> idmNoiseRng
  - SimulationCommand.Start(Long seed) record + CommandDto.seed field for STOMP-driven seed override
  - D-04 INFO log line emitted on every Start
  - RoadNetwork.seed placeholder field (Plan 03 will populate from MapConfig)
  - DET-03 + DET-05 covered by SimulationEngineSeedTest (4 tests) + SimulationEngineSplitOrderTest (2 tests)
affects: [25-03 MapConfig schema extension, 25-04 ring-road perturbation, 25-05 KPI suite, 25-06 NDJSON replay log header, future Phase 27 headless batch runner, future Phase 28 reward function]

# Tech tracking
tech-stack:
  added:
    - "java.util.random.RandomGeneratorFactory (Java 17)"
    - "java.util.random.RandomGenerator.SplittableGenerator (Java 17)"
  patterns:
    - "Master/sub-RNG via SplittableGenerator.split() with documented FIXED append-only spawn order"
    - "Spring constructor injection of concrete spawner/intersection beans alongside @Lazy CommandDispatcher; secondary 2-arg ctor preserved for non-Spring tests"
    - "Per-component RNG handle replaced on every Start via package-private setRng() — owners hold the reference"
    - "Optional Long seed propagated through CommandDto -> CommandHandler/SimulationController -> SimulationCommand.Start -> SimulationEngine.resolveSeedAndStart"

key-files:
  created:
    - "backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSplitOrderTest.java"
    - "backend/src/test/java/com/trafficsimulator/engine/RngBootstrapTest.java (Task 1)"
    - "backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerRngTest.java (Task 2)"
  modified:
    - "backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java (Tasks 1+3 — MASTER_ALGORITHM constant + resolveSeedAndStart + 4-arg @Autowired ctor + getters)"
    - "backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java (Task 2 — RandomGenerator field + setRng)"
    - "backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java (Task 2 — RandomGenerator field + setRng + 4 routing sites switched off ThreadLocalRandom)"
    - "backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java (Task 3 — handleStart receives Start cmd, calls engine.resolveSeedAndStart(cmd.seed()))"
    - "backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java (Task 3 — record Start(Long seed))"
    - "backend/src/main/java/com/trafficsimulator/dto/CommandDto.java (Task 3 — Long seed field)"
    - "backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java (Task 3 — START case passes dto.getSeed())"
    - "backend/src/main/java/com/trafficsimulator/controller/SimulationController.java (Task 3 — REST mirror also passes dto.getSeed())"
    - "backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java (Task 3 — placeholder Long seed field; Plan 03 wires loader)"
    - "backend/src/test/java/com/trafficsimulator/engine/CommandDispatcherTest.java (Task 3 — Start() -> Start(null) callsites)"
    - "backend/src/test/java/com/trafficsimulator/engine/CommandQueueTest.java (Task 3 — Start() -> Start(null) callsites)"
    - "backend/src/test/java/com/trafficsimulator/engine/ConcurrencySafetyTest.java (Task 3 — Start() -> Start(null) callsites)"
    - "backend/src/test/java/com/trafficsimulator/bdd/SimulationSteps.java (Task 3 — Start() -> Start(null))"

key-decisions:
  - "Two-constructor design on SimulationEngine: @Autowired 4-arg (Spring) delegates to private helper; convenience 2-arg (tests) calls 4-arg with nulls — preserves all existing test fixtures while adding bean injection without touching CommandDispatcherTest scaffolding."
  - "RoadNetwork.seed added as a placeholder (defaults to null) rather than waiting for Plan 03 — keeps SimulationEngine.resolveSeedAndStart self-contained and lets the Wave-1 contract land before MapConfig.seed JSON loading lands in Plan 03."
  - "Sub-RNG references owned by SimulationEngine (with @Getter for tests) rather than Spring-managed beans — allows clean re-seeding on every Start without re-creating the spawner/intersection-manager beans."
  - "spawnerRng split FIRST despite no current consumer — locks the D-02 spawn order today so future per-spawn jitter (planned for v3.0 reward function) can append at the END (idmNoiseRng position) without reshuffling intersectionRoutingRng/idmNoiseRng streams."
  - "Both CommandHandler (STOMP) and SimulationController (REST) parse dto.getSeed() — REST mirror was missed by the original plan grep (only checked test files); added during Task 3 to keep the two channels symmetric."

patterns-established:
  - "Pattern 1: Master RNG with split() for sub-streams (D-02) — append-only spawn order documented in setter comments + enforced by SimulationEngineSplitOrderTest."
  - "Pattern 2: Engine owns the RNG split tree, components own typed handles via package-private setRng — keeps test injectability without exposing factory churn."
  - "Pattern 3: Optional Long seed crosses the trust boundary safely — full Long range valid for L64X128MixRandom factory.create(long) per Oracle Javadoc; no bound needed (T-25-05 informational)."

requirements-completed:
  - DET-03
  - DET-05

# Metrics
duration: ~25min (Task 3 only; Tasks 1+2 covered in earlier commits)
completed: 2026-04-26
---

# Phase 25 Plan 02: Determinism RNG wiring Summary

**Master L64X128MixRandom + 3 sub-RNGs (spawnerRng -> ixtnRoutingRng -> idmNoiseRng) split on every Start with D-01 precedence (command > json > nanoTime) and D-04 INFO log line; ThreadLocalRandom eliminated from the engine package.**

## Performance

- **Duration:** ~25 min (Task 3 implementation + tests + commit; Tasks 1+2 were completed in earlier commits)
- **Started:** 2026-04-26 (Plan 02 began with Task 1 commit `387dce8`)
- **Completed:** 2026-04-26
- **Tasks:** 3
- **Files modified:** 13 (8 main src + 5 test/bdd)

## Accomplishments

- **D-01 seed precedence locked:** Resolution order `command.seed > mapConfig.seed > System.nanoTime()` lives in `SimulationEngine.resolveSeedAndStart(Long)` (lines 123-156). Source string ("command" / "json" / "auto") recorded on engine for tests + future replay-log header.
- **D-02 sub-RNG split contract enforced:** Three sub-RNGs spawned in FIXED append-only order via `masterRng.split()` — `spawnerRng -> ixtnRoutingRng -> idmNoiseRng`. Inline comment + SplitOrderTest enforce that future consumers append at the END.
- **D-03 component injection wired:** Both `VehicleSpawner.setRng()` (Task 2) and `IntersectionManager.setRng()` (Task 2) called by the engine on every Start with the appropriate sub-RNG; defaults to nanoTime-seeded master so non-Spring callers still work.
- **D-04 INFO log emitted** on every Start: `[SimulationEngine] Started with seed={} source={}` — verified by Task 3 wiring (visible in mvn test output).
- **5 ThreadLocalRandom call sites eliminated** from the engine package (1 in VehicleSpawner.vary() at line ~141, 4 in IntersectionManager at the routing/lane-pick sites).
- **STOMP + REST channels symmetric:** Both `CommandHandler` (`/app/command`) and `SimulationController` (`POST /api/command`) parse the optional `seed` from CommandDto and forward to `Start(Long)`.
- **6 new dedicated tests** cover DET-03 (4 in SimulationEngineSeedTest) and DET-05 (2 in SimulationEngineSplitOrderTest).
- **Backend regression:** 408 tests passed, 0 failures, 0 errors, 1 skipped (pre-existing). Up from 396 before plan started; +12 from new tests across all 3 tasks (RngBootstrapTest=3, VehicleSpawnerRngTest=3, SimulationEngineSeedTest=4, SimulationEngineSplitOrderTest=2).

## Task Commits

Each task committed atomically:

1. **Task 1: MASTER_ALGORITHM constant + RngBootstrapTest (T-25-04 mitigation)** — `387dce8` (feat)
2. **Task 2: Inject RandomGenerator into VehicleSpawner + IntersectionManager (D-03)** — `fcef216` (feat)
3. **Task 3: Wire seed resolution + sub-RNG split into SimulationEngine; extend Start command + DTO (D-01..D-04)** — `be077fd` (feat)

**Plan metadata commit:** to follow (this SUMMARY.md commit).

## Files Created/Modified

### Created
- `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java` — 4 tests covering D-01 precedence (command > json > auto) + lastSeedSource recording (DET-03)
- `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSplitOrderTest.java` — 2 tests covering append-only spawn order + sub-RNG distinctness (DET-05)
- `backend/src/test/java/com/trafficsimulator/engine/RngBootstrapTest.java` — Task 1: factory.of(MASTER_ALGORITHM) returns non-null + creates SplittableGenerator (T-25-04)
- `backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerRngTest.java` — Task 2: setRng + reproducibility of vary()

### Modified
- `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` — MASTER_ALGORITHM constant (Task 1); 4-arg @Autowired ctor + sub-RNG fields + resolveSeedAndStart + getters (Task 3, lines 102-167)
- `backend/src/main/java/com/trafficsimulator/engine/VehicleSpawner.java` — RandomGenerator field default to factory-seeded master + setRng (Task 2); vary() at line 170 reads injected idmNoiseRng (was ThreadLocalRandom at line 141)
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` — RandomGenerator field + setRng (Task 2); 4 ThreadLocalRandom callsites (formerly lines 366, 495, 500, 524) replaced with routingRng.nextInt(...)
- `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` — handleStart now receives `SimulationCommand.Start cmd` and calls `engine.resolveSeedAndStart(cmd.seed())` BEFORE flipping status to RUNNING (Task 3)
- `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java` — `record Start(Long seed)` (Task 3)
- `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java` — `private Long seed` with javadoc citing D-01 precedence (Task 3)
- `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java` — STOMP START case parses dto.getSeed() (Task 3)
- `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java` — REST POST /command START case parses dto.getSeed() (Task 3, missed by plan's grep)
- `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java` — placeholder `private Long seed` field; Plan 03 will populate from MapConfig
- `backend/src/test/java/com/trafficsimulator/engine/CommandDispatcherTest.java` — 4 zero-arg `Start()` callsites updated to `Start(null)` (Task 3)
- `backend/src/test/java/com/trafficsimulator/engine/CommandQueueTest.java` — 5 zero-arg `Start()` callsites updated to `Start(null)` (Task 3)
- `backend/src/test/java/com/trafficsimulator/engine/ConcurrencySafetyTest.java` — 4 zero-arg `Start()` callsites updated to `Start(null)` (Task 3)
- `backend/src/test/java/com/trafficsimulator/bdd/SimulationSteps.java` — 1 zero-arg `Start()` callsite updated to `Start(null)` (Task 3)

## Spawn Order Contract (D-02)

The fixed FIRST-DRAW order locked into `SimulationEngine.resolveSeedAndStart` (line ~146):

```
master = factory.of("L64X128MixRandom").create(seed)
spawnerRng       = master.split()   // [1] reserved for future per-spawn jitter
ixtnRoutingRng   = master.split()   // [2] -> IntersectionManager.setRng
idmNoiseRng      = master.split()   // [3] -> VehicleSpawner.setRng (vary())
```

**Future consumers MUST append at position [4]+** — never insert. Inserting reshuffles every existing seeded stream and breaks DET-05 byte-identity. `SimulationEngineSplitOrderTest.splitOrder_isFixed_acrossInvocationsWithSameSeed` will fail loudly if violated.

## Test Coverage Delta

| Suite                              | Before | After | Notes                                  |
|------------------------------------|--------|-------|----------------------------------------|
| Backend total                      | 396    | 408   | +12 new tests across 4 new test files  |
| RngBootstrapTest (DET T-25-04)     | 0      | 3     | Task 1                                 |
| VehicleSpawnerRngTest              | 0      | 3     | Task 2                                 |
| SimulationEngineSeedTest (DET-03)  | 0      | 4     | Task 3                                 |
| SimulationEngineSplitOrderTest (DET-05) | 0 | 2     | Task 3                                 |

## Decisions Made

- **Two-constructor design on SimulationEngine.** `@Autowired` 4-arg constructor used by Spring (with concrete spawner/intersection beans for setRng wiring); convenience 2-arg constructor preserved for the existing 16+ test sites that build engines manually with `new SimulationEngine(null, null)`. Avoids touching every test fixture; sub-RNG injection becomes a no-op when bean refs are null.
- **RoadNetwork.seed added now (placeholder).** Plan 03 was originally going to add this, but `resolveSeedAndStart` needs to read it today for D-01 precedence. Defaults to null; SimulationEngine reads via `roadNetwork.getSeed()` and treats null as "fall through to auto." Plan 03's MapConfig wiring will simply set the field non-null.
- **Sub-RNG handles owned by SimulationEngine, not Spring.** Field-level `@Getter` exposes them for tests. Spring-managed sub-RNGs would require destroying/recreating beans on every Start — too invasive for what's effectively a re-seed.
- **`spawnerRng` registered FIRST despite no current consumer.** Locks D-02 spawn order today so v3.0 per-spawn jitter (planned for reward function in Phase 28) can append at position [4] without reshuffling. Documented in inline comment inside `resolveSeedAndStart`.
- **REST endpoint added to seed propagation.** Plan 02 only listed STOMP `CommandHandler` for seed wiring; `SimulationController.POST /api/command` mirrors STOMP commands and was missed by the plan's grep. Updated to symmetric for completeness — keeps headless batch testing honest when v3.0 routes through REST.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Spring DI ambiguity from two constructors**
- **Found during:** Task 3 (after first compile + test run)
- **Issue:** Adding the new 4-arg constructor alongside the existing 2-arg constructor caused Spring to fail bean instantiation: `No default constructor found` because Spring couldn't pick which to use without a hint.
- **Fix:** Annotated the new 4-arg constructor with `@Autowired` (Spring's preferred-constructor marker). Added Javadoc on both constructors documenting their purpose (Spring vs test).
- **Files modified:** `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` (added `@Autowired` import + annotation)
- **Verification:** Full `mvn test` passes 408/408 (cucumber Spring context loads successfully again)
- **Committed in:** `be077fd` (Task 3 commit)

**2. [Rule 2 - Missing critical] REST `/api/command` endpoint not in plan grep**
- **Found during:** Task 3 (initial compile)
- **Issue:** Plan 02 Task 3 §"action" step 9 says "Find all `new SimulationCommand.Start()` call sites in tests + main code" — the grep also caught `SimulationController.java:232` (REST mirror of STOMP commands), which the plan didn't enumerate but which would have left REST callers unable to pass a seed.
- **Fix:** Updated `SimulationController.postCommand` `case "START"` to also call `new SimulationCommand.Start(dto.getSeed())`, mirroring `CommandHandler.handleCommand`.
- **Files modified:** `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java`
- **Verification:** Compile passes; REST channel now accepts seed parameter symmetrically with STOMP.
- **Committed in:** `be077fd` (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking Spring DI, 1 missing critical channel symmetry)
**Impact on plan:** Both auto-fixes essential for correctness. Spring fix unblocked the cucumber + integration suite (24+ tests would have failed without it). REST symmetry keeps headless batch testing honest. No scope creep.

## Issues Encountered

- **First mvn test attempt revealed cucumber Spring context failure (24 errors)** — root cause was the two-constructor ambiguity (Deviation #1). Fixed inline; subsequent `mvn test` clean (408/408 pass).
- **No issues encountered for Tasks 1 and 2** (committed earlier in this plan; verified clean by running their dedicated tests during this Task 3 execution).

## TDD Gate Compliance

Plan-level type was `execute` (not `tdd`), so plan-level gate sequence does not apply. However, Task 3 was marked `tdd="true"` and the tests for DET-03/DET-05 were written FIRST (existed in the working tree before any Task 3 source changes). Test files were verified to fail-to-compile before source modifications (confirmed by initial mvn compile error pointing at missing `engine.resolveSeedAndStart`, missing 4-arg ctor, missing `RoadNetwork.seed` field). After source changes the tests compiled AND passed first try (4/4 + 2/2). Effectively RED -> GREEN cycle within a single commit.

## User Setup Required

None — no external service configuration required. All RNG construction is in-process (Java standard library) and fully deterministic.

## Next Phase Readiness

- **Plan 03 (MapConfig.seed JSON loading)** can proceed — `RoadNetwork.seed` field placeholder is already in place; Plan 03 just needs to wire MapConfig schema + MapLoader to populate it. `SimulationEngine.resolveSeedAndStart` already reads it via `roadNetwork.getSeed()`.
- **Plan 04 (ring-road perturbation)** can proceed — perturbation injection point in PhysicsEngine.tick() does NOT need RNG (it's a deterministic clamp), so no Plan 02 dependency.
- **Plan 05 (KPI suite)** can proceed — KPI computation does not need RNG; reads from existing per-vehicle state.
- **Plan 06 (NDJSON replay log)** unblocked — header line can now read `engine.getLastResolvedSeed()` and `engine.getLastSeedSource()` directly off the engine.

### Blockers / Concerns

- None. Backend suite is fully green. Frontend not touched in this plan.

## Self-Check: PASSED

Verified all claims:

**Files exist:**
- FOUND: `backend/src/main/java/com/trafficsimulator/engine/SimulationEngine.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/engine/CommandDispatcher.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/engine/command/SimulationCommand.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/dto/CommandDto.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/controller/CommandHandler.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/controller/SimulationController.java` (modified)
- FOUND: `backend/src/main/java/com/trafficsimulator/model/RoadNetwork.java` (modified)
- FOUND: `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java` (created)
- FOUND: `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSplitOrderTest.java` (created)

**Commits exist:**
- FOUND: `387dce8` (Task 1: MASTER_ALGORITHM + RngBootstrapTest)
- FOUND: `fcef216` (Task 2: Inject RandomGenerator into VehicleSpawner + IntersectionManager)
- FOUND: `be077fd` (Task 3: seed resolution + sub-RNG split + Start(Long seed) + INFO log)

**Acceptance grep checks:**
- PASSED: `! grep -rn "ThreadLocalRandom" backend/src/main/java/com/trafficsimulator/engine/` (zero matches)
- PASSED: `grep -q "MASTER_ALGORITHM" backend/.../SimulationEngine.java`
- PASSED: `grep -q "record Start(Long seed)" backend/.../SimulationCommand.java`
- PASSED: `grep -q "private Long seed" backend/.../CommandDto.java`
- PASSED: `grep -q "resolveSeedAndStart" backend/.../SimulationEngine.java`
- PASSED: `grep -q "Started with seed=" backend/.../SimulationEngine.java`
- PASSED: `grep -q "spawnerRng = masterRng.split" backend/.../SimulationEngine.java`
- PASSED: `! grep -rn "new SimulationCommand.Start()" backend/src/` (zero matches)

**Test counts:**
- PASSED: SimulationEngineSeedTest 4/4 pass (DET-03)
- PASSED: SimulationEngineSplitOrderTest 2/2 pass (DET-05)
- PASSED: Full backend regression 408/408 pass, 0 failures, 0 errors, 1 skipped (pre-existing)

---
*Phase: 25-traffic-flow-visualization*
*Completed: 2026-04-26*
