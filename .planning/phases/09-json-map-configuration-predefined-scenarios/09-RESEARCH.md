# Phase 9 Research: JSON Map Configuration & Predefined Scenarios

**Date:** 2026-03-28
**Requirements:** ROAD-05, ROAD-06, CTRL-05

---

## Q1: What MapConfig/MapLoader/MapValidator already exist? What's missing?

### Already Implemented

**MapConfig.java** — Full typed POJO with Jackson annotations:
- Top-level: `id`, `name`, `nodes`, `roads`, `intersections`, `spawnPoints`, `despawnPoints`, `defaultSpawnRate`
- `NodeConfig`: id, type (ENTRY/EXIT/INTERSECTION), x, y coordinates
- `RoadConfig`: id, name, fromNodeId, toNodeId, length, speedLimit, laneCount
- `IntersectionConfig`: nodeId, type (SIGNAL/ROUNDABOUT/PRIORITY), signalPhases (nullable)
- `SignalPhaseConfig`: greenRoadIds, durationMs, type (GREEN/YELLOW/ALL_RED)
- `SpawnPointConfig` / `DespawnPointConfig`: roadId, laneIndex, position

**MapLoader.java** — Jackson-based JSON-to-RoadNetwork builder:
- `loadFromClasspath(String resourcePath)` reads from classpath, validates via MapValidator, builds RoadNetwork
- Builds Roads with Lanes, SpawnPoints, DespawnPoints, Intersections with TrafficLights
- Wires intersection inbound/outbound road connections
- Builds TrafficLight with phases for SIGNAL intersections

**MapValidator.java** — Structural validation with 10+ checks:
- Required fields: id, nodes (non-empty), roads (non-empty)
- Unique node IDs
- Road references valid nodes (fromNodeId, toNodeId)
- Lane count 1-4, positive length, positive speedLimit
- Spawn/despawn points reference existing roads
- Intersection signal phases: non-empty for SIGNAL type, positive durations, valid road references
- Orphan intersection detection (no roads connecting)

### What's Missing

1. **No `description` field in MapConfig** — maps list endpoint returns bare IDs only, no metadata for display
2. **No topological validation** — validator does not check:
   - Reachability: can vehicles reach from spawn to despawn?
   - Orphan roads: roads disconnected from any spawn/despawn path
   - Road direction consistency at intersections
3. **No map metadata DTO** — `GET /api/maps` returns `List<String>` (bare IDs), not objects with name/description
4. **No error propagation to frontend** — `handleLoadMap` catches exceptions and logs, never sends error back to client
5. **No `defaultSpawnRate` wiring** — field exists in config but `handleLoadMap` does not apply it to spawner after load
6. **No road re-fetch trigger** — after `LOAD_MAP`, frontend needs to re-fetch `/api/roads` for new geometry; currently only fetched once on mount

---

## Q2: How does LOAD_MAP command work?

### Full Pipeline

1. **Frontend** sends `{ type: "LOAD_MAP", mapId: "straight-road" }` via STOMP to `/app/command`
2. **CommandHandler.java** (STOMP listener) maps to `SimulationCommand.LoadMap(dto.getMapId())`
3. **SimulationEngine** enqueues command into `LinkedBlockingQueue`
4. **CommandDispatcher.handleLoadMap()** processes at next tick drain:
   - Sets status to STOPPED
   - Resets tick counter to 0
   - Resets VehicleSpawner
   - Calls `mapLoader.loadFromClasspath("maps/" + mapId + ".json")`
   - Sets new RoadNetwork on engine via `engine.setRoadNetwork(network)`
   - Logs success or error (no client feedback)

### Gaps in LoadMap Flow

- **No clearAllVehicles()** — Stop handler calls it, but LoadMap only sets STOPPED without clearing vehicles from old network
- **No obstacle cleanup** — old obstacles from previous map persist if ObstacleManager isn't reset
- **No frontend notification of success/failure** — error stays in server logs
- **No auto-start after load** — user must manually click Start after selecting new map
- **REST `/api/command` endpoint missing LOAD_MAP case** — the switch in `SimulationController.postCommand()` does not handle "LOAD_MAP" type (only STOMP handler does)
- **No road geometry push** — frontend must re-fetch roads after map change, but nothing triggers this

---

## Q3: What map scenarios make sense?

### Current Maps (2)
1. **straight-road.json** — Single 800m, 3-lane road. Simplest scenario.
2. **four-way-signal.json** — 4-approach signalised intersection with green/yellow/all-red cycles.

