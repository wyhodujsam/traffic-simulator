---
phase: 21
plan: 02
subsystem: vision/component-library
tags: [stitching, expansion, roundabout, segment, t-intersection, signal]
requirements: [P21-STITCH-MERGE, P21-STITCH-REJECT, P21-STITCH-BRIDGE, P21-STITCH-ORPHAN]
key-files:
  created:
    - backend/src/test/java/com/trafficsimulator/service/MapComponentLibraryTest.java
    - backend/src/test/java/com/trafficsimulator/vision/components/ReverseRoadIdComponentTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/service/MapComponentLibrary.java
    - backend/src/main/java/com/trafficsimulator/vision/components/ExpansionContext.java
    - backend/src/main/java/com/trafficsimulator/vision/components/RoundaboutFourArm.java
    - backend/src/main/java/com/trafficsimulator/vision/components/SignalFourWay.java
    - backend/src/main/java/com/trafficsimulator/vision/components/TIntersection.java
    - backend/src/main/java/com/trafficsimulator/vision/components/StraightSegment.java
    - backend/src/test/java/com/trafficsimulator/vision/components/StraightSegmentExpansionTest.java
metrics:
  tests-before: 254 (1 pre-existing arch failure)
  tests-after: 265 (same 1 pre-existing arch failure)
  delta: +11 tests
---

# Phase 21 Plan 02: Stitching Algorithm Summary

Implemented `MapComponentLibrary.expand(components, connections)`: fuses coincident (≤5 px) component arm endpoints into shared INTERSECTION nodes, rewrites affected road endpoints, drops orphan spawn/despawn, and rejects non-coincident connections with an actionable "Insert a STRAIGHT_SEGMENT" message.

## Algorithm (as implemented)

1. Validate component ids (existing rule: `^[a-z][a-z0-9]*$`, no `in`/`out` substrings).
2. Each component runs `expand(ctx)`; in plan 21-02 each component additionally calls `ctx.registerArm(componentId, armName, entryNodeId, exitNodeId, worldPos)` for every emitted arm. Absent ring arms register nothing — `lookupArm` throws on reference.
3. For each `Connection(a, b)`:
   - Resolve both arms via `ctx.lookupArm(...)` (unknown reference → `ExpansionException`).
   - If `dist(posA, posB) > MERGE_TOLERANCE_PX (5.0)` → `ExpansionException` with the actionable message.
   - Otherwise: create a new INTERSECTION node `merged__<aComp>_<aArm>__<bComp>_<bArm>` at the midpoint, then in this strict order:
     a. Drop spawn/despawn for any road touching the dead nodes (must be done BEFORE rewriting endpoints — once endpoints are merged we can no longer detect which roads were affected).
     b. Rewrite all road `from`/`to` references from the 4 dead arm node ids to the merged id.
     c. Drop the 4 dead arm nodes.
     d. Add a new PRIORITY `IntersectionConfig` (size 24) for the merged node.
4. Defensive sweep: drop any `IntersectionConfig` whose `nodeId` is no longer referenced by a road.
5. Run `MapValidator.validate(...)` — non-empty errors raise `ExpansionException`.

## Key design notes

- **Merged-id collision safety**: ids look like `merged__rb1_east__rb2_west`. The closed set of arm names is `{north, east, south, west, start, end}` — none contain the substrings `in` or `out`. Component ids are validated to also exclude those substrings. This guarantees `IntersectionGeometry.reverseRoadId` (which does a naive `_in → _out` string replace) cannot accidentally corrupt a merged id.
- **StraightSegment arm record**: a segment is a single one-way road, so its `start` and `end` arms register the same node id as both entry and exit (the merge will replace whichever side is needed). This avoids special-casing in the stitcher.
- **Spawn/despawn drop ordering**: the spawn/despawn drop step runs BEFORE road endpoint rewriting because it identifies affected roads by checking whether `road.fromNodeId` or `road.toNodeId` is in the dead set — once those endpoints are rewritten to the merged id the connection is invisible.

