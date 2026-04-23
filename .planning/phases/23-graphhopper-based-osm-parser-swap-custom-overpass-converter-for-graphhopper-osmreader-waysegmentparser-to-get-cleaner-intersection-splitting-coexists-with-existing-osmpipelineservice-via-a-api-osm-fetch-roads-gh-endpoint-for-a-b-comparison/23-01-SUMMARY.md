---
phase: 23-graphhopper-based-osm-parser
plan: 01
subsystem: osm-pipeline
tags: [refactor, shared-utils, osm, a-b-fairness, phase-18-regression-safe]

# Dependency graph
requires:
  - phase: 23
    plan: 00
    provides: GraphHopper 10.2 classpath + spike verdicts (Wave 0 complete)
  - phase: 18-osm-data-pipeline
    provides: OsmPipelineService (Overpass converter — public behaviour must remain byte-identical)
provides:
  - OsmConversionUtils public static utility with shared OSM→MapConfig helpers
  - Parameterised buildDefaultSignalPhases/buildRoadConfig accepting full (prefixed) node ids — lets Phase 23 reuse with "gh-" prefix
  - computeWayLength accepting List<double[]> (lat/lon pairs) — decoupled from Phase 18's private OsmNode schema so Phase 23 can feed GraphHopper coords directly
affects:
  - 23-02-service-wiring-graph-hopper (will inject OsmConversionUtils via static calls)
  - 23-03-graph-to-mapconfig (will reuse computeWayLength, lonToX, latToY, buildDefaultSignalPhases, buildRoadConfig for A/B fairness)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Static utility class for shared pure-function helpers (private ctor, public static members)"
    - "Thin private wrappers in the caller that adapt caller-specific types to the shared helper's generic schema (e.g. OsmNode map → List<double[]>)"
    - "Shared helpers take already-prefixed node ids (caller owns prefix policy) — removes the hardcoded 'osm-' assumption"

key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java
    - backend/src/test/java/com/trafficsimulator/service/OsmConversionUtilsTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java

key-decisions:
  - "buildRoadConfig + buildDefaultSignalPhases in utils take FULL (prefixed) node ids — lets Phase 23 pass 'gh-42' without leaking a prefix parameter through the API; Phase 18 keeps a thin private wrapper that prepends 'osm-'"
  - "computeWayLength in utils takes List<double[]> of {lat, lon} pairs, NOT the Phase-18-private OsmNode record; Phase 18 adapts internally, Phase 23 will adapt from GraphHopper's PillarInfo the same way"
  - "assembleMapConfig bbox id prefix ('osm-bbox-') is unchanged across converters — A/B identity is by coords, not converter origin"
  - "OsmPipelineServiceTest was NOT edited: the refactor is regression-safe by construction; public behaviour is byte-for-byte identical"

requirements-completed: [GH-REUSE-01, GH-REUSE-02]

# Metrics
duration: ~15min
completed: 2026-04-23
---

# Phase 23 Plan 01: Extract OsmConversionUtils Shared Helpers Summary

**Phase 18 helpers promoted to a shared `OsmConversionUtils` utility class so Phase 23's GraphHopper converter can reuse them with IDENTICAL constants — A/B comparison fairness guaranteed by construction; Phase 18 regression gate (`OsmPipelineServiceTest`, 14 tests) passes unchanged.**

## Performance

- **Duration:** ~15 min
- **Completed:** 2026-04-23
- **Tasks:** 2
- **Files created:** 2 (`OsmConversionUtils.java`, `OsmConversionUtilsTest.java`)
- **Files modified:** 1 (`OsmPipelineService.java` — delegation refactor)
- **Net diff:** +664 insertions (new class + tests), -217 / +46 in service (−171 net; the deletion dominates because the helpers moved out)

## Accomplishments

