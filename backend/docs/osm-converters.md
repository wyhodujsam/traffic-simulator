# OSM Converters — Architecture & Extension Guide

**Last updated:** 2026-04-23 (Phase 23 close)

## Overview

The backend exposes two OSM → `MapConfig` converters, both reachable via REST endpoints on the same base path. They coexist so we can A/B the output on the same bounding box.

| Converter            | Phase | Endpoint                          | Service class                | Label returned by `converterName()` |
|----------------------|-------|-----------------------------------|------------------------------|--------------------------------------|
| Overpass (heuristic) | 18    | `POST /api/osm/fetch-roads`       | `OsmPipelineService`         | `"Overpass"`                          |
| GraphHopper          | 23    | `POST /api/osm/fetch-roads-gh`    | `GraphHopperOsmService`      | `"GraphHopper"`                       |
| (future) osm2streets | 24    | `POST /api/osm/fetch-roads-o2s`   | TBD                          | TBD                                  |

Both current converters implement the `OsmConverter` interface (`backend/src/main/java/com/trafficsimulator/service/OsmConverter.java`) and emit a `MapConfig` that passes `MapValidator` unmodified.

## Why two converters?

Phase 18's converter does a hand-rolled pass over Overpass JSON and detects intersections heuristically (a node is an intersection if it is referenced by ≥2 ways). This works for most urban geometry but misses subtle cases like signals placed on pillar nodes inside a single way, or produces slightly wrong road counts when ways fork/merge in ways Overpass reports with unusual element ordering.

Phase 23 adds GraphHopper's `WaySegmentParser`, which operates on OSM XML (not Overpass JSON directly) and splits ways at junctions by definition (GraphHopper calls them "tower nodes"). Its output differs from Phase 18 in several measurable ways — road count, intersection count, sometimes coordinate precision — and we want to quantify the delta before deciding whether to replace Phase 18 or keep both.

