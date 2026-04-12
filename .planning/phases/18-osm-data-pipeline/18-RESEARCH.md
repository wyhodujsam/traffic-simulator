# Phase 18: OSM Data Pipeline - Research

**Researched:** 2026-04-12
**Domain:** OSM Overpass API + Java backend converter + MapConfig integration
**Confidence:** HIGH (codebase read in full; OSM API format verified against official docs)

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| OSM-01 | Backend pobiera dane drogowe z Overpass API dla wybranego bbox | Overpass QL query format verified; Spring RestClient confirmed available (Boot 3.2+) |
| OSM-02 | Konwerter zamienia OSM way/node na MapConfig (Road, Lane, Intersection) | Full MapConfig schema read from codebase; OSM JSON structure verified from official docs |
| OSM-03 | Konwerter rozpoznaje typy skrzyżowań z tagów OSM (traffic_signals→SIGNAL, roundabout→ROUNDABOUT) | Tag semantics verified from OSM wiki; existing IntersectionType enum supports SIGNAL/ROUNDABOUT/PRIORITY |
| OSM-04 | Konwerter wykrywa liczbę pasów z tagu lanes=X | Tag `lanes=*` confirmed as standard OSM tag; fallback heuristics documented per highway type |
</phase_requirements>

---

## Summary

Phase 18 is a pure backend phase: a new Spring service fetches OSM road data from the Overpass API for a user-selected bounding box, then converts the raw way/node JSON into a valid `MapConfig` object — the same format the existing simulation engine already understands.

The MapPage (Phase 17) is already built and has a placeholder `handleFetchRoads` stub. Phase 18 replaces that stub with a real backend endpoint and wires up the OSM pipeline. Phase 19 will close the loop by loading the generated `MapConfig` into the engine and starting the simulation.

The critical architectural constraint: the converter output must pass `MapValidator.validate()` without errors. The validator enforces: non-null id, at least one node, at least one road, `laneCount` 1–4, positive length and speedLimit, and SIGNAL intersections must have non-empty `signalPhases`. Any OSM-derived config that fails these rules must be silently filtered or corrected before returning from the service.

**Primary recommendation:** Build the Overpass API client and OSM converter as a single `OsmPipelineService` class. Keep coordinate normalization (WGS84 → pixel) as a pure-function static helper. The service is entirely additive — zero changes to existing engine code are required.

---

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot `RestClient` | 3.3.5 (managed) | HTTP call to Overpass API | Built into Spring Boot 3.2+, synchronous, fluent API, replaces RestTemplate. No new dep needed. |
| Jackson `ObjectMapper` | 2.17.x (managed) | Parse Overpass JSON response | Already in classpath via `spring-boot-starter-web`. Auto-wired by Spring. |
| Java `Math` (stdlib) | JDK 17 | Haversine distance, Mercator projection | No external library needed for coordinate math at this scale. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `@RestController` + `@RequestBody` | Spring Boot 3.3.x | Expose POST endpoint for bbox input | Standard Spring MVC — already in project. |
| `MapValidator` | existing | Validate generated MapConfig | Run before returning response; reject with 422 if invalid. |
| `MapLoader.loadFromConfig()` | NEW method | Load OSM-generated config into engine | Added in Phase 18 — Phase 19 calls it. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| RestClient | WebClient (reactive) | WebClient is reactive, adds Reactor dependency. RestClient is synchronous and sufficient — Overpass calls are infrequent user-triggered actions, not high-frequency events. |
| RestClient | OkHttp / Feign | Both valid, but adding a new client library is unnecessary overhead given RestClient is already managed by Spring Boot 3.2+. |
| Custom Overpass client | OSMnx library (Python) | OSMnx is Python-only; this is a Java backend. No equivalent Java OSM library is needed — raw RestClient + Jackson is sufficient. |

**Installation:** No new dependencies required. `RestClient` is part of `spring-boot-starter-web` (already in `pom.xml`).

---

## Architecture Patterns

### Recommended Package Structure

```
backend/src/main/java/com/trafficsimulator/
├── controller/
│   └── OsmController.java          # POST /api/osm/fetch-roads (NEW)
├── service/
│   └── OsmPipelineService.java     # Overpass call + OSM→MapConfig converter (NEW)
├── dto/
│   └── BboxRequest.java            # { south, west, north, east } input DTO (NEW)
├── config/
│   ├── MapConfig.java              # UNCHANGED — target output format
│   ├── MapLoader.java              # ADD loadFromConfig(MapConfig) method
│   └── MapValidator.java           # UNCHANGED — validation gate
└── engine/
    ├── command/
    │   └── SimulationCommand.java  # ADD LoadConfig record
    └── CommandDispatcher.java      # ADD handleLoadConfig() handler
```