### Proposed New Scenarios (3-4)

#### 1. highway-merge.json
- **Purpose:** Demonstrate merge congestion, MOBIL lane change under load
- **Layout:** 2-lane highway (1000m) with 1-lane on-ramp merging at 300m mark
- **Nodes:** entry_main, entry_ramp, merge_point, exit
- **Roads:** main_before (2 lanes, 300m), ramp (1 lane, 150m), main_after (3 lanes briefly then narrows back to 2)
- **Educational value:** Shows how merge points create bottleneck congestion even without obstacles
- **Complexity:** Medium — requires existing multi-road + lane change support

#### 2. phantom-jam-corridor.json
- **Purpose:** Showcase the flagship feature — phantom traffic jam emergence on an unobstructed road
- **Layout:** Long 2000m, 2-lane corridor with high spawn rate (3.0 veh/s)
- **Nodes:** entry, exit (simple straight)
- **Config:** High defaultSpawnRate, moderate speedLimit (25 m/s / 90 km/h)
- **Educational value:** With enough density, IDM creates spontaneous stop-and-go waves with zero obstacles
- **Complexity:** Low — just straight-road with tuned parameters

#### 3. construction-zone.json
- **Purpose:** Road narrowing + obstacle interaction
- **Layout:** 3-lane road (600m) with obstacle pre-placed at lane 2 position 300m, demonstrating forced lane change
- **Nodes:** entry, exit
- **Config:** Pre-placed obstacle or narrow lane section
- **Note:** Could be done with `defaultSpawnRate: 2.0` + instructions to place obstacle, or extend MapConfig with `defaultObstacles` list
- **Educational value:** Shows how a single lane closure ripples backward
- **Complexity:** Low-Medium — may need MapConfig extension for pre-placed obstacles

#### 4. signalised-grid.json (stretch goal)
- **Purpose:** 2x2 grid of signalised intersections showing green wave effects
- **Layout:** 4 intersections in grid, 8 approach roads, 8 exit roads
- **Nodes:** 4 corner entries, 4 intersection centers, 4 mid-edge exits (or pass-through)
- **Educational value:** Signal coordination, queue spillback between intersections
- **Complexity:** High — many nodes/roads, needs careful coordinate layout

### Recommendation
Implement 3 maps for Phase 9: **phantom-jam-corridor**, **highway-merge**, **construction-zone**. Defer signalised-grid to Phase 10 or backlog (it depends heavily on Phase 8 intersection polish).

---

## Q4: Frontend map selector — how to wire dropdown + LOAD_MAP

### Architecture

1. **On mount:** Fetch `GET /api/maps` to get available map list
2. **Display:** Dropdown `<select>` in ControlsPanel (not a separate MapSelector component — keep it simple)
3. **On change:** Send `{ type: "LOAD_MAP", mapId: selectedId }` via STOMP sendCommand
4. **After load:** Re-fetch `/api/roads` to get new road geometry for canvas

### Implementation Plan

**Backend changes:**
- Enhance `GET /api/maps` to return `List<MapInfoDto>` with `{ id, name, description }` instead of bare strings
- Add `MapInfoDto` record: `id`, `name` (from JSON), `description` (new optional field in MapConfig)
- Add `LOAD_MAP` case to `SimulationController.postCommand()` switch for REST parity

**Frontend changes:**
- Add `availableMaps: MapInfo[]` and `currentMapId: string | null` to Zustand store
- Fetch maps list on mount in `useWebSocket.ts` alongside roads fetch
- Add map `<select>` dropdown to `ControlsPanel.tsx` above simulation buttons
- On selection: send LOAD_MAP command, then re-fetch `/api/roads` after short delay
- Disable dropdown while simulation is RUNNING (require STOPPED state for map change)

### Re-fetch Trigger Options

**Option A: Poll after LOAD_MAP** — Send LOAD_MAP, wait 200ms, re-fetch `/api/roads`. Simple but fragile.

**Option B: Backend pushes roads in next tick state** — Add `roads` field to `SimulationStateDto` (only populated on map change). Frontend detects and updates. Clean but adds payload to every tick.

**Option C: Dedicated STOMP topic for map changes** — Backend publishes to `/topic/map-changed` after load. Frontend subscribes and re-fetches. Best separation of concerns.

**Recommendation:** Option A for simplicity. The map load is synchronous on the backend — by the time the next tick broadcasts, the new road network is already set. A 200ms delay after sending LOAD_MAP before fetching `/api/roads` is sufficient and avoids overengineering.

---

