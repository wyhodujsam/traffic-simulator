# Feature Research

**Domain:** Traffic simulation web application
**Researched:** 2026-03-27 (v1.0) / 2026-04-10 (v2.0 Map Screenshot to Simulation)
**Confidence:** HIGH (v1.0 features) / MEDIUM (v2.0 features — screenshot/CORS from multiple sources; OSM extraction HIGH; LLM accuracy from academic papers)

---

## v1.0 Feature Landscape (existing milestone — preserved)

### Table Stakes

Features users expect from any credible traffic simulator. Absence of these causes immediate rejection.

| Feature | Description | Reference |
|---------|-------------|-----------|
| **Vehicle physics — car-following** | Vehicles maintain safe gaps, accelerate toward free-flow speed, decelerate smoothly when leader slows. Modelled by IDM (Intelligent Driver Model) or Nagel-Schreckenberg CA. Core of any microsimulation. | SUMO, VISSIM, IDM literature |
| **Vehicle physics — individual parameters** | Each vehicle has its own max speed, acceleration, deceleration, reaction time. Without this, traffic looks robotic and phantom jams cannot form naturally. | SUMO vehicle types |
| **Road network — multi-lane roads** | At minimum 1–4 lanes per direction. Lane boundaries must be respected; vehicles may not occupy two lanes simultaneously. | All microsimulators |
| **Road network — intersections** | Junction nodes where roads meet. Geometry defines which lane connects to which exit. | SUMO, VISSIM |
| **Traffic signals** | Green/yellow/red cycles at intersections. Configurable cycle duration and phase splits. This is the #1 intervention users want to experiment with. | SUMO tls, NetLogo models |
| **Lane changing** | Vehicles change lanes when blocked or when approaching a lane-drop. Without this, lane-level road narrowing effects cannot be observed. | SUMO MOBIL model |
| **Real-time top-down visualization** | 2D overhead view showing cars as rectangles on roads. Users cannot reason about emergent phenomena without seeing them unfold in real time. | All educational simulators |
| **Play / Pause / Speed controls** | Standard simulation controls (1x, 2x, 5x, 10x speed). Pause to inspect state. Without this, tool is unusable as an interactive tool. | NetLogo, SUMO-GUI |
| **Spawn rate control** | Control how many vehicles enter the network per unit time. This is the primary independent variable for congestion experiments. | All traffic models |
| **Statistics dashboard** | Real-time metrics: average speed, vehicle count/density, throughput (vehicles/min). Minimum viable feedback loop for experiments. | SUMO output, VISSIM |

---

### Differentiators (v1.0)

| Feature | Value Proposition | Complexity |
|---------|-------------------|------------|
| **Live obstacle placement** | Drop a blocking obstacle in any lane while simulation runs. Instantly triggers shockwave propagation — the core educational moment. SUMO requires editing XML and restarting; no web tool does this live. | Medium |
| **Road narrowing simulation** | Reduce active lanes on a road segment mid-run. Models construction zones, accidents. Demonstrates merge-induced congestion and capacity reduction. | Medium |
| **Roundabout simulation** | Circular junction with yield-on-entry rules. Distinct traffic dynamics from signalised intersections — often outperforms signals at moderate volume. | Medium-High |
| **Phantom traffic jam visualisation** | Highlight the backward-propagating density wave that forms from nothing. With correct IDM parameters this emerges naturally from random deceleration events. No tool makes this visible as a named phenomenon. | Low (emergent) + Low UI overlay |
| **JSON-configurable maps** | Users can write/share road network configs as JSON without a graphical editor. Enables reproducible experiments and community sharing. SUMO uses XML (verbose); no lightweight JSON format exists. | Low |
| **Heatmap overlays** | Colour road segments by congestion intensity over time. Shows where jams form, how they move. Strong visual signal for educational use. | Medium (v2) |
| **Right-of-way / priority roads** | Uncontrolled intersections with priority-from-right rule. Completes the intersection taxonomy: signals + roundabout + priority. | Medium |
| **Configurable signal cycles via UI** | Change green/red duration on a running simulation and observe immediate downstream effects. Most simulators require restart. | Low |

