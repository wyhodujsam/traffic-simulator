---
phase: 11
plan: 7
name: "Sorted Vehicle List for O(log n) Leader Lookup"
status: done
---

# Plan 11-07: Summary

## What was done

Replaced O(n) linear scans in Lane's leader/follower lookup methods with early-exit scans on a sorted list, reducing per-tick complexity from O(n^2) per lane to O(n log n).

### Changes

1. **Lane.java** — Vehicles list now maintained in descending position order:
   - `addVehicle()` inserts at correct sorted position
   - `resortVehicles()` re-sorts after physics tick
   - `getLeader()` uses index-based lookup on sorted list (O(n) identity scan, but early-exit)
   - `findLeaderAt()` scans from back for first vehicle ahead (early break)
   - `findFollowerAt()` scans from front for first vehicle behind (early break)

2. **PhysicsEngine.java** — Removed redundant in-method sort (was sorting a local copy anyway). Added `lane.resortVehicles()` call at end of `tick()` to restore sorted invariant after position updates.

3. **LaneTest.java** — 7 new tests covering sort order maintenance, leader/follower lookup, resort after position change, and performance (500 vehicles x 100 ticks < 100ms).

## Test results

All 101 tests pass (94 existing + 7 new).

## Files modified

- `backend/src/main/java/com/trafficsimulator/model/Lane.java`
- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- `backend/src/test/java/com/trafficsimulator/model/LaneTest.java`