### Pattern 1: Overpass API Query — exact query string

**What:** POST to `https://overpass-api.de/api/interpreter` with OverpassQL in the request body. The query requests ways with highway tags inside the bbox, plus all their member nodes, with full geometry.

**When to use:** Every time user clicks "Fetch Roads" with a valid bbox.

**Exact query string:**
```
[out:json][timeout:25];
(
  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$"](${south},${west},${north},${east});
);
out body;
>;
out skel qt;
```

- `[out:json]` — JSON output format (HIGH confidence, verified from official docs)
- `["highway"~"regex"]` — filter by road type; excludes service roads, footways, cycleways
- `(south,west,north,east)` — Overpass bbox order is **lat-first** (south, west, north, east) not lng-first
- `out body;` — returns ways with their tags
- `>;` — recurse down to get member nodes
- `out skel qt;` — returns node coordinates (lat/lon) sorted geographically

**Spring RestClient call pattern:**
```java
// Source: Spring Framework docs https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
@Service
@RequiredArgsConstructor
public class OsmPipelineService {

    private final RestClient restClient;

    // @Bean in a config class:
    // @Bean RestClient restClient(RestClient.Builder builder) {
    //     return builder.baseUrl("https://overpass-api.de").build();
    // }

    public MapConfig fetchAndConvert(double south, double west, double north, double east) {
        String query = buildOverpassQuery(south, west, north, east);
        String json = restClient.post()
            .uri("/api/interpreter")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
            .retrieve()
            .body(String.class);
        return convertOsmToMapConfig(json, south, west, north, east);
    }
}
```

**CRITICAL:** Overpass API accepts the query as `application/x-www-form-urlencoded` POST body with key `data=<urlencoded-query>`. Do NOT send as raw body or JSON.

### Pattern 2: OSM JSON Response Structure

**What:** The `elements` array from Overpass contains two element types mixed together.

**Node element (from `out skel qt;`):**
```json
{
  "type": "node",
  "id": 35352001,
  "lat": 52.2297,
  "lon": 21.0122
}
```

**Way element (from `out body;`):**
```json
{
  "type": "way",
  "id": 5123563,
  "nodes": [35352001, 35352002, 35352003],
  "tags": {
    "highway": "primary",
    "lanes": "4",
    "oneway": "yes",
    "name": "ul. Marszałkowska",
    "junction": "roundabout"
  }
}
```

**Key point:** Ways reference nodes by ID. Nodes provide lat/lon. Parse in two passes: first collect all nodes into a `Map<Long, LatLon>`, then process ways using that map.

### Pattern 3: OSM Tags → MapConfig Field Mapping

**Highway type → default lane count (when `lanes` tag absent):**

| `highway` value | Default laneCount | Default speedLimit (m/s) | Include? |
|----------------|-------------------|--------------------------|----------|
| `motorway` | 2 | 36.1 (130 km/h) | YES |
| `trunk` | 2 | 27.8 (100 km/h) | YES |
| `primary` | 2 | 19.4 (70 km/h) | YES |
| `secondary` | 2 | 16.7 (60 km/h) | YES |
| `tertiary` | 1 | 13.9 (50 km/h) | YES |
| `unclassified` | 1 | 11.1 (40 km/h) | YES |
| `residential` | 1 | 8.3 (30 km/h) | YES |
| `living_street` | 1 | 2.8 (10 km/h) | YES |
| `service` | 1 | 5.6 (20 km/h) | NO — filter out (car parks, alleys) |
| `footway` / `path` / `cycleway` | — | — | NO — filter out |

**Explicit `lanes=N` tag overrides the default.** Clamp to validator range 1–4: `Math.min(4, Math.max(1, parsedLanes))`.

**Intersection type detection:**

| OSM tag | Where | MapConfig type |
|---------|-------|----------------|
| `highway=traffic_signals` | on node element | `SIGNAL` |
| `junction=roundabout` | on way element | `ROUNDABOUT` — all nodes of this way become roundabout nodes |
| (shared by 2+ ways, no signal tag) | node intersection | `PRIORITY` |