---

### Anti-Features (v1.0)

| Feature | Why It's Problematic |
|---------|----------------------|
| **3D rendering / perspective view** | 10x+ rendering complexity. No educational gain over top-down for studying flow dynamics. WebGL required — Canvas sufficient for 2D. Kills performance on mobile. |
| **AI/ML vehicle control** | Contradicts the project goal: we want to study emergent physics, not trained behaviour. ML agents mask the IDM dynamics that produce phantom jams. Enormous scope increase. |
| **Map editor (draw roads with mouse)** | High UI complexity (snap-to-grid, undo, validation). Not needed to validate simulation physics. Correctly deferred to v2; JSON config is sufficient for v1. |
| **Multiplayer / multi-user control** | WebSocket is already per-session. Shared simulation state requires conflict resolution, auth, and server-side isolation. No educational benefit over single-user. |
| **Pedestrians and cyclists** | Different physics model, different rendering, different right-of-way rules. Separate domain; scope doubles for minimal gain. |
| **Persistent storage / database** | Simulation is stateful in-memory by design. Adding a DB creates operational burden with no user-facing benefit — sessions are exploratory, not longitudinal. |
| **User authentication** | Single-user local tool. Auth adds complexity with zero value. |
| **Route planning / navigation** | GPS-style routing is a different problem domain. Vehicles in this simulator follow road topology, not optimal paths. |
| **Real-world map import (OSM)** | OpenStreetMap parsing requires significant data cleaning, coordinate projection, and topology repair. Complexity far exceeds educational value. v3 at earliest. — NOTE: This was the v1.0 assessment. v2.0 specifically targets this. |
| **Accident / crash simulation** | Collision detection requires response rules, cleanup rules, chain-reaction handling. Not necessary for congestion research; adds disturbing visual noise. |

---

## Feature Dependencies (v1.0)

```
JSON Map Config
    └── Road Network (lanes, geometry)
            ├── Traffic Signals
            │       └── Configurable Signal Cycles (UI)
            ├── Roundabout (yield rules)
            ├── Right-of-way / Priority
            └── Lane Changing (MOBIL)
                    ├── Road Narrowing
                    └── Obstacle Placement (live)

Vehicle Physics (IDM)
    ├── Individual Vehicle Parameters
    ├── Car-Following (gap maintenance)
    └── Phantom Traffic Jam (emergent — no extra code)

WebSocket Real-Time Tick
    └── Canvas Rendering (top-down)
            ├── Play/Pause/Speed Controls
            ├── Spawn Rate Control
            ├── Statistics Dashboard
            └── Heatmap Overlay (v2, needs accumulated tick data)
```

Key constraint: **Vehicle Physics** and **Road Network** must both be complete before any intersection type (signals, roundabout, priority) can be implemented. **Lane Changing** is required for Road Narrowing to have visible effect. Heatmap is a pure overlay that depends only on accumulated statistics — safe to defer.

---

## MVP Definition (v1.0)

The smallest runnable simulation that demonstrates phantom traffic jams.

**MVP = these features, nothing else:**

1. Single straight multi-lane road (no intersections)
2. IDM car-following with individual vehicle parameters
3. Spawn rate control (vehicles enter from one end, exit at other)
4. Live obstacle placement (block one lane)
5. Play / Pause / Speed controls (1x, 2x, 5x)
6. Canvas top-down rendering, vehicles as coloured rectangles
7. Basic stats: average speed + vehicle count
8. WebSocket tick delivery (backend → frontend)
9. Hardcoded map (no JSON loader yet)

**What MVP proves:** The physics engine produces realistic following behaviour and shockwave propagation. Everything else is additive.

---

## Feature Prioritization Matrix (v1.0)

