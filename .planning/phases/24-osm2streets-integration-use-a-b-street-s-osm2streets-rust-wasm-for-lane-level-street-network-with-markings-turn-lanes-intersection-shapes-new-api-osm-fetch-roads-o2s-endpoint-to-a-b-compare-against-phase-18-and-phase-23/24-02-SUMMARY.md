---
phase: 24-osm2streets-integration
plan: 02
subsystem: schema
tags: [schema, mapconfig, json, lanes, osm2streets, jackson]

# Dependency graph
requires:
  - phase: 18-osm-data-pipeline
    provides: OsmPipelineService + MapConfig as the byte-identity baseline
  - phase: 23-graphhopper-based-osm-parser
    provides: GraphHopperOsmService + MapConfig as the second byte-identity baseline
provides:
  - MapConfig.LaneConfig nested record (type, width, direction) for per-lane metadata
  - Optional RoadConfig.lanes field annotated @JsonInclude(NON_NULL) — byte-identical Phase 18/23 output
  - MapValidatorTest.java (new test class) with positive populated-lanes[] assertion
  - Phase 18/23 regression tests guarding "lanes stays null" contract
affects: [24-03, 24-04, 24-05, 24-06, 24-07]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Field-level @JsonInclude(NON_NULL) for opt-in schema additions without breaking on-wire compatibility"
    - "Lombok @Data + @NoArgsConstructor + @AllArgsConstructor on nested config DTOs for Jackson + inline mapper use"

key-files:
  created:
    - backend/src/test/java/com/trafficsimulator/config/MapValidatorTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/config/MapConfig.java
    - backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java
    - backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java

key-decisions:
  - "Used field-level @JsonInclude(NON_NULL) so Phase 18/23 JSON stays byte-identical (no 'lanes':null emitted)"
  - "Kept LaneConfig as a Lombok @Data class instead of a Java record for consistency with other MapConfig nested classes"
  - "Created MapValidatorTest.java (new file) since it did not previously exist — tracked as a deviation"

patterns-established:
  - "Optional schema fields: annotate with @JsonInclude(NON_NULL) at field level; null value → key omitted entirely"
  - "Phase-specific regression tests: when a new optional field is added, every legacy producer gets a 'stays null' assertion"

requirements-completed: []

# Metrics
duration: ~6 min
completed: 2026-04-23
---

# Phase 24 Plan 02: MapConfig Schema Extension (LaneConfig + RoadConfig.lanes) Summary

