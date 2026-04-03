---
phase: 15-sonarqube-quality-fixes
plan: "02"
subsystem: backend
tags: [refactoring, cognitive-complexity, sonarqube, java]
dependency_graph:
  requires: []
  provides: [SQ-03, SQ-06]
  affects: [SnapshotBuilder, PhysicsEngine, TickEmitter, SimulationController]
tech_stack:
  added: []
  patterns: [method-extraction, private-record, orchestrator-method]
key_files:
  created: []
  modified:
    - backend/src/main/java/com/trafficsimulator/scheduler/SnapshotBuilder.java
    - backend/src/main/java/com/trafficsimulator/engine/PhysicsEngine.java
    - backend/src/main/java/com/trafficsimulator/scheduler/TickEmitter.java
    - backend/src/main/java/com/trafficsimulator/controller/SimulationController.java
decisions:
  - "Used private record VehicleObstacleCollection as lightweight data carrier for extracted collection method"
  - "Used private record LeaderInfo in PhysicsEngine for leader selection result (position/speed/length/present)"
  - "SimulationController used double[] array instead of a record for Point to avoid adding a new type for a simple two-value return"
  - "Extracted logTickMetrics from TickEmitter to keep emitTick() a clean orchestrator"
metrics:
  duration: "3 minutes"
  completed_date: "2026-04-03"
  tasks_completed: 2
  tasks_total: 2
  files_modified: 4
---

# Phase 15 Plan 02: Cognitive Complexity Reduction Summary

Reduced cognitive complexity across 4 backend files by extracting private helper methods, resolving 4 CRITICAL SonarQube violations (java:S3776).

## What Was Built

Method extraction refactoring across 4 Java classes, converting complex monolithic methods into clean orchestrators that delegate to focused private helpers. All 140 tests pass unchanged — zero behavior changes.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Refactor SnapshotBuilder.buildSnapshot() | b12488f | SnapshotBuilder.java |
| 2 | Refactor PhysicsEngine, TickEmitter, SimulationController | a414ce9 | PhysicsEngine.java, TickEmitter.java, SimulationController.java |

## Changes Made

### Task 1: SnapshotBuilder (SQ-03 — complexity 77 -> <=15)

`buildSnapshot()` was a 90-line method with triple-nested loops. Extracted:
- `VehicleObstacleCollection` private record — carries vehicle/obstacle collection results
- `collectVehiclesAndObstacles(RoadNetwork)` — iterates roads/lanes collecting VehicleDtos and ObstacleDtos
- `collectTrafficLights(RoadNetwork)` — iterates intersections building TrafficLightDtos
- `isBoxBlocked(Intersection, String, RoadNetwork)` — the triple-nested box-blocking detection loop
- `computeStats(VehicleObstacleCollection, IVehicleSpawner)` — avgSpeed/density/throughput calculation

`buildSnapshot()` is now a 15-line orchestrator calling these 3 methods.

### Task 2: PhysicsEngine (SQ-06 — complexity 37 -> <=15)

`tick()` had a leader selection block (lines 68-110) with nested if/else chains. Extracted:
- `LeaderInfo` private record — carries leader position/speed/length/present flag
- `findNearestObstacleAhead(List<Obstacle>, double)` — obstacle search loop
- `findEffectiveLeader(Vehicle, Vehicle, Obstacle, double)` — full leader selection + stop line override logic
- `applyGuardsAndIntegrate(Vehicle, double, double, double, LeaderInfo)` — NaN guard, Euler integration, speed/position clamping

### Task 2: TickEmitter (SQ-06 — complexity 20 -> <=15)

`emitTick()` had the full simulation pipeline inline. Extracted:
- `runSimulationPipeline(RoadNetwork, long)` — traffic lights, stop lines, spawn, physics sub-steps, lane changes, intersection transfers, despawn
- `logTickMetrics(long, long, SimulationStateDto)` — slow tick warning + periodic info logging

`emitTick()` is now a clean: lock -> drain -> tick/pipeline -> snapshot -> broadcast -> log -> unlock.

### Task 2: SimulationController (SQ-06 — complexity 16 -> <=15)

`getIntersections()` had inline road endpoint averaging loop. Extracted:
- `computeIntersectionCenter(Intersection, RoadNetwork)` — averages road endpoints to find center, falls back to ixtn.getCenterX/Y()
- `computeIntersectionSize(Intersection, RoadNetwork)` — returns explicit size from config or derives from max lane count

## Deviations from Plan

None — plan executed exactly as written.

## Known Stubs

None.

## Self-Check: PASSED

- SnapshotBuilder.java exists: FOUND
- PhysicsEngine.java exists: FOUND
- TickEmitter.java exists: FOUND
- SimulationController.java exists: FOUND
- Commit b12488f: FOUND
- Commit a414ce9: FOUND
- All 140 tests pass: VERIFIED
