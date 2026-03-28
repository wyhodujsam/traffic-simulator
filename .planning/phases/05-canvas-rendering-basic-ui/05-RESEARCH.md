# Phase 5 Research: Canvas Rendering & Basic UI

**Date:** 2026-03-28
**Requirements:** VIS-01, VIS-02, VIS-03, CTRL-01, CTRL-02, CTRL-03, CTRL-04, STAT-01, STAT-02, STAT-03

---

## 1. Existing Frontend Code Audit

### Current state
The frontend is a minimal React 18 + TypeScript + Vite scaffold from Phase 1. It connects to the backend via WebSocket and logs tick data — no visual rendering exists yet.

### Components
- **`App.tsx`** — Displays tick count and raw JSON of last tick. Pure debug view, will be replaced entirely.
- **`main.tsx`** — Standard React 18 `createRoot` entry point with `StrictMode`. No changes needed.

### Hooks
- **`useWebSocket.ts`** — STOMP client via `@stomp/stompjs` + `sockjs-client`. Connects to `/ws`, subscribes to `/topic/simulation`, calls `useSimulationStore.getState().setTick(data)` on each message. Returns `clientRef` — this ref is needed for sending commands (publish to `/app/command`).

### Store
- **`useSimulationStore.ts`** — Zustand store with `TickDto` type (`{ tick: number; timestamp: number }`). Only tracks `lastTick` and `tickCount`. **Must be expanded** to match `SimulationStateDto` shape (vehicles, stats, status, previous snapshot for interpolation).

### Types
- `TickDto` — outdated Phase 1 shape. Must be replaced with proper TypeScript interfaces matching the backend DTOs.

### Dependencies (package.json)
- Runtime: `react`, `react-dom`, `@stomp/stompjs`, `sockjs-client`, `zustand`
- Dev: `vitest`, `@testing-library/react`, `@testing-library/jest-dom`, `jsdom`, `typescript ~5.6`, `vite ^5.4`
- No additional dependencies needed for Canvas rendering (native HTML5 Canvas API).

### Configuration
- `vite.config.ts` proxies `/ws` and `/api` to `localhost:8085`. Vitest configured with jsdom environment.
- `tsconfig.json` uses strict mode with `noUnusedLocals` and `noUnusedParameters`.

---

## 2. Backend DTO Shapes

### SimulationStateDto (broadcast on `/topic/simulation` every 50ms)
```typescript
interface SimulationStateDto {
  tick: number;
  timestamp: number;         // System.currentTimeMillis()
  status: 'RUNNING' | 'PAUSED' | 'STOPPED';
  vehicles: VehicleDto[];
  stats: StatsDto;
}
```

### VehicleDto
```typescript
interface VehicleDto {
  id: string;
  laneId: string;
  position: number;   // metres from lane start
  speed: number;      // m/s
  x: number;          // pixel x coordinate (projected by backend)
  y: number;          // pixel y coordinate (projected by backend)
  angle: number;      // radians — road direction
}
```
**Key finding:** Backend already projects `x`, `y`, `angle` from road geometry using `LANE_WIDTH_PX = 14.0`. The frontend does NOT need to do coordinate mapping from simulation meters to pixels — it receives pixel coordinates directly.

### StatsDto
```typescript
interface StatsDto {
  vehicleCount: number;
  avgSpeed: number;      // m/s (display as km/h: multiply by 3.6)
  density: number;       // vehicles/km (across all lanes)
  throughput: number;    // vehicles despawned in last 60 seconds (currently 0.0 — "deferred to Phase 5")
}
```
**Note:** Throughput is hardcoded to `0.0` in `TickEmitter.buildSnapshot()`. Phase 5 must implement throughput tracking in the backend or accept placeholder.

### CommandDto (sent to `/app/command` via STOMP)
```typescript
interface CommandDto {
  type: 'START' | 'STOP' | 'PAUSE' | 'RESUME' | 'SET_SPAWN_RATE' | 'SET_SPEED_MULTIPLIER' | 'LOAD_MAP';
  spawnRate?: number;      // vehicles per second (for SET_SPAWN_RATE)
  multiplier?: number;     // e.g. 0.5 = half speed (for SET_SPEED_MULTIPLIER)
  mapId?: string;          // map identifier (for LOAD_MAP)
}
```

### SimulationStatusDto (GET /api/simulation/status)
```typescript
interface SimulationStatusDto {
  status: 'RUNNING' | 'PAUSED' | 'STOPPED';
  tick: number;
  vehicleCount: number;
  speedMultiplier: number;
  spawnRate: number;
  mapId: string | null;
}
```
Useful for initializing the controls panel (current speed multiplier, spawn rate, status).