## Deviations from PLAN.md

1. **`StraightSegment` road id rename — `r_main_in` → `r_main`** (Rule 1, bug found in 21-01 code).
   - **Why**: `IntersectionGeometry.reverseRoadId` does `id.replace("_in", "_out")`. `StraightSegment` emits a single one-way road, so it has no sibling `_out` road. The plan's must-have asserts "IntersectionGeometry.reverseRoadId works for every emitted road id in a stitched multi-component map" — naming the segment road `r_main_in` violated that contract because the engine would route vehicles toward a non-existent `seg1__r_main_out` for U-turns.
   - **Fix**: dropped the `_in` suffix; updated `StraightSegmentExpansionTest` accordingly.
2. **`IntersectionConfig.priority(...)` factory mentioned in PLAN pseudocode does not exist**. The class is `@Data @NoArgsConstructor`, so I built the config inline with setters.
3. **Engine's `IntersectionGeometry` is package-private**, so `ReverseRoadIdComponentTest` re-implements the same `_in → _out` string-replace contract (a one-line method) and asserts the result is present in the road set. If the engine's helper ever changes, this test must be updated in lockstep — documented in the test's class javadoc.

## Tests added (+11)

`MapComponentLibraryTest` (9 methods):
- `stitch_mergesCoincidentArms` — two roundabouts joined at a coincident arm endpoint produce one merged INTERSECTION; 4 orphan arm nodes removed; spawn/despawn drop from 8→6.
- `stitch_rejectsNonCoincidentArms` — 800 px apart, expects `ExpansionException` containing `"Insert a STRAIGHT_SEGMENT"`.
- `stitch_bridgesWithStraightSegment` — two roundabouts bridged by a 200 px segment with two `Connection`s; bridge road's both endpoints become merged ids; validator passes.
- `stitch_orphanArmsRemainBoundaries` — single roundabout, no connections; 4 ENTRY + 4 EXIT survive.
- `stitch_twoTIntersectionsMergeAtSharedEndpoint` — two T-intersections share an arm endpoint; result has 3 PRIORITY intersections (2 original + 1 merged); merged node lives at the midpoint.
- `stitch_rejectsComponentIdWithInSubstring` — component id `train1` rejected at expand time.
- `stitch_rejectsComponentIdWithOutSubstring` — component id `scout` rejected.
- `stitch_unknownArmReferenceFailsLoudly` — references to non-existent components surface as `ExpansionException`.
- `stitch_signalFourWayBridgedToRoundabout_passesValidator` — heterogeneous components fuse cleanly; signal phases keep referencing their original `_in` roads (whose `from` endpoint is now the merged node).

`ReverseRoadIdComponentTest` (2 methods):
- `reverseRoadId_flipsForEveryEmittedRoad_singleRoundabout`
- `reverseRoadId_flipsForEveryEmittedRoad_twoRoundaboutsBridged` — also asserts no node id contains `_in`/`_out` (so the substring replace cannot ever accidentally rewrite a node-named road id).

## Verification

```
mvn test
→ Tests run: 265, Failures: 1, Errors: 0, Skipped: 0
   FAIL: ArchitectureTest.no_generic_exceptions  (pre-existing — OsmPipelineService:128 throws RuntimeException)
```

Delta vs 254 baseline: **+11 tests**, all passing. The single failing test is the carry-over arch violation flagged by the user as out-of-scope.

## Self-Check: PASSED

- Source files exist:
  - `backend/src/main/java/com/trafficsimulator/service/MapComponentLibrary.java`
  - `backend/src/main/java/com/trafficsimulator/vision/components/ExpansionContext.java`
- Test files exist:
  - `backend/src/test/java/com/trafficsimulator/service/MapComponentLibraryTest.java`
  - `backend/src/test/java/com/trafficsimulator/vision/components/ReverseRoadIdComponentTest.java`
- All Phase 20 + Phase 21-01 tests still green.
