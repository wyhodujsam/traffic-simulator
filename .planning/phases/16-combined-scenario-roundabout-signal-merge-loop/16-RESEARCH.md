# Phase 16: Combined Scenario — Roundabout + Signal + Merge + Loop — Research

**Researched:** 2026-04-08
**Domain:** JSON map authoring for the traffic-simulator engine
**Confidence:** HIGH

---

## Summary

Phase 16 is entirely a data-authoring task. The deliverable is a single JSON file — `backend/src/main/resources/maps/combined-loop.json` — that is auto-discovered from the classpath by `MapLoader` and must pass `MapValidator` without errors. No Java or TypeScript code changes are required.

The engine already supports every feature the phase needs: ROUNDABOUT intersections (Phase 10), SIGNAL intersections (Phase 8), PRIORITY/merge intersections (Phases 8/13), multi-lane roads (Phase 2), and the existing routing and despawn mechanics (Phases 2/4). The plan (16-01-PLAN.md) already exists and contains the full topology specification. The only work left is to author the JSON precisely, validate it, and run `mvn test`.

The key risks are reference integrity errors in the JSON (wrong node/road IDs, mismatched signalPhases road refs) and topology bugs that silently break the simulation (e.g., loop roads inadvertently getting no outbound connections at a node). A Python reference-check script is already specified in the plan.

**Primary recommendation:** Author the JSON exactly as specified in 16-01-PLAN.md, run the included Python reference validator, then run `mvn test` to confirm `MapLoaderScenarioTest` and `MapValidator` accept the map.

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| MAP-01 | Combined multi-feature scenario JSON map: roundabout + signalized intersection + highway merge + loop road | JSON schema fully defined in MapConfig.java; all three intersection types (ROUNDABOUT, SIGNAL, PRIORITY) are production-validated by existing scenarios; loop topology is supported by the existing routing engine |
</phase_requirements>

---

## Standard Stack

No new libraries are needed. Phase 16 uses only existing project infrastructure.

### Core — what does the work

| Component | Location | Purpose |
|-----------|----------|---------|
| `MapConfig.java` | `backend/src/main/java/com/trafficsimulator/config/MapConfig.java` | Defines the exact JSON schema (Jackson-bound) |
| `MapLoader.java` | `backend/src/main/java/com/trafficsimulator/config/MapLoader.java` | Loads JSON from `classpath:maps/*.json`, validates, builds domain model |
| `MapValidator.java` | `backend/src/main/java/com/trafficsimulator/config/MapValidator.java` | Structural validation: node refs, road constraints, signal phases |
| `MapLoaderScenarioTest.java` | `backend/src/test/java/com/trafficsimulator/config/MapLoaderScenarioTest.java` | Existing per-scenario load tests; must pass for all existing maps + the new one |
| `IntersectionManager.java` | `backend/src/main/java/com/trafficsimulator/engine/IntersectionManager.java` | Runtime routing logic for ROUNDABOUT, SIGNAL, PRIORITY |

**No installation needed.** All components are already present.

---

## Architecture Patterns

### JSON Map Schema (from MapConfig.java — HIGH confidence)

```json
{
  "id": "string",
  "name": "string",
  "description": "string",
  "nodes": [
    { "id": "string", "type": "ENTRY|EXIT|INTERSECTION", "x": 0.0, "y": 0.0 }
  ],
  "roads": [
    {
      "id": "string", "name": "string",
      "fromNodeId": "string", "toNodeId": "string",
      "length": 0.0, "speedLimit": 0.0,
      "laneCount": 1,
      "closedLanes": [0, 1]
    }
  ],
  "intersections": [
    {
      "nodeId": "string",
      "type": "SIGNAL|ROUNDABOUT|PRIORITY",
      "intersectionSize": 0.0,
      "roundaboutCapacity": 8,
      "signalPhases": [
        { "greenRoadIds": ["road-id"], "durationMs": 20000, "type": "GREEN|YELLOW|ALL_RED" }
      ]
    }
  ],
  "spawnPoints": [{ "roadId": "string", "laneIndex": 0, "position": 0.0 }],
  "despawnPoints": [{ "roadId": "string", "laneIndex": 0, "position": 0.0 }],
  "defaultSpawnRate": 1.0
}
```

