---
phase: 16-combined-scenario-roundabout-signal-merge-loop
verified: 2026-04-08T12:00:00Z
status: passed
score: 6/6 must-haves verified
gaps: []
human_verification:
  - test: "Load combined-loop scenario from frontend scenario selector and observe simulation"
    expected: "Vehicles spawn from highway, ramp, north, and east entry points; pass through roundabout, signal, and optionally circulate via the loop back to the roundabout south node; vehicles despawn at north-out and east-out exits"
    why_human: "Vehicle routing decisions (probabilistic loop vs despawn) cannot be verified without running the simulation; also verifies the scenario name 'Combined Loop' appears in the frontend UI dropdown"
---

# Phase 16: Combined Scenario — Roundabout + Signal + Merge + Loop — Verification Report

**Phase Goal:** Create a complex multi-feature scenario JSON map combining a roundabout, signalized intersection, highway merge, and a loop road — vehicles circulate on the loop or despawn off-screen
**Verified:** 2026-04-08
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | combined-loop.json loads without MapValidator errors | VERIFIED | Python validator passes; all node/road/intersection/spawnPoint/despawnPoint ID references resolve; signal greenRoadIds resolve; MapValidator logic confirmed in source |
| 2 | All 3 intersection types (ROUNDABOUT, SIGNAL, PRIORITY) are present in the loaded network | VERIFIED | JSON contains 4x ROUNDABOUT (n_ring_n/w/s/e), 1x SIGNAL (n_signal), 3x PRIORITY (n_merge, n_loop_se, n_loop_sw); test asserts .contains("ROUNDABOUT", "SIGNAL", "PRIORITY") |
| 3 | Signal intersection has 6-phase traffic light cycle wired | VERIFIED | n_signal signalPhases array has 6 entries (GREEN 25s, YELLOW 3s, ALL_RED 2s x2); MapLoader.buildTrafficLights() wires them into TrafficLight object; test asserts getPhases().hasSize(6) |
| 4 | Loop topology exists — roads form a cycle back to the roundabout | VERIFIED | r_sig_to_loop: n_signal -> n_loop_se; r_loop_bottom: n_loop_se -> n_loop_sw; r_loop_to_rndbt: n_loop_sw -> n_ring_s (ROUNDABOUT node) — confirmed by Python trace |
| 5 | Vehicles spawn at entry points and despawn at exit points | VERIFIED | 5 spawnPoints on r_hwy_in (lanes 0+1), r_ramp_in, r_north_in, r_east_in; 2 despawnPoints on r_north_out (pos 150.0) and r_east_out (pos 280.0) at road ends |
| 6 | Scenario appears in GET /api/maps endpoint list | VERIFIED | SimulationController.listMaps() scans classpath:maps/*.json; combined-loop.json is present in target/classes/maps/ confirming classpath presence; endpoint reads id/name/description fields which are all populated |

**Score:** 6/6 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/resources/maps/combined-loop.json` | Combined scenario map definition | VERIFIED | Exists, 15 roads, 14 nodes, 8 intersections, 5 spawnPoints, 2 despawnPoints, id="combined-loop" |
| `backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java` | Scenario load test for combined-loop | VERIFIED | Contains loadsCombinedLoop() test method at line 101 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| combined-loop.json | MapLoader | classpath:maps/combined-loop.json auto-discovery | WIRED | File present in src/main/resources/maps/ and compiled to target/classes/maps/; MapLoader.loadFromClasspath("maps/combined-loop.json") uses getClassLoader().getResourceAsStream() which resolves from classpath |
| MapLoaderScenarioTest | combined-loop.json | loadFromClasspath("maps/combined-loop.json") | WIRED | Test line 102 calls mapLoader.loadFromClasspath("maps/combined-loop.json") exactly as specified in plan interface |

### Data-Flow Trace (Level 4)

Not applicable — this phase produces a JSON data file and a test. No dynamic-data-rendering components were created.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| JSON parses and all ID references resolve | Python reference validator | 14 nodes, 15 roads, 8 intersections, 5 spawnPoints, 2 despawnPoints, ALL CHECKS PASSED | PASS |
| Loop topology connects back to roundabout | Python trace of loop roads | r_loop_to_rndbt lands at n_ring_s which is ROUNDABOUT: True | PASS |
| Signal has 6 phases | Python count of signalPhases | Signal n_signal has 6 phases | PASS |
| All 3 intersection type strings present | Python type list extraction | ['PRIORITY', 'ROUNDABOUT', 'ROUNDABOUT', 'ROUNDABOUT', 'ROUNDABOUT', 'SIGNAL', 'PRIORITY', 'PRIORITY'] | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|---------|
| MAP-01 | 16-01-PLAN.md | Complex multi-feature capstone scenario map | SATISFIED | combined-loop.json implements all required features; referenced in ROADMAP.md Phase 16; NOTE: MAP-01 is not defined in REQUIREMENTS.md — it exists only in ROADMAP.md. This is a pre-existing structural gap in project planning artifacts, not a phase 16 failure. |

**Orphaned requirements check:** No additional requirement IDs are mapped to Phase 16 in REQUIREMENTS.md (the file contains no MAP-* identifiers at all).

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| None | — | — | — | — |

The JSON map file contains no stubs, placeholders, or TODO markers. The test file contains a complete, substantive test method with 9 distinct assertions.

**Plan road-count discrepancy (INFO):** The PLAN header stated "Roads (19 total)" but the plan body enumerated exactly 15 distinct roads. The executor correctly built 15 roads matching the plan body and updated the test assertion accordingly. This was an authoring error in the PLAN document, not a code defect. The SUMMARY documents this as an auto-fixed deviation.

### Human Verification Required

#### 1. Frontend scenario selection and simulation run

**Test:** Start the backend and frontend. Open the scenario selector in the UI. Select "Combined Loop — Roundabout + Signal + Merge". Start the simulation.
**Expected:** Vehicles spawn from three highway entry points and the east approach. They pass through the priority merge, circulate through the 4-node roundabout, reach the signalized intersection (where traffic alternates between r_rndbt_to_sig and r_east_in on a ~60s cycle), and either despawn at north/east exits or continue down the loop back to the roundabout south arm.
**Why human:** Vehicle routing at non-despawn intersections (whether vehicles take the loop vs a different path) is runtime probabilistic behavior. The frontend UI scenario selector label also requires visual confirmation.

### Gaps Summary

No gaps. All 6 observable truths are verified against the codebase. Both artifacts exist and are fully implemented (not stubs). Key links are wired. The only item requiring human verification is the runtime simulation behavior and frontend UI rendering, which cannot be verified statically.

The plan-versus-implementation road count difference (19 vs 15) was correctly handled by the executor: the JSON has 15 roads matching the plan body description, the test asserts 15, and the SUMMARY documents the discrepancy. The implemented map is self-consistent.

---

_Verified: 2026-04-08_
_Verifier: Claude (gsd-verifier)_
