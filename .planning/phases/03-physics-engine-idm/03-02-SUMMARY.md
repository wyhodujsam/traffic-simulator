---
phase: 3
plan: 2
name: "PhysicsEngine Unit Test Suite"
requirements: ["SIM-01", "SIM-03", "SIM-04", "SIM-07"]
key-files:
  - backend/src/test/java/com/trafficsimulator/engine/PhysicsEngineTest.java
duration: ~5 min
---

# Plan 3.2 Summary: PhysicsEngine Unit Test Suite

## What Was Done

Created a comprehensive 9-test suite for `PhysicsEngine` covering all phase success criteria:

1. **Free-flow acceleration** -- single vehicle accelerates from 0 toward v0, speed > 3.0 after 60 ticks, invariants hold every tick
2. **Two-vehicle following (SIM-04)** -- gap never drops below s0 (2.0m) over 200 ticks
3. **Emergency stop (SIM-07)** -- leader stops instantly, follower brakes to near-stop, no collision, speed clamped [0, maxSpeed]
4. **Zero-gap guard** -- overlapping vehicles (negative gap) produce finite negative acceleration, no NaN/exception
5. **NaN guard** -- corrupted parameters (aMax=0, b=0) produce finite results via fallback
6. **Velocity clamp (SIM-07)** -- personal v0 above lane maxSpeed, speed clamped to lane.getMaxSpeed()
7. **500-vehicle benchmark** -- 100 ticks in <500ms (5ms/tick), no NaN leakage across all vehicles
8. **Position monotonicity** -- position never decreases in free-flow
9. **Empty lane** -- no exception on empty vehicle list

## Adaptation From Plan

Test 4 (zero-gap guard) was adapted: the original plan placed the follower ahead of the leader (position 104.5 vs 100.0), which after descending-position sorting made the "follower" the frontmost vehicle in free-flow. Fixed by placing follower at 99.0 so the gap is genuinely negative (-3.5m) while maintaining correct leader/follower ordering.

## Results

- All 9 PhysicsEngine tests pass
- All 38 total project tests pass (9 new + 29 existing)
- Benchmark: 500 vehicles, 100 ticks complete well under 500ms threshold