## Q5: Canvas resize after map change — how to handle different road geometries?

### Current Implementation

`computeCanvasSize(roads)` in `SimulationCanvas.tsx`:
- Scans all road endpoints (startX, startY, endX, endY)
- Adds lane width offset (half of laneCount * LANE_WIDTH_PX)
- Adds CANVAS_PADDING (60px)
- Returns computed `{ width, height }`

Canvas dimensions are derived from `roads` in Zustand store — when roads change, React re-renders with new dimensions.

### What Happens on Map Change

When `setRoads(newRoads)` is called after LOAD_MAP:
1. Zustand store updates `roads` array
2. `SimulationCanvas` re-renders — `computeCanvasSize` computes new dimensions
3. Both canvas elements (`roadsCanvas`, `vehiclesCanvas`) get new width/height attributes
4. `useEffect` with `[roadsLoaded, roads]` dependency triggers `drawRoads()` redraw
5. Vehicle animation loop uses updated road geometry

### Potential Issues

1. **roadsLoaded flag** — currently set to `true` once and never reset. After map change, the `useEffect` for drawing roads might not re-trigger if `roadsLoaded` stays `true`. Fix: set `roadsLoaded = false` before re-fetch, then `true` on new data.
2. **Stale vehicle interpolation** — vehicles from old map may briefly render at wrong positions during transition. Fix: clear snapshots in store when LOAD_MAP is sent.
3. **Canvas flicker** — brief blank canvas between old and new roads. Acceptable for map switch — not a continuous operation.

### No Fundamental Blockers

The architecture already supports dynamic canvas sizing. The main work is ensuring the re-fetch pipeline triggers correctly and stale state is cleaned up.

---

## Q6: Error handling — how to show validation errors in frontend?

### Current State

- `MapValidator` returns `List<String>` errors
- `MapLoader.loadFromClasspath()` throws `IllegalArgumentException` with error list
- `CommandDispatcher.handleLoadMap()` catches all exceptions, logs them, does nothing else
- Frontend has no error display mechanism for map loading

### Proposed Error Handling Strategy

**Backend:**
- After failed LOAD_MAP, publish error to a STOMP topic `/topic/errors` or include in next tick state
- Simplest: add `lastError: String | null` field to `SimulationStateDto` — set on load failure, cleared on next successful operation
- Alternative: dedicated `/topic/map-error` topic

**Frontend:**
- Subscribe to error topic (or read from tick state)
- Display error as dismissible banner/toast at top of canvas area
- Error format: "Failed to load map 'foo': [Map config validation failed: Road r1 references unknown fromNodeId: n99]"

**Recommendation:** Add `error: string | null` to `SimulationStateDto`. Backend sets it on failure, frontend displays as red banner. Simplest change, no new topics needed. Clear it on next successful command.

---

## Summary: Key Implementation Decisions

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| Map list endpoint | Return `List<MapInfoDto>` with id+name | Better UX than bare IDs in dropdown |
| Map selector placement | Inside ControlsPanel, above Start/Stop | No new component needed |
| Road re-fetch after load | Option A: 200ms delay then fetch | Simple, reliable enough |
| New scenarios count | 3 new maps (phantom-jam, highway-merge, construction-zone) | Meets CTRL-05 "3-4 built-in" |
| Error display | `error` field in SimulationStateDto | Minimal plumbing change |
| Validator enhancements | Defer topological validation | Structural checks are sufficient for v1 |
| Canvas resize | Works automatically via computeCanvasSize | No changes needed |
| Map change state | Requires STOPPED state | Prevents mid-simulation confusion |

---

## Files to Modify

### Backend
- `MapConfig.java` — add optional `description` field
- `SimulationController.java` — enhance `GET /api/maps` to return DTOs; add LOAD_MAP to REST handler
- `CommandDispatcher.java` — fix LoadMap: call clearAllVehicles, reset obstacles, wire defaultSpawnRate
- New: `MapInfoDto.java` — id, name, description record
- New: 3 map JSON files in `resources/maps/`

### Frontend
- `useSimulationStore.ts` — add `availableMaps`, `currentMapId`, `mapError`
- `useWebSocket.ts` — fetch maps list on mount, handle re-fetch after LOAD_MAP
- `ControlsPanel.tsx` — add map selector dropdown
- `simulation.ts` — add `MapInfo` type

### New Map Files
- `backend/src/main/resources/maps/phantom-jam-corridor.json`
- `backend/src/main/resources/maps/highway-merge.json`
- `backend/src/main/resources/maps/construction-zone.json`
