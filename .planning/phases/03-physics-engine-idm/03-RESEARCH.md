# Phase 3 Research: Physics Engine (IDM)

**Phase goal:** Implement full IDM car-following physics with velocity clamping and edge-case safety guards so physics is correct and isolated before integration into the tick loop.
**Requirements:** SIM-01, SIM-03, SIM-04, SIM-07
**Date:** 2026-03-27

---

## 1. IDM Formula — Exact Mathematics

The Intelligent Driver Model (Treiber et al., 2000) computes the longitudinal acceleration of vehicle `i` following vehicle `i-1`:

```
a_i = aMax * [ 1 - (v / v0)^delta - (s*(v, deltaV) / s)^2 ]
```

Where the desired gap `s*` is:

```
s*(v, deltaV) = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))
```

### Parameter Definitions

| Symbol  | Name                       | Unit  | Typical value       | Source in Vehicle.java |
|---------|----------------------------|-------|---------------------|------------------------|
| v       | Current speed              | m/s   | 0 – v0              | `vehicle.getSpeed()`   |
| v0      | Desired speed              | m/s   | 26.7–40.0 (±20%)    | `vehicle.getV0()`      |
| s       | Gap to leader front bumper | m     | computed             | computed per tick      |
| deltaV  | Speed difference (v - v_leader) | m/s | computed       | computed per tick      |
| s0      | Minimum stationary gap     | m     | 2.0 (fixed)         | `vehicle.getS0()`      |
| T       | Desired time headway       | s     | 1.2–1.8 (±20%)      | `vehicle.getT()`       |
| aMax    | Maximum acceleration       | m/s²  | 1.12–1.68 (±20%)    | `vehicle.getAMax()`    |
| b       | Comfortable deceleration   | m/s²  | 1.6–2.4 (±20%)      | `vehicle.getB()`       |
| delta   | Acceleration exponent      | —     | 4 (constant)        | hardcoded constant     |

### Gap Computation

The gap `s` is the front-bumper-to-rear-bumper distance between follower and leader:

```
s = leader.position - vehicle.position - leader.length
```

This is positive when the follower has not yet reached the leader. It represents clear space between vehicles.

### Derivation of the Two Terms

1. **Free-road term**: `(v / v0)^4` — suppresses acceleration as vehicle approaches desired speed. At `v = v0` this equals 1.0, making the first bracket approach zero (no net acceleration).
2. **Interaction term**: `(s* / s)^2` — braking pressure from following a leader. As gap `s` shrinks toward `s*`, this term approaches 1.0, fully cancelling the free-road acceleration. When `s < s*`, the interaction term exceeds 1.0, producing negative acceleration (braking).

---

## 2. Euler Integration

After computing acceleration, apply first-order Euler integration with time step `dt`:

```
speed_new = speed + acceleration * dt
position_new = position + speed_new * dt
```

**Why position uses speed_new (not speed):** Using the updated speed for position integration (semi-implicit Euler) is more stable numerically than using the old speed. It reduces oscillation in stop-and-go traffic at the cost of minimal inaccuracy at small `dt`.

**dt value for this project:** At 20 Hz tick rate with default `speedMultiplier = 1.0`, `dt = 0.05 s`. At `speedMultiplier = 5.0`, logical `dt = 0.25 s` (sub-stepping will be needed in Phase 4 for high multipliers, but Phase 3 only implements physics for a single `dt` value).

---

## 3. Free-Flow Case (No Leader)

When `lane.getLeader(vehicle)` returns `null`, the vehicle has no vehicle ahead. The interaction term drops out:

```
a_i = aMax * [ 1 - (v / v0)^4 ]
```

This is the standard free-acceleration profile. Vehicle accelerates from 0 toward `v0` with decreasing rate as `v` approaches `v0`. The asymptotic approach means the vehicle never exceeds `v0` in theory — in practice, clamping handles the boundary.

---

## 4. Edge Cases and Guards

All guards must execute in this order within `PhysicsEngine`:

### 4.1 Zero / Negative Gap Guard

**Problem:** If `s <= 0`, the interaction term `(s*/s)^2` is `Infinity` or `NaN`. This occurs when vehicles overlap (e.g., at spawn, or if position integration runs ahead of gap check).

**Guard:**
```java
double gap = leader.getPosition() - vehicle.getPosition() - leader.getLength();
double safeGap = Math.max(gap, S_MIN);  // S_MIN = 1.0 m
```

This clamps the effective gap to at least 1.0 m before computing the interaction term. It does not prevent overlap from occurring (that is a spawner concern) but prevents the formula from producing infinite values.

### 4.2 Negative Speed After Integration

**Problem:** A heavy braking step can drive `speed_new` below zero (vehicle reversing). IDM does not self-limit at v=0.