**Oneway handling:**
- `oneway=yes` OR `oneway=true` OR `junction=roundabout` (implied oneway) → single direction only
- `oneway=-1` → road runs opposite to way node order → reverse node array before building road
- Default: bidirectional → generate two `RoadConfig` entries (forward + reverse) from the same OSM way

### Pattern 4: Coordinate Conversion — WGS84 → Simulation Pixels

The simulation canvas uses arbitrary pixel coordinates with an origin at (0,0). OSM provides WGS84 decimal degrees. The conversion must:
1. Normalize all coordinates to a canvas within a fixed size (e.g., 1600×1200 px)
2. Preserve relative distances (aspect ratio must match geographic aspect ratio)
3. Keep origin at (0,0) in the top-left corner

**Step 1: Haversine distance (meters between two lat/lon points):**
```java
// Source: standard Haversine formula (verified from multiple authoritative sources)
private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    final double R = 6371000; // Earth radius in meters
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat/2) * Math.sin(dLat/2)
             + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
             * Math.sin(dLon/2) * Math.sin(dLon/2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}
```

**Step 2: Linear projection within bbox (sufficient for small urban areas ≤ 5km):**

For the OSM pipeline, a simple linear projection is adequate for urban bboxes (< 5km side). Mercator projection is only required for global or continental scale — not needed here.

```java
// Source: standard linear interpolation formula
private static double lonToPixelX(double lon, double west, double east, double canvasW) {
    return (lon - west) / (east - west) * canvasW;
}
private static double latToPixelY(double lat, double south, double north, double canvasH) {
    // Y axis: north is top (y=0), south is bottom (y=canvasH) — invert lat axis
    return (north - lat) / (north - south) * canvasH;
}
```

**Canvas size recommendation:** Use 1600×1200 px as the virtual canvas. This gives ample room for typical urban areas (500m–2km bbox) while staying compatible with the existing frontend Canvas renderer (which can scroll for large maps as fixed in Phase 13).

**Road length in meters:** Compute per road segment by summing haversine distances between consecutive geometry points:
```java
double lengthMeters = 0;
for (int i = 1; i < geometryPoints.size(); i++) {
    lengthMeters += haversineMeters(
        geometryPoints.get(i-1).lat, geometryPoints.get(i-1).lon,
        geometryPoints.get(i).lat, geometryPoints.get(i).lon
    );
}
```

The `Road.length` field in `MapConfig` is in meters — this maps directly to the simulation's physics engine which expects meters. Do NOT scale to pixels; keep meters as road length.

### Pattern 5: Intersection Detection from Shared Nodes

**Algorithm:**
1. Collect all node IDs referenced by any included way into a `Map<Long, Integer> nodeRefCount`
2. Nodes with `refCount >= 2` are shared between roads → intersections
3. Nodes with `refCount == 1` AND are terminal (first or last node of their way) → spawn/despawn candidates
4. Nodes tagged `highway=traffic_signals` in the node elements set → SIGNAL intersections

```java
// Pass 1: count node references across all included ways
Map<Long, Integer> nodeRefCount = new HashMap<>();
for (OsmWay way : includedWays) {
    for (Long nodeId : way.getNodes()) {
        nodeRefCount.merge(nodeId, 1, Integer::sum);
    }
}

// Pass 2: classify nodes
Set<Long> intersectionNodeIds = new HashSet<>();
Set<Long> terminalNodeIds = new HashSet<>();
for (OsmWay way : includedWays) {
    List<Long> nodes = way.getNodes();
    long firstNode = nodes.get(0);
    long lastNode = nodes.get(nodes.size() - 1);

    for (Long nodeId : nodes) {
        if (nodeRefCount.get(nodeId) >= 2) {
            intersectionNodeIds.add(nodeId);
        }
    }
    // Terminal = endpoint of a way with only one way connected to it
    if (nodeRefCount.get(firstNode) == 1) terminalNodeIds.add(firstNode);
    if (nodeRefCount.get(lastNode) == 1) terminalNodeIds.add(lastNode);
}
```

### Pattern 6: Spawn/Despawn Point Generation

Terminal nodes (degree=1 in road graph, i.e., road endpoints at map boundary or dead ends) become spawn/despawn points. For simplicity: **all terminal nodes get both a spawn point and a despawn point** on the road they connect to. The existing engine supports this pattern — see `straight-road.json`.

