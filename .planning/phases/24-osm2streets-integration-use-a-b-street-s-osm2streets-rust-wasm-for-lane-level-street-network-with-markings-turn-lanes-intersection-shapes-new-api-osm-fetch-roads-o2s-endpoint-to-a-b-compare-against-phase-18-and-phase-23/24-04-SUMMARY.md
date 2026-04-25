---
phase: 24-osm2streets-integration
plan: 04
subsystem: backend-service
tags: [mapper, streetnetwork, osm2streets, translation, overpass, lanes]

# Dependency graph
requires:
  - "24-01: 6 canned StreetNetwork JSON fixtures + observed field shape (tuple-array BTreeMap, lt/dir/width, scaled-i32, tagged LaneType variants)"
  - "24-02: MapConfig.LaneConfig nested record + optional RoadConfig.lanes field"
  - "24-03: Osm2StreetsService subprocess spine + executeCli deadlock-safe helper"
provides:
  - "OverpassXmlFetcher @Service — shared Overpass XML fetch helper with fetchXmlBytes(bbox) + fetchXmlToTempFile(bbox, tempDir)"
  - "StreetNetworkMapper (package-private final class) — static map(JsonNode, BboxRequest) -> MapConfig"
  - "Osm2StreetsService.fetchAndConvert wired end-to-end (Overpass fetch -> executeCli -> JSON parse -> mapper -> MapValidator -> MapConfig)"
  - "9 StreetNetworkMapperTest unit tests covering all 6 canned fixtures + lane-count clamp + MVP-type filter + tagged-LaneType variant"
  - "5 Osm2StreetsServiceTest end-to-end tests (stub OverpassXmlFetcher + overridden executeCli — no subprocess, no network)"
affects:
  - "24-05 (controller) — Osm2StreetsService.fetchAndConvert is now usable from REST endpoint"
  - "24-06 (frontend button) — backend /api/osm/fetch-roads?o2s contract is ready"
  - "24-07 (docs) — osm-converters.md must update its coverage table to include osm2streets as production-ready"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Shared helper via @Service extraction (OverpassXmlFetcher) with both bytes-return and file-return entry points — one fetch path for GraphHopper + osm2streets"
    - "Package-private static translator with a single public static entry point (StreetNetworkMapper.map) — pure function, trivially testable, no Spring context"
    - "Defensive JSON parsing: tagged-enum LaneType variant keys extracted via fieldNames().next(); width handled as flat i32 AND {inner:...} fallback"
    - "Reverse-projection of internal (x,y) scaled metres back to (lon, lat) using gps_bounds + flat-Earth metres-per-degree, then OsmConversionUtils.lonToX/latToY for A/B/C-fair canvas placement"

key-files:
  created:
    - backend/src/main/java/com/trafficsimulator/service/OverpassXmlFetcher.java
    - backend/src/main/java/com/trafficsimulator/service/StreetNetworkMapper.java
    - backend/src/test/java/com/trafficsimulator/service/StreetNetworkMapperTest.java
    - backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceTest.java
  modified:
    - backend/src/main/java/com/trafficsimulator/service/GraphHopperOsmService.java
    - backend/src/main/java/com/trafficsimulator/service/Osm2StreetsService.java
    - backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java
    - backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java