**Guard (after integration):**
```java
vehicle.setSpeed(Math.max(0.0, speed_new));
```

### 4.3 Speed Exceeds maxSpeed (SIM-07)

**Problem:** With heterogeneous parameters, `v0` may exceed `lane.getMaxSpeed()`. The vehicle will asymptote toward its personal `v0`, which may be above the road speed limit.

**Guard (after integration):**
```java
vehicle.setSpeed(Math.min(vehicle.getSpeed(), lane.getMaxSpeed()));
```

Velocity clamp is therefore `[0, lane.getMaxSpeed()]`, satisfying SIM-07.

### 4.4 NaN / Infinity Fallback

**Problem:** Unexpected NaN or Infinity in acceleration (e.g., from `sqrt(aMax * b)` when either parameter is zero due to data corruption).

**Guard:**
```java
if (!Double.isFinite(acceleration)) {
    acceleration = -vehicle.getB();  // fallback: comfortable braking
}
```

This ensures the vehicle slows safely rather than continuing at whatever speed it had, and prevents NaN propagation through position integration.

### 4.5 Desired Gap `s*` Floor

The formula `v*deltaV / (2*sqrt(aMax*b))` can be negative when `deltaV < 0` (follower is slower than leader — the leader is pulling away). Applying `max(0, ...)` prevents `s*` from going below `s0`:

```java
double interactionTerm = v * deltaV / (2.0 * Math.sqrt(vehicle.getAMax() * vehicle.getB()));
double sStar = vehicle.getS0() + Math.max(0.0, v * vehicle.getT() + interactionTerm);
```

---

## 5. Heterogeneous Vehicle Parameters — Phantom Jam Emergence

`VehicleSpawner` already applies ±20% uniform noise to `v0`, `aMax`, `b`, and `T` via the `vary()` method. `s0` is fixed at 2.0 m.

### Why Heterogeneity Matters for Phase 3

- **Emergent jams**: With uniform parameters, traffic flow is laminar. A small perturbation (one vehicle braking slightly) damps out. With heterogeneous parameters, the perturbation amplifies — the follower brakes harder than necessary, causing a cascading backward-propagating shockwave (phantom jam).
- **Critical density**: Phantom jams emerge at approximately 40 vehicles/km in a single lane. At lower densities, gaps are large enough that perturbations dissipate.
- **No code change needed**: The `VehicleSpawner.vary()` method already handles this correctly. `PhysicsEngine` simply reads the per-vehicle parameters and the emergence is automatic.

### VehicleSpawner Parameter Ranges (from existing code)

| Parameter | Base value | Range with ±20% noise |
|-----------|------------|----------------------|
| v0        | 33.3 m/s   | 26.64 – 39.96 m/s   |
| aMax      | 1.4 m/s²   | 1.12 – 1.68 m/s²    |
| b         | 2.0 m/s²   | 1.60 – 2.40 m/s²    |
| T         | 1.5 s      | 1.20 – 1.80 s        |
| s0        | 2.0 m      | fixed                |

---

## 6. PhysicsEngine.java Design

### Class Contract

`PhysicsEngine` is a **stateless Spring service** (`@Component`). It holds no mutable state. All state is passed in and mutated through the `Vehicle` objects within the `Lane` argument.

```java
@Component
public class PhysicsEngine {
    public void tick(Lane lane, double dt) { ... }
}
```

The tick loop in `SimulationEngine` (Phase 4) will call:
```java
for (Lane lane : roadNetwork.getAllLanes()) {
    physicsEngine.tick(lane, dt);
}
```

### Processing Order Within `tick(Lane, dt)`

1. For each vehicle in the lane (order by position descending — process front vehicles first to avoid using already-updated leader positions):
   a. Find leader via `lane.getLeader(vehicle)`.
   b. Compute gap with zero-guard.
   c. Compute `s*` with floor guard.
   d. Compute IDM acceleration.
   e. Apply NaN/Infinity guard.
   f. Apply Euler integration.
   g. Clamp speed to `[0, lane.getMaxSpeed()]`.
   h. Write updated `speed` and `acceleration` back to vehicle.
   i. Update `position`.

**Why process front vehicles first:** If we update vehicle A (at position 100m) then update vehicle B (at position 80m, following A), B reads A's new position for the gap calculation. If A has already moved forward, the gap appears larger — underestimating braking need. Processing front-to-back means each vehicle reads its leader's pre-tick position, which is correct for simultaneous update semantics.

### Performance Contract

