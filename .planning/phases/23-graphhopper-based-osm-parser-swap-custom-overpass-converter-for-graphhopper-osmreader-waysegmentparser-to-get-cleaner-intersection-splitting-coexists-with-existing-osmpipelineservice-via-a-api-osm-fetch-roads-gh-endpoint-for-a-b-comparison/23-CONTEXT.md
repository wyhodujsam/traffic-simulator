# Phase 23: GraphHopper-based OSM parser - Context

**Gathered:** 2026-04-22
**Status:** Ready for research
**Source:** ROADMAP.md Phase 23 scope + conversation with user (closing session after Phase 22.1)

<domain>
## Phase Boundary

Add a second OSM-to-MapConfig pipeline that uses **GraphHopper's** `OSMReader` / `WaySegmentParser` for road network extraction, exposed via a new endpoint `POST /api/osm/fetch-roads-gh`. The existing `OsmPipelineService` (Overpass-based, Phase 18) and `/api/osm/fetch-roads` endpoint **must stay byte-for-byte unchanged** so we can A/B compare the two converters on the same bbox.

Phase 23 does NOT alter the frontend MapPage flow to make GraphHopper the default — the frontend gets a second "Fetch roads (GraphHopper)" button (or similar) so the user chooses per request. Phase 24 (osm2streets) will extend the A/B into a three-way comparison; this phase must leave hooks for that.

</domain>

<decisions>
## Implementation Decisions

### Goal
GraphHopper does native OSM way-segment splitting at junctions (its `WaySegmentParser` splits ways at tower-nodes = junctions by design). Current Phase 18 `OsmPipelineService` does a heuristic split that can leave node-count / intersection-count inaccuracies. The comparison we want to quantify:

| Metric | Phase 18 (Overpass converter) | Phase 23 (GraphHopper) |
|---|---|---|
| Roads per bbox | Manually counted | Read from result |
| Intersections detected | Heuristic (nodes used >1 way) | WaySegmentParser tower-nodes |
| Node coordinate fidelity | Overpass lat/lon direct | GraphHopper's internal graph → extract back |
| Lane counts | `tags.lanes` passthrough | Same |
| Unknown tags handling | Best-effort | Likely stricter |

### Scope — what ships
1. **New Spring Boot service** `GraphHopperOsmService` (or similar) that:
   - Accepts a `BboxRequest` (same DTO as Phase 18)
   - Internally runs GraphHopper's `GraphHopper` core over a local `.osm.pbf` snippet OR over a pre-downloaded Overpass JSON (decision: **use the same Overpass fetch as Phase 18** — reuse `OsmPipelineService.fetchFromMirrors` if feasible, to avoid a second HTTP path; downstream-only diff). Research will confirm whether GraphHopper supports JSON input or only PBF/XML.
   - Returns a `MapConfig` that passes `MapValidator`
2. **New REST endpoint** `POST /api/osm/fetch-roads-gh` in a new or extended `OsmController` variant.
3. **Frontend button** "Fetch roads (GraphHopper)" on `/map` page next to the existing button — same bbox input, routes to new endpoint, displays in the same sidebar result panel.
4. **A/B integration test** that calls both endpoints on a known-small bbox and asserts that both produce a `MapValidator`-clean `MapConfig`; diff metrics (roads/intersections/nodes) are logged but do NOT gate the test.
5. **Documentation** in `.planning/research/` or `backend/docs/` of how to add a third converter later (Phase 24 osm2streets hook).

### Scope — what does NOT ship
- Replacing Phase 18 or making GraphHopper the default — additive only.
- Frontend rework — reuse the existing MapPage flow with one added button.
- Persistent map storage — same in-memory-only model as Phase 18.
- GraphHopper routing features — we only use its OSM parser, not its pathfinder.
- Lane-level geometry (turn lanes, markings, intersection polygons) — that's Phase 24's osm2streets scope.
- GraphHopper's Android/iOS/JS bindings — server-side JVM only.

