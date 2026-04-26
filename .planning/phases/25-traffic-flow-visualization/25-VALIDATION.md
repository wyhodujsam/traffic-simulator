---
phase: 25
slug: traffic-flow-visualization
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-26
---

# Phase 25 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Sourced from RESEARCH.md §"Validation Architecture" (24 proposed REQ-IDs across 5 categories).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Backend framework** | JUnit 5 + AssertJ + Mockito (bundled in `spring-boot-starter-test` 3.3.5) |
| **Backend config** | `backend/pom.xml` — Surefire plugin |
| **Backend quick run** | `mvn -pl backend -Dtest=<TargetTest> test` (single class, ~15-30s) |
| **Backend full suite** | `mvn -pl backend test` (~308 today, target ~340 after this phase) |
| **Frontend framework** | Vitest 1.6 + React Testing Library 16 + jsdom 29 |
| **Frontend config** | `frontend/vite.config.ts` (Vitest reuses Vite config) |
| **Frontend quick run** | `cd frontend && npm test -- --run <file>` |
| **Frontend full suite** | `cd frontend && npm test && npm run build` |
| **E2E framework** | Playwright 1.59 (Chromium, fullyParallel:false) |
| **E2E config** | `frontend/playwright.config.ts` |
| **E2E command** | `cd frontend && npm run test:e2e` |
| **Estimated full-phase runtime** | ~90s backend + ~25s frontend + ~60s e2e = ~3 min |

---

## Sampling Rate

- **After every task commit:** Run targeted single-class command for the task's primary test
- **After every plan wave:** Run backend full suite + `cd frontend && npm test && npm run build`
- **Before `/gsd-verify-work`:** Full backend + frontend + Playwright e2e all green
- **Max feedback latency:** ~30s for unit tests, ~90s for full backend suite

---

## Per-Task Verification Map

> Req IDs are proposed in RESEARCH.md (CONTEXT.md says they are TBD). The first plan wave finalises and back-fills these into REQUIREMENTS.md.

### Determinism (DET-01..07)

| Req ID | Behavior | Test Type | Automated Command | File Exists | Status |
|--------|----------|-----------|-------------------|-------------|--------|
| DET-01 | Same seed → byte-identical NDJSON over 1000 ticks on ring-road (HEADLINE) | integration | `mvn -pl backend -Dtest=DeterminismIT#sameSeedSameLog test` | ❌ W0 | ⬜ |
| DET-02 | Different seeds → different NDJSON logs | integration | `mvn -pl backend -Dtest=DeterminismIT#differentSeedDifferentLog test` | ❌ W0 | ⬜ |
| DET-03 | Seed precedence command > json > nanoTime; logged at INFO | unit | `mvn -pl backend -Dtest=SimulationEngineSeedTest test` | ❌ W0 | ⬜ |
| DET-04 | All 7 existing scenario JSONs continue to load and run | integration | `mvn -pl backend -Dtest=MapLoaderScenarioTest test` | ✅ extend | ⬜ |
| DET-05 | Sub-RNG split order is fixed (append-only) | unit | `mvn -pl backend -Dtest=SimulationEngineSplitOrderTest test` | ❌ W0 | ⬜ |
| DET-06 | `RUN_FOR_TICKS=N` auto-stops + terminal snapshot broadcast | integration | `mvn -pl backend -Dtest=RunForTicksIT test` | ❌ W0 | ⬜ |
| DET-07 | `RUN_FOR_TICKS_FAST=N` produces same NDJSON as `RUN_FOR_TICKS=N` (same seed) | integration | `mvn -pl backend -Dtest=FastModeParityIT test` | ❌ W0 | ⬜ |

### KPI Suite (KPI-01..07)