- O(n) per lane: each vehicle calls `lane.getLeader()` which is currently O(n). Total: O(n²) in the worst case.
- The `Lane.getLeader()` Javadoc (Phase 2) explicitly notes this must be replaced with a sorted structure for Phase 3.
- **Decision for Phase 3:** Sort `lane.getVehicles()` by position at the start of each tick (O(n log n)), then use index lookup (O(1) per vehicle). Total: O(n log n) per lane per tick — acceptable for 500 vehicles.
- Alternative: Maintain a `TreeMap<Double, Vehicle>` in `Lane` sorted by position. This makes `getLeader()` O(log n) without re-sorting each tick. **Recommended implementation.**

### Target: < 5ms for 500 vehicles

At 500 vehicles with O(n log n) sorted update:
- Sort 500 doubles: ~0.5 ms on modern JVM (JIT-warmed)
- 500 IDM computations (6 floating-point operations each): ~0.05 ms
- Total physics: << 1ms hot path
- Allocation pressure: zero allocation inside the loop (mutate in place)

---

## 7. Integration With Existing Model

### Vehicle.java Alignment

All IDM parameters are already present on `Vehicle`:
- `v0`, `aMax`, `b`, `s0`, `T` — assigned at spawn by `VehicleSpawner`
- `speed`, `acceleration`, `position` — mutable per tick
- `lane` — back-reference to current lane (needed for `maxSpeed`)

`PhysicsEngine` reads `vehicle.getLane().getMaxSpeed()` for the velocity clamp upper bound.

### Lane.java Alignment

`Lane.getLeader(Vehicle)` provides the leader lookup. Phase 3 must either:
1. Sort `lane.getVehicles()` at the start of each tick, or
2. Upgrade `Lane` to maintain a sorted structure.

Option 2 (sorted `List` or `TreeMap`) is cleaner and avoids repeated sorting overhead. However, it changes `Lane.java` which is Phase 2 code. The conservative Phase 3 approach: sort in `PhysicsEngine.tick()` before iterating, leaving `Lane.java` untouched.

### What PhysicsEngine Does NOT Do

- Does not spawn or despawn vehicles (that is `VehicleSpawner`)
- Does not check for obstacles (that is `CollisionDetector`, Phase 6)
- Does not enforce traffic lights (that is `TrafficLightController`, Phase 8)
- Does not manage lane changes (that is `LaneChangeEngine`, Phase 7)
- Does not broadcast state (that is `StatePublisher`, Phase 4)

---

## 8. Unit Test Strategy

### Test 1 — Free-Flow Acceleration

**Scenario:** Single vehicle on a lane, no leader, `v = 0`, `v0 = 33.3`.
**Expected:** After N ticks, vehicle accelerates toward `v0`. Speed never exceeds `lane.getMaxSpeed()`. Position increases each tick. No NaN.
**Assertion:** After 60 ticks (3 s at 20 Hz), `speed > 20.0` and `speed <= maxSpeed`.

### Test 2 — Car Following (SIM-04)

**Scenario:** Two vehicles. Leader at position 100m, speed 20 m/s. Follower at position 80m, speed 20 m/s. Gap = 100 - 80 - 4.5 = 15.5 m.
**Expected:** Follower maintains gap `>= s0 (2.0m)`. Gap never reaches zero over 200 ticks.
**Assertion:** `gap >= vehicle.getS0()` every tick.

### Test 3 — Emergency Stop (SIM-07)

**Scenario:** Leader vehicle stops instantly (speed forced to 0). Follower at 10m gap, speed 20 m/s.
**Expected:** Follower brakes hard. Speed clamped to >= 0 throughout. Position of follower never exceeds `leader.position - leader.length`. No NaN.
**Assertion:** `follower.speed >= 0` every tick; `follower.position < leader.position - leader.length` (no overlap).

### Test 4 — Zero Gap Guard

**Scenario:** Force `gap = 0` (overlap condition — inject directly). Call `PhysicsEngine.tick()`.
**Expected:** No exception, no NaN. Acceleration is large negative (heavy braking) but finite. Speed remains >= 0.

### Test 5 — NaN Guard

**Scenario:** Set `vehicle.aMax = 0` and `vehicle.b = 0` to force `sqrt(0) = 0` in denominator.
**Expected:** Acceleration falls through to NaN guard. Fallback acceleration = `-vehicle.getB()` = 0, which results in coasting. Speed unchanged. No exception.

### Test 6 — Velocity Clamp (SIM-07)

**Scenario:** Vehicle with `v = lane.maxSpeed - 0.01`, in free-flow. After one tick, raw speed would slightly exceed `maxSpeed`.
**Expected:** Speed clamped to exactly `lane.getMaxSpeed()`.

### Test 7 — 500 Vehicle Performance Benchmark

