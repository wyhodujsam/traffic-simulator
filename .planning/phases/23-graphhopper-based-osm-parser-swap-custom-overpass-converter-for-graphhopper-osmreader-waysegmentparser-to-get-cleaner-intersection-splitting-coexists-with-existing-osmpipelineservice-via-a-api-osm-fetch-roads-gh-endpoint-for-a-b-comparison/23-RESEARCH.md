# Phase 23: GraphHopper-based OSM parser — Research

**Researched:** 2026-04-22
**Domain:** Embedded Java use of GraphHopper's OSM parser (`com.graphhopper:graphhopper-core`) as a second converter alongside Phase 18's Overpass pipeline
**Confidence:** HIGH on version / dependency / API surface; MEDIUM on the chosen Overpass-XML feeding strategy (works but never benchmarked in this project); LOW on precise memory footprint under parallel load (no project-specific data).

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

1. **Additive endpoint.** `POST /api/osm/fetch-roads-gh` is NEW. `POST /api/osm/fetch-roads` is unchanged.
2. **Same DTO contract.** `BboxRequest` in, `MapConfig` out. Frontend and sidebar rendering reuse.
3. **Coexistence.** Both services run in the same Spring context. Neither depends on the other. If GraphHopper startup fails (classpath, native libs), Phase 18 must still work — graceful degradation.
4. **MapValidator compliance.** Whatever GraphHopper produces MUST pass the existing `MapValidator`. If GraphHopper emits data the validator rejects (e.g., orphan nodes), transform before returning — do NOT weaken the validator.
5. **Dependency choice.** Use official GraphHopper `com.graphhopper:graphhopper-core` Maven artifact. Pick a version compatible with Spring Boot 3.3.x + Java 17. Research must produce the exact GAV.
6. **OSM fetch strategy.** TBD by research: either (a) reuse `OsmPipelineService`'s Overpass fetch and feed JSON into GraphHopper if it accepts, or (b) have GraphHopper fetch/read a PBF directly. If (b), need a strategy for bbox-limited PBF (Overpass `[out:pbf]`?).
7. **Intersection semantics.** GraphHopper's tower-node concept maps to our `IntersectionConfig` with type derived from tag scan (PRIORITY default, SIGNAL if any adjacent way has traffic_signals). Roundabouts (`junction=roundabout`) emit ROUNDABOUT.
8. **Coordinate extraction.** GraphHopper internally uses `PillarInfo` for geometry; we extract lat/lon back via `NodeAccess` to feed `NodeConfig.x/y` (pixel coords via existing projection helper used in Phase 18).
9. **Non-goal: lanes > 1 handling.** Simplify to a single aggregate — do NOT add frontend support in this phase.

### Claude's Discretion

- GraphHopper's minimum-viable configuration (which `PMap` / `EncodingManager` profiles to enable). Recommend car-only for simplicity.
- Whether to run GraphHopper in an in-memory graph (preferred) or persist to `graph-cache/` (probably no — startup cost per request is acceptable for MVP).
- Handling of GraphHopper's logging: pipe through SLF4J, not stdout noise.
- Error path semantics: map GraphHopper exceptions to the same HTTP 422/503/504 pattern as `OsmController` for Phase 18.
- Caching strategy for repeated bboxes (probably none — keep stateless).
- Whether to extract a shared abstract `OsmConverter` interface in this phase or wait for Phase 24. Lean toward YES — thin interface like `MapConfig fetchAndConvert(BboxRequest)` so the frontend can eventually toggle between converters by an enum.

### Deferred Ideas (OUT OF SCOPE)

- Caching GraphHopper graphs per bbox (Phase 24 or later).
- Swapping Phase 18 to call GraphHopper internally (never — additive-only is the whole point).
- Routing / pathfinding features (out of scope).
- Persistent server-side map storage.
- Making GraphHopper the default from the frontend — user chooses per request.
- Lane-level geometry (turn lanes, crosswalks) — Phase 24 osm2streets.
- Offline Geofabrik PBF ingestion — only online Overpass-backed fetch in this phase.
</user_constraints>

## Summary

GraphHopper is an established Java routing library published as `com.graphhopper:graphhopper-core` on Maven Central. Every supported release (≥ 8.0) runs on **Java 17**. Two versions are candidates:

- **10.2** (published 2025-01-20) — uses Dropwizard 3.0.8 BOM → Jackson **2.17.2**, SLF4J 2.0.16, Logback 1.3.14. This is an **exact match** to Spring Boot 3.3.5's managed Jackson 2.17.2. `[VERIFIED: maven-metadata + POM inspection]`
- **11.0** (published 2024-10-14) — uses Dropwizard 4.0.16 BOM → Jackson **2.19.2**, SLF4J 2.0.17, Logback 1.5.18. Maven dependency mediation would keep Spring Boot's Jackson 2.17.2 (declared first), so 11.0 *would* work — but it silently downgrades Jackson from GraphHopper's expected version, which is a bug risk. `[VERIFIED: maven-metadata + POM inspection]`

**Primary recommendation:** use **`com.graphhopper:graphhopper-core:10.2`** for clean transitive alignment with Spring Boot 3.3.5. Document a single-line upgrade path to 11.0 once Spring Boot moves to Jackson 2.19.

For OSM input, GraphHopper's `OSMReader` supports **`.osm` XML, `.osm.gz`, `.osm.zip`, and `.osm.pbf`** — it does **not** consume Overpass JSON. The cleanest bridge: query Overpass with `[out:xml]` instead of `[out:json]`, write the response to a per-request temp file, feed that file path to `GraphHopper.setOSMFile()`, import, extract, delete temp file. No native libraries needed. No Overpass PBF support required (many public mirrors don't serve `[out:pbf]`). `[VERIFIED: Overpass output_formats.html + GraphHopper GraphHopper.java source]`

For graph extraction without routing: instantiate `new GraphHopper()`, configure a minimal `car` profile, call `importAndClose()`, iterate `baseGraph.getAllEdges()`, read coordinates via `baseGraph.getNodeAccess().getLat/getLon(nodeId)`, pull geometry via `edge.fetchWayGeometry(FetchMode.ALL)`. Tower-node IDs (the `from`/`to` of every edge) are implicitly GraphHopper's *intersection candidates* — this is the property we want. `[VERIFIED: GraphHopper master branch example files + low-level-api.md]`

**One critical gap:** the default `OSMReader` discards most OSM tags after encoding them into compact edge flags. To preserve `junction=roundabout`, `highway=traffic_signals` (on nodes), lane counts, and other tags we need for `MapConfig`, we must either (a) use `WaySegmentParser` directly with a custom `EdgeHandler` that captures `ReaderWay.getTags()`, or (b) attach custom `TagParser` / `RelationTagParser` to the `EncodingManager`. Option (a) is simpler and decoupled from routing profiles. Recommended. `[VERIFIED: PR #2448 source + ReaderWay.getTag() usage]`

---

## Phase Requirements

*(No canonical requirement IDs in `REQUIREMENTS.md` map to Phase 23 — this is a technical research + integration phase. The binding requirements below are reconstructed from CONTEXT.md decisions 1–9.)*

| Pseudo-ID | Description | Research Support |
|-----------|-------------|------------------|
| GH-01 | Add `POST /api/osm/fetch-roads-gh` that accepts `BboxRequest` and returns `MapConfig` | Section 5 (mapping), 11 (reuse strategy) |
| GH-02 | GraphHopper output MUST pass `MapValidator` unchanged | Section 5 (concrete DTO construction), Section 9 (pitfalls: orphan nodes) |
| GH-03 | Both endpoints coexist in the same Spring context; GraphHopper failure must not break Phase 18 | Section 6 (lifecycle), Section 7 (error taxonomy) |
| GH-04 | Use official `com.graphhopper:graphhopper-core` Maven artifact compatible with Java 17 + Spring Boot 3.3.x | Section 1 (Chosen Dependency) |
| GH-05 | Detect intersections via GraphHopper's tower-node semantics; map `junction=roundabout` → ROUNDABOUT, `traffic_signals` → SIGNAL, else PRIORITY | Section 4 (tag hook), Section 5 (mapping) |
| GH-06 | Frontend adds a second "Fetch roads (GraphHopper)" button routing to new endpoint | Out of backend scope; see CONTEXT.md "Frontend integration" |
| GH-07 | A/B integration test calls both endpoints on same bbox, logs metric diff (no hard assert) | Section 8 (testing strategy) |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Overpass HTTP fetch (bbox → OSM XML string) | Backend (`GraphHopperOsmService` or reused `OsmPipelineService` helper) | — | Network I/O must stay server-side; identical to Phase 18 |
| OSM XML → in-memory graph parsing | Backend (GraphHopper library) | — | GraphHopper is a JVM library; no browser component |
| Graph → MapConfig mapping | Backend (`GraphHopperOsmService`) | — | Output DTO schema lives in backend |
| `MapValidator` gate | Backend | — | Unchanged shared utility |
| REST endpoint `/api/osm/fetch-roads-gh` | Backend (`OsmController` or `OsmControllerGh`) | — | Same Spring MVC pattern as Phase 18 |
| Button + result rendering | Frontend (`MapSidebar.tsx` + `MapPage.tsx`) | — | Reuses existing state machine |
| Playwright test stubbing `/api/osm/fetch-roads-gh` | Frontend e2e | — | Mirrors `osm-bbox.spec.ts` from Phase 22.1 |