```java
// For each terminal node's road — add spawn at position 0.0, despawn at position = road.length
spawnPoints.add(new SpawnPointConfig(roadId, /* laneIndex */ 0, 0.0));
despawnPoints.add(new DespawnPointConfig(roadId, /* laneIndex */ 0, road.getLength()));
// If multi-lane, add for each lane
for (int lane = 0; lane < laneCount; lane++) {
    spawnPoints.add(new SpawnPointConfig(roadId, lane, 0.0));
    despawnPoints.add(new DespawnPointConfig(roadId, lane, road.getLength()));
}
```

### Pattern 7: LoadConfig command chain (additive to existing code)

**`SimulationCommand.java` — add to sealed interface:**
```java
// Add to permits list AND as a record
record LoadConfig(MapConfig config) implements SimulationCommand {}
```

**`MapLoader.java` — add loadFromConfig method:**
```java
public LoadedMap loadFromConfig(MapConfig config) {
    List<String> errors = mapValidator.validate(config);
    if (!errors.isEmpty()) {
        throw new IllegalArgumentException("Map config validation failed: " + errors);
    }
    RoadNetwork network = buildRoadNetwork(config);   // reuses EXACT same private method
    return new LoadedMap(network, config.getDefaultSpawnRate());
}
```

**`CommandDispatcher.java` — add handleLoadConfig (mirrors handleLoadMap exactly):**
```java
// In registerHandlers():
handlers.put(SimulationCommand.LoadConfig.class,
    cmd -> handleLoadConfig((SimulationCommand.LoadConfig) cmd));

// New handler method:
private void handleLoadConfig(SimulationCommand.LoadConfig cmd) {
    if (mapLoader == null) {
        log.warn("MapLoader not available — ignoring LoadConfig command");
        return;
    }
    engine.setStatus(SimulationStatus.STOPPED);
    engine.getTickCounter().set(0);
    engine.clearAllVehicles();
    RoadNetwork oldNetwork = engine.getRoadNetwork();
    if (obstacleManager != null && oldNetwork != null) {
        obstacleManager.clearAll(oldNetwork);
    }
    if (vehicleSpawner != null) vehicleSpawner.reset();

    MapLoader.LoadedMap loaded = mapLoader.loadFromConfig(cmd.config());
    engine.setRoadNetwork(loaded.network());
    engine.setSpawnRate(loaded.defaultSpawnRate());
    if (vehicleSpawner != null) vehicleSpawner.setVehiclesPerSecond(loaded.defaultSpawnRate());
    engine.setLastError(null);
}
```

**CRITICAL:** `SimulationCommand` is a `sealed interface` with an explicit `permits` list. Adding `LoadConfig` **requires updating the permits clause** — this is a compile-time error if forgotten.

### Anti-Patterns to Avoid

- **Sending query as JSON body:** Overpass API expects `application/x-www-form-urlencoded` with key `data=`. Sending raw JSON or raw QL text returns 400 Bad Request.
- **Using `out geom;` without `>; out skel qt;`:** The `>; out skel qt;` recurse pattern fetches member nodes separately. Using `out geom;` alone on ways provides geometry inline but does not return node IDs needed for intersection detection (the `nodes` array would not contain useful IDs for cross-referencing).
- **Bbox order confusion:** Overpass uses `(south, west, north, east)`. Leaflet's `getBounds()` returns `LatLngBounds` with `.getSouth()`, `.getWest()`, `.getNorth()`, `.getEast()` — these map directly. If the bbox comes from the frontend as `[south, west, north, east]`, no reordering is needed.
- **Forgetting to handle `oneway=-1`:** This tag means the road runs **opposite** to the OSM node order. Reversing node array before coordinate extraction is required.
- **Generating SIGNAL intersections without signalPhases:** The validator enforces that SIGNAL type intersections MUST have non-empty `signalPhases`. OSM does not provide phase timing. Solution: detect as SIGNAL but generate a default 2-phase cycle (30s green each direction).
- **laneCount > 4:** MapValidator rejects `laneCount` outside 1–4. OSM highways in dense cities can have `lanes=6`. Always clamp: `Math.min(4, Math.max(1, parsedCount))`.
- **Returning empty MapConfig:** If no roads found in bbox, return a meaningful error (HTTP 422) rather than a MapConfig with empty roads — MapValidator would reject it anyway and the message would be confusing.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP client for Overpass API | Custom OkHttp/HttpURLConnection calls | Spring `RestClient` | Already in classpath via `spring-boot-starter-web`. Fluent API, Spring-managed lifecycle. |
| JSON parsing of Overpass response | Manual String parsing, regex | Jackson `ObjectMapper.readTree()` | Already in classpath. `JsonNode` tree traversal is sufficient for the simple elements array structure. |
| OSM data library | Full OSM parsing library (e.g., Osmosis, osmosis4j) | Plain Jackson parsing of Overpass JSON | Overpass returns a simple JSON envelope; no XML parsing, no protobuf, no `.osm.pbf` — a full OSM library is massive overkill. |
| Coordinate projection library | Full GIS library (JTS, GeoTools) | Pure Java math (Haversine + linear interpolation) | GeoTools is 50MB+ with complex dependencies. Linear interpolation is accurate to within meters for urban-scale bboxes. JTS is only needed for polygon operations — not required here. |
| Road deduplication | Custom graph algorithm | Simple `Map<Long, OsmNode> nodeMap` + `Map<Long, OsmWay> wayMap` | Overpass guarantees each element appears once in the response. |

