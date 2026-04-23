# OSM Converters — Architecture & Extension Guide

**Last updated:** 2026-04-23 (Phase 24 close)

## Overview

The backend exposes **three** OSM → `MapConfig` converters. All three are reachable via REST endpoints on the same base path and implement the shared `OsmConverter` interface. They coexist so we can A/B/C the output on the same bounding box.

| Converter              | Phase | Endpoint                          | Service class            | `converterName()` | Detail level          |
|------------------------|-------|-----------------------------------|--------------------------|--------------------|------------------------|
| Overpass (heuristic)   | 18    | `POST /api/osm/fetch-roads`       | `OsmPipelineService`     | `"Overpass"`       | Flat `laneCount`       |
| GraphHopper            | 23    | `POST /api/osm/fetch-roads-gh`    | `GraphHopperOsmService`  | `"GraphHopper"`    | Flat `laneCount`       |
| osm2streets            | 24    | `POST /api/osm/fetch-roads-o2s`   | `Osm2StreetsService`     | `"osm2streets"`    | Lane-level (`lanes[]`) |

All three emit a `MapConfig` that passes `MapValidator` unmodified. The `RoadConfig.lanes` field (added in Phase 24) is OPTIONAL — Phase 18 and Phase 23 leave it `null`, Phase 24 populates it from the osm2streets lane spec. The `@JsonInclude(Include.NON_NULL)` annotation ensures Phase 18/23 REST output stays byte-identical to their pre-Phase-24 shape.

## Why three converters?

