---
status: passed
phase: 03-physics-engine-idm
verified_at: 2026-03-28T15:21:19Z
must_haves_verified: 4/4
---

# Phase 03 Verification: Physics Engine (IDM)

## Phase Goal

> Implement full IDM car-following physics with velocity clamping and all edge-case guards so physics is correct and isolated before integration.

**Verdict: PASSED** — all must-haves verified, all tests green.

## Must-Have Checklist

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Full IDM formula implemented | PASS | `computeAcceleration()` implements `aMax * [1 - (v/v0)^4 - (sStar/s)^2]` with desired gap `s* = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))` |
| 2 | 5 edge-case guards present | PASS | Guard 1: zero/negative gap clamp (`S_MIN=1.0`), Guard 2: negative speed clamp, Guard 3: maxSpeed clamp, Guard 4: NaN/Infinity fallback to `-b`, Guard 5: s* floor via `max(0,...)` |
| 3 | Semi-implicit Euler integration | PASS | Position uses updated speed: `newPosition = position + newSpeed * dt` |
| 4 | Front-to-back processing order | PASS | `vehicles.sort(Comparator.comparingDouble(Vehicle::getPosition).reversed())` |

## Success Criteria Verification

### 1. Single-vehicle free-flow test

**PASS** — Test `freeFlow_singleVehicle_acceleratesTowardV0()` confirms vehicle accelerates from 0, reaches speed > 3.0 m/s after 60 ticks (3s), no NaN/Infinity at any tick, speed clamped to [0, maxSpeed]. Test `freeFlow_positionMonotonicallyIncreases()` additionally confirms position never decreases.

### 2. Two-vehicle following test

**PASS** — Test `twoVehicleFollowing_gapNeverBelowS0()` runs 200 ticks with leader at 100m/20m/s and follower at 80m/20m/s. Asserts gap >= s0 (2.0m) every tick. Gap never reaches zero.

### 3. Emergency stop test

**PASS** — Test `emergencyStop_followerStopsBeforeCollision()` stops leader instantly, follower at 10m gap doing 20 m/s. Over 200 ticks: speed clamped to [0, maxSpeed], no collision (position check with 1.0m tolerance for S_MIN guard), follower stops to < 1.0 m/s.

## Requirement Traceability

| Requirement | Description | Covered By | Status |
|-------------|-------------|------------|--------|
| **SIM-01** | Simulation runs on tick-based loop at configurable rate (default 20 Hz) | `tick(Lane lane, double dt)` accepts configurable dt; tests use DT=0.05 (20 Hz) | DONE |
| **SIM-03** | Vehicles accelerate and decelerate smoothly using IDM car-following model | Full IDM formula in `computeAcceleration()`; free-flow and following tests | DONE |
| **SIM-04** | Vehicles maintain safe following distance to vehicle ahead | `twoVehicleFollowing_gapNeverBelowS0()` — gap >= s0 over 200 ticks | DONE |
| **SIM-07** | Velocity clamped to [0, maxSpeed] with edge-case guards (NaN, zero gap) | Guards 1-5; tests: `velocityClamp_speedNeverExceedsLaneMaxSpeed`, `zeroGapGuard_overlapProducesFiniteBraking`, `nanGuard_zeroParametersProduceFiniteResult` | DONE |

## Test Coverage Summary

| Test | What It Verifies | Result |
|------|------------------|--------|
| `freeFlow_singleVehicle_acceleratesTowardV0` | Free-flow acceleration, no NaN, speed bounds | PASS |
| `twoVehicleFollowing_gapNeverBelowS0` | Safe following distance (SIM-04) | PASS |
| `emergencyStop_followerStopsBeforeCollision` | Hard braking, no collision, velocity clamp (SIM-07) | PASS |
| `zeroGapGuard_overlapProducesFiniteBraking` | Guard 1: negative gap handling | PASS |
| `nanGuard_zeroParametersProduceFiniteResult` | Guard 4: NaN fallback with corrupted params | PASS |
| `velocityClamp_speedNeverExceedsLaneMaxSpeed` | Guard 3: lane maxSpeed clamp (SIM-07) | PASS |
| `benchmark_500vehicles_under5msPerTick` | Performance: 500 vehicles < 5ms/tick, no NaN leakage | PASS |
| `freeFlow_positionMonotonicallyIncreases` | Position invariant in free-flow | PASS |
| `emptyLane_noException` | Empty lane edge case | PASS |

**Test execution: 9/9 pass, 0 failures, 0.183s total** (verified 2026-03-28)

## Human Verification Items

None required. All success criteria are covered by automated tests with clear assertions.

## Key Files

- `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java` — implementation
- `backend/src/test/java/com/trafficsimulator/engine/PhysicsEngineTest.java` — 9-test suite