### Locked decisions

1. **Additive endpoint.** `POST /api/osm/fetch-roads-gh` is NEW. `POST /api/osm/fetch-roads` is unchanged.
2. **Same DTO contract.** `BboxRequest` in, `MapConfig` out. Frontend and sidebar rendering reuse.
3. **Coexistence.** Both services run in the same Spring context. Neither depends on the other. If GraphHopper startup fails (classpath, native libs), Phase 18 must still work — graceful degradation.
4. **MapValidator compliance.** Whatever GraphHopper produces MUST pass the existing `MapValidator`. If GraphHopper emits data the validator rejects (e.g., orphan nodes), transform before returning — do NOT weaken the validator.
5. **Dependency choice.** Use official GraphHopper `com.graphhopper:graphhopper-core` Maven artifact. Pick a version compatible with Spring Boot 3.3.x + Java 17. Research must produce the exact GAV.
6. **OSM fetch strategy.** TBD by research: either (a) reuse `OsmPipelineService`'s Overpass fetch and feed JSON into GraphHopper if it accepts, or (b) have GraphHopper fetch/read a PBF directly. If (b), need a strategy for bbox-limited PBF (Overpass `[out:pbf]`?).
7. **Intersection semantics.** GraphHopper's tower-node concept (nodes used by ≥2 ways or at dead-ends) maps to our `IntersectionConfig` with type derived from tag scan (PRIORITY default, SIGNAL if any adjacent way has traffic_signals). Roundabouts (`junction=roundabout`) emit ROUNDABOUT.
8. **Coordinate extraction.** GraphHopper internally uses `PillarInfo` for geometry; we extract lat/lon back to feed `NodeConfig.x/y` (pixel coords via existing projection helper used in Phase 18).
9. **Non-goal: lanes > 1 handling.** If GraphHopper exposes per-direction lane counts differently than Phase 18's flat `laneCount`, simplify to a single aggregate — don't add frontend support in this phase.

### Frontend integration
- Add a second button "Fetch roads (GraphHopper)" to `MapSidebar.tsx` idle state, positioned next to the existing "Fetch roads" and "AI Vision (…)" buttons.
- Reuse the existing loading/error/result state machine — the endpoint URL is the only difference in the handler.
- Minimal UI distinguisher: sidebar-result headline reads "GraphHopper: N roads, M intersections" vs Phase 18's "Overpass: N roads, M intersections" so A/B is obvious.

### Testing

- Backend unit test `GraphHopperOsmServiceTest` with a fixture `.osm.pbf` OR a hand-rolled JSON sample covering: simple straight road, T-intersection, roundabout, two-way vs one-way, missing tags.
- Backend integration test `OsmPipelineComparisonTest` (`@SpringBootTest`) that calls both services on the same fixture and logs the diff (no strict assertion).
- Frontend Playwright spec `osm-bbox-gh.spec.ts` mirroring `osm-bbox.spec.ts` (Phase 22.1) — stubbed `/api/osm/fetch-roads-gh` with canned MapConfig, assert sidebar result. Reuse the fixture pattern from 22.1.
- NO ArchUnit change expected — Phase 23 follows Phase 18 layering.

### Anti-regression

- Full backend suite (308 tests as of Phase 22.1 close) MUST stay green.
- Full Playwright suite (6 tests) MUST stay green; adding one spec for the new endpoint makes it 7.
- Vitest 51/3 skipped MUST stay unchanged.

### Claude's Discretion