- **Phase 18** (Overpass converter) does a hand-rolled pass over Overpass JSON and detects intersections heuristically (a node is an intersection if ≥2 ways reference it). Works for most urban geometry but misses subtle cases.
- **Phase 23** (GraphHopper) operates on OSM XML and splits ways at junctions by definition (GraphHopper's "tower nodes"). Corrects some intersection miscounts Phase 18 produces but still emits a flat `laneCount` per road.
- **Phase 24** (osm2streets) goes one level deeper: it models roads at the **lane level** with typed lane arrays (driving/parking/cycling/sidewalk), width in metres, and per-lane direction. This is materially richer than Phase 18/23.

The A/B/C comparison (`OsmPipelineComparisonTest`, gated by `-Dosm.online=on`) answers: "does the additional fidelity materially improve simulation quality, or is it pixel-dust we don't need?"

**Non-goals:** Phase 24 does NOT replace Phase 18 or Phase 23, does NOT render intersection polygons on the canvas, and does NOT wire `lanes[]` into the simulation engine. IDM/MOBIL still read the flat `laneCount`. Consuming lane-level data in the simulation is a future phase.

## Phase 24 integration strategy — Path B (Rust subprocess)

osm2streets is a Rust library. Four integration paths were evaluated in Phase 24 research:

- **Path A — Pre-built CLI:** does NOT exist (osm2streets has no releases, no tags).
- **Path B — Custom Rust wrapper + static binary + ProcessBuilder:** **chosen**. Mirrors Phase 20's `ClaudeVisionService` pattern (external tool via subprocess). Single binary artefact, no new runtime dependency, no operational complexity.
- **Path C — WASM in JVM:** `osm2streets-js` uses `wasm-bindgen` which is browser-only. Chicory and wasmer-java cannot provide the `__wbindgen_placeholder__` imports the bindgen runtime requires. Rejected.
- **Path D — Sidecar HTTP service:** works but adds a separate server to deploy. Rejected for MVP.

### Binary provenance

- **Source:** `tools/osm2streets-cli/` — a Cargo project with git-SHA-pinned osm2streets dependency.
- **Pinned SHA:** `fc119c47dac567d030c6ce7c24a48896f58ed906` (recorded in `tools/osm2streets-cli/Cargo.toml` + `Cargo.lock`).
- **Binary path:** `backend/bin/osm2streets-cli-linux-x64` — static-PIE musl-libc ELF, ~3.5 MB (comfortably under CONTEXT D9's 40 MB commit threshold).
- **SHA-256:** `9e657692510b118ee730bd8c1d27b22abb557b36df6241f80ae3ab56c41a3a98` (see `backend/bin/osm2streets-cli-linux-x64.sha256`).
- **Rebuild recipe:** see `backend/bin/README.md`. To rebuild after bumping the osm2streets SHA, `cd tools/osm2streets-cli && cargo build --release --target x86_64-unknown-linux-musl`, then copy `target/x86_64-unknown-linux-musl/release/osm2streets-cli` into `backend/bin/` and regenerate the SHA-256.

### Invocation pattern

`Osm2StreetsService.executeCli(byte[] osmXmlBytes)` spawns the binary via `ProcessBuilder`:
- `redirectErrorStream(false)` — separate stdout/stderr streams.
- Stdout and stderr each drained on their own `CompletableFuture` thread to avoid the single-buffer deadlock (mirrors and extends `ClaudeVisionService`'s pattern — osm2streets writes structured error text to stderr that we want to capture distinctly).
- Stdin written synchronously then closed explicitly so the child can exit.
- Hard timeout of 30 seconds per request (`Osm2StreetsConfig.timeoutSeconds`, configurable via `application.properties`).
- `@Lazy @Service` on `Osm2StreetsService`: if the binary is missing at runtime, constructor is deferred to first request — a failing bean never crashes the Spring context (same A7 mitigation pattern Phase 23 adopted for GraphHopper).

## Data flow

```
BboxRequest
   │
   ▼
OverpassXmlFetcher  (shared between Phase 23 and Phase 24; Phase 18 fetches JSON via its own client)
   │
   ▼
Overpass XML bytes
   │
   ├────────────────────┬────────────────────┐
   │                    │                    │
   ▼                    ▼                    ▼
 Phase 18             Phase 23             Phase 24
 (Overpass JSON       (GraphHopper         (osm2streets-cli
  elements loop)      WaySegmentParser)     subprocess)
   │                    │                    │
   ▼                    ▼                    ▼
 Handwritten         EdgeHandler           StreetNetwork
 graph assembly      captures tags         JSON output
   │                    │                    │
   │                    │                    ▼
   │                    │                  StreetNetworkMapper
   │                    │                  (defensive JSON
   │                    │                   parsing — handles
   │                    │                   TitleCase/snake_case
   │                    │                   + string/tagged-object
   │                    │                   LaneType variants)
   │                    │                    │
   └────────────────────┴────────────────────┘
                        │
                        ▼
                  OsmConversionUtils
                  (projection, haversine, lane
                   clamp, signal phases —
                   IDENTICAL for all three)
                        │
                        ▼
                   MapValidator
                        │
                        ▼
                    MapConfig
```

## A/B/C fairness contract

A meaningful A/B/C comparison requires that the three converters agree on everything except the graph-construction step. Shared logic lives in `OsmConversionUtils` (extracted during Phase 23), which all three services call into:

- **Projection** (lat/lon → canvas x/y): `lonToX`, `latToY` and the `CANVAS_*` constants.
- **Distance math**: `haversineMeters`, `computeWayLength` (sum-of-segments — NOT endpoint-to-endpoint).
- **Tag-driven defaults**: `speedLimitForHighway`, `laneCountForWay` (MAX-lane clamp = 4 for motorway/trunk, cascading defaults for lower classes).
- **Endpoint assembly**: `collectSpawnPoints`, `collectDespawnPoints`, `assembleMapConfig`.
- **Signal cycle defaults**: `buildDefaultSignalPhases`.

Changing any shared helper changes all three converters simultaneously. That is intentional.

Phase 24 additionally shares `OverpassXmlFetcher` with Phase 23 — the `[out:xml]` Overpass query + mirror-retry logic was extracted from `OsmPipelineService.fetchFromMirrors` during Plan 24-04.

## Lane metadata contract (Phase 24 schema extension)

`MapConfig.RoadConfig` gained an optional `lanes: List<LaneConfig>` field. Each `LaneConfig` is:

```java
public record LaneConfig(
    String type,      // "driving" | "parking" | "cycling" | "sidewalk"
    double width,     // metres
    String direction  // "forward" | "backward" | "both"
) {}
```

MVP lane types are locked (CONTEXT D7). Non-MVP osm2streets lane variants (`Buffer`, `Shoulder`, `TurnLane`, `Bus`, etc.) are folded into the nearest MVP category or dropped:

| osm2streets variant | Mapped to |
|---|---|
| `Driving` | `driving` |
| `Parking` (any sub-type) | `parking` |
| `Cycling` / `Biking` | `cycling` |
| `Sidewalk` / `Footway` | `sidewalk` |
| `Bus` | `driving` (dedicated bus lane is out of simulation scope) |
| `Buffer` / `Shoulder` / `TurnLane` | dropped |

**Consumer status:** the simulation engine does NOT currently read `lanes[]`. IDM/MOBIL still operate on the flat `laneCount`. Consuming the lane-level data is a future phase.

## Known gaps and limitations

### Phase 24 — roundabout detection

osm2streets' `IntersectionControl` enum only exposes four variants: `Uncontrolled`, `Signed`, `Signalled`, `Construction`. There is **no `Roundabout` variant**. Phase 24 therefore classifies all roundabout-like intersections as either `PRIORITY` (Uncontrolled) or `SIGNAL` (Signalled), missing the distinct ROUNDABOUT classification that Phases 18 and 23 produce for `junction=roundabout` tags.

This is an **accepted MVP gap**. Mitigation options for a future spike:
- Parse retained Overpass XML for `junction=roundabout` and cross-reference with osm2streets output by WGS84 position.
- Contribute upstream to osm2streets to surface roundabout detection.
- Fork and add a minimal post-processing pass in `tools/osm2streets-cli`.

### Phase 24 — xmlgraphics-commons transitive dependency

RESEARCH Q2 flagged whether `xmlgraphics-commons` (transitive dep of the Rust build via osm2streets) should be excluded. Decision (CONTEXT D9 era): **do not exclude** in this phase. Defer until a concrete jar-size complaint surfaces. The transitive lives only in the Rust build environment and does not affect the shipped binary.

### Phase 24 — lane JSON field-name serde variance

RESEARCH Q3 flagged MEDIUM confidence on osm2streets' serde field naming (TitleCase vs snake_case). Plan 24-01 recorded the observed shape on `straight.osm`: `lt` / `dir` / `width` with `Direction` values `Forward` / `Backward` / `Both`. Some `LaneType` variants (e.g. `Parking`, `Buffer`) serialise as tagged objects `{VariantName: subtype}` rather than bare strings. `StreetNetworkMapper` defensively handles both forms.

## Error taxonomy

All three converters surface the same HTTP error codes (mapped by `OsmController`'s `@ExceptionHandler`):

| HTTP | When                                                                              |
|------|-----------------------------------------------------------------------------------|
| 400  | Request body malformed or bbox values out of range                                |
| 422  | Parser ran but produced zero usable roads                                          |
| 503  | Overpass mirrors all failed, GraphHopper init threw, or osm2streets-cli returned non-zero exit |
| 504  | Upstream timeout (Overpass or osm2streets-cli hard timeout)                        |

## Adding a fourth converter (extension recipe)

1. Add the new dependency to `backend/pom.xml` (JVM lib) or bundle a new binary under `backend/bin/` (external tool).
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
3. Reuse `OsmConversionUtils` for projection/distance/tag-defaults and `OverpassXmlFetcher` for the XML fetch. Do NOT re-implement — A/B/C fairness depends on it.
4. Add `POST /api/osm/fetch-roads-your-slug` to `OsmController` (constructor injection + `@Lazy` on the dep). Mirror the error taxonomy.
5. Write unit + WebMvc tests mirroring Phase 23 / Phase 24 patterns.
6. `OsmPipelineComparisonTest` autowires `List<OsmConverter>` and iterates; your service joins the rotation automatically once it's an `@Service` bean.
7. Add a frontend button in `MapSidebar.tsx`, extend `resultOrigin` union, clone the handler in `MapPage.tsx`.
8. Add a Playwright spec under `frontend/e2e/` mirroring `osm-bbox-o2s.spec.ts`.

## Test suite

- **Backend:** 381 passed + 1 skipped (the A/B/C comparison, gated by `-Dosm.online=on`).
- **Vitest:** 59 passed + 3 skipped.
- **Playwright:** 8 passed (one per converter flow + simulation + controls + responsive + vision).

Run the full gate with:
```bash
mvn test -pl backend
cd frontend && npm test -- --run
cd frontend && npx playwright test
```
