# Plan 7.1 Summary: LaneChangeEngine with MOBIL Model & Two-Phase Update

## Status: COMPLETE

## What was done

### Task 1: Vehicle lane-change fields
Added 4 fields to `Vehicle.java`: `lastLaneChangeTick`, `forceLaneChange`, `laneChangeProgress`, `laneChangeSourceIndex` (with `@Builder.Default = -1`).

### Task 2: Road neighbor-lane helpers
Added `getLeftNeighbor(Lane)` and `getRightNeighbor(Lane)` methods to `Road.java`, returning adjacent active lanes or null.

### Task 3: Lane follower/leader lookup by position
Added `findLeaderAt(double position)` and `findFollowerAt(double position)` to `Lane.java` for MOBIL neighbor evaluation.

### Task 4: PhysicsEngine public computeAcceleration
Changed `computeAcceleration` from `private` to `public`. Added `computeFreeFlowAcceleration` convenience method.

### Task 5: LaneChangeEngine
Created `LaneChangeEngine.java` implementing:
- Full MOBIL algorithm (safety criterion with b_safe=4.0, incentive criterion with politeness=0.3)
- Asymmetric thresholds for keep-right bias (left=0.3, right=0.1)
- Two-phase intent buffer: collect intents -> resolve conflicts -> commit moves
- Conflict resolution by grouping intents per target lane and selecting highest incentive
- 3-second cooldown between lane changes
- Force lane change support (for road narrowing, skips incentive check)
- Lane change animation progress tracking (10-tick transition)

## Verification
- `mvn compile -q` -- SUCCESS
- `mvn test` -- 44 tests, 0 failures
- All acceptance criteria grep checks pass

## Files modified
- `backend/src/main/java/com/trafficsimulator/model/Vehicle.java`
- `backend/src/main/java/com/trafficsimulator/model/Road.java`
- `backend/src/main/java/com/trafficsimulator/model/Lane.java`
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- `backend/src/main/java/com/trafficsimulator/engine/LaneChangeEngine.java` (new)