| Feature | Value | Effort | Score | Priority |
|---------|-------|--------|-------|----------|
| IDM car-following physics | 5 | 2 (high) | 10 | P0 — MVP |
| Individual vehicle parameters | 4 | 5 (low) | 20 | P0 — MVP |
| Canvas top-down rendering | 5 | 3 | 15 | P0 — MVP |
| WebSocket tick delivery | 5 | 2 | 10 | P0 — MVP |
| Play/Pause/Speed controls | 5 | 4 | 20 | P0 — MVP |
| Spawn rate control | 5 | 4 | 20 | P0 — MVP |
| Obstacle placement (live) | 5 | 3 | 15 | P0 — MVP |
| Basic stats dashboard | 4 | 4 | 16 | P0 — MVP |
| Multi-lane roads + lane topology | 4 | 2 | 8 | P1 |
| Lane changing (MOBIL) | 4 | 2 | 8 | P1 |
| Road narrowing simulation | 5 | 3 | 15 | P1 |
| Traffic signals + configurable cycles | 4 | 3 | 12 | P1 |
| JSON map configuration | 4 | 4 | 16 | P1 |
| Predefined map scenarios | 3 | 4 | 12 | P1 |
| Roundabout simulation | 4 | 2 | 8 | P2 |
| Right-of-way / priority intersections | 3 | 3 | 9 | P2 |
| Phantom jam visualisation overlay | 4 | 3 | 12 | P2 |
| Heatmap congestion overlay | 3 | 2 | 6 | P3 — v2 |
| Map editor (graphical) | 2 | 1 | 2 | Backlog — v2 |

---

---

## v2.0 Feature Landscape — Map Screenshot to Simulation (NEW MILESTONE)

**Context:** User navigates to a real-world area on an embedded map, triggers capture, and the app generates a simulation from the actual road network. This section covers only the NEW features needed; the existing simulation engine (IDM, MapConfig loader, Canvas renderer, WebSocket) is already built.

### Table Stakes (v2.0)

Features the user must see for this milestone to feel complete. Missing any of these makes the feature feel half-baked.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Embedded interactive map with pan/zoom | User must navigate to their area of interest before initiating capture | LOW | Google Maps JS API v3 or Leaflet + OSM tiles; both well-established. Leaflet avoids Google billing and is CORS-safe by default |
| "Capture area" button | The core user interaction — triggers the road extraction pipeline | LOW | Button reads current map viewport bounding box coordinates |
| Road network extraction from selected area | Without actual road data the feature is a dead end | HIGH | See differentiators — OSM Overpass API is strongly preferred over screenshot + CV; see anti-features for why |
| Backend OSM-to-MapConfig converter | Bridges extracted road data to existing simulation engine format | HIGH | Converts OSM `way`/`node` JSON to existing `MapConfig` (Road, Lane, Intersection, spawn/despawn points) |
| Load generated MapConfig into existing simulation engine | Closes the loop — user came to simulate, not just detect | LOW | Existing MapConfig loader endpoint already built; just needs to be called |
| Loading/error states | Road extraction takes 1–5 seconds; network errors occur; empty results must be explained | LOW | Spinner during Overpass API call; error message if no roads found in area; retry button |

### Differentiators (v2.0)

