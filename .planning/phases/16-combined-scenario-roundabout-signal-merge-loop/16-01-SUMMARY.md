---
phase: 16-combined-scenario-roundabout-signal-merge-loop
plan: "01"
subsystem: maps/testing
tags: [scenario, json-map, roundabout, signal, merge, loop]
dependency_graph:
  requires: []
  provides: [combined-loop.json, MapLoaderScenarioTest.loadsCombinedLoop]
  affects: [GET /api/maps endpoint, MapLoaderScenarioTest]
tech_stack:
  added: []
  patterns: [JSON map authoring, MapLoader classpath auto-discovery]
key_files:
  created:
    - backend/src/main/resources/maps/combined-loop.json
  modified:
    - backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java
decisions:
  - "Used 15 roads (not 19 as stated in plan header — plan body listed exactly 15 roads; '19 total' in the header was a counting error)"
metrics:
  duration_minutes: 2
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 1
  completed_date: "2026-04-08"
---

# Phase 16 Plan 01: Combined Loop Scenario Map — Summary

**One-liner:** Combined-loop.json capstone scenario with 4-arm roundabout, 6-phase signal, highway merge, and loop road in a single 14-node/15-road JSON map.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Create combined-loop.json scenario map | c3118a3 | backend/src/main/resources/maps/combined-loop.json |
| 2 | Add MapLoaderScenarioTest for combined-loop | b424589 | backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java |

## What Was Built

**combined-loop.json** — a capstone scenario that exercises all three intersection types in one map:

- **14 nodes**: 4 ENTRY (highway, ramp, north, east), 2 EXIT (north, east), 8 INTERSECTION (merge, 4 ring nodes, signal, 2 loop bends)
- **15 roads**: highway approach (2-lane), on-ramp, merge-to-roundabout (2-lane), north approach/departure, 4 ring roads, roundabout-to-signal (2-lane), east approach/departure, 3 loop roads
- **8 intersections**: 4 ROUNDABOUT (ring nodes), 1 SIGNAL (6-phase cycle), 1 PRIORITY (highway merge), 2 PRIORITY (loop bends for pass-through)
- **5 spawn points**: highway lanes 0+1, ramp, north approach, east approach
- **2 despawn points**: north departure end, east departure end
- **Loop topology**: signal -> n_loop_se -> n_loop_sw -> n_ring_s — vehicles can circulate back into the roundabout probabilistically

**MapLoaderScenarioTest.loadsCombinedLoop()** — load test verifying:
- 15 roads, 8 intersections
- All 3 intersection types present (ROUNDABOUT, SIGNAL, PRIORITY)
- Signal node has TrafficLight with 6 phases
- Loop roads exist: r_sig_to_loop, r_loop_bottom, r_loop_to_rndbt
- 5 spawn points, 2 despawn points
- defaultSpawnRate = 0.8

## Verification Results

- Python reference validator: PASSED (all 14 nodes, 15 roads, 8 intersections, ID references valid)
- `mvn test -Dtest=MapLoaderScenarioTest`: 7/7 tests pass (6 existing + 1 new) — BUILD SUCCESS
- `mvn test` full suite: 160/161 tests pass; 1 pre-existing BDD failure ("Vehicles stop at red light") unrelated to this plan — not caused by these changes

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Data Inconsistency] Corrected road count from 19 to 15**
- **Found during:** Task 1 verification
- **Issue:** Plan heading said "Roads (19 total)" but the road list in the plan body contained exactly 15 distinct roads. No additional roads were described anywhere in the plan. The verify block also asserted `len(m['roads']) == 19`.
- **Fix:** Created the JSON with 15 roads matching the plan's actual road list. Updated the test assertion in Task 2 to `hasSize(15)` to match reality.
- **Files modified:** combined-loop.json (created with 15 roads), MapLoaderScenarioTest.java (assertion uses 15)
- **Commits:** c3118a3, b424589

## Known Stubs

None. The map is fully defined with all required structure.

## Self-Check: PASSED

- [x] `backend/src/main/resources/maps/combined-loop.json` exists
- [x] `backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java` contains `loadsCombinedLoop`
- [x] Commit c3118a3 exists: `git log --oneline --all | grep c3118a3`
- [x] Commit b424589 exists: `git log --oneline --all | grep b424589`
- [x] MapLoaderScenarioTest: 7/7 pass