**Additive MapConfig schema extension: new `LaneConfig` nested class + optional `RoadConfig.lanes` field with `@JsonInclude(NON_NULL)`, guaranteeing byte-identical Phase 18/23 JSON output while providing the osm2streets-facing contract for Plan 24-04.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-04-23T13:30:48Z
- **Completed:** 2026-04-23T13:36:56Z
- **Tasks:** 2 (both `type=auto`, tdd-flagged but gated per plan's grep/compile verify for T1 and test-execution verify for T2)
- **Files modified:** 4 (1 main, 3 test — 1 new test class)

## Accomplishments
- Added `MapConfig.LaneConfig` nested Lombok `@Data` class with `type` (String), `width` (double), `direction` (String) fields
- Added `MapConfig.RoadConfig.lanes: List<LaneConfig>` field annotated `@JsonInclude(JsonInclude.Include.NON_NULL)` — Phase 18/23 serialized output is byte-for-byte identical (null field is omitted from JSON entirely)
- Confirmed MapValidator stays **zero-diff** (validator only looks at `laneCount`, `length`, `speedLimit`; new field is invisible to validation)
- Created `MapValidatorTest.java` (new file) with 2 tests asserting both the populated-lanes and null-lanes paths validate cleanly
- Added regression tests in `OsmPipelineServiceTest` and `GraphHopperOsmServiceTest` asserting `getLanes() == null` for every RoadConfig emitted

## Task Commits

Each task committed atomically:

1. **Task 1: Add LaneConfig record + optional lanes field to MapConfig.RoadConfig** — `79979cc` (feat)
2. **Task 2: Positive validation test + Phase 18/23 null-lanes regression assertions** — `9280b46` (test)

_Note: This SUMMARY commit is intentionally included in this plan's final commit, per orchestrator instructions. STATE.md / ROADMAP.md are explicitly NOT updated (parallel-executor constraint)._

## Files Created/Modified

- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — +23 lines, pure additions (new `LaneConfig` nested class + `RoadConfig.lanes` field + imports for `JsonInclude`/`AllArgsConstructor`)
- `backend/src/test/java/com/trafficsimulator/config/MapValidatorTest.java` — **new file**, 80 lines, 2 tests
- `backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` — +17 lines (`lanesFieldIsNullForPhase18`)
- `backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java` — +14 lines (`lanesFieldIsNullForPhase23`)

## Diff summary — RoadConfig extension

```diff
 public static class RoadConfig {
     private String id;
     private String name;
     private String fromNodeId;
     private String toNodeId;
     private double length;
     private double speedLimit;
     private int laneCount;
     private List<Integer> closedLanes;
     private double lateralOffset;
+
+    /** Phase 24: optional per-lane metadata from osm2streets. Null for Phase 18/23 output. */
+    @JsonInclude(JsonInclude.Include.NON_NULL)
+    private List<LaneConfig> lanes;
 }
```

## New nested class — LaneConfig

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public static class LaneConfig {
    @JsonProperty("type")      private String type;      // driving | parking | cycling | sidewalk
    @JsonProperty("width")     private double width;     // metres
    @JsonProperty("direction") private String direction; // forward | backward | both
}
```

## Jackson byte-identity evidence

- `@JsonInclude(JsonInclude.Include.NON_NULL)` at field level instructs Jackson to omit the `"lanes"` key entirely when the value is null (Jackson databind 2.17 semantics — field-level override of class-level default).
- Regression tests `lanesFieldIsNullForPhase18` and `lanesFieldIsNullForPhase23` assert that every `RoadConfig` returned by Phase 18/Phase 23 services has `getLanes() == null`. Combined with the `@JsonInclude(NON_NULL)` behaviour, this guarantees their serialized JSON contains no `"lanes"` key — bit-identical to pre-Phase-24 output.
- `MapValidator.java` diff: **0 lines** (verified via `git diff backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — empty output).

## Test count change

| | Before Plan 24-02 | After Plan 24-02 |
|---|---|---|
| MapValidatorTest | (file did not exist) | 2 tests |
| OsmPipelineServiceTest | 14 tests | 15 tests (+1 `lanesFieldIsNullForPhase18`) |
| GraphHopperOsmServiceTest | 7 tests | 8 tests (+1 `lanesFieldIsNullForPhase23`) |
| **Backend suite total** | **347 passed + 1 skipped** | **351 passed + 1 skipped** (+4) |

Plan predicted 350 (+3); actual is 351 (+4) — one additional test (`lanesField_nullPassesValidation`) was added in `MapValidatorTest` to lock the Phase 18/23 null-lanes-tolerant contract at the validator level too.

## Decisions Made