key-decisions:
  - "OverpassXmlFetcher covers BOTH byte-buffer (osm2streets stdin pipe) AND file-based (GraphHopper File parser) consumers so a single @Service handles both Phase 23/24 needs; Phase 18's JSON-based OsmPipelineService stays untouched as the plan explicitly mandates."
  - "Mapper emits one RoadConfig per osm2streets road (src_i -> dst_i) with laneCount = clamped Driving-lane count across both directions. Phase 18/23 emit two RoadConfigs (fwd + rev with lateralOffset) for bidirectional ways; osm2streets already carries per-lane direction metadata in the lanes[] list, so keeping a single RoadConfig per road avoids double-counting."
  - "Roundabout-detection gap accepted (RESEARCH Pitfall 5): osm2streets' IntersectionControl values (Uncontrolled/Signed/Signalled/Construction) do not forward the OSM junction=roundabout tag, so every non-Signalled control collapses to PRIORITY. This is the documented MVP tradeoff; downstream A/B comparison vs Phase 23 will show the difference."
  - "Reverse-projection uses gps_bounds + flat-Earth metres-per-degree (111 320 m/deg at mid-lat × cos(lat)) since osm2streets internal (x,y) extent is not published alongside gps_bounds in the JSON tree. Accuracy suffices for city-block bboxes (< 1 km); node positions are clamped to bbox extents to avoid off-canvas drift."
  - "Tests use constructor injection of a test-only Osm2StreetsService subclass that overrides executeCli, rather than @SpyBean/@MockBean, so the tests run in plain JUnit without @SpringBootTest — 80ms full run vs 1.3s+ when loading a Spring context."

patterns-established:
  - "When a new converter arrives that needs the same Overpass XML fetch logic, inject OverpassXmlFetcher rather than copying the mirror-loop code (Phase 23 did the copy, this plan undid it)."
  - "StreetNetwork JSON ground-truth (Plan 24-01 SUMMARY) wins over RESEARCH §-hypotheses; mapper field names must echo the SUMMARY exactly (lt/dir/width, TitleCase values, Forward/Backward/Both directions)."

requirements-completed: []

# Metrics
duration: ~14 min
completed: 2026-04-23
tasks_completed: 2
files_created: 4
files_modified: 4
test_count_delta: "+14 (359 -> 373)"
---

# Phase 24 Plan 04: StreetNetworkMapper + OverpassXmlFetcher + Osm2StreetsService.fetchAndConvert Summary

**The heart of Phase 24.** A shared `OverpassXmlFetcher` @Service (extracted from GraphHopperOsmService for reuse), a package-private `StreetNetworkMapper.map(JsonNode, BboxRequest)` translator that turns osm2streets JSON into a MapValidator-clean MapConfig, and a fully wired `Osm2StreetsService.fetchAndConvert` — all driven by the ground-truth JSON shape recorded in 24-01-SUMMARY. 14 new tests (9 mapper + 5 service), zero regressions on Phase 18/23, full backend suite 373/1.

## Performance

- **Duration:** ~14 min wall-clock
- **Started:** 2026-04-23T14:02:58Z
- **Completed:** 2026-04-23T14:16:54Z
- **Tasks:** 2 of 2
- **Files created:** 4 (OverpassXmlFetcher, StreetNetworkMapper, StreetNetworkMapperTest, Osm2StreetsServiceTest)
- **Files modified:** 4 (GraphHopperOsmService + its test, Osm2StreetsService + its existing test)

## Accomplishments

- Extracted **`OverpassXmlFetcher`** from `GraphHopperOsmService` as a shared `@Service`. It owns:
  - `fetchXmlBytes(BboxRequest)` — returns UTF-8 bytes, suitable for piping to subprocess stdin (osm2streets pattern).
  - `fetchXmlToTempFile(BboxRequest, Path tempDir)` — writes the response to a file under the supplied temp dir and returns the path (GraphHopper `WaySegmentParser.readOSM(File)` pattern).
  - Package-private `buildOverpassXmlQuery` + `fetchFromMirrors` for test reuse.