Features that make this milestone genuinely useful vs a toy demo.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| OSM Overpass API as data source (not screenshot CV) | Deterministic, accurate, free, no CORS issues — queries the actual road graph for the visible bounding box rather than trying to detect roads from pixels | MEDIUM | Overpass API `way["highway"](bbox)` returns nodes/edges; analogous pattern achieves ~96% simulation accuracy (ChatSUMO, 2024). Screenshot CV achieves 80–93% and requires coordinate calibration |
| Lane count from OSM tags | OSM roads carry `lanes`, `oneway`, `highway` tags — enables realistic multi-lane roads without guessing from pixels | MEDIUM | `highway=primary` → 2 lanes default; explicit `lanes=4` tag → 4 lanes; maps to existing `Lane[]` model |
| Traffic signal placement from OSM | OSM `highway=traffic_signals` nodes identify signalized intersections — populates existing traffic light system with no CV required | MEDIUM | Existing `Intersection` + signal cycle model already built; just needs population from OSM node tags |
| Roundabout detection from OSM | OSM `junction=roundabout` tag identifies roundabouts explicitly — populates existing roundabout model | LOW | Existing roundabout model already built; OSM tag is unambiguous |
| Spawn/despawn point auto-placement | Places spawn points at road entry edges automatically (terminal nodes = degree-1 nodes in road graph) | MEDIUM | Heuristic: dead-end nodes + map-boundary-crossing roads become spawn/despawn; existing model supports these |
| Bounding box sync between map view and Overpass query | User pans/zooms map → bounding box auto-updates → Overpass query uses exact viewport bounds with no extra UI step | LOW | Google Maps `getBounds()` or Leaflet `getBounds()` returns lat/lng bounds; passes directly as Overpass bbox parameter |
| Preview overlay of detected roads before simulation | Lets user verify road detection before running — catches misidentified areas, missing major roads | MEDIUM | SVG/Canvas overlay on captured map tile or separate preview pane; shows road segments and detected intersections |

### Anti-Features (v2.0)

Features that seem natural but create serious problems.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Client-side screenshot with html2canvas | Seems simple — "capture what you see" | Google Maps tiles from `googleapis.com` have no CORS headers. Canvas becomes tainted. `toDataURL()` throws `SecurityError`. `useCORS: true` does not work because Google does not set `Access-Control-Allow-Origin`. Proxy workarounds are fragile and break with Maps API tile URL changes. Open GitHub issues #277, #584, #1519, #1544 in html2canvas confirm this is a persistent, unsolved problem. | Use OSM Overpass API (skips screenshot entirely) or Google Maps Static API (server-side fetch, no CORS issue) |
| GPT-4 Vision / LLM image analysis as primary road extraction | "AI reads the map" is impressive demo | 80–93% accuracy = broken simulations on 7–20% of captures. LLMs are documented to fail at precise spatial coordinate extraction (bounding box regression). Non-deterministic — same image gives different coordinates on re-run. Costly per request (~$0.01–0.05/image). Slower than Overpass API (3–10s vs 0.5–2s). Scale/coordinate calibration needed (pixel-to-meter). | Use OSM Overpass API as primary; LLM can be v3 enhancement for non-OSM user-uploaded satellite photos |
| Manual road editor in this milestone | "Let me fix the detected roads" | Scope explosion — a road editor is an entire separate milestone explicitly deferred in PROJECT.md (`Out of Scope: Edytor map`). Adds weeks. | Show detected roads as read-only overlay; let user re-capture different area if result is wrong |
| Real-time traffic data overlay from Google Maps | "Show actual current traffic jams" | Google Maps Terms of Service prohibits extracting or redistributing traffic data programmatically. Real-time traffic layer cannot be used as simulation input. | The simulation generates synthetic traffic — this is by design; frame it as "what would traffic look like under these conditions" |
| Automatic pixel-to-meter scale calibration | "AI should figure out the zoom scale from the image" | Zoom level, DPI, tile size, and viewport size all vary; pixel-to-meter conversion requires known ground resolution at each zoom level. Complex and fragile. OSM makes this moot. | OSM data carries real WGS84 coordinates; convert to simulation units using known scale factor at load time |
| Persist user-captured maps to database | "Save my maps for later" | PROJECT.md explicitly excludes persistence/database. Adds infrastructure cost with no simulation value in this milestone. | Keep in-memory; user can re-run the capture workflow; optionally allow JSON export/download of generated MapConfig |

---

## Feature Dependencies (v2.0)