---

## 3. Canvas Rendering Approach

### Two-layer canvas strategy
Use two `<canvas>` elements stacked via CSS `position: absolute`:

1. **Roads layer (static)** — Drawn once when map loads. Contains road fill (dark gray), lane markings (white dashed lines), road boundaries (solid lines). Re-drawn only on map change.
2. **Vehicles layer (dynamic)** — Cleared and redrawn every animation frame (~60fps). Contains vehicle rectangles with rotation.

Both canvases share the same dimensions. Use `useRef<HTMLCanvasElement>` for each.

### requestAnimationFrame loop
```
function renderLoop() {
  const now = performance.now();
  const alpha = computeAlpha(now);  // interpolation factor
  drawVehicles(vehiclesCtx, prevSnapshot, currSnapshot, alpha);
  requestAnimationFrame(renderLoop);
}
```
The loop runs independently of WebSocket ticks. It reads from the Zustand store (current + previous snapshot) and interpolates.

### Coordinate system
The backend already provides pixel coordinates (`x`, `y`) based on road geometry:
- Road `startX`, `startY`, `endX`, `endY` are in pixel space (e.g., straight-road.json has nodes at x=50,y=300 and x=850,y=300)
- Vehicle `x`, `y` are pixel positions along the road with lane offsets
- Vehicle `angle` is in radians

The canvas size should accommodate the map bounds. For the straight-road fixture, the road spans x=[50,850], y~=300, so a canvas of ~900x600 pixels works. A small margin/padding around the road network bounds is appropriate.

**Decision:** Since the backend does the projection, the frontend canvas just draws at the received (x, y) coordinates directly. No simulation-to-pixel conversion needed on the frontend side.

---

## 4. Position Interpolation

### Problem
Backend ticks at 20 Hz (every 50ms). Browser renders at 60 fps (~16.7ms per frame). Without interpolation, vehicles would jump positions every 3 frames, looking choppy.

### Solution: linear interpolation between snapshots
Store two consecutive snapshots in the Zustand store: `prevSnapshot` and `currSnapshot`. On each animation frame:

```
alpha = (performance.now() - currSnapshot.receivedAt) / TICK_INTERVAL_MS
alpha = clamp(alpha, 0, 1)

for each vehicle:
  prevVehicle = prevSnapshot.vehicles.find(v => v.id === vehicle.id)
  if (prevVehicle):
    renderX = prevVehicle.x + alpha * (vehicle.x - prevVehicle.x)
    renderY = prevVehicle.y + alpha * (vehicle.y - prevVehicle.y)
  else:
    renderX = vehicle.x  // newly spawned — no interpolation
    renderY = vehicle.y
```

### Edge cases
- **New vehicles** (spawned this tick): No previous position. Render at current position without interpolation.
- **Despawned vehicles** (in prev but not in curr): Stop rendering; they disappeared.
- **Alpha > 1**: Clamp to 1.0. Means we haven't received the next tick yet — extrapolation is risky, just hold at last known position.
- **Paused state**: Alpha stays at 1.0 (frozen at current positions).

### Performance optimization
Build a `Map<string, VehicleDto>` keyed by vehicle ID from the previous snapshot for O(1) lookup instead of O(n) find per vehicle.

### Tick interval
`TICK_INTERVAL_MS = 50` (20 Hz). With speed multiplier, the real-world tick interval stays 50ms (the backend `@Scheduled(fixedRate = 50)` is constant). The speed multiplier affects simulation time, not broadcast rate.

---

## 5. Road Geometry

### Backend model
```java
Road {
  startX, startY, endX, endY,  // pixel coordinates of road endpoints
  lanes: List<Lane>,            // index-ordered (lane 0 = rightmost)
  length                        // metres
}
```

### Lane layout
From `TickEmitter.projectVehicle()`:
```
LANE_WIDTH_PX = 14.0
laneOffset = (laneIndex - (laneCount - 1) / 2.0) * LANE_WIDTH_PX
```
Lanes are centered on the road axis. For a 3-lane road:
- Lane 0: offset = (0 - 1) * 14 = -14  (rightmost)
- Lane 1: offset = (1 - 1) * 14 = 0    (center)
- Lane 2: offset = (2 - 1) * 14 = +14  (leftmost)