**Constraints verified from MapValidator.java:**
- `laneCount` must be 1–4 (enforced: `laneCount < 1 || laneCount > 4` → error)
- `length` must be > 0
- `speedLimit` must be > 0
- `fromNodeId` and `toNodeId` must reference defined node IDs
- SIGNAL intersections must have non-empty `signalPhases`; each phase `durationMs > 0`
- `greenRoadIds` in signal phases must reference defined road IDs
- Duplicate node IDs → error

### Intersection Type Behaviors (from IntersectionManager.java — HIGH confidence)

| Type | Behavior | Config |
|------|----------|--------|
| `ROUNDABOUT` | Circulating ring-road vehicles have priority; entry gated at 80% capacity | `intersectionSize`, `roundaboutCapacity` |
| `SIGNAL` | Traffic light cycles GREEN→YELLOW→ALL_RED per approach; vehicles stop on red | `signalPhases` with `greenRoadIds` |
| `PRIORITY` | Right-of-way check; merge (fewer inbound lanes → rightmost lane targeting) | none required |

**Pass-through nodes:** Nodes listed as `INTERSECTION` type in the `nodes` array but NOT listed in the `intersections` array are NOT treated as intersections by the engine. `wireIntersectionConnections` only processes entries from the `intersections` config array. However, roads that connect `fromNodeId` or `toNodeId` to such nodes will still have the proper coordinates computed. The safest approach for geometry-only bend nodes is to give them `type: "INTERSECTION"` in `nodes` and add them to `intersections` with `type: "PRIORITY"` — this guarantees correct routing (vehicles pass through without blocking).

### Roundabout Topology Pattern (from roundabout.json — HIGH confidence)

Ring roads connect the 4 ring nodes in a directed cycle:
- `n_ring_n → n_ring_w` (NW segment)
- `n_ring_w → n_ring_s` (WS segment)
- `n_ring_s → n_ring_e` (SE segment)
- `n_ring_e → n_ring_n` (EN segment)

Each ring segment: `length 22.0`, `speedLimit 8.3`, `laneCount 1`.

Ring node identification in code: `isRingRoad = network.getIntersections().containsKey(road.getFromNodeId())`. A road is a ring road when its `fromNode` is an intersection node. This means approach roads (from ENTRY nodes) are never ring roads. Circulating vehicles always have priority over entering vehicles.

### Signal Phase Pattern (from four-way-signal.json — HIGH confidence)

Standard 6-phase cycle for 2 opposing approaches:
```json
[
  { "greenRoadIds": ["approach-A"], "durationMs": 25000, "type": "GREEN" },
  { "greenRoadIds": ["approach-A"], "durationMs": 3000,  "type": "YELLOW" },
  { "greenRoadIds": [],             "durationMs": 2000,  "type": "ALL_RED" },
  { "greenRoadIds": ["approach-B"], "durationMs": 25000, "type": "GREEN" },
  { "greenRoadIds": ["approach-B"], "durationMs": 3000,  "type": "YELLOW" },
  { "greenRoadIds": [],             "durationMs": 2000,  "type": "ALL_RED" }
]
```

### Loop Road Pattern (research — HIGH confidence from IntersectionManager)

A "loop" is not a special engine concept. It is simply a directed road graph where:
1. No despawn point is placed on loop roads.
2. Outbound roads at each loop node include a path back into the main network.
3. Vehicles are routed randomly at intersections (`pickOutboundRoad` uses `ThreadLocalRandom`).

For a vehicle to "circulate", it just needs to re-enter an approach road to the roundabout. The engine will route it through the roundabout again → east to signal → back into the loop probabilistically.