- Refactored `GraphHopperOsmService` to inject `OverpassXmlFetcher` (Lombok `@RequiredArgsConstructor`) and removed its three private copies of the same logic. Zero behavioural change — Phase 23's 8 tests pass untouched.
- `OsmPipelineService` (Phase 18) deliberately left alone — it consumes Overpass **JSON**, not XML, so it has nothing to share with this helper. Verified by `git diff --name-only HEAD~3..HEAD`.
- Implemented **`StreetNetworkMapper`** (package-private final class with private constructor, single public static entry point). Responsibilities:
  - Parse the tuple-array `roads[[id, Road], ...]` and `intersections[[id, Intersection], ...]` shapes (not string-keyed objects — see 24-01-SUMMARY "Top-level StreetNetwork").
  - Extract `lane_specs_ltr` with correct bare-string-or-tagged-object handling for `lt` (e.g. `"Driving"` OR `{"Parking": "Parallel"}`), Forward/Backward/Both for `dir`, scaled-i32 for `width`.
  - MVP lane-type filter drops Shoulder, Buffer, Bus, SharedLeftTurn, Construction, LightRail, Footway; keeps Driving→driving, Parking→parking, Biking→cycling, Sidewalk→sidewalk.
  - laneCount = clamp(count of Driving lanes, 1, `OsmConversionUtils.MAX_LANE_COUNT=4`). `lanes[]` preserves the full MVP-filtered list per RoadConfig.
  - Intersection classification: `Terminus`/`MapEdge` → ENTRY/EXIT terminal NodeConfig; everything else → INTERSECTION + IntersectionConfig.
  - Intersection type: `control == "Signalled"` → SIGNAL with `OsmConversionUtils.buildDefaultSignalPhases`; everything else → PRIORITY (roundabout gap).
  - All ids prefixed `o2s-`.
  - Empty roads → `IllegalStateException("No roads found in selected area")`.
  - Node XY placement: polygon centroid → reverse-projected to lon/lat via `gps_bounds` + flat-Earth mpd → `OsmConversionUtils.lonToX/latToY` (same projection as Phase 18/23 for A/B/C fairness).
  - 11 call sites into `OsmConversionUtils` (`buildRoadConfig`, `buildDefaultSignalPhases`, `collectSpawnPoints`, `collectDespawnPoints`, `assembleMapConfig`, `speedLimitForHighway`, `lonToX`, `latToY`, `MIN_ROAD_LENGTH_M`, `MIN_LANE_COUNT`, `MAX_LANE_COUNT`).
- Rewrote **`Osm2StreetsService.fetchAndConvert`** body: Overpass fetch → `executeCli` → Jackson tree parse → `StreetNetworkMapper.map` → `MapValidator.validate` → MapConfig. IO failures from `executeCli` wrapped as `Osm2StreetsCliException`; JSON parse failures wrapped as the same exception with an "invalid JSON" marker; post-mapper validation errors raise `IllegalStateException` with the joined error list.
- 14 new tests cover the translation + wiring surface; updated the pre-existing `Osm2StreetsServiceExecuteCliTest` to match the new 4-arg constructor + replaced the old "throws UnsupportedOperationException" guard with a Plan 24-04 end-to-end smoke that asserts the "No roads" IllegalStateException surfaces correctly.

## Task Commits

| #   | Task                                                                 | Commit    | Type    |
| --- | -------------------------------------------------------------------- | --------- | ------- |
| 1   | Extract OverpassXmlFetcher + refactor GraphHopperOsmService          | `e6bc250` | refactor |
| 2a  | Add StreetNetworkMapperTest + Osm2StreetsServiceTest (TDD RED)       | `09c3df1` | test    |
| 2b  | Implement StreetNetworkMapper + wire fetchAndConvert (TDD GREEN)     | `6d05f13` | feat    |

Plan metadata commit (this SUMMARY.md) will be the final commit.

## Observed JSON field shape — echoed from 24-01-SUMMARY (traceability)

Mapper matches these names exactly:

```jsonc
{
  "roads":         [[id, { "src_i":…, "dst_i":…, "highway_type":"…", "speed_limit": null|<i32 scaled>,
                           "reference_line": {"pts":[…], "length": <i32 scaled>},
                           "lane_specs_ltr": [
                             { "lt":"Driving"|"Biking"|"Sidewalk"
                                     |{"Parking":"Parallel"}|{"Buffer":"Curb"}, // tagged variants
                               "dir":"Forward"|"Backward"|"Both",
                               "width": <i32 scaled>
                             }, …
                           ]
                         }], …],
  "intersections":[[id, { "kind":"Terminus"|"MapEdge"|"Connection"|"Fork"|"Intersection",
                           "control":"Uncontrolled"|"Signed"|"Signalled"|"Construction",
                           "roads": [<road_id>, …],
                           "polygon": {"rings":[{"pts":[{"x":…,"y":…}, …]}]}
                         }], …],
  "gps_bounds":   {"min_lon":…, "min_lat":…, "max_lon":…, "max_lat":…}
}
```