**Key insight:** The Overpass JSON structure is flat and simple (array of elements with type/id/nodes/tags). All required processing can be done with plain Java collections and Jackson. No specialized library is justified.

---

## Common Pitfalls

### Pitfall 1: SIGNAL intersections without signal phases
**What goes wrong:** MapValidator throws "SIGNAL intersection X must have non-empty signalPhases". The simulation refuses to load.
**Why it happens:** OSM tags `highway=traffic_signals` on a node but provides zero information about cycle timing.
**How to avoid:** When detecting a SIGNAL intersection, always inject a default 2-phase signal cycle:
```java
// Default phases: 30s green for inbound roads group A, 30s green for group B
List<SignalPhaseConfig> defaultPhases = List.of(
    new SignalPhaseConfig(inboundRoadGroup1, 30_000L, "GREEN"),
    new SignalPhaseConfig(inboundRoadGroup2, 30_000L, "GREEN")
);
```
If the inbound road grouping is not deterministic at intersection-build time, use a simpler fallback: one phase with all connected roads as green (30s). This degrades gracefully to "always green" rather than crashing.
**Warning signs:** `IllegalArgumentException: Map config validation failed` in backend logs on POST /api/osm/fetch-roads.

### Pitfall 2: Sealed interface permits clause omission
**What goes wrong:** `java: class SimulationCommand.LoadConfig is not listed in permits clause of SimulationCommand` — compile error.
**Why it happens:** Java sealed interfaces require the permits clause to be exhaustive. Adding a new record inside the interface without updating the `permits` list on the outer interface causes a compile-time failure.
**How to avoid:** Update `permits` clause when adding `LoadConfig`.
**Warning signs:** Build fails at `mvn compile` immediately.

### Pitfall 3: Overpass rate limiting (HTTP 429 / 504)
**What goes wrong:** Overpass returns 429 Too Many Requests or times out with 504 on large queries.
**Why it happens:** The public Overpass API (overpass-api.de) has rate limits. Large bboxes with many roads can hit the 25-second timeout or the per-IP concurrency limit.
**How to avoid:**
- Add a `[timeout:25]` directive in the query
- Limit bbox size: if the area is larger than ~3km × 3km, warn the user (frontend should enforce this via bbox dimension display already built in Phase 17 `MapSidebar`)
- Catch `RestClientException` and return HTTP 503 with a user-friendly error message
**Warning signs:** `RestClientResponseException: 429` or `RestClientResponseException: 504` in logs.

### Pitfall 4: Ways with no usable geometry
**What goes wrong:** Some OSM ways have node references but those nodes are outside the bbox and thus not returned by the Overpass query. The way has `nodes: [12345, 67890]` but neither node appears in the elements.
**Why it happens:** The `>; out skel qt;` recurse fetches all member nodes of included ways, but if a node was missing from the OSM database or the query timed out partway through, gaps appear.
**How to avoid:** During way processing, skip any way where fewer than 2 node coordinates can be resolved. Log a warning with the way ID.
**Warning signs:** `NullPointerException` when accessing `nodeMap.get(nodeId)` — always null-check.