**PRIORITY nodes as pass-through bends:** Adding loop bend nodes to the `intersections` array with `type: "PRIORITY"` and no `signalPhases` is the proven safe pattern. With only one inbound and one outbound road at a bend, there is no right-of-way ambiguity — the single inbound road always proceeds.

### Coordinate Space and Canvas Size

- RENDER_SCALE = 3 (frontend multiplies all node coordinates by 3)
- LANE_WIDTH_PX = 14 × 3 = 42px per lane (rendered width)
- CANVAS_PADDING = 60 × 3 = 180px added to max extent
- Node coordinates are in "map units" (not pixels)
- Canvas width = `ceil(maxX × 3 + 180)`, height = `ceil(maxY × 3 + 180)`

For a 1200 × 800 map-unit layout:
- Canvas = `ceil(1200 × 3 + 180)` × `ceil(800 × 3 + 180)` = 3780 × 2580px
- This is wide. The existing phantom-jam-corridor at x:2050 → 6330px causes scrolling (FIX-01 addresses this).
- For Phase 16, keep max X coordinate ≤ ~1150, max Y ≤ ~800 to keep the canvas manageable (~3630 × 2580px). The responsive layout (BUG-4 fix, Phase 14) uses CSS transform:scale to fit within the viewport.

### Classpath Auto-Discovery (from MapLoader.java + SimulationController — HIGH confidence)