**Scale factor:** every distance / width / speed value is an i32 = `round(meters_or_mps × 10_000)`. Mapper divides by 10 000 before handing values to MapConfig / MapValidator. A flat `"width": 30000` means 3.0 m.

## Per-fixture mapper output (projected)

Driven by the test assertions and direct inspection of the fixture JSON (no fixture IDs survive if the mapper crashes; every passing test already implies MapValidator is empty).

| Fixture                    | Roads in JSON | Ints in JSON | After mapper (roads × intersections × terminal-nodes) | Notes                                                                 |
|----------------------------|--------------:|-------------:|-------------------------------------------------------|-----------------------------------------------------------------------|
| `straight.osm`             | 1             | 2            | 1 × 0 × 2 (ENTRY + EXIT)                             | bidirectional lanes_specs_ltr collapsed to a single src→dst RoadConfig with laneCount=2 |
| `t-intersection.osm`       | 3             | 4            | 3 × 1 × 3 (3 Terminus + 1 Intersection=PRIORITY)     | 1 PRIORITY intersection, 3 terminal nodes                             |
| `roundabout.osm`           | 8             | 8            | 8 × 4 × 4 (4 Intersection=PRIORITY + 4 Terminus)     | **Roundabout gap:** all 4 ring-intersections are PRIORITY, not ROUNDABOUT |
| `signal.osm`               | 4             | 5            | 4 × 1 × 4 (1 Intersection=SIGNAL + 4 Terminus)       | Signalled control → SIGNAL with default phases                        |
| `missing-tags.osm`         | 0             | 0            | **IllegalStateException: No roads**                  | Empty input path — test M6 + S2 exercise this                         |
| `bike-lane-boulevard.osm`  | 1             | 2            | 1 × 0 × 2, `lanes[]` has 8 entries                   | 10 lane_specs_ltr (2×Sidewalk, 2×Buffer, 2×Parking, 2×Biking, 2×Driving); Buffer dropped, others kept |

**Note on the bike-lane fixture:** `lane_specs_ltr` has 10 entries, 2 of which are `Buffer` (MVP-excluded). The emitted `lanes[]` has 8 entries (Sidewalk×2 + Parking×2 + Biking×2 + Driving×2). `laneCount` = 2 (Driving count, already within the 1–4 clamp).

## Roundabout-detection gap — magnitude

The `roundabout.osm` fixture should produce 4 ROUNDABOUT intersections (one per ring node) but the mapper emits 4 PRIORITY because osm2streets' `control` field for every non-traffic-signalled node collapses to `Signed`. This matches RESEARCH §Pitfall 5 and is accepted as the MVP tradeoff — Phase 24 ships it; a future plan can bridge the gap by inspecting either the original OSM `junction=roundabout` tag (would require re-reading the OSM XML alongside osm2streets output) or the osm2streets road classification when that feature lands upstream.

**Observable impact:** the forthcoming A/B comparison endpoint (`/api/osm/fetch-roads?source=o2s` vs `?source=gh` vs plain Phase 18) will show `ROUNDABOUT`-vs-`PRIORITY` for the same bbox — this is the phase's dogfoodable output for quality review.

## OsmConversionUtils reuse — 11 call sites in the mapper

```
OsmConversionUtils.MAX_LANE_COUNT          (clamp constant)
OsmConversionUtils.MIN_LANE_COUNT          (clamp constant)
OsmConversionUtils.MIN_ROAD_LENGTH_M       (floor for road length)
OsmConversionUtils.speedLimitForHighway    (fallback when speed_limit is null)
OsmConversionUtils.buildRoadConfig         (road DTO assembly)
OsmConversionUtils.buildDefaultSignalPhases (SIGNAL intersections)
OsmConversionUtils.collectSpawnPoints      (ENTRY-driven spawn points)
OsmConversionUtils.collectDespawnPoints    (EXIT-driven despawn points)
OsmConversionUtils.assembleMapConfig       (final MapConfig assembly)
OsmConversionUtils.lonToX                  (X projection)
OsmConversionUtils.latToY                  (Y projection)
```