- GraphHopper's minimum-viable configuration (which `PMap` / `EncodingManager` profiles to enable). Recommend car-only for simplicity.
- Whether to run GraphHopper in an in-memory graph (preferred) or persist to `graph-cache/` (probably no — startup cost per request is acceptable for MVP).
- Handling of GraphHopper's logging: pipe through SLF4J, not stdout noise.
- Error path semantics: GraphHopper throws its own exceptions; map to the same HTTP 422/503/504 pattern as `OsmController` for Phase 18.
- Caching strategy for repeated bboxes (probably none — keep stateless).
- Whether to extract a shared abstract `OsmConverter` interface in this phase or wait for Phase 24. Lean toward YES — thin interface like `MapConfig fetchAndConvert(BboxRequest)` so the frontend can eventually toggle between converters by an enum.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents (researcher, planner, executor) MUST read these.**

### Existing OSM pipeline (Phase 18)
- `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — reference implementation to mirror layering, logging, error taxonomy
- `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` — endpoint shape + error mapping (422 / 503 / 504)
- `backend/src/main/java/com/trafficsimulator/dto/BboxRequest.java` — request DTO (reuse)
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — output DTO schema (reuse)
- `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — hard output gate (must pass)
- `backend/src/main/java/com/trafficsimulator/config/OsmClientConfig.java` — HTTP client bean pattern
- `backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` — test idiom + fixture approach
- `backend/src/test/java/com/trafficsimulator/controller/OsmControllerTest.java` — @WebMvcTest pattern

### Frontend OSM flow
- `frontend/src/pages/MapPage.tsx` — bbox handler, pipeline state machine
- `frontend/src/components/MapSidebar.tsx` — button placement + idle/loading/result states
- `frontend/src/components/BoundingBoxMap.tsx` — Leaflet bbox emitter

### Phase 22.1 test patterns (Playwright)
- `frontend/e2e/osm-bbox.spec.ts` — stubbed OSM flow spec; mirror structurally for the GH variant
- `frontend/e2e/fixtures/osm-fetch-roads-response.json` — canned MapConfig shape

### Pattern sources
- `backend/pom.xml` — Maven dependency section; where GraphHopper goes
- `backend/src/main/resources/application.properties` — port 8086, logging levels

### Phase context
- `.planning/phases/18-osm-data-pipeline/18-*-SUMMARY.md` — what Phase 18 actually built
- `.planning/phases/22.1-playwright-.../22.1-04-PLAN.md` — Phase 22.1 OSM stub spec (mirror for GH)

</canonical_refs>

<specifics>
## Specific Ideas

- GraphHopper core artefact on Maven Central: `com.graphhopper:graphhopper-core`. Latest stable at time of writing is 10.x; must verify Java 17 / Spring Boot 3.3 compatibility.
- `WaySegmentParser` is the public entry point for splitting ways at tower nodes. Its output is a stream of edges + node lists.
- GraphHopper's `OSMReader` can consume `.osm.pbf` (binary) or `.osm` (XML). Does NOT natively consume Overpass JSON.
  - Options: (a) download `.osm.pbf` from Overpass using `[out:pbf]`, (b) transform Overpass JSON → OSM XML in-memory, (c) abandon Overpass and call GraphHopper's `DataReader` directly against a Geofabrik regional PBF (slow, huge file — rejected for MVP).
- `PMap` is GraphHopper's config bag. Minimal profile: `{profile=car,encoder=car}`.
- Running GraphHopper server-less (no router, just parser) is a supported pattern — instantiate `GraphHopper`, call `import()`, extract via `GraphHopper.getBaseGraph()`.

</specifics>

<deferred>
## Deferred Ideas

- Caching GraphHopper graphs per bbox (Phase 24 or later).
- Swapping Phase 18 to call GraphHopper internally (never — additive-only is the whole point).
- Routing / pathfinding features (out of scope).
- Persistent server-side map storage.
- Making GraphHopper the default from the frontend — user chooses per request.
- Lane-level geometry (turn lanes, crosswalks) — Phase 24 osm2streets.
- Offline Geofabrik PBF ingestion — only online Overpass-backed fetch in this phase.

</deferred>

---

*Phase: 23-graphhopper-based-osm-parser*
*Context gathered: 2026-04-22 from ROADMAP scope + conversation context*