### Drawing roads on the static canvas
The frontend needs road geometry to draw the static layer. Options:
1. **Fetch road data via REST** — Add a `GET /api/roads` endpoint (or extend `/api/simulation/status`) that returns road definitions with startX/startY/endX/endY/laneCount.
2. **Include road data in SimulationStateDto** — Add a `roads` field to the broadcast. Wasteful since roads are static.
3. **Hardcode for now** — Since there's only one map (straight-road), hardcode road drawing. Refactor in Phase 9.

**Recommendation:** Option 1 — add a REST endpoint `GET /api/roads` returning `List<RoadDto>` with `{ id, startX, startY, endX, endY, laneCount, length }`. This keeps the rendering data-driven and ready for Phase 9 map switching.

### Drawing specifics
For each road:
- Road width = `laneCount * LANE_WIDTH_PX`
- Road fill: dark gray rectangle from (startX, startY - roadWidth/2) to (endX, endY + roadWidth/2)
- Lane markings: white dashed lines at each lane boundary (perpendicular offset from road axis)
- Road boundaries: solid white lines at the road edges

For straight roads this is straightforward. For angled roads, use canvas `translate()` and `rotate()` based on road angle.

---

## 6. Vehicle Rendering

### Rectangle size
Backend `Vehicle.length = 4.5` metres. In the straight-road fixture, road is 800m displayed across 800px (node x: 50 to 850 = 800px for 800m = 1 px/m). So vehicle length ~ 4-5 pixels. Width ~ lane width * 0.7 = ~10 pixels.

Actual vehicle rendering size (in pixels):
- Length: ~8-10px (slightly exaggerated for visibility, since 4.5px is very small)
- Width: ~6-8px (fits within 14px lane width with margins)

### Color coding
Options for vehicle color:
- **By speed ratio** (speed / maxSpeed): Green = fast, Yellow = medium, Red = slow/stopped. This directly visualizes congestion.
- **Random per vehicle** — Less informative but visually distinct.

**Recommendation:** Speed-based gradient (green -> yellow -> red). Formula:
```
ratio = vehicle.speed / MAX_SPEED  // MAX_SPEED from config or ~33.3 m/s
hue = ratio * 120  // 0=red, 60=yellow, 120=green
color = `hsl(${hue}, 80%, 50%)`
```

### Rotation
Each `VehicleDto` has `angle` in radians. Use canvas `translate(x, y)` then `rotate(angle)` then `fillRect(-length/2, -width/2, length, width)`.

---

## 7. Controls Architecture

### STOMP command sending
The `useWebSocket` hook returns a `clientRef`. To send commands:
```typescript
clientRef.current?.publish({
  destination: '/app/command',
  body: JSON.stringify({ type: 'START' })
});
```

The `clientRef` must be accessible from the `ControlsPanel`. Options:
1. Pass it as a prop from App -> ControlsPanel.
2. Store the STOMP client in Zustand store.
3. Create a `useSendCommand` hook that accesses the client.

**Recommendation:** Store a `sendCommand` function in the Zustand store, set by `useWebSocket` on connect. This decouples the controls from WebSocket implementation details.

### Controls mapping

| UI Control | Command Type | Payload |
|---|---|---|
| Start button | `START` | none |
| Pause button | `PAUSE` | none |
| Resume button | `RESUME` | none |
| Stop button | `STOP` | none |
| Speed slider (0.5x-5x) | `SET_SPEED_MULTIPLIER` | `{ multiplier: number }` |
| Spawn rate slider | `SET_SPAWN_RATE` | `{ spawnRate: number }` |
| Max speed input | N/A — not yet in backend | Needs new command |

**Issue:** CTRL-04 requires "global maximum vehicle speed" control, but there is no `SetMaxSpeed` command in the backend's `SimulationCommand` sealed interface. This must be added as a new command type. Alternatively, this could be deferred — max speed affects newly spawned vehicles' `v0` parameter, not existing ones.

### Button state logic
- STOPPED: Show "Start" (enabled), "Pause" (disabled), "Stop" (disabled)
- RUNNING: Show "Start" (disabled), "Pause" (enabled), "Stop" (enabled)
- PAUSED: Show "Resume" (enabled), "Pause" (disabled), "Stop" (enabled)

Read current `status` from the Zustand store (populated by SimulationStateDto).

### Slider debouncing
Speed and spawn rate sliders should debounce command sending (e.g., 200ms) to avoid flooding the command queue during drag.

---

## 8. Statistics Calculation

### Data source
All stats come pre-computed from the backend in `StatsDto`. The frontend just displays them.