```
[Map View Page — new React route]
    └──requires──> [Interactive map embed (Google Maps JS API or Leaflet)]
                       └──requires──> [API key (Google) or none (Leaflet+OSM)]

[Capture Button click]
    └──reads──> [Map viewport bounding box (getBounds())]
                    └──sends to──> [Backend Spring Boot endpoint]
                                       └──calls──> [Overpass API (free, external)]
                                                       └──returns──> [OSM road JSON (ways, nodes, tags)]

[OSM road JSON]
    └──processed by──> [OSM → MapConfig converter (new backend service)]
                            ├──uses──> [existing MapConfig schema: Road, Lane, Intersection, Node]
                            ├──uses──> [OSM tags: highway, lanes, oneway, junction, traffic_signals]
                            └──produces──> [MapConfig JSON]

[MapConfig JSON]
    └──feeds──> [Existing simulation engine (already built — no changes needed)]

[Preview overlay]
    └──requires──> [MapConfig JSON or raw road graph]
    └──enhances──> [User confidence — optional P2 feature]

[Spawn/despawn auto-placement]
    └──requires──> [Road graph with terminal nodes identified (degree=1 in graph)]
    └──part of──> [OSM → MapConfig converter]
```

### Dependency Notes

- **OSM converter is the core backend work:** Converting OSM `way`/`node` JSON to the existing `MapConfig` (Road, Lane, Intersection, RoadNetwork) is the highest-complexity single task in this milestone. It requires understanding the existing JSON schema in detail — read current map config files before implementing.
- **Map embed tiles are independent of data source:** The interactive map (for navigation/pan/zoom) can use Google Maps JS or Leaflet. The road data comes from Overpass API regardless of which tile layer is shown. Using Leaflet + OSM tiles means zero billing and zero CORS issues on the display side.
- **Existing engine needs zero changes:** The MapConfig loader and simulation engine are already built. This milestone is entirely about building the pipeline that produces a valid MapConfig. If the converter produces valid MapConfig JSON, the rest works for free.
- **Preview overlay blocks nothing:** Can be added after the simulation runs as a P2 enhancement. Show detected roads on Canvas alongside simulation start. Adds polish but does not block MVP.
- **LLM road detection conflicts with OSM approach:** They solve the same problem differently. Pick one as primary. OSM Overpass API is strongly recommended as primary. LLM can be a v3 enhancement for non-OSM images (aerial photos, custom maps).

---

## MVP Definition (v2.0)

Minimum viable feature set to prove "real road network → simulation" works end-to-end.

- [ ] New map page (new React route `/map`) with embedded Leaflet map, pan/zoom
- [ ] "Simulate this area" button reading current viewport bounding box
- [ ] Spring Boot endpoint that calls Overpass API with bbox and returns OSM road data
- [ ] OSM → MapConfig converter: roads, lanes (from `lanes`/`highway` tags), intersections (from shared nodes), spawn/despawn at terminal nodes
- [ ] Load generated MapConfig into existing simulation engine and start
- [ ] Loading spinner during Overpass call; error state if no roads found or API unreachable

### Add After Validation (v2.x)

- [ ] Preview overlay showing detected road graph before simulation starts
- [ ] Traffic signal placement from OSM `highway=traffic_signals` nodes
- [ ] Roundabout detection from OSM `junction=roundabout` tag
- [ ] Zoom-level guard (warn user if viewport is too large — too many roads to simulate meaningfully; recommend 500m × 500m area)
- [ ] JSON export/download of generated MapConfig (for user sharing/reproducibility)

### Future Consideration (v3+)

- [ ] LLM vision analysis for user-uploaded non-OSM aerial/satellite photos
- [ ] Manual road correction overlay (full map editor — explicitly deferred per PROJECT.md)
- [ ] Named area search ("simulate downtown Warsaw") using Nominatim geocoder
- [ ] Multiple saved capture sessions (requires persistence infrastructure)

---

