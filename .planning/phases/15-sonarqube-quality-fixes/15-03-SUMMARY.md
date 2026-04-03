---
phase: 15-sonarqube-quality-fixes
plan: "03"
subsystem: backend-engine
tags: [refactoring, cognitive-complexity, sonarqube, lane-change, intersection]
dependency_graph:
  requires: []
  provides: [SQ-04, SQ-05]
  affects: [backend/engine/LaneChangeEngine, backend/engine/IntersectionManager]
tech_stack:
  added: []
  patterns: [method-extraction, early-returns, guard-clauses]
key_files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/engine/LaneChangeEngine.java
    - backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java
decisions:
  - "Extracted isSafeLaneChange() and checkGapSafety() as separate boolean guards instead of inline conditionals to meet complexity target"
  - "Extracted evaluateIncentive() as standalone method to isolate MOBIL incentive computation from safety checks"
  - "Added hasObstacleConflictInTarget() helper to consolidate obstacle checks in evaluateMOBIL()"
  - "Extracted checkBoxBlocking() from canEnterIntersection() making the entry check a simple 4-step linear flow"
  - "resolveDeadlock() takes ticksSinceTransfer as parameter to avoid recomputing inside the helper"
metrics:
  duration_seconds: 223
  completed_date: "2026-04-03"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 2
---

# Phase 15 Plan 03: Cognitive Complexity Refactoring — LaneChangeEngine & IntersectionManager Summary

Pure method-extraction refactoring resolving 9 CRITICAL SonarQube java:S3776 violations across LaneChangeEngine (4 methods) and IntersectionManager (5 methods), reducing all to cognitive complexity <=15 while keeping all 140 tests green.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Refactor LaneChangeEngine — 4 methods above complexity limit (SQ-04) | 8eb5efb | LaneChangeEngine.java |
| 2 | Refactor IntersectionManager — 5 methods above complexity limit (SQ-05) | a109ccd | IntersectionManager.java |

## What Was Built

### LaneChangeEngine — 9 new private helpers extracted

**From `collectIntents()` (complexity 50 -> ~5):**
- `shouldSkipVehicle(Vehicle, Lane, long, long)` — consolidates stuck-behind-obstacle + cooldown checks
- `evaluateBestIntent(Vehicle, Lane, Road)` — evaluates left/right neighbors and picks best intent

**From `evaluateMOBIL()` (complexity 37 -> ~10):**
- `isSafeLaneChange(Vehicle, Vehicle, boolean)` — safety criterion: new follower braking check
- `checkGapSafety(Vehicle, double, Vehicle, Vehicle, boolean)` — gap checks for zipper and normal modes
- `hasObstacleConflictInTarget(Vehicle, double, Lane)` — obstacle-ahead and obstacle-behind checks
- `evaluateIncentive(...)` — MOBIL incentive criterion computation

**From `markZipperCandidates()` (complexity 29 -> ~5):**
- `clearZipperMarks(Lane)` — clears zipper flags on all vehicles in lane
- `markZipperCandidatesInLane(Lane, long)` — per-lane obstacle iteration with interval enforcement
- `findClosestVehicleBehindObstacle(Lane, Obstacle)` — finds closest slow vehicle in proximity window

**From `commitLaneChanges()`:**
- `recordZipperMerge(Vehicle, Lane, long)` — records tick for zipper rate-limiting per obstacle

### IntersectionManager — 7 new private helpers extracted

**From `canEnterIntersection()` (complexity 27 -> ~6):**
- `checkBoxBlocking(Intersection, String, RoadNetwork)` — outbound road space check

**From `hasVehicleFromRight()` (complexity 23 -> ~6):**
- `hasWaitingVehicleOnRoad(Road, double)` — checks if any vehicle is past threshold position
- `isApproachFromRight(double, double)` — angle normalization and right-approach range check

**From `processTransfers()` (complexity 31 -> ~6):**
- `countWaitingVehicles(Intersection, RoadNetwork)` — counts stopped vehicles at stop lines
- `transferFromInboundRoads(Intersection, RoadNetwork, Map)` — iterates inbound roads and transfers

**From `checkDeadlocks()` (complexity 19 -> ~5):**
- `resolveDeadlock(Intersection, RoadNetwork, IntersectionState, long, long)` — force-advances victim vehicle

**From `transferVehiclesFromLane()` (complexity 18 -> ~6):**
- `tryTransferVehicle(Vehicle, Intersection, String, Road, RoadNetwork, Map)` — single vehicle transfer attempt

## Verification

- All 140 backend tests pass (mvn test — BUILD SUCCESS)
- LaneChangeEngine: 13 private methods (was 7 before refactoring, +6 net extracted helpers)
- IntersectionManager: 13 private methods (was 9 before refactoring, +7 net extracted helpers)
- No behavior changes — pure structural refactoring

## Deviations from Plan

None — plan executed exactly as written with minor additions:
- Added `hasObstacleConflictInTarget()` helper (not in plan explicitly, extracted from evaluateMOBIL() obstacle block to reach complexity target)
- Added `evaluateIncentive()` helper (made evaluateMOBIL linear flow cleaner)
- `findMergeTarget()` referenced in plan does not exist in codebase; the 4th method was the inner loop of `markZipperCandidates()` which was refactored by extracting `findClosestVehicleBehindObstacle()`, `clearZipperMarks()`, and `markZipperCandidatesInLane()`

## Known Stubs

None.

## Self-Check: PASSED