### Display formatting

| Stat | Source field | Display format | Conversion |
|---|---|---|---|
| Average speed | `stats.avgSpeed` (m/s) | "XX.X km/h" | multiply by 3.6 |
| Vehicle count | `stats.vehicleCount` | "XX vehicles" | none |
| Density | `stats.density` (veh/km) | "XX.X veh/km" | none |
| Throughput | `stats.throughput` (veh/60s) | "XX veh/min" | none |

### Backend computation (from TickEmitter.buildSnapshot)
- `avgSpeed` = sum of all vehicle speeds / vehicle count
- `density` = vehicleCount / (totalRoadLength / 1000.0) — vehicles per km across all lanes
- `throughput` = currently hardcoded to 0.0 — **needs backend work** to track despawn count over a rolling 60-second window

### Throughput implementation
The `VehicleSpawner.despawnVehicles()` method removes vehicles past the road end. To track throughput:
- Maintain a `Deque<Long>` of despawn timestamps
- On each despawn, add `System.currentTimeMillis()` to the deque
- In `buildSnapshot()`, count entries within the last 60,000ms, evict older ones
- This gives vehicles/minute throughput

---

## 9. Layout Structure

### App layout
```
+-----------------------------------------------+
|  Traffic Simulator                    [title]  |
+-----------------------------------------------+
|                              |  Controls Panel |
|                              |  [Start][Pause] |
|       Canvas Area            |  [Stop]         |
|    (roads + vehicles)        |  Speed: [====]  |
|                              |  Spawn: [====]  |
|                              |-----------------|
|                              |  Stats Panel    |
|                              |  Avg speed: XX  |
|                              |  Density: XX    |
|                              |  Throughput: XX  |
+-----------------------------------------------+
```

- **Main area (left, ~75%):** Canvas with roads and vehicles. Two stacked `<canvas>` elements.
- **Sidebar (right, ~25%):** Controls panel (top) + Stats panel (bottom).
- Use CSS Flexbox or Grid. No CSS framework needed — simple layout.

### Component tree
```
App
  ├── SimulationCanvas (two-layer canvas with rAF loop)
  ├── ControlsPanel (buttons, sliders)
  └── StatsPanel (read-only stats display)
```

### Responsive behavior
Not a priority — desktop-first. Fixed canvas size matching map bounds. Sidebar has fixed width (~250px).

---

## 10. Test Strategy

### Component tests (Vitest + @testing-library/react)
- **ControlsPanel** — Renders correct buttons based on status. Click handlers call sendCommand with correct CommandDto. Slider changes emit debounced commands.
- **StatsPanel** — Renders formatted stats from store. Handles zero/null values gracefully.

### Canvas rendering tests
Canvas is hard to unit-test meaningfully. Strategies:
- **CanvasRenderer unit tests** — Test `drawVehicles` and `drawRoads` functions by mocking `CanvasRenderingContext2D` and asserting method calls (e.g., `fillRect` called N times, `rotate` called with correct angle).
- **Interpolation logic** — Pure function, easy to test: given prev/curr snapshots and alpha, verify interpolated positions.

### Store tests
- Test that `setTick` correctly updates `prevSnapshot` and `currSnapshot`.
- Test that `sendCommand` function is wired correctly.

### Integration smoke test
- Mount `App`, verify canvas elements render.
- Verify controls panel appears with correct initial button states.

### What NOT to test
- Visual appearance (pixel-perfect canvas output) — too brittle.
- WebSocket integration — already tested in Phase 1.
- Backend DTO serialization — already tested in Phase 4.

---

## Key Decisions for Planning

1. **No coordinate conversion needed on frontend** — Backend provides pixel (x, y, angle) in VehicleDto.
2. **Road geometry REST endpoint needed** — `GET /api/roads` to draw static road layer.
3. **Throughput tracking needs backend work** — Currently hardcoded to 0.0.
4. **CTRL-04 (max speed) needs new backend command** — `SetMaxSpeed` not in SimulationCommand yet.
5. **sendCommand via Zustand** — Store a function reference, not the STOMP client.
6. **Vehicle color = speed-based HSL gradient** — Green (fast) to red (stopped).
7. **Interpolation uses Map<id, VehicleDto>** for O(1) prev-vehicle lookup.
8. **Canvas size derived from road bounds** — With padding. ~900x600 for straight-road fixture.
9. **Slider debounce at ~200ms** — Prevents command flooding.
10. **Throughput:** implement rolling 60s despawn counter in backend `TickEmitter`.