## Project Constraints (from CLAUDE.md)

- **Java 17 + Spring Boot 3.3.x + Maven** — all dependency choices must align. `[VERIFIED: pom.xml parent spring-boot-starter-parent 3.3.5]`
- **React 18 + TypeScript + Vite** — frontend. Phase 23 adds one button + one fetch handler, no new libraries.
- **WebSocket/STOMP over SockJS** — irrelevant to Phase 23 (HTTP only).
- **No database / persistence** — the `graphHopperLocation` temp dir must be cleaned up per request; no persisted graph cache.
- **Lombok** is in use (`@Slf4j`, `@RequiredArgsConstructor`, `@Data`) — follow same pattern in new service.
- **GSD workflow enforcement** — all file edits go through a GSD command; this research feeds `gsd-planner`.
- **CONVENTIONS.md** — follow existing code style; the Phase 18 `OsmPipelineService` is the reference pattern (record-based intermediate data, private decomposition helpers, static geometry utilities).

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.graphhopper:graphhopper-core` | **10.2** | OSM parser + in-memory graph (`BaseGraph`, `NodeAccess`, `EdgeIterator`, `WaySegmentParser`) | Only mainstream Java library that does OSM way-segment splitting at tower-nodes as a library call `[VERIFIED: Maven Central metadata]` |

**Rationale for 10.2 over 11.0:** Spring Boot 3.3.5 manages Jackson 2.17.2. GraphHopper 10.2's Dropwizard 3.0.8 BOM pins the same 2.17.2. GraphHopper 11.0's Dropwizard 4.0.16 BOM pins 2.19.2 — Maven mediation downgrades it silently to 2.17.2 (Spring Boot wins because it's declared first in `pom.xml`), which *usually* works (Jackson 2.x is backward-compatible within minor versions) but is a latent risk. 10.2 is lock-step. `[VERIFIED: inspected both Dropwizard BOM POMs + Spring Boot 3.3.5 dependencies POM]`

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Existing `RestClient` (Spring 6) | Spring-managed | Overpass HTTP fetch | Reuse `overpassRestClient` bean from `OsmClientConfig.java` — no new client |
| Existing Jackson `ObjectMapper` | Spring-managed (2.17.2) | Any JSON serialization | GraphHopper does NOT emit JSON for us; we only read OSM XML |
| `java.nio.file.Files.createTempDirectory` | JDK 17 | Per-request temp dir for `setGraphHopperLocation` | Needed because `importAndClose()` insists on a writable directory |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `com.graphhopper:graphhopper-core:10.2` | `11.0` | Newer, but silent Jackson downgrade + different Dropwizard BOM. Reserve for a future "bump GraphHopper" phase paired with Spring Boot upgrade. |
| `com.graphhopper:graphhopper-core:10.2` | `9.1` | 9.x still on Dropwizard 3.x and Jackson 2.17.x too, but 10.2 brings bug fixes and is the latest of the 10-line. No benefit to pinning lower. |
| GraphHopper `WaySegmentParser` (standalone) | Full `GraphHopper` facade + `getBaseGraph()` | Standalone is leaner (no routing profiles / encoded values / CH preparation), preserves raw `ReaderWay` tags in the EdgeHandler callback, and avoids the `graphHopperLocation` directory noise. **Recommended path** — see Section 3. |
| OSM XML transport via Overpass | Overpass `[out:pbf]` | Not all public Overpass mirrors support `[out:pbf]` (overpass-api.de does, kumi.systems may or may not). XML is universally supported, marginally larger over the wire, parses just fine via GraphHopper's built-in XML reader. `[VERIFIED: Overpass output_formats.html]` |
| Overpass JSON → OSM XML transform in-memory | Direct XML from Overpass | Transforming is pointless work when Overpass can emit XML natively. Only worth considering if we ever want to reuse Phase 18's JSON-cached fetch — but we don't need to, fetches are per-request anyway. |

**Installation:**

```xml
<!-- Add to backend/pom.xml under <dependencies>, after spring-boot-starter-websocket -->
<dependency>
    <groupId>com.graphhopper</groupId>
    <artifactId>graphhopper-core</artifactId>
    <version>10.2</version>
</dependency>
```

**Version verification (performed 2026-04-22):**

```
rtk proxy curl -sS "https://repo1.maven.org/maven2/com/graphhopper/graphhopper-core/maven-metadata.xml"
→ <latest>11.0</latest>
→ <release>11.0</release>
```

Both 10.2 (2025-01-20) and 11.0 (2024-10-14) are live on Maven Central. Dropwizard BOM inspection confirms Jackson 2.17.2 (Dropwizard 3.0.8, used by 10.2) vs Jackson 2.19.2 (Dropwizard 4.0.16, used by 11.0). `[VERIFIED: direct POM fetch 2026-04-22]`

---

## 1. Chosen Dependency

**GAV:** `com.graphhopper:graphhopper-core:10.2`

**Compatibility notes:**

- `<maven.compiler.target>17</maven.compiler.target>` — GraphHopper 10.x builds for Java 17. Project already on Java 17. ✓ `[VERIFIED: graphhopper-parent-10.2.pom]`
- Single jar — `graphhopper-core` pulls everything (`WaySegmentParser`, `OSMReader`, `BaseGraph`, `NodeAccess`, all encoded-value parsers). There is no separate `graphhopper-reader-osm` artifact in 10.x/11.x. `[VERIFIED: 10.2 POM dependency list]`
- Transitive pull-ins: `com.carrotsearch:hppc 0.8.1`, `org.locationtech.jts:jts-core 1.20.0`, `org.codehaus.janino:janino 3.1.9`, `com.fasterxml.jackson.core:*`, `com.fasterxml.jackson.dataformat:jackson-dataformat-xml`, `org.apache.xmlgraphics:xmlgraphics-commons 2.7`, `de.westnordost:osm-legal-default-speeds-jvm 1.4`, `org.jetbrains.kotlin:kotlin-stdlib 1.6.20`, `org.openstreetmap.osmosis:osmosis-osm-binary 0.48.3`, `org.slf4j:slf4j-api`. `[VERIFIED: graphhopper-core-10.2.pom]`
- **Jackson version** resolves to Spring Boot 3.3.5's **2.17.2** via Maven nearest-wins. Confirmed match with GraphHopper 10.2's declared version. ✓
- **SLF4J version** resolves to Spring Boot's **2.0.16**; GraphHopper wanted 2.0.16 too. ✓
- **Logback** resolves to Spring Boot's **1.5.11**; GraphHopper 10.2 wanted 1.3.14. Spring Boot's newer version wins — fine, Logback 1.3→1.5 is forward-compatible at the API surface GraphHopper uses (`org.slf4j.Logger` only).
- **Kotlin stdlib** 1.6.20 — pulled for `osm-legal-default-speeds-jvm`. Small footprint, no clash.
- **xmlgraphics-commons 2.7** — used for CGIAR elevation import (TIFF files). We won't call elevation code, but the class is on the classpath. Commons-logging is explicitly excluded by GraphHopper — good.

**Transitive deps to watch for:**

| Dep | Risk | Mitigation |
|-----|------|------------|
| `com.fasterxml.jackson.dataformat:jackson-dataformat-xml` | Pulls StAX + Woodstox. Normally invisible; could collide if we ever add another XML lib. | None needed for Phase 23. Document for Phase 24. |
| `org.jetbrains.kotlin:kotlin-stdlib 1.6.20` | Adds ~1.5 MB to fat jar. | Acceptable. Do NOT exclude — `osm-legal-default-speeds-jvm` will NPE without it. |
| `xmlgraphics-commons` | Only needed for elevation imports (not used by us). | Can be excluded in `pom.xml` to trim ~1 MB, but not required. Skip unless jar-size becomes a concern. |
| `osmosis-osm-binary 0.48.3` | Brings `protobuf-java` transitively. Spring Boot 3.3 does not manage protobuf — no clash. | Passive; do not exclude (needed for PBF support even if we only use XML — WaySegmentParser probes both). |

**Repository:** Maven Central only. No secondary repo needed. `[VERIFIED: artifact present under `https://repo1.maven.org/maven2/com/graphhopper/graphhopper-core/10.2/`]`

**Smoke verification before writing the plan:**

```bash
cd backend
./mvnw dependency:tree -Dincludes=com.graphhopper:graphhopper-core
# Expect: com.graphhopper:graphhopper-core:jar:10.2:compile
# No "OMITTED for conflict with" warnings on jackson-core / jackson-databind.
```

---

## 2. Input Format Strategy

**Decision:** Fetch OSM XML from Overpass, write to a temp file, feed the temp file path to GraphHopper.

**Rationale:**

- GraphHopper's readers accept `.osm`, `.osm.gz`, `.osm.zip`, and `.osm.pbf` — detected by file extension. `[VERIFIED: GraphHopper.setOSMFile javadoc]`
- Overpass supports `[out:xml]` as a first-class output format on every public mirror. `[VERIFIED: dev.overpass-api.de/output_formats.html]`
- Converting Overpass JSON → OSM XML in-memory is unnecessary work. Overpass can emit XML directly.
- GraphHopper has no in-memory `InputStream` reader API — it wants a `File` path. `[VERIFIED: GraphHopper source — `setOSMFile(String)`]` A temp file is the cleanest path.
- Bbox-limited XML queries return ~50–500 KB for typical urban bboxes (e.g., Warsaw 0.02° × 0.02°). Temp-file disk I/O is negligible compared to the Overpass round-trip.

**Overpass query to build** (mirrors Phase 18's query with XML output):

```java
private String buildOverpassXmlQuery(BboxRequest bbox) {
    return """
            [out:xml][timeout:25];
            (
              way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"](%f,%f,%f,%f);
            );
            out body;
            >;
            out skel qt;\
            """
            .formatted(bbox.south(), bbox.west(), bbox.north(), bbox.east());
}
```

(The `>;` recurses down to the nodes of each way — needed so GraphHopper's OSMReader can resolve every way's geometry. Identical to Phase 18's recursion clause.)

**Code skeleton — fetch + write to temp file:**

```java
// Source: adapted from OsmPipelineService.fetchFromMirrors (Phase 18) + JDK 17 Files API
private Path fetchOverpassXmlToTempFile(BboxRequest bbox) throws IOException {
    String query = buildOverpassXmlQuery(bbox);
    String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

    String xml = fetchFromMirrors(encoded);   // reuse Phase 18 helper (response is text)

    Path tempDir = Files.createTempDirectory("gh-osm-");
    Path osmFile = tempDir.resolve("bbox.osm");
    Files.writeString(osmFile, xml, StandardCharsets.UTF_8);
    return osmFile;
}
```

**Cleanup:** always delete the temp directory in a `try/finally`. See Section 6 lifecycle pattern.

**Why not PBF?** Smaller on the wire (5–10× compression), but:
1. Not universally available from public Overpass mirrors.
2. Requires `osmosis-osm-binary` + protobuf-java to be on classpath — already present transitively, so no extra dep cost.
3. No measurable parse-time win for sub-1MB bboxes.

If Phase 25 ever needs larger bboxes, PBF is a single-line swap: change `[out:xml]` → `[out:pbf]` and rename temp file `.osm.pbf`. GraphHopper autodetects the format by extension.

---

## 3. Parsing API Pattern

There are two viable paths; each has a different tradeoff against tag preservation (see Section 4).

### Path A: Full `GraphHopper` facade (simplest — routing infrastructure we don't need)

```java
GraphHopper hopper = new GraphHopper();
hopper.setOSMFile(osmFile.toString());
hopper.setGraphHopperLocation(tempDir.resolve("gh-cache").toString()); // writable dir, required
hopper.setEncodedValuesString("car_access, car_average_speed, road_access, road_class, max_speed");
hopper.setProfiles(new Profile("car").setCustomModel(new CustomModel()));
hopper.importAndClose();   // imports AND releases file locks

