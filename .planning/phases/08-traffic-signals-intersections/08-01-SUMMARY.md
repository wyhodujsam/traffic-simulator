# Plan 08-01 Summary: Traffic Light Model & Intersection Wiring

## Status: COMPLETE

## What was done

### Task 1: TrafficLightPhase model
- Created `TrafficLightPhase.java` with `greenRoadIds` (Set<String>), `durationMs` (long), `type` (PhaseType enum: GREEN, YELLOW, ALL_RED)
- Lombok @Data/@Builder annotations

### Task 2: TrafficLight model
- Created `TrafficLight.java` with phase cycling logic
- `tick(dtSeconds)` advances elapsed time and wraps phase index
- `isGreen(roadId)`, `isYellow(roadId)`, `getSignalState(roadId)` query methods
- `replacePhases()` for runtime reconfiguration

### Task 3: Extend Intersection model
- Added `inboundRoadIds`, `outboundRoadIds` (both @Builder.Default ArrayList)
- Added `trafficLight` field (nullable)
- Removed Phase 8 placeholder comment
- Retained `connectedRoadIds` for backward compatibility

### Task 4: Extend MapConfig
- Added `SignalPhaseConfig` inner class with `greenRoadIds`, `durationMs`, `type`
- Added `signalPhases` field to `IntersectionConfig`

### Task 5: Update MapValidator
- SIGNAL intersections require non-null, non-empty signalPhases
- greenRoadIds must reference existing road IDs
- durationMs must be > 0
- Orphan intersection nodes (no roads connecting) produce errors
- Existing straight-road.json continues to pass validation

### Task 6: Update MapLoader
- Step 4b: Wire inbound/outbound road IDs based on fromNodeId/toNodeId
- Step 4c: Build TrafficLight objects for SIGNAL intersections from JSON config

### Task 7: TrafficLightController
- Spring @Component that ticks all SIGNAL intersections
- Logs phase changes at DEBUG level

### Task 8: four-way-signal.json
- 9 nodes, 8 roads (4 inbound, 4 outbound), 1 SIGNAL intersection
- 6 signal phases (NS green, NS yellow, all-red, EW green, EW yellow, all-red)
- 4 spawn points, 4 despawn points, 50 km/h speed limit

## Verification
- `mvn compile -q` — SUCCESS
- `mvn test` — 65 tests, 0 failures
- All acceptance criteria met