- Created `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` as a pure-function utility holding every projection/lane/signal/endpoint helper that both converters need. Private constructor, all members `public static`. 308 lines, no Spring DI, no state.
- Added 28 direct unit tests (`OsmConversionUtilsTest`) covering each promoted helper: projection boundaries + midpoint, haversine identity + 1° equator, computeWayLength empty/two-point/three-point, all `speedLimitForHighway` branches, every `laneCountForWay` code path (explicit, clamped, non-numeric fallback, default), `buildRoadConfig` field mapping for both `osm-` and `gh-` prefixes, `buildDefaultSignalPhases` structure/durations/prefix-agnostic, `collectSpawnPoints`/`collectDespawnPoints` per-lane semantics, `assembleMapConfig` bbox id format + the "empty list → null" contract, and the utility-class shape guard (private constructor via reflection).
- Refactored `OsmPipelineService.java` to delegate every shared helper to `OsmConversionUtils`. Removed all 8 duplicated constants (`CANVAS_W/H/PADDING`, `MIN/MAX_LANE_COUNT`, `MIN_ROAD_LENGTH_M`, `DEFAULT_SIGNAL_PHASE_MS`, `LANE_WIDTH_BACKEND`) and 7 duplicated methods (`lonToX`, `latToY`, `haversineMeters`, `speedLimitForHighway`, `laneCountForWay`, `buildDefaultSignalPhases`, `collectSpawnPoints`, `collectDespawnPoints`, `assembleMapConfig`).
- Phase 18's public API (`fetchAndConvert`, package-private `convertOsmToMapConfig`) is untouched. The existing `OsmPipelineServiceTest` (14 tests) passes with zero test edits — the behavioural-equivalence guarantee.
- Full backend suite: **338 / 338 tests pass** (310 baseline + 28 new). No Phase 18 test flaked, regressed, or was modified.

## Task Commits

1. **Task 1: Create OsmConversionUtils + unit tests** — `ddf825f` (feat)
   - Files: `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` (new, 308 lines), `backend/src/test/java/com/trafficsimulator/service/OsmConversionUtilsTest.java` (new, 356 lines).
2. **Task 2: Retrofit OsmPipelineService to delegate to OsmConversionUtils** — `c6ae995` (refactor)
   - Files: `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` (-217 / +46 lines).

## Files Created / Modified

### Created

- `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` — `public final class` with private constructor; 8 public constants + 11 public static methods (including `computeWayLength` which Plan 23-03 will reuse for A/B fairness).
- `backend/src/test/java/com/trafficsimulator/service/OsmConversionUtilsTest.java` — 28 tests, one cluster per promoted helper, plus a reflection test asserting the private constructor.

### Modified