## Feature Prioritization Matrix (v2.0)

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Embedded interactive map (Leaflet) | HIGH | LOW | P1 |
| Bounding box → Overpass API call (backend) | HIGH | LOW | P1 |
| OSM → MapConfig converter (backend) | HIGH | HIGH | P1 |
| Load generated MapConfig into simulation | HIGH | LOW (already exists) | P1 |
| Loading/error states | MEDIUM | LOW | P1 |
| Spawn/despawn auto-placement heuristic | HIGH | MEDIUM | P1 |
| Preview overlay of detected roads | MEDIUM | MEDIUM | P2 |
| Traffic signal placement from OSM tags | MEDIUM | MEDIUM | P2 |
| Roundabout detection from OSM tags | MEDIUM | LOW | P2 |
| Zoom-level / area-size guard | MEDIUM | LOW | P2 |
| JSON export of generated MapConfig | LOW | LOW | P2 |
| LLM vision fallback for uploaded photos | LOW | HIGH | P3 |
| Manual road editor | OUT OF SCOPE (next milestone) | VERY HIGH | — |

**Priority key:**
- P1: Must have for milestone launch
- P2: Should have, add when core pipeline is working
- P3: Nice to have, future milestone
- OUT OF SCOPE: Explicitly deferred

---

## MapConfig Schema Dependencies (v2.0)

The OSM-to-MapConfig converter must produce output compatible with the **existing** simulation engine's JSON format. Based on PROJECT.md and CLAUDE.md context:

**Existing MapConfig fields (must produce):**
- `roads` — array of Road objects, each with `lanes` array
- `intersections` — junction points with signal cycle configuration
- `nodes` — graph nodes with coordinate positions
- `spawnPoints` / `despawnPoints` — vehicle entry/exit positions
- `RoadNetwork` — top-level container

**OSM provides (as input):**
- `way` — road segments with geometry (list of node coordinates) and tags
- `node` — points; shared by multiple `way` objects at intersections
- `highway` tag — road type (primary, secondary, residential, etc.) → lane count heuristic
- `lanes` tag — explicit lane count (overrides highway-based heuristic)
- `oneway` tag — direction constraint
- `junction=roundabout` — roundabout identification
- `highway=traffic_signals` on `node` — signalized intersection

**Key converter logic:**
- Shared node between 2+ `way` objects → `Intersection`
- Dead-end node (appears in only 1 `way`, is the terminal node) → spawn/despawn candidate
- `highway=primary` with no `lanes` tag → default 2 lanes; `highway=residential` → 1 lane; explicit `lanes=N` overrides
- `oneway=yes` → single-direction road (do not generate reverse lane)

---

## Sources

**v1.0 sources:**
- SUMO (Simulation of Urban MObility) — sumo.dlr.de. Reference for vehicle types, traffic lights, lane-changing (MOBIL), junction types.
- Intelligent Driver Model (IDM) — Treiber, Hennecke, Helbing (2000).
- Nagel-Schreckenberg cellular automaton — discrete-time traffic model.
- NetLogo Traffic models — educational CA-based traffic models.
- VISSIM (PTV Group) — commercial microsimulator reference.
- Sugiyama et al. (2008) — circular road phantom traffic jam experiment.

**v2.0 sources:**
- Overpass API documentation — https://wiki.openstreetmap.org/wiki/Overpass_API
- OSMnx 2.1.0 release (Feb 2026) — https://osmnx.readthedocs.io/
- ChatSUMO paper (2024) — "Large Language Model for Automating Traffic Scenario Generation in Simulation of Urban MObility" — https://arxiv.org/html/2409.09040v1
- SAFE framework accuracy (2025) — https://arxiv.org/html/2502.02025v2
- html2canvas CORS issues (persistent, multiple GitHub issues) — https://github.com/niklasvh/html2canvas/issues/277
- Google Maps Geo Guidelines / Terms of Service — https://about.google/brand-resource-center/products-and-services/geo-guidelines/
- Google Maps Static API overview — https://developers.google.com/maps/documentation/maps-static/overview
- Minimalist traffic simulation using OSMnx reference — https://github.com/donjpierce/traffic
- GPT-4o vision limitations (bounding box regression weakness) — https://blog.roboflow.com/gpt-4o-vision-use-cases/
- PROJECT.md — /home/sebastian/traffic-simulator/.planning/PROJECT.md