| Req ID | Behavior | Test Type | Automated Command | File Exists | Status |
|--------|----------|-----------|-------------------|-------------|--------|
| KPI-01 | `KpiDto.throughputVehiclesPerMin` matches existing `StatsDto.throughput` | unit | `mvn -pl backend -Dtest=KpiAggregatorTest#throughput test` | ❌ W0 | ⬜ |
| KPI-02 | `KpiDto.meanDelaySeconds` per D-05 formula | unit | `mvn -pl backend -Dtest=KpiAggregatorTest#meanDelay test` | ❌ W0 | ⬜ |
| KPI-03 | Per-segment queue length per D-06 | unit | `mvn -pl backend -Dtest=QueueAnalyzerTest test` | ❌ W0 | ⬜ |
| KPI-04 | LOS classifier: A≤7, B≤11, C≤16, D≤22, E≤28, F>28 (D-07) | unit | `mvn -pl backend -Dtest=LosClassifierTest test` | ❌ W0 | ⬜ |
| KPI-05 | Per-segment / per-intersection lists sub-sampled every 5 ticks (D-08) | unit | `mvn -pl backend -Dtest=SnapshotBuilderTest#subSampling test` | ✅ extend | ⬜ |
| KPI-06 | `KpiDto` block present on `/topic/state` every tick after Start | integration | `mvn -pl backend -Dtest=KpiBroadcastIT test` | ❌ W0 | ⬜ |
| KPI-07 | Sub-sample cache cleared on `LOAD_MAP` / `LOAD_CONFIG` | unit | `mvn -pl backend -Dtest=SnapshotBuilderTest#cacheCleared test` | ✅ extend | ⬜ |

### Ring-Road Scenario (RING-01..04)

| Req ID | Behavior | Test Type | Automated Command | File Exists | Status |
|--------|----------|-----------|-------------------|-------------|--------|
| RING-01 | `ring-road.json` loads cleanly (passes MapValidator) | unit | `mvn -pl backend -Dtest=MapLoaderScenarioTest#loadsRingRoad test` | ✅ extend | ⬜ |
| RING-02 | 80 primed vehicles still present after 100 ticks (no PRIORITY-yield stall) | integration | `mvn -pl backend -Dtest=RingRoadIT#ringDoesNotStall test` | ❌ W0 | ⬜ |
| RING-03 | Steady-state ring (pre-perturbation) → all segments at LOS C or D | integration | `mvn -pl backend -Dtest=RingRoadIT#steadyStateLos test` | ❌ W0 | ⬜ |
| RING-04 | After perturbation (tick 200, vehicle 0, 5 m/s, 60 ticks) → at least one segment hits LOS F by tick 500 | integration | `mvn -pl backend -Dtest=RingRoadIT#perturbationProducesLosF test` | ❌ W0 | ⬜ |

### Replay Logger (REPLAY-01..04)

| Req ID | Behavior | Test Type | Automated Command | File Exists | Status |
|--------|----------|-----------|-------------------|-------------|--------|
| REPLAY-01 | NDJSON file written to `target/replays/{seed}-{ISO8601}.ndjson` when enabled | integration | `mvn -pl backend -Dtest=ReplayLoggerIT#writesFile test` | ❌ W0 | ⬜ |
| REPLAY-02 | Header line schema per D-14 (seed, source, mapId, tickDt) | unit | `mvn -pl backend -Dtest=ReplayLoggerTest#headerSchema test` | ❌ W0 | ⬜ |
| REPLAY-03 | Disabled by default; auto-enabled when `RUN_FOR_TICKS` invoked | integration | `mvn -pl backend -Dtest=ReplayLoggerIT#defaultDisabled test` | ❌ W0 | ⬜ |
| REPLAY-04 | `IOException` during write disables logger, no tick-loop crash | unit | `mvn -pl backend -Dtest=ReplayLoggerTest#ioErrorDisablesNotCrashes test` | ❌ W0 | ⬜ |

### Frontend Diagnostics (UI-01..04)

