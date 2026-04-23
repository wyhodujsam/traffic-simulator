---
phase: 22
plan: 01
subsystem: vision-components
tags: [component-library, viaduct, highway-exit-ramp, sealed-interface]
requires: [ComponentSpec sealed interface, ExpansionContext, MapComponentLibrary]
provides: [Viaduct record, HighwayExitRamp record, extended permits clause]
affects: [ComponentSpec.java]
tech-stack:
  added: []
  patterns: [sealed interface permits, record-based ComponentSpec, _in/_out road-id contract]
key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/vision/components/Viaduct.java
    - backend/src/main/java/com/trafficsimulator/vision/components/HighwayExitRamp.java
    - backend/src/test/java/com/trafficsimulator/vision/components/ViaductExpansionTest.java
    - backend/src/test/java/com/trafficsimulator/vision/components/HighwayExitRampExpansionTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/vision/components/ComponentSpec.java
    - backend/src/test/java/com/trafficsimulator/vision/components/ReverseRoadIdComponentTest.java
decisions:
  - "Viaduct: 4 arms, 4 one-way roads (2 per through-pair), no shared crossing node, no IntersectionConfig"
  - "HighwayExitRamp: 3 arms (main_in, main_out, ramp_out) with one PRIORITY intersection at centre, lane counts 2/2/1"
  - "HighwayExitRamp node-id nicknames (mi/mo/rmp) to avoid _in/_out substrings in node ids — road ids keep CONTEXT §3 convention"
metrics:
  tests_added: 13
  duration_minutes: ~20
  completed: 2026-04-13
---

# Phase 22 Plan 01: Viaduct + HighwayExitRamp Component Records — Summary

Two new `ComponentSpec` records added to the Phase 21 catalogue (4 → 6 types) so Claude vision can identify viaducts and highway exit ramps on OSM imagery. Purely additive; all existing Phase 21 records untouched.

## Record Contracts

### Viaduct (`Viaduct(String id, Point2D.Double center, double rotationDeg)`)
- Arms: fixed `north, east, south, west` at angles 270/0/90/180 (+ rotationDeg).
- APPROACH_LEN = 200.0, APPROACH_SPEED = 13.9.
- Two through-road pairs, each emitted as two one-way roads of length `2 * APPROACH_LEN`:
  - Lower (south ↔ north): `r_south_in` (south ENTRY → north EXIT), `r_south_out` (north ENTRY → south EXIT).
  - Upper (west ↔ east):   `r_west_in`  (west  ENTRY → east  EXIT), `r_west_out`  (east  ENTRY → west  EXIT).
- 8 nodes (4 ENTRY + 4 EXIT), 0 INTERSECTION nodes, 0 IntersectionConfig — the crossing has no junction control (physics engine handles "two roads passing near each other" correctly).
- Arm registration: `(entry = n_<arm>, exit = n_<arm>_exit)` per arm.

### HighwayExitRamp (`HighwayExitRamp(String id, Point2D.Double center, double rotationDeg)`)
- Arms: fixed `main_in` (0°), `main_out` (180°), `ramp_out` (225°).
- APPROACH_LEN = 240.0, APPROACH_SPEED = 22.2, INTERSECTION_SIZE = 32.0.
- 7 nodes (1 centre INTERSECTION + 3 ENTRY + 3 EXIT); 3 roads; 1 PRIORITY IntersectionConfig.
- Lane counts: main_in=2, main_out=2, ramp_out=1.
- Roads: `r_main_in` (main_in ENTRY → centre), `r_main_out` (centre → main_out EXIT), `r_ramp_out` (centre → ramp_out EXIT).

## Deviations from Plan

### [Rule 1 — Bug] Node-id naming adjustment for HighwayExitRamp

**Found during:** Task 3 (ReverseRoadIdComponentTest extension).

**Issue:** Plan instructed arm names `main_in`/`main_out`/`ramp_out`. Following TIntersection's node-naming pattern would emit nodes like `hx1__n_main_in`, `hx1__n_main_in_exit` — node ids containing the `_in`/`_out` substring. This violates the `IntersectionGeometry.reverseRoadId` contract (naive `_in → _out` string replace on a road id would collide with/corrupt node references).

**Fix:** Decoupled node naming from arm logical names. Introduced `ARM_NODE_NICK` map (`main_in → mi`, `main_out → mo`, `ramp_out → rmp`) so node ids become `n_mi`, `n_mi_exit`, `n_mo`, `n_mo_exit`, `n_rmp`, `n_rmp_exit` — all free of `_in`/`_out` substrings. Road ids remain `r_main_in`, `r_main_out`, `r_ramp_out` per 22-CONTEXT §3. Arm registration still uses logical names for external callers (stitcher).

**Files modified:** `HighwayExitRamp.java` (added `ARM_NODE_NICK` + switched node-id construction).

### [Rule 3 — Blocking] Stale stash conflict noted

While verifying that `ComponentVisionServiceTest#analyzeImageBytes_disconnectedArms_throwsParseException` was pre-existing, `git stash` briefly restored an older stash (c2e4d59, Phase 21 lombok/ROADMAP work) which reverted the `ComponentSpec.permits` edit. Dropped that stash; re-applied the permits update. No code lost.

## Test Count Delta

- New tests added: **13** (ViaductExpansionTest ×5, HighwayExitRampExpansionTest ×5, ReverseRoadIdComponentTest new methods ×3).
- Full backend suite: **299 tests, 2 pre-existing failures** (both unrelated to Phase 22):
  - `ArchitectureTest.no_generic_exceptions` — Phase-21 carry-over documented in 22-CONTEXT §Success Criteria §4.
  - `ComponentVisionServiceTest.analyzeImageBytes_disconnectedArms_throwsParseException` — confirmed pre-existing on `fix/overpass-fallback` branch (reproduced by stashing Phase 22 files and running against bare branch state).
- All 13 new tests green. All previously-green tests still green.

## Authentication Gates

None.

## Known Stubs

None.

## Self-Check

Files verified present; commit hash recorded in the atomic commit below.