### Pitfall 5: Road length of zero or negative
**What goes wrong:** MapValidator rejects `length must be positive`. Simulation engine uses length for physics calculations; zero-length roads cause divide-by-zero in IDM.
**Why it happens:** Two OSM nodes at the same coordinate (duplicate nodes in OSM data), or floating-point precision loss.
**How to avoid:** After computing road length with Haversine, if length < 1.0 meters, skip the road segment. Also ensure the pixel coordinate start ≠ end — if they're identical after projection, skip.
**Warning signs:** Validator error `Road X length must be positive` on generated configs.

### Pitfall 6: Roundabout ways split into multiple segments
**What goes wrong:** OSM roundabouts are often mapped as a single closed circular way, but sometimes split into arcs. If the converter creates multiple `RoadConfig` entries for the arcs, the junction logic treats them as separate roads, not a roundabout.
**Why it happens:** OSM data quality varies; editors sometimes split roundabout ways.
**How to avoid:** For Phase 18 MVP, treat each `junction=roundabout` way as generating a `ROUNDABOUT` intersection at every shared node. The roundabout type on `IntersectionConfig` is what matters for simulation behavior — the exact road topology is secondary.
**Warning signs:** Many short roads all pointing to the same intersection area.

---

## Code Examples

### Building and sending the Overpass query
```java
// Source: Overpass API official docs https://wiki.openstreetmap.org/wiki/Overpass_API
private String buildOverpassQuery(double south, double west, double north, double east) {
    return String.format(
        "[out:json][timeout:25];\n" +
        "(\n" +
        "  way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)$\"]" +
        "(%f,%f,%f,%f);\n" +
        ");\n" +
        "out body;\n" +
        ">;\n" +
        "out skel qt;",
        south, west, north, east
    );
}

private String callOverpassApi(String query) {
    return restClient.post()
        .uri("/api/interpreter")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8))
        .retrieve()
        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
            (req, res) -> { throw new OsmFetchException("Overpass API error: " + res.getStatusCode()); })
        .body(String.class);
}
```

### Parsing Overpass JSON with Jackson
```java
// Source: Jackson ObjectMapper tree traversal — standard pattern
JsonNode root = objectMapper.readTree(jsonResponse);
JsonNode elements = root.get("elements");

Map<Long, OsmNode> nodeMap = new HashMap<>();
List<OsmWay> wayList = new ArrayList<>();

for (JsonNode el : elements) {
    String type = el.get("type").asText();
    long id = el.get("id").asLong();

    if ("node".equals(type)) {
        double lat = el.get("lat").asDouble();
        double lon = el.get("lon").asDouble();
        Map<String, String> tags = parseTags(el);
        nodeMap.put(id, new OsmNode(id, lat, lon, tags));
    } else if ("way".equals(type)) {
        List<Long> nodeIds = new ArrayList<>();
        for (JsonNode n : el.get("nodes")) {
            nodeIds.add(n.asLong());
        }
        Map<String, String> tags = parseTags(el);
        wayList.add(new OsmWay(id, nodeIds, tags));
    }
}

private Map<String, String> parseTags(JsonNode el) {
    Map<String, String> tags = new HashMap<>();
    JsonNode tagsNode = el.get("tags");
    if (tagsNode != null) {
        tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
    }
    return tags;
}
```

### Coordinate normalization
```java
// Linear projection for urban bbox (< 5km side) — no external library needed
private static final double CANVAS_W = 1600.0;
private static final double CANVAS_H = 1200.0;
private static final double CANVAS_PADDING = 50.0;

private double lonToX(double lon, double west, double east) {
    double usable = CANVAS_W - 2 * CANVAS_PADDING;
    return CANVAS_PADDING + (lon - west) / (east - west) * usable;
}

private double latToY(double lat, double south, double north) {
    double usable = CANVAS_H - 2 * CANVAS_PADDING;
    // Invert Y: north = top = small Y, south = bottom = large Y
    return CANVAS_PADDING + (north - lat) / (north - south) * usable;
}
```

