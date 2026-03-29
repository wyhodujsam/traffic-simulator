# Plan 08-05 Summary: Intersection Tests

## Status: DONE

## What was done

Created 21 new tests across 4 test classes covering all Phase 8 intersection logic.

### Task 1: TrafficLightTest (6 tests)
- `phaseCyclesCorrectly` — verifies phase advances after duration expires
- `phaseWrapsAround` — verifies wrap-around back to phase 0
- `isGreenReturnsTrueForCorrectRoad` — green signal check for listed roads
- `isYellowReturnsTrueForCorrectRoad` — yellow signal check
- `redForRoadNotInCurrentPhase` — RED default for unlisted roads
- `replacePhasesResetsTimer` — timer and index reset on phase replacement

### Task 2: TrafficLightControllerTest (3 tests)
- `advancesAllSignalIntersections` — SIGNAL light phaseElapsedMs advances, NONE untouched
- `doesNothingOnEmptyNetwork` — no exception on empty intersections map
- `doesNothingOnNullNetwork` — null-safe tick

### Task 3: IntersectionManagerTest (9 tests)
- `stopLinesGeneratedForRedLight` — RED lanes get stop line entries
- `noStopLinesForGreenLight` — all-GREEN produces no stop lines
- `vehicleTransferredOnGreenLight` — vehicle moves to outbound road at position 0
- `vehicleBlockedOnRedLight` — RED light prevents transfer
- `boxBlockingPreventsEntry` — blocked outbound roads prevent entry
- `boxBlockingAllowsWhenOutboundClear` — empty outbound allows transfer
- `vehicleSpeedPreservedDuringTransfer` — speed retained, position reset to 0
- `deadlockDetectedAndResolved` — force-advance after 200 ticks of no transfers
- `rightOfWayYieldsToVehicleFromRight` — PRIORITY intersection blocks road with vehicle from right

### Task 4: MapLoaderIntersectionTest (3 tests)
- `loadsFourWaySignalMap` — 8 roads, 1 intersection, 4 in/4 out, 6 phases, 4 spawn/despawn
- `trafficLightPhasesMatchConfig` — phase types, greenRoadIds, and durations match JSON
- `straightRoadStillLoadsCorrectly` — backward compatibility (1 road, 0 intersections, 3 spawns)

## Test results

- **New tests**: 21 (6 + 3 + 9 + 3)
- **Total test suite**: 86 tests, 0 failures, 0 errors
- **No regressions** in existing tests

## Files created
- `backend/src/test/java/com/trafficsimulator/model/TrafficLightTest.java`
- `backend/src/test/java/com/trafficsimulator/engine/TrafficLightControllerTest.java`
- `backend/src/test/java/com/trafficsimulator/engine/IntersectionManagerTest.java`
- `backend/src/test/java/com/trafficsimulator/config/MapLoaderIntersectionTest.java`