- `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — constants deleted, helpers deleted, call sites replaced with `OsmConversionUtils.xxx(...)`. Two thin private wrappers kept to preserve Phase 18 call shapes without leaking Phase-18-specific types into the shared utility: `buildRoadConfig(String, long, long, ...)` prepends `OSM_ID_PREFIX`, and `computeWayLength(List<Long>, Map<Long, OsmNode>)` adapts the private `OsmNode` map into the `List<double[]>` schema. Also introduced an `OSM_ID_PREFIX = "osm-"` constant to deduplicate the prefix literal (SonarQube java:S1192).

## Helper-promotion Mapping

Source → target (line numbers at the tip of Wave-0 / `2ad0e87`):

| Helper | OsmPipelineService.java (old) | OsmConversionUtils.java (new) |
|---|---|---|
| `CANVAS_W` | line 35 | line 32 |
| `CANVAS_H` | line 36 | line 35 |
| `CANVAS_PADDING` | line 37 | line 38 |
| `MIN_LANE_COUNT` | line 40 | line 41 |
| `MAX_LANE_COUNT` | line 41 | line 44 |
| `MIN_ROAD_LENGTH_M` | line 44 | line 47 |
| `DEFAULT_SIGNAL_PHASE_MS` | line 47 | line 50 |
| `LANE_WIDTH_BACKEND` | line 330 | line 56 |
| `lonToX` | lines 684–687 | lines 70–73 |
| `latToY` | lines 689–692 | lines 79–82 |
| `haversineMeters` | lines 671–682 | lines 85–96 |
| `computeWayLength` | lines 565–575 (inline lat/lon; kept as private adapter) | lines 104–117 (`List<double[]>` schema) |
| `speedLimitForHighway` | lines 596–607 | lines 127–138 |
| `laneCountForWay` | lines 609–624 | lines 145–159 |
| `buildRoadConfig` | lines 577–594 (hardcoded `"osm-"` prefix) | lines 165–182 (full node ids) |
| `buildDefaultSignalPhases` | lines 626–665 (takes `long nodeId`, prepends `"osm-"` internally) | lines 192–227 (takes `String fullNodeId`) |
| `collectSpawnPoints` | lines 473–488 | lines 233–248 |
| `collectDespawnPoints` | lines 490–505 | lines 254–269 |
| `assembleMapConfig` | lines 507–537 | lines 277–307 |

## Decisions Made

1. **`buildRoadConfig` and `buildDefaultSignalPhases` in `OsmConversionUtils` accept FULLY-formed node ids, not `long + prefix`.** Phase 18 keeps a thin private `buildRoadConfig(String, long, long, ...)` wrapper that prepends `"osm-"` before delegating; Phase 23 will call the utility with `"gh-" + id` directly. Rationale: RESEARCH.md §11 wants prefix-neutrality at the utility boundary. Smaller diff than inlining prefixes at every call site in Phase 18.

2. **`computeWayLength` in `OsmConversionUtils` takes `List<double[]>` (each `{lat, lon}`), NOT the Phase-18-private `OsmNode` record.** Phase 18 adapts via a private method that maps `nodeIds → List<double[]>` using its `OsmNode` map; Phase 23 will feed GraphHopper's `PillarInfo` lat/lon the same way. This keeps the utility schema-free (no types from either converter leak into it) — essential for A/B fairness and Phase 24 (osm2streets) extensibility.

3. **`assembleMapConfig` keeps the `"osm-bbox-"` id prefix.** A/B identity is by the bbox coordinates, not the converter; the prefix documents the pipeline family. If Phase 24 ever needs per-converter ids it can override assembly itself — not a blocker now.

4. **No test edits to `OsmPipelineServiceTest`.** The refactor is regression-safe by construction: the Phase 18 public behaviour (return value of `fetchAndConvert` / `convertOsmToMapConfig`) is byte-for-byte identical because every delegation preserves the input-output contract. 14 existing tests pass with zero changes — the gate is met.

5. **SLF4J Logger injected manually into the utility** (`LoggerFactory.getLogger(OsmConversionUtils.class)`) rather than Lombok `@Slf4j`, because the utility is NOT a Spring component and isn't annotation-scanned the same way. Single line of boilerplate, avoids cross-cutting Lombok surprises on a static-only class.

6. **Introduced `OSM_ID_PREFIX = "osm-"` constant in `OsmPipelineService`** to remove the "osm-" string literal that otherwise appears in 8+ places (SonarQube java:S1192 — forbid ≥3 duplications).

## Deviations from Plan

### Auto-fixed Issues (Rules 1–3)

**1. [Rule 2 — Critical functionality] Promote `computeWayLength` to `OsmConversionUtils`**

- **Found during:** Task 1 setup.
- **Issue:** The PLAN.md task list (Tasks 1 and 2) did not enumerate `computeWayLength` among the helpers to promote, but the prompt's `<critical_constraints>` block explicitly calls it out as "especially critical — Plan 23-03 will reuse it for A/B fairness." Leaving it Phase-18-private would force Plan 23-03 to either duplicate the sum-of-segments logic (risking drift) or reach into Phase 18 internals.
- **Fix:** Added `computeWayLength(List<double[]>)` to `OsmConversionUtils`; changed Phase 18's private `computeWayLength(List<Long>, Map<Long, OsmNode>)` into a thin adapter that maps via the existing `OsmNode` lookup and delegates. Three direct tests added (empty, two-point 1°, three-point sum).
- **Files modified:** `OsmConversionUtils.java` (+13 lines, +1 method), `OsmPipelineService.java` (adapter in place of the original inline loop), `OsmConversionUtilsTest.java` (3 tests).
- **Commit:** `ddf825f` (Task 1) + `c6ae995` (Task 2 adapter rewrite).

**2. [Rule 2 — Convention compliance] Extract `OSM_ID_PREFIX` constant**

- **Found during:** Task 2 — the "osm-" string literal was about to appear 10+ times after I deleted `buildRoadConfig`/`collectSpawnPoints`/`collectDespawnPoints`/`buildDefaultSignalPhases` (which had centralised prior literal uses).
- **Issue:** `.planning/CONVENTIONS.md` (SonarQube java:S1192) forbids ≥3 duplications of a string literal.
- **Fix:** Added `private static final String OSM_ID_PREFIX = "osm-"` and used it at every call site.
- **Commit:** `c6ae995` (Task 2).

### Plan deviations (beyond auto-fixes)

- **`./mvnw` → `mvn`.** The repo has no Maven wrapper (confirmed by `ls backend/mvnw`). All verification commands used `mvn` directly. Plan 23-00 already documented this deviation.
- **Plan did not specify Logger for the utility.** Used SLF4J directly (documented in Decision 5).

## Authentication Gates

None.

## User Setup Required

None — refactor-only; no new configuration, env vars, or external services.

## Verification Results

- `mvn compile` → BUILD SUCCESS (post Task 1 and post Task 2).
- `mvn test -Dtest=OsmConversionUtilsTest` → 28 tests / 0 failures / 0 errors / 0 skipped.
- `mvn test -Dtest=OsmPipelineServiceTest` → 14 tests / 0 failures / 0 errors / 0 skipped (regression gate — Phase 18 behaviour preserved).
- `mvn test -Dtest='OsmPipelineServiceTest,OsmControllerTest'` → 17 unit/MVC tests + 24 Cucumber scenarios, BUILD SUCCESS.
- `mvn test` (full suite) → **338 / 338 tests pass**. Baseline (post-Wave-0) was 310 (308 Phase-22.1 + 2 Wave-0 spike); delta +28 = OsmConversionUtilsTest count, matches exactly.
- `grep -c "private static final double CANVAS_W" backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` → 0 (constants moved).
- `grep -c "private.*speedLimitForHighway" backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` → 0 (method moved).
- `grep -c "OsmConversionUtils\." backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` → 12 delegations (≥8 required).
- `git diff HEAD~2 HEAD -- backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` → empty (zero edits to the regression-gate test).

## Next Phase Readiness

- Plan 23-02 (GraphHopperOsmService skeleton + controller wiring) — ready. Can call `OsmConversionUtils.lonToX(...)`, `OsmConversionUtils.latToY(...)` at will; constants available via `OsmConversionUtils.CANVAS_W` etc.
- Plan 23-03 (GraphHopper graph → MapConfig) — ready. Can call `OsmConversionUtils.buildDefaultSignalPhases("gh-" + towerNodeId, roads)`, `OsmConversionUtils.buildRoadConfig("gh-" + wayId + "-fwd", "gh-" + from, "gh-" + to, ...)`, `OsmConversionUtils.computeWayLength(pillarLatLons)`, `OsmConversionUtils.assembleMapConfig(bbox, nodes, roads, intersections, spawns, despawns)`. A/B fairness is now guaranteed by shared code, not by discipline.

## Self-Check: PASSED

- `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` — FOUND (308 lines; ≥ 120 min_lines requirement).
- `backend/src/test/java/com/trafficsimulator/service/OsmConversionUtilsTest.java` — FOUND (356 lines; ≥ 60 min_lines requirement).
- `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — MODIFIED (delegation in place; 12 `OsmConversionUtils.` references).
- Commit `ddf825f` (Task 1) — FOUND in git log.
- Commit `c6ae995` (Task 2) — FOUND in git log.
- Phase 18 regression gate `OsmPipelineServiceTest` — 14 tests pass, zero test-file edits.
- Full backend suite — 338 tests pass, 0 failures, 0 errors, 0 skipped.
- No edits to STATE.md, ROADMAP.md, `frontend/` (per parallel_execution directive).

---
*Phase: 23-graphhopper-based-osm-parser*
*Plan: 01*
*Completed: 2026-04-23*