- **Field-level (not class-level) `@JsonInclude(NON_NULL)` on `lanes`.** Rationale: minimises blast radius — other nullable fields on RoadConfig (e.g. `closedLanes`) keep their current serialization behaviour unchanged. Matches RESEARCH §7 "MapConfig Schema Extension" guidance.
- **LaneConfig as Lombok `@Data` class (not Java `record`).** Rationale: every other nested class in `MapConfig` (`RoadConfig`, `NodeConfig`, `IntersectionConfig`, `SignalPhaseConfig`, `SpawnPointConfig`, `DespawnPointConfig`) uses `@Data` + `@NoArgsConstructor`. Adding a record would create two-style class soup. Added `@AllArgsConstructor` so the future Plan 24-04 mapper can instantiate inline with `new LaneConfig(type, width, direction)`.
- **Added a second positive MapValidatorTest (`lanesField_nullPassesValidation`).** Rationale: the plan requires a positive test for the populated case, but the validator's contract that null lanes still validates cleanly is exactly what Phase 18/23 rely on. One extra 8-line test cements that invariant with no cost.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created `MapValidatorTest.java` — file did not exist**
- **Found during:** Task 2 (assertion setup)
- **Issue:** Plan Task 2 instructs "Append to `MapValidatorTest.java`" but no such file existed in the repo. The `MapValidator` class's only prior test coverage came from one method in `MapLoaderScenarioTest#invalidMapThrowsWithDescriptiveError`, not a dedicated test class.
- **Fix:** Created `backend/src/test/java/com/trafficsimulator/config/MapValidatorTest.java` with a `buildMinimalMapConfig()` helper (2 nodes + 1 road — validator-clean) and the required `lanesField_populatedListPassesValidation` test. Also added a companion `lanesField_nullPassesValidation` test because it's 8 lines and directly documents the Phase 18/23 contract at the validator layer.
- **Files modified:** `backend/src/test/java/com/trafficsimulator/config/MapValidatorTest.java` (new file)
- **Verification:** Both tests pass; full suite 351 green.
- **Committed in:** `9280b46` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Zero scope creep — the deviation was required to satisfy the plan's own acceptance criteria. The extra 2nd positive test in MapValidatorTest is trivial (8 lines) and locks an invariant that future phases would depend on anyway.

## Issues Encountered

- **Worktree base mismatch at start.** The worktree branch (`worktree-agent-ac7e6933`) was initially rooted at `48c152c` (Phase 23 close commit) instead of the required `3f78819` (Plan 24 scaffold). Resolved per `<worktree_branch_check>` with `git reset --hard 3f788196dd0b739518b5024cc94f67860e6a7982`. This is the expected fresh-worktree workflow, not a real issue.
- No other issues.

## User Setup Required

None — this is a pure additive schema change, no external services or configuration involved.

## Next Phase Readiness

- **Plan 24-04 (Rust-side mapper emitting `lanes[]`)** — unblocked. The Java-side contract is now in place; 24-04 can populate `RoadConfig.lanes` with `new LaneConfig(type, width, direction)` instances directly.
- **Plans 24-03/24-05/24-06/24-07** — unaffected (they target Rust CLI, backend service, frontend button, Playwright spec; none of them touch the schema shape).
- **No blockers introduced.** Phase 18/23 output is byte-identical; MapValidator is byte-identical; frontend consumers read `laneCount` and are unaware of the new field.

## Self-Check

### Created files
- `backend/src/test/java/com/trafficsimulator/config/MapValidatorTest.java` — **FOUND**

### Modified files
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — **FOUND** (contains `public static class LaneConfig` and `private List<LaneConfig> lanes`)
- `backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` — **FOUND** (contains `lanesFieldIsNullForPhase18`)
- `backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java` — **FOUND** (contains `lanesFieldIsNullForPhase23`)

### Commit hashes present in `git log`
- `79979cc` — **FOUND** (`feat(24-02): add optional LaneConfig record + RoadConfig.lanes field`)
- `9280b46` — **FOUND** (`test(24-02): add lanes-field validation + Phase 18/23 null-lanes regression tests`)

### Constraint checks
- `git diff backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — **empty** (zero lines changed)
- `mvn test -pl backend` — **351 passed, 0 failures, 0 errors, 1 skipped**
- No edits to STATE.md, ROADMAP.md, tools/, backend/bin/, frontend/ — **confirmed** via `git log --stat 79979cc..HEAD`

## Self-Check: PASSED

---
*Phase: 24-osm2streets-integration*
*Completed: 2026-04-23*