Maps are loaded via `getClass().getClassLoader().getResourceAsStream(resourcePath)`. The `GET /api/maps` endpoint lists all maps registered at startup. Based on `MapLoaderScenarioTest`, each scenario is loaded by path string (`"maps/combined-loop.json"`). The existing test file tests all current maps by name; the new map will need a matching test case in `MapLoaderScenarioTest`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead |
|---------|-------------|-------------|
| Routing at intersections | Custom "which road does vehicle take" logic | Existing `IntersectionManager.pickOutboundRoad()` — random among valid candidates |
| Loop detection | Cycle detection algorithm | Simply omit despawn points on loop roads |
| Pass-through nodes | NONE intersection type (doesn't exist) | `type: "PRIORITY"` with single inbound/outbound — passes through with no contention |
| JSON validation | Manual pre-flight checking | The Python script in 16-01-PLAN.md Task 1 verify block + `MapValidator.validate()` at load time |

---

## Common Pitfalls

### Pitfall 1: Signal Phase greenRoadIds Reference Mismatch
**What goes wrong:** `signalPhases` lists a road ID that doesn't exist in `roads` → `MapValidator` throws "references unknown road", backend refuses to load the map, test fails with validation error.
**Why it happens:** Copy-paste from the road list with a typo.
**How to avoid:** After writing the JSON, run the Python reference check from the plan. Also verify: every road ID in `signalPhases` must appear in the `roads` array.
**Warning signs:** `MapValidator` error message containing "phase X references unknown road".

### Pitfall 2: Loop Bend Nodes Not in Intersections Array
**What goes wrong:** If `n_loop_se` and `n_loop_sw` are type `INTERSECTION` in `nodes` but absent from `intersections`, they have no entry in `network.getIntersections()`. `wireIntersectionConnections` builds inbound/outbound sets only for nodes in the `intersections` map. Roads connecting through these nodes will still have correct coordinates (MapLoader always uses node coords for road geometry), but `IntersectionManager.processTransfers()` will never process them — vehicles reaching the end of `r_sig_to_loop` will accumulate indefinitely (no transfer fires).
**How to avoid:** Add both loop bend nodes to `intersections` with `type: "PRIORITY"`.
**Warning signs:** Vehicles pile up at bend nodes; simulation shows growing queue at r_sig_to_loop end.

### Pitfall 3: Ring Road Direction Error
**What goes wrong:** The roundabout ring must flow counterclockwise (N→W→S→E→N in the coordinate system where Y increases downward). If ring direction is reversed, `isRingRoad` detection still works, but the visual flow looks wrong and the roundabout yield logic may behave unexpectedly.
**How to avoid:** Follow the exact pattern from roundabout.json: `n_ring_n→n_ring_w`, `n_ring_w→n_ring_s`, `n_ring_s→n_ring_e`, `n_ring_e→n_ring_n`.

### Pitfall 4: Duplicate Node IDs or Coordinate Collisions
**What goes wrong:** Using the same node ID twice → `MapValidator` "Duplicate node IDs detected". Using the same coordinates for two nodes is allowed structurally but confuses the visual (ENTRY and EXIT at same pixel renders as one point visually, which is fine).
**How to avoid:** Plan specifies separate IDs for same-coordinate entry/exit pairs (e.g., `n_north_entry` at 300,50 and `n_north_exit` at 300,50 are intentionally co-located — this is the established pattern from roundabout.json).

### Pitfall 5: Missing Test Case for New Map
**What goes wrong:** `mvn test` passes because the existing scenario tests don't test `combined-loop.json`. A structural error in the new map is only caught at runtime.
**How to avoid:** Add a test method to `MapLoaderScenarioTest` that loads `maps/combined-loop.json` and asserts: road count, intersection count (8 = 4 roundabout + merge + signal + 2 loop bends), spawn point count, and that all 3 intersection types are present.

### Pitfall 6: U-turn ID Convention Conflict
**What goes wrong:** `IntersectionManager.reverseRoadId()` uses a simple heuristic: replace `_in` with `_out` to detect U-turns. If approach roads are named `r_north_in` / `r_north_out`, the engine will correctly skip U-turns. Non-standard naming won't break anything but will allow U-turns at those nodes.
**How to avoid:** The plan uses names like `r_hwy_before`, `r_rndbt_to_sig` — these don't match the `_in`/`_out` pattern, which means U-turns at those intersections are possible. Acceptable for this scenario; no action needed.

---

## Code Examples

### Adding a Scenario Test (pattern from MapLoaderScenarioTest.java)
```java
@Test
void loadsCombinedLoop() throws IOException {
    MapLoader.LoadedMap loaded = mapLoader.loadFromClasspath("maps/combined-loop.json");

    RoadNetwork network = loaded.network();
    // 4 ring + merge + signal + 2 loop bends = 8 intersections
    assertThat(network.getIntersections()).hasSize(8);
    // Verify all 3 feature types
    assertThat(network.getIntersections().values())
        .extracting(i -> i.getType().name())
        .contains("ROUNDABOUT", "SIGNAL", "PRIORITY");
    // Verify spawn points exist
    assertThat(network.getSpawnPoints()).isNotEmpty();
    // Verify traffic light wired on signal node
    assertThat(network.getIntersections().get("n_signal").getTrafficLight()).isNotNull();
    assertThat(network.getIntersections().get("n_signal").getTrafficLight().getPhases()).hasSize(6);
}
```

### Python Reference Validator (from 16-01-PLAN.md)
```python
import json, sys
with open('backend/src/main/resources/maps/combined-loop.json') as f:
    m = json.load(f)
node_ids = {n['id'] for n in m['nodes']}
road_ids = {r['id'] for r in m['roads']}
errors = []
for r in m['roads']:
    if r['fromNodeId'] not in node_ids:
        errors.append(f'Road {r["id"]} fromNodeId {r["fromNodeId"]} missing')
    if r['toNodeId'] not in node_ids:
        errors.append(f'Road {r["id"]} toNodeId {r["toNodeId"]} missing')
for i in m['intersections']:
    if i['nodeId'] not in node_ids:
        errors.append(f'Intersection nodeId {i["nodeId"]} missing')
for sp in m['spawnPoints']:
    if sp['roadId'] not in road_ids:
        errors.append(f'SpawnPoint roadId {sp["roadId"]} missing')
for dp in m['despawnPoints']:
    if dp['roadId'] not in road_ids:
        errors.append(f'DespawnPoint roadId {dp["roadId"]} missing')
if errors:
    print('ERRORS:', errors)
    sys.exit(1)
print('PASSED')
```

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + AssertJ (Spring Boot 3.3.x test starter) |
| Config file | `backend/pom.xml` (spring-boot-starter-test managed) |
| Quick run command | `cd /home/sebastian/traffic-simulator/backend && mvn test -pl . -Dtest=MapLoaderScenarioTest -q` |
| Full suite command | `cd /home/sebastian/traffic-simulator/backend && mvn test -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| MAP-01 | combined-loop.json loads without validation errors | unit (MapLoader) | `mvn test -Dtest=MapLoaderScenarioTest` | ✅ exists, needs new test method |
| MAP-01 | All 3 intersection types present (ROUNDABOUT, SIGNAL, PRIORITY) | unit (MapLoader) | same | ✅ existing file, new assertion |
| MAP-01 | Signal traffic light wired with 6 phases | unit (MapLoader) | same | ✅ existing file, new assertion |
| MAP-01 | Loop topology: r_sig_to_loop + r_loop_bottom + r_loop_to_rndbt exist | unit (MapLoader) | same | ✅ existing file, new assertion |

### Sampling Rate
- **Per task commit:** `mvn test -Dtest=MapLoaderScenarioTest -q`
- **Phase gate:** Full suite `mvn test -q` green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] Add `loadsCombinedLoop()` test method to `MapLoaderScenarioTest.java` — covers MAP-01

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Python 3 | JSON reference validation script | check at runtime | 3.x | Skip script, rely on mvn test |
| Java 17 / Maven | `mvn test` | assumed present (previous phases ran) | 17 / 3.9.x | — |

---

## Open Questions

1. **Should n_loop_se and n_loop_sw be PRIORITY or simply omitted from the intersections array?**
   - What we know: Nodes not in the `intersections` array are not processed by `IntersectionManager.processTransfers()` — vehicles reaching the end of inbound roads to those nodes will never be transferred.
   - Recommendation: **Always add geometry-only bend nodes to `intersections` with type PRIORITY.** This is the safe, proven pattern.

2. **Does the plan's coordinate layout (max x:1150, max y:650) fit comfortably in the viewport?**
   - What we know: Canvas = `ceil(1150×3+180)` × `ceil(650×3+180)` = 3630 × 2130px. The Phase 14 responsive layout scales this with CSS transform to fit the container. The sidebar is always visible (FIX-03/BUG-3 fixed).
   - Recommendation: No action needed — the responsive layout handles oversized canvases.

---

## Sources

### Primary (HIGH confidence)
- `MapConfig.java` — definitive JSON schema (Jackson field names, types, defaults)
- `MapValidator.java` — all structural constraints enforced at load time
- `MapLoader.java` — how nodes, roads, intersections, spawn/despawn are built from JSON
- `IntersectionManager.java` — runtime routing logic, ring-road detection, roundabout yield
- `roundabout.json`, `four-way-signal.json`, `highway-merge.json` — canonical examples of each intersection type
- `MapLoaderScenarioTest.java` — how new scenario tests should be structured

### Secondary (MEDIUM confidence)
- `drawIntersections.ts` — rendering details (ROUNDABOUT group detection from node positions)
- `SimulationCanvas.tsx` — canvas sizing from max node coordinates

---

## Metadata

**Confidence breakdown:**
- JSON schema: HIGH — read directly from MapConfig.java and existing maps
- Intersection behavior: HIGH — read directly from IntersectionManager.java
- Loop topology: HIGH — derived from existing routing logic (no special loop support needed)
- Test patterns: HIGH — read directly from MapLoaderScenarioTest.java

**Research date:** 2026-04-08
**Valid until:** 2026-05-08 (stable — no engine changes expected)