### Default signal phase generation
```java
// Generates a 2-phase signal cycle when OSM provides no timing data
private List<MapConfig.SignalPhaseConfig> buildDefaultSignalPhases(
        List<String> inboundRoadIds) {
    if (inboundRoadIds.isEmpty()) {
        return List.of();
    }
    // Split inbound roads into two groups (alternate assignment)
    List<String> group1 = new ArrayList<>();
    List<String> group2 = new ArrayList<>();
    for (int i = 0; i < inboundRoadIds.size(); i++) {
        (i % 2 == 0 ? group1 : group2).add(inboundRoadIds.get(i));
    }
    List<MapConfig.SignalPhaseConfig> phases = new ArrayList<>();
    if (!group1.isEmpty()) {
        MapConfig.SignalPhaseConfig p1 = new MapConfig.SignalPhaseConfig();
        p1.setGreenRoadIds(group1);
        p1.setDurationMs(30_000L);
        p1.setType("GREEN");
        phases.add(p1);
    }
    if (!group2.isEmpty()) {
        MapConfig.SignalPhaseConfig p2 = new MapConfig.SignalPhaseConfig();
        p2.setGreenRoadIds(group2);
        p2.setDurationMs(30_000L);
        p2.setType("GREEN");
        phases.add(p2);
    }
    return phases;
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| RestTemplate | RestClient | Spring Boot 3.2 (Nov 2023) | RestTemplate deprecated for non-reactive use; RestClient is the replacement with identical capability |
| Manual HTTP call to Overpass | Same pattern — Overpass API itself unchanged | N/A | Overpass API has been stable since 2010 |
| Complex GIS libraries for coordinate conversion | Linear projection for urban scale | — | No library needed for < 5km areas |

**Deprecated/outdated:**
- `RestTemplate`: Still works but deprecated in Spring 6 — do not use for new code in this project.
- Overpass XML output (`[out:xml]`): Works but requires a separate XML parser. Use `[out:json]` with Jackson.

---

## Open Questions

1. **Bidirectional road IDs**
   - What we know: When generating two `RoadConfig` entries for a bidirectional OSM way (forward + reverse), both need unique IDs. Pattern: `"osm-{wayId}-fwd"` and `"osm-{wayId}-rev"`.
   - What's unclear: How does the frontend/canvas renderer handle overlapping road geometries (two roads occupying the same physical space)? Phase 17 BoundingBoxMap shows the OSM tile map; the simulation canvas is separate. Likely no visual conflict, but worth validating in Phase 19 integration testing.
   - Recommendation: Use distinct IDs with `-fwd`/`-rev` suffix. Accept visual overlap on simulation canvas for now — Phase 19 can address rendering polish.

2. **Minimum number of spawn/despawn points**
   - What we know: `MapValidator` does not enforce minimum spawn/despawn point count. The engine spawns nothing if `spawnPoints` is empty, which is valid but produces a zero-vehicle simulation.
   - What's unclear: Will users be confused if they select an area with no terminal nodes (e.g., a closed road loop)?
   - Recommendation: Log a warning if fewer than 2 terminal nodes are found. Return the MapConfig anyway — empty spawn points are valid. Document in API response.

3. **Overpass API availability**
   - What we know: `overpass-api.de` is the primary public instance, run by the OSM community. It has occasional downtime and rate limits.
   - What's unclear: Should the backend use a fallback Overpass instance (e.g., `overpass.kumi.systems`)? Not critical for Phase 18 MVP.
   - Recommendation: Make the Overpass base URL configurable via `application.properties` (`osm.overpass.url=https://overpass-api.de`) so it can be changed without code changes. Default to the primary instance.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Spring RestClient | Overpass HTTP call | ✓ | Spring Boot 3.3.5 (RestClient in Spring 6.1+) | — |
| Jackson ObjectMapper | Overpass JSON parsing | ✓ | 2.17.x (managed by Spring Boot) | — |
| Internet / Overpass API | Road data fetch | ✓ (dev machine) | overpass-api.de public | Configure alternate URL in properties |
| Maven 3.x | Backend build | ✓ | project's existing build | — |

**Missing dependencies with no fallback:** None.