**Scenario:** Create one lane, spawn 500 vehicles at uniform spacing (10m apart). Run 100 ticks.
**Expected:** Total wall-clock time < 500 ms (5 ms/tick × 100 ticks).
**Implementation:** `System.nanoTime()` around the tick loop.

---

## Validation Architecture

The validation strategy for Phase 3 operates at three levels:

### Level 1 — Formula Correctness (Unit Assertions)

Each tick, in test mode, assert post-conditions on every vehicle:

```java
// Post-tick invariants
assert vehicle.getSpeed() >= 0.0 : "negative speed";
assert vehicle.getSpeed() <= lane.getMaxSpeed() : "speed exceeds max";
assert Double.isFinite(vehicle.getSpeed()) : "non-finite speed";
assert Double.isFinite(vehicle.getPosition()) : "non-finite position";
assert vehicle.getPosition() >= 0.0 : "negative position";
```

In production code, these assertions log-and-clamp rather than throwing:
```java
if (!Double.isFinite(newSpeed) || newSpeed < 0) {
    log.warn("Speed invariant violated for vehicle {}: {}", vehicle.getId(), newSpeed);
    newSpeed = Math.max(0.0, Double.isFinite(newSpeed) ? newSpeed : 0.0);
}
```

### Level 2 — Physical Plausibility (Integration Tests)

Tests that run the full physics loop for N ticks and assert macro behavior:

| Test name | Property validated | Duration |
|-----------|--------------------|----------|
| Free-flow convergence | Vehicle reaches ~v0 asymptotically | 100 ticks |
| Safe following gap | `gap >= s0` never violated in following | 500 ticks |
| No backward motion | `position` monotonically non-decreasing | Any |
| Emergency stop no overlap | Follower stops before leader rear bumper | 100 ticks |
| Heterogeneous jam emergence | With 40+ vehicles/km, variance in speeds increases over time | 1000 ticks |

### Level 3 — Performance Regression (Benchmark Tests)

Tagged with `@Tag("benchmark")` to exclude from CI fast path:

- 500-vehicle tick: must complete in < 5 ms (measured over 1000 ticks, take median)
- Allocation check: zero `Vehicle` object allocations during `PhysicsEngine.tick()` (use Java Flight Recorder or `-verbose:gc` assertion)

### Logging Strategy

`PhysicsEngine` logs at `DEBUG` level only:
- Per-vehicle acceleration computed (expensive in production — guard with `log.isDebugEnabled()`)
- NaN/Infinity guard triggered (always log at `WARN`)
- Velocity clamp triggered (log at `TRACE` — very frequent)

Structured log format for observability:
```
WARN  PhysicsEngine - NaN acceleration guard triggered for vehicle=abc123 aMax=1.4 b=0.0
WARN  PhysicsEngine - Speed clamp applied vehicle=def456 rawSpeed=34.2 maxSpeed=33.3
```

### Pitfall Coverage

From PITFALLS.md Section 4 (Vehicle Physics Edge Cases):

| Pitfall | Guard implemented |
|---------|-------------------|
| Overlap on spawn → infinite deceleration | Zero-gap guard clamps `s` to `S_MIN = 1.0 m` |
| Negative speed after integration | `Math.max(0.0, speed_new)` after each integration step |
| Teleportation past road end | Not in scope for Phase 3 — handled by `VehicleSpawner.despawnVehicles()` in Phase 2 |
| Infinite acceleration at gap=0 | Zero-gap guard (same as above) |
| Stopped vehicle chain collapse | `s0 = 2.0 m` minimum gap enforced by `s*` formula floor |

From PITFALLS.md Section 5 (Floating Point Accumulation):

- Use `double` (64-bit) for all position and velocity — already enforced by `Vehicle.java` field types.
- Position boundary for despawn uses `>=` comparison — already in `VehicleSpawner.despawnVehicles()`.

---

## 9. Key Constants

```java
// PhysicsEngine constants
private static final double DELTA = 4.0;          // IDM acceleration exponent
private static final double S_MIN = 1.0;           // minimum gap guard (metres)
private static final double DT_DEFAULT = 0.05;     // default 20 Hz tick interval (seconds)
```

These are separate from the `VehicleSpawner` constants; `PhysicsEngine` does not import IDM base values — it reads them per-vehicle from `vehicle.getAMax()` etc.

---

## 10. Files to Create in Phase 3

| File | Type | Description |
|------|------|-------------|
| `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java` | New | IDM implementation |
| `backend/src/test/java/com/trafficsimulator/engine/PhysicsEngineTest.java` | New | 7-test unit suite |

No existing files from Phase 2 require modification for core functionality. Optionally: add sorted-list optimization to `Lane.java` (Phase 2 file) — this is a performance enhancement, not a correctness fix, and can be deferred.

---

## RESEARCH COMPLETE
