# Phase 24: osm2streets integration - Context

**Gathered:** 2026-04-23
**Status:** Ready for research
**Source:** ROADMAP.md Phase 24 + conversation after Phase 23 close

<domain>
## Phase Boundary

Add a third OSM → `MapConfig` converter that uses **A/B Street's osm2streets** (Rust library) to produce lane-level street geometry with intersection shapes, turn lanes, and road markings. Exposed via a new endpoint `POST /api/osm/fetch-roads-o2s`. Coexists with Phase 18 (Overpass heuristic) and Phase 23 (GraphHopper WaySegmentParser) for three-way A/B comparison on the same bbox.

</domain>

<decisions>
## Implementation Decisions

### Goal
osm2streets models roads at the **lane level** — each road has an explicit array of typed lanes (driving, parking, cycling, sidewalk), turn-lane annotations, and intersection polygons. This is materially richer than Phase 18 (flat `laneCount` per road) and Phase 23 (same flat model via GraphHopper's encoded flags). The A/B/C comparison answers: "does the additional fidelity materially improve simulation quality, or is it pixel-dust we don't need?"

### Scope — what ships
1. New Spring Boot service `Osm2StreetsService` that:
   - Accepts a `BboxRequest` (same DTO as Phases 18/23)
   - Fetches Overpass OSM XML (reuse `OsmPipelineService.fetchFromMirrors` or `OsmConversionUtils` equivalent)
   - Invokes osm2streets over the XML to produce a `StreetNetwork` (its native output)
   - Converts `StreetNetwork` → our `MapConfig` schema, flattening lane-level data to our existing flat model AND optionally surfacing lane-level metadata in a new `lanes[]` array per `RoadConfig` (additive field; existing consumers that read `laneCount` stay unaffected)
   - Output passes `MapValidator` unchanged
2. New REST endpoint `POST /api/osm/fetch-roads-o2s` wired in `OsmController`.
3. Frontend: third button "Fetch roads (osm2streets)" next to Phase 18 + Phase 23 buttons; `resultOrigin` threading already supports this (just add one more string label).
4. Playwright spec `osm-bbox-o2s.spec.ts` mirroring Phase 23's `osm-bbox-gh.spec.ts` — stubbed endpoint, canned MapConfig fixture.
5. Three-way A/B/C comparison test extending `OsmPipelineComparisonTest` (same `@EnabledIfSystemProperty` gate).
6. `backend/docs/osm-converters.md` updated to document the three-way architecture (remove Phase 24 "future" placeholder; document the new `lanes[]` optional field).

### Scope — what does NOT ship
- Replacing Phase 18 or Phase 23 — additive only. `OsmConverter` interface is already stable.
- Simulation engine changes to USE the new lane-level data. This phase only surfaces the richer data; consuming it in IDM/MOBIL is a future phase.
- Turn-lane enforcement in simulation — out of scope.
- Intersection polygon rendering on canvas — out of scope.
- Full osm2streets feature set (e.g. detailed pavement marking emission). MVP: lanes per road + intersection classification.

### Locked decisions

1. **Additive only.** `/api/osm/fetch-roads` (Phase 18) and `/api/osm/fetch-roads-gh` (Phase 23) stay byte-for-byte unchanged.
2. **Reuse `OsmConverter` interface.** `Osm2StreetsService implements OsmConverter` with `converterName() = "osm2streets"`.
3. **Reuse `OsmConversionUtils` for shared concerns** — projection (lonToX/latToY), pixel canvas constants, `assembleMapConfig`, spawn/despawn collectors, `buildDefaultSignalPhases`. A/B/C fairness contract extends from Phase 23.
4. **Reuse Overpass XML fetch** from Phase 23's pattern — osm2streets consumes OSM XML, not JSON.
5. **Integration strategy: Rust binary subprocess** (DECISION SUBJECT TO RESEARCH — see Claude's Discretion). Rationale: JVM has no first-class Rust FFI, and the existing codebase already uses a subprocess pattern for external tools (`ClaudeVisionService` → `claude` CLI). A pre-built osm2streets-cli wrapper binary under `backend/bin/` is the lowest-risk integration path. Alternatives (WASM runtime in JVM, GraalVM native image, JNI) are explicitly deferred unless research shows a trivial path.
6. **Output schema extension.** `MapConfig.RoadConfig` gains an OPTIONAL field `lanes: List<LaneConfig>` where `LaneConfig = { type: String, width: double, direction: String }`. Phase 18 and Phase 23 services continue to emit `null` for this field. `MapValidator` treats the field as optional. Simulation engine IGNORES the field (no consumer wired in this phase).
7. **MVP lane types:** `driving`, `parking`, `cycling`, `sidewalk`. Others (median, shoulder, turn, bus) are folded into `driving` or dropped for MVP.
8. **Error taxonomy same as Phase 18/23.** 400 / 422 / 503 / 504.
9. **Rust binary bundling.** The osm2streets wrapper binary ships pre-built under `backend/bin/osm2streets-cli-linux-x64` (Linux-only for MVP; dev + deployment are Linux). Cross-platform builds deferred. Binary is committed to the repo if ≤40 MB (RESEARCH-observed static musl builds fall in the 15–25 MB range, comfortably under the threshold); if the resulting artifact exceeds 40 MB, fall back to gitignore + fetch-on-build. Threshold was raised from an earlier 5 MB estimate after RESEARCH confirmed static musl footprint; this threshold is the authoritative source.
10. **Timeout.** osm2streets invocation wrapped in a 30-second hard timeout per request, consistent with ClaudeVisionService timing.

### Frontend integration
- Add third button "Fetch roads (osm2streets)" in `MapSidebar.tsx` idle state, after Phase 23's "Fetch roads (GraphHopper)".
- Reuse existing `handleFetchRoads` / `handleFetchRoadsGh` pattern → `handleFetchRoadsO2s` calling the new endpoint.
- `resultOrigin="osm2streets"` → sidebar heading reads "osm2streets: N roads, M intersections" mirroring Phase 23's labeling.
- No new UI panels; the `lanes[]` field is surfaced in the result panel ONLY if present (fallback: silent).

### Testing

- Backend unit test `Osm2StreetsServiceTest` — fixture-driven using the same 5 OSM XML fixtures from Phase 23 (straight, T-intersection, roundabout, signal, missing-tags). If osm2streets produces substantively different outputs (e.g. extra sidewalks on straight roads), document and relax assertions to focus on structural invariants (MapValidator-clean, non-empty roads, matching intersection count within tolerance).
- `OsmPipelineComparisonTest` extended to iterate all three converters and log their outputs side-by-side when `osm.online=on`.
- `Osm2StreetsControllerTest` — 3 new WebMvc tests mirroring Phase 23 pattern (`fetchRoadsO2s_success_200`, `fetchRoadsO2s_empty_422`, `fetchRoadsO2s_invalid_400`).
- Playwright spec `frontend/e2e/osm-bbox-o2s.spec.ts` mirroring 22.1/23 patterns (stubbed endpoint + canned fixture + heading assertion).
- No ArchUnit changes expected.

### Anti-regression

- Full backend suite currently 347 passed + 1 skipped (post Phase 23 close). MUST stay green at end of Phase 24 with reasonable new-test count.
- Vitest 56 + 3 skipped stays ≥56.
- Playwright 7 stays ≥7; one new spec brings it to 8.

### Claude's Discretion (confirmable by research)

- **Integration path.** The binary-subprocess approach is the current default, but research should investigate:
  - (a) **Pre-built binary:** does osm2streets publish a CLI binary? If yes, ship it + invoke via ProcessBuilder.
  - (b) **Build from source:** cargo-compile an osm2streets-cli wrapper in CI. Adds Rust toolchain requirement.
  - (c) **WASM in JVM:** use a lightweight WASM runtime (Chicory, wasmer-java) to load osm2streets.wasm into the JVM. No subprocess overhead but new dependency risk.
  - (d) **Sidecar service:** run osm2streets as a separate HTTP server; backend calls it via RestClient. Adds operational complexity.
  Recommend ONE path with rationale. (a) is the target unless it's infeasible.
- osm2streets' JSON output schema — research must produce a concrete mapping to our `MapConfig`.
- Exact `LaneConfig` field names — align with osm2streets' terminology if possible.
- Whether the MVP should also surface intersection polygons in `IntersectionConfig` (deferred by default).
- Whether to extract a shared `OsmConverter` comparison harness module (currently inline in `OsmPipelineComparisonTest`).
- Whether the 5 Phase 23 OSM fixtures are sufficient or osm2streets needs a 6th richer fixture (e.g. bike-lane-heavy bidirectional road).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before research/planning/implementation.**

### Existing OSM pipeline (Phase 18 + 23)
- `backend/src/main/java/com/trafficsimulator/service/OsmConverter.java` — interface to implement
- `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` — Phase 18 reference
- `backend/src/main/java/com/trafficsimulator/service/GraphHopperOsmService.java` — Phase 23 reference (same structural template as Phase 24 will follow)
- `backend/src/main/java/com/trafficsimulator/service/OsmConversionUtils.java` — shared helpers; REUSE for A/B/C fairness
- `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` — endpoint + error mapping
- `backend/src/main/java/com/trafficsimulator/dto/BboxRequest.java` — input DTO (reuse)
- `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` — output schema (EXTEND with optional `lanes[]`)
- `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` — hard gate (must stay compatible with nullable `lanes`)

### External-process precedent
- `backend/src/main/java/com/trafficsimulator/service/ClaudeVisionService.java` — existing ProcessBuilder pattern (timeout, stdin/stdout handling, error taxonomy) to mirror for the osm2streets-cli subprocess path
- `backend/src/main/java/com/trafficsimulator/config/ClaudeCliConfig.java` — `@ConfigurationProperties` for external binary path + timeout; same pattern for `Osm2StreetsConfig`

### Frontend
- `frontend/src/components/MapSidebar.tsx` — button placement; `resultOrigin` prop already threaded through Phase 23
- `frontend/src/pages/MapPage.tsx` — handler clone point (`handleFetchRoads` → `handleFetchRoadsGh` → `handleFetchRoadsO2s`)
- `frontend/e2e/osm-bbox-gh.spec.ts` — Playwright pattern to mirror
- `frontend/e2e/fixtures/osm-fetch-roads-response.json` — fixture shape reference

### Phase 23 artefacts (just landed)
- `.planning/phases/23-.../23-CONTEXT.md`, `23-RESEARCH.md`, `23-SPIKE.md`
- `.planning/phases/23-.../23-00..07-SUMMARY.md`
- `backend/docs/osm-converters.md` — documents the two-converter architecture; extend with Phase 24 section

### Fixtures
- `backend/src/test/resources/osm/*.osm` — 5 OSM XML fixtures from Phase 23; reuse for Phase 24 unit tests

</canonical_refs>

<specifics>
## Specific Ideas

- osm2streets GitHub: https://github.com/a-b-street/osm2streets — Rust library; has `osm2streets` and related crates
- Apparently publishes a WASM build for the a-b-street web editor — worth investigating whether a pre-compiled WASM artifact exists and is usable
- osm2streets output "StreetNetwork" format is documented in their repo under `osm2streets/src/*` and in the web demo's TypeScript bindings
- Web demo lives at https://a-b-street.github.io/osm2streets/ — may expose a useful JS API or JSON format reference
- Phase 20's ClaudeVisionService uses `ProcessBuilder` + `--output-format text` + JSON extraction — same mental model applies if we invoke a Rust binary
- Minimal Rust CLI wrapper: `osm2streets-cli fetch --input-xml <path> --bbox <json> --output-json <path>` — emits StreetNetwork as JSON

</specifics>

<deferred>
## Deferred Ideas

- Simulation engine consumption of `lanes[]` data (IDM/MOBIL lane-aware routing) — separate future phase.
- Canvas rendering of intersection polygons and turn-lane markings — separate future phase.
- Cross-platform Rust binary builds (macOS, Windows) — Linux-only for MVP.
- Turn-lane enforcement at intersections — out of scope.
- Automatic choice of "best" converter based on bbox characteristics — manual choice per request stays.
- Caching osm2streets outputs per bbox — stateless for now.
- Full bidirectional lane modeling (e.g. cycle lane direction opposite to main traffic) — fold into `driving` for MVP.

</deferred>

---

*Phase: 24-osm2streets-integration*
*Context gathered: 2026-04-23 from ROADMAP + Phase 23 artefacts + conversation*
