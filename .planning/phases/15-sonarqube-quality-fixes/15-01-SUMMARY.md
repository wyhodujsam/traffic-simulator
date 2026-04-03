---
phase: 15-sonarqube-quality-fixes
plan: "01"
subsystem: backend-quality
tags: [sonarqube, test-quality, refactoring, java]
dependency_graph:
  requires: []
  provides: [SQ-01-resolved, SQ-02-resolved, SQ-07-resolved]
  affects: [MapValidator, CommandQueueTest, VehicleSpawnerTest]
tech_stack:
  added: []
  patterns: [extract-method, string-constants, dispatcher-pattern]
key_files:
  created: []
  modified:
    - backend/src/test/java/com/trafficsimulator/engine/CommandQueueTest.java
    - backend/src/test/java/com/trafficsimulator/engine/VehicleSpawnerTest.java
    - backend/src/main/java/com/trafficsimulator/config/MapValidator.java
decisions:
  - "Kept original SIGNAL intersection error message string verbatim (not using INTERSECTION_PREFIX) to preserve exact error text"
  - "Used assertThat(engine.getStatus()).isNotNull() as secondary assertion in CommandQueueTest after latch.await assertion"
metrics:
  duration: "7 minutes"
  completed: "2026-04-03T15:37:07Z"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 3
---

# Phase 15 Plan 01: SonarQube BLOCKER/CRITICAL Fixes (Test Assertions + MapValidator) Summary

Resolved 2 BLOCKER SonarQube violations (java:S2699 missing test assertions) and 2 CRITICAL violations (java:S3776 cognitive complexity, java:S1192 duplicated string literals) across 3 Java files with all 140 tests passing.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add meaningful assertions to CommandQueueTest and VehicleSpawnerTest (SQ-01) | f1bb32a | CommandQueueTest.java, VehicleSpawnerTest.java |
| 2 | Refactor MapValidator — extract methods and string constants (SQ-02, SQ-07) | 4e0a6d1 | MapValidator.java |

## What Was Built

**Task 1 — Test Assertions (SQ-01):**
- `CommandQueueTest.concurrentEnqueue_100Threads`: Added `assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()` to verify all 100 threads completed, plus `assertThat(engine.getStatus()).isNotNull()` after drain
- `VehicleSpawnerTest.tick_noSpawnPoints_doesNothing`: Added `assertThat(emptyNetwork.getRoads()).isEmpty()` and `assertThat(spawner.getThroughput()).isEqualTo(0)` to verify no side effects

**Task 2 — MapValidator Refactoring (SQ-02, SQ-07):**
- Extracted `ROAD_PREFIX = "Road "` and `INTERSECTION_PREFIX = "Intersection "` constants
- Decomposed monolithic 113-line `validate()` method (cognitive complexity 103) into 10 focused private methods
- Main `validate()` is now a clean 8-line dispatcher delegating to: `validateBasicFields`, `validateNodes`, `validateRoads`, `validateSpawnAndDespawnPoints`, `validateIntersections`, `logResult`
- Additional sub-helpers: `validateRoadNodeRefs`, `validateRoadConstraints`, `buildConnectedNodeIds`, `validateSignalPhases`, `validateSignalPhaseRoadRefs`

## Verification

- All 140 backend tests pass: `Tests run: 140, Failures: 0, Errors: 0, Skipped: 0`
- CommandQueueTest: 10 `assert` occurrences (was insufficient before)
- VehicleSpawnerTest: 21 `assert` occurrences (2 new in the fixed test)
- MapValidator: `ROAD_PREFIX` and `INTERSECTION_PREFIX` constants present
- MapValidator: 11 private helper methods extracted

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written, with one minor adjustment:

**Decision: Kept "SIGNAL intersection" error message verbatim**
- The SIGNAL intersection empty-phases error used `"SIGNAL intersection "` (lowercase 'i') in the original code
- Plan suggested using `INTERSECTION_PREFIX` but that would change the message (uppercase 'I')
- Kept original string to preserve exact error text and avoid test regressions

## Known Stubs

None.