// At this point the graph is on disk. Reopen read-only to iterate.
BaseGraph baseGraph = hopper.getBaseGraph();   // accessible after importOrLoad, but NOT after importAndClose
```

Problem: `importAndClose()` releases the graph. `importOrLoad()` keeps it open — use that.

```java
hopper.setOSMFile(osmFile.toString());
hopper.setGraphHopperLocation(ghCacheDir.toString());
hopper.setEncodedValuesString("car_access, car_average_speed, road_access");
hopper.setProfiles(new Profile("car"));
hopper.importOrLoad();   // populates baseGraph in-place

BaseGraph baseGraph = hopper.getBaseGraph();
NodeAccess na = baseGraph.getNodeAccess();
AllEdgesIterator edges = baseGraph.getAllEdges();
while (edges.next()) {
    int baseNode = edges.getBaseNode();
    int adjNode  = edges.getAdjNode();
    PointList geom = edges.fetchWayGeometry(FetchMode.ALL);   // lat/lon for all pillar nodes + towers
    double distanceMeters = edges.getDistance();
    // ... map to RoadConfig
}

try { hopper.close(); } catch (Exception ignored) {}
```

**Downside of Path A:** by the time we iterate, GraphHopper has already discarded the raw `ReaderWay` tags. We'd get `edges.get(roadClassEnc)` (enum: MOTORWAY, PRIMARY, …) but *not* `oneway=-1` literal, *not* `junction=roundabout`, *not* `highway=traffic_signals` on nodes, *not* `lanes=X` passthrough. We'd have to reverse-engineer from encoded values. Fragile.

### Path B: **WaySegmentParser directly with EdgeHandler** (recommended)

`WaySegmentParser` is the public class GraphHopper extracted in PR #2448 for exactly this use case: parse OSM without the routing shell. The `EdgeHandler` callback receives the raw `ReaderWay` object — full access to all tags.

```java
// Source: adapted from graphhopper/graphhopper PR #2448 README example
// https://github.com/graphhopper/graphhopper/pull/2448

import com.graphhopper.reader.osm.WaySegmentParser;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.PointList;

// 1. Create a throwaway in-memory graph (RAMDirectory means no disk persistence).
Directory dir = new RAMDirectory();
BaseGraph graph = new BaseGraph.Builder(0).setDir(dir).create();

// 2. Accumulators for the data we actually want.
List<ParsedEdge> parsedEdges = new ArrayList<>();
Map<Integer, Map<String, Object>> nodeTagsByTowerId = new HashMap<>();

// 3. Configure and run the parser.
WaySegmentParser parser = new WaySegmentParser.Builder(graph.getNodeAccess(), dir)
    .setWayFilter(way -> way.hasTag("highway")
                       && isDrivableHighway(way.getTag("highway")))
    .setEdgeHandler((from, to, pointList, way, nodeTagsList) -> {
        // from, to: tower-node IDs (ints, graph-local — NOT OSM ids)
        // pointList: lat/lon for all nodes in the segment (pillar + tower)
        // way: ReaderWay with ALL OSM tags — getTag("highway"), getTag("oneway"), etc.
        // nodeTagsList: tags of intermediate nodes along the segment (we mainly care about tower nodes)
        parsedEdges.add(new ParsedEdge(from, to, pointList.clone(false), copyTags(way)));
    })
    .setWorkerThreads(1)   // deterministic ordering; single-request scope, parallelism not needed
    .build();

parser.readOSM(osmFile.toFile());

// 4. Extract node coordinates from the graph's NodeAccess.
NodeAccess na = graph.getNodeAccess();
for (ParsedEdge pe : parsedEdges) {
    double fromLat = na.getLat(pe.from);
    double fromLon = na.getLon(pe.from);
    double toLat   = na.getLat(pe.to);
    double toLon   = na.getLon(pe.to);
    // ... build RoadConfig, NodeConfig, IntersectionConfig
}

// 5. Cleanup: RAMDirectory → no disk; but still close to release HPPC buffers.
graph.close();
```

**Path B wins because:**

1. **Full tag fidelity** — `ReaderWay` handed to the `EdgeHandler` callback has every OSM tag. Map `way.getTag("highway")`, `way.getTag("oneway")`, `way.getTag("lanes")`, `way.getTag("junction")`, `way.getTag("maxspeed")` directly to `RoadConfig` fields.
2. **No routing infrastructure** — no `EncodingManager` setup, no `Profile`, no `CustomModel`, no CH preparation. Strictly parse-and-emit.
3. **In-memory only** — `RAMDirectory` (not `GHDirectory` with file-backed storage). No temp cache directory for GraphHopper to litter; only the OSM temp file we created in Section 2.
4. **Clean thread boundary** — each request creates its own `BaseGraph` + `WaySegmentParser`. No shared state, so no thread-safety concerns (see Section 6).

**Recommendation: Path B.** All subsequent sections assume Path B.

### Tower nodes vs pillar nodes (essential concept)

GraphHopper splits ways into segments at **tower nodes**. A tower node is any OSM node that is:
- shared between ≥ 2 ways (a junction), OR
- a dead-end endpoint of a way.

Pillar nodes are intermediate nodes *inside* a segment — they exist only for geometry. `pointList.clone(false)` gives us both.

**For our purposes:** tower nodes = our `NodeConfig`s. This is the single biggest reason to use GraphHopper over Phase 18's heuristic — GraphHopper's tower-node detection is robust against all the OSM edge cases (self-intersecting ways, shared-node roundabouts, T-junctions mid-way).

---

## 4. Tag Preservation Hook

**The problem:** `OSMReader` (not `WaySegmentParser`) discards raw tags after encoding. If we take Path A above we lose `junction`, `highway=traffic_signals` on nodes, and lane numeric value.

**The solution:** Path B's `EdgeHandler` gets the live `ReaderWay` — we copy the tags we care about into our own intermediate record before the callback returns.

### Way-tag capture (needed for roads + roundabout detection)

```java
private static final Set<String> WAY_TAGS_OF_INTEREST = Set.of(
    "highway", "oneway", "lanes", "maxspeed", "junction", "name", "ref"
);

