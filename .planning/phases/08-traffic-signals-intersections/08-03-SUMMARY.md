# Plan 08-03 Summary: Deadlock Watchdog & Priority Intersections

## Status: COMPLETE

## What Was Done

### Task 1: Intersection State Tracking
- Added `IntersectionState` inner class with `lastTransferTick` and `waitingVehicleCount` fields
- Added `intersectionStates` HashMap for per-intersection tracking
- State is lazily initialized via `computeIfAbsent` on first encounter
- Waiting vehicle count updated every tick (vehicles near stop line with speed < 0.5)
- Transfer tracking integrated into `processTransfers()` loop

### Task 2: Deadlock Detection & Force-Advance
- Added `checkDeadlocks()` method called at end of `processTransfers()`
- Deadlock detected when 2+ waiting vehicles AND no transfer for 200 ticks (10s at 20Hz)
- `findLongestWaitingVehicle()` selects victim by oldest `spawnedAt` tick
- `forceTransferVehicle()` bypasses space checks, picks random outbound road
- `lastTransferTick` reset after force-advance to prevent repeated triggers
- Warning logged on each deadlock resolution

### Task 3: Right-of-Way Priority (First from Right)
- Added `hasVehicleFromRight()` using geometric angle calculation (`Math.atan2`)
- Integrated into `canEnterIntersection()` for PRIORITY and NONE intersection types
- "Right" = 90 degrees clockwise from approach direction (diff in range [-0.75PI, -0.25PI])
- Opposing roads (180 degrees) do not block each other
- SIGNAL intersections unaffected (use traffic light)
- Box-blocking check still applies to all types

## Verification
- `mvn compile -q`: PASS
- `mvn test`: 65/65 tests pass, 0 failures

## Files Modified
- `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java`

## Requirements Covered
- IXTN-04: Deadlock detection and force-advance resolution
- IXTN-07: Right-of-way priority for unsignalled intersections