**Missing dependencies with fallback:** Overpass API public instance — configurable URL fallback documented above.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ (Spring Boot Test) |
| Config file | `pom.xml` — `spring-boot-starter-test` scope test |
| Quick run command | `mvn test -pl backend -Dtest=OsmPipelineServiceTest -q` |
| Full suite command | `mvn test -pl backend -q` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| OSM-01 | Overpass API is called with correct bbox and query format | unit (mock RestClient) | `mvn test -pl backend -Dtest=OsmPipelineServiceTest#fetchRoads_callsOverpassWithCorrectQuery` | ❌ Wave 0 |
| OSM-02 | OSM JSON with ways+nodes converts to valid MapConfig | unit | `mvn test -pl backend -Dtest=OsmConverterTest#convert_basicWaysAndNodes_producesValidMapConfig` | ❌ Wave 0 |
| OSM-03 | `highway=traffic_signals` node → SIGNAL intersection; `junction=roundabout` → ROUNDABOUT | unit | `mvn test -pl backend -Dtest=OsmConverterTest#convert_detects_trafficSignals_and_roundabouts` | ❌ Wave 0 |
| OSM-04 | `lanes=3` tag produces laneCount=3; absent tag uses highway default; lanes=6 clamped to 4 | unit | `mvn test -pl backend -Dtest=OsmConverterTest#convert_laneCount_fromTagAndDefault` | ❌ Wave 0 |
| OSM-02 | Generated MapConfig passes MapValidator.validate() with empty errors | integration | `mvn test -pl backend -Dtest=OsmPipelineServiceTest#fetchAndConvert_producesValidConfig` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `mvn test -pl backend -Dtest=OsmPipelineServiceTest,OsmConverterTest -q`
- **Per wave merge:** `mvn test -pl backend -q`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `backend/src/test/java/com/trafficsimulator/service/OsmPipelineServiceTest.java` — covers OSM-01, OSM-02 (integration path)
- [ ] `backend/src/test/java/com/trafficsimulator/service/OsmConverterTest.java` — covers OSM-02, OSM-03, OSM-04
- [ ] Test fixture: sample Overpass JSON response file in `src/test/resources/osm/sample-response.json`

*(Existing test infrastructure — `spring-boot-starter-test` + Mockito — covers all needs. No new test libraries required.)*

---

## Project Constraints (from CLAUDE.md)

| Directive | Impact on Phase 18 |
|-----------|-------------------|
| Tech stack: Java 17 + Spring Boot 3.x backend | All new code in Java 17, Spring Boot 3.3.5. No Python/Node backend code. |
| RestTemplate deprecated → use RestClient | Confirmed. Use `RestClient` for Overpass HTTP call. |
| No database / no persistence | OSM-converted MapConfig is in-memory only. No caching, no file writes. |
| Spring Security NOT used | No auth on the new POST /api/osm/fetch-roads endpoint. |
| WebFlux / reactive NOT used | Use synchronous `RestClient`, not `WebClient`. |
| Maven build | `pom.xml` — no new dependencies required for Phase 18. |
| Conventions.md governs coding conventions | Follow project conventions (loaded by executor). |
| GSD workflow: no direct edits outside GSD | Research only. Implementation through GSD execute-phase. |

---

## Sources

### Primary (HIGH confidence)
- Overpass API official docs: https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL — query syntax verified
- OSM JSON format: https://wiki.openstreetmap.org/wiki/OSM_JSON — element structure for node/way verified
- Spring RestClient docs: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html — RestClient API confirmed
- Project codebase read directly: `MapConfig.java`, `MapLoader.java`, `MapValidator.java`, `CommandDispatcher.java`, `SimulationCommand.java`, `Road.java`, `Intersection.java`, `Lane.java` — all integration points verified against live code

### Secondary (MEDIUM confidence)
- OSM Key:highway wiki: https://wiki.openstreetmap.org/wiki/Key:highway — highway type hierarchy; lane defaults are community conventions, not enforced by OSM spec
- Tag:highway=traffic_signals: https://wiki.openstreetmap.org/wiki/Tag:highway%3Dtraffic_signals
- Tag:junction=roundabout: https://wiki.openstreetmap.org/wiki/Tag:junction%3Droundabout
- Overpass API by Example: https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_API_by_Example — query patterns verified

### Tertiary (LOW confidence)
- Highway lane count defaults: community convention (not normative OSM spec) — `lanes` tag is the normative source; defaults documented here are best-effort heuristics
- Overpass public instance rate limits: informally documented in community; exact limits not officially published

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries are existing project dependencies; RestClient confirmed in Spring Boot 3.2+
- Architecture: HIGH — MapConfig schema read from live code; all integration points verified
- Overpass query format: HIGH — verified from official OSM wiki
- OSM JSON structure: HIGH — verified from official OSM JSON spec page
- Lane count heuristics: MEDIUM — community conventions, not normative OSM specification
- Signal phase generation: MEDIUM — chosen approach (default 2-phase 30s) is pragmatic; timing values are arbitrary defaults

**Research date:** 2026-04-12
**Valid until:** 2026-07-12 (90 days — Overpass API and OSM tag schema are extremely stable)