**Non-goals:** this phase does NOT replace Phase 18, does NOT ship lane-level geometry (turn lanes, crosswalks, intersection polygons — that is Phase 24's scope), and does NOT persist any parsed graph to disk. Both services are stateless and hold state only for the duration of a single request.

## A/B fairness contract

A meaningful A/B comparison requires that both converters agree on everything except the graph-construction step. Shared logic lives in `OsmConversionUtils` (`backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java`), which both services call into:

- **Projection** (lat/lon → canvas x/y): `lonToX`, `latToY` and the `CANVAS_*` constants.
- **Distance math**: `haversineMeters`, `computeWayLength` (sum-of-segments across all intermediate points — NOT endpoint-to-endpoint).
- **Tag-driven defaults**: `speedLimitForHighway`, `laneCountForWay` (both include the same MAX-lane clamp = 4 for motorway/trunk and cascading defaults for lower classes).
- **Endpoint assembly**: `collectSpawnPoints`, `collectDespawnPoints`, `assembleMapConfig`.
- **Signal cycle defaults**: `buildDefaultSignalPhases` — a consistent N-S/E-W cycle applied in both pipelines when OSM carries `highway=traffic_signals` without an explicit cycle.

If you change one of these helpers, BOTH converters change at once. That is intentional — it preserves the fairness contract.

## Phase 23 data flow

```
BboxRequest
   │
   ▼
OsmPipelineService.fetchFromMirrors  (Overpass `[out:xml]` query, reused by GH)
   │
   ▼
Overpass XML → tempfile
   │
   ▼
GraphHopper WaySegmentParser (Path B — parser only, no routing)
   │   EdgeHandler captures:
   │     - ReaderWay tags (highway, oneway, lanes, maxspeed, junction)
   │     - Node tags on pillar points (traffic_signals, …)
   │     - PointList geometry (deep-copied)
   │
   ▼
GraphHopperOsmService.buildMapConfig
   │     - node IDs prefixed "gh-"
   │     - road IDs prefixed "gh-"
   │     - intersections classified ROUNDABOUT/SIGNAL/PRIORITY from captured tags
   │     - road length computed via OsmConversionUtils.computeWayLength
   │     - lanes/speed/default signal phases via OsmConversionUtils
   │
   ▼
MapValidator.validate(mapConfig)   (hard gate — throws on validator errors)
   │
   ▼
MapConfig  → 200 OK
```

## Spike findings (Wave 0 of Phase 23)

Before committing to the Phase 23 implementation, a throwaway spike (`23-SPIKE.md`) probed two open questions. Keeping the findings here for historical record, since the spike test was deleted once Plan 23-07 closed the phase.

**A1 — does `WaySegmentParser.EdgeHandler.nodeTags` surface OSM node tags? → PASS.**
The 5th parameter `List<Map<String, Object>> nodeTags` in GraphHopper 10.2 exposes the tags verbatim. A 3-node way with `<tag k="highway" v="traffic_signals"/>` on the middle node produced `nodeTags[1] = {highway=traffic_signals}`. No two-pass StAX fallback was needed.

**A7 — does a failing `@Service` bean degrade gracefully? → FAIL.**
A bean that throws from its constructor aborts Spring context startup with `BeanCreationException`, taking down Phase 18 with it. Mitigation: `GraphHopperOsmService` is annotated `@Lazy`, and the controller injects it via constructor so the failure surface is deferred to first request — a classpath issue produces a 503 on `/fetch-roads-gh` without taking Phase 18's endpoint offline.

## Adding a third converter (Phase 24 recipe)

1. Add the new dependency to `backend/pom.xml`.
2. Create `YourConverterOsmService` under `backend/src/main/java/com/trafficsimulator/service/`:
   ```java
   @Service
   @Lazy
   public class YourConverterOsmService implements OsmConverter {
       @Override
       public MapConfig fetchAndConvert(BboxRequest bbox) { … }
       @Override
       public String converterName() { return "YourConverter"; }
   }
   ```
3. Reuse `OsmConversionUtils` for projection, distance, tag defaults, and endpoint assembly. Do NOT re-implement these; fairness is lost the moment two converters use different constants.
4. Add `POST /api/osm/fetch-roads-your-slug` to `OsmController` (constructor injection + `@Lazy` on the dep). Mirror the error taxonomy from Phase 18 (400 / 422 / 503 / 504).
5. Write tests under `backend/src/test/java/com/trafficsimulator/service/YourConverterOsmServiceTest.java` — cover at minimum: straight road, T-intersection, roundabout, signal, missing tags. Reuse the Phase 23 fixtures in `backend/src/test/resources/osm/` if applicable.
6. Extend `OsmPipelineComparisonTest` to A/B/C the three converters (the test is gated by `@EnabledIfSystemProperty(named="osm.online", matches="on|true")` so CI stays offline).
7. Add a frontend button mirroring Phase 23's "Fetch roads (GraphHopper)" pattern in `MapSidebar.tsx`; thread `resultOrigin` so the sidebar heading distinguishes the three origins.
8. Add a Playwright spec under `frontend/e2e/` mirroring `osm-bbox-gh.spec.ts` that stubs `/api/osm/fetch-roads-your-slug`.

## Error taxonomy

Both converters surface the same HTTP error codes (mapped by `OsmController`'s `@ExceptionHandler`):

| HTTP | When                                                                            |
|------|---------------------------------------------------------------------------------|
| 400  | Request body malformed or bbox values out of range                              |
| 422  | Parser ran but produced zero usable roads (empty intersection, all filtered)    |
| 503  | Overpass mirrors all failed, or GraphHopper initialisation threw                |
| 504  | Upstream Overpass timeout                                                       |

## Known open questions (for future work)

- **Should `xmlgraphics-commons` be excluded from `graphhopper-core`?** Not in this phase — defer until a concrete jar-size complaint surfaces. Research note: it is a transitive only used by GraphHopper's image-based debug output, which we do not use.
- **Should the converters agree on intersection node IDs?** Currently they do NOT (Phase 18 uses `osm-<way>-<idx>`, Phase 23 uses `gh-<id>`). This prevents mismatched-ID noise in A/B diffs. If a future convergence layer compares geometry rather than IDs, this may need re-visiting.
- **Threading.** Both services are singleton `@Service` beans. Neither holds mutable instance state; all per-request state lives on the call stack. Safe for concurrent requests. No shared `BaseGraph`, no shared `RAMDirectory`.