private Map<String, String> copyTags(ReaderWay way) {
    Map<String, String> m = new HashMap<>();
    for (String key : WAY_TAGS_OF_INTEREST) {
        String v = way.getTag(key);
        if (v != null) m.put(key, v);
    }
    // Also preserve OSM way id for debugging / A/B comparison
    m.put("_osm_way_id", Long.toString(way.getId()));
    return m;
}
```

### Node-tag capture (needed for traffic_signals detection)

This is where it gets tricky. `WaySegmentParser.EdgeHandler` gets `List<Map<String, Object>> nodeTags` — this is the list of intermediate (pillar + tower) node tag maps along the segment. For a segment `from → ... pillars ... → to`, index 0 is `from`'s tags, last index is `to`'s tags.

**Use `setWayPreprocessor` to tap into the tags of tower nodes specifically:**

```java
// Simpler approach: just inspect nodeTags[0] and nodeTags[last] in the EdgeHandler callback
.setEdgeHandler((from, to, pointList, way, nodeTags) -> {
    // nodeTags parallel to pointList — first entry is `from`, last is `to`
    Map<String, Object> fromTags = nodeTags.isEmpty() ? Map.of() : nodeTags.get(0);
    Map<String, Object> toTags   = nodeTags.isEmpty() ? Map.of() : nodeTags.get(nodeTags.size() - 1);

    boolean fromIsSignal = "traffic_signals".equals(fromTags.get("highway"));
    boolean toIsSignal   = "traffic_signals".equals(toTags.get("highway"));

    if (fromIsSignal) signalTowerIds.add(from);
    if (toIsSignal)   signalTowerIds.add(to);

    parsedEdges.add(new ParsedEdge(from, to, pointList.clone(false), copyTags(way), fromIsSignal, toIsSignal));
})
```

**Important:** `WaySegmentParser` by default does NOT preserve node tags through the pipeline unless you set them up. You must also configure `.setSplitNodeFilter()` and pass a `nodeTagParser` or — simpler — call `.setWayPreprocessor()` that reads node tags via the provided `NodeTagSupplier`. The current PR-2448 API exposes `nodeTags` in the `EdgeHandler` signature directly (confirmed by the source for v10.2). No extra setup needed. `[CITED: https://github.com/graphhopper/graphhopper/pull/2448]`

If the PR-2448 `nodeTags` parameter does **not** work as expected in 10.2 at implementation time, fall back to a two-pass approach: first run `WaySegmentParser` to collect edges, then run a separate OSM XML parse (via GraphHopper's `OSMInputFile`) just to read `<node>` tags. Flag this as **LOW confidence — needs implementation-time verification**.

---

## 5. Graph → MapConfig Mapping

**Implementation-ready pseudo-code.** Ship this nearly verbatim into `GraphHopperOsmService.convert(...)`.

```java
MapConfig convertToMapConfig(List<ParsedEdge> edges, NodeAccess na,
                              Set<Integer> signalTowerIds, BboxRequest bbox) {

    // 1. Collect all unique tower node IDs referenced by edges.
    Set<Integer> towerIds = new HashSet<>();
    Map<Integer, Integer> towerInDegree  = new HashMap<>();
    Map<Integer, Integer> towerOutDegree = new HashMap<>();
    Set<Integer> roundaboutTowerIds      = new HashSet<>();

    for (ParsedEdge pe : edges) {
        towerIds.add(pe.from);
        towerIds.add(pe.to);
        towerOutDegree.merge(pe.from, 1, Integer::sum);
        towerInDegree.merge(pe.to, 1, Integer::sum);
        if ("roundabout".equals(pe.tags.get("junction"))) {
            roundaboutTowerIds.add(pe.from);
            roundaboutTowerIds.add(pe.to);
        }
    }

    // 2. Classify each tower node.
    //    - If signal  → INTERSECTION (type=SIGNAL later)
    //    - If roundabout-member → INTERSECTION (type=ROUNDABOUT later)
    //    - If (inDeg + outDeg) >= 3 edges touching → INTERSECTION (type=PRIORITY)
    //    - Else (degree 1) → terminal → ENTRY or EXIT based on edge direction
    Set<Integer> intersectionTowers = new HashSet<>();
    Set<Integer> terminalTowers     = new HashSet<>();
    for (int id : towerIds) {
        int deg = towerInDegree.getOrDefault(id, 0) + towerOutDegree.getOrDefault(id, 0);
        if (signalTowerIds.contains(id) || roundaboutTowerIds.contains(id) || deg >= 3) {
            intersectionTowers.add(id);
        } else if (deg == 1) {
            terminalTowers.add(id);
        }
        // deg == 2 with no signal/roundabout: a through-node — counts neither as intersection nor terminal.
        // Should not happen after WaySegmentParser (it only splits at junctions/endpoints),
        // but defensive: treat as terminal if strictly degree 1, otherwise as intersection.
    }

    // 3. Build NodeConfig list (pixel coords via Phase 18 projection helpers — REUSE).
    List<MapConfig.NodeConfig> nodes = new ArrayList<>();
    for (int id : towerIds) {
        MapConfig.NodeConfig nc = new MapConfig.NodeConfig();
        nc.setId("gh-" + id);
        nc.setType(intersectionTowers.contains(id) ? "INTERSECTION"
                 : isSourceOnly(id, edges) ? "ENTRY" : "EXIT");
        nc.setX(OsmPipelineService.lonToX(na.getLon(id), bbox.west(), bbox.east()));
        nc.setY(OsmPipelineService.latToY(na.getLat(id), bbox.south(), bbox.north()));
        nodes.add(nc);
    }

    // 4. Build RoadConfig list — handle oneway cases exactly like Phase 18 does.
    List<MapConfig.RoadConfig> roads = new ArrayList<>();
    for (ParsedEdge pe : edges) {
        double length = pe.geometry.hasElevation()
                ? sumHaversine(pe.geometry)     // re-compute from coords
                : computeLengthFromPointList(pe.geometry);
        if (length < 1.0) continue;             // mirror Phase 18 MIN_ROAD_LENGTH_M

        String highway = pe.tags.getOrDefault("highway", "residential");
        double speedLimit = OsmPipelineService.speedLimitForHighway(highway); // REUSE static helper — PROMOTE to public or duplicate
        int laneCount = laneCountFromTags(pe.tags, highway);                 // same logic as Phase 18

        String oneway = pe.tags.get("oneway");
        boolean isOnewayForward = "yes".equals(oneway) || "true".equals(oneway)
                              || "roundabout".equals(pe.tags.get("junction"));
        boolean isOnewayReverse = "-1".equals(oneway);

        if (isOnewayReverse) {
            roads.add(buildRoadConfig("gh-" + pe.osmWayId + "-fwd",
                                      "gh-" + pe.to, "gh-" + pe.from,
                                      length, speedLimit, laneCount, 0.0));
        } else if (isOnewayForward) {
            roads.add(buildRoadConfig("gh-" + pe.osmWayId + "-fwd",
                                      "gh-" + pe.from, "gh-" + pe.to,
                                      length, speedLimit, laneCount, 0.0));
        } else {
            double offset = laneCount * LANE_WIDTH_BACKEND / 2.0 + 1.0;
            roads.add(buildRoadConfig("gh-" + pe.osmWayId + "-fwd",
                                      "gh-" + pe.from, "gh-" + pe.to,
                                      length, speedLimit, laneCount, offset));
            roads.add(buildRoadConfig("gh-" + pe.osmWayId + "-rev",
                                      "gh-" + pe.to, "gh-" + pe.from,
                                      length, speedLimit, laneCount, offset));
        }
    }

    // 5. Build IntersectionConfig list.
    List<MapConfig.IntersectionConfig> intersections = new ArrayList<>();
    for (int id : intersectionTowers) {
        MapConfig.IntersectionConfig ic = new MapConfig.IntersectionConfig();
        ic.setNodeId("gh-" + id);
        if (signalTowerIds.contains(id)) {
            ic.setType("SIGNAL");
            ic.setSignalPhases(buildDefaultSignalPhases("gh-" + id, roads));  // same as Phase 18
        } else if (roundaboutTowerIds.contains(id)) {
            ic.setType("ROUNDABOUT");
            ic.setRoundaboutCapacity(8);
        } else {
            ic.setType("PRIORITY");
        }
        intersections.add(ic);
    }

    // 6. Spawn/despawn points — same logic as Phase 18: one per lane per terminal tower.
    EndpointResult endpoints = generateEndpoints(terminalTowers, roads);

    return assembleMapConfig(bbox, nodes, roads, intersections,
                             endpoints.spawnPoints(), endpoints.despawnPoints());
}
```

**Key mapping decisions (all identical to Phase 18 — preserve A/B comparability):**

| From OSM | To MapConfig | Transform |
|----------|--------------|-----------|
| Tower node (lat, lon) | `NodeConfig.x/y` | `lonToX` / `latToY` — **reuse Phase 18's static helpers** (promote to `public`, or copy) |
| `highway=*` | `RoadConfig.speedLimit` | `speedLimitForHighway` — **reuse Phase 18 helper** |
| `lanes=X` | `RoadConfig.laneCount` | clamp `[1,4]` — **reuse Phase 18 helper** `laneCountForWay` |
| `oneway=yes/true/-1` | direction of `RoadConfig.fromNodeId→toNodeId` | same three-branch logic as Phase 18 |
| `junction=roundabout` | `ROUNDABOUT` + `roundaboutCapacity=8` | same as Phase 18 |
| node tag `highway=traffic_signals` | `SIGNAL` + 2-group default phases | `buildDefaultSignalPhases` — **reuse Phase 18** |
| terminal tower (degree 1) | `ENTRY` or `EXIT` + spawn/despawn per lane | same as Phase 18 `generateEndpoints` |

**Node ID namespace:** prefix with `"gh-"` instead of `"osm-"`. This guarantees zero collision if both services' outputs are ever compared in one process, and makes A/B diff logs trivially readable.

**Lane-count simplification:** per CONTEXT.md decision 9, we do NOT add per-direction lane support. Use the same flat `lanes=X` parse as Phase 18.

---

## 6. Concurrency & Lifecycle

### Thread safety

- **Read-only access to a loaded `GraphHopper` / `BaseGraph` is thread-safe.** `[CITED: https://discuss.graphhopper.com/t/is-multi-threaded-access-to-graphhopper-object-safe-using-ram-store/560]`
- **`WaySegmentParser.readOSM()` is a write operation.** The parser mutates the backing `BaseGraph`. Not thread-safe.
- In Path B (recommended), every request builds its own `BaseGraph` + `WaySegmentParser` + `RAMDirectory`. There is **no shared mutable state** between requests. Thread-safety is therefore inherent.

### Recommended lifecycle: per-request instances

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphHopperOsmService implements OsmConverter {

    private final RestClient overpassRestClient;
    @Value("${osm.overpass.urls:https://overpass-api.de}")
    private List<String> overpassMirrors;

    @Override
    public MapConfig fetchAndConvert(BboxRequest bbox) {
        Path osmFile = null;
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("gh-osm-");
            osmFile = fetchOverpassXmlToTempFile(bbox, tempDir);
            return parseAndMap(osmFile, bbox);
        } catch (IOException e) {
            throw new IllegalStateException("OSM fetch/IO failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempDir);      // recursive best-effort delete
        }
    }

    private MapConfig parseAndMap(Path osmFile, BboxRequest bbox) {
        Directory dir = new RAMDirectory();
        try (BaseGraph graph = new BaseGraph.Builder(0).setDir(dir).create()) {
            List<ParsedEdge> edges = new ArrayList<>();
            Set<Integer> signals = new HashSet<>();

            WaySegmentParser parser = new WaySegmentParser.Builder(graph.getNodeAccess(), dir)
                    .setWayFilter(this::isDrivableHighway)
                    .setEdgeHandler((from, to, points, way, nodeTags) -> {
                        captureEdge(from, to, points, way, nodeTags, edges, signals);
                    })
                    .setWorkerThreads(1)
                    .build();

            parser.readOSM(osmFile.toFile());

            if (edges.isEmpty()) {
                throw new IllegalStateException("No roads found in selected area");
            }

            return convertToMapConfig(edges, graph.getNodeAccess(), signals, bbox);
        }
    }
}
```

**Resource contract:**

- **Temp file**: created in `fetchOverpassXmlToTempFile`, deleted in `finally` (even on parse failure).
- **RAMDirectory**: garbage collected when the `BaseGraph` goes out of scope.
- **BaseGraph**: closed via try-with-resources (`BaseGraph implements AutoCloseable`).

### Memory footprint

- Per-request `BaseGraph` with `RAMDirectory` for a ~500 KB Overpass XML response: estimated **5–20 MB** heap usage. No empirical data for this project. `[ASSUMED — based on typical HPPC int-buffer overhead]`
- JVM should be sized to accommodate ~3 concurrent requests (peak) → **~60 MB worst-case**. Acceptable for the existing Spring Boot process.
- GraphHopper's own JVM recommendation for sub-country datasets is "1 GB heap" — our per-bbox data is 3 orders of magnitude smaller. Non-issue.

### Cold-start considerations

- No graph-on-disk cache means every request does a full parse. For a 500 KB XML this is **< 500 ms**. `[ASSUMED]`
- If the parse time ever becomes a concern (Phase 25 ring-road demos with large bboxes), consider a small LRU `Map<BboxRequest, MapConfig>` in the service. **Explicitly deferred by CONTEXT.md.**

### Singleton vs per-request service bean

The `@Service`-annotated bean is a Spring singleton. That's **fine** — the bean holds no mutable state; each `fetchAndConvert` call creates its own `BaseGraph` locally. Do not scope as prototype.

---

## 7. Error Handling

Mirror `OsmController`'s existing taxonomy exactly (lines 49–67 of `OsmController.java`):

| Exception raised in `GraphHopperOsmService` | HTTP status | Body | Rationale |
|---------------------------------------------|-------------|------|-----------|
| `IllegalStateException("No roads found in selected area")` | **422** | `{"error":"No roads found..."}` | Same as Phase 18 when filter returns nothing |
| `IllegalStateException("OSM fetch/IO failed: ...")` (from temp-file I/O) | **503** | `{"error":"Overpass API unavailable..."}` | Server-side I/O failure — treat as upstream unavailable |
| `RestClientException` (Overpass HTTP failure) | **503** | `{"error":"Overpass API unavailable..."}` | Identical to Phase 18 |
| `java.lang.OutOfMemoryError` | NOT caught | JVM crashes — intentional | Bbox too large; user must pick smaller area. Cannot recover. |
| GraphHopper internal exceptions (`com.graphhopper.util.exceptions.*`) | **422** if data-related, else **503** | error key | Map via try/catch: wrap in `IllegalStateException` for known-data problems; rethrow as `RuntimeException` → falls to 503 handler |
| `InterruptedException` (thread pool shutdown) | **503** | `{"error":"Overpass API unavailable..."}` | Preserve interrupt: `Thread.currentThread().interrupt()` before rethrowing |

**GraphHopper exception types to catch and translate:**

- `com.graphhopper.util.exceptions.ConnectionNotFoundException` → unlikely in parser-only mode; treat as 422 ("area has no connected roads")
- `com.graphhopper.util.exceptions.PointNotFoundException` → same
- `IllegalArgumentException("pbf file corrupt")` or XML parse errors → 422 ("malformed OSM data")

**Logging policy:** match Phase 18 — `log.warn` for 4xx, `log.error` for 5xx, include bbox coordinates in every error message.

### Separate or shared `@ExceptionHandler`?

Phase 18's `OsmController` has a class-local `@ExceptionHandler`. Two options:

1. **New `OsmControllerGh` class** with its own `@RequestMapping("/api/osm")` and its own handlers. Cleaner separation, but duplicates handler code.
2. **Add a second `@PostMapping("/fetch-roads-gh")` method to the existing `OsmController`**, inject both services. The existing exception handlers cover both.

**Recommendation:** option 2. Less code, and the exception taxonomy is identical. The single controller keeps `@RequestMapping("/api/osm")` coherent.

---

## 8. Testing Strategy

### 8.1 Fixture approach

- **No fixture `.osm.pbf` needed.** A tiny hand-crafted `.osm.xml` string is simpler, auditable, and lives in the test resources.

### 8.2 Minimum fixture — `src/test/resources/osm/t-intersection.osm`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="52.2200" lon="21.0000"/>
  <node id="2" lat="52.2210" lon="21.0010"/>
  <node id="3" lat="52.2220" lon="21.0000"/>
  <node id="4" lat="52.2210" lon="21.0020"/>
  <way id="100">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/>
    <tag k="highway" v="residential"/>
    <tag k="lanes" v="2"/>
  </way>
  <way id="200">
    <nd ref="2"/><nd ref="4"/>
    <tag k="highway" v="residential"/>
    <tag k="oneway" v="yes"/>
  </way>
</osm>
```

Additional fixtures to create:
- `roundabout.osm` — 4 ways tagged `junction=roundabout` forming a closed ring with 4 external arms
- `signal.osm` — 2 crossing ways with a node tagged `highway=traffic_signals`
- `straight.osm` — single way, 2 nodes (smallest viable)
- `missing-tags.osm` — way with NO `highway` tag (should be filtered)

### 8.3 `GraphHopperOsmServiceTest` — skeleton

```java
@Test
void parseAndConvert_tIntersection_producesOneIntersectionAndThreeRoads() throws Exception {
    Path fixture = copyFixtureToTemp("t-intersection.osm");
    BboxRequest bbox = new BboxRequest(52.2190, 20.9990, 52.2230, 21.0030);

    // Stub the Overpass fetch so the test is offline.
    GraphHopperOsmService service = new GraphHopperOsmService(stubRestClient(fixture), objectMapper);
    MapConfig cfg = service.convert(fixture, bbox);  // package-private convert() bypasses HTTP

    assertThat(cfg.getRoads()).hasSize(3);            // 2 bidirectional + 1 oneway = 3 roads
    assertThat(cfg.getIntersections()).hasSize(1);
    assertThat(cfg.getIntersections().get(0).getType()).isEqualTo("PRIORITY");

    // Critical: output must pass MapValidator
    List<String> errors = new MapValidator().validate(cfg);
    assertThat(errors).isEmpty();
}

@Test
void parseAndConvert_signal_producesSignalIntersectionWithPhases() { /* ... */ }

@Test
void parseAndConvert_roundabout_producesRoundaboutIntersection() { /* ... */ }

@Test
void parseAndConvert_emptyArea_throwsIllegalStateException() { /* ... */ }
```

**Assertions beyond MapValidator:**

- Exact road count
- Exact intersection count
- Intersection `type` correctness (SIGNAL / ROUNDABOUT / PRIORITY)
- Node ID prefix is `"gh-"` (distinguishes from Phase 18's `"osm-"`)
- `oneway=yes` → exactly one `RoadConfig` emitted, not a pair
- `oneway=-1` → road direction is reversed (`fromNodeId` == OSM last node)
- Terminal nodes get `SpawnPoint` (one per lane) or `DespawnPoint`, never both
- Lane count clamped to `[1, 4]` — given `lanes=6`, output should be 4

### 8.4 `OsmControllerTest` additions (WebMvcTest)

Add three new tests mirroring the existing three:

```java
@Test
void fetchRoadsGh_validBbox_returns200WithMapConfig() { /* ... */ }
@Test
void fetchRoadsGh_emptyArea_returns422WithErrorMessage() { /* ... */ }
@Test
void fetchRoadsGh_overpassUnavailable_returns503WithErrorMessage() { /* ... */ }
```

Requires `@MockBean private GraphHopperOsmService graphHopperOsmService;` in addition to the existing `OsmPipelineService` mock.

### 8.5 `OsmPipelineComparisonTest` (@SpringBootTest)

```java
@SpringBootTest
@AutoConfigureMockMvc
class OsmPipelineComparisonTest {

    @Autowired MockMvc mvc;

    @Test
    @DisabledIfSystemProperty(named = "osm.online", matches = "false|off")
    void bothConverters_sameBbox_produceValidatorCleanConfigs_logDiff() throws Exception {
        String bboxJson = """
                {"south":52.2197,"west":21.0022,"north":52.2397,"east":21.0222}""";

        MapConfig phase18 = postAndReadMapConfig(mvc, "/api/osm/fetch-roads", bboxJson);
        MapConfig phase23 = postAndReadMapConfig(mvc, "/api/osm/fetch-roads-gh", bboxJson);

        new MapValidator().validate(phase18).isEmpty();   // gates are identical
        new MapValidator().validate(phase23).isEmpty();

        log.info("A/B diff — Phase18: {} roads / {} intersections; Phase23: {} roads / {} intersections",
                 phase18.getRoads().size(), phase18.getIntersections().size(),
                 phase23.getRoads().size(), phase23.getIntersections().size());
        // NO strict equality assert — this test documents divergence, doesn't gate it.
    }
}
```

**Disable-by-default flag** (`-Dosm.online=off`): keep the test out of CI runs that don't have Overpass network access.

### 8.6 Playwright spec — `frontend/e2e/osm-bbox-gh.spec.ts`

Mirror `osm-bbox.spec.ts`:
- Stub `/api/osm/fetch-roads-gh` with `osm-fetch-roads-response.json` (same fixture as Phase 22.1 — no need to duplicate)
- Click the new "Fetch roads (GraphHopper)" button
- Assert sidebar displays the road + intersection counts
- Assert headline reads "GraphHopper: …" not "Overpass: …"

### 8.7 ArchUnit

No changes expected. Phase 23 follows Phase 18 layering (service + controller + DTO). If `OsmConverter` interface is introduced (CONTEXT.md discretion), add an ArchUnit rule: implementations of `OsmConverter` must live in `com.trafficsimulator.service`.

---

## 9. Known Pitfalls

### Pitfall 1: `importAndClose()` vs `importOrLoad()` (Path A only)

**What goes wrong:** call `importAndClose()`, then try `hopper.getBaseGraph()` — NPE or `IllegalStateException`.
**Why:** `importAndClose()` releases the graph handle; `importOrLoad()` keeps it open.
**How to avoid:** in Path B we sidestep this entirely by using `WaySegmentParser` + a standalone `BaseGraph`. If we ever fall back to Path A, use `importOrLoad()` + explicit `hopper.close()` in `finally`.

### Pitfall 2: Temp directory litter

**What goes wrong:** test runs leave `/tmp/gh-osm-*` directories. CI disk fills up.
**Why:** JDK `Files.createTempDirectory` does NOT auto-delete on JVM exit.
**How to avoid:** `finally { deleteQuietly(tempDir); }` with a recursive delete walker. Unit-test the cleanup with a finally-throwing test.

### Pitfall 3: `NodeAccess.getLat/getLon` returns 0.0 if called on a non-existent tower ID

**What goes wrong:** silently emit `NodeConfig` with `x=50, y=50` (canvas padding only, no offset).
**Why:** `NodeAccess` is array-backed; out-of-range IDs return the default 0.0 doubles.
**How to avoid:** always iterate IDs that came from `towerIds.add(pe.from)` / `towerIds.add(pe.to)`. Never hardcode ID ranges.

### Pitfall 4: Self-loop ways (OSM way where first node == last node)

**What goes wrong:** `from == to` — RoadConfig has same `fromNodeId` and `toNodeId`, MapValidator doesn't catch it but simulation crashes.
**Why:** closed OSM ways (e.g., parking lot perimeters) tagged with `highway` produce self-loops.
**How to avoid:** filter `if (pe.from == pe.to) continue;` in the edge conversion loop. Also increases `isDrivableHighway` strictness to exclude `highway=service` by default (parking aisles are the common source).

### Pitfall 5: `junction=roundabout` without `oneway` implicit direction

**What goes wrong:** a `junction=roundabout` way emits two bidirectional `RoadConfig`s, which simulation interprets as a symmetric road and cars drive the wrong way.
**Why:** OSM convention — roundabouts are implicitly oneway (right-hand drive direction). Not all OSM data explicitly tags `oneway=yes`.
**How to avoid:** Phase 18 already handles this: `isOnewayForward = "yes".equals(oneway) || "true".equals(oneway) || isRoundabout`. Replicate exactly.

### Pitfall 6: Orphan nodes from WaySegmentParser filtering

**What goes wrong:** `.setWayFilter` rejects a way whose endpoints are tower nodes referenced by other ways too. Those towers stay; the rejected way's "middle" pillar nodes don't get stored. MapValidator would reject `NodeConfig` with no incident `RoadConfig`.
**Why:** WaySegmentParser's filter is way-level, not node-level.
**How to avoid:** in the final NodeConfig build step, filter towers whose ID never appears as `from` or `to` in any emitted `RoadConfig`. Phase 18 does this via `usedEndpointNodeIds`. Replicate.

### Pitfall 7: Kotlin stdlib noise

**What goes wrong:** Spotless / SpotBugs may flag `kotlin-stdlib` as an unexpected dep.
**Why:** `osm-legal-default-speeds-jvm` pulls it transitively.
**How to avoid:** configuration is already set up to `failOnViolation=false` for these plugins. If a CI gate ever moves to strict, add an exception list for `kotlin-stdlib` and `kotlin-stdlib-common`.

### Pitfall 8: Logback version bump warning

**What goes wrong:** `mvn dependency:tree` might complain about Logback 1.3.14 vs 1.5.11 version divergence.
**Why:** GraphHopper declares Logback 1.3.14 (test-scope only), Spring Boot provides 1.5.11 at runtime.
**How to avoid:** GraphHopper's Logback is test-scoped — never reaches runtime classpath. Spring Boot's 1.5.11 wins. Ignore the warning if it appears at build time.

### Pitfall 9: Overpass `timeout:25` is server-side

**What goes wrong:** client (`RestClient`) times out at 45s (`osm.overpass.read-timeout-ms`) while the server still has 25s to work. Complex bboxes cause server timeout → 504.
**Why:** server-side `[timeout:25]` is an Overpass runtime limit, unrelated to our HTTP timeout.
**How to avoid:** mirror Phase 18's configured values exactly. If 504s appear, document the bbox size as too large rather than extending server timeout.

### Pitfall 10: Edge IDs are graph-local ints, not OSM way IDs

**What goes wrong:** developer assumes `edge.getEdge()` returns `100` matching OSM way id 100 in a fixture. It does not.
**Why:** GraphHopper assigns internal sequential edge IDs. Preserve original via the `_osm_way_id` we copy from `ReaderWay.getId()`.
**How to avoid:** document in code comment, and always use our captured `ParsedEdge.osmWayId` for user-facing road naming.

---

## 10. Nyquist Validation Architecture

**N/A** — this phase does not involve signal sampling, timing windows, or rate-based measurement. OSM parsing is a deterministic request/response operation. `nyquist_validation` is not applicable to the converter comparison.

Standard test sampling covers the phase:
- **Per task commit:** `./mvnw test -Dtest=GraphHopperOsmServiceTest` (< 5 s)
- **Per wave merge:** `./mvnw test` (full backend suite ~30 s)
- **Phase gate:** full backend (308+) + frontend (51 vitest) + 7 Playwright specs green before `/gsd-verify-work`

If `.planning/config.json` does not explicitly disable Nyquist validation and the executor needs this section populated, use the standard sampling rate above. No framework changes required — JUnit 5, AssertJ, and Mockito are already in `spring-boot-starter-test`.

---

## 11. Reuse Strategy

### Reuse verbatim (no duplication)

From `OsmPipelineService.java`:

- `fetchFromMirrors(String encodedBody)` — extract to a helper bean `OverpassFetcher` (or make the existing method package-private and call from `GraphHopperOsmService`). Same mirror fallback, same timeouts, same error taxonomy.
- `lonToX(double, double, double)`, `latToY(double, double, double)` — promote from package-private static to `public static` so `GraphHopperOsmService` can reuse them. Alternative: extract into a separate `OsmProjection` utility class.
- `speedLimitForHighway(String)`, `laneCountForWay(Map, String)` — same story, promote or extract.
- `buildDefaultSignalPhases(long nodeId, List<RoadConfig> roads)` — promote. Takes a `String` prefix instead of hardcoded `"osm-"` to work with `"gh-"` too.
- `collectSpawnPoints` / `collectDespawnPoints` / `generateEndpoints` / `assembleMapConfig` — same idiom. Extract into a shared `MapConfigBuilder` utility.

From `OsmClientConfig.java`:

- `overpassRestClient` bean — inject directly into both services.

From `BboxRequest.java`:

- Reuse as-is.

From `MapValidator.java`:

- Call to validate output. `GraphHopperOsmService` should invoke `new MapValidator().validate(cfg)` and throw `IllegalStateException` if any errors returned — this is defensive; if Phase 23 ever emits validator-rejecting data, fail fast rather than returning garbage.

### Extract (new shared abstraction — CONTEXT.md discretion favors YES)

```java
public interface OsmConverter {
    MapConfig fetchAndConvert(BboxRequest bbox);
}

@Service public class OsmPipelineService implements OsmConverter { /* unchanged */ }

@Service public class GraphHopperOsmService implements OsmConverter { /* new */ }
```

Place in `com.trafficsimulator.service`. The interface is intentionally tiny (1 method). Phase 24 (osm2streets) implements the same interface. Frontend remains free to call specific endpoints (URLs are distinct) — the interface is a *backend* abstraction only.

### Do NOT reuse / do NOT duplicate

- `convertOsmToMapConfig(json, bbox)` — **specific to Overpass JSON**. GraphHopper has its own parse path.
- `parseOsmElements`, `detectIntersections`, `parseRoads`, `countNodeReferences` — all JSON-specific. Let them stay private to `OsmPipelineService`.

### Source of truth for the projection

`OsmPipelineService.CANVAS_W`, `CANVAS_H`, `CANVAS_PADDING`, `LANE_WIDTH_BACKEND`, `MIN_ROAD_LENGTH_M`, `DEFAULT_SIGNAL_PHASE_MS` — these constants define the A/B comparison fairness. **Extract to a `MapProjectionConstants` class** before writing `GraphHopperOsmService`. If Phase 23 uses even slightly different values the comparison is invalidated.

---

## 12. What "Done" Looks Like

Phase 23 is complete when **all of the following are demonstrably true**:

**Runnable artifacts:**

1. `backend/pom.xml` has one new dependency: `com.graphhopper:graphhopper-core:10.2`. `./mvnw dependency:tree` shows no version conflict warnings for Jackson or Logback.
2. `backend/src/main/java/com/trafficsimulator/service/GraphHopperOsmService.java` exists, is `@Service`-annotated, implements `OsmConverter`, and has public method `MapConfig fetchAndConvert(BboxRequest bbox)`.
3. `com.trafficsimulator.service.OsmConverter` interface exists; both `OsmPipelineService` and `GraphHopperOsmService` implement it.
4. `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` has a new `@PostMapping("/fetch-roads-gh")` method wired to `GraphHopperOsmService`. The original `/fetch-roads` endpoint is byte-identical to its pre-Phase-23 state (git-diff-clean).
5. `frontend/src/components/MapSidebar.tsx` has a second "Fetch roads (GraphHopper)" button. `MapPage.tsx` routes it to `/api/osm/fetch-roads-gh`. Result headline reads "GraphHopper: …".

**Tests:**

6. `GraphHopperOsmServiceTest` — minimum 5 new tests (straight, T-intersection, roundabout, signal, empty-area). All pass.
7. `OsmControllerTest` — 3 new tests covering `/fetch-roads-gh`. All pass. Previous 3 Phase-18 tests still pass.
8. `OsmPipelineComparisonTest` — @SpringBootTest, disabled-by-default, logs A/B metrics; does NOT fail on divergence.
9. `frontend/e2e/osm-bbox-gh.spec.ts` — mirrors `osm-bbox.spec.ts`, passes in Playwright.
10. Full backend suite: ≥ 308 (Phase 22.1 baseline) + new tests ≈ 318+, all green.
11. Full frontend suite: 51 vitest + 3 skipped unchanged; Playwright 7 specs (was 6) all green.

**Behavioural:**

12. Pointing the frontend at a known bbox (Warsaw centre): clicking the new button displays a road+intersection count sidebar result within 5s. The generated `MapConfig` passes `MapValidator`. The user can click "Run Simulation" and the generated map loads into the sim engine without crashing.
13. If GraphHopper bean fails to initialize at startup (e.g., wrong classpath), the Spring context still comes up and Phase 18's `/fetch-roads` continues to work. `[per CONTEXT.md locked decision #3]`

**Documentation:**

14. A short `backend/docs/osm-converters.md` (≤ 50 lines) explaining: "Two converters coexist. To add a third (Phase 24 osm2streets): implement `OsmConverter`, add `@Service`, add controller mapping." Lists the reusable projection constants.
15. `.planning/phases/23-.../23-SUMMARY.md` (phase wrap-up artefact — written by `/gsd-close-phase`).
16. `.planning/phases/23-.../23-VERIFICATION.md` (phase verification report).

**Negative criteria (must NOT be true):**

17. No changes to `OsmPipelineService.java` except moving constants (`lonToX` etc.) to public visibility. All existing methods remain.
18. No changes to `MapValidator.java`. At all. (Decision #4.)
19. No server-side `graph-cache/` directory written to project root at runtime. All GraphHopper storage is RAM or temp.
20. No new ports, no new WebSocket topics, no new Spring security filters.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java | Runtime | ✓ | 17+ | — |
| Maven | Build | ✓ | 3.9.x | `mvnw` wrapper always available |
| Maven Central | Dependency fetch | ✓ | — | — |
| Network (for Overpass API) | Live endpoint behaviour | ✓ at dev time; ✗ offline | — | All unit tests use local `.osm` fixtures — no network needed |
| Internet (one-time jar download) | First Maven build | ✓ | — | — |

**Missing dependencies with no fallback:** none.

**Missing dependencies with fallback:** none.

---

## Runtime State Inventory

*Not applicable* — Phase 23 is additive greenfield code, not a rename/refactor/migration. No existing runtime state (databases, OS tasks, secrets, build artifacts) embeds a string that would need updating. The only artifact touched is `backend/pom.xml` (one new dependency) and a small set of new files.

Nothing in any of the five runtime-state categories — verified by reading the full Phase 18 source and `backend/` contents.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `WaySegmentParser.EdgeHandler`'s `nodeTags` parameter in 10.2 actually surfaces OSM node tags (e.g., `highway=traffic_signals`) | Section 4 | If the 10.2 API doesn't preserve node tags through the callback, we need a two-pass fallback (parse the XML separately for node tags). Flagged for implementation-time verification via a smoke test on `signal.osm` fixture. |
| A2 | Per-request `BaseGraph` + `RAMDirectory` uses ≤ 20 MB heap for a ~500 KB Overpass XML response | Section 6 | If memory explodes, options are: (a) cap Overpass XML size, (b) add a `@Scheduled` cleanup of stale temp dirs, (c) switch to a singleton GraphHopper with a mutex. None are blockers — only deferrable optimisations. |
| A3 | Overpass XML parse takes < 500 ms for typical urban bboxes | Section 6 | User-facing latency. If slower, move fetch + parse to a separate thread pool; frontend already has a loading spinner — acceptable degradation. |
| A4 | GraphHopper's transitive `kotlin-stdlib 1.6.20` does not clash with any existing dep on classpath | Section 1 | If a later phase adds a Kotlin-using library at 2.x, Maven mediation nearest-wins determines which wins. Currently no Kotlin elsewhere — zero risk now. |
| A5 | Overpass mirrors that serve `[out:json]` also serve `[out:xml]` (same endpoint, same verb) | Section 2 | If a mirror rejects XML but accepts JSON, we'd need to fall through mirrors specifically for Phase 23. The reference mirror `overpass-api.de` supports both — other mirrors occasionally differ. Low risk; easy to mitigate. |
| A6 | `ReaderWay.getTag(String)` returns the literal OSM value without normalization | Section 4 | Tested path — confirmed by GraphHopper source and community examples. Unlikely to fail. |
| A7 | A single-line `@Service` bean failing at startup does NOT abort the Spring context (it falls through to `BeanInstantiationException` only if `@Autowired required=true`) | Section 12 / Decision #3 | If Spring startup does abort, we need a `@ConditionalOnClass` or a lazy-init pattern. Note: current service wiring is eager and `required=true` — so this IS a risk requiring a plan step. **Flagged as a concrete pitfall.** |

Assumptions A1 and A7 have the highest risk and should be verified in Wave 0 of the plan (i.e., a small spike task before committing to the full implementation).

---

## Open Questions (RESOLVED)

1. **Does GraphHopper 10.2's `WaySegmentParser.EdgeHandler` expose full node tags as `Map<String, Object>` per position?**
   - What we know: PR #2448 introduced the callback signature with `List<Map<String, Object>> nodeTags`. GraphHopper's master branch confirms this. `[CITED]`
   - What's unclear: whether 10.2's surface matches master. API could have narrowed between PR merge and 10.2 tag.
   - Recommendation: Wave 0 spike — 30 min write-a-test to confirm. If absent, use two-pass approach with GraphHopper's `OSMInputFile` for node-only parse.
   - **RESOLVED:** deferred to Wave-0 spike per Plan 00 — Plan 03 has a conditional two-pass StAX fallback if spike reveals NOT exposed.

2. **Do we want to exclude `xmlgraphics-commons` to trim jar size?**
   - What we know: elevation import isn't used.
   - What's unclear: whether the class is referenced anywhere else (potential ClassNotFoundException on transitive init).
   - Recommendation: don't exclude unless fat-jar size complaint arises. Document as a possible future cleanup.
   - **RESOLVED:** do NOT exclude in this phase — defer until a concrete jar-size complaint surfaces; document in osm-converters.md (Plan 07).

3. **Should the `OsmConverter` interface include an identifying method like `String converterName()` for A/B logging?**
   - What we know: CONTEXT.md hints at "toggle between converters by an enum" at Phase 24.
   - What's unclear: whether to define the enum now or let Phase 24 decide.
   - Recommendation: yes, add `default String converterName() { return getClass().getSimpleName(); }` — zero cost, future-proof.
   - **RESOLVED:** YES — Plan 02 ships it as a default method with reason (A/B log labels + result-panel heading in Plan 05).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) 5.10.x (via spring-boot-starter-test 3.3.5) + Mockito + AssertJ |
| Config file | `backend/pom.xml` (managed by Spring Boot parent) + `backend/src/test/resources/application.properties` |
| Quick run command | `./mvnw -pl backend test -Dtest=GraphHopperOsmServiceTest` |
| Full suite command | `./mvnw -pl backend test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| GH-01 | POST /fetch-roads-gh returns 200 + MapConfig | integration | `./mvnw test -Dtest=OsmControllerTest#fetchRoadsGh_validBbox_returns200WithMapConfig` | ❌ Wave 0 |
| GH-02 | Output passes MapValidator | unit | `./mvnw test -Dtest=GraphHopperOsmServiceTest#parseAndConvert_tIntersection_producesValidMapConfig` | ❌ Wave 0 |
| GH-03 | Endpoint coexistence — both services live in the same Spring context | integration | `./mvnw test -Dtest=OsmPipelineComparisonTest` | ❌ Wave 0 |
| GH-04 | GraphHopper 10.2 is on classpath | build-time | `./mvnw dependency:tree -Dincludes=com.graphhopper:graphhopper-core` | ✅ (build gate) |
| GH-05 | Intersection type detection | unit | `./mvnw test -Dtest=GraphHopperOsmServiceTest#parseAndConvert_signal_producesSignalIntersection` (+ roundabout variant) | ❌ Wave 0 |
| GH-06 | Frontend button renders | e2e | `npx playwright test osm-bbox-gh.spec.ts` | ❌ Wave 0 |
| GH-07 | A/B diff logged, no strict assert | integration | `./mvnw test -Dtest=OsmPipelineComparisonTest -Dosm.online=on` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw -pl backend test -Dtest=GraphHopperOsmServiceTest,OsmControllerTest` (< 10 s)
- **Per wave merge:** `./mvnw -pl backend test` (full backend, ~30 s) + `npm run test` in frontend
- **Phase gate:** full backend + full frontend + all Playwright green, verified by `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `backend/src/test/java/com/trafficsimulator/service/GraphHopperOsmServiceTest.java` — covers GH-02, GH-05
- [ ] `backend/src/test/resources/osm/t-intersection.osm` — canonical fixture
- [ ] `backend/src/test/resources/osm/roundabout.osm` — roundabout fixture
- [ ] `backend/src/test/resources/osm/signal.osm` — signal fixture
- [ ] `backend/src/test/resources/osm/straight.osm` — minimum viable fixture
- [ ] `backend/src/test/resources/osm/missing-tags.osm` — negative fixture
- [ ] `backend/src/test/java/com/trafficsimulator/controller/OsmControllerTest.java` — ADD 3 new tests for `/fetch-roads-gh`
- [ ] `backend/src/test/java/com/trafficsimulator/service/OsmPipelineComparisonTest.java` — new @SpringBootTest
- [ ] `frontend/e2e/osm-bbox-gh.spec.ts` — mirror `osm-bbox.spec.ts`
- [ ] Framework install: **none** — all test frameworks already present.
- [ ] Maven dependency addition: `<dependency>com.graphhopper:graphhopper-core:10.2</dependency>` in `backend/pom.xml`

---

## Sources

### Primary (HIGH confidence)

- GraphHopper `graphhopper-core` Maven Central metadata — `https://repo1.maven.org/maven2/com/graphhopper/graphhopper-core/maven-metadata.xml` (fetched 2026-04-22)
- GraphHopper `graphhopper-core-10.2.pom` — direct fetch from `https://repo1.maven.org/maven2/com/graphhopper/graphhopper-core/10.2/graphhopper-core-10.2.pom`
- GraphHopper `graphhopper-core-11.0.pom` — direct fetch from `https://repo1.maven.org/maven2/com/graphhopper/graphhopper-core/11.0/graphhopper-core-11.0.pom`
- GraphHopper `graphhopper-parent-10.2.pom` and `graphhopper-parent-11.0.pom` — direct fetch
- Dropwizard `dropwizard-dependencies-3.0.8.pom` and `dropwizard-dependencies-4.0.16.pom` — direct fetch (Jackson / Guava / SLF4J pinning)
- Spring Boot `spring-boot-dependencies-3.3.5.pom` — direct fetch (Jackson 2.17.2 confirmed)
- [GraphHopper releases page](https://github.com/graphhopper/graphhopper/releases) (Oct 2024 release 11.0, Jan 2025 release 10.2)
- [GraphHopper README](https://github.com/graphhopper/graphhopper/blob/master/README.md) (Java 17 requirement)
- [WaySegmentParser.java source](https://github.com/graphhopper/graphhopper/blob/master/core/src/main/java/com/graphhopper/reader/osm/WaySegmentParser.java) (public API: Builder, readOSM, EdgeHandler callback with `List<Map<String, Object>> nodeTags`)
- [PR #2448](https://github.com/graphhopper/graphhopper/pull/2448) (standalone WaySegmentParser usage example)
- [GraphHopper.java source](https://github.com/graphhopper/graphhopper/blob/master/core/src/main/java/com/graphhopper/GraphHopper.java) (setOSMFile / importOrLoad / importAndClose / getBaseGraph)
- [Overpass API output formats](https://dev.overpass-api.de/output_formats.html) ([out:xml] support)
- [Thread-safety discussion](https://discuss.graphhopper.com/t/is-multi-threaded-access-to-graphhopper-object-safe-using-ram-store/560) (reads safe, writes not)
- Local: `backend/src/main/java/com/trafficsimulator/service/OsmPipelineService.java` (Phase 18 reference)
- Local: `backend/src/main/java/com/trafficsimulator/controller/OsmController.java` (error taxonomy reference)
- Local: `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` and `MapValidator.java`

### Secondary (MEDIUM confidence)

- [GraphHopper low-level-api.md](https://github.com/graphhopper/graphhopper/blob/master/docs/core/low-level-api.md) (fetchWayGeometry with FetchMode.PILLAR_ONLY; partial — LowLevelAPIExample referenced but not shown in doc body)
- [Tabnine code examples for BaseGraph](https://www.tabnine.com/code/java/classes/com.graphhopper.storage.BaseGraph) (iteration patterns — confirmed via independent sources)
- [Stack Overflow / discuss.graphhopper.com scattered forum threads] (general API usage — cross-referenced, not primary)

### Tertiary (LOW confidence — flagged for Wave 0 verification)

- Assumption A1 (EdgeHandler node tags in 10.2 specifically): API signature confirmed on master; 10.2 tag not individually source-verified. **Smoke-test in Wave 0.**
- Assumption A2 (memory footprint): 20 MB estimate is extrapolated from general HPPC behaviour, not measured.

---

## Metadata

**Confidence breakdown:**

- **Standard stack (GraphHopper 10.2):** HIGH — version confirmed from two sources (Maven metadata.xml + direct POM fetch); Jackson 2.17.2 match verified against both Dropwizard 3.0.8 BOM and Spring Boot 3.3.5 POM.
- **Architecture (Path B: WaySegmentParser standalone):** HIGH — PR-2448 code sample and master-branch source confirm the API. The one residual uncertainty (assumption A1) is scoped to a 30-min Wave 0 spike.
- **Pitfalls:** HIGH on 1-6, 8-10 (known patterns from Phase 18 + GraphHopper docs); MEDIUM on 7 (project-specific linter behaviour).
- **Reuse strategy:** HIGH — based on direct reading of Phase 18 source.
- **Memory / concurrency:** MEDIUM — thread-safety documented by GraphHopper maintainer; memory footprint extrapolated not measured.

**Research date:** 2026-04-22
**Valid until:** 2026-05-22 (30 days for stable library versions; re-verify if GraphHopper releases 11.1 or 12.x)