| Req ID | Behavior | Test Type | Automated Command | File Exists | Status |
|--------|----------|-----------|-------------------|-------------|--------|
| UI-01 | `DiagnosticsPanel` collapsed by default; toggle button works | unit (Vitest) | `cd frontend && npm test -- DiagnosticsPanel.test.tsx` | ❌ W0 | ⬜ |
| UI-02 | When collapsed, no canvas elements mounted (D-09) | unit (Vitest) | `cd frontend && npm test -- DiagnosticsPanel.test.tsx#noMountWhenClosed` | ❌ W0 | ⬜ |
| UI-03 | Space-time diagram shows ≥100 m continuous low-speed band on ring-road at tick 500 | e2e (Playwright) | `cd frontend && npm run test:e2e -- diagnostics-spacetime.spec.ts` | ❌ W0 (manual-verify-acceptable per CONTEXT.md "Specific Ideas") | ⬜ |
| UI-04 | Frontend types mirror backend `KpiDto`/`SegmentKpiDto`/`IntersectionKpiDto` | static | `cd frontend && npm run build` | ✅ tsc runs | ⬜ |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

22 new test files / extensions concentrated in backend. None require new test infrastructure — `spring-boot-starter-test`, Vitest, and `@playwright/test` are already present.

**New test files (backend):**

- [ ] `backend/src/test/java/com/trafficsimulator/engine/SimulationEngineSeedTest.java` — DET-03, DET-05
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/KpiAggregatorTest.java` — KPI-01, KPI-02
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/QueueAnalyzerTest.java` — KPI-03
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/LosClassifierTest.java` — KPI-04
- [ ] `backend/src/test/java/com/trafficsimulator/engine/kpi/DelayWindowTest.java` — D-05 rolling-window
- [ ] `backend/src/test/java/com/trafficsimulator/engine/PerturbationManagerTest.java` — D-12 hook
- [ ] `backend/src/test/java/com/trafficsimulator/replay/ReplayLoggerTest.java` — REPLAY-02, REPLAY-04
- [ ] `backend/src/test/java/com/trafficsimulator/integration/DeterminismIT.java` — DET-01, DET-02
- [ ] `backend/src/test/java/com/trafficsimulator/integration/RunForTicksIT.java` — DET-06
- [ ] `backend/src/test/java/com/trafficsimulator/integration/FastModeParityIT.java` — DET-07
- [ ] `backend/src/test/java/com/trafficsimulator/integration/RingRoadIT.java` — RING-02, RING-03, RING-04
- [ ] `backend/src/test/java/com/trafficsimulator/integration/KpiBroadcastIT.java` — KPI-06
- [ ] `backend/src/test/java/com/trafficsimulator/integration/ReplayLoggerIT.java` — REPLAY-01, REPLAY-03

**Extensions to existing files:**

- [ ] `backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java` — RING-01, DET-04 regression methods
- [ ] `backend/src/test/java/com/trafficsimulator/scheduler/SnapshotBuilderTest.java` — KPI-05, KPI-07 methods

**Frontend:**

- [ ] `frontend/src/components/__tests__/DiagnosticsPanel.test.tsx` — UI-01, UI-02
- [ ] `frontend/e2e/diagnostics-spacetime.spec.ts` — UI-03

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Phantom-jam wave is *visually* recognisable on the space-time diagram | UI-03 supplement | Per CONTEXT.md §"Specific Ideas": acceptance is "user can see the wave". Playwright assertion bounds it (≥100 m continuous low-speed band) but final aesthetic judgement is human | After ring-road scenario runs for ≥500 ticks, open `http://localhost:5173`, expand Diagnostics panel, observe a backward-propagating dark band in the space-time diagram |
| INFO log line `[SimulationEngine] Started with seed=<long> source=<json|command|auto>` appears on every Start | DET-03 supplement | Log assertion is brittle in unit tests; eyeball verification at first manual run is sufficient | Start any scenario; check backend stdout for the line |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags in any test command
- [ ] Feedback latency < 30s for unit, < 90s for full backend
- [ ] Existing 308-test backend suite remains green throughout (regression gate)
- [ ] `nyquist_compliant: true` set in frontmatter once plans pass checker

**Approval:** pending