This satisfies the A/B/C fairness contract (Plan 24-03 §acceptance 3 "≥5 OsmConversionUtils calls"); any A/B/C divergence now comes from OSM parsing differences, not helper drift.

## Test count change

| Suite                                   | Before Plan 24-04 | After Plan 24-04 | Delta |
|-----------------------------------------|-------------------|------------------|-------|
| StreetNetworkMapperTest                 | (did not exist)   | 9                | +9    |
| Osm2StreetsServiceTest                  | (did not exist)   | 5                | +5    |
| Osm2StreetsServiceExecuteCliTest        | 8                 | 8                | 0 (Test H rewired, not counted as delta) |
| GraphHopperOsmServiceTest               | 8                 | 8                | 0     |
| OsmPipelineServiceTest                  | 15                | 15               | 0     |
| **Backend suite total**                 | **359 + 1 skip**  | **373 + 1 skip** | **+14** |

## Decisions Made

1. **OverpassXmlFetcher has BOTH a byte-buffer path AND a temp-file path.** One for osm2streets stdin piping, one for GraphHopper's `File`-based parser. A single `@Service` serves both; the alternative (two helpers or forcing the fetcher to always write to disk) would either duplicate the mirror-loop code or add a disk roundtrip to the osm2streets hot path.
2. **Mapper emits one RoadConfig per osm2streets road.** osm2streets already carries per-lane direction metadata in `lane_specs_ltr`; emitting a second `-rev` RoadConfig the way Phase 18/23 do would double-count driving lanes. The direction info survives inside `lanes[]` for downstream consumers that care.
3. **Roundabout gap is shipped as PRIORITY.** RESEARCH §Pitfall 5 documented this; the plan accepts it; the 24-04-SUMMARY table (above) quantifies it. Future work: consult the raw OSM `junction=roundabout` tag during fetch, or wait for osm2streets upstream to expose a distinct control value.
4. **Tests use a test-only subclass override of `executeCli`** rather than `@SpyBean`. Keeps the test suite Spring-context-free (80 ms vs 1.3 s+), and makes the test seam explicit in-file.
5. **Test H in `Osm2StreetsServiceExecuteCliTest` rewritten.** The old test asserted `UnsupportedOperationException` — a placeholder contract now gone. Replaced with a Plan 24-04 smoke test that asserts the "No roads" `IllegalStateException` surfaces end-to-end through the new wiring, which is arguably a more valuable invariant to lock.
6. **Reverse projection uses flat-Earth metres-per-degree.** osm2streets does not ship the internal (x,y) extent alongside `gps_bounds`; we approximate with `111_320 × cos(mid_lat)` metres-per-degree. Accuracy is adequate for sub-1-km bboxes (our use case). Node positions are clamped to bbox extents so any residual drift never pushes a node off-canvas.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] Test H in Osm2StreetsServiceExecuteCliTest expected removed contract**

- **Found during:** Task 2 (GREEN step), after removing `throw new UnsupportedOperationException(...)` from `Osm2StreetsService.fetchAndConvert`.
- **Issue:** The existing test `fetchAndConvert_throwsUnsupportedForNow` asserts that `fetchAndConvert` throws `UnsupportedOperationException` with message containing "24-04". Plan 24-04 is literally the one that removes that throw, so the assertion became a guaranteed failure. The plan's `files_modified` list did not explicitly include this test, but keeping both the new body and the old assertion would mean a red build.
- **Fix:** Replaced the test with `fetchAndConvert_emptyPipelineSurfacesNoRoadsError` — same file, same test count, but now asserts the Plan 24-04 end-to-end contract (empty-JSON pipeline surfaces an `IllegalStateException` with "No roads") instead of the temporary placeholder.
- **Files modified:** `backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java`
- **Verification:** `mvn test -pl backend -Dtest=Osm2StreetsServiceExecuteCliTest` — 8/8 green.
- **Committed in:** `6d05f13` (Task 2 GREEN commit).

