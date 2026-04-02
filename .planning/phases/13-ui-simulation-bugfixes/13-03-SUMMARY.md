---
phase: 13-ui-simulation-bugfixes
plan: "03"
subsystem: backend-engine
tags: [intersection, merge, lane-targeting, tdd]
dependency_graph:
  requires: []
  provides: [merge-lane-targeting]
  affects: [IntersectionManager, highway-merge-map]
tech_stack:
  added: []
  patterns: [merge-detection, lane-index-targeting]
key_files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java
    - backend/src/test/java/com/trafficsimulator/engine/IntersectionManagerTest.java
decisions:
  - "Overloaded pickTargetLane instead of changing signature to avoid breaking forceTransferVehicle call site"
  - "Used getLanes().size() instead of getLaneCount() — Road model has no getLaneCount() method"
metrics:
  duration: "1m 47s"
  completed: "2026-04-02"
  tasks_completed: 1
  tasks_total: 1
  files_changed: 2
requirements_satisfied: [FIX-03]
---

# Phase 13 Plan 03: Merge-Aware Lane Targeting Summary

**One-liner:** pickTargetLane with merge detection — ramp vehicles (1 lane) target lane 0 of multi-lane outbound road instead of random lane.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 RED | Add failing merge tests | c20807e | IntersectionManagerTest.java |
| 1 GREEN | Implement merge-aware pickTargetLane | 5deef63 | IntersectionManager.java |

## What Was Done

Added merge-detection logic to `pickTargetLane` in `IntersectionManager`:

- Added overload `pickTargetLane(Road outRoad, Road inboundRoad)` with merge detection
- When `inboundRoad.getLanes().size() < outRoad.getLanes().size()`, picks the lane with lowest index (lane 0 = rightmost) using `Comparator.comparingInt`
- Updated `transferVehiclesFromLane` to pass `inboundRoad` to the new overload
- Kept single-arg `pickTargetLane(Road road)` overload (delegates to two-arg) for `forceTransferVehicle` call site
- Added `java.util.Comparator` import

## Test Coverage

Two new tests in `IntersectionManagerTest`:

1. `mergeRampVehicleTargetsLane0OfOutboundRoad` — asserts vehicle from 1-lane ramp lands on lane 0 of 2-lane main_after
2. `equalLaneTransferRemainsRandom` — asserts that 50 trials of 2-lane to 2-lane transfer use both lanes (random behavior preserved)

Total: 140 tests pass (was 138 before this plan).

## Deviations from Plan

**1. [Rule 1 - Bug] getLaneCount() method does not exist on Road**
- **Found during:** Task 1 GREEN phase
- **Issue:** Plan specified `inboundRoad.getLaneCount()` but Road model only has `getLanes()` list
- **Fix:** Used `getLanes().size()` instead
- **Files modified:** IntersectionManager.java
- **Commit:** 5deef63

**2. [Architectural choice] Overloaded instead of changed signature**
- **Found during:** Task 1 GREEN phase
- **Issue:** `forceTransferVehicle` also calls `pickTargetLane(outRoad)` without an inbound context
- **Fix:** Added overload `pickTargetLane(Road road)` that delegates to two-arg version with null inboundRoad; kept deadlock resolution unaffected
- **Commit:** 5deef63

## Known Stubs

None.

## Self-Check: PASSED

- IntersectionManager.java: FOUND
- IntersectionManagerTest.java: FOUND
- 13-03-SUMMARY.md: FOUND
- commit c20807e: FOUND
- commit 5deef63: FOUND
