---
phase: 3
plan: 1
name: "PhysicsEngine — Full IDM Implementation"
requirements_completed: ["SIM-01", "SIM-03", "SIM-04", "SIM-07"]
key_files:
  - backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java
duration: "<1 min"
---

# Plan 3.1 Summary: PhysicsEngine — Full IDM Implementation

## What Was Built

Created `PhysicsEngine.java` — a stateless Spring `@Component` implementing the Intelligent Driver Model (IDM) car-following physics.

**Key capabilities:**
- `tick(Lane lane, double dt)` method processes all vehicles in a lane per time step
- IDM acceleration formula: `aMax * [1 - (v/v0)^4 - (s*/s)^2]`
- Desired gap: `s* = s0 + max(0, v*T + v*deltaV / (2*sqrt(aMax*b)))`
- Semi-implicit Euler integration (position uses updated speed)
- Front-to-back processing order (sort descending by position)
- 5 edge-case guards:
  1. Zero/negative gap clamp (`Math.max(gap, S_MIN)` where S_MIN = 1.0m)
  2. Negative speed clamp (`Math.max(0.0, newSpeed)`)
  3. maxSpeed clamp (`Math.min(newSpeed, lane.getMaxSpeed())`)
  4. NaN/Infinity fallback (falls back to `-vehicle.getB()`)
  5. s* floor (`Math.max(0.0, ...)` on dynamic term)

## Deviations

- **Javadoc comment fix**: The plan's Javadoc contained `(s*/s)` which the Java compiler interpreted as closing the `/** */` comment block. Replaced `s*` with `sStar` in the Javadoc only. Code logic is unchanged and matches the plan exactly.

## Self-Check

- [x] File exists: `backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java`
- [x] Has `@Component` annotation
- [x] Has `public void tick(Lane lane, double dt)` method
- [x] All 5 guards present (DELTA, S_MIN, isFinite, Math.max(0.0), Math.min)
- [x] `mvn compile -q` exits 0
- [x] Commit present in git history