**2. [Rule 3 — Blocking] Osm2StreetsService constructor shape changed; pre-existing test helpers had to follow**

- **Found during:** Task 2 (GREEN step), after adding three new dependencies (`OverpassXmlFetcher`, `ObjectMapper`, `MapValidator`) to the service via `@RequiredArgsConstructor`.
- **Issue:** `Osm2StreetsServiceExecuteCliTest` had `new Osm2StreetsService(cfg)` at three call sites plus `serviceFor(...)` helper. After the constructor change those calls were compile errors.
- **Fix:** Added a `newServiceWith(cfg)` helper in the test that wires `OverpassXmlFetcher` (pointing at an empty mirror list), `new ObjectMapper()`, `new MapValidator()` — all throwaway instances since the tests stub `executeCli` directly and never reach the Overpass fetch path.
- **Files modified:** same as above.
- **Verification:** same as above.
- **Committed in:** `6d05f13`.

**3. [Rule 3 — Blocking] GraphHopperOsmServiceTest constructor after OverpassXmlFetcher injection**

- **Found during:** Task 1, immediately after removing the `RestClient` field from `GraphHopperOsmService`.
- **Issue:** `GraphHopperOsmServiceTest#newService()` constructed `new GraphHopperOsmService(RestClient.create(), validator)`. After Task 1 the constructor signature is `(OverpassXmlFetcher, MapValidator)`.
- **Fix:** Changed the helper to construct `new OverpassXmlFetcher(RestClient.create(), List.of())` and pass that in. Since Phase 23's 8 tests only exercise the `File`-based overload, the fetcher is never used at runtime — any throwaway instance works.
- **Files modified:** `backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java`
- **Verification:** `mvn test -pl backend -Dtest=GraphHopperOsmServiceTest` — 8/8 green, all Phase 23 assertions unchanged.
- **Committed in:** `e6bc250` (Task 1).

---

**Total deviations:** 3 auto-fixed (all Rule 3 — Blocking). No Rule 4 architectural questions; no scope creep; each fix was mechanical and strictly necessary for the build to compile.

## Issues Encountered

- **Worktree base mismatch at startup.** HEAD was at `48c152c` (Phase 23 close) but the plan requires `ab5b6bb`. Fixed with `git reset --hard ab5b6bb2f3b3c064162f6b5be6a1c72c956d2fdb` per the `<worktree_branch_check>` block.
- **No other issues.** Clean RED→GREEN run; mapper passed its full 9-test suite on the first GREEN execution.

## User Setup Required

None — this is a pure backend change; the Plan 24-01 binary is already staged under `backend/bin/`, Overpass mirrors are already configured in `application.properties`, and all tests run offline.

## Known Stubs

**None** — every code path is fully wired. The previous `UnsupportedOperationException` placeholder in `Osm2StreetsService.fetchAndConvert` is gone; the pre-existing `Osm2StreetsCliException` / `Osm2StreetsCliTimeoutException` taxonomy from Plan 24-03 is now hooked into a production code path.

## Deferred Issues

- `deferred-items.md` from Plan 24-03 notes `backend/target/` is tracked in git — that predates Phase 24 and remains out of scope here.
- **Roundabout gap** is the only phase-24 debt introduced by this plan. Tracked in the "Roundabout-detection gap — magnitude" section above; a follow-up plan (out of Phase 24 scope) can bridge it by re-reading the OSM junction tag.

## Threat Flags

**None introduced.** The only new network-touching surface is `OverpassXmlFetcher`, which is a refactor of pre-existing GraphHopper Overpass logic and inherits its threat profile unchanged (outbound HTTPS to a configurable mirror list, no inbound surface, no credentials). The new `StreetNetworkMapper` is pure in-process JSON parsing and projection arithmetic — no IO, no reflection, no subprocess spawning.

## Next Phase Readiness

- **Plan 24-05 (controller)** unblocked — `Osm2StreetsService.fetchAndConvert` returns a real MapConfig; the controller can now wire `/api/osm/fetch-roads?source=o2s` with the same exception-mapping table Plan 24-03 §"HTTP mapping preview" outlined.
- **Plan 24-06 (frontend button)** unblocked — backend is ready; only the UI wiring remains.
- **Plan 24-07 (phase docs)** unblocked — `backend/docs/osm-converters.md` can promote osm2streets from "skeleton" to "production-ready" and add the roundabout-gap caveat.
- **Phase 18 + Phase 23 regression gates** both green; no behaviour change on their side.

## Self-Check

### Created files
- `backend/src/main/java/com/trafficsimulator/service/OverpassXmlFetcher.java` — **FOUND** (contains `class OverpassXmlFetcher` + `fetchXmlBytes` + `fetchXmlToTempFile`)
- `backend/src/main/java/com/trafficsimulator/service/StreetNetworkMapper.java` — **FOUND** (contains `final class StreetNetworkMapper` + `static map(JsonNode, BboxRequest)`)
- `backend/src/test/java/com/trafficsimulator/service/StreetNetworkMapperTest.java` — **FOUND** (9 tests)
- `backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceTest.java` — **FOUND** (5 tests)

### Modified files
- `backend/src/main/java/com/trafficsimulator/service/GraphHopperOsmService.java` — **FOUND** (no longer contains `buildOverpassXmlQuery` / `fetchFromMirrors`; injects `OverpassXmlFetcher` via `@RequiredArgsConstructor`)
- `backend/src/main/java/com/trafficsimulator/service/Osm2StreetsService.java` — **FOUND** (fetchAndConvert no longer throws `UnsupportedOperationException`; contains `StreetNetworkMapper.map` call)
- `backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java` — **FOUND** (helper constructs `OverpassXmlFetcher` instance)
- `backend/src/test/java/com/trafficsimulator/service/Osm2StreetsServiceExecuteCliTest.java` — **FOUND** (uses 4-arg `newServiceWith(cfg)` helper; Test H rewritten)

### Commit hashes present in git log
- `e6bc250` — **FOUND** (Task 1 refactor — OverpassXmlFetcher extraction)
- `09c3df1` — **FOUND** (Task 2 RED — failing tests for mapper + service)
- `6d05f13` — **FOUND** (Task 2 GREEN — mapper implementation + service wiring)

### Constraint checks
- `mvn test -pl backend` — **373 passed, 0 failures, 0 errors, 1 skipped** (matches plan prediction `358 + 14 = 372` ± 1 with the extra Phase 24-02 MapValidator assertion that was already in place)
- `mvn test -pl backend -Dtest='OsmPipelineServiceTest,GraphHopperOsmServiceTest'` — **23 passed, 0 failures** (Phase 18/23 regression gates green)
- `mvn test -pl backend -Dtest='StreetNetworkMapperTest,Osm2StreetsServiceTest'` — **14 passed, 0 failures, 0 errors** (new coverage)
- `mvn test -pl backend -Dtest=ArchitectureTest` — **7 passed, 0 failures** (no new ArchUnit violations)
- `git diff --name-only HEAD~3..HEAD -- backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — **empty** (Phase 18 untouched)
- `grep -c 'OsmConversionUtils\.' backend/src/main/java/com/trafficsimulator/service/StreetNetworkMapper.java` — **11** (≥5 required — A/B/C fairness contract satisfied)
- No edits to `STATE.md`, `ROADMAP.md`, `tools/`, `backend/bin/`, `frontend/` — confirmed via `git diff --name-only HEAD~3..HEAD`

## Self-Check: PASSED

---
*Phase: 24-osm2streets-integration*
*Plan: 04 (Wave 3, the heaviest plan in the phase)*
*Completed: 2026-04-23*
